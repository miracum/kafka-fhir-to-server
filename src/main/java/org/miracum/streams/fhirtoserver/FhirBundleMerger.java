package org.miracum.streams.fhirtoserver;

import static net.logstash.logback.argument.StructuredArguments.kv;

import ca.uhn.fhir.fhirpath.IFhirPath;
import com.google.common.collect.Lists;
import java.util.HashMap;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FhirBundleMerger {
  private static final Logger LOG = LoggerFactory.getLogger(FhirBundleMerger.class);
  private final IFhirPath fhirPath;

  public record MergeResult(Bundle bundle, Bundle deletBundle) {}

  public FhirBundleMerger(IFhirPath fhirPath) {
    this.fhirPath = fhirPath;
  }

  public Bundle merge(List<Bundle> bundles) {
    return this.merge(bundles, "request.url.toString()");
  }

  public Bundle merge(List<Bundle> bundles, String fhirPathExpression) {
    return mergeSeperateDeleteBundles(bundles, fhirPathExpression).bundle();
  }

  public MergeResult mergeSeperateDeleteBundles(List<Bundle> bundles, String fhirPathExpression) {
    if (bundles.isEmpty()) {
      throw new IllegalArgumentException("bundles parameter cannot be empty");
    }

    // iterate through the list of bundles. The first entry in the list is the
    // oldest bundle, the last entry is the most recent. We only care about
    // the most recent unique resource when merging bundles. We use a
    // dictionary to automatically replace older entries with newer ones.
    var setOfUniqueBundleEntries = new HashMap<String, BundleEntryComponent>();
    var setOfUniqueDeleteBundleEntries = new HashMap<String, BundleEntryComponent>();

    for (var bundle : bundles) {
      for (var entryComponent : bundle.getEntry()) {
        var entryIdentifier =
            fhirPath.evaluateFirst(entryComponent, fhirPathExpression, StringType.class);
        if (entryIdentifier.isEmpty()) {
          LOG.warn(
              "Expression '{}' didn't evaluate to any result for the entry component in bundle {}. "
                  + "Not included in merged bundle.",
              kv("fhirPathExpression", fhirPathExpression),
              kv("bundleId", bundle.getIdElement().toVersionless()));
        } else {
          if (entryComponent.getRequest().getMethod() == HTTPVerb.DELETE) {
            setOfUniqueDeleteBundleEntries.put(
                entryIdentifier.get().asStringValue(), entryComponent);
          } else {
            setOfUniqueBundleEntries.put(entryIdentifier.get().asStringValue(), entryComponent);
          }
        }
      }
    }

    var resultBundle =
        new Bundle()
            .setType(bundles.get(0).getType())
            .setEntry(setOfUniqueBundleEntries.values().stream().toList());

    var deleteBundle =
        new Bundle()
            .setType(bundles.get(0).getType())
            .setEntry(setOfUniqueDeleteBundleEntries.values().stream().toList());

    LOG.debug(
        "{} input bundles merged to one bundle of {} entries and one DELETE bundle consisting of {} entries",
        kv("numInputBundles", bundles.size()),
        kv("numMergedEntries", resultBundle.getEntry().size()),
        kv("numMergedDeleteEntries", deleteBundle.getEntry().size()));

    return new MergeResult(resultBundle, deleteBundle);
  }

  public List<Bundle> partitionBundle(final Bundle bundle, int maxPartitionSize) {
    @SuppressWarnings("null")
    var partitions = Lists.partition(bundle.getEntry(), maxPartitionSize);

    return partitions.stream()
        .map(
            partition -> {
              var partitionBundle = new Bundle();
              partitionBundle.setType(bundle.getType());
              partitionBundle.setEntry(partition);
              return partitionBundle;
            })
        .toList();
  }
}
