package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureOperation;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetNumberClaim;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceId;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceOperation;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetCaptureOperationRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetNumberClaimRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetSourceOperationRepository;
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

  public InventoryAssetBoundaryRegistrar(
      InventoryAssetCaptureOperationRepository captureOperations,
      InventoryAssetSourceOperationRepository sourceOperations,
      InventoryAssetNumberClaimRepository numberClaims) {
    this.captureOperations = captureOperations;
    this.sourceOperations = sourceOperations;
    this.numberClaims = numberClaims;
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
  public void claimNumber(String identityMatchKey, InventoryAssetSourceId sourceId) {
    if (!numberClaims.existsById(identityMatchKey)) {
      numberClaims.saveAndFlush(InventoryAssetNumberClaim.claim(identityMatchKey, sourceId));
    }
  }
}
