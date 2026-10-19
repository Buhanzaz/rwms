package dev.buhanzaz.rwms.logistics.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules bounded, fenced transfer departure and arrival operations. Owner-proof transfer
 * operations are intentionally excluded because they belong to {@link MediaOwnerProofRelay}.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rwms.logistics.transfer", name = "relay-enabled", havingValue = "true")
class TransferRelay {
  private static final List<String> OPERATION_TYPES =
      List.of(
          LogisticsDocumentService.TRANSFER_ORIGIN_WAREHOUSE_IDENTITY,
          LogisticsDocumentService.TRANSFER_DESTINATION_WAREHOUSE_IDENTITY,
          LogisticsDocumentService.TRANSFER_ASSET_SNAPSHOT,
          LogisticsDocumentService.TRANSFER_MAINTENANCE_PREPARE_DEPARTURE,
          LogisticsDocumentService.TRANSFER_ASSET_LEASE_ACQUIRE,
          LogisticsDocumentService.TRANSFER_ASSET_DEPART,
          LogisticsDocumentService.TRANSFER_ARRIVAL_DESTINATION_WAREHOUSE_IDENTITY,
          LogisticsDocumentService.TRANSFER_MEDIA_VALIDATE,
          LogisticsDocumentService.TRANSFER_ASSET_ARRIVAL_SNAPSHOT,
          LogisticsDocumentService.TRANSFER_ASSET_ARRIVE,
          LogisticsDocumentService.TRANSFER_ASSET_LEASE_RELEASE,
          LogisticsDocumentService.TRANSFER_MAINTENANCE_COMPLETE_ARRIVAL);

  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsExternalAttemptRelayExecutor relayExecutor;
  private final TransferProcessor processor;

  @Scheduled(
      fixedDelayString = "${rwms.logistics.transfer.relay-delay:1s}",
      initialDelayString = "${rwms.logistics.transfer.relay-initial-delay:1s}",
      scheduler = "logisticsExternalAttemptTriggerScheduler")
  void relayDueAttempts() {
    LogisticsExternalAttemptClaimService.Owner owner = LogisticsExternalAttemptClaimService.Owner.TRANSFER;
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
