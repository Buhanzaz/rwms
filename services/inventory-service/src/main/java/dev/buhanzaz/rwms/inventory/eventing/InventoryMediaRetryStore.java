package dev.buhanzaz.rwms.inventory.eventing;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Persistence boundary for retrying inventory inbox records without losing their identity.
 */
@Repository
public class InventoryMediaRetryStore {
  private static final String CONSUMER = "inventory-service-media-inbox-v1";
  private static final String TOPIC = "rwms.media.media.v1";
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final InventoryDeadLetterStore deadLetters;

  public InventoryMediaRetryStore(
      JdbcTemplate jdbc, ObjectMapper mapper, InventoryDeadLetterStore deadLetters) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.deadLetters = deadLetters;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean scheduleInitial(byte[] bytes) {
    return scheduleInitial(bytes, null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean scheduleInitial(byte[] bytes, byte[] recordKey) {
    String raw = new String(bytes, StandardCharsets.UTF_8);
    try {
      JsonNode root = mapper.readTree(raw);
      UUID eventId = UUID.fromString(root.path("eventId").asText());
      long version = root.path("aggregateVersion").asLong(-1);
      if (version < 0) return false;
      String body = jdbc.queryForObject("select (?::jsonb)::text", String.class, raw);
      if (body == null) return false;
      String hash = InventoryEventChecksum.sha256(bytes);
      String aggregateId = root.path("aggregateId").asText();
      String key =
          recordKey == null
              ? aggregateId
              : new String(recordKey, StandardCharsets.UTF_8);
      if (!aggregateId.equals(key)) return false;
      jdbc.update(
          """
            insert into inbox_message(
              consumer_group,event_id,source_topic,aggregate_type,aggregate_id,record_key,aggregate_version,
              event_type,payload_sha256,envelope_body,status,attempt_count,received_at,next_attempt_at)
          values (?,?,'rwms.media.media.v1','MEDIA',?,?,?,?,?,?::jsonb,'RETRY',1,
            clock_timestamp(),clock_timestamp()+interval '1 second')
          on conflict do nothing
          """,
          CONSUMER,
          eventId,
          aggregateId,
          key,
          version,
          root.path("eventType").asText(),
          hash,
          body);
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  @Transactional(readOnly = true)
  public Optional<UUID> due() {
    return jdbc
        .query(
            """
            select event_id from inbox_message where consumer_group=? and status='RETRY'
              and next_attempt_at<=clock_timestamp() order by next_attempt_at,received_at limit 1
            """,
            (resultSet, rowNumber) -> resultSet.getObject("event_id", UUID.class),
            CONSUMER)
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failed(UUID eventId) {
    RetryState state =
        jdbc.queryForObject(
            """
            select attempt_count,payload_sha256 from inbox_message
             where consumer_group=? and event_id=? and status='RETRY' for update
            """,
            (resultSet, rowNumber) ->
                new RetryState(
                    resultSet.getInt("attempt_count"),
                    resultSet.getString("payload_sha256").trim()),
            CONSUMER,
            eventId);
    if (state == null) return;
    if (state.attempts() >= 3) {
      jdbc.update(
          """
          update inbox_message set status='DLT',attempt_count=attempt_count+1,
            dlt_at=clock_timestamp(),next_attempt_at=null,quarantine_reason='PROCESSING_FAILED'
           where consumer_group=? and event_id=? and status='RETRY'
          """,
          CONSUMER,
          eventId);
      deadLetters.record("PROCESSING_FAILED", state.hash(), TOPIC, eventId);
      return;
    }
    int nextAttempts = state.attempts() + 1;
    long delaySeconds = 1L << (nextAttempts - 1);
    jdbc.update(
        """
        update inbox_message set attempt_count=?,
          next_attempt_at=clock_timestamp()+(?*interval '1 second')
         where consumer_group=? and event_id=? and status='RETRY'
        """,
        nextAttempts,
        delaySeconds,
        CONSUMER,
        eventId);
  }

  private record RetryState(int attempts, String hash) {}
}
