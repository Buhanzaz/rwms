package dev.buhanzaz.rwms.logistics.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Schedules bounded, fenced recovery for committed acceptance and estimate operations. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "rwms.logistics.return-completion",
    name = "relay-enabled",
    havingValue = "true")
class ReturnCompletionRelay {
  private static final List<String> OPERATION_TYPES =
      List.of(
          LogisticsDocumentService.RETURN_MEDIA_VALIDATE,
          LogisticsDocumentService.RETURN_ASSET_SETTLE_FREE,
          LogisticsDocumentService.RETURN_ASSET_SETTLE_ESTIMATE,
          LogisticsDocumentService.RETURN_MAINTENANCE_ESTIMATE_SOURCE_UPSERT,
          LogisticsDocumentService.RETURN_ASSET_ADDITIONAL_EQUIPMENT_RECEIVE,
          LogisticsDocumentService.RETURN_ASSET_LEASE_RELEASE);

  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsExternalAttemptRelayExecutor relayExecutor;
  private final ReturnCompletionProcessor processor;

  @Scheduled(
      fixedDelayString = "${rwms.logistics.return-completion.relay-delay:1s}",
      initialDelayString = "${rwms.logistics.return-completion.relay-initial-delay:1s}",
      scheduler = "logisticsExternalAttemptTriggerScheduler")
  void relayDueAttempts() {
    LogisticsExternalAttemptClaimService.Owner owner =
        LogisticsExternalAttemptClaimService.Owner.RETURN_COMPLETION;
    int permits = relayExecutor.availablePermits(owner);
    for (int index = 0; index < permits; index++) {
      var claim = claims.claimNext(owner, OPERATION_TYPES);
      if (claim.isEmpty()) return;
      if (!relayExecutor.submit(owner, () -> processor.process(claim.get()))) {
        claims.defer(claim.get());
      }
    }
  }
}
