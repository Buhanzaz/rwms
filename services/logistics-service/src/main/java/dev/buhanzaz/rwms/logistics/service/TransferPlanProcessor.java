package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Executes one fenced transfer-plan dependency effect outside its local database transaction. */
@Service
@RequiredArgsConstructor
public class TransferPlanProcessor {
  private static final Logger log = LoggerFactory.getLogger(TransferPlanProcessor.class);

  private final TransferPlanWorkflowStore store;
  private final LogisticsExternalAttemptClaimService claims;
  private final LogisticsDependencyGateway dependencies;

  /** Processes at most one claimed effect so owner permits remain fair across workflows. */
  public void process(LogisticsExternalAttemptClaimService.Claim claim) {
    try {
      var work = store.workForClaim(claim);
      if (work.isEmpty()) {
        defer(claim);
        return;
      }
      execute(claim, work.orElseThrow());
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // A later lease owns the durable decision.
    }
  }

  private void execute(
      LogisticsExternalAttemptClaimService.Claim claim, TransferPlanWorkflowStore.Work work) {
    try {
      if (work instanceof TransferPlanWorkflowStore.UnitReservationWork value) {
        store.confirmUnitReservation(
            claim,
            dependencies.reserveTransferUnits(
                value.operationId(),
                value.transferId(),
                value.sourceWarehouseId(),
                value.requests()));
        return;
      }
      if (work instanceof TransferPlanWorkflowStore.PrepareFurnitureReservationWork value) {
        prepareFurniture(claim, value);
        return;
      }
      if (work instanceof TransferPlanWorkflowStore.FurnitureReservationWork value) {
        reserveFurniture(claim, value);
        return;
      }
      if (work instanceof TransferPlanWorkflowStore.AssignmentCreateWork value) {
        store.confirmAssignment(
            claim,
            value.role(),
            dependencies.createWorkerOperationalAssignment(
                value.transferId(),
                value.workerId(),
                value.sourceWarehouseId(),
                value.destinationWarehouseId(),
                value.mode(),
                value.travelStartsAt(),
                value.effectiveFrom(),
                value.effectiveUntil()));
        return;
      }
      if (work instanceof TransferPlanWorkflowStore.DriverTaskPlanWork value) {
        planDriverTask(claim, value);
        return;
      }
      if (work instanceof TransferPlanWorkflowStore.UnitReleaseWork value) {
        store.confirmUnitRelease(
            claim,
            dependencies.releaseTransferUnits(
                value.operationId(), value.transferId(), value.requests()));
        return;
      }
      if (work instanceof TransferPlanWorkflowStore.FurnitureReleaseWork value) {
        store.confirmFurnitureRelease(
            claim,
            value.position(),
            dependencies.releaseEquipmentMovementReservation(
                value.operationId(),
                value.reservationId(),
                value.expectedReservationVersion(),
                value.transferId(),
                value.lineId()));
        return;
      }
      if (work instanceof TransferPlanWorkflowStore.FurnitureExecuteWork value) {
        store.confirmFurnitureExecution(
            claim,
            dependencies.executeEquipmentMovement(
                value.operationId(), value.transferId(), value.requests()));
        return;
      }
      if (work instanceof TransferPlanWorkflowStore.AssignmentTransitionWork value) {
        store.confirmAssignmentTransition(
            claim,
            dependencies.transitionWorkerOperationalAssignment(
                value.assignmentId(), value.expectedVersion(), value.targetStatus()));
        return;
      }
      throw new IllegalStateException("Unsupported transfer-plan work item");
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // Never record a response through an obsolete lease capability.
    } catch (LogisticsDependencyException exception) {
      recordFailure(claim, exception);
    } catch (LogisticsConflictException exception) {
      recordFailure(
          claim,
          new LogisticsDependencyException(
              LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
              exception.getMessage(),
              exception));
    } catch (RuntimeException exception) {
      log.warn("Transfer-plan effect {} produced an unexpected error", work.operationId(), exception);
      recordFailure(
          claim,
          new LogisticsDependencyException(
              LogisticsDependencyException.FailureKind.TRANSIENT,
              "Transfer-plan dependency outcome is unknown",
              exception));
    }
  }

