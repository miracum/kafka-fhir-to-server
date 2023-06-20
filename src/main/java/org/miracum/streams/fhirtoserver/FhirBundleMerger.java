package org.miracum.streams.fhirtoserver;

import static net.logstash.logback.argument.StructuredArguments.kv;

import ca.uhn.fhir.fhirpath.IFhirPath;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FhirBundleMerger {
  private static final Logger LOG = LoggerFactory.getLogger(FhirBundleMerger.class);
  private final IFhirPath fhirPath;

  public FhirBundleMerger(IFhirPath fhirPath) {
    this.fhirPath = fhirPath;
  }

  public Bundle merge(List<Bundle> bundles) {
    return this.merge(bundles, "fullUrl.toString()");
  }

  public Bundle merge(List<Bundle> bundles, String fhirPathExpression) {
    if (bundles.isEmpty()) {
      throw new IllegalArgumentException("bundles parameter cannot be empty");
    }

    if (bundles.size() == 1) {
      return bundles.get(0);
    }

    // iterate through the list of bundles. The first entry in the list is the
    // oldest bundle, the last entry is the most recent. We only care about
    // the most recent unique resource when merging bundles. We use a
    // dictionary to automatically replace older entries with newer ones.
    var setOfUniqueBundleEntries = new HashMap<String, BundleEntryComponent>();
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
          setOfUniqueBundleEntries.put(entryIdentifier.get().asStringValue(), entryComponent);
        }
      }
    }

    var resultBundle =
        new Bundle()
            .setType(bundles.get(0).getType())
            .setEntry(setOfUniqueBundleEntries.entrySet().stream().map(Entry::getValue).toList());

    LOG.debug(
        "{} input bundles merged to one bundle of {} entries",
        kv("numInputBundles", bundles.size()),
        kv("numMergedEntries", resultBundle.getEntry().size()));

    return resultBundle;
  }
}
