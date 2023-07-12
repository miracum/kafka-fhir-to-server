package org.miracum.streams.fhirtoserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.uhn.fhir.context.FhirContext;
import java.util.ArrayList;
import java.util.List;
import org.hl7.fhir.r4.hapi.fluentpath.FhirPathR4;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class FhirBundleMergerTests {
  private final FhirContext fhirContext = FhirContext.forR4();
  private final FhirBundleMerger sut;

  public FhirBundleMergerTests() {
    sut = new FhirBundleMerger(new FhirPathR4(fhirContext));
  }

  @Test
  void merge_whenGivenEmptyList_shouldThrowIllegalArgumentException() {
    var bundles = new ArrayList<Bundle>();

    assertThrows(IllegalArgumentException.class, () -> sut.merge(bundles));
  }

  @Test
  void merge_whenGivenListWithSingleBundle_shouldReturnThatBundle() {
    var bundle = new Bundle().setType(BundleType.TRANSACTION);
    bundle.setId("1");

    var bundles = List.of(bundle);

    var result = sut.merge(bundles);

    assertThat(result).isEqualTo(bundle);
  }

  @Test
  void
      merge_whenGivenListWithTwoBundlesWithAResourceWithTheSameRequestUrl_shouldReturnBundleWithOnlyTheMostRecentResource() {

    var patient1 = new Patient().setName(List.of(new HumanName().setFamily("older"))).setId("p");
    var patient2 = new Patient().setName(List.of(new HumanName().setFamily("newer"))).setId("p");

    var bundle1 = new Bundle().setType(BundleType.TRANSACTION);
    bundle1
        .addEntry()
        .setResource(patient1)
        .setFullUrl("Patient/p")
        .getRequest()
        .setUrl("Patient/p")
        .setMethod(HTTPVerb.PUT);
    bundle1.setId("1");

    var bundle2 = new Bundle().setType(BundleType.TRANSACTION);
    bundle2
        .addEntry()
        .setResource(patient2)
        .setFullUrl("Patient/p")
        .getRequest()
        .setUrl("Patient/p")
        .setMethod(HTTPVerb.PUT);
    bundle2.setId("2");

    var bundles = List.of(bundle1, bundle2);

    var result = sut.merge(bundles);

    assertThat(result.getEntry()).hasSize(1);
    var resultBundleEntry = result.getEntry().get(0);

    assertThat(resultBundleEntry.getResource().fhirType()).isEqualTo("Patient");
    assertThat(resultBundleEntry.getRequest().getUrl()).isEqualTo("Patient/p");
    assertThat(resultBundleEntry.getRequest().getMethod()).isEqualTo(HTTPVerb.PUT);
  }

  @ParameterizedTest
  @CsvSource({"10,5", "60,3", "100,10"})
  void partitionBundle_withBundleExceedingSize_shouldBeSplitIntoMultipleBundles(
      int inputBundleSize, int partitionSize) {

    var inputBundle = new Bundle();
    inputBundle.setType(BundleType.TRANSACTION);

    for (int i = 0; i < inputBundleSize; i++) {
      var patient = new Patient();
      patient.setId("p-" + i);

      inputBundle
          .addEntry()
          .setResource(patient)
          .setFullUrl("Patient/p-" + i)
          .getRequest()
          .setMethod(HTTPVerb.PUT)
          .setUrl("Patient/p-" + i);
    }

    var partitioned = sut.partitionBundle(inputBundle, partitionSize);

    assertThat(partitioned)
        .allSatisfy(bundle -> assertThat(bundle.getEntry()).hasSize(partitionSize));
  }
}
