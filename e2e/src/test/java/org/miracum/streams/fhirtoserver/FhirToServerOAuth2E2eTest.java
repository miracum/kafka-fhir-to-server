package org.miracum.streams.fhirtoserver;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.SummaryEnum;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies that the FHIR client authenticates against an OAuth2/OIDC secured FHIR server (Blaze,
 * see <a href="https://github.com/samply/blaze/blob/main/docs/authentication.md">its
 * authentication docs</a>) using the client credentials grant, with Keycloak as the identity
 * provider.
 */
@Testcontainers
@SpringBootTest(
    classes = FhirToServerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FhirToServerOAuth2E2eTest {

  private static final String TOPIC = "fhir-msg";
  private static final String CLIENT_ID = "fhir-to-server-client";
  private static final String CLIENT_SECRET = "fhir-to-server-secret";

  private static final Network network = Network.newNetwork();

  private static final DockerImageName kafkaImage =
      DockerImageName.parse(
              "docker.io/apache/kafka-native:4.3.1@sha256:2885898ba17065023f1bd605f3a81efcfa986014f062b73b91ef5462485f9060")
          .asCompatibleSubstituteFor("apache/kafka");

  @Container static final KafkaContainer kafka = new KafkaContainer(kafkaImage);

  @Container
  @SuppressWarnings("resource")
  static final GenericContainer<?> keycloak =
      new GenericContainer<>(
              DockerImageName.parse(
                  "quay.io/keycloak/keycloak:26.7.0@sha256:0f198be292568439d700cdbfb893e69a6009bb43a94a06a945b1d3d506c76b13"))
          .withNetwork(network)
          .withNetworkAliases("keycloak")
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
          .withExposedPorts(8080)
          .withEnv("OPENID_PROVIDER_URL", "http://keycloak:8080/realms/fhir-to-server")
          .dependsOn(keycloak)
          .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200));

  private static IGenericClient authenticatedFhirClient;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrapServers", kafka::getBootstrapServers);
    registry.add("spring.cloud.stream.bindings.sinkSingle-in-0.destination", () -> TOPIC);
    registry.add("fhir.url", () -> fhirServerUrl());
    registry.add("s3.enabled", () -> "false");
    registry.add("fhir.merge-batches-into-single-bundle.enabled", () -> "false");
    registry.add("fhir.auth.basic.enabled", () -> "false");
    registry.add("fhir.auth.oauth2.enabled", () -> "true");
    registry.add("fhir.auth.oauth2.token-url", () -> tokenUrl());
    registry.add("fhir.auth.oauth2.client-id", () -> CLIENT_ID);
    registry.add("fhir.auth.oauth2.client-secret", () -> CLIENT_SECRET);
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
  static void setUp() throws IOException, InterruptedException {
    var fhirContext = FhirContext.forR4();
    fhirContext.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);

    authenticatedFhirClient = fhirContext.newRestfulGenericClient(fhirServerUrl());
    authenticatedFhirClient.registerInterceptor(
        new BearerTokenAuthInterceptor(fetchAccessToken()));
  }

  private static String fetchAccessToken() throws IOException, InterruptedException {
    var form = "grant_type=client_credentials";
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl()))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header(
                "Authorization",
                "Basic "
                    + java.util.Base64.getEncoder()
                        .encodeToString(
                            (CLIENT_ID + ":" + CLIENT_SECRET)
                                .getBytes(StandardCharsets.UTF_8)))
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();

    var response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    var body = new ObjectMapper().readTree(response.body());
    return body.get("access_token").asText();
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
