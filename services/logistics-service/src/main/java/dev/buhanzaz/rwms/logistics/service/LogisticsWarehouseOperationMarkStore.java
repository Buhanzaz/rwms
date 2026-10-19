package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionEvidence;
import dev.buhanzaz.rwms.logistics.service.persistence.LogisticsWarehouseOperationMarkPersistence;
import dev.buhanzaz.rwms.logistics.service.persistence.LogisticsWarehouseOperationMarkPersistence.MarkIdentity;
import dev.buhanzaz.rwms.logistics.service.persistence.LogisticsWarehouseOperationMarkPersistence.Pending;
import dev.buhanzaz.rwms.logistics.service.persistence.LogisticsWarehouseOperationMarkPersistence.RecoveryRow;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Transactional outbox for immutable warehouse operation-boundary marks and replay evidence. */
@Repository
public class LogisticsWarehouseOperationMarkStore {
  static final int MAX_ATTEMPTS = 8;

  private final LogisticsWarehouseOperationMarkPersistence persistence;

  public LogisticsWarehouseOperationMarkStore(LogisticsWarehouseOperationMarkPersistence persistence) {
    this.persistence = persistence;
  }

  /**
   * Commits an immutable operation-boundary mark together with optional exact remote-admission
   * evidence. A null evidence value is deliberate for test-only and parent-owned continuations
   * and can never authorize dependency-free replay.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(
      UUID warehouseId,
      UUID operationId,
      OffsetDateTime occurredAt,
      AdmissionEvidence admissionEvidence) {
    if (warehouseId == null || operationId == null || occurredAt == null) {
      throw new IllegalArgumentException("Warehouse operation mark identity is required");
    }
    if (admissionEvidence != null && !warehouseId.equals(admissionEvidence.warehouseId())) {
      throw new IllegalArgumentException(
          "Warehouse operation mark admission evidence belongs to another warehouse");
    }
    OffsetDateTime canonical =
        occurredAt.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    OffsetDateTime now = now();
    String admissionDirection =
        admissionEvidence == null ? null : admissionEvidence.direction().name();
    Long admissionWarehouseVersion =
        admissionEvidence == null ? null : admissionEvidence.warehouseVersion();
    persistence.insertIfAbsent(
        warehouseId,
        operationId,
        canonical,
        admissionDirection,
        admissionWarehouseVersion,
        now);
    MarkIdentity stored =
        persistence.markIdentityForUpdate(warehouseId, operationId);
    if (stored == null
        || !warehouseId.equals(stored.warehouseId())
        || !canonical.toInstant().equals(stored.occurredAt().toInstant())
        || !Objects.equals(admissionDirection, stored.admissionDirection())
        || !Objects.equals(admissionWarehouseVersion, stored.admissionWarehouseVersion())) {
      throw new LogisticsConflictException(
          "Warehouse operation ID is bound to another immutable occurrence or admission");
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
        persistence.dueForClaim(MAX_ATTEMPTS, claimedAt).orElse(null);
    if (pending == null) return Optional.empty();
    UUID claimToken = UUID.randomUUID();
    OffsetDateTime claimUntil = claimedAt.plus(lease);
    int changed = persistence.claim(pending, claimToken, claimUntil, claimedAt);
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
        persistence.confirm(
            work.warehouseId(),
            work.operationId(),
            work.claimToken(),
            work.attemptCount(),
            now());
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
        persistence.fail(
            work.warehouseId(),
            work.operationId(),
            work.claimToken(),
            work.attemptCount(),
            quarantined ? "QUARANTINED" : "RETRY_PENDING",
            attempt,
            now.plusSeconds(1L << Math.min(work.attemptCount(), 6)),
            safeErrorCode,
            now);
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
        persistence
            .recoveryForUpdate(warehouseId, operationId)
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
    persistence.appendRecoveryAudit(
        UUID.randomUUID(),
        warehouseId,
        operationId,
        nextRecoveryVersion,
        reviewedBySubjectId,
        normalizedReason,
        recoveredAt);
    int changed =
        persistence.resetRecovered(
            warehouseId,
            operationId,
            expectedRecoveryVersion,
            nextRecoveryVersion,
            reviewedBySubjectId,
            normalizedReason,
            recoveredAt);
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
    return persistence.databaseNow();
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

  /** Fenced short-lived capability returned only after a successful operation-mark lease claim. */
  public record WorkItem(
      UUID operationId,
      UUID warehouseId,
      OffsetDateTime occurredAt,
      int attemptCount,
      UUID claimToken) {}

  /** Reviewed recovery state returned after a version-fenced quarantine reset or replay. */
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
