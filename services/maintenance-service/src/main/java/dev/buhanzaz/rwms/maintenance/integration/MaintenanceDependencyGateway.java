package dev.buhanzaz.rwms.maintenance.integration;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Private transport boundary for maintenance dependencies; user credentials never cross it. */
public interface MaintenanceDependencyGateway {
  default boolean productionReady() { return true; }

  WarehouseOperationAdmission warehouseAdmission(
      UUID warehouseId, WarehouseOperationDirection direction);

  WarehouseLifecycleReadinessWorkPage warehouseLifecycleReadinessWork(
      UUID after, int limit);

  WarehouseLifecycleReadinessConfirmation confirmWarehouseLifecycleReadiness(
      UUID warehouseId, long expectedVersion);

  WarehouseTimeZone warehouseTimeZoneAt(UUID warehouseId, OffsetDateTime at);

  void markWarehouseOperation(UUID warehouseId, UUID operationId, OffsetDateTime occurredAt);

  AssetSnapshot getRentalItemSnapshot(UUID rentalItemId);

  /**
   * Narrow asset truth used to open a property disposition. The caller still sends the same
   * version fences to PREPARE; this read is deliberately not a substitute for that atomic check.
   */
  PropertyAssetSnapshot getPropertyAssetSnapshot(
      PropertyAssetKind assetKind, UUID assetId, UUID warehouseId);

  /** Creates or reads the immutable asset-side fence for a maintenance decision. */
  PropertyDispositionFence preparePropertyDisposition(
      UUID idempotencyKey, UUID decisionId, PropertyDispositionPreparation request);

  /** Applies only a previously prepared decision; the immutable plan stays in asset-service. */
  PropertyDispositionEffect applyPropertyDisposition(
      UUID idempotencyKey, UUID decisionId, UUID completedMovementTaskId);

  /** Creates or reads the worker-confirmed furniture movement owned by one disposition decision. */
  PropertyEquipmentMovementTask createPropertyEquipmentMovementTask(
      UUID idempotencyKey, PropertyEquipmentMovementCommand command);

  /** Returns logistics' current durable movement truth for the given maintenance task. */
  PropertyEquipmentMovementTask getPropertyEquipmentMovementTask(UUID taskId);

  /** Asset-owned pending-return truth for the exact estimate or direct-repair lease owner. */
  List<MaintenanceFurnitureCustodyClaim> unresolvedFurnitureCustody(
      String ownerType, UUID ownerId);

  /**
   * Ensures the durable maintenance-node binding and applies a maximum mutation only when the
   * caller supplies the asset equipment version used as its CAS fence.
   */
  FurnitureEquipmentSnapshot ensureFurnitureEquipment(
      UUID catalogNodeId,
      String equipmentName,
      Long expectedEquipmentVersion,
      Integer maximumPerCabin);

  /** Legacy ensure semantics: preserve every existing asset-owned maximum. */
  default FurnitureEquipmentSnapshot ensureFurnitureEquipment(
      UUID catalogNodeId, String equipmentName) {
    return ensureFurnitureEquipment(catalogNodeId, equipmentName, null, null);
  }

  /** Returns live asset-owned furniture settings for the bounded durable node identities. */
  List<FurnitureEquipmentSnapshot> furnitureEquipmentSnapshots(List<UUID> catalogNodeIds);

  List<CabinCharacteristicSnapshot> cabinCharacteristics();

  AppliedCabinCharacteristic applyCabinCharacteristic(
      UUID idempotencyKey, UUID rentalItemId, UUID characteristicId);

  LeaseSnapshot acquireLease(
      UUID idempotencyKey,
      UUID rentalItemId,
      long rentalItemExpectedVersion,
      String ownerType,
      String ownerId);

  LeaseSnapshot renewLease(
      UUID idempotencyKey,
      UUID leaseId,
      long leaseExpectedVersion,
      long fencingToken,
      String ownerType,
      String ownerId);

  default AssetSnapshot fencedStatus(
      UUID idempotencyKey,
      UUID rentalItemId,
      UUID warehouseId,
      long rentalItemExpectedVersion,
      UUID leaseId,
      long fencingToken,
      String ownerType,
      String ownerId,
      String transition,
      boolean linkedReturn) {
    return fencedStatus(
        idempotencyKey,
        rentalItemId,
        warehouseId,
        rentalItemExpectedVersion,
        leaseId,
        fencingToken,
        ownerType,
        ownerId,
        transition,
        linkedReturn,
        List.of());
  }

  AssetSnapshot fencedStatus(
      UUID idempotencyKey,
      UUID rentalItemId,
      UUID warehouseId,
      long rentalItemExpectedVersion,
      UUID leaseId,
      long fencingToken,
      String ownerType,
      String ownerId,
      String transition,
      boolean linkedReturn,
      List<FurniturePendingReturn> furniturePendingReturns);

  void releaseLease(
      UUID idempotencyKey,
      UUID leaseId,
      long leaseExpectedVersion,
      long fencingToken,
      String ownerType,
      String ownerId);

  TaskSnapshot registerTask(
      UUID idempotencyKey,
      UUID externalTaskId,
      UUID sourceRepairId,
      UUID warehouseId,
      UUID rentalItemId,
      String unitNumber,
      LocalDate scheduledDate,
      int priority,
      int dailyCapacity,
      List<TaskStage> stages);

