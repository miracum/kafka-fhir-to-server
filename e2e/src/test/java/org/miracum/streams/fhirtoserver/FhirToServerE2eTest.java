package org.miracum.streams.fhirtoserver;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.SummaryEnum;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;

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
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(classes = FhirToServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FhirToServerE2eTest {

  private static final String TOPIC = "fhir-msg";

  private static final DockerImageName kafkaImage = DockerImageName
      .parse(
          "docker.io/apache/kafka:4.3.0@sha256:0be4c9eb3565733612d2836d65636fd611a219ebcf5b4162e5ac259ea0ecb907")
      .asCompatibleSubstituteFor("apache/kafka");

  @Container
  static final KafkaContainer kafka = new KafkaContainer(kafkaImage);

  @Container
  @SuppressWarnings("resource")
  static final GenericContainer<?> fhirServer = new GenericContainer<>(
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
    registry.add(
        "fhir.url",
        () -> String.format(
            "http://%s:%d/fhir", fhirServer.getHost(), fhirServer.getMappedPort(8080)));
    registry.add("s3.enabled", () -> "false");
    registry.add("fhir.merge-batches-into-single-bundle.enabled", () -> "false");
  }

  @BeforeAll
  static void setUp() {
    var fhirContext = FhirContext.forR4();
    fhirContext.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
    var fhirServerUrl = String.format("http://%s:%d/fhir", fhirServer.getHost(), fhirServer.getMappedPort(8080));
    fhirClient = fhirContext.newRestfulGenericClient(fhirServerUrl);
  }

  @Test
  void shouldCreateExpectedResourcesOnFhirServer() throws IOException {
    publishMockDataToKafka();

    await()
        .atMost(Duration.ofMinutes(3))
        .pollInterval(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var patientBundle = fhirClient
                  .search()
                  .forResource(Patient.class)
                  .summaryMode(SummaryEnum.COUNT)
                  .returnBundle(Bundle.class)
                  .execute();
              assertTrue(
                  patientBundle.getTotal() >= 2,
                  "Expected at least 2 Patients, got " + patientBundle.getTotal());

              var observationBundle = fhirClient
                  .search()
                  .forResource(Observation.class)
                  .summaryMode(SummaryEnum.COUNT)
                  .returnBundle(Bundle.class)
                  .execute();
              assertTrue(
                  observationBundle.getTotal() >= 1,
                  "Expected at least 1 Observation, got " + observationBundle.getTotal());

              var encounterBundle = fhirClient
                  .search()
                  .forResource(Encounter.class)
                  .summaryMode(SummaryEnum.COUNT)
                  .returnBundle(Bundle.class)
                  .execute();
              assertTrue(
                  encounterBundle.getTotal() >= 2,
                  "Expected at least 2 Encounters, got " + encounterBundle.getTotal());
            });
  }

  private void publishMockDataToKafka() throws IOException {
    var bundle1 = new ClassPathResource("fhir/bundle-1.json").getContentAsString(StandardCharsets.UTF_8);
    var bundle2 = new ClassPathResource("fhir/bundle-2.json").getContentAsString(StandardCharsets.UTF_8);

    var producerProps = Map.<String, Object>of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

    try (var producer = new KafkaProducer<String, String>(producerProps)) {
      try {
        producer.send(new ProducerRecord<>(TOPIC, "1", bundle1)).get();
        producer.send(new ProducerRecord<>(TOPIC, "2", bundle2)).get();
      } catch (InterruptedException | ExecutionException e) {
        e.printStackTrace();
      }
    }
  }
}
// opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9093 --all-groups --describe
// opt/kafka/bin/kafka-topics.sh --bootstrap-server=localhost:9093  --list
