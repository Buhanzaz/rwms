package dev.buhanzaz.rwms.logistics.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules bounded, fenced recovery for committed return and transfer owner proofs.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "rwms.logistics.owner-proof",
    name = "relay-enabled",
    havingValue = "true")
class MediaOwnerProofRelay {
  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsExternalAttemptRelayExecutor relayExecutor;
  private final MediaOwnerProofProcessor processor;

  @Scheduled(
      fixedDelayString = "${rwms.logistics.owner-proof.relay-delay:1s}",
      initialDelayString = "${rwms.logistics.owner-proof.relay-initial-delay:1s}",
      scheduler = "logisticsExternalAttemptTriggerScheduler")
  void relayDueAttempts() {
    LogisticsExternalAttemptClaimService.Owner owner =
        LogisticsExternalAttemptClaimService.Owner.MEDIA_OWNER_PROOF;
    int permits = relayExecutor.availablePermits(owner);
    for (int index = 0; index < permits; index++) {
      var claim = claims.claimNext(owner, MediaOwnerProofWorkflowStore.OPERATIONS);
      if (claim.isEmpty()) return;
      if (!relayExecutor.submit(owner, () -> processor.process(claim.get()))) {
        claims.defer(claim.get());
      }
    }
  }
}
