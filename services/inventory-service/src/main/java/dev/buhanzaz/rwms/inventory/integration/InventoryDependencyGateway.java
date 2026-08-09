package dev.buhanzaz.rwms.inventory.integration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Private integration boundary from inventory workflow to warehouse, asset and maintenance owners.
 */
public interface InventoryDependencyGateway {
  WarehouseOperation beginWarehouseOperation(
      UUID warehouseId,
      UUID operationId,
      OffsetDateTime occurredAt,
      WarehouseOperationDirection direction);

  WarehouseAdmission warehouseAdmission(
      UUID warehouseId, WarehouseOperationDirection direction);

  WarehouseLifecycleReadinessWorkPage warehouseLifecycleReadinessWork(
      UUID after, int limit);

  WarehouseLifecycleReadinessConfirmation confirmWarehouseLifecycleReadiness(
      UUID warehouseId, long expectedVersion);

  Capture createCapture(UUID idempotencyKey, CaptureRequest request);

  CapturePage readCapture(UUID captureId, String cursor, int size);

  void releaseCapture(UUID captureId);

  NumberResolution resolveNumber(UUID warehouseId, String number);

  Optional<LiveAssetSnapshot> currentAsset(UUID assetId);

  SourceAsset createSourceAsset(UUID idempotencyKey, JsonNode request);

  Validation validateAssets(List<UUID> assetIds);

  RepairSnapshots repairSnapshots(List<UUID> assetIds);

  FurnitureSnapshot furnitureSnapshot(UUID warehouseId, List<UUID> assetIds);

  void reconcileFurniture(
      UUID inventoryId, UUID idempotencyKey, FurnitureReconciliationRequest request);

  InventoryLossDisposition createInventoryLossDisposition(
      UUID idempotencyKey, InventoryLossDispositionRequest request);

  FrozenPlan freezePlan(UUID idempotencyKey, JsonNode request);

  /**
   * Reads the maintenance-owned candidate set before inventory freezes a final plan version.
   * The body is deliberately canonical JSON because the frozen plan snapshot remains opaque
   * evidence owned by maintenance-service.
   */
  JsonNode preflightReconciliation(UUID idempotencyKey, JsonNode request);

  /** Applies one already-selected final-plan reconciliation using a stable idempotency key. */
  JsonNode applyReconciliation(
      UUID inventoryId, UUID findingId, UUID idempotencyKey, JsonNode request);

  RepairUpsert upsertRepair(
      UUID inventoryId, UUID findingId, UUID idempotencyKey, JsonNode request);

  default boolean productionReady() {
    return true;
  }

  enum WarehouseOperationDirection {
    INCOMING,
    OUTGOING
  }

  record WarehouseOperation(
      UUID warehouseId,
      long warehouseVersion,
      String lifecycleState,
      WarehouseOperationDirection direction,
      String timeZone,
      OffsetDateTime timeZoneEffectiveFrom) {}

  record WarehouseAdmission(
      UUID warehouseId,
      long warehouseVersion,
      String lifecycleState,
      WarehouseOperationDirection direction,
      boolean admitted) {}

  record WarehouseLifecycleReadinessWork(
      UUID warehouseId, long warehouseVersion, String lifecycleState) {}

  record WarehouseLifecycleReadinessWorkPage(
      List<WarehouseLifecycleReadinessWork> items, UUID nextAfter) {}

  record WarehouseLifecycleReadinessConfirmation(
      UUID warehouseId,
      long warehouseVersion,
      String lifecycleState,
      String readinessOwner,
      OffsetDateTime confirmedAt) {}

  record CaptureRequest(
      UUID operationId, long technicalAttempt, String requestFingerprint, UUID warehouseId) {}

  record Capture(
      UUID captureId,
      UUID operationId,
      long technicalAttempt,
      UUID warehouseId,
      long totalCount,
      String membershipDigest,
      OffsetDateTime createdAt,
      OffsetDateTime expiresAt) {}

