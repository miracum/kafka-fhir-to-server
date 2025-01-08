package org.miracum.streams.fhirtoserver;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.util.BundleUtil;
import io.micrometer.common.lang.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.io.output.StringBuilderWriter;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.MessageHeaders;
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

  public Void storeBatch(List<Bundle> bundles, MessageHeaders headers)
      throws DataFormatException, IOException {
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

    for (var entry : grouped.entrySet()) {
      var resourceType = entry.getKey();
      var ndjson = listOfResourcesToNdjson(entry.getValue());

      var prefix = config.objectNamePrefix().orElse("");
      var startTimestamp =
          (Long) headers.get(KafkaHeaders.RECEIVED_TIMESTAMP, ArrayList.class).getFirst();
      // in Spring Kafka all messages in a batch are from the same partition
      var partition =
          (Integer) headers.get(KafkaHeaders.RECEIVED_PARTITION, ArrayList.class).getFirst();
      var startOffset = (Long) headers.get(KafkaHeaders.OFFSET, ArrayList.class).getFirst();

      var objectKey =
          String.format(
              "%s%s/%s-%s-%s.ndjson", prefix, resourceType, startTimestamp, partition, startOffset);

      LOG.debug(
          "Storing {} resources of type {} as object {}",
          entry.getValue().size(),
          entry.getKey(),
          objectKey);

      var body = RequestBody.fromString(ndjson);
      var metadata =
          Map.of(
              "kafka-timestamp",
              startTimestamp.toString(),
              "kafka-partition",
              partition.toString(),
              "kafka-offset",
              startOffset.toString(),
              "kafka-topic",
              (String) headers.get(KafkaHeaders.RECEIVED_TOPIC, ArrayList.class).getFirst(),
              "kafka-group-id",
              headers.getOrDefault(KafkaHeaders.GROUP_ID, "").toString());

      s3Client.putObject(
          request ->
              request
                  .bucket(config.bucketName())
                  .key(objectKey)
                  .metadata(metadata)
                  .contentType(Constants.CT_FHIR_NDJSON),
          body);
    }

    // now, deal with the DELETE entries

    // extract the bundles grouped by the resource type of the DELETE request
    var groupedDeletes =
        mergedBundle.deletBundle().getEntry().stream()
            .collect(Collectors.groupingBy(e -> e.getRequest().getUrl().split("/")[0]));

    storeDeleteBundles(groupedDeletes, headers);

    return null;
  }

  private String listOfResourcesToNdjson(List<IBaseResource> resources)
      throws DataFormatException, IOException {
    var parser = fhirContext.newJsonParser();
    boolean isFirstResource = true;
    try (var stringWriter = new StringBuilderWriter()) {
      for (var resource : resources) {
        if (!(isFirstResource)) {
          stringWriter.write("\n");
        }
        isFirstResource = false;

        parser.encodeResourceToWriter(resource, stringWriter);
      }
      return stringWriter.toString();
    }
  }

  private void storeDeleteBundles(
      Map<String, List<BundleEntryComponent>> groupedDeletes, MessageHeaders headers) {
    LOG.debug(
        "Storing {} delete requests in buckets ({})",
        groupedDeletes.size(),
        groupedDeletes.keySet());

    var parser = fhirContext.newJsonParser();

    // each entry is one resource type
    for (var entry : groupedDeletes.entrySet()) {
      LOG.debug("Processing resource type {}", entry.getKey());

      var deleteBundle = new Bundle();
      deleteBundle.setType(BundleType.TRANSACTION);

      // turns all entries of the merged bundles into a single one
      // per resource type
      // Note that this is not an NDJSON but instead a regular bundle
      for (var bundleEntry : entry.getValue()) {
        deleteBundle.addEntry().setRequest(bundleEntry.getRequest());
      }

      var resourceType = entry.getKey();

      var deleteBundleJson = parser.encodeResourceToString(deleteBundle);

      var prefix = config.objectNamePrefix().orElse("");

      var startTimestamp =
          (Long) headers.get(KafkaHeaders.RECEIVED_TIMESTAMP, ArrayList.class).getFirst();
      // in Spring Kafka all messages in a batch are from the same partition
      var partition = (Long) headers.get(KafkaHeaders.PARTITION, ArrayList.class).getFirst();
      var startOffset = (Long) headers.get(KafkaHeaders.OFFSET, ArrayList.class).getFirst();

      var objectKey =
          String.format(
              "%s%s/_delete/%s-%s-%s.ndjson",
              prefix, resourceType, startTimestamp, partition, startOffset);

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
  }

  public Void storeSingleBundle(Bundle bundle, MessageHeaders messageHeaders)
      throws DataFormatException, IOException {
    var mergedBundle =
        merger.mergeSeperateDeleteBundles(
            List.of(bundle), mergerConfig.entryUniquenessFhirpathExpression());

    // extract all POST/PUT bundle entries (mergedBundle.deleteBundle() contains the DELETE entries)
    var resources = BundleUtil.toListOfResources(fhirContext, mergedBundle.bundle());

    var grouped = resources.stream().collect(Collectors.groupingBy(IBaseResource::fhirType));

    for (var entry : grouped.entrySet()) {
      var resourceType = entry.getKey();
      var ndjson = listOfResourcesToNdjson(entry.getValue());

      var prefix = config.objectNamePrefix().orElse("");

      var timestamp = messageHeaders.get(KafkaHeaders.RECEIVED_TIMESTAMP);
      var partition = messageHeaders.get(KafkaHeaders.RECEIVED_PARTITION);
      var offset = messageHeaders.get(KafkaHeaders.OFFSET);

      var objectKey =
          String.format("%s%s/%s-%s-%s.ndjson", prefix, resourceType, timestamp, partition, offset);

      LOG.debug(
          "Storing {} resources of type {} as object {}",
          entry.getValue().size(),
          entry.getKey(),
          objectKey);

      var body = RequestBody.fromString(ndjson);
      var metadata =
          Map.of(
              "kafka-timestamp",
              timestamp.toString(),
              "kafka-partition",
              partition.toString(),
              "kafka-offset",
              offset.toString(),
              "kafka-topic",
              messageHeaders.getOrDefault(KafkaHeaders.RECEIVED_TOPIC, "").toString(),
              "kafka-group-id",
              messageHeaders.getOrDefault(KafkaHeaders.GROUP_ID, "").toString());

      s3Client.putObject(
          request ->
              request
                  .bucket(config.bucketName())
                  .key(objectKey)
                  .metadata(metadata)
                  .contentType(Constants.CT_FHIR_NDJSON),
          body);
    }

    // TODO: DELETE bundle entries are not handled here yet

    return null;
  }
}
