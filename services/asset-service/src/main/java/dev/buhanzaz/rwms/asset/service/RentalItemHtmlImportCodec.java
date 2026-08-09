package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.HtmlImportPlan;
import static dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.HtmlImportRowDecision;

import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportRow;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser.ParsedRow;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the canonical serialized values used by the durable HTML-import aggregate.
 *
 * <p>It deliberately contains no plan, commit, media, repository, or authorization decisions:
 * callers provide already-decided values and receive the exact bounded JSON, hash, or stable key
 * representation needed by the persisted workflow.
 */
@Component
public class RentalItemHtmlImportCodec {
  private final ObjectMapper objectMapper;

  public RentalItemHtmlImportCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  ParsedRow parsed(RentalItemHtmlImportRow row) {
    try {
      return objectMapper.readerFor(ParsedRow.class).readValue(row.getParsedJson());
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored HTML import row is corrupt", exception);
    }
  }

  ParsedRow withoutPhotoPublicKey(ParsedRow source) {
    return new ParsedRow(
        source.sourceRowId(),
        source.sourcePosition(),
        source.sourceNumber(),
        source.proposedNumber(),
        source.identityMatchKey(),
        source.rentalType(),
        source.dimension(),
        source.finishing(),
        source.category(),
        source.characteristics(),
        source.linoleum(),
        source.storageState(),
        source.status(),
        source.proposedStatus(),
        source.comment(),
        null,
        source.furniture(),
        source.shipmentDate(),
        source.tenant(),
        source.price(),
        source.diagnostics());
  }

  HtmlImportPlan readPlan(String json) {
    if (json == null || json.isBlank() || "{}".equals(json.trim())) {
      return new HtmlImportPlan(List.of(), List.of(), List.of(), List.of());
    }
    try {
      return objectMapper.readerFor(HtmlImportPlan.class).readValue(json);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored HTML import plan is corrupt", exception);
    }
  }

  HtmlImportRowDecision readDecision(String json) {
    if (json == null || json.isBlank() || "{}".equals(json.trim())) return null;
    try {
      return objectMapper.readerFor(HtmlImportRowDecision.class).readValue(json);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored HTML import row decision is corrupt", exception);
    }
  }

  <T> T read(JsonNode node, Class<T> type) {
    try {
      return objectMapper.readerFor(type).readValue(node);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored idempotent HTML import response is corrupt", exception);
    }
  }

  Map<String, Object> readMap(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored rental passport is corrupt", exception);
    }
  }

  String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("HTML import value cannot be serialized", exception);
    }
  }

  String hash(Object value) {
    try {
      return AssetChecksum.sha256(objectMapper.writeValueAsBytes(value));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("HTML import command cannot be fingerprinted", exception);
    }
  }

  UUID stableKey(String namespace, UUID importId, String... values) {
    StringBuilder source = new StringBuilder(namespace).append(':').append(importId);
    for (String value : values) source.append(':').append(value);
    return UUID.nameUUIDFromBytes(source.toString().getBytes(StandardCharsets.UTF_8));
  }
}
