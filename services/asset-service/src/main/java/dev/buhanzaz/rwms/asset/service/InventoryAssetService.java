package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory-facing asset boundary for frozen captures, safe current reads, furniture count truth,
 * and permanent source creates.
 *
 * <p>This facade preserves controller and inbox seams while keeping transaction annotations at the
 * original entry points. Cohesive collaborators own their respective persistence, mapping, lock,
 * replay, and recovery decisions; the facade does not reconstruct a dependency graph.
 */
@Service
public class InventoryAssetService {
  private final InventoryAssetCaptureService captureLifecycle;
  private final InventoryAssetProjectionService projections;
  private final InventoryFurnitureReconciliationService furniture;
  private final InventoryAssetSourceService sources;

  @Autowired
  public InventoryAssetService(
      InventoryAssetCaptureService captureLifecycle,
      InventoryAssetProjectionService projections,
      InventoryFurnitureReconciliationService furniture,
      InventoryAssetSourceService sources) {
    this.captureLifecycle = captureLifecycle;
    this.projections = projections;
    this.furniture = furniture;
    this.sources = sources;
  }

  @Transactional
  public InventoryCaptureResponse createCapture(InventoryCaptureRequest request) {
    return captureLifecycle.createCapture(request);
  }

  @Transactional(readOnly = true)
  public InventoryCapturePage capturePage(UUID captureId, String cursor, int size) {
    return captureLifecycle.capturePage(captureId, cursor, size);
  }

  @Transactional
  public void releaseCapture(UUID captureId) {
    captureLifecycle.releaseCapture(captureId);
  }

  @Transactional(readOnly = true)
  public InventoryNumberResolutionResponse resolveNumber(InventoryNumberResolutionRequest request) {
    return projections.resolveNumber(request);
  }

  /** Reads one current inventory-safe asset projection from one repeatable-read transaction. */
  public InventoryAssetCurrentSnapshot currentAssetSnapshot(UUID assetId) {
    return projections.currentAssetSnapshot(assetId);
  }

  @Transactional(readOnly = true)
  public InventoryValidationResponse validateAssets(InventoryValidationRequest request) {
    return projections.validateAssets(request);
  }

  public InventoryFurnitureSnapshot furnitureSnapshot(InventoryFurnitureSnapshotRequest request) {
    return furniture.furnitureSnapshot(request);
  }

  /**
   * Applies one reviewed absolute furniture count. The durable inventory identity is registered
   * before balance locks so a retry remains a replay even after ordinary idempotency retention.
   */
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public FurnitureReconciliationResult reconcileFurniture(
      UUID inventoryId,
      UUID idempotencyKey,
      InventoryFurnitureReconciliationRequest request) {
    return new FurnitureReconciliationResult(
        furniture.reconcile(inventoryId, idempotencyKey, request));
  }

  @Transactional
  public CreateResult<InventorySourceAssetResponse> createSourceAsset(
      InventorySourceAssetRequest request) {
    InventoryAssetSourceService.SourceAssetResult result = sources.createSourceAsset(request);
    return new CreateResult<>(result.response(), result.replayed());
  }

  @Scheduled(fixedDelayString = "${rwms.asset.inventory-capture-cleanup-delay:PT1M}")
  @Transactional
  public void expireOrphanCaptures() {
    captureLifecycle.expireCapturesNow();
  }

  public record CreateResult<T>(T response, boolean replayed) {}

  public record FurnitureReconciliationResult(boolean replayed) {}
}
