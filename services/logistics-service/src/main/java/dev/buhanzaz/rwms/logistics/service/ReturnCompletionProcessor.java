package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Executes one durable return-completion attempt at a time outside the DB transaction. */
@Service
@RequiredArgsConstructor
public class ReturnCompletionProcessor {
  private final ReturnCompletionWorkflowStore store;
  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsDependencyGateway dependencies;

  /**
   * Executes exactly one claimed completion operation after a short local preflight transaction.
   * It deliberately returns after one dependency call so other owners retain fair worker access.
   */
  public void process(LogisticsExternalAttemptClaimService.Claim claim) {
    try {
      Optional<ReturnCompletionWorkflowStore.Work> work = store.workForClaim(claim);
      if (work.isEmpty()) {
        defer(claim);
        return;
      }
      process(claim, work.get());
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // A current claimant, not this worker, owns the result.
    }
  }

  private void process(
      LogisticsExternalAttemptClaimService.Claim claim, ReturnCompletionWorkflowStore.Work work) {
    try {
      switch (work.type()) {
        case MEDIA ->
            store.confirmMedia(
                claim,
                dependencies.validateMediaReferences(
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN,
                    work.documentId(),
                    work.lineId(),
                    work.warehouseId(),
                    work.references()));
        case SETTLEMENT ->
            store.confirmSettlement(
                claim,
                dependencies.settleReturn(
                    work.operationId(),
                    work.assetId(),
                    work.expectedAssetVersion(),
                    work.leaseId(),
                    work.fencingToken(),
                    work.documentId(),
                    work.lineId(),
                    work.estimate()),
                work.estimate());
        case MAINTENANCE ->
            store.confirmMaintenance(
                claim,
                dependencies.upsertReturnEstimateSource(
                    work.documentId(),
                    work.lineId(),
                    work.warehouseId(),
                    work.assetId(),
                    work.expectedAssetVersion(),
                    work.dispatchDate(),
                    work.arrivedAt(),
                    work.references()));
        case RETURN_EQUIPMENT ->
            store.confirmAdditionalEquipmentReceipt(
                claim,
                dependencies.receiveReturnEquipment(
                    work.operationId(),
                    work.documentId(),
                    work.lineId(),
                    work.warehouseId(),
                    work.returnEquipment()));
        case LEASE_RELEASE ->
            store.confirmLeaseRelease(
                claim,
                dependencies.releaseOperationLease(
                    work.operationId(),
                    work.leaseId(),
                    work.expectedLeaseVersion(),
                    work.fencingToken(),
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN,
                    work.documentId(),
                    work.lineId()));
      }
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // A newer lease may have completed while the dependency call was in flight.
    } catch (LogisticsDependencyException exception) {
      recordFailure(claim, exception);
    } catch (RuntimeException exception) {
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
      // Expiry or a newer claimant determines the next attempt.
    }
  }

  private void recordFailure(
      LogisticsExternalAttemptClaimService.Claim claim, LogisticsDependencyException exception) {
    try {
      store.recordFailure(claim, exception);
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // A stale worker must not replace another worker's terminal result.
    }
  }
}
