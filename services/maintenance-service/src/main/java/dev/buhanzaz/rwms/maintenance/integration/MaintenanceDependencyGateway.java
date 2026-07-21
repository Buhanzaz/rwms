package dev.buhanzaz.rwms.maintenance.integration;

import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface MaintenanceDependencyGateway {
  default boolean productionReady() { return true; }

  AssetSnapshot getRentalItemSnapshot(UUID rentalItemId);

  FurnitureEquipmentSnapshot ensureFurnitureEquipment(
      String equipmentCode, String equipmentName);

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
      UUID warehouseId,
      UUID rentalItemId,
      List<TaskStage> stages);

  TaskSnapshot updatePreStartTask(
      UUID idempotencyKey,
      UUID externalTaskId,
      long expectedVersion,
      List<TaskStage> stages);

  TaskSnapshot getTask(UUID externalTaskId);

  TaskSnapshot cancelTask(
      UUID idempotencyKey, UUID externalTaskId, long expectedVersion);

  RoutingPreflight preflightMaintenanceRouting(
      UUID warehouseId, List<RoutingQueueRequirement> queues);

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

  record AssetSnapshot(UUID rentalItemId, long version, UUID warehouseId, String status) {}

  record FurnitureEquipmentSnapshot(
      UUID equipmentId, String equipmentCode, String equipmentName) {
    public FurnitureEquipmentSnapshot {
      if (equipmentId == null
          || equipmentCode == null
          || !equipmentCode.matches("^[A-Z0-9][A-Z0-9_-]{0,63}$")
          || equipmentName == null
          || equipmentName.isBlank()
          || equipmentName.length() > 255) {
        throw new IllegalArgumentException("Furniture equipment snapshot is invalid");
      }
    }
  }

  record FurnitureLoss(UUID equipmentId, String equipmentCode, long quantity) {}

  record TaskStage(
      UUID stageId,
      int order,
      RepairStageKind kind,
      String title,
      String queueRef,
      OffsetDateTime taskDeadline) {}

  record TaskStageSnapshot(int routeIndex, UUID taskBoardEntryId, long entryVersion) {}

  record TaskSnapshot(
      UUID externalTaskId,
      long version,
      String state,
      List<TaskStageSnapshot> stages) {}

  record RoutingQueueRequirement(UUID queueId, String code, String type) {
    public RoutingQueueRequirement {
      if (queueId == null
          || code == null
          || code.isBlank()
          || code.length() > 64
          || !("REPAIR".equals(type) || "HOLDING".equals(type))) {
        throw new IllegalArgumentException("Maintenance routing requirement is invalid");
      }
    }
  }

  record RoutingMismatch(UUID queueId, List<String> fields) {
    private static final java.util.Set<String> FIELDS = java.util.Set.of(
        "WAREHOUSE_ID", "CODE", "TYPE", "ACTIVE", "HIDDEN");

    public RoutingMismatch {
      if (queueId == null
          || fields == null
          || fields.isEmpty()
          || fields.stream().anyMatch(field -> field == null || !FIELDS.contains(field))
          || fields.size() != java.util.Set.copyOf(fields).size()) {
        throw new IllegalArgumentException("Maintenance routing mismatch is invalid");
      }
      fields = List.copyOf(fields);
    }
  }

  record RoutingPreflight(
      UUID warehouseId,
      boolean ready,
      List<UUID> missingQueueIds,
      List<RoutingMismatch> mismatches) {
    public RoutingPreflight {
      if (warehouseId == null || missingQueueIds == null || mismatches == null) {
        throw new IllegalArgumentException("Maintenance routing preflight is invalid");
      }
      missingQueueIds = List.copyOf(missingQueueIds);
      mismatches = List.copyOf(mismatches);
    }
  }

  record CatalogPositionReference(
      UUID id,
      long version,
      UUID queueId,
      String type,
      String externalReferenceId) {
    public CatalogPositionReference {
      if (id == null
          || version < 0
          || queueId == null
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
        "MAINTENANCE_ACCEPTANCE",
        "MAINTENANCE_CATALOG_NODE");

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
