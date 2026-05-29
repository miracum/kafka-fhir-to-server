package org.miracum.streams.fhirtoserver;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
    classes = FhirToServerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FhirToServerE2eTest {

  private static final String TOPIC = "fhir-msg";

  private static final Network network = Network.newNetwork();

  @Container
  static final KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.0")).withNetwork(network);

  @Container
  @SuppressWarnings("resource")
  static final GenericContainer<?> fhirServer =
      new GenericContainer<>(DockerImageName.parse("hapiproject/hapi:v8.4.0-3"))
          .withNetwork(network)
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
    registry.add(
        "fhir.url",
        () ->
            String.format(
                "http://%s:%d/fhir", fhirServer.getHost(), fhirServer.getMappedPort(8080)));
    registry.add("s3.enabled", () -> "false");
    registry.add("fhir.merge-batches-into-single-bundle.enabled", () -> "false");
  }

  @BeforeAll
  static void setUp() {
    var fhirContext = FhirContext.forR4();
    fhirContext.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
    var fhirServerUrl =
        String.format("http://%s:%d/fhir", fhirServer.getHost(), fhirServer.getMappedPort(8080));
    fhirClient = fhirContext.newRestfulGenericClient(fhirServerUrl);
  }

  @Test
  void shouldCreateExpectedResourcesOnFhirServer() {
    publishMockDataToKafka();

    // Wait for the application to consume from Kafka and push to the FHIR server.
    // The Python tests waited up to 10 minutes; we use a shorter timeout since
    // Testcontainers starts everything locally.
    await()
        .atMost(Duration.ofMinutes(3))
        .pollInterval(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var patientBundle =
                  fhirClient
                      .search()
                      .forResource(Patient.class)
                      .summaryMode(ca.uhn.fhir.rest.api.SummaryEnum.COUNT)
                      .returnBundle(Bundle.class)
                      .execute();
              assertTrue(
                  patientBundle.getTotal() >= 2,
                  "Expected at least 2 Patients, got " + patientBundle.getTotal());

              var observationBundle =
                  fhirClient
                      .search()
                      .forResource(Observation.class)
                      .summaryMode(ca.uhn.fhir.rest.api.SummaryEnum.COUNT)
                      .returnBundle(Bundle.class)
                      .execute();
              assertTrue(
                  observationBundle.getTotal() >= 1,
                  "Expected at least 1 Observation, got " + observationBundle.getTotal());

              var encounterBundle =
                  fhirClient
                      .search()
                      .forResource(Encounter.class)
                      .summaryMode(ca.uhn.fhir.rest.api.SummaryEnum.COUNT)
                      .returnBundle(Bundle.class)
                      .execute();
              assertTrue(
                  encounterBundle.getTotal() >= 2,
                  "Expected at least 2 Encounters, got " + encounterBundle.getTotal());
            });
  }

  /**
   * Publishes the same mock data used in the original Python e2e tests to Kafka. The data contains
   * 2 Patients, 1 Observation, and 2 Encounters across two FHIR Bundle messages.
   */
  private void publishMockDataToKafka() {
    var producerProps =
        Map.<String, Object>of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

    try (var producer = new KafkaProducer<String, String>(producerProps)) {
      // Bundle 1: Patient pid.999 + Encounter cid.106
      var bundle1 =
          "{\"resourceType\":\"Bundle\",\"type\":\"batch\",\"entry\":"
              + "[{\"fullUrl\":\"Patient/pid.999\",\"resource\":{\"resourceType\":\"Patient\","
              + "\"id\":\"pid.999\",\"meta\":{\"source\":\"#p21\"},"
              + "\"identifier\":[{\"use\":\"usual\",\"type\":{\"coding\":"
              + "[{\"system\":\"http://terminology.hl7.org/CodeSystem/v2-0203\",\"code\":\"MR\"}]},"
              + "\"system\":\"https://miracum.org/fhir/NamingSystem/identifier/PatientId\","
              + "\"value\":\"pid.999\"}],\"gender\":\"unknown\",\"birthDate\":\"1941-01-01\"},"
              + "\"request\":{\"method\":\"PUT\",\"url\":\"Patient/pid.999\"}},"
              + "{\"fullUrl\":\"Encounter/cid.106\",\"resource\":{\"resourceType\":\"Encounter\","
              + "\"id\":\"cid.106\",\"meta\":{\"source\":\"#p21\"},\"status\":\"finished\","
              + "\"class\":{\"system\":\"http://terminology.hl7.org/CodeSystem/v3-ActCode\","
              + "\"code\":\"IMP\"},\"subject\":{\"reference\":\"Patient/pid.999\"},"
              + "\"period\":{\"start\":\"2018-07-31T20:32:00+02:00\","
              + "\"end\":\"2018-08-05T21:28:00+02:00\"}},"
              + "\"request\":{\"method\":\"PUT\",\"url\":\"Encounter/cid.106\"}}]}";

      // Bundle 2: Patient pid.1001 + Observation id-6457799fed46dffc + Encounter cid.108
      var bundle2 =
          "{\"resourceType\":\"Bundle\",\"type\":\"batch\",\"entry\":"
              + "[{\"fullUrl\":\"Observation/id-6457799fed46dffc\","
              + "\"resource\":{\"resourceType\":\"Observation\","
              + "\"id\":\"id-6457799fed46dffc\",\"meta\":{\"source\":\"#p21\"},"
              + "\"status\":\"final\",\"code\":{\"coding\":"
              + "[{\"system\":\"http://loinc.org\",\"code\":\"74200-7\","
              + "\"display\":\"Days in intensive care unit\"}]},"
              + "\"subject\":{\"reference\":\"Patient/pid.1001\"},"
              + "\"encounter\":{\"reference\":\"Encounter/cid.108\"},"
              + "\"valueQuantity\":{\"value\":2,\"unit\":\"d\","
              + "\"system\":\"http://unitsofmeasure.org\",\"code\":\"d\"}},"
              + "\"request\":{\"method\":\"PUT\",\"url\":\"Observation/id-6457799fed46dffc\"}},"
              + "{\"fullUrl\":\"Patient/pid.1001\",\"resource\":{\"resourceType\":\"Patient\","
              + "\"id\":\"pid.1001\",\"meta\":{\"source\":\"#p21\"},"
              + "\"identifier\":[{\"use\":\"usual\",\"type\":{\"coding\":"
              + "[{\"system\":\"http://terminology.hl7.org/CodeSystem/v2-0203\",\"code\":\"MR\"}]},"
              + "\"system\":\"https://miracum.org/fhir/NamingSystem/identifier/PatientId\","
              + "\"value\":\"pid.1001\"}],\"birthDate\":\"1941-01-01\"},"
              + "\"request\":{\"method\":\"PUT\",\"url\":\"Patient/pid.1001\"}},"
              + "{\"fullUrl\":\"Encounter/cid.108\",\"resource\":{\"resourceType\":\"Encounter\","
              + "\"id\":\"cid.108\",\"meta\":{\"source\":\"#p21\"},\"status\":\"in-progress\","
              + "\"class\":{\"system\":\"http://terminology.hl7.org/CodeSystem/v3-ActCode\","
              + "\"code\":\"IMP\"},\"subject\":{\"reference\":\"Patient/pid.1001\"},"
              + "\"period\":{\"start\":\"2018-07-31T20:32:00+02:00\"}},"
              + "\"request\":{\"method\":\"PUT\",\"url\":\"Encounter/cid.108\"}}]}";

      producer.send(new ProducerRecord<>(TOPIC, "1", bundle1));
      producer.send(new ProducerRecord<>(TOPIC, "2", bundle2));
      producer.flush();
    }
  }
}
