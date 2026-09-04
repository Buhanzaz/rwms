package dev.buhanzaz.rwms.warehouse.eventing;

import dev.buhanzaz.rwms.warehouse.service.WarehouseConflictException;
import dev.buhanzaz.rwms.warehouse.service.WarehouseNotFoundException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable, administrator-reviewed recovery for a terminal warehouse outbox event. */
@Repository
public class WarehouseOutboxRecoveryStore {
  private final JdbcTemplate jdbc;
  private final WarehouseOutboxEnvelopeValidator envelopeValidator;

  public WarehouseOutboxRecoveryStore(
      JdbcTemplate jdbc, WarehouseOutboxEnvelopeValidator envelopeValidator) {
    this.jdbc = jdbc;
    this.envelopeValidator = envelopeValidator;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RecoveryResult recover(
      UUID eventId,
      long expectedReviewVersion,
      UUID reviewedBySubjectId,
      String reason) {
    if (eventId == null
        || expectedReviewVersion < 0
        || reviewedBySubjectId == null
        || reason == null
        || reason.isBlank()
        || reason.trim().length() > 2000) {
      throw new IllegalArgumentException("Warehouse outbox recovery command is invalid");
    }
    String normalizedReason = reason.trim();
    RecoveryRow row = requireRow(eventId);
    envelopeValidator.validateOrThrow(row.envelope());

    if (isExactReplay(row, expectedReviewVersion, reviewedBySubjectId, normalizedReason)) {
      return result(row, true);
    }
    if (row.reviewVersion() != expectedReviewVersion) {
      throw conflict("Warehouse outbox recovery review version is stale");
    }
    if (!isTerminal(row.status())) {
      throw conflict("Only a DLT or quarantined warehouse outbox event can be recovered");
    }
    if (!isFirstUnpublishedHead(row)) {
      throw conflict("Only the first unpublished warehouse aggregate event can be recovered");
    }

    long nextReviewVersion;
    try {
      nextReviewVersion = Math.addExact(row.reviewVersion(), 1);
    } catch (ArithmeticException exception) {
      throw conflict("Warehouse outbox recovery review version is exhausted");
    }
    OffsetDateTime reviewedAt = databaseNow();
    jdbc.update(
        """
        insert into warehouse_outbox_recovery_audit(
          id,event_id,aggregate_type,aggregate_id,aggregate_version,review_version,
          prior_status,prior_attempt_count,prior_last_error_code,envelope_sha256,
          reviewed_by_subject_id,reason,reviewed_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        UUID.randomUUID(),
        row.eventId(),
        row.envelope().aggregateType(),
        row.envelope().aggregateId(),
        row.envelope().aggregateVersion(),
        nextReviewVersion,
        row.status(),
        row.attemptCount(),
        row.lastErrorCode(),
        row.envelope().envelopeSha256().trim(),
        reviewedBySubjectId,
        normalizedReason,
        reviewedAt);
    int changed =
        jdbc.update(
            """
            update outbox_event
               set status='PENDING',attempt_count=0,next_attempt_at=?,dlt_at=null,
                   lease_owner=null,lease_token=null,lease_until=null,
                   review_version=?,last_reviewed_by_subject_id=?,last_recovery_reason=?,
                   last_recovered_at=?
             where event_id=? and status=? and attempt_count=? and review_version=?
            """,
            reviewedAt,
            nextReviewVersion,
            reviewedBySubjectId,
            normalizedReason,
            reviewedAt,
            row.eventId(),
            row.status(),
            row.attemptCount(),
            expectedReviewVersion);
    if (changed != 1) throw conflict("Warehouse outbox event changed during recovery");
    return new RecoveryResult(
        row.eventId(),
        row.envelope().aggregateType(),
        row.envelope().aggregateId(),
        row.envelope().aggregateVersion(),
        "PENDING",
        0,
        nextReviewVersion,
        row.lastErrorCode(),
        reviewedBySubjectId,
        normalizedReason,
        reviewedAt,
        false);
  }

  private RecoveryRow requireRow(UUID eventId) {
    return jdbc
        .query(
            """
            select event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,topic,
                   occurred_at,recorded_at,envelope_body::text,envelope_sha256,
                   status,attempt_count,last_error_code,review_version,
                   last_reviewed_by_subject_id,last_recovery_reason,last_recovered_at
              from outbox_event event
             where event.event_id=?
             for update
            """,
            (rs, ignored) ->
                new RecoveryRow(
                    rs.getObject("event_id", UUID.class),
                    new WarehouseOutboxEnvelopeValidator.ImmutableOutboxEnvelope(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getLong("aggregate_version"),
                        rs.getString("event_type"),
                        rs.getInt("event_version"),
                        rs.getString("topic"),
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getObject("recorded_at", OffsetDateTime.class),
                        rs.getString("envelope_body"),
                        rs.getString("envelope_sha256")),
                    rs.getString("status"),
                    rs.getInt("attempt_count"),
                    rs.getString("last_error_code"),
                    rs.getLong("review_version"),
                    rs.getObject("last_reviewed_by_subject_id", UUID.class),
                    rs.getString("last_recovery_reason"),
                    rs.getObject("last_recovered_at", OffsetDateTime.class)),
            eventId)
        .stream()
        .findFirst()
        .orElseThrow(WarehouseNotFoundException::new);
  }

  private boolean isExactReplay(
      RecoveryRow row, long expectedReviewVersion, UUID reviewedBySubjectId, String reason) {
    if (expectedReviewVersion == Long.MAX_VALUE
        || row.reviewVersion() != expectedReviewVersion + 1
        || !reviewedBySubjectId.equals(row.lastReviewedBySubjectId())
        || !reason.equals(row.lastRecoveryReason())
        || row.lastRecoveredAt() == null) {
      return false;
    }
    Integer count =
        jdbc.queryForObject(
            """
            select count(*)
              from warehouse_outbox_recovery_audit
             where event_id=? and review_version=? and reviewed_by_subject_id=? and reason=?
               and reviewed_at=?
            """,
            Integer.class,
            row.eventId(),
            row.reviewVersion(),
            reviewedBySubjectId,
            reason,
            row.lastRecoveredAt());
    if (count != null && count == 1) return true;
    throw conflict("Warehouse outbox recovery audit does not match the reviewed event");
  }

  private boolean isFirstUnpublishedHead(RecoveryRow row) {
    Integer blockers =
        jdbc.queryForObject(
            """
            select count(*)
              from outbox_event earlier
             where earlier.aggregate_type=? and earlier.aggregate_id=?
               and earlier.aggregate_version < ? and earlier.status <> 'PUBLISHED'
            """,
            Integer.class,
            row.envelope().aggregateType(),
            row.envelope().aggregateId(),
            row.envelope().aggregateVersion());
    return blockers != null && blockers == 0;
  }

  private OffsetDateTime databaseNow() {
    OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    if (value == null) throw new IllegalStateException("Warehouse database clock returned null");
    return value.withOffsetSameInstant(ZoneOffset.UTC);
  }

  private static boolean isTerminal(String status) {
    return "DLT".equals(status) || "QUARANTINED".equals(status);
  }

  private static RecoveryResult result(RecoveryRow row, boolean replayed) {
    return new RecoveryResult(
        row.eventId(),
        row.envelope().aggregateType(),
        row.envelope().aggregateId(),
        row.envelope().aggregateVersion(),
        row.status(),
        row.attemptCount(),
        row.reviewVersion(),
        row.lastErrorCode(),
        row.lastReviewedBySubjectId(),
        row.lastRecoveryReason(),
        row.lastRecoveredAt(),
        replayed);
  }

  private static WarehouseConflictException conflict(String message) {
    return new WarehouseConflictException(message);
  }

  private record RecoveryRow(
      UUID eventId,
      WarehouseOutboxEnvelopeValidator.ImmutableOutboxEnvelope envelope,
      String status,
      int attemptCount,
      String lastErrorCode,
      long reviewVersion,
      UUID lastReviewedBySubjectId,
      String lastRecoveryReason,
      OffsetDateTime lastRecoveredAt) {}

  public record RecoveryResult(
      UUID eventId,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      String status,
      int attemptCount,
      long reviewVersion,
      String lastErrorCode,
      UUID reviewedBySubjectId,
      String recoveryReason,
      OffsetDateTime recoveredAt,
      boolean replayed) {}
}
