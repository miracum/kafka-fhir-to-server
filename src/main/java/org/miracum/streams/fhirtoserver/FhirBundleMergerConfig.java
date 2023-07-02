package org.miracum.streams.fhirtoserver;

import jakarta.validation.constraints.NotEmpty;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "fhir.merge-batches-into-single-bundle")
@Validated
public record FhirBundleMergerConfig(
    boolean enabled,
    @NotEmpty String entryUniquenessFhirpathExpression,
    Optional<Integer> bundleMaxSize) {}
