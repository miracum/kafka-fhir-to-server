package org.miracum.streams.fhirtoserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Group;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResearchStudy;
import org.hl7.fhir.r4.model.ResearchStudy.ResearchStudyStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
    classes = FhirToServerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FhirToServerPreserveElementsE2eTest {

  private static final String TOPIC = "fhir-msg-preserve-elements";
  private static final String STUDY_IDENTIFIER_SYSTEM = "https://example.com/studies";

  private static final DockerImageName kafkaImage =
      DockerImageName.parse(
              "docker.io/apache/kafka:4.3.0@sha256:0be4c9eb3565733612d2836d65636fd611a219ebcf5b4162e5ac259ea0ecb907")
          .asCompatibleSubstituteFor("apache/kafka");

  @Container static final KafkaContainer kafka = new KafkaContainer(kafkaImage);

  @Container
  @SuppressWarnings("resource")
  static final GenericContainer<?> fhirServer =
      new GenericContainer<>(
              DockerImageName.parse(
                  "docker.io/hapiproject/hapi:v8.10.0-1@sha256:1be4d7ffe7a35a9fb46151851e5a20b25c5016f16c8ef8b59b0c807ad06a40c1"))
          .withExposedPorts(8080)
          .withEnv("HAPI_FHIR_VALIDATION_REQUESTS_ENABLED", "false")
          .withEnv("HAPI_FHIR_SUBSCRIPTION_RESTHOOK_ENABLED", "false")
          .withEnv("HAPI_FHIR_FHIR_VERSION", "R4")
          .withEnv("HAPI_FHIR_CORS_ALLOWCREDENTIALS", "false")
          .withEnv("HAPI_FHIR_CORS_ALLOWED_ORIGIN", "*")
          .waitingFor(Wait.forHttp("/fhir/metadata").forPort(8080).forStatusCode(200));

  private static IGenericClient fhirClient;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrapServers", kafka::getBootstrapServers);
    registry.add("spring.cloud.stream.bindings.sinkSingle-in-0.destination", () -> TOPIC);
    registry.add("fhir.url", FhirToServerPreserveElementsE2eTest::getFhirServerUrl);
    registry.add("fhir.preserve-elements", () -> "ResearchStudy.enrollment");
    registry.add("s3.enabled", () -> "false");
    registry.add("fhir.merge-batches-into-single-bundle.enabled", () -> "false");
  }

  private static String getFhirServerUrl() {
    return String.format(
        "http://%s:%d/fhir", fhirServer.getHost(), fhirServer.getMappedPort(8080));
  }

  @BeforeAll
  static void setUp() {
    var fhirContext = FhirContext.forR4();
    fhirContext.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
    fhirClient = fhirContext.newRestfulGenericClient(getFhirServerUrl());
  }

  private static ResearchStudy createStudy(String id, String title) {
    var study = new ResearchStudy().setTitle(title).setStatus(ResearchStudyStatus.ACTIVE);
    study.addIdentifier().setSystem(STUDY_IDENTIFIER_SYSTEM).setValue(id);
    study.setId(id);
    return study;
  }

  /** Simulates another system, e.g. a study registry UI, enrolling an existing study. */
  private static void createEnrolledStudy(String id, String groupId) {
    var group = new Group().setType(Group.GroupType.PERSON).setActual(false);
    group.setId(groupId);
    fhirClient.update().resource(group).execute();

    var study = createStudy(id, "initial title");
    study.addEnrollment(new Reference("Group/" + groupId));
    fhirClient.update().resource(study).execute();
  }

  @Test
  void shouldKeepEnrollmentOfExistingStudiesWhenUpdatingThemWithoutOne()
      throws InterruptedException, ExecutionException {
    createEnrolledStudy("rs-plain", "g-plain");
    createEnrolledStudy("rs-conditional", "g-conditional");

    // the upstream ETL job only knows about the study's own metadata, not its enrollment
    var bundle = new Bundle().setType(BundleType.TRANSACTION);
    bundle
        .addEntry()
        .setResource(createStudy("rs-plain", "updated title"))
        .getRequest()
        .setMethod(HTTPVerb.PUT)
        .setUrl("ResearchStudy/rs-plain");
    var conditionalStudy = createStudy("rs-conditional", "updated title");
    conditionalStudy.setId((String) null);
    bundle
        .addEntry()
        .setResource(conditionalStudy)
        .getRequest()
        .setMethod(HTTPVerb.PUT)
        .setUrl("ResearchStudy?identifier=" + STUDY_IDENTIFIER_SYSTEM + "|rs-conditional");
    bundle
        .addEntry()
        .setResource(createStudy("rs-new", "new study"))
        .getRequest()
        .setMethod(HTTPVerb.PUT)
        .setUrl("ResearchStudy/rs-new");

    publishToKafka(fhirClient.getFhirContext().newJsonParser().encodeResourceToString(bundle));

    await()
        .atMost(Duration.ofMinutes(3))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              var plain = readStudy("rs-plain");
              assertThat(plain.getTitle()).isEqualTo("updated title");
              assertThat(plain.getEnrollmentFirstRep().getReference()).isEqualTo("Group/g-plain");

              var conditional = readStudy("rs-conditional");
              assertThat(conditional.getTitle()).isEqualTo("updated title");
              assertThat(conditional.getEnrollmentFirstRep().getReference())
                  .isEqualTo("Group/g-conditional");

              var created = readStudy("rs-new");
              assertThat(created.getTitle()).isEqualTo("new study");
              assertThat(created.hasEnrollment()).isFalse();
            });
  }

  /**
   * SendToServerProcessor relies on the server rejecting a stale If-Match in a transaction with
   * one of the exceptions it retries on.
   */
  @Test
  void serverShouldRejectStaleIfMatchWithAVersionConflict() {
    fhirClient.update().resource(createStudy("rs-stale", "v1")).execute();
    fhirClient.update().resource(createStudy("rs-stale", "v2")).execute();

    var bundle = new Bundle().setType(BundleType.TRANSACTION);
    bundle
        .addEntry()
        .setResource(createStudy("rs-stale", "v3"))
        .getRequest()
        .setMethod(HTTPVerb.PUT)
        .setUrl("ResearchStudy/rs-stale")
        .setIfMatch("W/\"1\"");

    assertThatThrownBy(() -> fhirClient.transaction().withBundle(bundle).execute())
        .isInstanceOfAny(
            PreconditionFailedException.class, ResourceVersionConflictException.class);
  }

  private static ResearchStudy readStudy(String id) {
    return fhirClient.read().resource(ResearchStudy.class).withId(id).execute();
  }

  private static void publishToKafka(String bundle)
      throws InterruptedException, ExecutionException {
    var producerProps =
        Map.<String, Object>of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

    try (var producer = new KafkaProducer<String, String>(producerProps)) {
      producer.send(new ProducerRecord<>(TOPIC, "1", bundle)).get();
    }
  }
}
