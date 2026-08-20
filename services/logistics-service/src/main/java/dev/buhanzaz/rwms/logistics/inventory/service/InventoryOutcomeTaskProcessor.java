package dev.buhanzaz.rwms.logistics.inventory.service;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverBoardTask;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.RepairPlaceAllocation;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.RepairPlaceProjection;
import dev.buhanzaz.rwms.logistics.inventory.service.InventoryOutcomeTaskStore.GuardLeaseReleaseWork;
import dev.buhanzaz.rwms.logistics.inventory.service.InventoryOutcomeTaskStore.RemoteWork;
import dev.buhanzaz.rwms.logistics.inventory.service.InventoryOutcomeTaskStore.RemoteTaskWork;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Executes one source-owned task cancellation or asset-lease release outside a database
 * transaction. Task cancellation reads task-board before retry; lease release reuses a stable
 * dependency key.
 */
@Component
@RequiredArgsConstructor
public class InventoryOutcomeTaskProcessor {
  private static final String CANCELLATION_REASON =
      "Задание заменено итогами завершённой инвентаризации";

  private final InventoryOutcomeTaskStore store;
  private final LogisticsDependencyGateway dependencies;

  public void process(UUID actionId) {
    RemoteWork pending = store.begin(actionId);
    if (pending == null) return;
    if (pending instanceof GuardLeaseReleaseWork lease) {
      releaseGuardLease(lease);
      return;
    }
    RemoteTaskWork work = (RemoteTaskWork) pending;
    DriverBoardTask current = dependencies.readDriverTask(work.externalTaskId());
    validate(work, current);
    if (completed(current)) {
      store.preserveCompleted(actionId, current);
      return;
    }
    DriverBoardTask cancelled = current;
    if (!"CANCELLED".equals(current.status())) {
      try {
        cancelled =
            dependencies.cancelDriverTask(
                work.externalTaskId(), current.taskVersion(), CANCELLATION_REASON);
      } catch (LogisticsDependencyException exception) {
        if (exception.kind() != LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
          throw exception;
        }
        DriverBoardTask reconciled = dependencies.readDriverTask(work.externalTaskId());
        validate(work, reconciled);
        if (completed(reconciled)) {
          store.preserveCompleted(actionId, reconciled);
          return;
        }
        if (!"CANCELLED".equals(reconciled.status())) throw exception;
        cancelled = reconciled;
      }
    }
    validate(work, cancelled);
    if (!"CANCELLED".equals(cancelled.status())) {
      throw new LogisticsConflictException("Task-board did not cancel displaced logistics work");
    }
    releaseInboundRepairPlace(work);
    store.confirmCancelled(actionId, cancelled);
  }

  private void releaseGuardLease(GuardLeaseReleaseWork work) {
    store.confirmLeaseReleased(
        work.actionId(),
        dependencies.releaseOperationLease(
            work.idempotencyKey(),
            work.leaseId(),
            work.expectedLeaseVersion(),
            work.fencingToken(),
            work.ownerType(),
            work.documentId(),
            work.lineId()));
  }

  private void releaseInboundRepairPlace(RemoteTaskWork work) {
    if (work.driverTaskKind() != DriverTaskKind.DELIVER_TO_REPAIR
        || work.repairPlaceAllocationId() == null
        || work.repairPlaceAllocationVersion() == null) {
      return;
    }
    if (work.repairId() == null || work.cabinId() == null) {
      throw new LogisticsConflictException("Driver task has an incomplete repair-place checkpoint");
    }
    RepairPlaceProjection projection = dependencies.readRepairPlaces(work.warehouseId());
    if (projection == null
        || !work.warehouseId().equals(projection.warehouseId())
        || projection.allocations() == null) {
      throw new LogisticsConflictException("Maintenance returned a mismatched repair-place projection");
    }
    RepairPlaceAllocation allocation =
        projection.allocations().stream()
            .filter(value -> work.repairPlaceAllocationId().equals(value.id()))
            .findFirst()
            .orElseThrow(
                () ->
                    new LogisticsConflictException(
                        "Repair-place allocation disappeared during inventory cancellation"));
    validateAllocation(work, allocation);
    if ("RELEASED".equals(allocation.state())) return;
    if (!"RESERVED".equals(allocation.state())
        && !"READY_TO_RELEASE".equals(allocation.state())) {
      throw new LogisticsConflictException(
          "Occupied repair place cannot be released by logistics inventory cancellation");
    }
    RepairPlaceAllocation released =
        dependencies.transitionRepairPlace(
            repairPlaceReleaseKey(work.localTaskId()),
            work.warehouseId(),
            work.repairId(),
            allocation.version(),
            "release");
    validateAllocation(work, released);
    if (!"RELEASED".equals(released.state())) {
      throw new LogisticsConflictException("Maintenance did not release the repair place");
    }
  }

  private static void validate(RemoteTaskWork work, DriverBoardTask board) {
    if (board == null
        || !work.warehouseId().equals(board.warehouseId())
        || !work.externalTaskId().equals(board.externalTaskId())
        || !work.taskBoardTaskId().equals(board.taskId())
        || board.taskVersion() < 0
        || board.status() == null) {
      throw new LogisticsConflictException("Task-board returned a mismatched task snapshot");
    }
  }

  private static void validateAllocation(
      RemoteTaskWork work, RepairPlaceAllocation allocation) {
    if (allocation == null
        || !work.repairPlaceAllocationId().equals(allocation.id())
        || !work.warehouseId().equals(allocation.warehouseId())
        || !work.repairId().equals(allocation.repairId())
        || !work.cabinId().equals(allocation.rentalItemId())
        || allocation.version() < work.repairPlaceAllocationVersion()
        || allocation.state() == null) {
      throw new LogisticsConflictException("Maintenance returned a mismatched repair-place allocation");
    }
  }

  private static boolean completed(DriverBoardTask board) {
    return "DONE".equals(board.status()) || "DONE".equals(board.entryStatus());
  }

  private static UUID repairPlaceReleaseKey(UUID taskId) {
    return UUID.nameUUIDFromBytes(
        ("inventory-outcome:repair-place-release:" + taskId).getBytes(StandardCharsets.UTF_8));
  }
}