  default TaskSnapshot registerTask(
      UUID idempotencyKey,
      UUID externalTaskId,
      UUID warehouseId,
      UUID rentalItemId,
      String unitNumber,
      LocalDate scheduledDate,
      int priority,
      List<TaskStage> stages) {
    return registerTask(
        idempotencyKey,
        externalTaskId,
        null,
        warehouseId,
        rentalItemId,
        unitNumber,
        scheduledDate,
        priority,
        6,
        stages);
  }

  default TaskSnapshot registerTask(
      UUID idempotencyKey,
      UUID externalTaskId,
      UUID warehouseId,
      UUID rentalItemId,
      String unitNumber,
      List<TaskStage> stages) {
    OffsetDateTime at = OffsetDateTime.now(java.time.ZoneOffset.UTC);
    return registerTask(
        idempotencyKey,
        externalTaskId,
        null,
        warehouseId,
        rentalItemId,
        unitNumber,
        at.toInstant()
            .atZone(java.time.ZoneId.of(warehouseTimeZoneAt(warehouseId, at).timeZone()))
            .toLocalDate(),
        3,
        6,
        stages);
  }

  TaskSnapshot updatePreStartTask(
      UUID idempotencyKey,
      UUID externalTaskId,
      long expectedVersion,
      String unitNumber,
      List<TaskStage> stages);

  TaskSnapshot getTask(UUID externalTaskId);

  TaskSnapshot cancelTask(
      UUID idempotencyKey, UUID externalTaskId, long expectedVersion);

  /**
   * Atomically cancels a maintenance repair task only while every board route is still waiting.
   * Unlike the historical broad cancellation call, STARTED never mutates task-board state.
   */
  PreStartTaskCancellation cancelTaskIfPreStart(
      UUID idempotencyKey, UUID externalTaskId, long expectedVersion);

  TaskSnapshot relocateTask(
      UUID idempotencyKey,
      UUID externalTaskId,
      long expectedVersion,
      UUID targetWarehouseId);

  DriverTaskSnapshot createDriverTask(
      UUID idempotencyKey, DriverTaskCommand command);

  /** Strict logistics-owned movement truth used before inventory supersedes a queued repair. */
  MaintenanceDriverTaskCompensation maintenanceDriverTaskCompensation(
      UUID repairId, MaintenanceDriverTaskKind kind);

  /**
   * Requests logistics' own task-board-guarded compensation for a not-yet-started movement.
   * The same idempotency key is reused after any ambiguous dependency response.
   */
  MaintenanceDriverTaskCompensation cancelMaintenanceDriverTaskCompensation(
      UUID idempotencyKey, UUID repairId, MaintenanceDriverTaskKind kind);

  CatalogRoutingPreflight preflightCatalogRouting(
      List<CatalogRoutingQueueRequirement> queues);

  RoutingPreflight preflightMaintenanceRouting(
      UUID warehouseId, List<RoutingQueueRequirement> queues);

  QueueCapabilities queueCapabilities(UUID warehouseId);

  CatalogPositionReference registerCatalogPosition(
      UUID queueId, String externalReferenceId);

  void deleteCatalogPosition(String externalReferenceId, long expectedVersion);

  MediaOwnerProof upsertMediaOwnerProof(MediaOwnerProof proof);

  record LeaseSnapshot(
      UUID leaseId,
      long version,
      UUID rentalItemId,
      String ownerType,
      UUID ownerId,
      long fencingToken,
      OffsetDateTime expiresAt) {}

  record AssetSnapshot(
      UUID rentalItemId, long version, UUID warehouseId, String number, String status) {
    public AssetSnapshot(
        UUID rentalItemId, long version, UUID warehouseId, String status) {
      this(rentalItemId, version, warehouseId, null, status);
    }
  }

  enum PropertyAssetKind { CABIN, EQUIPMENT }

  enum PropertyDispositionKind { WRITE_OFF, LOSS }

  enum PropertyDispositionContentsMode { MOVE_SELECTED_TO_STOCK, DISPOSE_WITH_CABIN }

  record PropertyAssetContentSnapshot(
      UUID equipmentId,
      String equipmentName,
      String equipmentFormat,
      long balanceVersion,
      long quantity) {
    public PropertyAssetContentSnapshot {
      if (equipmentId == null
          || equipmentName == null
          || equipmentName.isBlank()
          || equipmentName.length() > 255
          || (equipmentFormat != null && equipmentFormat.length() > 512)
          || balanceVersion < 0
          || quantity < 1) {
        throw new IllegalArgumentException("Property cabin-content snapshot is invalid");
      }
      equipmentName = equipmentName.trim();
      equipmentFormat = equipmentFormat == null || equipmentFormat.isBlank()
          ? null : equipmentFormat.trim();
    }
  }

