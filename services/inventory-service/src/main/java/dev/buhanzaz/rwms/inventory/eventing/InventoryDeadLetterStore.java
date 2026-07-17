package dev.buhanzaz.rwms.inventory.eventing;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class InventoryDeadLetterStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public InventoryDeadLetterStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public void record(
      String failureCode, String messageSha256, String sourceTopic, UUID sourceEventId) {
    Map<String, Object> safeBody = new LinkedHashMap<>();
    safeBody.put("failureCode", failureCode);
    safeBody.put("messageSha256", messageSha256);
    safeBody.put("recordedAt", OffsetDateTime.now(ZoneOffset.UTC));
    String body = jdbc.queryForObject("select (?::jsonb)::text", String.class, json(safeBody));
    if (body == null) throw new IllegalStateException("PostgreSQL did not canonicalize DLT JSON");
    jdbc.update(
        """
        insert into sanitized_dead_letter(
          dlt_id,destination,source_topic,source_event_id,message_sha256,failure_code,
          safe_body,body_sha256,status,attempt_count,next_attempt_at,created_at)
        values (?,'rwms.inventory.dlt.v1',?,?,?,?,?::jsonb,?,'PENDING',0,
          clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        sourceTopic,
        sourceEventId,
        messageSha256,
        failureCode,
        body,
        InventoryEventChecksum.sha256(body));
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Sanitized DLT body is not serializable", exception);
    }
  }
}
