package org.miracum.streams.fhirtoserver;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.hapi.fluentpath.FhirPathR4;
import org.junit.jupiter.api.Test;

class SendToServerProcessorTests {
  private final SendToServerProcessor sut;

  public SendToServerProcessorTests() {
    var fhirContext = FhirContext.forR4();
    var filter = new FhirPathResourceFilter(new FhirPathR4(fhirContext));
    var serverUrl = "http://localhost/fhir";
    sut =
        new SendToServerProcessor(
            fhirContext.newRestfulGenericClient(serverUrl), "", null, false, "", filter, null);
  }

  @Test
  void process_withNullResource_shouldNotThrow() {
    assertDoesNotThrow(() -> sut.sink().accept(null));
  }
}
