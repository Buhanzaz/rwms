package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Claims durable reconciliation work and dispatches it without holding a local transaction remotely. */
@Service
public class MaintenanceReconciliationUseCases {
  private static final Duration RECONCILIATION_CLAIM_LEASE = Duration.ofMinutes(2);

  private final MaintenanceReconciliationStore reconciliations;
  private final MaintenanceDependencyGateway dependencies;
  private final TransactionTemplate transactions;
  private final MaintenanceReconciliationSupport reconciliationSupport;
  private final MaintenanceAssetReconciliationUseCases assetReconciliations;
  private final MaintenanceTaskReconciliationUseCases taskReconciliations;
  private final MaintenanceRepairLifecycleReconciliationUseCases lifecycleReconciliations;

  public MaintenanceReconciliationUseCases(
      MaintenanceReconciliationStore reconciliations,
      MaintenanceDependencyGateway dependencies,
      PlatformTransactionManager transactionManager,
      MaintenanceReconciliationSupport reconciliationSupport,
      MaintenanceAssetReconciliationUseCases assetReconciliations,
      MaintenanceTaskReconciliationUseCases taskReconciliations,
      MaintenanceRepairLifecycleReconciliationUseCases lifecycleReconciliations) {
    this.reconciliations = reconciliations;
    this.dependencies = dependencies;
    this.transactions = new TransactionTemplate(transactionManager);
    this.reconciliationSupport = reconciliationSupport;
    this.assetReconciliations = assetReconciliations;
    this.taskReconciliations = taskReconciliations;
    this.lifecycleReconciliations = lifecycleReconciliations;
  }

  public boolean reconcileOneTask() {
    Optional<MaintenanceReconciliationStore.WorkItem> candidate =
        reconciliations.claimNextDue(RECONCILIATION_CLAIM_LEASE);
    if (candidate.isEmpty()) {
      Boolean enqueued = transactions.execute(status -> reconciliationSupport.enqueueDueLeaseRenewal());
      return Boolean.TRUE.equals(enqueued);
    }
    MaintenanceReconciliationStore.WorkItem work = candidate.orElseThrow();
    try {
      reconcileClaimedTask(work);
      return true;
    } catch (RuntimeException exception) {
      recordClaimedTaskFailure(work, exception);
      return true;
    }
  }

  public boolean reconcileOneMediaOwnerProof() {
    Optional<MaintenanceReconciliationStore.WorkItem> candidate =
        reconciliations.claimNextDueMedia(RECONCILIATION_CLAIM_LEASE);
    if (candidate.isEmpty()) return false;
    MaintenanceReconciliationStore.WorkItem work = candidate.orElseThrow();
    try {
      // The durable claim committed above; no local transaction or row lock is held while
      // media-service is called.
      MaintenanceDependencyGateway.MediaOwnerProof proof = mediaProof(work);
      MaintenanceDependencyGateway.MediaOwnerProof response =
          dependencies.upsertMediaOwnerProof(proof);
      if (!proof.equals(response)) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Media-service returned mismatched owner proof truth");
      }
      transactions.executeWithoutResult(status -> reconciliations.confirmed(work, response));
      return true;
    } catch (RuntimeException exception) {
      try {
        transactions.executeWithoutResult(status -> reconciliations.failed(work, exception));
      } catch (RuntimeException staleClaim) {
        if (!MaintenanceReconciliationStore.isStaleClaim(staleClaim)) throw staleClaim;
      }
      return true;
    }
  }

  private void reconcileClaimedTask(MaintenanceReconciliationStore.WorkItem work) {
    switch (work.operation()) {
      case "QUEUE_REPAIR" -> lifecycleReconciliations.reconcileQueuedRepairClaim(work);
      case "MATERIALIZE_FURNITURE_CUSTODY" -> assetReconciliations.reconcileFurnitureCustodyClaim(work);
      case "COMPLETE_EMPTY_ESTIMATE" -> assetReconciliations.reconcileEmptyEstimateClaim(work);
      case "REGISTER_TASK" -> taskReconciliations.reconcileTaskClaim(work, false);
      case "UPDATE_TASK" -> taskReconciliations.reconcileTaskClaim(work, true);
      case "CREATE_DRIVER_TASK" -> taskReconciliations.reconcileDriverLogisticsTaskClaim(work);
      case "SYNC_REPAIR_COMPLEXITY_STATUS" ->
          lifecycleReconciliations.reconcileRepairComplexityStatusClaim(work);
      case "APPLY_CHARACTERISTIC" -> assetReconciliations.reconcileAcceptedCharacteristicClaim(work);
      case "REGISTER_CATALOG_POSITION" ->
          taskReconciliations.reconcileCatalogPositionRegistrationClaim(work);
      case "DELETE_CATALOG_POSITION" ->
          taskReconciliations.reconcileCatalogPositionDeletionClaim(work);
      case "EMPTY_REPAIR_TO_FREE", "ACCEPT_TO_FREE", "WRITE_OFF", "PENDING_ACCEPTANCE" ->
          lifecycleReconciliations.reconcileAssetTransitionClaim(work);
      case "RENEW_LEASE" -> lifecycleReconciliations.reconcileLeaseRenewalClaim(work);
      default -> throw new IllegalStateException(
          "Unsupported maintenance reconciliation operation " + work.operation());
    }
  }

  private void recordClaimedTaskFailure(
      MaintenanceReconciliationStore.WorkItem work, RuntimeException exception) {
    try {
      transactions.executeWithoutResult(
          status -> {
            boolean quarantined = reconciliations.failed(work, exception);
            reconciliationSupport.recordReconciliationFailure(work, quarantined);
          });
    } catch (RuntimeException staleClaim) {
      // The claim may have expired while a remote dependency was slow. A newer worker owns
      // the durable outcome; never turn that harmless race into another failed attempt.
      if (!MaintenanceReconciliationStore.isStaleClaim(staleClaim)) throw staleClaim;
    }
  }

  private static MaintenanceDependencyGateway.MediaOwnerProof mediaProof(
      MaintenanceReconciliationStore.WorkItem work) {
    if (!"MEDIA".equals(work.dependency())
        || !"UPSERT_MEDIA_OWNER_PROOF".equals(work.operation())
        || work.mediaOwnerType() == null
        || work.mediaOwnerId() == null
        || work.mediaWarehouseId() == null
        || work.mediaOwnerRevision() == null
        || work.mediaAggregateVersion() == null
        || work.mediaProofEventId() == null
        || work.mediaActive() == null) {
      throw new IllegalStateException("Stored media owner proof is incomplete");
    }
    return new MaintenanceDependencyGateway.MediaOwnerProof(
        work.mediaOwnerType(),
        work.mediaOwnerId(),
        work.mediaWarehouseId(),
        work.mediaOwnerRevision(),
        work.mediaAggregateVersion(),
        work.mediaProofEventId(),
        work.mediaActive());
  }
}
