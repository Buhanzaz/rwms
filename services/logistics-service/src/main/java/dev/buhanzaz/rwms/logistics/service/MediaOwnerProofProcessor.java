package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Delivers one committed, idempotent media-owner proof at a time. */
@Service
@RequiredArgsConstructor
public class MediaOwnerProofProcessor {
  private static final int MAX_STEPS_PER_DRAIN = 500;

  private final MediaOwnerProofWorkflowStore store;
  private final LogisticsDependencyGateway dependencies;

  public int processUntilIdle(UUID documentId) {
    if (documentId == null) throw new IllegalArgumentException("documentId is required");
    int processed = 0;
    while (processed < MAX_STEPS_PER_DRAIN) {
      Optional<MediaOwnerProofWorkflowStore.Work> work = store.nextWork(documentId);
      if (work.isEmpty()) return processed;
      process(work.get());
      processed++;
    }
    throw new IllegalStateException("Media owner proofs did not reach a stable local state");
  }

  private void process(MediaOwnerProofWorkflowStore.Work work) {
    try {
      store.confirm(
          work.operationId(),
          dependencies.upsertMediaOwnerProof(
              work.ownerType(),
              work.documentId(),
              work.lineId(),
              work.warehouseId(),
              work.ownerRevision(),
              work.aggregateVersion(),
              work.operationId(),
              work.active()));
    } catch (LogisticsDependencyException exception) {
      store.recordFailure(work.operationId(), exception);
    } catch (RuntimeException exception) {
      store.recordFailure(
          work.operationId(),
          new LogisticsDependencyException(
              LogisticsDependencyException.FailureKind.TRANSIENT,
              "Media owner-proof outcome is unknown",
              exception));
    }
  }
}
