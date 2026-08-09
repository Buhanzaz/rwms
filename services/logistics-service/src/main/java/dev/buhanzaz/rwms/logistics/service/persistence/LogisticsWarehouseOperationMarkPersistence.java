package dev.buhanzaz.rwms.logistics.service.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Narrow PostgreSQL persistence adapter for immutable warehouse-operation marks and their bounded
 * recovery state.
 *
 * <p>It retains the original database clock, conflict-safe insert, row locks, {@code SKIP LOCKED}
 * lease claim, and conditional fencing writes. The owning store decides whether a row is valid,
 * retryable, or recoverable and translates conditional-write outcomes into domain failures.
 */
@Repository
public class LogisticsWarehouseOperationMarkPersistence {
  private final JdbcTemplate jdbc;

  public LogisticsWarehouseOperationMarkPersistence(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Reads the PostgreSQL wall clock used by mark claims and recovery writes. */
  public OffsetDateTime databaseNow() {
    OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    if (value == null) {
      throw new IllegalStateException("Database clock returned null");
    }
    return value.withOffsetSameInstant(ZoneOffset.UTC);
  }

  /** Inserts an immutable pending mark only if its warehouse-operation key is absent. */
  public void insertIfAbsent(
      UUID warehouseId,
      UUID operationId,
      OffsetDateTime occurredAt,
      String admissionDirection,
      Long admissionWarehouseVersion,
      OffsetDateTime now) {
    jdbc.update(
        """
        insert into warehouse_operation_mark_outbox(
          operation_id,warehouse_id,occurred_at,admission_direction,admission_warehouse_version,
          state,attempt_count,next_attempt_at,created_at,updated_at)
        values (?,?,?,?,?,'PENDING',0,?,?,?)
        on conflict (warehouse_id,operation_id) do nothing
        """,
        operationId,
        warehouseId,
        occurredAt,
        admissionDirection,
        admissionWarehouseVersion,
        now,
        now,
        now);
  }

  /** Locks and returns the immutable identity columns for one already-persisted mark. */
  public MarkIdentity markIdentityForUpdate(UUID warehouseId, UUID operationId) {
    return jdbc.queryForObject(
        """
        select warehouse_id,occurred_at,admission_direction,admission_warehouse_version
          from warehouse_operation_mark_outbox
         where warehouse_id=? and operation_id=? for update
        """,
        (rs, ignored) ->
            new MarkIdentity(
                rs.getObject("warehouse_id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getString("admission_direction"),
                rs.getObject("admission_warehouse_version", Long.class)),
        warehouseId,
        operationId);
  }

  /** Locks at most one due mark using PostgreSQL {@code FOR UPDATE SKIP LOCKED}. */
  public Optional<Pending> dueForClaim(int maxAttempts, OffsetDateTime claimedAt) {
    return jdbc
        .query(
            """
            select operation_id,warehouse_id,occurred_at,attempt_count,state
              from warehouse_operation_mark_outbox
             where attempt_count < ?
               and ((state in ('PENDING','RETRY_PENDING') and next_attempt_at <= ?)
                 or (state='IN_FLIGHT' and claim_until <= ?))
             order by case when state='IN_FLIGHT' then claim_until else next_attempt_at end,
                      warehouse_id,operation_id
             for update skip locked limit 1
            """,
            (rs, ignored) ->
                new Pending(
                    rs.getObject("operation_id", UUID.class),
                    rs.getObject("warehouse_id", UUID.class),
                    rs.getObject("occurred_at", OffsetDateTime.class),
                    rs.getInt("attempt_count"),
                    rs.getString("state")),
            maxAttempts,
            claimedAt,
            claimedAt)
        .stream()
        .findFirst();
  }

  /** Applies the state-and-attempt fenced transition from a locked due row to an in-flight lease. */
  public int claim(
      Pending pending,
      UUID claimToken,
      OffsetDateTime claimUntil,
      OffsetDateTime claimedAt) {
    return jdbc.update(
        """
        update warehouse_operation_mark_outbox
           set state='IN_FLIGHT',claim_token=?,claim_until=?,updated_at=?
         where warehouse_id=? and operation_id=? and state=? and attempt_count=?
        """,
        claimToken,
        claimUntil,
        claimedAt,
        pending.warehouseId(),
        pending.operationId(),
        pending.state(),
        pending.attemptCount());
  }

  /** Applies the claim-token-and-attempt fenced confirmation write. */
  public int confirm(
      UUID warehouseId,
      UUID operationId,
      UUID claimToken,
      int attemptCount,
      OffsetDateTime now) {
    return jdbc.update(
        """
        update warehouse_operation_mark_outbox
           set state='CONFIRMED',attempt_count=attempt_count+1,
               claim_token=null,claim_until=null,last_error_code=null,updated_at=?
         where warehouse_id=? and operation_id=? and state='IN_FLIGHT'
           and claim_token=? and attempt_count=?
        """,
        now,
        warehouseId,
        operationId,
        claimToken,
        attemptCount);
  }

  /** Applies the claim-token-and-attempt fenced retry or quarantine write. */
  public int fail(
      UUID warehouseId,
      UUID operationId,
      UUID claimToken,
      int priorAttemptCount,
      String nextState,
      int nextAttemptCount,
      OffsetDateTime nextAttemptAt,
      String safeErrorCode,
      OffsetDateTime now) {
    return jdbc.update(
        """
        update warehouse_operation_mark_outbox
           set state=?,attempt_count=?,next_attempt_at=?,claim_token=null,claim_until=null,
               last_error_code=?,updated_at=?
         where warehouse_id=? and operation_id=? and state='IN_FLIGHT'
           and claim_token=? and attempt_count=?
        """,
        nextState,
        nextAttemptCount,
        nextAttemptAt,
        safeErrorCode,
        now,
        warehouseId,
        operationId,
        claimToken,
        priorAttemptCount);
  }

  /** Locks and returns the recovery columns of a mark, failing absent-row handling to the caller. */
  public Optional<RecoveryRow> recoveryForUpdate(UUID warehouseId, UUID operationId) {
    return jdbc
        .query(
            """
            select state,attempt_count,last_error_code,recovery_version,
                   recovered_by_subject_id,recovery_reason,recovered_at
              from warehouse_operation_mark_outbox
             where warehouse_id=? and operation_id=?
             for update
            """,
            (rs, ignored) ->
                new RecoveryRow(
                    rs.getString("state"),
                    rs.getInt("attempt_count"),
                    rs.getString("last_error_code"),
                    rs.getLong("recovery_version"),
                    rs.getObject("recovered_by_subject_id", UUID.class),
                    rs.getString("recovery_reason"),
                    rs.getObject("recovered_at", OffsetDateTime.class)),
            warehouseId,
            operationId)
        .stream()
        .findFirst();
  }

  /** Appends the immutable audit row for a reviewed recovery attempt. */
  public void appendRecoveryAudit(
      UUID auditId,
      UUID warehouseId,
      UUID operationId,
      long recoveryVersion,
      UUID reviewedBySubjectId,
      String normalizedReason,
      OffsetDateTime recoveredAt) {
    jdbc.update(
        """
        insert into warehouse_operation_mark_recovery_audit(
          id,warehouse_id,operation_id,recovery_version,reviewed_by_subject_id,reason,reviewed_at)
        values (?,?,?,?,?,?,?)
        """,
        auditId,
        warehouseId,
        operationId,
        recoveryVersion,
        reviewedBySubjectId,
        normalizedReason,
        recoveredAt);
  }

  /** Applies the recovery-version fenced reset from a quarantined mark to a pending mark. */
  public int resetRecovered(
      UUID warehouseId,
      UUID operationId,
      long expectedRecoveryVersion,
      long nextRecoveryVersion,
      UUID reviewedBySubjectId,
      String normalizedReason,
      OffsetDateTime recoveredAt) {
    return jdbc.update(
        """
        update warehouse_operation_mark_outbox
           set state='PENDING',attempt_count=0,next_attempt_at=?,
               claim_token=null,claim_until=null,recovery_version=?,
               recovered_by_subject_id=?,recovery_reason=?,recovered_at=?,updated_at=?
         where warehouse_id=? and operation_id=?
           and state='QUARANTINED' and recovery_version=?
        """,
        recoveredAt,
        nextRecoveryVersion,
        reviewedBySubjectId,
        normalizedReason,
        recoveredAt,
        recoveredAt,
        warehouseId,
        operationId,
        expectedRecoveryVersion);
  }

  /** Immutable columns used to verify that a repeated enqueue names the same mark. */
  public record MarkIdentity(
      UUID warehouseId,
      OffsetDateTime occurredAt,
      String admissionDirection,
      Long admissionWarehouseVersion) {}

  /** Locked due-mark projection used solely as the input to a fenced lease claim. */
  public record Pending(
      UUID operationId,
      UUID warehouseId,
      OffsetDateTime occurredAt,
      int attemptCount,
      String state) {}

  /** Locked recovery-state projection on which the owning store makes replay and fence decisions. */
  public record RecoveryRow(
      String state,
      int attemptCount,
      String lastErrorCode,
      long recoveryVersion,
      UUID recoveredBySubjectId,
      String recoveryReason,
      OffsetDateTime recoveredAt) {}
}