  record PropertyAssetSnapshot(
      PropertyAssetKind assetKind,
      UUID assetId,
      String assetDisplayName,
      UUID warehouseId,
      long version,
      String status,
      Long quantity,
      Long sourceBalanceVersion,
      List<PropertyAssetContentSnapshot> contents,
      boolean activeReservation,
      boolean activeHold,
      boolean activeLease,
      boolean dispositionAllowed) {
    public PropertyAssetSnapshot {
      if (assetKind == null
          || assetId == null
          || assetDisplayName == null
          || assetDisplayName.isBlank()
          || assetDisplayName.length() > 255
          || warehouseId == null
          || version < 0
          || (quantity != null && quantity < 0)
          || (sourceBalanceVersion != null && sourceBalanceVersion < 0)) {
        throw new IllegalArgumentException("Property asset snapshot is invalid");
      }
      assetDisplayName = assetDisplayName.trim();
      contents = contents == null ? List.of() : List.copyOf(contents);
      if (contents.stream().anyMatch(java.util.Objects::isNull)
          || contents.stream().map(PropertyAssetContentSnapshot::equipmentId).distinct().count()
              != contents.size()) {
        throw new IllegalArgumentException("Property asset contents contain duplicate identities");
      }
      if (assetKind == PropertyAssetKind.CABIN
          && (quantity != null || sourceBalanceVersion != null)) {
        throw new IllegalArgumentException("Cabin property snapshot cannot expose stock balance truth");
      }
      if (assetKind == PropertyAssetKind.EQUIPMENT
          && (status != null || !contents.isEmpty() || quantity == null || sourceBalanceVersion == null)) {
        throw new IllegalArgumentException("Equipment property snapshot is incomplete");
      }
    }
  }

  record PropertyDispositionLeaseProof(
      UUID leaseId, long fencingToken, String ownerType, UUID ownerId) {
    public PropertyDispositionLeaseProof {
      if (leaseId == null
          || fencingToken < 1
          || !("MAINTENANCE_REPAIR".equals(ownerType)
              || "MAINTENANCE_ESTIMATE".equals(ownerType))
          || ownerId == null) {
        throw new IllegalArgumentException("Property disposition lease proof is invalid");
      }
    }
  }

  record PropertyDispositionContent(
      UUID equipmentId,
      long expectedBalanceVersion,
      long currentQuantity,
      long moveQuantity) {
    public PropertyDispositionContent {
      if (equipmentId == null
          || expectedBalanceVersion < 0
          || currentQuantity < 1
          || moveQuantity < 0
          || moveQuantity > currentQuantity) {
        throw new IllegalArgumentException("Property disposition content is invalid");
      }
    }
  }

  record PropertyDispositionPreparation(
      UUID warehouseId,
      PropertyAssetKind assetKind,
      UUID assetId,
      PropertyDispositionKind disposition,
      Long expectedAssetVersion,
      Long expectedSourceBalanceVersion,
      Long quantity,
      UUID maintenanceCustodyClaimId,
      Long maintenanceCustodyVersion,
      PropertyDispositionContentsMode contentsMode,
      List<PropertyDispositionContent> contents,
      PropertyDispositionLeaseProof authorizedMaintenanceLease) {
    public PropertyDispositionPreparation {
      if (warehouseId == null || assetKind == null || assetId == null || disposition == null) {
        throw new IllegalArgumentException("Property disposition preparation is incomplete");
      }
      contents = contents == null ? List.of() : List.copyOf(contents);
      if (contents.stream().anyMatch(java.util.Objects::isNull)
          || contents.stream().map(PropertyDispositionContent::equipmentId).distinct().count()
              != contents.size()) {
        throw new IllegalArgumentException("Property disposition contents are invalid");
      }
      if (assetKind == PropertyAssetKind.EQUIPMENT
          && (expectedAssetVersion != null
              || quantity == null
              || quantity < 1
              || contentsMode != null
              || !contents.isEmpty()
              || authorizedMaintenanceLease != null)) {
        throw new IllegalArgumentException("Equipment disposition preparation is invalid");
      }
      boolean custody = maintenanceCustodyClaimId != null || maintenanceCustodyVersion != null;
      if (assetKind == PropertyAssetKind.EQUIPMENT
          && (custody
              ? maintenanceCustodyClaimId == null
                  || maintenanceCustodyVersion == null
                  || maintenanceCustodyVersion < 0
                  || expectedSourceBalanceVersion != null
              : expectedSourceBalanceVersion == null || expectedSourceBalanceVersion < 0)) {
        throw new IllegalArgumentException("Equipment disposition source fence is invalid");
      }
      if (assetKind == PropertyAssetKind.CABIN
          && (expectedAssetVersion == null
              || expectedAssetVersion < 0
              || expectedSourceBalanceVersion != null
              || quantity != null
              || custody)) {
        throw new IllegalArgumentException("Cabin disposition preparation cannot contain stock quantity");
      }
      if (assetKind == PropertyAssetKind.CABIN
          && ((contents.isEmpty() && contentsMode != null)
              || (!contents.isEmpty() && contentsMode == null))) {
        throw new IllegalArgumentException("Cabin contents mode does not match the prepared contents");
      }
    }

    public PropertyDispositionPreparation(
        UUID warehouseId,
        PropertyAssetKind assetKind,
        UUID assetId,
        PropertyDispositionKind disposition,
        Long expectedAssetVersion,
        Long expectedSourceBalanceVersion,
        Long quantity,
        PropertyDispositionContentsMode contentsMode,
        List<PropertyDispositionContent> contents,
        PropertyDispositionLeaseProof authorizedMaintenanceLease) {
      this(
          warehouseId,
          assetKind,
          assetId,
          disposition,
          expectedAssetVersion,
          expectedSourceBalanceVersion,
          quantity,
          null,
          null,
          contentsMode,
          contents,
          authorizedMaintenanceLease);
    }
  }

