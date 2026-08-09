package dev.buhanzaz.rwms.logistics.eventing;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads logistics-owned technical recovery queues without claiming, retrying, or completing work.
 *
 * <p>This adapter is the single low-level SQL boundary for operational observations spanning the
 * main transactional outbox, sanitized DLT, rental-inquiry outbox, warehouse-operation marks, and
 * inbound gap/checkpoint state. It deliberately returns only counts and timestamps: identifiers,
 * topics, payloads, and error text never cross into metric labels.
 */
@Component
public final class LogisticsRecoveryObservationStore {
  private final JdbcTemplate jdbc;

  /** Creates the read-only observation adapter over the logistics database. */
  public LogisticsRecoveryObservationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Returns main outbox rows that still require broker acknowledgement. */
  long countMainOutboxBacklog() {
    return count("select count(*) from outbox_event where status in ('PENDING','IN_FLIGHT')");
  }

  /** Returns the creation time of the oldest main outbox row still awaiting acknowledgement. */
  Optional<OffsetDateTime> findOldestMainOutboxBacklogCreatedAt() {
    return oldest(
        "select min(created_at) from outbox_event where status in ('PENDING','IN_FLIGHT')");
  }

  /** Returns main outbox rows requiring reviewed terminal recovery. */
  long countTerminalMainOutbox() {
    return count("select count(*) from outbox_event where status in ('DLT','QUARANTINED')");
  }

  /** Returns sanitized DLT rows that remain publishable or in a current relay lease. */
  long countSanitizedDltBacklog() {
    return count(
        "select count(*) from sanitized_dead_letter where status in ('PENDING','IN_FLIGHT')");
  }

  /** Returns the oldest creation time among recoverable sanitized DLT rows. */
  Optional<OffsetDateTime> findOldestSanitizedDltBacklogCreatedAt() {
    return oldest(
        "select min(created_at) from sanitized_dead_letter "
            + "where status in ('PENDING','IN_FLIGHT')");
  }

  /** Returns sanitized DLT rows whose bounded relay attempts ended in terminal failure. */
  long countTerminalSanitizedDlt() {
    return count("select count(*) from sanitized_dead_letter where status = 'FAILED'");
  }

  /** Returns rental-inquiry booking events that have not been acknowledged by Kafka. */
  long countRentalInquiryOutboxBacklog() {
    return count("select count(*) from rental_inquiry_outbox where status = 'PENDING'");
  }

  /** Returns the oldest creation time in the pending rental-inquiry booking outbox. */
  Optional<OffsetDateTime> findOldestRentalInquiryOutboxBacklogCreatedAt() {
    return oldest(
        "select min(created_at) from rental_inquiry_outbox where status = 'PENDING'");
  }

  /** Returns warehouse-operation marks that remain claimable, retryable, or currently leased. */
  long countWarehouseMarkBacklog() {
    return count(
        "select count(*) from warehouse_operation_mark_outbox "
            + "where state in ('PENDING','RETRY_PENDING','IN_FLIGHT')");
  }

  /** Returns the oldest creation time among unfinished warehouse-operation marks. */
  Optional<OffsetDateTime> findOldestWarehouseMarkBacklogCreatedAt() {
    return oldest(
        "select min(created_at) from warehouse_operation_mark_outbox "
            + "where state in ('PENDING','RETRY_PENDING','IN_FLIGHT')");
  }

  /** Returns warehouse-operation marks held for explicit reviewed recovery. */
  long countTerminalWarehouseMarks() {
    return count(
        "select count(*) from warehouse_operation_mark_outbox where state = 'QUARANTINED'");
  }

  /** Returns retained inbound aggregate-version gaps that remain unresolved. */
  long countOpenInboundGaps() {
    return count("select count(*) from version_gap_quarantine where status = 'OPEN'");
  }

  /** Returns when the oldest retained open inbound gap was detected. */
  Optional<OffsetDateTime> findOldestOpenInboundGapDetectedAt() {
    return oldest(
        "select min(detected_at) from version_gap_quarantine where status = 'OPEN'");
  }

  /** Returns inbound aggregate checkpoints blocked from ordered projection. */
  long countBlockedInboundCheckpoints() {
    return count("select count(*) from consumer_aggregate_checkpoint where blocked");
  }

  /** Executes one scalar count query without translating an unavailable database into zero. */
  private long count(String sql) {
    Long result = jdbc.queryForObject(sql, Long.class);
    return result == null ? 0 : result;
  }

  /**
   * Executes one scalar oldest-time query and preserves an empty queue as {@link Optional#empty}.
   */
  private Optional<OffsetDateTime> oldest(String sql) {
    return Optional.ofNullable(jdbc.queryForObject(sql, OffsetDateTime.class));
  }
}
