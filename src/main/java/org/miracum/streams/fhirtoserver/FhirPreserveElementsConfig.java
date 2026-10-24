package org.miracum.streams.fhirtoserver;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param preserveElements "ResourceType.element" paths to keep from the existing resource on the
 *     server, see {@link ExistingElementsPreserver}. Bound from either a YAML list or a
 *     comma-separated string, e.g. FHIR_PRESERVE_ELEMENTS="ResearchStudy.enrollment,Patient.link".
 */
@ConfigurationProperties(prefix = "fhir")
public record FhirPreserveElementsConfig(List<String> preserveElements) {
  public FhirPreserveElementsConfig {
    preserveElements = preserveElements == null ? List.of() : List.copyOf(preserveElements);
  }
}
