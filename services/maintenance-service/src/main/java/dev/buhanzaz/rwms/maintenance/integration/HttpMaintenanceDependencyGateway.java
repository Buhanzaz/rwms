package dev.buhanzaz.rwms.maintenance.integration;

import static dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;

/**
 * Private maintenance dependency facade that preserves the gateway seam while routing each
 * dependency boundary to its cohesive owner.
 */
final class HttpMaintenanceDependencyGateway implements MaintenanceDependencyGateway {
  private final MaintenanceWarehouseHttpClient warehouse;
  private final MaintenanceAssetHttpClient asset;
  private final MaintenanceLogisticsHttpClient logistics;
  private final MaintenanceTaskBoardHttpClient taskBoard;
  private final MaintenanceMediaHttpClient media;

  HttpMaintenanceDependencyGateway(
      RestClient client,
      OAuth2AuthorizedClientManager authorizedClients,
      MaintenanceDependencyProperties.Validated properties) {
    MaintenanceHttpTransport transport = new MaintenanceHttpTransport(client, authorizedClients);
    warehouse = new MaintenanceWarehouseHttpClient(transport, properties);
    asset = new MaintenanceAssetHttpClient(transport, properties);
    logistics = new MaintenanceLogisticsHttpClient(transport, properties);
    taskBoard = new MaintenanceTaskBoardHttpClient(transport, properties);
    media = new MaintenanceMediaHttpClient(transport, properties);
  }

  @Override
  public WarehouseOperationAdmission warehouseAdmission(
      UUID warehouseId, WarehouseOperationDirection direction) {
    return warehouse.warehouseAdmission(warehouseId, direction);
  }

  @Override
  public WarehouseLifecycleReadinessWorkPage warehouseLifecycleReadinessWork(
      UUID after, int limit) {
    return warehouse.warehouseLifecycleReadinessWork(after, limit);
  }

  @Override
  public WarehouseLifecycleReadinessConfirmation confirmWarehouseLifecycleReadiness(
      UUID warehouseId, long expectedVersion) {
    return warehouse.confirmWarehouseLifecycleReadiness(warehouseId, expectedVersion);
  }

  @Override
  public WarehouseTimeZone warehouseTimeZoneAt(UUID warehouseId, OffsetDateTime at) {
    return warehouse.warehouseTimeZoneAt(warehouseId, at);
  }

  @Override
  public ReturnArrival returnArrival(UUID warehouseId, UUID rentalItemId) {
    return logistics.returnArrival(warehouseId, rentalItemId);
  }

  @Override
  public void markWarehouseOperation(
      UUID warehouseId, UUID operationId, OffsetDateTime occurredAt) {
    warehouse.markWarehouseOperation(warehouseId, operationId, occurredAt);
  }

  @Override
  public AssetSnapshot getRentalItemSnapshot(UUID rentalItemId) {
    return asset.getRentalItemSnapshot(rentalItemId);
  }

  @Override
  public PropertyAssetSnapshot getPropertyAssetSnapshot(
      PropertyAssetKind assetKind, UUID assetId, UUID warehouseId) {
    return asset.getPropertyAssetSnapshot(assetKind, assetId, warehouseId);
  }

  @Override
  public PropertyDispositionFence preparePropertyDisposition(
      UUID key, UUID decisionId, PropertyDispositionPreparation request) {
    return asset.preparePropertyDisposition(key, decisionId, request);
  }

  @Override
  public PropertyDispositionEffect applyPropertyDisposition(
      UUID key, UUID decisionId, UUID completedMovementTaskId) {
    return asset.applyPropertyDisposition(key, decisionId, completedMovementTaskId);
  }

  @Override
  public PropertyEquipmentMovementTask createPropertyEquipmentMovementTask(
      UUID key, PropertyEquipmentMovementCommand command) {
    return logistics.createPropertyEquipmentMovementTask(key, command);
  }

  @Override
  public PropertyEquipmentMovementTask getPropertyEquipmentMovementTask(UUID taskId) {
    return logistics.getPropertyEquipmentMovementTask(taskId);
  }

  @Override
  public List<MaintenanceFurnitureCustodyClaim> unresolvedFurnitureCustody(
      String ownerType, UUID ownerId) {
    return asset.unresolvedFurnitureCustody(ownerType, ownerId);
  }

  @Override
  public FurnitureEquipmentSnapshot ensureFurnitureEquipment(
      UUID catalogNodeId,
      String equipmentName,
      Long expectedEquipmentVersion,
      Integer maximumPerCabin) {
    return asset.ensureFurnitureEquipment(
        catalogNodeId, equipmentName, expectedEquipmentVersion, maximumPerCabin);
  }

  @Override
  public List<FurnitureEquipmentSnapshot> furnitureEquipmentSnapshots(
      List<UUID> catalogNodeIds) {
    return asset.furnitureEquipmentSnapshots(catalogNodeIds);
  }

  @Override
  public List<CabinCharacteristicSnapshot> cabinCharacteristics() {
    return asset.cabinCharacteristics();
  }

  @Override
  public AppliedCabinCharacteristic applyCabinCharacteristic(
      UUID key, UUID rentalItemId, UUID characteristicId) {
    return asset.applyCabinCharacteristic(key, rentalItemId, characteristicId);
  }

  @Override
  public LeaseSnapshot acquireLease(
      UUID key, UUID rentalItemId, long expectedVersion, String ownerType, String ownerId) {
    return asset.acquireLease(key, rentalItemId, expectedVersion, ownerType, ownerId);
  }

