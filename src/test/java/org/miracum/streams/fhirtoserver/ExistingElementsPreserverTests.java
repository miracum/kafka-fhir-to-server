package org.miracum.streams.fhirtoserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.ITransaction;
import ca.uhn.fhir.rest.gclient.ITransactionTyped;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResearchStudy;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class ExistingElementsPreserverTests {
  private final FhirContext fhirContext = FhirContext.forR4();
  private IGenericClient client;
  private ITransactionTyped<Bundle> transactionTyped;
  private ArgumentCaptor<Bundle> sentBundles;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    client = mock(IGenericClient.class);
    var transaction = mock(ITransaction.class);
    transactionTyped = mock(ITransactionTyped.class);
    sentBundles = ArgumentCaptor.forClass(Bundle.class);

    when(client.getFhirContext()).thenReturn(fhirContext);
    when(client.transaction()).thenReturn(transaction);
    when(transaction.withBundle(sentBundles.capture())).thenReturn(transactionTyped);
  }

  private ExistingElementsPreserver createSut(String... preserveElements) {
    return new ExistingElementsPreserver(
        client, new FhirPreserveElementsConfig(List.of(preserveElements)));
  }

  private static Bundle createPutBundle(String url, Resource resource) {
    var bundle = new Bundle().setType(BundleType.TRANSACTION);
    bundle.addEntry().setResource(resource).getRequest().setMethod(HTTPVerb.PUT).setUrl(url);
    return bundle;
  }

  private static ResearchStudy createExistingStudy() {
    var study = new ResearchStudy().setTitle("old title").addEnrollment(new Reference("Group/g1"));
    study.setId("rs1");
    study.getMeta().setVersionId("3");
    return study;
  }

  private static Bundle createReadResponse(String status, Resource resource) {
    var response = new Bundle().setType(BundleType.BATCHRESPONSE);
    response.addEntry().setResource(resource).getResponse().setStatus(status);
    return response;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ResearchStudy",
        "ResearchStudy.",
        ".enrollment",
        "ResearchStudy.enrollment.reference",
        "NotAResource.enrollment",
        "ResearchStudy.notAnElement"
      })
  void constructor_withInvalidElementPath_shouldThrow(String path) {
    assertThrows(IllegalArgumentException.class, () -> createSut(path));
  }

  @Test
  void parseElementPaths_withMultiplePaths_shouldGroupThemByResourceType() {
    var result =
        ExistingElementsPreserver.parseElementPaths(
            fhirContext,
            List.of(
                "ResearchStudy.enrollment",
                "Patient.generalPractitioner",
                "",
                "ResearchStudy.site"));

    assertThat(result).containsOnlyKeys("ResearchStudy", "Patient");
    assertThat(result.get("ResearchStudy"))
        .extracting(c -> c.getElementName())
        .containsExactly("enrollment", "site");
  }

  @Test
  void isEnabled_withEmptyConfig_shouldBeFalse() {
    assertThat(createSut().isEnabled()).isFalse();
    assertThat(createSut("ResearchStudy.enrollment").isEnabled()).isTrue();
  }

  @Test
  void preserve_withoutMatchingEntries_shouldReturnSameBundleWithoutReading() {
    var sut = createSut("ResearchStudy.enrollment");
    var bundle = createPutBundle("Patient/p1", new Patient());
    bundle
        .addEntry()
        .setResource(new ResearchStudy())
        .getRequest()
        .setMethod(HTTPVerb.POST)
        .setUrl("ResearchStudy");

    var result = sut.preserve(bundle);

    assertThat(result).isSameAs(bundle);
    verify(client).getFhirContext();
    verify(client, never()).transaction();
  }

  @Test
  void preserve_whenIncomingLacksElement_shouldCopyItFromExistingAndSetIfMatch() {
    var sut = createSut("ResearchStudy.enrollment");
    var bundle = createPutBundle("ResearchStudy/rs1", new ResearchStudy().setTitle("new title"));
    when(transactionTyped.execute())
        .thenReturn(createReadResponse("200 OK", createExistingStudy()));

    var result = sut.preserve(bundle);

    var readBatch = sentBundles.getValue();
    assertThat(readBatch.getType()).isEqualTo(BundleType.BATCH);
    assertThat(readBatch.getEntryFirstRep().getRequest().getMethod()).isEqualTo(HTTPVerb.GET);
    assertThat(readBatch.getEntryFirstRep().getRequest().getUrl()).isEqualTo("ResearchStudy/rs1");

    var entry = result.getEntryFirstRep();
    var study = (ResearchStudy) entry.getResource();
    assertThat(study.getTitle()).isEqualTo("new title");
    assertThat(study.getEnrollment())
        .extracting(Reference::getReference)
        .containsExactly("Group/g1");
    assertThat(entry.getRequest().getIfMatch()).isEqualTo("W/\"3\"");

    // the input bundle itself must stay untouched so a retry can start over from it
    assertThat(result).isNotSameAs(bundle);
    assertThat(((ResearchStudy) bundle.getEntryFirstRep().getResource()).hasEnrollment()).isFalse();
    assertThat(bundle.getEntryFirstRep().getRequest().hasIfMatch()).isFalse();
  }

  @Test
  void preserve_whenIncomingSetsElement_shouldKeepIncomingValue() {
    var sut = createSut("ResearchStudy.enrollment");
    var bundle =
        createPutBundle(
            "ResearchStudy/rs1", new ResearchStudy().addEnrollment(new Reference("Group/new")));
    when(transactionTyped.execute())
        .thenReturn(createReadResponse("200 OK", createExistingStudy()));

    var result = sut.preserve(bundle);

    var study = (ResearchStudy) result.getEntryFirstRep().getResource();
    assertThat(study.getEnrollment())
        .extracting(Reference::getReference)
        .containsExactly("Group/new");
    assertThat(result.getEntryFirstRep().getRequest().getIfMatch()).isEqualTo("W/\"3\"");
  }

  @Test
  void preserve_whenIncomingHasOnlyEmptyValues_shouldReplaceThemWithExisting() {
    var sut = createSut("ResearchStudy.enrollment");
    var bundle =
        createPutBundle("ResearchStudy/rs1", new ResearchStudy().addEnrollment(new Reference()));
    when(transactionTyped.execute())
        .thenReturn(createReadResponse("200 OK", createExistingStudy()));

    var result = sut.preserve(bundle);

    var study = (ResearchStudy) result.getEntryFirstRep().getResource();
    assertThat(study.getEnrollment())
        .extracting(Reference::getReference)
        .containsExactly("Group/g1");
  }

  @Test
  void preserve_withSingleValuedElement_shouldCopyIt() {
    var sut = createSut("ResearchStudy.primaryPurposeType");
    var existing = createExistingStudy();
    existing.getPrimaryPurposeType().setText("screening");
    var bundle = createPutBundle("ResearchStudy/rs1", new ResearchStudy());
    when(transactionTyped.execute()).thenReturn(createReadResponse("200 OK", existing));

    var result = sut.preserve(bundle);

    var study = (ResearchStudy) result.getEntryFirstRep().getResource();
    assertThat(study.getPrimaryPurposeType().getText()).isEqualTo("screening");
  }

  @Test
  void preserve_whenResourceDoesNotExistYet_shouldSendItUnchanged() {
    var sut = createSut("ResearchStudy.enrollment");
    var bundle = createPutBundle("ResearchStudy/rs1", new ResearchStudy().setTitle("new title"));
    when(transactionTyped.execute()).thenReturn(createReadResponse("404 Not Found", null));

    var result = sut.preserve(bundle);

    var entry = result.getEntryFirstRep();
    assertThat(((ResearchStudy) entry.getResource()).hasEnrollment()).isFalse();
    assertThat(entry.getRequest().hasIfMatch()).isFalse();
  }

  @Test
  void preserve_whenReadFailsWithUnexpectedStatus_shouldThrow() {
    var sut = createSut("ResearchStudy.enrollment");
    var bundle = createPutBundle("ResearchStudy/rs1", new ResearchStudy());
    when(transactionTyped.execute()).thenReturn(createReadResponse("403 Forbidden", null));

    assertThrows(IllegalStateException.class, () -> sut.preserve(bundle));
  }

  @Test
  void preserve_withConditionalUrl_shouldUseTheSingleSearchMatch() {
    var sut = createSut("ResearchStudy.enrollment");
    var url = "ResearchStudy?identifier=https://example.com/studies|rs1";
    var bundle = createPutBundle(url, new ResearchStudy());
    var searchResult = new Bundle().setType(BundleType.SEARCHSET);
    searchResult.addEntry().setResource(createExistingStudy());
    when(transactionTyped.execute()).thenReturn(createReadResponse("200 OK", searchResult));

    var result = sut.preserve(bundle);

    assertThat(sentBundles.getValue().getEntryFirstRep().getRequest().getUrl()).isEqualTo(url);
    var entry = result.getEntryFirstRep();
    assertThat(((ResearchStudy) entry.getResource()).getEnrollmentFirstRep().getReference())
        .isEqualTo("Group/g1");
    assertThat(entry.getRequest().getIfMatch()).isEqualTo("W/\"3\"");
  }

  @Test
  void preserve_withConditionalUrlMatchingNothing_shouldSendItUnchanged() {
    var sut = createSut("ResearchStudy.enrollment");
    var bundle = createPutBundle("ResearchStudy?identifier=x", new ResearchStudy());
    var searchResult = new Bundle().setType(BundleType.SEARCHSET);
    when(transactionTyped.execute()).thenReturn(createReadResponse("200 OK", searchResult));

    var result = sut.preserve(bundle);

    assertThat(((ResearchStudy) result.getEntryFirstRep().getResource()).hasEnrollment()).isFalse();
  }

  @Test
  void preserve_withEmptyConfig_shouldNeverTouchTheClient() {
    var unusedClient = mock(IGenericClient.class);
    when(unusedClient.getFhirContext()).thenReturn(fhirContext);
    var sut = new ExistingElementsPreserver(unusedClient, new FhirPreserveElementsConfig(null));
    var bundle = createPutBundle("ResearchStudy/rs1", new ResearchStudy());

    assertThat(sut.preserve(bundle)).isSameAs(bundle);
    verify(unusedClient).getFhirContext();
    verifyNoMoreInteractions(unusedClient);
    verifyNoInteractions(transactionTyped);
  }
}
