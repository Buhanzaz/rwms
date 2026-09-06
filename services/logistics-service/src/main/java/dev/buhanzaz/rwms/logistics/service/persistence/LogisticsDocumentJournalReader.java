package dev.buhanzaz.rwms.logistics.service.persistence;

import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Reads bounded immutable document events from the logistics-owned domain journal. */
@Component
@RequiredArgsConstructor
public class LogisticsDocumentJournalReader {
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public List<JournalEvent> readPage(
      String aggregateType,
      UUID aggregateId,
      long afterVersion,
      long currentVersion,
      int limit) {
    return jdbc.query(
        """
        select event_id,aggregate_version,event_type,occurred_at,recorded_at,baseline,
          actor_ref::text,payload->>'state' as state,payload->>'resultCode' as result_code
        from domain_event
        where aggregate_type=? and aggregate_id=? and aggregate_version>? and aggregate_version<=?
        order by aggregate_version asc limit ?
        """,
        (result, row) -> {
          String actor = result.getString("actor_ref");
          return new JournalEvent(
              result.getObject("event_id", UUID.class),
              result.getLong("aggregate_version"),
              result.getString("event_type"),
              result.getObject("occurred_at", OffsetDateTime.class),
              result.getObject("recorded_at", OffsetDateTime.class),
              result.getBoolean("baseline"),
              actor == null ? null : objectMapper.readValue(actor, OpaqueActorReference.class),
              result.getString("state"),
              result.getString("result_code"));
        },
        aggregateType,
        aggregateId.toString(),
        afterVersion,
        currentVersion,
        limit);
  }

  /** Technical journal row whose domain-state value is decoded by the owning application service. */
  public record JournalEvent(
      UUID eventId,
      long aggregateVersion,
      String eventType,
      OffsetDateTime occurredAt,
      OffsetDateTime recordedAt,
      boolean baseline,
      OpaqueActorReference actor,
      String state,
      String resultCode) {}
}
