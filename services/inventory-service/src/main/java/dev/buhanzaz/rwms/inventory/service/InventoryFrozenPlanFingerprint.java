package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.eventing.InventoryEventChecksum;
import dev.buhanzaz.rwms.inventory.persistence.InventoryPostgresJsonbCanonicalizer;
import java.nio.charset.StandardCharsets;
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
  private final InventoryPostgresJsonbCanonicalizer jsonb;

  public InventoryFrozenPlanFingerprint(
      ObjectMapper mapper, InventoryPostgresJsonbCanonicalizer jsonb) {
    this.mapper = mapper;
    this.jsonb = jsonb;
  }

  /**
   * Hashes the exact PostgreSQL {@code jsonb::text} bytes used by the V12 frozen-plan rewrite.
   *
   * <p>Changing the serializer alone cannot alter the fingerprint because PostgreSQL first
   * canonicalizes object key order and representation before the UTF-8 SHA-256 is calculated.
   */
  public String sha256(Object value) {
    try {
      String serialized = mapper.writeValueAsString(value);
      String canonical = jsonb.canonicalize(serialized);
      if (canonical == null) {
        throw new IllegalStateException("PostgreSQL did not canonicalize frozen inventory plan");
      }
      return InventoryEventChecksum.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Frozen inventory plan cannot be canonicalized", exception);
    }
  }
}