  record CaptureMember(
      long sequence,
      UUID assetId,
      long version,
      UUID warehouseId,
      String status,
      String displayCanonicalNumber,
      String identityMatchKey,
      JsonNode passportSnapshot,
      JsonNode contentsSnapshot) {}

  record CapturePage(
      UUID captureId,
      UUID operationId,
      long technicalAttempt,
      UUID warehouseId,
      long totalCount,
      String membershipDigest,
      String nextCursor,
      List<CaptureMember> content) {}

  record AssetSnapshot(
      UUID assetId,
      long version,
      UUID warehouseId,
      String status,
      String displayCanonicalNumber,
      String identityMatchKey,
      String tenantSnapshot) {
    public AssetSnapshot(
        UUID assetId,
        long version,
        UUID warehouseId,
        String status,
        String displayCanonicalNumber,
        String identityMatchKey) {
      this(
          assetId,
          version,
          warehouseId,
          status,
          displayCanonicalNumber,
          identityMatchKey,
          null);
    }
  }

  record LiveAssetSnapshot(
      UUID assetId,
      long version,
      UUID warehouseId,
      String status,
      String displayCanonicalNumber,
      String identityMatchKey,
      String tenantSnapshot,
      JsonNode passportSnapshot,
      JsonNode contentsSnapshot) {}

  record NumberResolution(
      String displayCanonicalNumber, String identityMatchKey, boolean found, AssetSnapshot asset) {}

  record SourceAsset(UUID inventoryId, UUID findingId, AssetSnapshot asset) {}

  record ValidationItem(
      UUID assetId,
      boolean found,
      Long version,
      UUID warehouseId,
      String status,
      String displayCanonicalNumber,
      String identityMatchKey,
      String tenantSnapshot,
      JsonNode passportSnapshot,
      JsonNode contentsSnapshot) {}

  record Validation(
      OffsetDateTime validatedAt, String validationDigest, List<ValidationItem> assets) {}

  record RepairRegistryFact(
      UUID repairId,
      UUID rootRepairId,
      String origin,
      String kind,
      String executionState,
      String acceptanceState,
      String planFingerprintSha256) {}

  record RepairAssetSnapshot(UUID assetId, List<RepairRegistryFact> repairs) {}

  record RepairSnapshots(List<RepairAssetSnapshot> assets) {}

  record FurnitureSnapshot(
      UUID warehouseId, String snapshotSha256, List<FurnitureSnapshotItem> items) {}

  record FurnitureSnapshotItem(
      UUID equipmentId,
      long catalogVersion,
      String equipmentName,
      long currentStockQuantity,
      Long stockBalanceVersion,
      List<FurnitureSnapshotCabin> cabins) {}

  record FurnitureSnapshotCabin(
      UUID assetId,
      long assetVersion,
      String displayCanonicalNumber,
      String status,
      long currentQuantity) {}

  record FurnitureReconciliationRequest(
      UUID warehouseId,
      String expectedSnapshotSha256,
      String reviewSha256,
      List<FurnitureReconciliationItem> items) {}

  record FurnitureReconciliationItem(
      UUID equipmentId,
      long catalogVersion,
      long stockQuantity,
      List<FurnitureReconciliationCabin> cabins) {}

  record FurnitureReconciliationCabin(UUID assetId, long quantity) {}

  record InventoryLossDispositionRequest(
      UUID inventorySessionId,
      UUID findingId,
      UUID warehouseId,
      UUID equipmentId,
      String equipmentName,
      long expectedAssetVersion,
      long quantity,
      long expectedSourceBalanceVersion,
      String reason,
      String evidenceLink) {}

  record InventoryLossDisposition(
      UUID id,
      UUID inventorySessionId,
      UUID findingId,
      UUID warehouseId,
      UUID assetId,
      String disposition,
      String state) {}

  record FrozenPlan(
      UUID warehouseId,
      UUID inventoryId,
      UUID findingId,
      long sourceRevision,
      JsonNode snapshot,
      String fingerprint) {}

  record RepairUpsert(UUID repairId, JsonNode response) {}
}
