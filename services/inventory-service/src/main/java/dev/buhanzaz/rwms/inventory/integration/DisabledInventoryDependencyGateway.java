package dev.buhanzaz.rwms.inventory.integration;

import dev.buhanzaz.rwms.inventory.service.InventoryException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Disabled development/test implementation of the inventory private dependency boundary.
 */
final class DisabledInventoryDependencyGateway implements InventoryDependencyGateway {
  private InventoryException unavailable() {
    return InventoryException.dependency("Inventory dependencies are disabled");
  }

  @Override
  public WarehouseOperation beginWarehouseOperation(
      UUID warehouseId,
      UUID operationId,
      OffsetDateTime occurredAt,
      WarehouseOperationDirection direction) {
    throw unavailable();
  }

  @Override
  public WarehouseAdmission warehouseAdmission(
      UUID warehouseId, WarehouseOperationDirection direction) {
    throw unavailable();
  }

  @Override
  public WorkCalendarSnapshot workCalendarSnapshot(UUID warehouseId, LocalDate from, LocalDate through) {
    throw unavailable();
  }

  @Override
  public WarehouseLifecycleReadinessWorkPage warehouseLifecycleReadinessWork(
      UUID after, int limit) {
    throw unavailable();
  }

  @Override
  public WarehouseLifecycleReadinessConfirmation confirmWarehouseLifecycleReadiness(
      UUID warehouseId, long expectedVersion) {
    throw unavailable();
  }

  @Override
  public Capture createCapture(UUID key, CaptureRequest request) {
    throw unavailable();
  }

  @Override
  public CapturePage readCapture(UUID id, String cursor, int size) {
    throw unavailable();
  }

  @Override
  public void releaseCapture(UUID id) {
    throw unavailable();
  }

  @Override
  public NumberResolution resolveNumber(UUID warehouseId, String number) {
    throw unavailable();
  }

  @Override
  public Optional<LiveAssetSnapshot> currentAsset(UUID assetId) {
    throw unavailable();
  }

  @Override
  public NormalReturnInspection normalReturnInspection(UUID returnId) {
    throw unavailable();
  }

  @Override
  public Optional<CompletedReturnEstimateProof> completedReturnEstimate(UUID estimateId) {
    throw unavailable();
  }

  @Override
  public SourceAsset createSourceAsset(UUID key, JsonNode request) {
    throw unavailable();
  }

  @Override
  public Validation validateAssets(List<UUID> assetIds) {
    throw unavailable();
  }

  @Override
  public RepairSnapshots repairSnapshots(List<UUID> assetIds) {
    throw unavailable();
  }

  @Override
  public FurnitureSnapshot furnitureSnapshot(UUID warehouseId, List<UUID> assetIds) {
    throw unavailable();
  }

  @Override
  public void reconcileFurniture(
      UUID inventoryId, UUID idempotencyKey, FurnitureReconciliationRequest request) {
    throw unavailable();
  }

  @Override
  public InventoryAssetOutcome applyInventoryOutcome(
      UUID inventoryId, UUID findingId, UUID idempotencyKey, JsonNode request) {
    throw unavailable();
  }

  @Override
  public InventoryCabinPhotoOutcome publishInventoryCabinPhotos(
      UUID inventoryId, UUID findingId, UUID idempotencyKey, JsonNode request) {
    throw unavailable();
  }

  @Override
  public JsonNode applyLogisticsOutcomes(
      UUID inventoryId, UUID idempotencyKey, JsonNode request) {
    throw unavailable();
  }

  @Override
  public InventoryLossDisposition createInventoryLossDisposition(
      UUID idempotencyKey, InventoryLossDispositionRequest request) {
    throw unavailable();
  }

  @Override
  public InventoryCabinWriteOffOutcome createInventoryCabinWriteOff(
      UUID idempotencyKey, InventoryCabinWriteOffRequest request) {
    throw unavailable();
  }

  @Override
  public FrozenPlan freezePlan(UUID key, JsonNode request) {
    throw unavailable();
  }

  @Override
  public JsonNode preflightReconciliation(UUID key, JsonNode request) {
    throw unavailable();
  }

  @Override
  public JsonNode applyReconciliation(
      UUID inventoryId, UUID findingId, UUID key, JsonNode request) {
    throw unavailable();
  }

  @Override
  public JsonNode applyNoWorkDisposition(
      UUID inventoryId, UUID findingId, UUID key, JsonNode request) {
    throw unavailable();
  }

  @Override
  public RepairUpsert upsertRepair(UUID inventoryId, UUID findingId, UUID key, JsonNode request) {
    throw unavailable();
  }

  @Override
  public boolean productionReady() {
    return false;
  }
}
