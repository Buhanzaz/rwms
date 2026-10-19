package dev.buhanzaz.rwms.maintenance.eventing;

import dev.buhanzaz.rwms.maintenance.service.MaintenanceChecksum;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL-jsonb canonicalization used by immutable checksums that must stay byte-compatible
 * with Flyway-authored historical rows.
 */
@Component
public class MaintenanceJsonbCanonicalizer {
  private final ObjectMapper mapper;
  private final JdbcTemplate jdbc;

  public MaintenanceJsonbCanonicalizer(ObjectMapper mapper, JdbcTemplate jdbc) {
    this.mapper = mapper;
    this.jdbc = jdbc;
  }

  public String sha256(Object value) {
    try {
      String json = mapper.writeValueAsString(value);
      String canonical =
          jdbc.queryForObject("select (?::jsonb)::text", String.class, json);
      if (canonical == null) {
        throw new IllegalStateException("PostgreSQL did not canonicalize maintenance JSON");
      }
      return MaintenanceChecksum.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException(
          "Maintenance value cannot be canonicalized", exception);
    }
  }
}
