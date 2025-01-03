package org.miracum.streams.fhirtoserver;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.util.BundleUtil;
import io.micrometer.common.lang.Nullable;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.io.output.StringBuilderWriter;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

@Service
public class S3BundleStore {
  private static final Logger LOG = LoggerFactory.getLogger(S3BundleStore.class);
  private FhirBundleMerger merger;
  private FhirContext fhirContext;
  private FhirBundleMergerConfig mergerConfig;
  private S3Config config;
  private S3Client s3Client;

  public S3BundleStore(
      @Nullable S3Client s3Client,
      S3Config config,
      FhirBundleMerger merger,
      FhirContext fhirContext,
      FhirBundleMergerConfig mergerConfig) {
    this.s3Client = s3Client;
    this.config = config;
    this.merger = merger;
    this.fhirContext = fhirContext;
    this.mergerConfig = mergerConfig;
  }

  public Void storeBatch(List<Bundle> bundles) throws DataFormatException, IOException {
    // start by merging all those bundles split into POST/PUT and DELETE bundles
    var mergedBundle =
        merger.mergeSeperateDeleteBundles(
            bundles, mergerConfig.entryUniquenessFhirpathExpression());

    // extract all POST/PUT bundle entries (mergedBundle.deleteBundle() contains the DELETE entries)
    var resources = BundleUtil.toListOfResources(fhirContext, mergedBundle.bundle());

    var grouped = resources.stream().collect(Collectors.groupingBy(IBaseResource::fhirType));

    LOG.debug(
        "Storing {} resources in {} buckets ({})",
        resources.size(),
        grouped.size(),
        grouped.keySet());

    var parser = fhirContext.newJsonParser();

    for (var entry : grouped.entrySet()) {
      try (var stringWriter = new StringBuilderWriter()) {
        var resourceType = entry.getKey();
        boolean isFirstResource = true;
        for (var resource : entry.getValue()) {
          if (!(isFirstResource)) {
            stringWriter.write("\n");
          }
          isFirstResource = false;

          parser.encodeResourceToWriter(resource, stringWriter);
        }

        var prefix = config.objectNamePrefix().orElse("");

        var objectKey =
            String.format(
                "%s%s/bundle-%s.ndjson", prefix, resourceType, Instant.now().toEpochMilli());

        LOG.debug(
            "Storing {} resources of type {} as object {}",
            entry.getValue().size(),
            entry.getKey(),
            objectKey);

        var body = RequestBody.fromString(stringWriter.toString());
        s3Client.putObject(
            request ->
                request
                    .bucket(config.bucketName())
                    .key(objectKey)
                    .contentType(Constants.CT_FHIR_NDJSON),
            body);
      }
    }

    // now, deal with the DELETE entries

    // extract the bundles grouped by the resource type of the DELETE request
    var groupedDeletes =
        mergedBundle.deletBundle().getEntry().stream()
            .collect(Collectors.groupingBy(e -> e.getRequest().getUrl().split("/")[0]));

    LOG.debug(
        "Storing {} delete requests in buckets ({})",
        groupedDeletes.size(),
        groupedDeletes.keySet());

    // each entry is one resource type
    for (var entry : groupedDeletes.entrySet()) {
      var deleteBundle = new Bundle();
      deleteBundle.setType(BundleType.TRANSACTION);

      // turns all entries of the merged bundles into a single one
      // per resource type
      for (var bundleEntry : entry.getValue()) {
        deleteBundle.addEntry().setRequest(bundleEntry.getRequest());
      }

      var resourceType = entry.getKey();

      var deleteBundleJson = parser.encodeResourceToString(deleteBundle);

      var prefix = config.objectNamePrefix().orElse("");

      var objectKey =
          String.format(
              "%s%s/_delete/bundle-%s.json", prefix, resourceType, Instant.now().toEpochMilli());

      LOG.debug(
          "Storing delete bundle with {} entries as object {}",
          deleteBundle.getEntry().size(),
          objectKey);

      var body = RequestBody.fromString(deleteBundleJson);
      s3Client.putObject(
          request ->
              request
                  .bucket(config.bucketName())
                  .key(objectKey)
                  .contentType(Constants.CT_FHIR_JSON_NEW),
          body);
    }
    return null;
  }
}
