package dev.buhanzaz.rwms.inventory.eventing;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Owns the asset-membership consumer's durable {@code inbox_message} records.
 *
 * <p>This is a technical persistence boundary only: it preserves the consumer's SQL-level
 * deduplication, row fences, retry timing and terminal state changes, but never parses an event or
 * decides whether inventory membership should change.
 */
@Repository
public class InventoryAssetInboxStore {
  static final String CONSUMER = "inventory-service-asset-membership-v1";
  static final String TOPIC = "rwms.asset.rental-item.v1";

  private final JdbcTemplate jdbc;

  InventoryAssetInboxStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a received asset fact without changing an existing event-id record.
   *
   * <p>The caller compares the stored payload hash when this returns zero, keeping an exact
   * redelivery a no-op and routing a reused event ID through its DLT path.
   */
  int insertReceived(
      UUID eventId,
      UUID assetId,
      long aggregateVersion,
      String eventType,
      String payloadSha256,
      String canonicalEnvelope) {
    return jdbc.update(
        """
        insert into inbox_message(
          consumer_group,event_id,source_topic,aggregate_type,aggregate_id,record_key,
          aggregate_version,event_type,payload_sha256,envelope_body,status,attempt_count,received_at)
        values (?,?,'rwms.asset.rental-item.v1','RENTAL_ITEM',?,?,?,?,?,?::jsonb,
          'RECEIVED',0,clock_timestamp())
        on conflict do nothing
        """,
        CONSUMER,
        eventId,
        assetId.toString(),
        assetId.toString(),
        aggregateVersion,
        eventType,
        payloadSha256,
        canonicalEnvelope);
  }

  /**
   * Reads the payload hash retained for an event-id conflict after an insert lost the unique race.
   */
  String payloadSha256(UUID eventId) {
    return jdbc.queryForObject(
        "select payload_sha256 from inbox_message where consumer_group=? and event_id=?",
        String.class,
        CONSUMER,
        eventId);
  }

  /**
   * Locks the retry row before an application attempt reads its canonical event body.
   *
   * <p>The enclosing processor transaction retains this fence until it marks the message processed
   * or rolls back for the retry scheduler.
   */
  String lockRetryEnvelope(UUID eventId) {
    return jdbc.queryForObject(
        """
        select envelope_body::text from inbox_message
         where consumer_group=? and event_id=? and status='RETRY' for update
        """,
        String.class,
        CONSUMER,
        eventId);
  }

  /**
   * Reads the broker key from the retry row already locked by {@link #lockRetryEnvelope(UUID)}.
   */
  String retryRecordKey(UUID eventId) {
    return jdbc.queryForObject(
        "select record_key from inbox_message where consumer_group=? and event_id=?",
        String.class,
        CONSUMER,
        eventId);
  }

  /** Marks a retry whose body fails validation as terminal without releasing a future retry. */
  void markValidationRejected(UUID eventId) {
    jdbc.update(
        """
        update inbox_message set status='DLT',dlt_at=clock_timestamp(),next_attempt_at=null,
          quarantine_reason='VALIDATION_REJECTED'
         where consumer_group=? and event_id=?
        """,
        CONSUMER,
        eventId);
  }

  /** Marks a successfully applied fact processed and clears all retry/quarantine state. */
  void markProcessed(UUID eventId) {
    jdbc.update(
        """
        update inbox_message set status='PROCESSED',processed_at=clock_timestamp(),
          next_attempt_at=null,dlt_at=null,quarantine_reason=null
         where consumer_group=? and event_id=?
        """,
        CONSUMER,
        eventId);
  }

  /**
   * Persists the first retry attempt after the original consumer transaction rolled back.
   *
   * <p>A duplicate leaves the prior row untouched, including its existing retry timing.
   */
  void insertInitialRetry(
      UUID eventId,
      UUID assetId,
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
        values (?,?,'rwms.asset.rental-item.v1','RENTAL_ITEM',?,?,?,?,?,?::jsonb,
          'RETRY',1,clock_timestamp(),clock_timestamp()+interval '1 second')
        on conflict do nothing
        """,
        CONSUMER,
        eventId,
        assetId.toString(),
        recordKey,
        aggregateVersion,
        eventType,
        payloadSha256,
        canonicalEnvelope);
  }

  /** Returns the oldest retry whose durable backoff deadline has elapsed. */
  Optional<UUID> dueRetry() {
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

  /**
   * Locks a retry row while the scheduler computes the next backoff or terminal transition.
   */
  RetryState lockRetryState(UUID eventId) {
    return jdbc.queryForObject(
        """
        select attempt_count,payload_sha256 from inbox_message
         where consumer_group=? and event_id=? and status='RETRY' for update
        """,
        (resultSet, rowNumber) ->
            new RetryState(
                resultSet.getInt("attempt_count"), resultSet.getString("payload_sha256").trim()),
        CONSUMER,
        eventId);
  }

  /** Marks a retry exhausted and increments its terminal attempt within the caller's row lock. */
  void markProcessingFailedTerminal(UUID eventId) {
    jdbc.update(
        """
        update inbox_message set status='DLT',attempt_count=attempt_count+1,
          dlt_at=clock_timestamp(),next_attempt_at=null,quarantine_reason='PROCESSING_FAILED'
         where consumer_group=? and event_id=? and status='RETRY'
        """,
        CONSUMER,
        eventId);
  }

  /** Updates the next retry deadline using the caller's already-computed exponential delay. */
  void reschedule(UUID eventId, int attempts, long delaySeconds) {
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

  /** Retry count and payload checksum read under the scheduler's row lock. */
  record RetryState(int attempts, String hash) {}
}
