package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureOperation;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetNumberClaim;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceId;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceOperation;
import dev.buhanzaz.rwms.asset.domain.InventoryFurnitureReconciliation;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetCaptureOperationRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetNumberClaimRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetSourceOperationRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryFurnitureReconciliationRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates durable lock rows before the caller acquires a pessimistic JPA lock. */
@Service
public class InventoryAssetBoundaryRegistrar {
  private final InventoryAssetCaptureOperationRepository captureOperations;
  private final InventoryAssetSourceOperationRepository sourceOperations;
  private final InventoryAssetNumberClaimRepository numberClaims;
  private final InventoryFurnitureReconciliationRepository furnitureReconciliations;

  public InventoryAssetBoundaryRegistrar(
      InventoryAssetCaptureOperationRepository captureOperations,
      InventoryAssetSourceOperationRepository sourceOperations,
      InventoryAssetNumberClaimRepository numberClaims,
      InventoryFurnitureReconciliationRepository furnitureReconciliations) {
    this.captureOperations = captureOperations;
    this.sourceOperations = sourceOperations;
    this.numberClaims = numberClaims;
    this.furnitureReconciliations = furnitureReconciliations;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void registerCaptureOperation(
      UUID operationId, UUID warehouseId, String requestFingerprint) {
    if (!captureOperations.existsById(operationId)) {
      captureOperations.saveAndFlush(
          InventoryAssetCaptureOperation.register(operationId, warehouseId, requestFingerprint));
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void registerSourceOperation(InventoryAssetSourceId id, String requestFingerprint) {
    if (!sourceOperations.existsById(id)) {
      sourceOperations.saveAndFlush(InventoryAssetSourceOperation.register(id, requestFingerprint));
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void claimNumber(
      UUID warehouseId, String identityMatchKey, InventoryAssetSourceId sourceId) {
    if (numberClaims.existsByWarehouseIdAndIdentityMatchKey(warehouseId, identityMatchKey)) {
      return;
    }
    InventoryAssetNumberClaim existing = numberClaims
        .findBySourceForUpdate(sourceId.getInventoryId(), sourceId.getFindingId())
        .orElse(null);
    if (existing != null) {
      existing.bindWarehouse(warehouseId, identityMatchKey);
      numberClaims.saveAndFlush(existing);
      return;
    }
    numberClaims.saveAndFlush(
        InventoryAssetNumberClaim.claim(warehouseId, identityMatchKey, sourceId));
  }

  /** Registers an immutable inventory source before the outer reconciliation locks balances. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void registerFurnitureReconciliation(
      UUID inventoryId, String requestSha256, UUID idempotencyKey) {
    if (!furnitureReconciliations.existsById(inventoryId)) {
      furnitureReconciliations.saveAndFlush(
          InventoryFurnitureReconciliation.register(inventoryId, requestSha256, idempotencyKey));
    }
  }
}
