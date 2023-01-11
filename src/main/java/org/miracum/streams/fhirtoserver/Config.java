package org.miracum.streams.fhirtoserver;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.okhttp.client.OkHttpRestfulClientFactory;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import io.jaegertracing.internal.propagation.TraceContextCodec;
import io.opentracing.Span;
import io.opentracing.contrib.java.spring.jaeger.starter.TracerBuilderCustomizer;
import io.opentracing.contrib.okhttp3.OkHttpClientSpanDecorator;
import io.opentracing.contrib.okhttp3.TracingInterceptor;
import io.opentracing.propagation.Format;
import io.opentracing.util.GlobalTracer;
import java.time.Duration;
import java.util.Arrays;
import okhttp3.Connection;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.hl7.fhir.r4.hapi.fluentpath.FhirPathR4;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Config {
  @Value("${fhir.httpTimeoutSeconds}")
  int timeout;

  @Bean
  public FhirContext fhirContext() {
    var fhirContext = FhirContext.forR4();

    var opNameDecorator =
        new OkHttpClientSpanDecorator() {
          @Override
          public void onRequest(Request request, Span span) {
            // add the operation name to the span
            span.setOperationName(request.url().encodedPath());
          }

          @Override
          public void onError(Throwable throwable, Span span) {}

          @Override
          public void onResponse(Connection connection, Response response, Span span) {}
        };

    var tracingInterceptor =
        new TracingInterceptor(
            GlobalTracer.get(),
            Arrays.asList(OkHttpClientSpanDecorator.STANDARD_TAGS, opNameDecorator));

    var httpClient =
        new OkHttpClient.Builder()
            .addInterceptor(tracingInterceptor)
            .addNetworkInterceptor(tracingInterceptor)
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
  public IGenericClient fhirClient(
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
  public IFhirPath fhirPath(FhirContext ctx) {
    return new FhirPathR4(ctx);
  }

  @Bean
  public TracerBuilderCustomizer traceContextJaegerTracerCustomizer() {
    return builder -> {
      var injector = new TraceContextCodec.Builder().build();

      builder
          .registerInjector(Format.Builtin.HTTP_HEADERS, injector)
          .registerExtractor(Format.Builtin.HTTP_HEADERS, injector);

      builder
          .registerInjector(Format.Builtin.TEXT_MAP, injector)
          .registerExtractor(Format.Builtin.TEXT_MAP, injector);
    };
  }

  @Bean
  @ConditionalOnProperty(value = "opentracing.jaeger.enabled", havingValue = "false")
  public io.opentracing.Tracer jaegerTracer() {
    return io.opentracing.noop.NoopTracerFactory.create();
  }
}
