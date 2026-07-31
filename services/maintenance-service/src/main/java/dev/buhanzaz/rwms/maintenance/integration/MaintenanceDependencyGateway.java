package dev.buhanzaz.rwms.maintenance.integration;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface MaintenanceDependencyGateway {
  default boolean productionReady() { return true; }

  AssetSnapshot getRentalItemSnapshot(UUID rentalItemId);

  FurnitureEquipmentSnapshot ensureFurnitureEquipment(
      UUID catalogNodeId, String equipmentName);

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
        null,
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
      UUID estimateId,
      List<FurnitureLoss> furnitureLosses);

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
    return registerTask(
        idempotencyKey,
        externalTaskId,
        null,
        warehouseId,
        rentalItemId,
        unitNumber,
        LocalDate.now(java.time.ZoneId.of("Europe/Moscow")),
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

  TaskSnapshot relocateTask(
      UUID idempotencyKey,
      UUID externalTaskId,
      long expectedVersion,
      UUID targetWarehouseId);

  DriverTaskSnapshot createDriverTask(
      UUID idempotencyKey, DriverTaskCommand command);

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

  record FurnitureEquipmentSnapshot(UUID equipmentId, String equipmentName) {
    public FurnitureEquipmentSnapshot {
      if (equipmentId == null
          || equipmentName == null
          || equipmentName.isBlank()
          || equipmentName.length() > 255) {
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

  record FurnitureLoss(UUID equipmentId, long quantity) {}

  /**
   * Immutable worker-facing work snapshot. Prices intentionally do not belong to this boundary.
   */
  record TaskWork(
      UUID id,
      String name,
      double quantity,
      String unit,
      Integer durationMinutes,
      String comment) {
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
          || !Set.of("REPAIR", "ESTIMATE", "INVENTORY")
              .contains(sourceType)
          || sourceId == null
          || !"DELIVER_TO_REPAIR".equals(kind)
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
          || !Set.of("REPAIR", "ESTIMATE", "INVENTORY")
              .contains(sourceType)
          || sourceId == null
          || !"DELIVER_TO_REPAIR".equals(kind)
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
      boolean movementToShipmentAvailable,
      List<MovementQueueBinding> movementQueueDefinitions) {
    public QueueCapabilities {
      if (warehouseId == null || movementQueueDefinitions == null) {
        throw new IllegalArgumentException("Warehouse queue capabilities are invalid");
      }
      movementQueueDefinitions = List.copyOf(movementQueueDefinitions);
      if (movementToShipmentAvailable != !movementQueueDefinitions.isEmpty()) {
        throw new IllegalArgumentException("Movement capability contradicts its bindings");
      }
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
}
