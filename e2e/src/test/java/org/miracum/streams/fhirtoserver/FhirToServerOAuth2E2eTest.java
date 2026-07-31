package org.miracum.streams.fhirtoserver;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.SummaryEnum;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
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
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Deploys the actual, published {@code kafka-fhir-to-server} container image alongside Kafka,
 * Keycloak, and an OAuth2-secured Blaze FHIR server (see <a
 * href="https://github.com/samply/blaze/blob/main/docs/authentication.md">Blaze's authentication
 * docs</a>) to exercise the full pipeline end-to-end: reading a Bundle from Kafka, authenticating
 * against Keycloak via the OAuth2 client credentials grant, and writing the resources to the FHIR
 * server. The application is configured entirely via environment variables, the same way it would
 * be deployed in production.
 */
@Testcontainers
class FhirToServerOAuth2E2eTest {

  private static final String TOPIC = "fhir-msg";
  private static final String CLIENT_ID = "fhir-to-server-client";
  private static final String CLIENT_SECRET = "fhir-to-server-secret";
  private static final String KEYCLOAK_ALIAS = "keycloak";
  private static final String BLAZE_ALIAS = "blaze";
  private static final String KAFKA_ALIAS = "kafka";
  private static final int KAFKA_INTERNAL_PORT = 19092;

  /**
   * The kafka-fhir-to-server image under test. Overridable via the {@code fhirToServer.image}
   * system property (e.g. by CI, to point at the image it just built) so this test doesn't only
   * ever validate the last released version.
   */
  private static final String FHIR_TO_SERVER_IMAGE =
      System.getProperty("fhirToServer.image", "ghcr.io/miracum/kafka-fhir-to-server:v3.1.0");

  private static final Network network = Network.newNetwork();

  private static final DockerImageName kafkaImage =
      DockerImageName.parse(
              "docker.io/apache/kafka-native:4.3.1@sha256:2885898ba17065023f1bd605f3a81efcfa986014f062b73b91ef5462485f9060")
          .asCompatibleSubstituteFor("apache/kafka");

  @Container
  @SuppressWarnings("resource")
  static final KafkaContainer kafka =
      new KafkaContainer(kafkaImage)
          .withNetwork(network)
          .withListener(KAFKA_ALIAS + ":" + KAFKA_INTERNAL_PORT);

  @Container
  @SuppressWarnings("resource")
  static final GenericContainer<?> keycloak =
      new GenericContainer<>(
              DockerImageName.parse(
                  "quay.io/keycloak/keycloak:26.7.0@sha256:0f198be292568439d700cdbfb893e69a6009bb43a94a06a945b1d3d506c76b13"))
          .withNetwork(network)
          .withNetworkAliases(KEYCLOAK_ALIAS)
          .withExposedPorts(8080)
          .withClasspathResourceMapping(
              "keycloak/realm.json", "/opt/keycloak/data/import/realm.json", BindMode.READ_ONLY)
          .withCommand("start-dev", "--import-realm")
          .waitingFor(
              Wait.forHttp("/realms/fhir-to-server/.well-known/openid-configuration")
                  .forPort(8080)
                  .forStatusCode(200));

