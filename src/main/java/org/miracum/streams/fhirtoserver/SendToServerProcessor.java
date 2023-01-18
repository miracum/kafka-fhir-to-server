package org.miracum.streams.fhirtoserver;

import static net.logstash.logback.argument.StructuredArguments.kv;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.HashMap;
import java.util.function.Consumer;
import org.apache.logging.log4j.util.Strings;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.listener.RetryListenerSupport;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@Service
public class SendToServerProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(SendToServerProcessor.class);

  private static final String PROCESSING_ERRORS_COUNTER_NAME =
      "fhirtoserver.processing.errors.total";

  private static final Counter sendingFailedCounter =
      Metrics.globalRegistry.counter(PROCESSING_ERRORS_COUNTER_NAME, "kind", "sending-failed");

  private static final Counter unsupportedResourceTypeCounter =
      Metrics.globalRegistry.counter(
          PROCESSING_ERRORS_COUNTER_NAME, "kind", "unsupported-resource-type");

  private static final Counter messageNullCounter =
      Metrics.globalRegistry.counter(PROCESSING_ERRORS_COUNTER_NAME, "kind", "message-is-null");

  private static final Counter filterMatchedCounter =
      Metrics.globalRegistry.counter("fhirtoserver.fhir.filter.matches.total");

  private static final Timer filterDurationTimer =
      Metrics.globalRegistry.timer("fhirtoserver.fhir.filter.duration");

  private final IGenericClient client;
  private final RetryTemplate retryTemplate;
  private final String fhirPathFilterExpression;
  private final Bundle.BundleType overrideBundleType;
  private final FhirPathResourceFilter resourceFilter;

  @Autowired
  public SendToServerProcessor(
      IGenericClient fhirClient,
      @Value("${fhir.filter.expression}") String fhirPathFilterExpression,
      @Value("${fhir.override-bundle-type-with}") Bundle.BundleType overrideBundleType,
      FhirPathResourceFilter resourceFilter) {
    this.fhirPathFilterExpression = fhirPathFilterExpression;
    this.overrideBundleType = overrideBundleType;
    this.resourceFilter = resourceFilter;
    client = fhirClient;

    this.retryTemplate = new RetryTemplate();

    var backOffPolicy = new ExponentialRandomBackOffPolicy();
    backOffPolicy.setInitialInterval(10_000); // 10 seconds
    backOffPolicy.setMaxInterval(300_000); // 5 minutes

    retryTemplate.setBackOffPolicy(backOffPolicy);

    var retryableExceptions = new HashMap<Class<? extends Throwable>, Boolean>();
    retryableExceptions.put(HttpServerErrorException.class, true);
    retryableExceptions.put(ResourceAccessException.class, true);
    retryableExceptions.put(FhirClientConnectionException.class, true);
    retryableExceptions.put(InternalErrorException.class, true);
    retryableExceptions.put(ResourceNotFoundException.class, false);
    retryableExceptions.put(ResourceVersionConflictException.class, false);

    retryTemplate.setRetryPolicy(new SimpleRetryPolicy(Integer.MAX_VALUE, retryableExceptions));

    this.retryTemplate.registerListener(
        new RetryListenerSupport() {
          @Override
          public <T, E extends Throwable> void onError(
              RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            LOG.warn(
                "Trying to sent resource to FHIR server caused error. {} attempt.",
                context.getRetryCount(),
                throwable);
            sendingFailedCounter.increment();
            if (throwable instanceof BaseServerResponseException fhirException) {
              var operationOutcome = fhirException.getOperationOutcome();
              if (operationOutcome != null) {
                LOG.warn(
                    fhirClient
                        .getFhirContext()
                        .newJsonParser()
                        .setPrettyPrint(true)
                        .encodeResourceToString(operationOutcome));
              }
            }
          }
        });
  }

  @Bean
  public Consumer<Resource> sink() {
    return resource -> {
      if (resource == null) {
        LOG.warn("resource is null. Ignoring.");
        messageNullCounter.increment();
        return;
      }

      MDC.put("resourceId", resource.getIdElement().toUnqualifiedVersionless().toString());
      MDC.put("resourceType", resource.getResourceType().name());

      if (!(resource instanceof Bundle bundle)) {
        LOG.warn("Can only process resources of type Bundle. Ignoring.");
        unsupportedResourceTypeCounter.increment();
        return;
      }

      MDC.put(
          "bundleFirstEntryId",
          bundle
              .getEntryFirstRep()
              .getResource()
              .getIdElement()
              .toUnqualifiedVersionless()
              .toString());
      MDC.put(
          "bundleFirstEntryType", bundle.getEntryFirstRep().getResource().getResourceType().name());

      if (overrideBundleType != null) {
        bundle.setType(overrideBundleType);
      }

      var shouldSend = true;

      if (Strings.isNotBlank(fhirPathFilterExpression)) {
        LOG.debug(
            "Applying FHIR path filter {} to resource", kv("expression", fhirPathFilterExpression));

        shouldSend =
            Boolean.TRUE.equals(
                filterDurationTimer.record(
                    () -> resourceFilter.matches(resource, fhirPathFilterExpression)));

        if (shouldSend) {
          LOG.debug("FHIR path filter matched");
          filterMatchedCounter.increment();
        } else {
          LOG.debug("Filtered out resource via path expression");
        }
      }

      if (shouldSend) {
        LOG.debug("Sending Bundle to server");
        retryTemplate.execute(context -> client.transaction().withBundle(bundle).execute());
      }
    };
  }
}
