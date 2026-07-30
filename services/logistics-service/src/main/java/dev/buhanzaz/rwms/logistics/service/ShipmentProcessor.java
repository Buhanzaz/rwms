package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Executes one persisted shipment dependency attempt at a time. */
@Service
@RequiredArgsConstructor
public class ShipmentProcessor {
  private static final int MAX_STEPS_PER_DRAIN = 1_000;
  private static final Logger log = LoggerFactory.getLogger(ShipmentProcessor.class);

  private final ShipmentWorkflowStore store;
  private final LogisticsDependencyGateway dependencies;

  public int processUntilIdle(UUID documentId) {
    if (documentId == null) throw new IllegalArgumentException("documentId is required");
    int processed = 0;
    while (processed < MAX_STEPS_PER_DRAIN) {
      Optional<ShipmentWorkflowStore.Work> work = store.nextWork(documentId);
      if (work.isEmpty()) return processed;
      process(work.get());
      processed++;
    }
    throw new IllegalStateException("Shipment workflow did not reach a stable local state");
  }

  private void process(ShipmentWorkflowStore.Work work) {
    try {
      switch (work.type()) {
        case SNAPSHOT ->
            store.confirmSnapshot(
                work.operationId(), dependencies.readRentalItemSnapshot(work.assetId()));
        case LEASE ->
            store.confirmLease(
                work.operationId(),
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
                work.operationId(),
                dependencies.acquireEquipmentHold(
                    work.operationId(),
                    work.equipmentId(),
                    work.warehouseId(),
                    work.documentId(),
                    work.lineId(),
                    work.quantity(),
                    work.expectedStockVersion()));
        case HOLD_COMMAND ->
            confirmHoldCommand(work);
        case EFFECT ->
            store.confirmShipmentEffect(
                work.operationId(),
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
                work.operationId(),
                dependencies.releaseOperationLease(
                    work.operationId(),
                    work.leaseId(),
                    work.expectedLeaseVersion(),
                    work.fencingToken(),
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT,
                    work.documentId(),
                    work.lineId()));
      }
    } catch (LogisticsDependencyException exception) {
      store.recordFailure(work.operationId(), exception);
    } catch (RuntimeException exception) {
      log.warn("Shipment dependency attempt {} produced an unexpected local error", work.operationId(), exception);
      store.recordFailure(
          work.operationId(),
          new LogisticsDependencyException(
              LogisticsDependencyException.FailureKind.TRANSIENT,
              "Logistics dependency outcome is unknown",
              exception));
    }
  }

  private void confirmHoldCommand(ShipmentWorkflowStore.Work work) {
    LogisticsDependencyGateway.EquipmentHold result =
        dependencies.commandEquipmentHold(
            work.operationId(),
            work.holdAction(),
            work.holdId(),
            work.expectedTaskVersion(),
            work.documentId(),
            work.lineId());
    if (work.holdAction() == LogisticsDependencyGateway.EquipmentHoldAction.COMMIT) {
      store.confirmHoldCommit(work.operationId(), result);
    } else {
      store.confirmHoldRelease(work.operationId(), result);
    }
  }
}
