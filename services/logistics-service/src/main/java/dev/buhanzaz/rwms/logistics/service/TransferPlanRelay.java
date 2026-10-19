package dev.buhanzaz.rwms.logistics.service;

import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.DRIVER_TASK_PLAN;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.FURNITURE_EXECUTE;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.REPOSITION_DRIVER_ACTIVE;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.REPOSITION_DRIVER_ASSIGN;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.REPOSITION_DRIVER_CANCEL;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.REPOSITION_DRIVER_TRANSIT;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.TRIP_DRIVER_ASSIGN;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.TRIP_DRIVER_CANCEL;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.TRIP_DRIVER_COMPLETE;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.TRIP_DRIVER_TRANSIT;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.UNIT_RELEASE;
import static dev.buhanzaz.rwms.logistics.service.TransferPlanWorkflowOperations.UNIT_RESERVE;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Schedules bounded transfer-plan reservation, compensation, and resource lifecycle effects. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "rwms.logistics.transfer",
    name = "relay-enabled",
    havingValue = "true")
class TransferPlanRelay {
  private static final List<String> EXACT_OPERATIONS =
      List.of(
          UNIT_RESERVE,
          DRIVER_TASK_PLAN,
          TRIP_DRIVER_ASSIGN,
          REPOSITION_DRIVER_ASSIGN,
          TRIP_DRIVER_TRANSIT,
          REPOSITION_DRIVER_TRANSIT,
          TRIP_DRIVER_COMPLETE,
          REPOSITION_DRIVER_ACTIVE,
          TRIP_DRIVER_CANCEL,
          REPOSITION_DRIVER_CANCEL,
          UNIT_RELEASE,
          FURNITURE_EXECUTE);

  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsExternalAttemptRelayExecutor relayExecutor;
  private final TransferPlanProcessor processor;

  /** Shares the existing transfer worker budget and claims one bounded effect per permit. */
  @Scheduled(
      fixedDelayString = "${rwms.logistics.transfer.relay-delay:1s}",
      initialDelayString = "${rwms.logistics.transfer.relay-initial-delay:1s}",
      scheduler = "logisticsExternalAttemptTriggerScheduler")
  void relayDueAttempts() {
    LogisticsExternalAttemptClaimService.Owner owner =
        LogisticsExternalAttemptClaimService.Owner.TRANSFER;
    int permits = relayExecutor.availablePermits(owner);
    for (int index = 0; index < permits; index++) {
      Optional<LogisticsExternalAttemptClaimService.Claim> claim = claims.claimNext(owner, EXACT_OPERATIONS);
      if (claim.isEmpty()) {
        claim =
            claims.claimNextByOperationPrefix(
                owner, TransferPlanWorkflowOperations.FURNITURE_RESERVE_PREFIX);
      }
      if (claim.isEmpty()) {
        claim =
            claims.claimNextByOperationPrefix(
                owner, TransferPlanWorkflowOperations.FURNITURE_RELEASE_PREFIX);
      }
      if (claim.isEmpty()) return;
      LogisticsExternalAttemptClaimService.Claim selected = claim.orElseThrow();
      if (!relayExecutor.submit(owner, () -> processor.process(selected))) {
        claims.defer(selected);
      }
    }
  }
}
