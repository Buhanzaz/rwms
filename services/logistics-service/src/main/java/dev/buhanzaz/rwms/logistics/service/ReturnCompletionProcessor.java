package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Executes one durable return-completion attempt at a time outside the DB transaction. */
@Service
@RequiredArgsConstructor
public class ReturnCompletionProcessor {
  private static final int MAX_STEPS_PER_DRAIN = 500;

  private final ReturnCompletionWorkflowStore store;
  private final LogisticsDependencyGateway dependencies;

  public int processUntilIdle(UUID documentId) {
    if (documentId == null) throw new IllegalArgumentException("documentId is required");
    int processed = 0;
    while (processed < MAX_STEPS_PER_DRAIN) {
      Optional<ReturnCompletionWorkflowStore.Work> work = store.nextWork(documentId);
      if (work.isEmpty()) return processed;
      process(work.get());
      processed++;
    }
    throw new IllegalStateException("Return completion did not reach a stable local state");
  }

  private void process(ReturnCompletionWorkflowStore.Work work) {
    try {
      switch (work.type()) {
        case MEDIA ->
            store.confirmMedia(
                work.operationId(),
                dependencies.validateMediaReferences(
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN,
                    work.documentId(),
                    work.lineId(),
                    work.warehouseId(),
                    work.references()));
        case SETTLEMENT ->
            store.confirmSettlement(
                work.operationId(),
                dependencies.settleReturn(
                    work.operationId(),
                    work.assetId(),
                    work.expectedAssetVersion(),
                    work.leaseId(),
                    work.fencingToken(),
                    work.documentId(),
                    work.lineId(),
                    work.shortage()),
                work.shortage());
        case MAINTENANCE ->
            store.confirmMaintenance(
                work.operationId(),
                dependencies.upsertReturnShortage(
                    work.documentId(),
                    work.lineId(),
                    work.warehouseId(),
                    work.assetId(),
                    work.expectedAssetVersion(),
                    work.dispatchDate(),
                    work.references(),
                    work.shortages()));
        case RETURN_EQUIPMENT ->
            store.confirmAdditionalEquipmentReceipt(
                work.operationId(),
                dependencies.receiveReturnEquipment(
                    work.operationId(),
                    work.documentId(),
                    work.lineId(),
                    work.warehouseId(),
                    work.returnEquipment()));
        case LEASE_RELEASE ->
            store.confirmLeaseRelease(
                work.operationId(),
                dependencies.releaseOperationLease(
                    work.operationId(),
                    work.leaseId(),
                    work.expectedLeaseVersion(),
                    work.fencingToken(),
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN,
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