  record PropertyDispositionFence(
      UUID decisionId,
      String state,
      String requestSha256,
      UUID warehouseId,
      PropertyAssetKind assetKind,
      UUID assetId,
      PropertyDispositionKind disposition,
      UUID maintenanceCustodyClaimId,
      Long maintenanceCustodyVersion,
      List<PropertyDispositionContent> contents,
      Instant preparedAt,
      Instant appliedAt) {
    public PropertyDispositionFence {
      if (decisionId == null
          || !("PREPARED".equals(state) || "APPLIED".equals(state))
          || requestSha256 == null
          || !requestSha256.matches("[0-9a-f]{64}")
          || warehouseId == null
          || assetKind == null
          || assetId == null
          || disposition == null
          || (maintenanceCustodyVersion != null && maintenanceCustodyVersion < 0)
          || ((maintenanceCustodyClaimId == null) != (maintenanceCustodyVersion == null))
          || preparedAt == null) {
        throw new IllegalArgumentException("Property disposition fence is invalid");
      }
      contents = contents == null ? List.of() : List.copyOf(contents);
    }

    public PropertyDispositionFence(
        UUID decisionId,
        String state,
        String requestSha256,
        UUID warehouseId,
        PropertyAssetKind assetKind,
        UUID assetId,
        PropertyDispositionKind disposition,
        List<PropertyDispositionContent> contents,
        Instant preparedAt,
        Instant appliedAt) {
      this(
          decisionId,
          state,
          requestSha256,
          warehouseId,
          assetKind,
          assetId,
          disposition,
          null,
          null,
          contents,
          preparedAt,
          appliedAt);
    }
  }

  record PropertyDispositionEffect(
      UUID effectId,
      UUID decisionId,
      PropertyAssetKind assetKind,
      UUID assetId,
      PropertyDispositionKind disposition,
      Long assetVersion,
      Instant appliedAt) {
    public PropertyDispositionEffect {
      if (effectId == null
          || decisionId == null
          || assetKind == null
          || assetId == null
          || disposition == null
          || (assetVersion != null && assetVersion < 0)
          || appliedAt == null) {
        throw new IllegalArgumentException("Property disposition effect is invalid");
      }
    }
  }

  record PropertyEquipmentMovementLine(
      UUID equipmentId,
      UUID sourceRentalItemId,
      long expectedSourceBalanceVersion,
      long quantity) {
    public PropertyEquipmentMovementLine {
      if (equipmentId == null
          || sourceRentalItemId == null
          || expectedSourceBalanceVersion < 0
          || quantity < 1) {
        throw new IllegalArgumentException("Property equipment movement line is invalid");
      }
    }
  }

  record PropertyEquipmentMovementCommand(
      UUID decisionId,
      UUID warehouseId,
      String unitNumber,
      int plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<PropertyEquipmentMovementLine> lines) {
    public PropertyEquipmentMovementCommand {
      if (decisionId == null
          || warehouseId == null
          || unitNumber == null
          || unitNumber.isBlank()
          || unitNumber.length() > 64
          || plannedDurationMinutes < 1
          || deadlineAt == null
          || lines == null
          || lines.isEmpty()
          || lines.size() > 100) {
        throw new IllegalArgumentException("Property equipment movement command is invalid");
      }
      unitNumber = unitNumber.trim();
      lines = List.copyOf(lines);
      if (lines.stream().anyMatch(java.util.Objects::isNull)
          || lines.stream().map(PropertyEquipmentMovementLine::equipmentId).distinct().count()
              != lines.size()) {
        throw new IllegalArgumentException("Property equipment movement lines are invalid");
      }
    }
  }

  record PropertyEquipmentMovementTask(
      UUID id,
      UUID warehouseId,
      String ownerType,
      UUID ownerId,
      String state,
      String terminalState,
      OffsetDateTime doneAt) {
    public PropertyEquipmentMovementTask {
      if (id == null
          || warehouseId == null
          || ownerType == null
          || ownerType.isBlank()
          || ownerId == null
          || state == null
          || state.isBlank()) {
        throw new IllegalArgumentException("Property equipment movement task is invalid");
      }
      ownerType = ownerType.trim();
    }

    public boolean isOwnedByMaintenanceDisposition(UUID decisionId) {
      return decisionId != null
          && "MAINTENANCE_DISPOSITION".equals(ownerType)
          && decisionId.equals(ownerId);
    }

    public boolean completed() {
      return "COMPLETED".equals(state) || "COMPLETED".equals(terminalState);
    }

    public boolean failedTerminally() {
      return terminalState != null && !"COMPLETED".equals(terminalState);
    }
  }

  /**
   * Live asset-owned furniture binding returned to maintenance; the version fences editor writes
   * and {@code maximumPerCabin} is never duplicated into the maintenance catalog tree.
   */
  record FurnitureEquipmentSnapshot(
      UUID catalogNodeId,
      UUID equipmentId,
      String equipmentName,
      long equipmentVersion,
      Integer maximumPerCabin) {
    public FurnitureEquipmentSnapshot(UUID equipmentId, String equipmentName) {
      this(null, equipmentId, equipmentName, 0, null);
    }

    public FurnitureEquipmentSnapshot {
      if (equipmentId == null
          || equipmentName == null
          || equipmentName.isBlank()
          || equipmentName.length() > 255
          || equipmentVersion < 0
          || (maximumPerCabin != null && maximumPerCabin < 1)) {
        throw new IllegalArgumentException("Furniture equipment snapshot is invalid");
      }
    }
  }

