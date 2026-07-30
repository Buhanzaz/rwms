package dev.buhanzaz.rwms.maintenance.integration;

import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Development/test fixture only; production safety rejects disabled dependencies. */
final class NoOpMaintenanceDependencyGateway implements MaintenanceDependencyGateway {
  @Override
  public boolean productionReady() { return false; }

  @Override
  public AssetSnapshot getRentalItemSnapshot(UUID rentalItemId) {
    throw new MaintenanceDependencyException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Canonical asset snapshot is unavailable without production dependencies");
  }

  @Override
  public FurnitureEquipmentSnapshot ensureFurnitureEquipment(
      UUID catalogNodeId, String equipmentName) {
    String canonicalName = equipmentName == null ? "" : equipmentName.trim();
    if (catalogNodeId == null
        || canonicalName.isEmpty()
        || canonicalName.length() > 255) {
      throw new IllegalArgumentException("Furniture equipment identity is invalid");
    }
    return new FurnitureEquipmentSnapshot(
        UUID.nameUUIDFromBytes(
            ("furniture-equipment:" + catalogNodeId).getBytes(StandardCharsets.UTF_8)),
        canonicalName);
  }

  @Override
  public List<CabinCharacteristicSnapshot> cabinCharacteristics() {
    return List.of();
  }

  @Override
  public AppliedCabinCharacteristic applyCabinCharacteristic(
      UUID key, UUID rentalItemId, UUID characteristicId) {
    return new AppliedCabinCharacteristic(rentalItemId, characteristicId, true, 0);
  }

  @Override
  public LeaseSnapshot acquireLease(
      UUID key, UUID rentalItemId, long expectedVersion, String ownerType, String ownerId) {
    return new LeaseSnapshot(
        deterministic("lease", key), 0, rentalItemId, ownerType, UUID.fromString(ownerId), 1,
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15));
  }

  @Override
  public LeaseSnapshot renewLease(
      UUID key,
      UUID leaseId,
      long leaseExpectedVersion,
      long fencingToken,
      String ownerType,
      String ownerId) {
    return new LeaseSnapshot(
        leaseId,
        Math.addExact(leaseExpectedVersion, 1),
        deterministic("rental-item", leaseId),
        ownerType,
        UUID.fromString(ownerId),
        fencingToken,
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15));
  }

  @Override
  public AssetSnapshot fencedStatus(
      UUID key,
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
      List<FurnitureLoss> furnitureLosses) {
    if (!furnitureLosses.isEmpty()) {
      throw new MaintenanceDependencyException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Furniture losses require the production asset dependency");
    }
    String status = transition.contains("CAPITAL") ? "CAPITAL_REPAIR"
        : transition.contains("FREE") ? "FREE"
        : transition.contains("WRITE_OFF") ? "WRITTEN_OFF"
        : transition.contains("WAITING") ? "WAITING_REPAIR_CHECK" : "REPAIR";
    return new AssetSnapshot(
        rentalItemId, Math.addExact(rentalItemExpectedVersion, 1), warehouseId, status);
  }

  @Override
  public void releaseLease(
      UUID key,
      UUID leaseId,
      long leaseExpectedVersion,
      long fencingToken,
      String ownerType,
      String ownerId) {}

  @Override
  public TaskSnapshot registerTask(
      UUID key,
      UUID externalTaskId,
      UUID sourceRepairId,
      UUID warehouseId,
      UUID rentalItemId,
      String unitNumber,
      java.time.LocalDate scheduledDate,
      int priority,
      int dailyCapacity,
      List<TaskStage> stages) {
    return task(externalTaskId, stages, 0, "ACTIVE");
  }

  @Override
  public TaskSnapshot updatePreStartTask(
      UUID key, UUID externalTaskId, long expectedVersion, String unitNumber,
      List<TaskStage> stages) {
    return task(externalTaskId, stages, Math.addExact(expectedVersion, 1), "ACTIVE");
  }

  @Override
  public TaskSnapshot getTask(UUID externalTaskId) {
    return new TaskSnapshot(externalTaskId, 0, "ACTIVE", List.of());
  }

  @Override
  public TaskSnapshot cancelTask(UUID key, UUID externalTaskId, long expectedVersion) {
    return new TaskSnapshot(
        externalTaskId, Math.addExact(expectedVersion, 1), "CANCELLED", List.of());
  }

  @Override
  public TaskSnapshot relocateTask(
      UUID key, UUID externalTaskId, long expectedVersion, UUID targetWarehouseId) {
    return new TaskSnapshot(
        externalTaskId, Math.addExact(expectedVersion, 1), "ACTIVE", List.of());
  }

  @Override
  public CatalogRoutingPreflight preflightCatalogRouting(
      List<CatalogRoutingQueueRequirement> queues) {
    return new CatalogRoutingPreflight(
        true,
        List.of(),
        List.of(),
        queues.stream()
            .map(
                queue ->
                    new QueueDefinitionSnapshot(
                        queue.queueDefinitionId(),
                        queue.queueDefinitionId().toString(),
                        queue.type()))
            .toList());
  }

  @Override
  public RoutingPreflight preflightMaintenanceRouting(
      UUID warehouseId, List<RoutingQueueRequirement> queues) {
    return new RoutingPreflight(
        warehouseId,
        true,
        List.of(),
        List.of(),
        List.of(),
        queues.stream()
            .map(queue -> new RoutingQueueSnapshot(
                queue.queueDefinitionId(),
                deterministic("work-queue", queue.queueDefinitionId()),
                queue.queueDefinitionId().toString(),
                queue.type()))
            .toList());
  }

  @Override
  public RepairComplexityThresholds repairComplexityThresholds(UUID warehouseId) {
    return new RepairComplexityThresholds(warehouseId, 0, 60, 180, 360);
  }

  @Override
  public QueueCapabilities queueCapabilities(UUID warehouseId) {
    return new QueueCapabilities(warehouseId, false, List.of());
  }

  @Override
  public CatalogPositionReference registerCatalogPosition(
      UUID queueId, String externalReferenceId) {
    return new CatalogPositionReference(
        UUID.nameUUIDFromBytes(
            ("catalog-position:" + externalReferenceId).getBytes(StandardCharsets.UTF_8)),
        0,
        queueId,
        "CATALOG_POSITION",
        externalReferenceId);
  }

  @Override
  public void deleteCatalogPosition(String externalReferenceId, long expectedVersion) {}

  @Override
  public MediaOwnerProof upsertMediaOwnerProof(MediaOwnerProof proof) {
    return proof;
  }

  private static TaskSnapshot task(
      UUID externalTaskId, List<TaskStage> stages, long version, String state) {
    return new TaskSnapshot(externalTaskId, version, state, stages.stream()
        .map(stage -> new TaskStageSnapshot(
            stage.order(), deterministic("entry:" + stage.order(), externalTaskId), 0))
        .toList());
  }

  private static UUID deterministic(String prefix, UUID key) {
    return UUID.nameUUIDFromBytes((prefix + ":" + key).getBytes(StandardCharsets.UTF_8));
  }
}
