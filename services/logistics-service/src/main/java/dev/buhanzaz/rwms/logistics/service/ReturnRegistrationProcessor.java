package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.util.Optional;
import java.util.UUID;
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
  private static final int MAX_STEPS_PER_DRAIN = 500;

  private final ReturnRegistrationWorkflowStore store;
  private final LogisticsDependencyGateway dependencies;

  public int processUntilIdle(UUID documentId) {
    if (documentId == null) throw new IllegalArgumentException("documentId is required");
    int processed = 0;
    while (processed < MAX_STEPS_PER_DRAIN) {
      Optional<ReturnRegistrationWorkflowStore.Work> work = store.nextWork(documentId);
      if (work.isEmpty()) return processed;
      process(work.get());
      processed++;
    }
    throw new IllegalStateException("Return registration did not reach a stable local state");
  }

  private void process(ReturnRegistrationWorkflowStore.Work work) {
    try {
      switch (work.type()) {
        case WAREHOUSE_IDENTITY ->
            store.confirmWarehouse(
                work.operationId(), dependencies.readWarehouseIdentity(work.warehouseId()));
        case ASSET_SNAPSHOT ->
            store.confirmAssetSnapshot(
                work.operationId(), dependencies.readRentalItemSnapshot(work.assetId()));
        case ASSET_LEASE ->
            store.confirmLease(
                work.operationId(),
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
                work.operationId(),
                dependencies.applyReturnIntake(
                    work.operationId(),
                    work.assetId(),
                    work.expectedAssetVersion(),
                    work.leaseId(),
                    work.fencingToken(),
                    work.documentId(),
                    work.lineId()));
      }
    } catch (LogisticsDependencyException exception) {
      store.recordFailure(work.operationId(), exception);
    } catch (RuntimeException exception) {
      store.recordFailure(
          work.operationId(),
          new LogisticsDependencyException(
              LogisticsDependencyException.FailureKind.TRANSIENT,
              "Logistics dependency outcome is unknown",
              exception));
    }
  }
}