  record CabinCharacteristicSnapshot(UUID characteristicId, String characteristicName) {
    public CabinCharacteristicSnapshot {
      if (characteristicId == null
          || characteristicName == null
          || characteristicName.isBlank()
          || characteristicName.length() > 255) {
        throw new IllegalArgumentException("Cabin characteristic snapshot is invalid");
      }
      characteristicName = characteristicName.trim();
    }
  }

  record AppliedCabinCharacteristic(
      UUID rentalItemId,
      UUID characteristicId,
      boolean added,
      long rentalItemVersion) {
    public AppliedCabinCharacteristic {
      if (rentalItemId == null
          || characteristicId == null
          || rentalItemVersion < 0) {
        throw new IllegalArgumentException("Applied cabin characteristic truth is invalid");
      }
    }
  }

  record FurniturePendingReturn(
      UUID equipmentId, long expectedSourceBalanceVersion, long quantity) {
    public FurniturePendingReturn {
      if (equipmentId == null || expectedSourceBalanceVersion < 0 || quantity < 1) {
        throw new IllegalArgumentException("Furniture pending return is invalid");
      }
    }
  }

  record MaintenanceFurnitureCustodyClaim(
      UUID id,
      long custodyVersion,
      String ownerType,
      UUID ownerId,
      UUID rentalItemId,
      UUID warehouseId,
      UUID equipmentId,
      UUID sourceBalanceId,
      long sourceBalanceVersion,
      long quantity,
      long returnedToStockQuantity,
      long preparedDispositionQuantity,
      long terminalDispositionQuantity,
      long unresolvedQuantity,
      long availableForDispositionQuantity,
      OffsetDateTime selectedAt) {
    public MaintenanceFurnitureCustodyClaim {
      if (id == null
          || custodyVersion < 0
          || !("MAINTENANCE_ESTIMATE".equals(ownerType)
              || "MAINTENANCE_REPAIR".equals(ownerType))
          || ownerId == null
          || rentalItemId == null
          || warehouseId == null
          || equipmentId == null
          || sourceBalanceId == null
          || sourceBalanceVersion < 0
          || quantity < 1
          || returnedToStockQuantity < 0
          || preparedDispositionQuantity < 0
          || terminalDispositionQuantity < 0
          || unresolvedQuantity < 0
          || availableForDispositionQuantity < 0
          || unresolvedQuantity > quantity
          || availableForDispositionQuantity > unresolvedQuantity
          || selectedAt == null) {
        throw new IllegalArgumentException("Maintenance furniture custody truth is invalid");
      }
      try {
        long expectedUnresolved = Math.subtractExact(
            Math.subtractExact(quantity, returnedToStockQuantity), terminalDispositionQuantity);
        long expectedAvailable = Math.subtractExact(
            Math.subtractExact(quantity, returnedToStockQuantity), preparedDispositionQuantity);
        if (unresolvedQuantity != expectedUnresolved
            || availableForDispositionQuantity != expectedAvailable
            || terminalDispositionQuantity > preparedDispositionQuantity) {
          throw new IllegalArgumentException("Maintenance furniture custody totals are inconsistent");
        }
      } catch (ArithmeticException exception) {
        throw new IllegalArgumentException(
            "Maintenance furniture custody totals are inconsistent", exception);
      }
    }
  }

  /**
   * Immutable worker-facing work snapshot. Prices intentionally do not belong to this boundary.
   */
  record TaskWork(
      UUID id,
      String name,
      double quantity,
      String unit,
      Integer durationMinutes,
      String comment,
      List<UUID> sourceMediaIds) {
    public TaskWork {
      if (id == null
          || name == null
          || name.isBlank()
          || !Double.isFinite(quantity)
          || quantity < 0
          || durationMinutes == null
          || durationMinutes < 1
          || durationMinutes > 525600) {
        throw new IllegalArgumentException("Worker task work snapshot is invalid");
      }
      sourceMediaIds = sourceMediaIds == null ? List.of() : List.copyOf(sourceMediaIds);
      if (sourceMediaIds.stream().anyMatch(java.util.Objects::isNull)
          || sourceMediaIds.stream().distinct().count() != sourceMediaIds.size()) {
        throw new IllegalArgumentException("Worker task work media identities are invalid");
      }
    }

    public TaskWork(
        UUID id,
        String name,
        double quantity,
        String unit,
        Integer durationMinutes,
        String comment) {
      this(id, name, quantity, unit, durationMinutes, comment, List.of());
    }
  }

  record TaskMaterial(UUID id, String name, double quantity, String unit) {}

  record TaskComment(
      UUID id, String text, String authorDisplayName, OffsetDateTime createdAt) {}

  record TaskSourceMedia(
      UUID mediaId,
      long generation,
      String contentType,
      OffsetDateTime capturedAt,
      OffsetDateTime recordedAt) {}

