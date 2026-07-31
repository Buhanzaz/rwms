package dev.buhanzaz.rwms.logistics.driver.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DriverQueueSchedulerTest {
  private final UUID warehouseId = UUID.randomUUID();
  private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
  private final DriverLogisticsTaskRepository tasks =
      mock(DriverLogisticsTaskRepository.class);
  private final DriverTaskService taskService = mock(DriverTaskService.class);
  private final DriverTaskWorkflowStore store = mock(DriverTaskWorkflowStore.class);
  private final DriverTaskProcessor processor = mock(DriverTaskProcessor.class);
  private final LogisticsDependencyGateway dependencies =
      mock(LogisticsDependencyGateway.class);
  private final DriverQueueScheduler scheduler =
      new DriverQueueScheduler(tasks, taskService, store, processor, dependencies);

  @BeforeEach
  void configureWarehouseClockAndIdleCurrentLane() {
    when(dependencies.readWarehouseIdentity(warehouseId))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(
                warehouseId, 0, true, "UTC"));
    when(tasks.existsByWarehouseIdAndStateIn(eq(warehouseId), any())).thenReturn(false);
    when(tasks.findRecentByWarehouseAndState(warehouseId, DriverTaskState.COMPLETED))
        .thenReturn(List.of());
    when(dependencies.isWarehouseDriverQueueAvailable(warehouseId)).thenReturn(true);
  }

  @Test
  void warehouseWithoutDriverQueueIsSkippedWithoutReadingOrCreatingTasks() {
    when(dependencies.isWarehouseDriverQueueAvailable(warehouseId)).thenReturn(false);

    scheduler.reconcileAndPromote(warehouseId);

    verify(dependencies, never()).readRepairPlaces(warehouseId);
    verify(dependencies, never()).readDriverBoard(warehouseId);
    verify(dependencies, never()).setDriverTaskLane(any(), any(Long.class), any());
  }

  @Test
  void occupiedRepairPlacesKeepInboundDeliveryScheduled() {
    DriverLogisticsTask inbound = scheduledTask(DriverTaskKind.DELIVER_TO_REPAIR);
    LogisticsDependencyGateway.DriverBoardTask inboundBoard = boardTask(inbound, today, 0);
    configureBoard(List.of(inboundBoard));
    configureLocal(inbound);
    when(dependencies.readRepairPlaces(warehouseId)).thenReturn(repairPlaces(0));

    scheduler.reconcileAndPromote(warehouseId);

    verify(dependencies, never()).setDriverTaskLane(any(), any(Long.class), any());
    verify(dependencies, never())
        .transitionRepairPlace(any(), any(), any(), any(Long.class), any());
  }

  @Test
  void completedRemovalIsFollowedByEligibleInboundDelivery() {
    DriverLogisticsTask outbound = scheduledTask(DriverTaskKind.REMOVE_FROM_REPAIR);
    DriverLogisticsTask inbound = scheduledTask(DriverTaskKind.DELIVER_TO_REPAIR);
    LogisticsDependencyGateway.DriverBoardTask outboundBoard = boardTask(outbound, today, 0);
    LogisticsDependencyGateway.DriverBoardTask inboundBoard = boardTask(inbound, today, 1);
    configureBoard(List.of(outboundBoard, inboundBoard));
    configureLocal(outbound, inbound);
    configureLastCompletedKind(DriverTaskKind.REMOVE_FROM_REPAIR);
    LogisticsDependencyGateway.RepairPlaceProjection places = repairPlaces(1);
    LogisticsDependencyGateway.RepairPlaceAllocation reservation =
        allocation(inbound, "RESERVED", 0);
    when(dependencies.readRepairPlaces(warehouseId)).thenReturn(places);
    when(dependencies.transitionRepairPlace(
            any(), eq(warehouseId), eq(inbound.getRepairId()), eq(0L), eq("reserve")))
        .thenReturn(reservation);
    when(dependencies.setDriverTaskLane(
            inbound.getExternalTaskId(), inboundBoard.taskVersion(), "CURRENT"))
        .thenReturn(current(inboundBoard));

    scheduler.reconcileAndPromote(warehouseId);

    verify(dependencies)
        .setDriverTaskLane(
            inbound.getExternalTaskId(), inboundBoard.taskVersion(), "CURRENT");
    verify(dependencies, never())
        .setDriverTaskLane(
            outbound.getExternalTaskId(), outboundBoard.taskVersion(), "CURRENT");
    verify(store).confirmReservation(inbound.getId(), reservation);
  }

  @Test
  void completedInboundDeliveryIsFollowedByReadyRemoval() {
    DriverLogisticsTask inbound = scheduledTask(DriverTaskKind.DELIVER_TO_REPAIR);
    DriverLogisticsTask outbound = scheduledTask(DriverTaskKind.REMOVE_FROM_REPAIR);
    LogisticsDependencyGateway.DriverBoardTask inboundBoard = boardTask(inbound, today, 0);
    LogisticsDependencyGateway.DriverBoardTask outboundBoard = boardTask(outbound, today, 1);
    configureBoard(List.of(inboundBoard, outboundBoard));
    configureLocal(inbound, outbound);
    configureLastCompletedKind(DriverTaskKind.DELIVER_TO_REPAIR);
    when(dependencies.readRepairPlaces(warehouseId)).thenReturn(repairPlaces(0));
    when(dependencies.setDriverTaskLane(
            outbound.getExternalTaskId(), outboundBoard.taskVersion(), "CURRENT"))
        .thenReturn(current(outboundBoard));

    scheduler.reconcileAndPromote(warehouseId);

    verify(dependencies)
        .setDriverTaskLane(
            outbound.getExternalTaskId(), outboundBoard.taskVersion(), "CURRENT");
    verify(dependencies, never())
        .setDriverTaskLane(
            inbound.getExternalTaskId(), inboundBoard.taskVersion(), "CURRENT");
  }

  @Test
  void futureDateIsNeverPromotedBeforeWarehouseToday() {
    DriverLogisticsTask inbound = scheduledTask(DriverTaskKind.DELIVER_TO_REPAIR);
    LogisticsDependencyGateway.DriverBoardTask future =
        boardTask(inbound, today.plusDays(1), 0);
    configureBoard(List.of(future));
    configureLocal(inbound);
    when(dependencies.readRepairPlaces(warehouseId)).thenReturn(repairPlaces(1));

    scheduler.reconcileAndPromote(warehouseId);

    verify(dependencies, never()).setDriverTaskLane(any(), any(Long.class), any());
    verify(dependencies, never())
        .transitionRepairPlace(any(), any(), any(), any(Long.class), any());
  }

  private DriverLogisticsTask scheduledTask(DriverTaskKind kind) {
    UUID repairId = UUID.randomUUID();
    DriverLogisticsTask task =
        DriverLogisticsTask.create(
            warehouseId,
            UUID.randomUUID(),
            repairId,
            DriverTaskSourceType.REPAIR,
            repairId,
            kind,
            DriverTaskPlanningMode.AUTO,
            today,
            3,
            "БЫТ-" + repairId.toString().substring(0, 4),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64));
    task.registerBoardTask(
        UUID.randomUUID(), 0, UUID.randomUUID(), "WAITING", "SCHEDULED", null);
    if (kind.releasesRepairPlace()) {
      task.bindRemovalRepairPlace(UUID.randomUUID(), 0);
    }
    return task;
  }

  private void configureLocal(DriverLogisticsTask... localTasks) {
    for (DriverLogisticsTask task : localTasks) {
      when(tasks.findByExternalTaskId(task.getExternalTaskId()))
          .thenReturn(Optional.of(task));
    }
  }

  private void configureLastCompletedKind(DriverTaskKind kind) {
    DriverLogisticsTask completed = mock(DriverLogisticsTask.class);
    when(completed.getKind()).thenReturn(kind);
    when(tasks.findRecentByWarehouseAndState(warehouseId, DriverTaskState.COMPLETED))
        .thenReturn(List.of(completed));
  }

  private void configureBoard(List<LogisticsDependencyGateway.DriverBoardTask> boardTasks) {
    when(dependencies.readDriverBoard(warehouseId))
        .thenReturn(
            new LogisticsDependencyGateway.DriverBoardSnapshot(
                warehouseId,
                UUID.randomUUID(),
                0,
                List.of(),
                List.of(
                    new LogisticsDependencyGateway.DriverBoardDateColumn(
                        boardTasks.getFirst().scheduledDate(), boardTasks))));
  }

  private LogisticsDependencyGateway.DriverBoardTask boardTask(
      DriverLogisticsTask task, LocalDate scheduledDate, int position) {
    return new LogisticsDependencyGateway.DriverBoardTask(
        task.getTaskBoardTaskId(),
        task.getTaskBoardTaskVersion(),
        warehouseId,
        task.getExternalTaskId(),
        "Задание",
        task.getUnitNumber(),
        null,
        "ACTIVE",
        scheduledDate,
        "SCHEDULED",
        task.getPriority(),
        false,
        null,
        task.getTaskBoardEntryId(),
        0,
        "WAITING",
        position);
  }

  private LogisticsDependencyGateway.DriverBoardTask current(
      LogisticsDependencyGateway.DriverBoardTask task) {
    return new LogisticsDependencyGateway.DriverBoardTask(
        task.taskId(),
        task.taskVersion() + 1,
        task.warehouseId(),
        task.externalTaskId(),
        task.title(),
        task.unitNumber(),
        task.taskText(),
        task.status(),
        task.scheduledDate(),
        "CURRENT",
        task.priority(),
        task.pinned(),
        task.doneAt(),
        task.entryId(),
        task.entryVersion(),
        task.entryStatus(),
        0);
  }

  private LogisticsDependencyGateway.RepairPlaceProjection repairPlaces(long available) {
    long occupied = Math.max(0, 1 - available);
    return new LogisticsDependencyGateway.RepairPlaceProjection(
        warehouseId,
        1,
        0,
        occupied,
        0,
        available,
        false,
        List.of());
  }

  private LogisticsDependencyGateway.RepairPlaceAllocation allocation(
      DriverLogisticsTask task, String state, long version) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return new LogisticsDependencyGateway.RepairPlaceAllocation(
        UUID.randomUUID(),
        version,
        warehouseId,
        task.getRepairId(),
        task.getCabinId(),
        state,
        now,
        now);
  }
}
