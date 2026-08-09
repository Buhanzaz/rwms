package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionEvidence;
import dev.buhanzaz.rwms.logistics.service.persistence.LogisticsWarehouseAdmissionPersistence;
import dev.buhanzaz.rwms.logistics.service.persistence.LogisticsWarehouseAdmissionPersistence.AdmissionRow;
import dev.buhanzaz.rwms.logistics.service.persistence.LogisticsWarehouseAdmissionPersistence.MarkAdmissionRow;
import dev.buhanzaz.rwms.logistics.service.persistence.LogisticsWarehouseAdmissionPersistence.ReadinessRow;
import dev.buhanzaz.rwms.logistics.service.persistence.LogisticsWarehouseAdmissionPersistence.StoredRequirementRow;
import dev.buhanzaz.rwms.logistics.service.persistence.LogisticsWarehouseLifecycleBlockerReader;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logistics-local half of the warehouse lifecycle handshake.
 *
 * <p>Short-lived admission intents close the gap between the remote admission read and the local
 * aggregate commit. Readiness uses the same per-warehouse advisory lock, so it can never promise
 * an empty owner while an admitted operation is about to commit. Exact live domain joins to
 * permanent evidenced marks are the only local source of a dependency-free replay candidate. The
 * owning command still compares its stored request checksum before returning a replay.
 */
@Repository
public class LogisticsWarehouseLifecycleStore {
  private static final int ADMISSION_TTL_SECONDS = 120;
  private final LogisticsWarehouseAdmissionPersistence persistence;
  private final LogisticsWarehouseLifecycleBlockerReader blockerReader;
  private final LogisticsTransactionLock transactionLock;

  public LogisticsWarehouseLifecycleStore(
      LogisticsWarehouseAdmissionPersistence persistence,
      LogisticsWarehouseLifecycleBlockerReader blockerReader,
      LogisticsTransactionLock transactionLock) {
    this.persistence = persistence;
    this.blockerReader = blockerReader;
    this.transactionLock = transactionLock;
  }

  /**
   * Finds a document replay candidate from its live idempotency tuple, referenced domain row and
   * complete permanent admission-mark set. This does not prove payload equality; the document owner
   * remains the checksum authority. Expired receipts and legacy unproven marks are not evidence.
   */
  public Optional<List<AdmissionEvidence>> evidencedDocumentReplay(
      UUID subjectId, String operationName, UUID idempotencyKey) {
    if (subjectId == null
        || operationName == null
        || operationName.isBlank()
        || idempotencyKey == null) {
      return Optional.empty();
    }
    List<StoredRequirementRow> requirements =
        persistence.documentReplayRequirements(subjectId, operationName, idempotencyKey);
    return storedReplayEvidence(requirements);
  }

  /** Finds an equipment replay candidate from its actor/key task and exact admitted mark set. */
  public Optional<List<AdmissionEvidence>> evidencedEquipmentMovementReplay(
      UUID actorSubjectId, UUID idempotencyKey) {
    if (actorSubjectId == null || idempotencyKey == null) return Optional.empty();
    List<StoredRequirementRow> requirements =
        persistence.equipmentMovementReplayRequirements(actorSubjectId, idempotencyKey);
    return storedReplayEvidence(requirements);
  }

  /** Finds a driver replay candidate from its actor/key task and exact admitted mark set. */
  public Optional<List<AdmissionEvidence>> evidencedDriverTaskReplay(
      UUID actorSubjectId, UUID idempotencyKey) {
    if (actorSubjectId == null || idempotencyKey == null) return Optional.empty();
    List<StoredRequirementRow> requirements =
        persistence.driverTaskReplayRequirements(actorSubjectId, idempotencyKey);
    return storedReplayEvidence(requirements);
  }