  record TaskStage(
      UUID stageId,
      int order,
      RepairStageKind kind,
      String title,
      UUID queueId,
      OffsetDateTime taskDeadline,
      List<TaskWork> works,
      List<TaskMaterial> materials,
      List<TaskComment> comments,
      List<TaskSourceMedia> sourceMedia,
      Integer plannedDurationMinutes) {
    public TaskStage(
        UUID stageId,
        int order,
        RepairStageKind kind,
        String title,
        UUID queueId,
        OffsetDateTime taskDeadline) {
      this(
          stageId,
          order,
          kind,
          title,
          queueId,
          taskDeadline,
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          null);
    }

    public TaskStage(
        UUID stageId,
        int order,
        RepairStageKind kind,
        String title,
        UUID queueId,
        OffsetDateTime taskDeadline,
        List<TaskMaterial> materials,
        List<TaskComment> comments,
        List<TaskSourceMedia> sourceMedia) {
      this(
          stageId,
          order,
          kind,
          title,
          queueId,
          taskDeadline,
          List.of(),
          materials,
          comments,
          sourceMedia,
          null);
    }

    public TaskStage {
      if (plannedDurationMinutes != null && plannedDurationMinutes < 1) {
        throw new IllegalArgumentException(
            "Worker task stage planned duration must be positive when present");
      }
      works = works == null ? List.of() : List.copyOf(works);
      materials = materials == null ? List.of() : List.copyOf(materials);
      comments = comments == null ? List.of() : List.copyOf(comments);
      sourceMedia = sourceMedia == null ? List.of() : List.copyOf(sourceMedia);
    }
  }

  record TaskStageSnapshot(int routeIndex, UUID taskBoardEntryId, long entryVersion) {}

  record TaskSnapshot(
      UUID externalTaskId,
      long version,
      String state,
      List<TaskStageSnapshot> stages) {}

  enum PreStartTaskCancellationOutcome {
    CANCELLED,
    ALREADY_CANCELLED,
    STARTED,
    VERSION_CONFLICT
  }

  record PreStartTaskCancellation(
      PreStartTaskCancellationOutcome outcome,
      UUID taskId,
      UUID externalTaskId,
      long taskVersion,
      String state,
      OffsetDateTime cancelledAt) {
    public PreStartTaskCancellation {
      if (outcome == null
          || taskId == null
          || externalTaskId == null
          || taskVersion < 0
          || state == null
          || state.isBlank()) {
        throw new IllegalArgumentException("Task-board pre-start cancellation truth is invalid");
      }
      if ((outcome == PreStartTaskCancellationOutcome.CANCELLED
              || outcome == PreStartTaskCancellationOutcome.ALREADY_CANCELLED)
          && !"CANCELLED".equals(state)) {
        throw new IllegalArgumentException("Cancelled task-board guard must return CANCELLED state");
      }
    }
  }

  /**
   * Exact logistics command for an ordinary inbound or external-capital outbound movement.
   * {@code scheduledDate} remains the immutable requested date for {@code FIXED_DATE}; logistics
   * may normalize an overdue request only in its returned effective task truth.
   */
  record DriverTaskCommand(
      UUID warehouseId,
      UUID cabinId,
      UUID repairId,
      String sourceType,
      UUID sourceId,
      String kind,
      RepairLogisticsPlanningMode planningMode,
      LocalDate scheduledDate,
      int priority,
      boolean activateNow) {
    public DriverTaskCommand {
      if (warehouseId == null
          || cabinId == null
          || repairId == null
          || !Set.of("REPAIR", "ESTIMATE", "INVENTORY", "CAPITAL_REPAIR")
              .contains(sourceType)
          || sourceId == null
          || !Set.of("DELIVER_TO_REPAIR", "CAPITAL_TO_PRODUCTION").contains(kind)
          || ("CAPITAL_TO_PRODUCTION".equals(kind) != "CAPITAL_REPAIR".equals(sourceType))
          || planningMode == null
          || (planningMode == RepairLogisticsPlanningMode.FIXED_DATE)
              != (scheduledDate != null)
          || priority < 1
          || priority > 5
          || activateNow) {
        throw new IllegalArgumentException(
            "Maintenance driver-task command is invalid");
      }
    }
  }

  /**
   * Logistics-owned movement truth returned for a maintenance command. Its {@code scheduledDate}
   * is the effective execution date and can therefore be later than an overdue fixed request.
   */
  record DriverTaskSnapshot(
      UUID id,
      long version,
      UUID warehouseId,
      UUID cabinId,
      UUID repairId,
      String sourceType,
      UUID sourceId,
      String kind,
      RepairLogisticsPlanningMode planningMode,
      LocalDate scheduledDate,
      int priority,
      String state) {
    public DriverTaskSnapshot {
      if (id == null
          || version < 0
          || warehouseId == null
          || cabinId == null
          || repairId == null
          || !Set.of("REPAIR", "ESTIMATE", "INVENTORY", "CAPITAL_REPAIR")
              .contains(sourceType)
          || sourceId == null
          || !Set.of("DELIVER_TO_REPAIR", "CAPITAL_TO_PRODUCTION").contains(kind)
          || ("CAPITAL_TO_PRODUCTION".equals(kind) != "CAPITAL_REPAIR".equals(sourceType))
          || planningMode == null
          || scheduledDate == null
          || priority < 1
          || priority > 5
          || state == null
          || state.isBlank()) {
        throw new IllegalArgumentException(
            "Maintenance driver-task snapshot is invalid");
      }
    }
  }

  enum MaintenanceDriverTaskKind { DELIVER_TO_REPAIR, CAPITAL_TO_PRODUCTION }

