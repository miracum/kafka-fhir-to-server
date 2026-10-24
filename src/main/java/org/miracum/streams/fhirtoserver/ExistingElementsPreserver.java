package org.miracum.streams.fhirtoserver;

import static net.logstash.logback.argument.StructuredArguments.kv;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps elements that another system manages on the target server from being wiped by a PUT that
 * doesn't include them. For every PUT entry of a configured resource type, the resource's current
 * version is read from the server and each configured element the incoming resource doesn't set
 * itself is copied over from it. The entry also gets an If-Match on that version, so an update made
 * in between the read and the write fails the request instead of being silently overwritten.
 */
@Service
public class ExistingElementsPreserver {
  private static final Logger LOG = LoggerFactory.getLogger(ExistingElementsPreserver.class);

  private final IGenericClient client;
  private final Map<String, List<BaseRuntimeChildDefinition>> elementsByResourceType;

  public ExistingElementsPreserver(IGenericClient client, FhirPreserveElementsConfig config) {
    this.client = client;
    this.elementsByResourceType =
        parseElementPaths(client.getFhirContext(), config.preserveElements());
  }

  public boolean isEnabled() {
    return !elementsByResourceType.isEmpty();
  }

  /**
   * Returns a copy of the given bundle with the configured elements filled in from the server, or
   * the bundle itself if it doesn't contain any PUT entries of a configured resource type. The
   * given bundle is never modified, so calling this again after a version conflict re-reads the
   * server state instead of resending values taken from an outdated version.
   */
  public Bundle preserve(Bundle bundle) {
    if (bundle.getEntry().stream().noneMatch(this::isCandidate)) {
      return bundle;
    }

    var result = bundle.copy();
    var candidates = result.getEntry().stream().filter(this::isCandidate).toList();

    // A batch of GETs reads all candidates in a single request and works the same for both plain
    // ("ResearchStudy/123") and conditional ("ResearchStudy?identifier=...") update urls.
    var readBatch = new Bundle().setType(BundleType.BATCH);
    for (var entry : candidates) {
      readBatch.addEntry().getRequest().setMethod(HTTPVerb.GET).setUrl(entry.getRequest().getUrl());
    }

    LOG.debug(
        "Reading {} existing resources to preserve their elements",
        kv("numCandidates", candidates.size()));

    var response = client.transaction().withBundle(readBatch).execute();

    if (response.getEntry().size() != candidates.size()) {
      throw new IllegalStateException(
          String.format(
              "Expected %d entries in the batch read response, got %d",
              candidates.size(), response.getEntry().size()));
    }

    for (var i = 0; i < candidates.size(); i++) {
      var entry = candidates.get(i);
      findExisting(response.getEntry().get(i), entry)
          .ifPresent(existing -> preserveElements(entry, existing));
    }

    return result;
  }

  private boolean isCandidate(BundleEntryComponent entry) {
    // not entry.hasResource(), which is false for a resource without any elements set
    return entry.getResource() != null
        && entry.getRequest().getMethod() == HTTPVerb.PUT
        && elementsByResourceType.containsKey(entry.getResource().fhirType());
  }

  private Optional<Resource> findExisting(
      BundleEntryComponent readResponseEntry, BundleEntryComponent entry) {
    var url = entry.getRequest().getUrl();
    var status = readResponseEntry.getResponse().getStatus();

    if (status == null || !status.startsWith("2")) {
      if (status != null && (status.startsWith("404") || status.startsWith("410"))) {
        LOG.debug("{} doesn't exist yet, nothing to preserve", kv("url", url));
        return Optional.empty();
      }

      // anything else may well be a resource that does exist - sending the entry as-is would
      // overwrite exactly the elements this is supposed to preserve.
      throw new IllegalStateException(
          String.format("Reading existing resource %s failed with status '%s'", url, status));
    }

    var resourceType = entry.getResource().fhirType();

    if (readResponseEntry.getResource() instanceof Bundle searchResult) {
      // conditional update url: the server resolves it the same way when processing the PUT
      var matches =
          searchResult.getEntry().stream()
              .map(BundleEntryComponent::getResource)
              .filter(r -> r != null && r.fhirType().equals(resourceType))
              .toList();

      if (matches.size() > 1) {
        // the server rejects the conditional update itself in this case, so let it report it
        LOG.warn(
            "Conditional url {} matches {} resources, not preserving any elements",
            kv("url", url),
            kv("numMatches", matches.size()));
        return Optional.empty();
      }

      return matches.stream().findFirst();
    }

    var resource = readResponseEntry.getResource();
    if (resource == null || !resource.fhirType().equals(resourceType)) {
      throw new IllegalStateException(
          String.format("Reading existing resource %s returned no %s", url, resourceType));
    }

    return Optional.of(resource);
  }

  private void preserveElements(BundleEntryComponent entry, Resource existing) {
    var incoming = entry.getResource();

    for (var child : elementsByResourceType.get(incoming.fhirType())) {
      var incomingValues = child.getAccessor().getValues(incoming);
      if (!incomingValues.stream().allMatch(IBase::isEmpty)) {
        continue;
      }

      var existingValues = child.getAccessor().getValues(existing);
      if (existingValues.isEmpty()) {
        continue;
      }

      // clears any empty placeholder values before adding the existing ones
      child.getMutator().setValue(incoming, null);
      for (var value : existingValues) {
        child.getMutator().addValue(incoming, value);
      }

      LOG.debug(
          "Preserved {} of {}",
          kv("element", child.getElementName()),
          kv("url", entry.getRequest().getUrl()));
    }

    if (!entry.getRequest().hasIfMatch() && existing.getMeta().hasVersionId()) {
      entry.getRequest().setIfMatch("W/\"" + existing.getMeta().getVersionId() + "\"");
    }
  }

  /**
   * Resolves "ResourceType.element" paths, e.g. "ResearchStudy.enrollment", to their element
   * definitions. These are plain element paths, not FHIRPath expressions: only top-level elements
   * of a resource are supported.
   */
  static Map<String, List<BaseRuntimeChildDefinition>> parseElementPaths(
      FhirContext fhirContext, List<String> elementPaths) {
    var result = new LinkedHashMap<String, List<BaseRuntimeChildDefinition>>();

    for (var path : elementPaths) {
      // e.g. from a trailing comma in a comma-separated value
      if (path.isBlank()) {
        continue;
      }

      var parts = path.split("\\.");
      if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
        throw new IllegalArgumentException(
            String.format(
                "Invalid element path '%s' in fhir.preserve-elements: expected"
                    + " '<ResourceType>.<element>', e.g. 'ResearchStudy.enrollment'",
                path));
      }

      var resourceDefinition = getResourceDefinition(fhirContext, parts[0], path);
      var child = resourceDefinition.getChildByName(parts[1]);
      if (child == null) {
        throw new IllegalArgumentException(
            String.format(
                "Invalid element path '%s' in fhir.preserve-elements: %s has no element '%s'",
                path, resourceDefinition.getName(), parts[1]));
      }

      result.computeIfAbsent(resourceDefinition.getName(), k -> new ArrayList<>()).add(child);
    }

    return result;
  }

  private static RuntimeResourceDefinition getResourceDefinition(
      FhirContext fhirContext, String resourceType, String path) {
    try {
      return fhirContext.getResourceDefinition(resourceType);
    } catch (DataFormatException e) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid element path '%s' in fhir.preserve-elements: unknown resource type '%s'",
              path, resourceType),
          e);
    }
  }
}
