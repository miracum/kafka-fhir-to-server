package org.miracum.streams.fhirtoserver;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Paths;
import org.hl7.fhir.r4.hapi.fluentpath.FhirPathR4;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

class FhirPathResourceFilterTest {

  private final FhirContext fhirContext = FhirContext.forR4();
  private final FhirPathResourceFilter sut;
  private final IParser parser = fhirContext.newJsonParser();

  public FhirPathResourceFilterTest() {
    sut = new FhirPathResourceFilter(new FhirPathR4(fhirContext));
  }

  @ParameterizedTest
  @CsvFileSource(resources = "/test-expressions.csv", numLinesToSkip = 1)
  void matches_withGivenResource_shouldReturnExpected(
      String resourceFile, String expression, boolean expectedResult) throws FileNotFoundException {
    var resourceDir = "src/test/resources/fhir";
    var file = Paths.get(resourceDir, resourceFile).toFile();
    var resource = parser.parseResource(new FileInputStream(file));

    var result = sut.matches(resource, expression);

    assertThat(result).isEqualTo(expectedResult);
  }
}
