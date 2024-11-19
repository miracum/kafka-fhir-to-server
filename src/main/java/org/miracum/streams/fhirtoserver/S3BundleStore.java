package org.miracum.streams.fhirtoserver;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.util.BundleUtil;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.io.output.StringBuilderWriter;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class S3BundleStore {
  private static final Logger LOG = LoggerFactory.getLogger(S3BundleStore.class);
  private MinioClient minioClient;
  private FhirBundleMerger merger;
  private FhirContext fhirContext;
  private FhirBundleMergerConfig mergerConfig;
  private S3Config config;

  public S3BundleStore(
      @Nullable MinioClient minioClient,
      S3Config config,
      FhirBundleMerger merger,
      FhirContext fhirContext,
      FhirBundleMergerConfig mergerConfig) {
    this.minioClient = minioClient;
    this.config = config;
    this.merger = merger;
    this.fhirContext = fhirContext;
    this.mergerConfig = mergerConfig;
  }

  public Void storeBatch(List<Bundle> bundles)
      throws DataFormatException,
          IOException,
          InvalidKeyException,
          ErrorResponseException,
          InsufficientDataException,
          InternalException,
          InvalidResponseException,
          NoSuchAlgorithmException,
          ServerException,
          XmlParserException {
    // start by merging all those bundles
    var mergedBundle =
        merger.mergeSeperateDeleteBundles(
            bundles, mergerConfig.entryUniquenessFhirpathExpression());

    // extract all POST/PUT bundle entries
    var resources = BundleUtil.toListOfResources(fhirContext, mergedBundle.bundle());

    var grouped = resources.stream().collect(Collectors.groupingBy(IBaseResource::fhirType));

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

        var bais =
            new ByteArrayInputStream(stringWriter.toString().getBytes(StandardCharsets.UTF_8));
        var prefix = config.objectNamePrefix().orElse("");
        var putArgs =
            PutObjectArgs.builder()
                .bucket(config.bucketName())
                .object(
                    String.format(
                        "%s%s/bundle-%s.ndjson",
                        prefix, resourceType, Instant.now().toEpochMilli()))
                .stream(bais, bais.available(), 0)
                .contentType(Constants.CT_FHIR_NDJSON)
                .build();

        minioClient.putObject(putArgs);
      }
    }

    // extract the bundles grouped by the resource type of the DELETE request
    var groupedDeletes =
        mergedBundle.deletBundle().getEntry().stream()
            .collect(Collectors.groupingBy(e -> e.getRequest().getUrl().split("/")[0]));

    for (var entry : groupedDeletes.entrySet()) {
      var deleteBundle = new Bundle();
      deleteBundle.setType(BundleType.BATCH);

      // turns all entries off the merged bundles into a single one
      // per resource type
      for (var bundleEntry : entry.getValue()) {
        deleteBundle.addEntry().setRequest(bundleEntry.getRequest());
      }

      try (var stringWriter = new StringBuilderWriter()) {
        var resourceType = entry.getKey();

        parser.encodeResourceToWriter(deleteBundle, stringWriter);

        var bais =
            new ByteArrayInputStream(stringWriter.toString().getBytes(StandardCharsets.UTF_8));
        var prefix = config.objectNamePrefix().orElse("");
        var putArgs =
            PutObjectArgs.builder()
                .bucket(config.bucketName())
                .object(
                    String.format(
                        "%s%s/_delete/bundle-%s.json",
                        prefix, resourceType, Instant.now().toEpochMilli()))
                .stream(bais, bais.available(), 0)
                .contentType(Constants.CT_FHIR_NDJSON)
                .build();

        minioClient.putObject(putArgs);
      }
    }
    return null;
  }
}
