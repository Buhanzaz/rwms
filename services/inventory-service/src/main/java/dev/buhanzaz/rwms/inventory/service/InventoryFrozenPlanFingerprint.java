package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.eventing.InventoryEventChecksum;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL-jsonb checksum used by maintenance for immutable inventory-plan snapshots.
 *
 * <p>Keeping this separate from generic inventory command canonicalization avoids changing the
 * established idempotency and validation digests while making the V12 data rewrite and future
 * maintenance responses byte-compatible.
 */
@Component
public class InventoryFrozenPlanFingerprint {
  private final ObjectMapper mapper;
  private final JdbcTemplate jdbc;

  public InventoryFrozenPlanFingerprint(ObjectMapper mapper, JdbcTemplate jdbc) {
    this.mapper = mapper;
    this.jdbc = jdbc;
  }

  public String sha256(Object value) {
    try {
      String serialized = mapper.writeValueAsString(value);
      String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, serialized);
      if (canonical == null) {
        throw new IllegalStateException("PostgreSQL did not canonicalize frozen inventory plan");
      }
      return InventoryEventChecksum.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Frozen inventory plan cannot be canonicalized", exception);
    }
  }
}