  enum MaintenanceDriverTaskCompensationOutcome {
    ABSENT,
    PENDING,
    CANCELLED,
    STARTED,
    COMPLETED,
    RECONCILIATION_REQUIRED
  }

  /**
   * The private logistics compensation response. Allocation fields are present only when that
   * movement owns a repair-place effect; maintenance still validates its own local projection
   * before changing a repair identity.
   */
  record MaintenanceDriverTaskCompensation(
      UUID repairId,
      MaintenanceDriverTaskKind kind,
      MaintenanceDriverTaskCompensationOutcome outcome,
      UUID taskId,
      Long taskVersion,
      String state,
      UUID externalTaskId,
      UUID taskBoardTaskId,
      Long taskBoardTaskVersion,
      UUID repairPlaceAllocationId,
      Long repairPlaceAllocationVersion) {
    public MaintenanceDriverTaskCompensation {
      if (repairId == null || kind == null || outcome == null) {
        throw new IllegalArgumentException("Maintenance driver-task compensation identity is invalid");
      }
      if (outcome == MaintenanceDriverTaskCompensationOutcome.ABSENT) {
        if (taskId != null
            || taskVersion != null
            || state != null
            || externalTaskId != null
            || taskBoardTaskId != null
            || taskBoardTaskVersion != null
            || repairPlaceAllocationId != null
            || repairPlaceAllocationVersion != null) {
          throw new IllegalArgumentException("Absent driver-task compensation has unexpected truth");
        }
      } else if (taskId == null || taskVersion == null || taskVersion < 0 || state == null
          || state.isBlank()) {
        throw new IllegalArgumentException("Driver-task compensation truth is incomplete");
      }
      if ((repairPlaceAllocationId == null) != (repairPlaceAllocationVersion == null)
          || (repairPlaceAllocationVersion != null && repairPlaceAllocationVersion < 0)) {
        throw new IllegalArgumentException("Driver-task repair-place compensation truth is invalid");
      }
    }
  }

  record CatalogRoutingQueueRequirement(UUID queueDefinitionId, String type) {
    public CatalogRoutingQueueRequirement {
      if (queueDefinitionId == null
          || !("REPAIR".equals(type)
              || "HOLDING".equals(type)
              || "MOVEMENT".equals(type))) {
        throw new IllegalArgumentException("Catalog routing requirement is invalid");
      }
    }
  }

  record CatalogRoutingMismatch(UUID queueDefinitionId, List<String> fields) {
    public CatalogRoutingMismatch {
      if (queueDefinitionId == null
          || fields == null
          || fields.isEmpty()
          || fields.stream().anyMatch(field -> !"TYPE".equals(field))) {
        throw new IllegalArgumentException("Catalog routing mismatch is invalid");
      }
      fields = List.copyOf(fields);
    }
  }

  record QueueDefinitionSnapshot(UUID queueDefinitionId, String name, String type) {
    public QueueDefinitionSnapshot {
      if (queueDefinitionId == null
          || name == null
          || name.isBlank()
          || name.length() > 255
          || type == null
          || type.isBlank()
          || type.length() > 64) {
        throw new IllegalArgumentException("Queue-definition snapshot is invalid");
      }
      name = name.trim();
      type = type.trim();
    }
  }

  record CatalogRoutingPreflight(
      boolean ready,
      List<UUID> missingQueueDefinitionIds,
      List<CatalogRoutingMismatch> mismatches,
      List<QueueDefinitionSnapshot> resolvedDefinitions) {
    public CatalogRoutingPreflight {
      if (missingQueueDefinitionIds == null
          || mismatches == null
          || resolvedDefinitions == null) {
        throw new IllegalArgumentException("Catalog routing preflight is invalid");
      }
      missingQueueDefinitionIds = List.copyOf(missingQueueDefinitionIds);
      mismatches = List.copyOf(mismatches);
      resolvedDefinitions = List.copyOf(resolvedDefinitions);
    }
  }

  record RoutingQueueRequirement(UUID queueDefinitionId, String type) {
    public RoutingQueueRequirement {
      if (queueDefinitionId == null
          || !("REPAIR".equals(type)
              || "HOLDING".equals(type)
              || "MOVEMENT".equals(type))) {
        throw new IllegalArgumentException("Maintenance routing requirement is invalid");
      }
    }

  }

  record RoutingMismatch(UUID queueDefinitionId, List<String> fields) {
    private static final java.util.Set<String> FIELDS = java.util.Set.of(
        "TYPE", "ACTIVE", "HIDDEN");

    public RoutingMismatch {
      if (queueDefinitionId == null
          || fields == null
          || fields.isEmpty()
          || fields.stream().anyMatch(field -> field == null || !FIELDS.contains(field))
          || fields.size() != java.util.Set.copyOf(fields).size()) {
        throw new IllegalArgumentException("Maintenance routing mismatch is invalid");
      }
      fields = List.copyOf(fields);
    }

  }

  record RoutingQueueSnapshot(
      UUID queueDefinitionId, UUID workQueueId, String name, String type) {
    public RoutingQueueSnapshot {
      if (queueDefinitionId == null
          || workQueueId == null
          || name == null || name.isBlank() || name.length() > 255
          || type == null || type.isBlank() || type.length() > 64) {
        throw new IllegalArgumentException("Maintenance routing queue snapshot is invalid");
      }
      name = name.trim();
      type = type.trim();
    }

  }