  /**
   * Reconstructs the original requirement vector from its domain row, then accepts only the exact
   * complete mark set. Incoming request requirements are deliberately not used: their owner must
   * return the canonical 409 when they differ from this stored candidate.
   */
  private Optional<List<AdmissionEvidence>> storedReplayEvidence(
      List<StoredRequirementRow> storedRequirements) {
    if (storedRequirements.isEmpty()) return Optional.empty();
    UUID operationId = storedRequirements.getFirst().operationId();
    java.util.ArrayList<AdmissionRequirement> requirements =
        new java.util.ArrayList<>(storedRequirements.size());
    try {
      for (StoredRequirementRow stored : storedRequirements) {
        if (operationId == null
            || !operationId.equals(stored.operationId())
            || stored.warehouseId() == null
            || stored.direction() == null) {
          return Optional.empty();
        }
        requirements.add(
            new AdmissionRequirement(
                stored.warehouseId(), WarehouseOperationDirection.valueOf(stored.direction())));
      }
      List<AdmissionRequirement> normalized = normalizedRequirements(requirements);
      List<MarkAdmissionRow> marks = persistence.operationMarkAdmissions(operationId);
      return replayEvidence(marks, normalized);
    } catch (IllegalArgumentException invalidStoredIdentity) {
      return Optional.empty();
    }
  }

  private Optional<List<AdmissionEvidence>> replayEvidence(
      List<MarkAdmissionRow> rows, List<AdmissionRequirement> requirements) {
    List<AdmissionRequirement> normalized = normalizedRequirements(requirements);
    if (rows.size() != normalized.size()) return Optional.empty();
    java.util.HashMap<UUID, MarkAdmissionRow> rowsByWarehouse = new java.util.HashMap<>();
    for (MarkAdmissionRow row : rows) {
      if (row.warehouseId() == null || rowsByWarehouse.put(row.warehouseId(), row) != null) {
        return Optional.empty();
      }
    }

    java.util.ArrayList<AdmissionEvidence> evidence = new java.util.ArrayList<>(rows.size());
    for (AdmissionRequirement requirement : normalized) {
      MarkAdmissionRow row = rowsByWarehouse.get(requirement.warehouseId());
      if (row == null
          || !requirement.direction().name().equals(row.direction())
          || row.warehouseVersion() == null
          || row.warehouseVersion() < 0) {
        return Optional.empty();
      }
      evidence.add(
          new AdmissionEvidence(
              row.warehouseId(), requirement.direction(), row.warehouseVersion()));
    }
    return Optional.of(List.copyOf(evidence));
  }

