package dev.buhanzaz.rwms.inventory.eventing;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable inbox and retry persistence for logistics normal-return facts. */
@Repository
public class InventoryLogisticsReturnInboxStore {
  public static final String CONSUMER = "inventory-service-return-inspection-inbox-v1";

  private final JdbcTemplate jdbc;

  public InventoryLogisticsReturnInboxStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<String> payloadSha256(UUID eventId) {
    return jdbc
        .query(
            "select payload_sha256 from inbox_message where consumer_group=? and event_id=?",
            (resultSet, rowNumber) -> resultSet.getString("payload_sha256").trim(),
            CONSUMER,
            eventId)
        .stream()
        .findFirst();
  }

  public int insertReceived(
      Source source,
      UUID eventId,
      UUID aggregateId,
      long aggregateVersion,
      String eventType,
      String payloadSha256,
      String canonicalEnvelope) {
    return jdbc.update(
        """
        insert into inbox_message(
          consumer_group,event_id,source_topic,aggregate_type,aggregate_id,record_key,
          aggregate_version,event_type,payload_sha256,envelope_body,status,attempt_count,received_at)
        values (?,?,?,?,?,?,?,?,?,?::jsonb,
          'RECEIVED',0,clock_timestamp())
        on conflict do nothing
        """,
        CONSUMER,
        eventId,
        source.topic(),
        source.aggregateType(),
        aggregateId.toString(),
        aggregateId.toString(),
        aggregateVersion,
        eventType,
        payloadSha256,
        canonicalEnvelope);
  }

  public Optional<RetryEnvelope> retryEnvelope(UUID eventId) {
    return jdbc
        .query(
            """
            select envelope_body::text,record_key,payload_sha256,source_topic from inbox_message
             where consumer_group=? and event_id=? and status='RETRY'
            """,
            (resultSet, rowNumber) ->
                new RetryEnvelope(
                    resultSet.getString("envelope_body"),
                    resultSet.getString("record_key"),
                    resultSet.getString("payload_sha256").trim(),
                    Source.fromTopic(resultSet.getString("source_topic"))),
            CONSUMER,
            eventId)
        .stream()
        .findFirst();
  }

  public Optional<String> lockRetryHash(UUID eventId) {
    return jdbc
        .query(
            """
            select payload_sha256 from inbox_message
             where consumer_group=? and event_id=? and status='RETRY' for update
            """,
            (resultSet, rowNumber) -> resultSet.getString("payload_sha256").trim(),
            CONSUMER,
            eventId)
        .stream()
        .findFirst();
  }

  public void markValidationRejected(UUID eventId) {
    jdbc.update(
        """
        update inbox_message set status='DLT',dlt_at=clock_timestamp(),next_attempt_at=null,
          quarantine_reason='VALIDATION_REJECTED'
         where consumer_group=? and event_id=?
        """,
        CONSUMER,
        eventId);
  }

  public void markProcessed(UUID eventId) {
    jdbc.update(
        """
        update inbox_message set status='PROCESSED',processed_at=clock_timestamp(),
          next_attempt_at=null,dlt_at=null,quarantine_reason=null
         where consumer_group=? and event_id=?
        """,
        CONSUMER,
        eventId);
  }

  public void insertInitialRetry(
      Source source,
      UUID eventId,
      UUID aggregateId,
      String recordKey,
      long aggregateVersion,
      String eventType,
      String payloadSha256,
      String canonicalEnvelope) {
    jdbc.update(
        """
        insert into inbox_message(
          consumer_group,event_id,source_topic,aggregate_type,aggregate_id,record_key,
          aggregate_version,event_type,payload_sha256,envelope_body,status,attempt_count,
          received_at,next_attempt_at)
        values (?,?,?,?,?,?,?,?,?,?::jsonb,
          'RETRY',1,clock_timestamp(),clock_timestamp()+interval '1 second')
        on conflict do nothing
        """,
        CONSUMER,
        eventId,
        source.topic(),
        source.aggregateType(),
        aggregateId.toString(),
        recordKey,
        aggregateVersion,
        eventType,
        payloadSha256,
        canonicalEnvelope);
  }

  public Optional<UUID> dueRetry() {
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

  public RetryState lockRetryState(UUID eventId) {
    return jdbc
        .query(
            """
            select attempt_count,payload_sha256,source_topic from inbox_message
             where consumer_group=? and event_id=? and status='RETRY' for update
            """,
            (resultSet, rowNumber) ->
                new RetryState(
                    resultSet.getInt("attempt_count"),
                    resultSet.getString("payload_sha256").trim(),
                    resultSet.getString("source_topic")),
            CONSUMER,
            eventId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void markProcessingFailedTerminal(UUID eventId) {
    jdbc.update(
        """
        update inbox_message set status='DLT',attempt_count=attempt_count+1,
          dlt_at=clock_timestamp(),next_attempt_at=null,quarantine_reason='PROCESSING_FAILED'
         where consumer_group=? and event_id=? and status='RETRY'
        """,
        CONSUMER,
        eventId);
  }

  public void reschedule(UUID eventId, int attempts, long delaySeconds) {
    jdbc.update(
        """
        update inbox_message set attempt_count=?,
          next_attempt_at=clock_timestamp()+(?*interval '1 second')
         where consumer_group=? and event_id=? and status='RETRY'
        """,
        attempts,
        delaySeconds,
        CONSUMER,
        eventId);
  }

  public record RetryEnvelope(
      String body, String recordKey, String payloadSha256, Source source) {}

  public record RetryState(int attempts, String hash, String sourceTopic) {}

  /** Exact inbound aggregate/topic pair sharing this inspection inbox and retry policy. */
  public enum Source {
    LOGISTICS_RETURN("rwms.logistics.return.v1", "RETURN"),
    MAINTENANCE_ESTIMATE("rwms.maintenance.estimate.v1", "ESTIMATE");

    private final String topic;
    private final String aggregateType;

    Source(String topic, String aggregateType) {
      this.topic = topic;
      this.aggregateType = aggregateType;
    }

    public String topic() {
      return topic;
    }

    public String aggregateType() {
      return aggregateType;
    }

    static Source fromTopic(String topic) {
      for (Source source : values()) {
        if (source.topic.equals(topic)) return source;
      }
      throw new IllegalStateException("Unknown return inspection inbox topic");
    }
  }
}
