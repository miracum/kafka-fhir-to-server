package org.miracum.streams.fhirtoserver;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.okhttp.client.OkHttpRestfulClientFactory;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import java.net.URISyntaxException;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.hl7.fhir.r4.hapi.fluentpath.FhirPathR4;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.*;

@Configuration
public class Config {
  @Value("${fhir.http-timeout-seconds}")
  int timeout;

  @Bean
  FhirContext fhirContext() {
    var fhirContext = FhirContext.forR4();

    var httpClient =
        new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(timeout))
            .connectTimeout(Duration.ofSeconds(timeout))
            .readTimeout(Duration.ofSeconds(timeout))
            .writeTimeout(Duration.ofSeconds(timeout))
            .build();

    var okHttpFactory = new OkHttpRestfulClientFactory(fhirContext);
    okHttpFactory.setHttpClient(httpClient);

    fhirContext.setRestfulClientFactory(okHttpFactory);
    return fhirContext;
  }

  @Bean
  IGenericClient fhirClient(
      FhirContext fhirContext,
      @Value("${fhir.auth.basic.username}") String username,
      @Value("${fhir.auth.basic.password}") String password,
      @Value("${fhir.auth.basic.enabled}") boolean isBasicAuthEnabled,
      @Value("${fhir.url}") String fhirServerUrl) {
    var client = fhirContext.newRestfulGenericClient(fhirServerUrl);

    if (isBasicAuthEnabled) {
      client.registerInterceptor(new BasicAuthInterceptor(username, password));
    }

    return client;
  }

  @Bean
  IFhirPath fhirPath(FhirContext ctx) {
    return new FhirPathR4(ctx);
  }

  @Bean
  @ConditionalOnProperty(prefix = "s3", name = "enabled", havingValue = "true")
  S3Client s3Client(S3Config config) throws URISyntaxException {
    AwsCredentialsProvider credentialsProvider = null;

    if (config.accessKey().isPresent() && config.secretKey().isPresent()) {
      var credentials =
          AwsBasicCredentials.create(config.accessKey().get(), config.secretKey().get());
      credentialsProvider = StaticCredentialsProvider.create(credentials);
    } else {
      credentialsProvider = EnvironmentVariableCredentialsProvider.create();
    }

    var builder =
        S3Client.builder()
            .region(config.region())
            .endpointOverride(config.endpointUrl().toURI())
            .forcePathStyle(config.forcePathStyle())
            .overrideConfiguration(
                b -> b.apiCallTimeout(Duration.ofSeconds(config.timeoutSeconds())))
            .credentialsProvider(credentialsProvider);

    return builder.build();
  }
}
