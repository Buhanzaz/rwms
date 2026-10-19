package dev.buhanzaz.rwms.logistics.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Schedules bounded, fenced shipment recovery without occupying the trigger scheduler in HTTP. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rwms.logistics.shipment", name = "relay-enabled", havingValue = "true")
class ShipmentRelay {
  private static final String OPERATION_PREFIX = "SHIPMENT_";

  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsExternalAttemptRelayExecutor relayExecutor;
  private final ShipmentProcessor processor;

  @Scheduled(
      fixedDelayString = "${rwms.logistics.shipment.relay-delay:1s}",
      initialDelayString = "${rwms.logistics.shipment.relay-initial-delay:1s}",
      scheduler = "logisticsExternalAttemptTriggerScheduler")
  void relayDueAttempts() {
    LogisticsExternalAttemptClaimService.Owner owner = LogisticsExternalAttemptClaimService.Owner.SHIPMENT;
    int permits = relayExecutor.availablePermits(owner);
    for (int index = 0; index < permits; index++) {
      var claim = claims.claimNextByOperationPrefix(owner, OPERATION_PREFIX);
      if (claim.isEmpty()) return;
      if (!relayExecutor.submit(owner, () -> processor.process(claim.get()))) {
        claims.defer(claim.get());
      }
    }
  }
}
