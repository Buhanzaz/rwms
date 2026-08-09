package dev.buhanzaz.rwms.asset.disposition;

import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyDispositionEffect;
import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Owns canonical JSON encoding, request fingerprints, and decoding of durable disposition
 * values.
 *
 * <p>Its only database interaction is PostgreSQL JSONB canonicalization. It deliberately does
 * not make workflow, authorization, repository, or transaction decisions.
 */
@Service
final class PropertyDispositionCodec {
  private final ObjectMapper mapper;
  private final JdbcTemplate jdbc;

  PropertyDispositionCodec(ObjectMapper mapper, JdbcTemplate jdbc) {
    this.mapper = mapper;
    this.jdbc = jdbc;
  }

  String hash(Object value) {
    try {
      return AssetChecksum.sha256(
          mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Property disposition request cannot be fingerprinted", exception);
    }
  }

  String canonicalJson(Object value) {
    try {
      String raw = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);
      String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, raw);
      if (canonical == null) {
        throw new IllegalStateException("PostgreSQL did not canonicalize property disposition JSON");
      }
      return canonical;
    } catch (JacksonException exception) {
      throw new IllegalStateException("Property disposition JSON cannot be serialized", exception);
    }
  }

  MaintenancePropertyDispositionEffect effect(String responseBody) {
    try {
      return mapper.readValue(responseBody, MaintenancePropertyDispositionEffect.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored property disposition effect is unreadable", exception);
    }
  }

  Map<String, Object> map(String value) {
    try {
      return mapper.readValue(value, new TypeReference<Map<String, Object>>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored rental-item passport is invalid", exception);
    }
  }

  List<Object> list(String value) {
    try {
      return mapper.readValue(value, new TypeReference<List<Object>>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored rental-item tags are invalid", exception);
    }
  }
}
