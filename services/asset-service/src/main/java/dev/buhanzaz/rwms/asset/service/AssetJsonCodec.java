package dev.buhanzaz.rwms.asset.service;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the asset service's stable JSON serialization and idempotency-response decoding rules.
 *
 * <p>It is deliberately limited to JSON shape and canonical passport sanitization. Workflow
 * services retain ownership of their command hashes, transactions, locks and events.
 */
@Service
final class AssetJsonCodec {
  private final ObjectMapper mapper;

  AssetJsonCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  String hash(Object value) {
    try {
      return AssetChecksum.sha256(mapper.writeValueAsBytes(value));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Asset command cannot be fingerprinted", exception);
    }
  }

  String jsonObject(Object value) {
    try {
      String serialized = mapper.writeValueAsString(value == null ? Map.of() : value);
      Map<String, Object> passport =
          mapper.readValue(serialized, new TypeReference<Map<String, Object>>() {});
      return mapper.writeValueAsString(RentalPassportSanitizer.sanitize(passport));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Asset JSON is invalid", exception);
    }
  }

  String jsonArray(Object value) {
    try {
      return mapper.writeValueAsString(value == null ? List.of() : value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Asset JSON is invalid", exception);
    }
  }

  Map<String, Object> map(String value) {
    try {
      Map<String, Object> passport =
          mapper.readValue(value, new TypeReference<Map<String, Object>>() {});
      return RentalPassportSanitizer.sanitize(passport);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored rental passport is corrupt", exception);
    }
  }

  List<String> strings(String value) {
    try {
      return mapper.readValue(value, new TypeReference<List<String>>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored rental tags are corrupt", exception);
    }
  }

  <T> T read(JsonNode node, Class<T> type) {
    try {
      return mapper.readerFor(type).readValue(node);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored idempotent response is corrupt", exception);
    }
  }

  /** Decodes durable typed movement context while preserving its generic unit-requirement shape. */
  <T> T read(String value, TypeReference<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored asset JSON is corrupt", exception);
    }
  }
}