  record RoutingPreflight(
      UUID warehouseId,
      boolean ready,
      List<UUID> missingQueueDefinitionIds,
      List<UUID> missingWarehouseBindingDefinitionIds,
      List<RoutingMismatch> mismatches,
      @JsonProperty("resolvedQueues") List<RoutingQueueSnapshot> queues) {
    public RoutingPreflight {
      if (warehouseId == null
          || missingQueueDefinitionIds == null
          || missingWarehouseBindingDefinitionIds == null
          || mismatches == null
          || queues == null) {
        throw new IllegalArgumentException("Maintenance routing preflight is invalid");
      }
      missingQueueDefinitionIds = List.copyOf(missingQueueDefinitionIds);
      missingWarehouseBindingDefinitionIds =
          List.copyOf(missingWarehouseBindingDefinitionIds);
      mismatches = List.copyOf(mismatches);
      queues = List.copyOf(queues);
    }

  }

  record MovementQueueBinding(UUID queueDefinitionId, UUID workQueueId) {
    public MovementQueueBinding {
      if (queueDefinitionId == null || workQueueId == null) {
        throw new IllegalArgumentException("Movement queue binding is invalid");
      }
    }
  }

  record QueueCapabilities(
      UUID warehouseId,
      List<MovementQueueBinding> movementQueueDefinitions) {
    public QueueCapabilities {
      if (warehouseId == null || movementQueueDefinitions == null) {
        throw new IllegalArgumentException("Warehouse queue capabilities are invalid");
      }
      movementQueueDefinitions = List.copyOf(movementQueueDefinitions);
    }
  }

  record CatalogPositionReference(
      UUID id,
      long version,
      UUID queueDefinitionId,
      String type,
      String externalReferenceId) {
    public CatalogPositionReference {
      if (id == null
          || version < 0
          || queueDefinitionId == null
          || !"CATALOG_POSITION".equals(type)
          || externalReferenceId == null
          || externalReferenceId.isBlank()
          || externalReferenceId.length() > 128) {
        throw new IllegalArgumentException("Catalog-position reference is invalid");
      }
    }

  }

  record MediaOwnerProof(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      boolean active) {
    private static final java.util.Set<String> OWNER_TYPES = java.util.Set.of(
        "MAINTENANCE_ESTIMATE",
        "MAINTENANCE_REPAIR",
        "MAINTENANCE_ACCEPTANCE");

    public MediaOwnerProof {
      if (!OWNER_TYPES.contains(ownerType)
          || ownerId == null
          || warehouseId == null
          || ownerRevision < 0
          || aggregateVersion < 0
          || proofEventId == null) {
        throw new IllegalArgumentException("Maintenance media owner proof is invalid");
      }
    }
  }

  enum WarehouseOperationDirection { INCOMING, OUTGOING }

  enum WarehouseLifecycleState { ACTIVE, DRAINING, INACTIVE }

  record WarehouseOperationAdmission(
      UUID warehouseId,
      long warehouseVersion,
      WarehouseLifecycleState lifecycleState,
      WarehouseOperationDirection direction,
      boolean admitted) {
    public WarehouseOperationAdmission {
      if (warehouseId == null
          || warehouseVersion < 0
          || lifecycleState == null
          || direction == null) {
        throw new IllegalArgumentException("Warehouse admission truth is invalid");
      }
    }
  }

  record WarehouseLifecycleReadinessWork(
      UUID warehouseId, long warehouseVersion, WarehouseLifecycleState lifecycleState) {
    public WarehouseLifecycleReadinessWork {
      if (warehouseId == null
          || warehouseVersion < 0
          || lifecycleState != WarehouseLifecycleState.DRAINING) {
        throw new IllegalArgumentException("Warehouse readiness work is invalid");
      }
    }
  }

  record WarehouseLifecycleReadinessWorkPage(
      List<WarehouseLifecycleReadinessWork> items, UUID nextAfter) {
    public WarehouseLifecycleReadinessWorkPage {
      if (items == null || items.stream().anyMatch(java.util.Objects::isNull)) {
        throw new IllegalArgumentException("Warehouse readiness page is invalid");
      }
      items = List.copyOf(items);
    }
  }

  record WarehouseLifecycleReadinessConfirmation(
      UUID warehouseId,
      long warehouseVersion,
      WarehouseLifecycleState lifecycleState,
      String readinessOwner,
      OffsetDateTime confirmedAt) {
    public WarehouseLifecycleReadinessConfirmation {
      if (warehouseId == null
          || warehouseVersion < 0
          || lifecycleState != WarehouseLifecycleState.DRAINING
          || !"MAINTENANCE".equals(readinessOwner)
          || confirmedAt == null) {
        throw new IllegalArgumentException("Warehouse readiness confirmation is invalid");
      }
    }
  }

  record WarehouseTimeZone(UUID warehouseId, String timeZone, OffsetDateTime effectiveFrom) {
    public WarehouseTimeZone {
      if (warehouseId == null
          || timeZone == null
          || timeZone.isBlank()
          || timeZone.length() > 64
          || effectiveFrom == null) {
        throw new IllegalArgumentException("Warehouse timezone truth is invalid");
      }
      timeZone = timeZone.trim();
      java.time.ZoneId.of(timeZone);
    }
  }
}
