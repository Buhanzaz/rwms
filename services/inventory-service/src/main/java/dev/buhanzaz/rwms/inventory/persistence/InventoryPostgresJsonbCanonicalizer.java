package dev.buhanzaz.rwms.inventory.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Produces PostgreSQL's exact {@code jsonb::text} representation of already serialized JSON.
 *
 * <p>This adapter performs no event validation or domain persistence. Its output is the canonical
 * UTF-8 text used by technical inbox payload storage and frozen-plan fingerprinting.
 */
@Repository
public class InventoryPostgresJsonbCanonicalizer {
  private final JdbcTemplate jdbc;

  public InventoryPostgresJsonbCanonicalizer(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Canonicalizes a JSON document through PostgreSQL without reading or mutating inventory tables.
   *
   * @param serializedJson complete JSON serialized by the caller
   * @return PostgreSQL's {@code jsonb::text} value, or {@code null} if the driver reports SQL NULL
   */
  public String canonicalize(String serializedJson) {
    return jdbc.queryForObject("select (?::jsonb)::text", String.class, serializedJson);
  }
}
