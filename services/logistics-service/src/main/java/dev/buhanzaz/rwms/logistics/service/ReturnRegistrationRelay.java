package dev.buhanzaz.rwms.logistics.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules bounded, fenced return-registration recovery. It does not call a dependency itself:
 * each iteration claims one operation only when this owner has a worker permit to accept it.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "rwms.logistics.return-registration",
    name = "relay-enabled",
    havingValue = "true")
class ReturnRegistrationRelay {
  private static final List<String> OPERATION_TYPES =
      List.of(
          LogisticsDocumentService.RETURN_WAREHOUSE_IDENTITY,
          ReturnRegistrationWorkflowStore.RETURN_ASSET_SNAPSHOT,
          ReturnRegistrationWorkflowStore.RETURN_ASSET_LEASE_ACQUIRE,
          ReturnRegistrationWorkflowStore.RETURN_ASSET_INTAKE);

  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsExternalAttemptRelayExecutor relayExecutor;
  private final ReturnRegistrationProcessor processor;

  @Scheduled(
      fixedDelayString = "${rwms.logistics.return-registration.relay-delay:1s}",
      initialDelayString = "${rwms.logistics.return-registration.relay-initial-delay:1s}",
      scheduler = "logisticsExternalAttemptTriggerScheduler")
  void relayDueAttempts() {
    LogisticsExternalAttemptClaimService.Owner owner =
        LogisticsExternalAttemptClaimService.Owner.RETURN_REGISTRATION;
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