  private void prepareFurniture(
      LogisticsExternalAttemptClaimService.Claim claim,
      TransferPlanWorkflowStore.PrepareFurnitureReservationWork value) {
    LogisticsDependencyGateway.EquipmentWarehouseAvailability availability =
        dependencies.readLogisticsEquipmentAvailability(value.sourceWarehouseId()).stream()
            .filter(item -> item.equipmentId().equals(value.equipmentId()))
            .filter(LogisticsDependencyGateway.EquipmentWarehouseAvailability::active)
            .findFirst()
            .orElseThrow(
                () ->
                    new LogisticsConflictException(
                        "Позиция мебели недоступна на складе отправления"));
    LogisticsDependencyGateway.EquipmentBalanceAvailability balance =
        availability.balances().stream()
            .filter(item -> item.warehouseId().equals(value.sourceWarehouseId()))
            .filter(item -> item.rentalItemId() == null)
            .filter(item -> "STOCK".equals(item.locationKind()))
            .filter(LogisticsDependencyGateway.EquipmentBalanceAvailability::allocatable)
            .filter(item -> item.availableStock() >= value.quantity())
            .sorted(Comparator.comparing(item -> item.balanceId().toString()))
            .findFirst()
            .orElseThrow(
                () ->
                    new LogisticsConflictException(
                        "Недостаточно свободной мебели на складе отправления"));
    store.freezeFurnitureSource(claim, value.position(), balance);
    TransferPlanWorkflowStore.Work frozen =
        store.workForClaim(claim).orElseThrow(
            () -> new IllegalStateException("Frozen furniture reservation work is missing"));
    if (!(frozen instanceof TransferPlanWorkflowStore.FurnitureReservationWork reservation)) {
      throw new IllegalStateException("Furniture source did not freeze deterministically");
    }
    reserveFurniture(claim, reservation);
  }

  private void reserveFurniture(
      LogisticsExternalAttemptClaimService.Claim claim,
      TransferPlanWorkflowStore.FurnitureReservationWork value) {
    store.confirmFurnitureReservation(
        claim,
        value.position(),
        dependencies.acquireEquipmentMovementReservation(
            value.operationId(),
            value.transferId(),
            value.lineId(),
            value.equipmentId(),
            value.sourceWarehouseId(),
            null,
            "STOCK",
            value.expectedSourceBalanceVersion(),
            value.quantity(),
            value.reservedUntil(),
            LogisticsDependencyGateway.EquipmentMovementPurpose.TRANSFER_REBALANCE));
  }

  private void planDriverTask(
      LogisticsExternalAttemptClaimService.Claim claim,
      TransferPlanWorkflowStore.DriverTaskPlanWork value) {
    if (value.tripDriverId() != null) {
      LogisticsDependencyGateway.WarehouseDriverIdentity driver =
          dependencies
              .listWarehouseDrivers(
                  value.sourceWarehouseId(), value.plannedDepartureAt(), false)
              .stream()
              .filter(item -> item.workerId().equals(value.tripDriverId()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new LogisticsConflictException(
                          "Назначенный водитель недоступен к началу рейса"));
      store.assignTripDriver(value.transferId(), driver);
    }
    store.planDriverTask(value.transferId());
    store.confirmDriverTaskPlan(claim);
  }

  private void defer(LogisticsExternalAttemptClaimService.Claim claim) {
    try {
      claims.defer(claim);
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // The subsequent owner controls recovery.
    }
  }

  private void recordFailure(
      LogisticsExternalAttemptClaimService.Claim claim, LogisticsDependencyException exception) {
    try {
      store.recordFailure(claim, exception);
    } catch (LogisticsExternalAttemptClaimService.StaleClaimException ignored) {
      // Never write retry state through an obsolete lease capability.
    }
  }
}