  @Override
  public LeaseSnapshot renewLease(
      UUID key,
      UUID leaseId,
      long expectedVersion,
      long fencingToken,
      String ownerType,
      String ownerId) {
    return asset.renewLease(key, leaseId, expectedVersion, fencingToken, ownerType, ownerId);
  }

  @Override
  public AssetSnapshot fencedStatus(
      UUID key,
      UUID rentalItemId,
      UUID warehouseId,
      long expectedVersion,
      UUID leaseId,
      long fencingToken,
      String ownerType,
      String ownerId,
      String transition,
      boolean linkedReturn) {
    return fencedStatus(
        key,
        rentalItemId,
        warehouseId,
        expectedVersion,
        leaseId,
        fencingToken,
        ownerType,
        ownerId,
        transition,
        linkedReturn,
        List.of());
  }

  @Override
  public AssetSnapshot fencedStatus(
      UUID key,
      UUID rentalItemId,
      UUID warehouseId,
      long expectedVersion,
      UUID leaseId,
      long fencingToken,
      String ownerType,
      String ownerId,
      String transition,
      boolean linkedReturn,
      List<FurniturePendingReturn> furniturePendingReturns) {
    return asset.fencedStatus(
        key,
        rentalItemId,
        warehouseId,
        expectedVersion,
        leaseId,
        fencingToken,
        ownerType,
        ownerId,
        transition,
        linkedReturn,
        furniturePendingReturns);
  }

  @Override
  public void releaseLease(
      UUID key,
      UUID leaseId,
      long expectedVersion,
      long fencingToken,
      String ownerType,
      String ownerId) {
    asset.releaseLease(key, leaseId, expectedVersion, fencingToken, ownerType, ownerId);
  }

  @Override
  public TaskSnapshot registerTask(
      UUID key,
      UUID externalTaskId,
      UUID sourceRepairId,
      UUID warehouseId,
      UUID rentalItemId,
      String unitNumber,
      LocalDate scheduledDate,
      int priority,
      List<TaskStage> stages) {
    return taskBoard.registerTask(
        key,
        externalTaskId,
        sourceRepairId,
        warehouseId,
        rentalItemId,
        unitNumber,
        scheduledDate,
        priority,
        stages);
  }

  @Override
  public TaskSnapshot updatePreStartTask(
      UUID key,
      UUID externalTaskId,
      long expectedVersion,
      String unitNumber,
      List<TaskStage> stages) {
    return taskBoard.updatePreStartTask(key, externalTaskId, expectedVersion, unitNumber, stages);
  }

  @Override
  public TaskSnapshot getTask(UUID externalTaskId) {
    return taskBoard.getTask(externalTaskId);
  }

  @Override
  public TaskSnapshot cancelTask(UUID key, UUID externalTaskId, long expectedVersion) {
    return taskBoard.cancelTask(key, externalTaskId, expectedVersion);
  }

  @Override
  public PreStartTaskCancellation cancelTaskIfPreStart(
      UUID key, UUID externalTaskId, long expectedVersion) {
    return taskBoard.cancelTaskIfPreStart(key, externalTaskId, expectedVersion);
  }

  @Override
  public PreStartTaskCancellation cancelTaskIfPreStart(
      UUID key, UUID externalTaskId, long expectedVersion, String reason) {
    return taskBoard.cancelTaskIfPreStart(key, externalTaskId, expectedVersion, reason);
  }

  @Override
  public TaskSnapshot relocateTask(
      UUID key, UUID externalTaskId, long expectedVersion, UUID targetWarehouseId) {
    return taskBoard.relocateTask(key, externalTaskId, expectedVersion, targetWarehouseId);
  }

  @Override
  public DriverTaskSnapshot createDriverTask(UUID key, DriverTaskCommand command) {
    return logistics.createDriverTask(key, command);
  }

  @Override
  public MaintenanceDriverTaskCompensation maintenanceDriverTaskCompensation(
      UUID repairId, MaintenanceDriverTaskKind kind) {
    return logistics.maintenanceDriverTaskCompensation(repairId, kind);
  }

  @Override
  public MaintenanceDriverTaskCompensation cancelMaintenanceDriverTaskCompensation(
      UUID key, UUID repairId, MaintenanceDriverTaskKind kind) {
    return logistics.cancelMaintenanceDriverTaskCompensation(key, repairId, kind);
  }

  @Override
  public CatalogRoutingPreflight preflightCatalogRouting(
      List<CatalogRoutingQueueRequirement> queues) {
    return taskBoard.preflightCatalogRouting(queues);
  }

  @Override
  public RoutingPreflight preflightMaintenanceRouting(
      UUID warehouseId, List<RoutingQueueRequirement> queues) {
    return taskBoard.preflightMaintenanceRouting(warehouseId, queues);
  }

  @Override
  public QueueCapabilities queueCapabilities(UUID warehouseId) {
    return taskBoard.queueCapabilities(warehouseId);
  }

  @Override
  public CatalogPositionReference registerCatalogPosition(
      UUID queueId, String externalReferenceId) {
    return taskBoard.registerCatalogPosition(queueId, externalReferenceId);
  }

  @Override
  public void deleteCatalogPosition(String externalReferenceId, long expectedVersion) {
    taskBoard.deleteCatalogPosition(externalReferenceId, expectedVersion);
  }

  @Override
  public MediaOwnerProof upsertMediaOwnerProof(MediaOwnerProof proof) {
    return media.upsertMediaOwnerProof(proof);
  }
}
