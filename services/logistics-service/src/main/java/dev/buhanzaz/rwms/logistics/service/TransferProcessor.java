package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Executes persisted transfer attempts outside their local database transaction. */
@Service
@RequiredArgsConstructor
public class TransferProcessor {
  private static final int MAX_STEPS_PER_DRAIN = 1_000;
  private static final Logger log = LoggerFactory.getLogger(TransferProcessor.class);

  private final TransferWorkflowStore store;
  private final LogisticsDependencyGateway dependencies;

  public int processUntilIdle(UUID documentId) {
    if (documentId == null) throw new IllegalArgumentException("documentId is required");
    int processed = 0;
    while (processed < MAX_STEPS_PER_DRAIN) {
      Optional<TransferWorkflowStore.Work> work = store.nextWork(documentId);
      if (work.isEmpty()) return processed;
      process(work.get());
      processed++;
    }
    throw new IllegalStateException("Transfer workflow did not reach a stable local state");
  }

  private void process(TransferWorkflowStore.Work work) {
    try {
      switch (work.type()) {
        case WAREHOUSE ->
            store.confirmWarehouse(
                work.operationId(), dependencies.readWarehouseIdentity(work.warehouseId()));
        case SNAPSHOT ->
            store.confirmSnapshot(
                work.operationId(), dependencies.readRentalItemSnapshot(work.assetId()));
        case LEASE ->
            store.confirmLease(
                work.operationId(),
                dependencies.acquireOperationLease(
                    work.operationId(),
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER,
                    work.assetId(),
                    work.expectedAssetVersion(),
                    work.documentId(),
                    work.lineId()));
        case TASK_REGISTER ->
            store.confirmTaskRegistration(
                work.operationId(),
                dependencies.registerPreparationTask(
                    work.warehouseId(), work.externalTaskId(), 0, null));
        case TASK_CANCEL ->
            store.confirmTaskCancellation(
                work.operationId(),
                dependencies.cancelPreparationTask(
                    work.externalTaskId(), work.expectedLeaseVersion()));
        case MEDIA ->
            store.confirmMedia(
                work.operationId(),
                dependencies.validateMediaReferences(
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER,
                    work.documentId(),
                    work.lineId(),
                    work.warehouseId(),
                    work.references()));
        case EFFECT ->
            store.confirmFencedEffect(
                work.operationId(),
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
                    work.warehouseId()));
        case LEASE_RELEASE ->
            store.confirmLeaseRelease(
                work.operationId(),
                dependencies.releaseOperationLease(
                    work.operationId(),
                    work.leaseId(),
                    work.expectedLeaseVersion(),
                    work.fencingToken(),
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER,
                    work.documentId(),
                    work.lineId()));
      }
    } catch (LogisticsDependencyException exception) {
      store.recordFailure(work.operationId(), exception);
    } catch (RuntimeException exception) {
      log.warn("Transfer dependency attempt {} produced an unexpected local error", work.operationId(), exception);
      store.recordFailure(
          work.operationId(),
          new LogisticsDependencyException(
              LogisticsDependencyException.FailureKind.TRANSIENT,
              "Logistics dependency outcome is unknown",
              exception));
    }
  }
}
