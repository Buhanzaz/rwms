package dev.buhanzaz.rwms.asset.service;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Owns inventory-boundary JSON serialization, deterministic request hashes, and persisted-value
 * decoding.
 *
 * <p>It has no repository, authorization, lock, or workflow dependency: callers retain ownership
 * of the identities and transactions that make a serialized value meaningful.
 */
@Service
final class InventoryAssetCodec {
  private final ObjectMapper mapper;

  InventoryAssetCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  String canonicalHash(Object value) {
    try {
      String canonical =
          mapper
              .writer()
              .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsString(value);
      return AssetChecksum.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory asset value cannot be canonicalized", exception);
    }
  }

  String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory asset value cannot be serialized", exception);
    }
  }

  <T> T read(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored inventory asset value is corrupt", exception);
    }
  }

  <T> T read(String value, TypeReference<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored inventory asset value is corrupt", exception);
    }
  }
}
