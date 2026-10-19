package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Executes persisted transfer attempts outside their local database transaction. */
@Service
@RequiredArgsConstructor
public class TransferProcessor {
  private static final Logger log = LoggerFactory.getLogger(TransferProcessor.class);

  private final TransferWorkflowStore store;
  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsDependencyGateway dependencies;

  /**
   * Executes one exact transfer claim and yields to the bounded worker pool after that one remote
   * operation, preserving fair recovery across owners and replicas.
   */
  public void process(LogisticsExternalAttemptClaimService.Claim claim) {
    try {
      Optional<TransferWorkflowStore.Work> work = store.workForClaim(claim);
      if (work.isEmpty()) {
        defer(claim);
        return;
      }
      process(claim, work.get());
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // A newer lease owns the durable decision.
    }
  }

  private void process(
      LogisticsExternalAttemptClaimService.Claim claim, TransferWorkflowStore.Work work) {
    try {
      switch (work.type()) {
        case WAREHOUSE ->
            store.confirmWarehouse(
                claim, dependencies.readWarehouseIdentity(work.warehouseId()));
        case SNAPSHOT ->
            store.confirmSnapshot(
                claim, dependencies.readRentalItemSnapshot(work.assetId()));
        case LEASE ->
            store.confirmLease(
                claim,
                dependencies.acquireOperationLease(
                    work.operationId(),
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER,
                    work.assetId(),
                    work.expectedAssetVersion(),
                    work.documentId(),
                    work.lineId()));
        case MEDIA ->
            store.confirmMedia(
                claim,
                dependencies.validateMediaReferences(
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER,
                    work.documentId(),
                    work.lineId(),
                    work.warehouseId(),
                    work.references()));
        case EFFECT ->
            store.confirmFencedEffect(
                claim,
                dependencies.applyFencedEffect(
                    work.operationId(),
                    work.assetEffect(),
                    work.assetId(),
                    work.expectedAssetVersion(),
                    work.leaseId(),
                    work.fencingToken(),
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER,
                    work.documentId(),
                    work.lineId(),
                    work.warehouseId(),
                    work.transferAssetStatus()));
        case LEASE_RELEASE ->
            store.confirmLeaseRelease(
                claim,
                dependencies.releaseOperationLease(
                    work.operationId(),
                    work.leaseId(),
                    work.expectedLeaseVersion(),
                    work.fencingToken(),
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER,
                    work.documentId(),
                    work.lineId()));
        case MAINTENANCE_PREPARE ->
            store.confirmMaintenanceDeparture(
                claim,
                dependencies.prepareTransferDeparture(
                    work.operationId(),
                    work.documentId(),
                    work.lineId(),
                    work.assetId(),
                    work.sourceWarehouseId(),
                    work.warehouseId()));
        case MAINTENANCE_COMPLETE ->
            store.confirmMaintenanceArrival(
                claim,
                dependencies.completeTransferArrival(
                    work.operationId(),
                    work.documentId(),
                    work.lineId(),
                    work.assetId(),
                    work.rentalItemVersion(),
                    work.sourceWarehouseId(),
                    work.warehouseId(),
                    work.priority()));
      }
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // A response from a stale lease must never mutate the workflow.
    } catch (LogisticsDependencyException exception) {
      recordFailure(claim, exception);
    } catch (RuntimeException exception) {
      log.warn("Transfer dependency attempt {} produced an unexpected local error", work.operationId(), exception);
      recordFailure(
          claim,
          new LogisticsDependencyException(
              LogisticsDependencyException.FailureKind.TRANSIENT,
              "Logistics dependency outcome is unknown",
              exception));
    }
  }

  private void defer(LogisticsExternalAttemptClaimService.Claim claim) {
    try {
      claims.defer(claim);
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // The subsequent owner controls recovery.
    }
  }

  private void recordFailure(
      LogisticsExternalAttemptClaimService.Claim claim, LogisticsDependencyException exception) {
    try {
      store.recordFailure(claim, exception);
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // Never write retry state through an obsolete lease capability.
    }
  }
}
