package org.miracum.streams.fhirtoserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.ITransaction;
import ca.uhn.fhir.rest.gclient.ITransactionTyped;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r4.hapi.fluentpath.FhirPathR4;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResearchStudy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SendToServerProcessorTests {
  private final FhirContext fhirContext = FhirContext.forR4();

  private SendToServerProcessor createSut(IGenericClient client, List<String> preserveElements) {
    var filter = new FhirPathResourceFilter(new FhirPathR4(fhirContext));
    return new SendToServerProcessor(
        client,
        "",
        null,
        new FhirBundleMergerConfig(false, null, Optional.empty()),
        filter,
        null,
        new ExistingElementsPreserver(client, new FhirPreserveElementsConfig(preserveElements)),
        null,
        null);
  }

  @Test
  void process_withNullResource_shouldNotThrow() {
    var sut = createSut(fhirContext.newRestfulGenericClient("http://localhost/fhir"), List.of());

    assertDoesNotThrow(() -> sut.sinkBatch().accept(null));
    assertDoesNotThrow(() -> sut.sinkSingle().accept(null));
  }

  private static Bundle createReadResponse(String groupReference, String versionId) {
    var existing = new ResearchStudy().addEnrollment(new Reference(groupReference));
    existing.setId("rs1");
    existing.getMeta().setVersionId(versionId);
    var response = new Bundle().setType(BundleType.BATCHRESPONSE);
    response.addEntry().setResource(existing).getResponse().setStatus("200 OK");
    return response;
  }

  private static Bundle createStudyBundle() {
    var bundle = new Bundle().setType(BundleType.TRANSACTION);
    bundle
        .addEntry()
        .setResource(new ResearchStudy().setTitle("new title"))
        .getRequest()
        .setMethod(HTTPVerb.PUT)
        .setUrl("ResearchStudy/rs1");
    return bundle;
  }

  @SuppressWarnings("unchecked")
  private IGenericClient mockClient(
      ArgumentCaptor<Bundle> sentBundles, ITransactionTyped<Bundle> transactionTyped) {
    var client = mock(IGenericClient.class);
    var transaction = mock(ITransaction.class);
    when(client.getFhirContext()).thenReturn(fhirContext);
    when(client.transaction()).thenReturn(transaction);
    when(transaction.withBundle(sentBundles.capture())).thenReturn(transactionTyped);
    return client;
  }

  @Test
  @SuppressWarnings("unchecked")
  void sendSingleBundleToServer_onVersionConflict_shouldReReadAndSendAgain() {
    var sentBundles = ArgumentCaptor.forClass(Bundle.class);
    ITransactionTyped<Bundle> transactionTyped = mock(ITransactionTyped.class);
    var sut =
        createSut(mockClient(sentBundles, transactionTyped), List.of("ResearchStudy.enrollment"));

    // read, conflicting write, re-read of the now newer version, successful write
    when(transactionTyped.execute())
        .thenReturn(createReadResponse("Group/old", "1"))
        .thenThrow(new PreconditionFailedException("version conflict"))
        .thenReturn(createReadResponse("Group/new", "2"))
        .thenReturn(new Bundle().setType(BundleType.TRANSACTIONRESPONSE));

    sut.sendSingleBundleToServer(createStudyBundle());

    var sent = sentBundles.getAllValues();
    assertThat(sent).hasSize(4);

    var lastWrite = sent.get(3).getEntryFirstRep();
    assertThat(((ResearchStudy) lastWrite.getResource()).getEnrollment())
        .extracting(Reference::getReference)
        .containsExactly("Group/new");
    assertThat(lastWrite.getRequest().getIfMatch()).isEqualTo("W/\"2\"");
  }

  @Test
  @SuppressWarnings("unchecked")
  void sendSingleBundleToServer_onPersistentVersionConflict_shouldEventuallyThrow() {
    var sentBundles = ArgumentCaptor.forClass(Bundle.class);
    ITransactionTyped<Bundle> transactionTyped = mock(ITransactionTyped.class);
    var sut =
        createSut(mockClient(sentBundles, transactionTyped), List.of("ResearchStudy.enrollment"));

    var readResponse = createReadResponse("Group/g1", "1");
    var conflict = new ResourceVersionConflictException("version conflict");
    var stubbing = when(transactionTyped.execute());
    for (var i = 0; i < 5; i++) {
      stubbing = stubbing.thenReturn(readResponse).thenThrow(conflict);
    }

    assertThrows(
        ResourceVersionConflictException.class,
        () -> sut.sendSingleBundleToServer(createStudyBundle()));
    // 5 attempts, each a read and a write
    assertThat(sentBundles.getAllValues()).hasSize(10);
  }
}