  @Container
  @SuppressWarnings("resource")
  static final GenericContainer<?> fhirServer =
      new GenericContainer<>(
              DockerImageName.parse(
                  "ghcr.io/samply/blaze:1.10.1@sha256:dcc951be40714a4eee5f78db08b90a5a9ea9d4a172f716bdb419f5c1a2feebc6"))
          .withNetwork(network)
          .withNetworkAliases(BLAZE_ALIAS)
          .withExposedPorts(8080)
          .withEnv("OPENID_PROVIDER_URL", "http://" + KEYCLOAK_ALIAS + ":8080/realms/fhir-to-server")
          .dependsOn(keycloak)
          .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200));

  @Container
  @SuppressWarnings("resource")
  static final GenericContainer<?> fhirToServer =
      new GenericContainer<>(DockerImageName.parse(FHIR_TO_SERVER_IMAGE))
          .withNetwork(network)
          .withExposedPorts(8080)
          .withEnv(
              Map.of(
                  "BOOTSTRAP_SERVERS", KAFKA_ALIAS + ":" + KAFKA_INTERNAL_PORT,
                  "TOPIC", TOPIC,
                  "FHIR_URL", "http://" + BLAZE_ALIAS + ":8080/fhir",
                  "FHIR_AUTH_OAUTH2_ENABLED", "true",
                  "FHIR_AUTH_OAUTH2_TOKEN_URL", internalTokenUrl(),
                  "FHIR_AUTH_OAUTH2_CLIENT_ID", CLIENT_ID,
                  "FHIR_AUTH_OAUTH2_CLIENT_SECRET", CLIENT_SECRET))
          .dependsOn(kafka, keycloak, fhirServer)
          .waitingFor(Wait.forHttp("/actuator/health").forPort(8080).forStatusCode(200));

  private static IGenericClient authenticatedFhirClient;

  private static String internalTokenUrl() {
    return "http://" + KEYCLOAK_ALIAS + ":8080/realms/fhir-to-server/protocol/openid-connect/token";
  }

  private static String fhirServerUrl() {
    return String.format(
        "http://%s:%d/fhir", fhirServer.getHost(), fhirServer.getMappedPort(8080));
  }

  private static String tokenUrl() {
    return String.format(
        "http://%s:%d/realms/fhir-to-server/protocol/openid-connect/token",
        keycloak.getHost(), keycloak.getMappedPort(8080));
  }

  @BeforeAll
  static void setUp() {
    var fhirContext = FhirContext.forR4();
    fhirContext.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);

    // uses the same OAuth2ClientCredentialsAuthInterceptor that Config.fhirClient() registers
    // on the application's own FHIR client, purely to verify the results the containerized app
    // under test wrote via its own, independently configured OAuth2 client.
    authenticatedFhirClient = fhirContext.newRestfulGenericClient(fhirServerUrl());
    authenticatedFhirClient.registerInterceptor(
        new OAuth2ClientCredentialsAuthInterceptor(tokenUrl(), CLIENT_ID, CLIENT_SECRET, null));
  }

  @Test
  void shouldRejectUnauthenticatedRequests() {
    var fhirContext = FhirContext.forR4();
    fhirContext.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
    var anonymousClient = fhirContext.newRestfulGenericClient(fhirServerUrl());

    assertThrows(
        AuthenticationException.class,
        () ->
            anonymousClient
                .search()
                .forResource(Patient.class)
                .summaryMode(SummaryEnum.COUNT)
                .returnBundle(Bundle.class)
                .execute());
  }

  @Test
  void shouldCreateExpectedResourcesOnOAuth2SecuredFhirServer() throws IOException {
    publishMockDataToKafka();

    await()
        .atMost(Duration.ofMinutes(3))
        .pollInterval(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var patientBundle =
                  authenticatedFhirClient
                      .search()
                      .forResource(Patient.class)
                      .summaryMode(SummaryEnum.COUNT)
                      .returnBundle(Bundle.class)
                      .execute();
              assertTrue(
                  patientBundle.getTotal() >= 1,
                  "Expected at least 1 Patient, got " + patientBundle.getTotal());

              var encounterBundle =
                  authenticatedFhirClient
                      .search()
                      .forResource(Encounter.class)
                      .summaryMode(SummaryEnum.COUNT)
                      .returnBundle(Bundle.class)
                      .execute();
              assertTrue(
                  encounterBundle.getTotal() >= 1,
                  "Expected at least 1 Encounter, got " + encounterBundle.getTotal());
            });
  }

  private void publishMockDataToKafka() throws IOException {
    var bundle1 =
        new ClassPathResource("fhir/bundle-1.json").getContentAsString(StandardCharsets.UTF_8);

    var producerProps =
        Map.<String, Object>of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

    try (var producer = new KafkaProducer<String, String>(producerProps)) {
      try {
        producer.send(new ProducerRecord<>(TOPIC, "1", bundle1)).get();
      } catch (InterruptedException | ExecutionException e) {
        e.printStackTrace();
      }
    }
  }
}
