package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Executes one persisted shipment dependency attempt at a time. */
@Service
@RequiredArgsConstructor
public class ShipmentProcessor {
  private static final Logger log = LoggerFactory.getLogger(ShipmentProcessor.class);

  private final ShipmentWorkflowStore store;
  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsDependencyGateway dependencies;

  /**
   * Executes one exact shipment claim. The processor does not scan a document or retain a database
   * transaction while the target dependency is running.
   */
  public void process(LogisticsExternalAttemptClaimService.Claim claim) {
    try {
      Optional<ShipmentWorkflowStore.Work> work = store.workForClaim(claim);
      if (work.isEmpty()) {
        defer(claim);
        return;
      }
      process(claim, work.get());
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // The row is now controlled by a newer lease.
    }
  }

  private void process(
      LogisticsExternalAttemptClaimService.Claim claim, ShipmentWorkflowStore.Work work) {
    try {
      switch (work.type()) {
        case HISTORICAL_MAINTENANCE_CLOSE ->
            store.confirmHistoricalMaintenanceClose(
                claim,
                dependencies.closeHistoricalShipment(
                    work.operationId(),
                    work.documentId(),
                    work.warehouseId(),
                    work.assetId()));
        case SNAPSHOT ->
            store.confirmSnapshot(
                claim, dependencies.readRentalItemSnapshot(work.assetId()));
        case LEASE ->
            store.confirmLease(
                claim,
                work.rentalOrderId() == null
                    ? dependencies.acquireOperationLease(
                        work.operationId(),
                        LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT,
                        work.assetId(),
                        work.expectedAssetVersion(),
                        work.documentId(),
                        work.lineId())
                    : dependencies.acquireOperationLease(
                        work.operationId(),
                        LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT,
                        work.assetId(),
                        work.expectedAssetVersion(),
                        work.documentId(),
                        work.lineId(),
                        work.rentalOrderId()));
        case HOLD_ACQUIRE ->
            store.confirmHoldAcquire(
                claim,
                dependencies.acquireEquipmentHold(
                    work.operationId(),
                    work.equipmentId(),
                    work.warehouseId(),
                    work.documentId(),
                    work.lineId(),
                    work.quantity(),
                    work.expectedStockVersion()));
        case HOLD_COMMAND ->
            confirmHoldCommand(claim, work);
        case EFFECT ->
            store.confirmShipmentEffect(
                claim,
                dependencies.applyFencedEffect(
                    work.operationId(),
                    work.assetEffect(),
                    work.assetId(),
                    work.expectedAssetVersion(),
                    work.leaseId(),
                    work.fencingToken(),
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT,
                    work.documentId(),
                    work.lineId(),
                    work.warehouseId()));
        case LEASE_RELEASE ->
            store.confirmLeaseRelease(
                claim,
                dependencies.releaseOperationLease(
                    work.operationId(),
                    work.leaseId(),
                    work.expectedLeaseVersion(),
                    work.fencingToken(),
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT,
                    work.documentId(),
                    work.lineId()));
      }
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // Do not let an expired worker overwrite a newer lease's outcome.
    } catch (LogisticsDependencyException exception) {
      recordFailure(claim, exception);
    } catch (RuntimeException exception) {
      log.warn("Shipment dependency attempt {} produced an unexpected local error", work.operationId(), exception);
      recordFailure(
          claim,
          new LogisticsDependencyException(
              LogisticsDependencyException.FailureKind.TRANSIENT,
              "Logistics dependency outcome is unknown",
              exception));
    }
  }

  private void confirmHoldCommand(
      LogisticsExternalAttemptClaimService.Claim claim, ShipmentWorkflowStore.Work work) {
    LogisticsDependencyGateway.EquipmentHold result =
        dependencies.commandEquipmentHold(
            work.operationId(),
            work.holdAction(),
            work.holdId(),
            work.expectedTaskVersion(),
            work.documentId(),
            work.lineId());
    if (work.holdAction() == LogisticsDependencyGateway.EquipmentHoldAction.COMMIT) {
      store.confirmHoldCommit(claim, result);
    } else {
      store.confirmHoldRelease(claim, result);
    }
  }

  private void defer(LogisticsExternalAttemptClaimService.Claim claim) {
    try {
      claims.defer(claim);
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // The current owner decides whether the row stays deferred.
    }
  }

  private void recordFailure(
      LogisticsExternalAttemptClaimService.Claim claim, LogisticsDependencyException exception) {
    try {
      store.recordFailure(claim, exception);
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // The exact row version/fence prevents a stale failure from winning.
    }
  }
}
