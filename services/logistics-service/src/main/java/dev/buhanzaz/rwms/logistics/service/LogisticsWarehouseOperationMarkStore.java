package dev.buhanzaz.rwms.logistics.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Transactional outbox for immutable warehouse operated-boundary marks. */
@Repository
public class LogisticsWarehouseOperationMarkStore {
  static final int MAX_ATTEMPTS = 8;

  private final JdbcTemplate jdbc;

  public LogisticsWarehouseOperationMarkStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(UUID warehouseId, UUID operationId, OffsetDateTime occurredAt) {
    if (warehouseId == null || operationId == null || occurredAt == null) {
      throw new IllegalArgumentException("Warehouse operation mark identity is required");
    }
    OffsetDateTime canonical =
        occurredAt.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    OffsetDateTime now = now();
    jdbc.update(
        """
        insert into warehouse_operation_mark_outbox(
          operation_id,warehouse_id,occurred_at,state,attempt_count,next_attempt_at,created_at,updated_at)
        values (?,? ,?,'PENDING',0,?,?,?)
        on conflict (warehouse_id,operation_id) do nothing
        """,
        operationId,
        warehouseId,
        canonical,
        now,
        now,
        now);
    MarkIdentity stored =
        jdbc.queryForObject(
            """
            select warehouse_id,occurred_at from warehouse_operation_mark_outbox
             where warehouse_id=? and operation_id=? for update
            """,
            (rs, ignored) ->
                new MarkIdentity(
                    rs.getObject("warehouse_id", UUID.class),
                    rs.getObject("occurred_at", OffsetDateTime.class)),
            warehouseId,
            operationId);
    if (stored == null
        || !warehouseId.equals(stored.warehouseId())
        || !canonical.toInstant().equals(stored.occurredAt().toInstant())) {
      throw new LogisticsConflictException(
          "Warehouse operation ID is bound to another immutable occurrence");
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<WorkItem> claimNext(Duration lease) {
    if (lease == null
        || lease.isZero()
        || lease.isNegative()
        || lease.compareTo(Duration.ofMinutes(10)) > 0) {
      throw new IllegalArgumentException("Warehouse operation mark lease is invalid");
    }
    OffsetDateTime claimedAt = now();
    Pending pending =
        jdbc.query(
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
                MAX_ATTEMPTS,
                claimedAt,
                claimedAt)
            .stream()
            .findFirst()
            .orElse(null);
    if (pending == null) return Optional.empty();
    UUID claimToken = UUID.randomUUID();
    OffsetDateTime claimUntil = claimedAt.plus(lease);
    int changed =
        jdbc.update(
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
    if (changed != 1) throw changed();
    return Optional.of(
        new WorkItem(
            pending.operationId(),
            pending.warehouseId(),
            pending.occurredAt(),
            pending.attemptCount(),
            claimToken));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void confirmed(WorkItem work) {
    requireWork(work);
    int changed =
        jdbc.update(
            """
            update warehouse_operation_mark_outbox
               set state='CONFIRMED',attempt_count=attempt_count+1,
                   claim_token=null,claim_until=null,last_error_code=null,updated_at=?
             where warehouse_id=? and operation_id=? and state='IN_FLIGHT'
               and claim_token=? and attempt_count=?
            """,
            now(),
            work.warehouseId(),
            work.operationId(),
            work.claimToken(),
            work.attemptCount());
    if (changed != 1) throw changed();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean failed(WorkItem work, String safeErrorCode) {
    requireWork(work);
    if (safeErrorCode == null || safeErrorCode.isBlank() || safeErrorCode.length() > 96) {
      throw new IllegalArgumentException("Warehouse operation failure code is invalid");
    }
    int attempt = Math.addExact(work.attemptCount(), 1);
    boolean quarantined = attempt >= MAX_ATTEMPTS;
    OffsetDateTime now = now();
    int changed =
        jdbc.update(
            """
            update warehouse_operation_mark_outbox
               set state=?,attempt_count=?,next_attempt_at=?,claim_token=null,claim_until=null,
                   last_error_code=?,updated_at=?
             where warehouse_id=? and operation_id=? and state='IN_FLIGHT'
               and claim_token=? and attempt_count=?
            """,
            quarantined ? "QUARANTINED" : "RETRY_PENDING",
            attempt,
            now.plusSeconds(1L << Math.min(work.attemptCount(), 6)),
            safeErrorCode,
            now,
            work.warehouseId(),
            work.operationId(),
            work.claimToken(),
            work.attemptCount());
    if (changed != 1) throw changed();
    return quarantined;
  }

  /** Reviewed, version-fenced recovery for an exhausted operation mark. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RecoveryResult recoverQuarantined(
      UUID warehouseId,
      UUID operationId,
      long expectedRecoveryVersion,
      UUID reviewedBySubjectId,
      String reason) {
    if (warehouseId == null
        || operationId == null
        || expectedRecoveryVersion < 0
        || reviewedBySubjectId == null
        || reason == null
        || reason.isBlank()
        || reason.trim().length() > 2000) {
      throw new IllegalArgumentException("Reviewed warehouse operation recovery is invalid");
    }
    String normalizedReason = reason.trim();
    RecoveryRow row =
        jdbc.query(
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
            .findFirst()
            .orElseThrow(LogisticsNotFoundException::new);
    if (row.recoveryVersion() == Math.addExact(expectedRecoveryVersion, 1)) {
      if (reviewedBySubjectId.equals(row.recoveredBySubjectId())
          && normalizedReason.equals(row.recoveryReason())) {
        return recoveryResult(warehouseId, operationId, row, true);
      }
      throw new LogisticsConflictException(
          "Recovery version is already bound to another reviewed command");
    }
    if (row.recoveryVersion() != expectedRecoveryVersion) {
      throw new LogisticsConflictException("Warehouse operation recovery version is stale");
    }
    if (!"QUARANTINED".equals(row.state())) {
      throw new LogisticsConflictException(
          "Only a quarantined warehouse operation can be recovered");
    }

    long nextRecoveryVersion = Math.addExact(row.recoveryVersion(), 1);
    OffsetDateTime recoveredAt = now();
    jdbc.update(
        """
        insert into warehouse_operation_mark_recovery_audit(
          id,warehouse_id,operation_id,recovery_version,reviewed_by_subject_id,reason,reviewed_at)
        values (?,?,?,?,?,?,?)
        """,
        UUID.randomUUID(),
        warehouseId,
        operationId,
        nextRecoveryVersion,
        reviewedBySubjectId,
        normalizedReason,
        recoveredAt);
    int changed =
        jdbc.update(
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
    if (changed != 1) throw changed();
    return new RecoveryResult(
        warehouseId,
        operationId,
        "PENDING",
        0,
        nextRecoveryVersion,
        row.lastErrorCode(),
        reviewedBySubjectId,
        normalizedReason,
        recoveredAt,
        false);
  }

  private static RecoveryResult recoveryResult(
      UUID warehouseId, UUID operationId, RecoveryRow row, boolean replayed) {
    return new RecoveryResult(
        warehouseId,
        operationId,
        row.state(),
        row.attemptCount(),
        row.recoveryVersion(),
        row.lastErrorCode(),
        row.recoveredBySubjectId(),
        row.recoveryReason(),
        row.recoveredAt(),
        replayed);
  }

  private OffsetDateTime now() {
    OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    if (value == null) throw new IllegalStateException("Database clock returned null");
    return value.withOffsetSameInstant(ZoneOffset.UTC);
  }

  private static void requireWork(WorkItem work) {
    if (work == null
        || work.operationId() == null
        || work.warehouseId() == null
        || work.occurredAt() == null
        || work.attemptCount() < 0
        || work.claimToken() == null) {
      throw new IllegalArgumentException("Warehouse operation claim is invalid");
    }
  }

  private static LogisticsConflictException changed() {
    return new LogisticsConflictException("Warehouse operation mark claim changed concurrently");
  }

  public record WorkItem(
      UUID operationId,
      UUID warehouseId,
      OffsetDateTime occurredAt,
      int attemptCount,
      UUID claimToken) {}

  private record Pending(
      UUID operationId,
      UUID warehouseId,
      OffsetDateTime occurredAt,
      int attemptCount,
      String state) {}

  private record MarkIdentity(UUID warehouseId, OffsetDateTime occurredAt) {}

  private record RecoveryRow(
      String state,
      int attemptCount,
      String lastErrorCode,
      long recoveryVersion,
      UUID recoveredBySubjectId,
      String recoveryReason,
      OffsetDateTime recoveredAt) {}

  public record RecoveryResult(
      UUID warehouseId,
      UUID operationId,
      String state,
      int attemptCount,
      long recoveryVersion,
      String lastErrorCode,
      UUID recoveredBySubjectId,
      String recoveryReason,
      OffsetDateTime recoveredAt,
      boolean replayed) {}
}
