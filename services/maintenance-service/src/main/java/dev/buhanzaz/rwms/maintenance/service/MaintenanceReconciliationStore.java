package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceReconciliation;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceReconciliationRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Typed JPA coordinator for durable post-commit asset and task-board effects. */
@Repository
public class MaintenanceReconciliationStore {
  static final int MAX_ATTEMPTS = 4;

  private final MaintenanceReconciliationRepository reconciliations;
  private final ObjectMapper mapper;

  public MaintenanceReconciliationStore(
      MaintenanceReconciliationRepository reconciliations,
      ObjectMapper mapper) {
    this.reconciliations = reconciliations;
    this.mapper = mapper;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(
      UUID repairId,
      String dependency,
      String operation,
      UUID idempotencyKey,
      Object payload) {
    enqueue(repairId, dependency, operation, idempotencyKey, payload, false);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueueRequired(
      UUID repairId,
      String dependency,
      String operation,
      UUID idempotencyKey,
      Object payload) {
    enqueue(repairId, dependency, operation, idempotencyKey, payload, true);
  }

  private void enqueue(
      UUID repairId,
      String dependency,
      String operation,
      UUID idempotencyKey,
      Object payload,
      boolean reviewRequired) {
    if (dependency == null || operation == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Reconciliation identity is required");
    }
    MaintenanceReconciliation existing =
        reconciliations
            .findByStableKeyForUpdate(dependency, operation, idempotencyKey)
            .orElse(null);
    if (existing != null) {
      try {
        existing.requireStableIdentity(repairId);
      } catch (IllegalArgumentException exception) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_IDEMPOTENCY_CONFLICT",
            "Stable reconciliation identity is bound to another repair");
      }
      rejectQuarantined(existing);
      return;
    }
    OffsetDateTime now = now();
    MaintenanceReconciliation created =
        reviewRequired
            ? MaintenanceReconciliation.reconciliationRequired(
                repairId, dependency, operation, idempotencyKey, write(payload), now)
            : MaintenanceReconciliation.pending(
                repairId, dependency, operation, idempotencyKey, write(payload), now);
    reconciliations.saveAndFlush(created);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public MediaProofEnqueueResult enqueueMediaOwnerProof(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      UUID sourceId,
      long sourceVersion,
      boolean active) {
    if (ownerType == null
        || ownerId == null
        || warehouseId == null
        || sourceId == null
        || sourceVersion < 0) {
      throw new IllegalArgumentException("Media owner proof source is required");
    }
    MaintenanceReconciliation exact = reconciliations.findMediaSourceForUpdate(
        ownerType, ownerId, sourceId, sourceVersion).orElse(null);
    if (exact != null) {
      try {
        exact.requireStableMediaSource(
            ownerType, ownerId, warehouseId, sourceId, sourceVersion, active);
      } catch (IllegalArgumentException exception) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT",
            "Media owner proof source is already bound to different owner truth");
      }
      return mediaProofResult(exact, true);
    }
    MaintenanceReconciliation latest = reconciliations
        .findFirstByDependencyTypeAndMediaOwnerTypeAndMediaOwnerIdOrderByMediaOwnerRevisionDesc(
            "MEDIA", ownerType, ownerId)
        .orElse(null);
    if (latest != null && !warehouseId.equals(latest.getMediaWarehouseId())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Media owner proof cannot move between warehouses");
    }
    if (latest != null
        && sourceId.equals(latest.getMediaSourceId())
        && sourceVersion < latest.getMediaSourceVersion()) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_VERSION_CONFLICT", "Media owner proof source version regressed");
    }
    long ownerRevision = latest == null
        ? 0
        : Math.addExact(latest.getMediaOwnerRevision(), 1);
    long aggregateVersion = latest == null
        ? 0
        : Math.max(sourceVersion, Math.addExact(latest.getMediaAggregateVersion(), 1));
    UUID proofEventId = stableProofEventId(ownerType, ownerId, ownerRevision, aggregateVersion);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ownerType", ownerType);
    payload.put("ownerId", ownerId);
    payload.put("warehouseId", warehouseId);
    payload.put("ownerRevision", ownerRevision);
    payload.put("aggregateVersion", aggregateVersion);
    payload.put("proofEventId", proofEventId);
    payload.put("active", active);
    MaintenanceReconciliation created = MaintenanceReconciliation.mediaOwnerProof(
        ownerType,
        ownerId,
        warehouseId,
        ownerRevision,
        aggregateVersion,
        sourceId,
        sourceVersion,
        proofEventId,
        active,
        write(payload),
        now());
    reconciliations.saveAndFlush(created);
    return mediaProofResult(created, false);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueueCatalogPosition(
      String operation,
      UUID idempotencyKey,
      UUID catalogVersionId,
      UUID catalogNodeId,
      UUID queueId,
      String externalReferenceId,
      List<UUID> predecessorKeys) {
    if (!("REGISTER_CATALOG_POSITION".equals(operation)
            || "DELETE_CATALOG_POSITION".equals(operation))
        || idempotencyKey == null
        || catalogVersionId == null
        || catalogNodeId == null
        || queueId == null
        || externalReferenceId == null
        || externalReferenceId.isBlank()
        || externalReferenceId.length() > 128
        || predecessorKeys == null
        || predecessorKeys.stream().anyMatch(java.util.Objects::isNull)
        || predecessorKeys.size() != new java.util.HashSet<>(predecessorKeys).size()
        || ("REGISTER_CATALOG_POSITION".equals(operation) && !predecessorKeys.isEmpty())) {
      throw new IllegalArgumentException("Catalog-position reconciliation intent is invalid");
    }
    MaintenanceReconciliation existing =
        reconciliations
            .findByStableKeyForUpdate("TASK_BOARD", operation, idempotencyKey)
            .orElse(null);
    if (existing != null) {
      try {
        existing.requireStableCatalogPosition(
            operation, catalogVersionId, catalogNodeId, queueId, externalReferenceId);
      } catch (IllegalArgumentException exception) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_IDEMPOTENCY_CONFLICT",
            "Stable catalog-position work is bound to different routing truth");
      }
      rejectQuarantined(existing);
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("catalogVersionId", catalogVersionId);
    payload.put("catalogNodeId", catalogNodeId);
    payload.put("queueId", queueId);
    payload.put("externalReferenceId", externalReferenceId);
    payload.put("predecessorKeys", List.copyOf(predecessorKeys));
    MaintenanceReconciliation created =
        MaintenanceReconciliation.catalogPosition(
            operation,
            idempotencyKey,
            catalogVersionId,
            catalogNodeId,
            queueId,
            externalReferenceId,
            write(payload),
            now());
    reconciliations.saveAndFlush(created);
  }

  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<CatalogRoutingTruth> catalogRoutingTruth(UUID catalogVersionId) {
    if (catalogVersionId == null) {
      throw new IllegalArgumentException("Catalog version is required");
    }
    List<MaintenanceReconciliation> values =
        reconciliations.findAllByCatalogVersionIdOrderByOperationTypeAscCatalogNodeIdAsc(
            catalogVersionId);
    if (values.isEmpty()) return Optional.empty();
    int registrationsRequired = 0;
    int registrationsConfirmed = 0;
    int cleanupRequired = 0;
    int cleanupConfirmed = 0;
    int attempts = 0;
    OffsetDateTime updatedAt = null;
    String state = "DELIVERED";
    for (MaintenanceReconciliation value : values) {
      boolean registration = "REGISTER_CATALOG_POSITION".equals(value.getOperationType());
      if (registration) {
        registrationsRequired++;
        if ("CONFIRMED".equals(value.getState())) registrationsConfirmed++;
      } else if ("DELETE_CATALOG_POSITION".equals(value.getOperationType())) {
        cleanupRequired++;
        if ("CONFIRMED".equals(value.getState())) cleanupConfirmed++;
      } else {
        throw new IllegalStateException("Stored catalog routing operation is invalid");
      }
      attempts = Math.addExact(attempts, value.getAttemptCount());
      if (updatedAt == null || value.getUpdatedAt().isAfter(updatedAt)) {
        updatedAt = value.getUpdatedAt();
      }
      state = aggregateDeliveryState(state, value.getState());
    }
    return Optional.of(
        new CatalogRoutingTruth(
            state,
            registrationsRequired,
            registrationsConfirmed,
            cleanupRequired,
            cleanupConfirmed,
            attempts,
            updatedAt));
  }

  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public List<UUID> catalogRegistrationKeys(UUID catalogVersionId) {
    if (catalogVersionId == null) {
      throw new IllegalArgumentException("Catalog version is required");
    }
    return reconciliations
        .findAllByCatalogVersionIdOrderByOperationTypeAscCatalogNodeIdAsc(catalogVersionId)
        .stream()
        .filter(value -> "REGISTER_CATALOG_POSITION".equals(value.getOperationType()))
        .map(MaintenanceReconciliation::getIdempotencyKey)
        .toList();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public ResumeResult resumeQuarantined(
      UUID reconciliationId,
      long expectedReviewVersion,
      UUID reviewSubjectId,
      String reviewReason) {
    if (reconciliationId == null
        || expectedReviewVersion < 0
        || reviewSubjectId == null
        || reviewReason == null
        || reviewReason.isBlank()
        || reviewReason.trim().length() > 2000) {
      throw new IllegalArgumentException("Reviewed reconciliation resume metadata is invalid");
    }
    MaintenanceReconciliation reconciliation =
        reconciliations
            .findByIdForUpdate(reconciliationId)
            .orElseThrow(() -> new MaintenanceNotFoundException("Reconciliation record not found"));
    try {
      reconciliation.resume(expectedReviewVersion, reviewSubjectId, reviewReason, now());
      reconciliations.flush();
    } catch (IllegalArgumentException exception) {
      if ("REVIEW_VERSION".equals(exception.getMessage())) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_VERSION_CONFLICT", "Reconciliation review version conflict");
      }
      if ("REVIEW_STATE".equals(exception.getMessage())) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT",
            "Only a quarantined reconciliation can be resumed");
      }
      throw exception;
    }
    return resumeResult(reconciliation);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<WorkItem> lockNextDue() {
    return reconciliations
        .findDueForUpdateSkipLocked(MAX_ATTEMPTS, now(), PageRequest.of(0, 1))
        .stream()
        .findFirst()
        .map(this::workItem);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<WorkItem> lockNextDueMedia() {
    return reconciliations
        .findDueMediaForUpdateSkipLocked(MAX_ATTEMPTS, now(), PageRequest.of(0, 1))
        .stream()
        .findFirst()
        .map(this::workItem);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<WorkItem> findStable(
      String dependency, String operation, UUID idempotencyKey) {
    return reconciliations
        .findByDependencyTypeAndOperationTypeAndIdempotencyKey(
            dependency, operation, idempotencyKey)
        .map(this::workItem);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean allConfirmed(
      String dependency, String operation, List<UUID> idempotencyKeys) {
    if (idempotencyKeys == null
        || idempotencyKeys.stream().anyMatch(java.util.Objects::isNull)
        || idempotencyKeys.size() != new java.util.HashSet<>(idempotencyKeys).size()) {
      throw new IllegalArgumentException("Reconciliation predecessor keys are invalid");
    }
    return idempotencyKeys.stream().allMatch(key -> findStable(dependency, operation, key)
        .filter(work -> "CONFIRMED".equals(work.state()))
        .isPresent());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void defer(WorkItem item, Duration delay) {
    if (delay == null || delay.isZero() || delay.isNegative()) {
      throw new IllegalArgumentException("Reconciliation deferral is invalid");
    }
    MaintenanceReconciliation reconciliation = requireLocked(item.id());
    OffsetDateTime now = now();
    try {
      reconciliation.defer(item.attemptCount(), now.plus(delay), now);
      reconciliations.flush();
    } catch (IllegalArgumentException exception) {
      throw claimConflict();
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void confirmed(WorkItem item, Object response) {
    MaintenanceReconciliation reconciliation = requireLocked(item.id());
    try {
      reconciliation.confirm(item.attemptCount(), write(response), now());
      reconciliations.flush();
    } catch (IllegalArgumentException exception) {
      throw claimConflict();
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean failed(WorkItem item, RuntimeException failure) {
    MaintenanceReconciliation reconciliation = requireLocked(item.id());
    try {
      boolean quarantined =
          reconciliation.fail(
              item.attemptCount(), MAX_ATTEMPTS, failureCode(failure), now());
      reconciliations.flush();
      return quarantined;
    } catch (IllegalArgumentException exception) {
      throw claimConflict();
    }
  }

  private MaintenanceReconciliation requireLocked(UUID reconciliationId) {
    return reconciliations
        .findByIdForUpdate(reconciliationId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Reconciliation record not found"));
  }

  private WorkItem workItem(MaintenanceReconciliation value) {
    return new WorkItem(
        value.getId(),
        value.getRepairId(),
        value.getDependencyType(),
        value.getOperationType(),
        value.getIdempotencyKey(),
        value.getState(),
        value.getAttemptCount(),
        value.getNextAttemptAt(),
        read(value.getResponseSnapshot()),
        value.getMediaOwnerType(),
        value.getMediaOwnerId(),
        value.getMediaWarehouseId(),
        value.getMediaOwnerRevision(),
        value.getMediaAggregateVersion(),
        value.getMediaProofEventId(),
        value.getMediaActive(),
        value.getCatalogVersionId(),
        value.getCatalogNodeId(),
        value.getCatalogQueueId(),
        value.getCatalogExternalReferenceId());
  }

  private static String aggregateDeliveryState(String aggregate, String current) {
    if ("QUARANTINED".equals(aggregate) || "QUARANTINED".equals(current)) {
      return "QUARANTINED";
    }
    if ("RETRY_PENDING".equals(aggregate)
        || "RETRY_PENDING".equals(current)
        || "RECONCILIATION_REQUIRED".equals(current)) {
      return "RETRY_PENDING";
    }
    if ("PENDING".equals(aggregate) || "PENDING".equals(current)) return "PENDING";
    if ("DELIVERED".equals(aggregate) && "CONFIRMED".equals(current)) return "DELIVERED";
    throw new IllegalStateException("Stored catalog routing delivery state is invalid");
  }

  private static MediaProofEnqueueResult mediaProofResult(
      MaintenanceReconciliation value, boolean replayed) {
    return new MediaProofEnqueueResult(
        value.getId(),
        value.getMediaOwnerType(),
        value.getMediaOwnerId(),
        value.getMediaWarehouseId(),
        value.getMediaOwnerRevision(),
        value.getMediaAggregateVersion(),
        value.getMediaProofEventId(),
        value.getMediaActive(),
        replayed);
  }

  private ResumeResult resumeResult(MaintenanceReconciliation value) {
    return new ResumeResult(
        value.getId(),
        value.getIdempotencyKey(),
        value.getState(),
        value.getReviewVersion(),
        value.getReviewSubjectId(),
        value.getReviewReason(),
        value.getReviewedAt());
  }

  private static void rejectQuarantined(MaintenanceReconciliation existing) {
    if ("QUARANTINED".equals(existing.getState())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_RECONCILIATION_QUARANTINED",
          "Stable reconciliation work is quarantined and requires reviewed resume");
    }
  }

  private static MaintenanceConflictException claimConflict() {
    return new MaintenanceConflictException(
        "MAINTENANCE_STATE_CONFLICT", "Reconciliation claim changed concurrently");
  }

  private static String failureCode(RuntimeException failure) {
    String value = failure.getClass().getSimpleName();
    return value.length() <= 64 ? value : value.substring(0, 64);
  }

  private JsonNode read(String value) {
    try {
      return mapper.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored reconciliation payload is invalid", exception);
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Reconciliation payload cannot be serialized", exception);
    }
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static UUID stableProofEventId(
      String ownerType, UUID ownerId, long ownerRevision, long aggregateVersion) {
    return UUID.nameUUIDFromBytes((
        "maintenance-media-proof:" + ownerType + ":" + ownerId + ":"
            + ownerRevision + ":" + aggregateVersion)
        .getBytes(StandardCharsets.UTF_8));
  }

  public record WorkItem(
      UUID id,
      UUID repairId,
      String dependency,
      String operation,
      UUID idempotencyKey,
      String state,
      int attemptCount,
      OffsetDateTime nextAttemptAt,
      JsonNode payload,
      String mediaOwnerType,
      UUID mediaOwnerId,
      UUID mediaWarehouseId,
      Long mediaOwnerRevision,
      Long mediaAggregateVersion,
      UUID mediaProofEventId,
      Boolean mediaActive,
      UUID catalogVersionId,
      UUID catalogNodeId,
      UUID catalogQueueId,
      String catalogExternalReferenceId) {}

  public record CatalogRoutingTruth(
      String state,
      int registrationsRequired,
      int registrationsConfirmed,
      int cleanupRequired,
      int cleanupConfirmed,
      int attempts,
      OffsetDateTime updatedAt) {}

  public record MediaProofEnqueueResult(
      UUID reconciliationId,
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      boolean active,
      boolean replayed) {}

  public record ResumeResult(
      UUID reconciliationId,
      UUID idempotencyKey,
      String state,
      long reviewVersion,
      UUID reviewSubjectId,
      String reviewReason,
      OffsetDateTime reviewedAt) {}
}