  /** Parent-owned child work is legal only while this owner has not promised readiness. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requireOwnedContinuation(List<AdmissionRequirement> requirements) {
    List<AdmissionRequirement> normalized = normalized(UUID.randomUUID(), requirements);
    lockWarehouses(normalized);
    for (AdmissionRequirement requirement : normalized) {
      requireOpen(requirement.warehouseId());
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean reserve(UUID operationId, List<AdmissionRequirement> requirements) {
    List<AdmissionRequirement> normalized = normalized(operationId, requirements);
    lockWarehouses(normalized);
    OffsetDateTime now = databaseNow();
    deleteExpired(normalized, now);
    for (AdmissionRequirement requirement : normalized) {
      requireOpen(requirement.warehouseId());
      OffsetDateTime expiresAt = now.plusSeconds(ADMISSION_TTL_SECONDS);
      persistence.insertReservedIfAbsent(
          operationId,
          requirement.warehouseId(),
          requirement.direction().name(),
          expiresAt,
          now);
      AdmissionRow stored = admission(operationId, requirement.warehouseId());
      if (stored == null
          || !requirement.direction().name().equals(stored.direction())
          || !stored.expiresAt().isAfter(now)) {
        throw new LogisticsConflictException(
            "Warehouse admission operation is bound to another immutable request");
      }
    }
    return normalized.stream()
        .allMatch(
            requirement -> {
              AdmissionRow row = admission(operationId, requirement.warehouseId());
              return row != null && "ADMITTED".equals(row.state());
            });
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void admit(
      UUID operationId,
      List<AdmissionRequirement> requirements,
      List<Long> warehouseVersions) {
    List<AdmissionRequirement> normalized = normalized(operationId, requirements);
    if (warehouseVersions == null || warehouseVersions.size() != normalized.size()) {
      throw new IllegalArgumentException("Warehouse admission versions are incomplete");
    }
    lockWarehouses(normalized);
    OffsetDateTime now = databaseNow();
    for (int index = 0; index < normalized.size(); index++) {
      AdmissionRequirement requirement = normalized.get(index);
      long warehouseVersion = warehouseVersions.get(index);
      if (warehouseVersion < 0) {
        throw new IllegalArgumentException("Warehouse admission version must not be negative");
      }
      requireOpen(requirement.warehouseId());
      AdmissionRow row = admission(operationId, requirement.warehouseId());
      if (row == null
          || !requirement.direction().name().equals(row.direction())
          || !row.expiresAt().isAfter(now)) {
        throw new LogisticsConflictException("Warehouse admission intent expired or changed");
      }
      if ("ADMITTED".equals(row.state())) {
        if (row.warehouseVersion() == null || row.warehouseVersion() != warehouseVersion) {
          throw new LogisticsConflictException(
              "Warehouse admission retry returned another lifecycle version");
        }
        continue;
      }
      int changed =
          persistence.admitReserved(
              operationId,
              requirement.warehouseId(),
              requirement.direction().name(),
              warehouseVersion,
              now);
      if (changed != 1) {
        throw new LogisticsConflictException("Warehouse admission intent changed concurrently");
      }
    }
  }

  /**
   * Re-reads the immutable versions of an already admitted intent. This is the lost-timezone-
   * response recovery path: it must carry exactly the versions originally returned by
   * warehouse-service rather than making another admission call.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<AdmissionEvidence> admittedEvidence(
      UUID operationId, List<AdmissionRequirement> requirements) {
    List<AdmissionRequirement> normalized = normalized(operationId, requirements);
    lockWarehouses(normalized);
    OffsetDateTime now = databaseNow();
    java.util.ArrayList<AdmissionEvidence> evidence =
        new java.util.ArrayList<>(normalized.size());
    for (AdmissionRequirement requirement : normalized) {
      AdmissionRow row = admission(operationId, requirement.warehouseId());
      if (row == null
          || !"ADMITTED".equals(row.state())
          || !requirement.direction().name().equals(row.direction())
          || row.warehouseVersion() == null
          || row.warehouseVersion() < 0
          || !row.expiresAt().isAfter(now)) {
        throw new LogisticsConflictException(
            "Warehouse lifecycle admission is missing or expired; retry the command");
      }
      evidence.add(
          new AdmissionEvidence(
              requirement.warehouseId(), requirement.direction(), row.warehouseVersion()));
    }
    return List.copyOf(evidence);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void cancelReserved(UUID operationId, List<AdmissionRequirement> requirements) {
    List<AdmissionRequirement> normalized = normalized(operationId, requirements);
    lockWarehouses(normalized);
    for (AdmissionRequirement requirement : normalized) {
      persistence.deleteReserved(
          operationId, requirement.warehouseId(), requirement.direction().name());
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void consume(
      UUID operationId,
      List<AdmissionRequirement> requirements,
      List<AdmissionEvidence> evidence,
      boolean bypassed) {
    if (bypassed) return;
    List<AdmissionRequirement> normalized = normalized(operationId, requirements);
    List<AdmissionEvidence> normalizedEvidence = normalizedEvidence(normalized, evidence);
    lockWarehouses(normalized);
    OffsetDateTime now = databaseNow();
    for (int index = 0; index < normalized.size(); index++) {
      AdmissionRequirement requirement = normalized.get(index);
      AdmissionEvidence expected = normalizedEvidence.get(index);
      AdmissionRow row = admission(operationId, requirement.warehouseId());
      if (row == null
          || !"ADMITTED".equals(row.state())
          || !requirement.direction().name().equals(row.direction())
          || row.warehouseVersion() == null
          || row.warehouseVersion() != expected.warehouseVersion()
          || !row.expiresAt().isAfter(now)) {
        throw new LogisticsConflictException(
            "Warehouse lifecycle admission is missing or expired; retry the command");
      }
    }
    for (AdmissionRequirement requirement : normalized) {
      persistence.deleteAdmission(operationId, requirement.warehouseId());
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ReadinessAttempt beginReadiness(UUID warehouseId, long warehouseVersion) {
    if (warehouseId == null || warehouseVersion < 0) {
      throw new IllegalArgumentException("Warehouse readiness identity is invalid");
    }
    lockWarehouse(warehouseId);
    OffsetDateTime now = databaseNow();
    persistence.deleteExpired(warehouseId, now);
    ReadinessRow existing = readiness(warehouseId);
    if (existing != null) {
      return new ReadinessAttempt(
          warehouseId, existing.warehouseVersion(), "SEALED".equals(existing.state()), true);
    }
    if (hasLocalBlockers(warehouseId)) {
      return new ReadinessAttempt(warehouseId, warehouseVersion, false, false);
    }
    persistence.insertConfirmingReadiness(warehouseId, warehouseVersion, now);
    return new ReadinessAttempt(warehouseId, warehouseVersion, false, true);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void sealReadiness(UUID warehouseId, long attemptedVersion) {
    lockWarehouse(warehouseId);
    int changed = persistence.sealReadiness(warehouseId, attemptedVersion);
    if (changed == 0) {
      ReadinessRow row = readiness(warehouseId);
      if (row == null
          || row.warehouseVersion() != attemptedVersion
          || !"SEALED".equals(row.state())) {
        throw new LogisticsConflictException("Warehouse readiness fence changed concurrently");
      }
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void releaseReadiness(UUID warehouseId, long attemptedVersion) {
    lockWarehouse(warehouseId);
    persistence.releaseReadiness(warehouseId, attemptedVersion);
  }

  boolean hasLocalBlockers(UUID warehouseId) {
    return blockerReader.hasLocalBlockers(warehouseId);
  }

  private void requireOpen(UUID warehouseId) {
    ReadinessRow fence = readiness(warehouseId);
    if (fence != null) {
      throw new LogisticsConflictException(
          "Logistics has already fenced this warehouse for lifecycle readiness");
    }
  }

  private AdmissionRow admission(UUID operationId, UUID warehouseId) {
    return persistence.admissionForUpdate(operationId, warehouseId);
  }

  private ReadinessRow readiness(UUID warehouseId) {
    return persistence.readinessForUpdate(warehouseId);
  }

  private void deleteExpired(List<AdmissionRequirement> requirements, OffsetDateTime now) {
    for (AdmissionRequirement requirement : requirements) {
      persistence.deleteExpired(requirement.warehouseId(), now);
    }
  }

  private void lockWarehouses(List<AdmissionRequirement> requirements) {
    requirements.stream()
        .map(AdmissionRequirement::warehouseId)
        .distinct()
        .sorted()
        .forEach(this::lockWarehouse);
  }

  private void lockWarehouse(UUID warehouseId) {
    transactionLock.acquire("warehouse-lifecycle:logistics:" + warehouseId);
  }

  private OffsetDateTime databaseNow() {
    return persistence.databaseNow();
  }

  private static List<AdmissionRequirement> normalized(
      UUID operationId, List<AdmissionRequirement> requirements) {
    if (operationId == null) {
      throw new IllegalArgumentException("Warehouse admission requirements are incomplete");
    }
    return normalizedRequirements(requirements);
  }

  private static List<AdmissionRequirement> normalizedRequirements(
      List<AdmissionRequirement> requirements) {
    if (requirements == null || requirements.isEmpty()) {
      throw new IllegalArgumentException("Warehouse admission requirements are incomplete");
    }
    List<AdmissionRequirement> normalized =
        requirements.stream()
            .sorted(Comparator.comparing(AdmissionRequirement::warehouseId))
            .toList();
    if (normalized.stream().map(AdmissionRequirement::warehouseId).distinct().count()
        != normalized.size()) {
      throw new IllegalArgumentException("A warehouse can appear only once in one admission");
    }
    return normalized;
  }

  private static List<AdmissionEvidence> normalizedEvidence(
      List<AdmissionRequirement> requirements, List<AdmissionEvidence> evidence) {
    if (evidence == null || evidence.size() != requirements.size()) {
      throw new IllegalArgumentException("Warehouse admission evidence is incomplete");
    }
    List<AdmissionEvidence> normalized =
        evidence.stream()
            .sorted(Comparator.comparing(AdmissionEvidence::warehouseId))
            .toList();
    for (int index = 0; index < requirements.size(); index++) {
      AdmissionRequirement requirement = requirements.get(index);
      AdmissionEvidence item = normalized.get(index);
      if (!requirement.warehouseId().equals(item.warehouseId())
          || requirement.direction() != item.direction()) {
        throw new IllegalArgumentException(
            "Warehouse admission evidence does not match its requirements");
      }
    }
    return normalized;
  }

  /** One immutable warehouse-direction requirement sorted and locked by the lifecycle protocol. */
  public record AdmissionRequirement(
      UUID warehouseId, WarehouseOperationDirection direction) {
    public AdmissionRequirement {
      if (warehouseId == null || direction == null) {
        throw new IllegalArgumentException("Warehouse admission requirement is invalid");
      }
    }
  }

  /** Result of a locally serialized readiness attempt before warehouse-service confirmation. */
  public record ReadinessAttempt(
      UUID warehouseId, long warehouseVersion, boolean sealed, boolean shouldConfirm) {}

}
