package org.miracum.streams.fhirtoserver;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.okhttp.client.OkHttpRestfulClientFactory;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import io.minio.MinioClient;
import io.minio.credentials.AwsEnvironmentProvider;
import io.minio.credentials.Provider;
import io.minio.credentials.StaticProvider;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.hl7.fhir.r4.hapi.fluentpath.FhirPathR4;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
  MinioClient minioClient(S3Config config) {
    Provider credentialsProvider;

    if (config.accessKey().isPresent() && config.secretKey().isPresent()) {
      credentialsProvider =
          new StaticProvider(config.accessKey().get(), config.secretKey().get(), null);
    } else {
      credentialsProvider = new AwsEnvironmentProvider();
    }

    return MinioClient.builder()
        .credentialsProvider(credentialsProvider)
        .endpoint(config.endpointUrl())
        .build();
  }
}
