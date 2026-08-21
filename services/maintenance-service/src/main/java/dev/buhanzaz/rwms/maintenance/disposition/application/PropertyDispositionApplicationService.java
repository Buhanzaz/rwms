package dev.buhanzaz.rwms.maintenance.disposition.application;

import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.ApprovePropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateInventoryLossDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateInventoryCabinWriteOffRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreatePropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateResult;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.PropertyDispositionDecisionResponse;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.PropertyDispositionPage;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.RecoverPropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.RejectPropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.WriteOffRepairRequest;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionState;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public compatibility facade for maintenance-owned property write-off and loss decisions.
 *
 * <p>The facade preserves controller, processor, and reconciliation seams while focused
 * collaborators own creation, furniture materialization, review/recovery, read projection, and
 * local processing callbacks. Asset and logistics calls remain outside local lock transactions.
 */
@Service
public class PropertyDispositionApplicationService {
  private final PropertyDispositionCreationUseCases creation;
  private final PropertyDispositionFurnitureMaterialization furniture;
  private final PropertyDispositionReviewRecoveryUseCases reviewRecovery;
  private final PropertyDispositionReadProjection readProjection;
  private final PropertyDispositionProcessingCallbacks processingCallbacks;

  public PropertyDispositionApplicationService(
      PropertyDispositionCreationUseCases creation,
      PropertyDispositionFurnitureMaterialization furniture,
      PropertyDispositionReviewRecoveryUseCases reviewRecovery,
      PropertyDispositionReadProjection readProjection,
      PropertyDispositionProcessingCallbacks processingCallbacks) {
    this.creation = creation;
    this.furniture = furniture;
    this.reviewRecovery = reviewRecovery;
    this.readProjection = readProjection;
    this.processingCallbacks = processingCallbacks;
  }

  public CreateResult createManual(
      UUID subjectId, UUID idempotencyKey, CreatePropertyDispositionRequest request) {
    return creation.createManual(subjectId, idempotencyKey, request);
  }

  public CreateResult createRepairWriteOff(
      UUID subjectId,
      UUID idempotencyKey,
      UUID repairId,
      UUID warehouseId,
      WriteOffRepairRequest request) {
    return creation.createRepairWriteOff(subjectId, idempotencyKey, repairId, warehouseId, request);
  }

  public CreateResult createInventoryLoss(
      UUID idempotencyKey, CreateInventoryLossDispositionRequest request) {
    return creation.createInventoryLoss(idempotencyKey, request);
  }

  /** Creates or replays the pending write-off decision for one unresolved inventory cabin. */
  public CreateResult createInventoryCabinWriteOff(
      UUID idempotencyKey, CreateInventoryCabinWriteOffRequest request) {
    return creation.createInventoryCabinWriteOff(idempotencyKey, request);
  }

  /**
   * Creates one pending administrator decision per unresolved asset custody claim. Furniture is
   * not terminalized here: the existing approval -> PREPARE -> APPLY processor remains the only
   * path that can consume the claim.
   */
  public int materializeFurnitureCustody(
      MaintenanceRepair completedRepair,
      Map<UUID, String> equipmentNames,
      List<MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim> claims) {
    return furniture.materializeFurnitureCustody(completedRepair, equipmentNames, claims);
  }

