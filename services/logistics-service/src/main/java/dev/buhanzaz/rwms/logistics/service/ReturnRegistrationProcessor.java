package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Executes one persisted return-registration step at a time. Every mutating
 * asset call receives the external attempt's stable operation ID as its
 * idempotency key, so a timeout is retried as a replay rather than inferred.
 */
@Service
@RequiredArgsConstructor
public class ReturnRegistrationProcessor {
  private final ReturnRegistrationWorkflowStore store;
  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsDependencyGateway dependencies;

  /**
   * Executes exactly one already-claimed operation. Both local preparation and completion verify
   * the same immutable lease capability, while dependency I/O occurs with no transaction open.
   */
  public void process(LogisticsExternalAttemptClaimService.Claim claim) {
    try {
      Optional<ReturnRegistrationWorkflowStore.Work> work = store.workForClaim(claim);
      if (work.isEmpty()) {
        defer(claim);
        return;
      }
      process(claim, work.get());
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // A newer claim owns the row; it alone may complete or defer it.
    }
  }

  private void process(
      LogisticsExternalAttemptClaimService.Claim claim, ReturnRegistrationWorkflowStore.Work work) {
    try {
      switch (work.type()) {
        case WAREHOUSE_IDENTITY ->
            store.confirmWarehouse(
                claim, dependencies.readWarehouseIdentity(work.warehouseId()));
        case ASSET_SNAPSHOT ->
            store.confirmAssetSnapshot(
                claim, dependencies.readRentalItemSnapshot(work.assetId()));
        case ASSET_LEASE ->
            store.confirmLease(
                claim,
                work.rentalOrderId() == null
                    ? dependencies.acquireReturnLease(
                        work.operationId(),
                        work.assetId(),
                        work.expectedAssetVersion(),
                        work.documentId(),
                        work.lineId())
                    : dependencies.acquireReturnLease(
                        work.operationId(),
                        work.assetId(),
                        work.expectedAssetVersion(),
                        work.documentId(),
                        work.lineId(),
                        work.rentalOrderId()));
        case ASSET_RETURN_INTAKE ->
            store.confirmReturnIntake(
                claim,
                dependencies.applyReturnIntake(
                    work.operationId(),
                    work.assetId(),
                    work.expectedAssetVersion(),
                    work.leaseId(),
                    work.fencingToken(),
                    work.documentId(),
                    work.lineId()));
      }
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // A newer lease won while the remote call was in flight.
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
      // The expiry or a newer claimant owns the next decision.
    }
  }

  private void recordFailure(
      LogisticsExternalAttemptClaimService.Claim claim, LogisticsDependencyException exception) {
    try {
      store.recordFailure(claim, exception);
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // Never overwrite the outcome of a newer fenced lease.
    }
  }
}
