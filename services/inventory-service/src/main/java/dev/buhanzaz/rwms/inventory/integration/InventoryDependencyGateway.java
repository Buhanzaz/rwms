package dev.buhanzaz.rwms.inventory.integration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public interface InventoryDependencyGateway {
  WarehouseMetadata warehouse(UUID warehouseId);

  Capture createCapture(UUID idempotencyKey, CaptureRequest request);

  CapturePage readCapture(UUID captureId, String cursor, int size);

  void releaseCapture(UUID captureId);

  NumberResolution resolveNumber(UUID warehouseId, String number);

  Optional<LiveAssetSnapshot> currentAsset(UUID assetId);

  SourceAsset createSourceAsset(UUID idempotencyKey, JsonNode request);

  Validation validateAssets(List<UUID> assetIds);

  RepairSnapshots repairSnapshots(List<UUID> assetIds);

  FrozenPlan freezePlan(UUID idempotencyKey, JsonNode request);

  RepairUpsert upsertRepair(
      UUID inventoryId, UUID findingId, UUID idempotencyKey, JsonNode request);

  default boolean productionReady() {
    return true;
  }

  record WarehouseMetadata(UUID id, long version, boolean active, String timeZone) {}

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

  record FrozenPlan(
      UUID warehouseId,
      UUID inventoryId,
      UUID findingId,
      long sourceRevision,
      JsonNode snapshot,
      String fingerprint) {}

  record RepairUpsert(UUID repairId, JsonNode response) {}
}