  /**
   * Local-only half of {@link #materializeFurnitureCustody}. Reconciliation callers that have
   * already performed admission outside their remote phase may join their final CAS transaction
   * without reopening a warehouse-service call under local locks.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int materializeFurnitureCustodyLocally(
      MaintenanceRepair completedRepair,
      Map<UUID, String> equipmentNames,
      List<MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim> claims) {
    return furniture.materializeFurnitureCustodyLocally(completedRepair, equipmentNames, claims);
  }

  /**
   * Records furniture selected from a legacy cabin with no recorded contents as separately
   * approved loss decisions. No claim, balance fence, or asset-service effect is created.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int materializeUnaccountedFurnitureLocally(
      MaintenanceRepair completedRepair,
      Map<UUID, String> equipmentNames,
      Map<UUID, Long> equipmentQuantities) {
    return furniture.materializeUnaccountedFurnitureLocally(
        completedRepair, equipmentNames, equipmentQuantities);
  }

  @Transactional(readOnly = true)
  public PropertyDispositionDecisionResponse get(UUID decisionId, UUID warehouseId) {
    return readProjection.get(decisionId, warehouseId);
  }

  @Transactional(readOnly = true)
  public PropertyDispositionPage list(
      UUID warehouseId,
      PropertyDispositionKind kind,
      PropertyDispositionState state,
      int page,
      int size) {
    return readProjection.list(warehouseId, kind, state, page, size);
  }

  public PropertyDispositionDecisionResponse approve(
      UUID decisionId, UUID warehouseId, ApprovePropertyDispositionRequest request) {
    return reviewRecovery.approve(decisionId, warehouseId, request);
  }

  public PropertyDispositionDecisionResponse reject(
      UUID decisionId, UUID warehouseId, RejectPropertyDispositionRequest request) {
    return reviewRecovery.reject(decisionId, warehouseId, request);
  }

  public PropertyDispositionDecisionResponse recover(
      UUID decisionId, UUID warehouseId, RecoverPropertyDispositionRequest request) {
    return reviewRecovery.recover(decisionId, warehouseId, request);
  }

  /** Read model used only by the server-owned processor, never exposed through a browser route. */
  @Transactional(readOnly = true)
  public ProcessingView processingView(UUID decisionId) {
    PropertyDispositionReadProjection.ProcessingProjection projection =
        readProjection.processingProjection(decisionId);
    PropertyDispositionReadProjection.LeaseReleaseProjection release = projection.leaseRelease();
    LeaseReleaseCommand leaseRelease = release == null
        ? null
        : new LeaseReleaseCommand(
            release.leaseId(),
            release.expectedLeaseVersion(),
            release.fencingToken(),
            release.ownerType(),
            release.ownerId());
    return new ProcessingView(
        projection.decisionId(),
        projection.warehouseId(),
        projection.state(),
        projection.movementTaskId(),
        projection.requiresMovement(),
        projection.requiresAssetEffect(),
        projection.preparation(),
        projection.movementCommand(),
        leaseRelease);
  }

  @Transactional
  public void startMovement(UUID decisionId, UUID taskId) {
    processingCallbacks.startMovement(decisionId, taskId);
  }

  @Transactional
  public void completeMovement(UUID decisionId) {
    processingCallbacks.completeMovement(decisionId);
  }

  @Transactional
  public void startAssetEffect(UUID decisionId) {
    processingCallbacks.startAssetEffect(decisionId);
  }

  /**
   * Commits the confirmed asset effect and every repair-chain terminal state together. A remote
   * effect is never claimed effective until this transaction succeeds.
   */
  @Transactional
  public void markEffective(UUID decisionId, UUID effectId) {
    processingCallbacks.markEffective(decisionId, effectId);
  }

  /** Commits an approved unaccounted-loss decision without requesting an asset-service effect. */
  @Transactional
  public void markEffectiveWithoutAssetEffect(UUID decisionId) {
    processingCallbacks.markEffectiveWithoutAssetEffect(decisionId);
  }

  @Transactional
  public void quarantine(UUID decisionId, String failureCode, String failureDetail) {
    processingCallbacks.quarantine(decisionId, failureCode, failureDetail);
  }

  /** Finishes a confirmed root-lease release without invoking a remote service in this transaction. */
  @Transactional
  public void confirmLeaseReleased(UUID decisionId, UUID leaseId) {
    processingCallbacks.confirmLeaseReleased(decisionId, leaseId);
  }

  /** Server-only processor view kept on the facade for the existing processor seam. */
  public record ProcessingView(
      UUID decisionId,
      UUID warehouseId,
      PropertyDispositionState state,
      UUID movementTaskId,
      boolean requiresMovement,
      boolean requiresAssetEffect,
      MaintenanceDependencyGateway.PropertyDispositionPreparation preparation,
      MaintenanceDependencyGateway.PropertyEquipmentMovementCommand movementCommand,
      LeaseReleaseCommand leaseRelease) {}

  /** Fenced root-lease release instruction consumed only after the disposition is effective. */
  public record LeaseReleaseCommand(
      UUID leaseId,
      long expectedLeaseVersion,
      long fencingToken,
      String ownerType,
      UUID ownerId) {}
}
