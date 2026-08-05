package dev.buhanzaz.rwms.logistics.equipment.service;

import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskOwnerType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Executes one persisted equipment-movement dependency effect at a time. */
@Service
@RequiredArgsConstructor
public class EquipmentMovementTaskProcessor {
  private static final int MAX_STEPS_PER_DRAIN = 1_000;
  private static final Logger log = LoggerFactory.getLogger(EquipmentMovementTaskProcessor.class);

  private final EquipmentMovementWorkflowStore store;
  private final LogisticsDependencyGateway dependencies;

  public int processUntilIdle(UUID taskId) {
    if (taskId == null) throw new IllegalArgumentException("taskId is required");
    int processed = 0;
    while (processed < MAX_STEPS_PER_DRAIN) {
      Optional<EquipmentMovementWorkflowStore.Work> next = store.nextWork(taskId);
      if (next.isEmpty()) return processed;
      execute(next.get());
      processed++;
    }
    throw new IllegalStateException("Equipment movement task did not reach a stable local state");
  }

  private void execute(EquipmentMovementWorkflowStore.Work work) {
    try {
      if (work instanceof EquipmentMovementWorkflowStore.ReserveWork reserve) {
        store.confirmReservation(
            reserve.taskId(),
            reserve.lineId(),
            dependencies.acquireEquipmentMovementReservation(
                reserve.lineId(),
                reserve.assetMovementOwnerId(),
                reserve.lineId(),
                reserve.equipmentId(),
                reserve.sourceWarehouseId(),
                reserve.sourceRentalItemId(),
                reserve.sourceLocationKind(),
                reserve.expectedSourceBalanceVersion(),
                reserve.quantity(),
                reserve.reservedUntil(),
                reservationPurpose(reserve.ownerType())));
        return;
      }
      if (work instanceof EquipmentMovementWorkflowStore.RegisterWork register) {
        store.confirmTaskRegistration(
            register.taskId(),
            dependencies.registerEquipmentMovementTask(
                register.warehouseId(),
                register.externalTaskId(),
                register.unitNumber(),
                register.plannedDurationMinutes(),
                register.deadlineAt(),
                register.operations()));
        return;
      }
      if (work instanceof EquipmentMovementWorkflowStore.StatusWork status) {
        store.confirmTaskStatus(
            status.taskId(), dependencies.readEquipmentMovementTask(status.externalTaskId()));
        return;
      }
      if (work instanceof EquipmentMovementWorkflowStore.ExecuteWork execute) {
        store.confirmExecution(
            execute.taskId(),
            dependencies.executeEquipmentMovement(
                execute.taskId(), execute.assetMovementOwnerId(), execute.lines()));
        return;
      }
      if (work instanceof EquipmentMovementWorkflowStore.CancelBoardTaskWork cancel) {
        store.confirmTaskCancellation(
            cancel.taskId(),
            dependencies.cancelEquipmentMovementTask(
                cancel.externalTaskId(), cancel.expectedTaskVersion()));
        return;
      }
      if (work instanceof EquipmentMovementWorkflowStore.ReleaseWork release) {
        store.confirmReservationRelease(
            release.taskId(),
            release.lineId(),
            dependencies.releaseEquipmentMovementReservation(
                derivedKey("release", release.lineId()),
                release.reservationId(),
                release.expectedReservationVersion(),
                release.assetMovementOwnerId(),
                release.lineId()));
        return;
      }
      throw new IllegalStateException("Unsupported equipment movement work item");
    } catch (LogisticsDependencyException exception) {
      store.recordFailure(taskId(work), exception);
    } catch (RuntimeException exception) {
      UUID taskId = taskId(work);
      log.warn("Equipment movement dependency task {} produced an unexpected local error", taskId, exception);
      store.recordFailure(
          taskId,
          new LogisticsDependencyException(
              LogisticsDependencyException.FailureKind.TRANSIENT,
              "Equipment movement dependency outcome is unknown",
              exception));
    }
  }

  private static UUID taskId(EquipmentMovementWorkflowStore.Work work) {
    return switch (work) {
      case EquipmentMovementWorkflowStore.ReserveWork value -> value.taskId();
      case EquipmentMovementWorkflowStore.RegisterWork value -> value.taskId();
      case EquipmentMovementWorkflowStore.StatusWork value -> value.taskId();
      case EquipmentMovementWorkflowStore.ExecuteWork value -> value.taskId();
      case EquipmentMovementWorkflowStore.CancelBoardTaskWork value -> value.taskId();
      case EquipmentMovementWorkflowStore.ReleaseWork value -> value.taskId();
    };
  }

  private static LogisticsDependencyGateway.EquipmentMovementPurpose reservationPurpose(
      EquipmentMovementTaskOwnerType ownerType) {
    if (ownerType == null) {
      throw new IllegalArgumentException("Equipment movement task owner type is required");
    }
    return switch (ownerType) {
      case USER_REQUEST -> LogisticsDependencyGateway.EquipmentMovementPurpose.ALLOCATABLE_REBALANCE;
      case MAINTENANCE_DISPOSITION ->
          LogisticsDependencyGateway.EquipmentMovementPurpose.MAINTENANCE_DISPOSITION;
    };
  }

  private static UUID derivedKey(String operation, UUID value) {
    return UUID.nameUUIDFromBytes((operation + ":" + value).getBytes(StandardCharsets.UTF_8));
  }
}
