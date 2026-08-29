package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Delivers one committed, idempotent media-owner proof at a time. */
@Service
@RequiredArgsConstructor
public class MediaOwnerProofProcessor {
  private final MediaOwnerProofWorkflowStore store;
  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsDependencyGateway dependencies;

  /**
   * Delivers one claimed proof only after validating the current local owner state. A stale proof
   * is discarded rather than being allowed to overwrite a later owner revision.
   */
  public void process(LogisticsExternalAttemptClaimService.Claim claim) {
    try {
      Optional<MediaOwnerProofWorkflowStore.Work> work = store.workForClaim(claim);
      if (work.isEmpty()) {
        defer(claim);
        return;
      }
      process(claim, work.get());
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // A newer lease owns the outcome.
    }
  }

  private void process(
      LogisticsExternalAttemptClaimService.Claim claim, MediaOwnerProofWorkflowStore.Work work) {
    try {
      store.confirm(
          claim,
          dependencies.upsertMediaOwnerProof(
              work.ownerType(),
              work.documentId(),
              work.lineId(),
              work.warehouseId(),
              work.ownerRevision(),
              work.aggregateVersion(),
              work.operationId(),
              work.authorizedSubjectId(),
              work.active()));
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // The remote result belongs to an expired or superseded local lease.
    } catch (LogisticsDependencyException exception) {
      recordFailure(claim, exception);
    } catch (RuntimeException exception) {
      recordFailure(
          claim,
          new LogisticsDependencyException(
              LogisticsDependencyException.FailureKind.TRANSIENT,
              "Media owner-proof outcome is unknown",
              exception));
    }
  }

  private void defer(LogisticsExternalAttemptClaimService.Claim claim) {
    try {
      claims.defer(claim);
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // Another worker controls the next eligible time.
    }
  }

  private void recordFailure(
      LogisticsExternalAttemptClaimService.Claim claim, LogisticsDependencyException exception) {
    try {
      store.recordFailure(claim, exception);
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // A stale error must not overwrite the current owner's decision.
    }
  }
}
