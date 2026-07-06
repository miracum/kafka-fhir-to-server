package org.miracum.streams.fhirtoserver;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "fhir.auth")
@Validated
public record FhirAuthConfig(Basic basic, OAuth2 oauth2) {
  public record Basic(boolean enabled, String username, String password) {}

  public record OAuth2(
      boolean enabled,
      String tokenUrl,
      String clientId,
      String clientSecret,
      @Nullable String scope) {}
}
