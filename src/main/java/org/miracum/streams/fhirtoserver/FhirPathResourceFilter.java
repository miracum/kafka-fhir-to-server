package org.miracum.streams.fhirtoserver;

import ca.uhn.fhir.fhirpath.IFhirPath;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.PrimitiveType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FhirPathResourceFilter {
  private final IFhirPath fhirPath;

  @Autowired
  public FhirPathResourceFilter(IFhirPath fhirPath) {
    this.fhirPath = fhirPath;
  }

  /**
   * Evaluates the given FHIR Path expression against the given resource. If the first result
   * evaluates to true, then true is returned.
   *
   * @param resource the FHIR resource to evaluate against
   * @param pathExpression the FHIR path expression that should result in a FHIR boolean type
   * @return true if the first result of evaluating the expression yields true, false if it does
   *     not.
   */
  public boolean matches(IBaseResource resource, String pathExpression) {
    var result = fhirPath.evaluateFirst(resource, pathExpression, BooleanType.class);

    return result.map(PrimitiveType::getValue).orElse(false);
  }
}
