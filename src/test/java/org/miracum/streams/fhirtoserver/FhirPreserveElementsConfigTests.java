package org.miracum.streams.fhirtoserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class FhirPreserveElementsConfigTests {
  private static FhirPreserveElementsConfig bind(Binder binder) {
    return binder.bindOrCreate("fhir", FhirPreserveElementsConfig.class);
  }

  @Test
  void bind_fromCommaSeparatedEnvironmentVariable_shouldSplitIntoList() {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "test-env",
                Map.of("FHIR_PRESERVE_ELEMENTS", "ResearchStudy.enrollment, Patient.link")));

    var config = bind(new Binder(ConfigurationPropertySources.get(environment)));

    assertThat(config.preserveElements())
        .containsExactly("ResearchStudy.enrollment", "Patient.link");
  }

  @Test
  void bind_fromYamlList_shouldBindAllItems() {
    var source =
        new MapConfigurationPropertySource(
            Map.of(
                "fhir.preserve-elements[0]", "ResearchStudy.enrollment",
                "fhir.preserve-elements[1]", "Patient.link"));

    var config = bind(new Binder(source));

    assertThat(config.preserveElements())
        .containsExactly("ResearchStudy.enrollment", "Patient.link");
  }

  @Test
  void bind_whenUnset_shouldBeEmpty() {
    var config = bind(new Binder(new MapConfigurationPropertySource()));

    assertThat(config.preserveElements()).isEmpty();
  }
}
