package org.miracum.streams.fhirtoserver;

import jakarta.validation.constraints.NotEmpty;
import java.net.URL;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "s3")
@Validated
public record S3Config(
    boolean enabled,
    URL endpointUrl,
    Optional<String> accessKey,
    Optional<String> secretKey,
    @NotEmpty String bucketName,
    Optional<String> objectNamePrefix) {}
