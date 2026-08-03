package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import org.springframework.test.util.ReflectionTestUtils;

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
    when(dependencies.setDriverTaskLane(
            outbound.getExternalTaskId(), outboundBoard.taskVersion(), "CURRENT"))
        .thenReturn(current(outboundBoard));

    scheduler.reconcileAndPromote(warehouseId);

    verify(dependencies)
        .setDriverTaskLane(
            inbound.getExternalTaskId(), inboundBoard.taskVersion(), "CURRENT");
    verify(dependencies)
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

  @Test
  void manualPromotionAllowsAPlannedFutureTaskIntoTheCurrentLane() {
    DriverLogisticsTask inbound = scheduledTask(DriverTaskKind.DELIVER_TO_REPAIR);
    LogisticsDependencyGateway.DriverBoardTask future =
        boardTask(inbound, today.plusDays(3), 0);
    configureBoard(List.of(future));
    configureLocal(inbound);
    when(tasks.findById(inbound.getId())).thenReturn(Optional.of(inbound));
    when(dependencies.readRepairPlaces(warehouseId)).thenReturn(repairPlaces(1));
    LogisticsDependencyGateway.RepairPlaceAllocation reservation =
        allocation(inbound, "RESERVED", 0);
    when(dependencies.transitionRepairPlace(
        any(), eq(warehouseId), eq(inbound.getRepairId()), eq(0L), eq("reserve")))
        .thenReturn(reservation);
    when(dependencies.setDriverTaskLane(
        inbound.getExternalTaskId(), future.taskVersion(), "CURRENT"))
        .thenReturn(current(future));

    scheduler.promoteRequested(inbound.getId());

    verify(dependencies).setDriverTaskLane(
        inbound.getExternalTaskId(), future.taskVersion(), "CURRENT");
    verify(store).confirmReservation(inbound.getId(), reservation);
    verify(store).confirmCurrent(inbound.getId(), current(future));
  }

  @Test
  void automaticPassFillsCurrentLaneOnlyUpToAvailableRepairPlaces() {
    DriverLogisticsTask first = scheduledTask(DriverTaskKind.DELIVER_TO_REPAIR);
    DriverLogisticsTask second = scheduledTask(DriverTaskKind.DELIVER_TO_REPAIR);
    DriverLogisticsTask waiting = scheduledTask(DriverTaskKind.DELIVER_TO_REPAIR);
    LogisticsDependencyGateway.DriverBoardTask firstBoard = boardTask(first, today, 0);
    LogisticsDependencyGateway.DriverBoardTask secondBoard = boardTask(second, today, 1);
    LogisticsDependencyGateway.DriverBoardTask waitingBoard = boardTask(waiting, today, 2);
    configureBoard(List.of(firstBoard, secondBoard, waitingBoard));
    configureLocal(first, second, waiting);
    when(dependencies.readRepairPlaces(warehouseId)).thenReturn(repairPlaces(3, 2));

    LogisticsDependencyGateway.RepairPlaceAllocation firstReservation =
        allocation(first, "RESERVED", 0);
    LogisticsDependencyGateway.RepairPlaceAllocation secondReservation =
        allocation(second, "RESERVED", 0);
    when(dependencies.transitionRepairPlace(
            any(), eq(warehouseId), eq(first.getRepairId()), eq(0L), eq("reserve")))
        .thenReturn(firstReservation);
    when(dependencies.transitionRepairPlace(
            any(), eq(warehouseId), eq(second.getRepairId()), eq(0L), eq("reserve")))
        .thenReturn(secondReservation);
    when(dependencies.setDriverTaskLane(
            first.getExternalTaskId(), firstBoard.taskVersion(), "CURRENT"))
        .thenReturn(current(firstBoard));
    when(dependencies.setDriverTaskLane(
            second.getExternalTaskId(), secondBoard.taskVersion(), "CURRENT"))
        .thenReturn(current(secondBoard));

    scheduler.reconcileAndPromote(warehouseId);

    verify(dependencies)
        .setDriverTaskLane(first.getExternalTaskId(), firstBoard.taskVersion(), "CURRENT");
    verify(dependencies)
        .setDriverTaskLane(second.getExternalTaskId(), secondBoard.taskVersion(), "CURRENT");
    verify(dependencies, never())
        .setDriverTaskLane(waiting.getExternalTaskId(), waitingBoard.taskVersion(), "CURRENT");
  }

  @Test
  void freshRepairPlaceSnapshotsDoNotConsumeTheSameCapacityTwice() {
    DriverLogisticsTask first = scheduledTask(DriverTaskKind.DELIVER_TO_REPAIR);
    DriverLogisticsTask second = scheduledTask(DriverTaskKind.DELIVER_TO_REPAIR);
    DriverLogisticsTask waiting = scheduledTask(DriverTaskKind.DELIVER_TO_REPAIR);
    LogisticsDependencyGateway.DriverBoardTask firstBoard = boardTask(first, today, 0);
    LogisticsDependencyGateway.DriverBoardTask secondBoard = boardTask(second, today, 1);
    LogisticsDependencyGateway.DriverBoardTask waitingBoard = boardTask(waiting, today, 2);
    configureBoard(List.of(firstBoard, secondBoard, waitingBoard));
    configureLocal(first, second, waiting);
    when(dependencies.readRepairPlaces(warehouseId))
        .thenReturn(
            repairPlaces(3, 2, 0, 1, 0),
            repairPlaces(3, 2, 0, 1, 0),
            repairPlaces(3, 1, 1, 1, 0),
            repairPlaces(3, 0, 2, 1, 0));

    LogisticsDependencyGateway.RepairPlaceAllocation firstReservation =
        allocation(first, "RESERVED", 0);
    LogisticsDependencyGateway.RepairPlaceAllocation secondReservation =
        allocation(second, "RESERVED", 0);
    when(dependencies.transitionRepairPlace(
            any(), eq(warehouseId), eq(first.getRepairId()), eq(0L), eq("reserve")))
        .thenReturn(firstReservation);
    when(dependencies.transitionRepairPlace(
            any(), eq(warehouseId), eq(second.getRepairId()), eq(0L), eq("reserve")))
        .thenReturn(secondReservation);
    when(dependencies.setDriverTaskLane(
            first.getExternalTaskId(), firstBoard.taskVersion(), "CURRENT"))
        .thenReturn(current(firstBoard));
    when(dependencies.setDriverTaskLane(
            second.getExternalTaskId(), secondBoard.taskVersion(), "CURRENT"))
        .thenReturn(current(secondBoard));

    scheduler.reconcileAndPromote(warehouseId);

    verify(dependencies)
        .setDriverTaskLane(first.getExternalTaskId(), firstBoard.taskVersion(), "CURRENT");
    verify(dependencies)
        .setDriverTaskLane(second.getExternalTaskId(), secondBoard.taskVersion(), "CURRENT");
    verify(dependencies, never())
        .setDriverTaskLane(waiting.getExternalTaskId(), waitingBoard.taskVersion(), "CURRENT");
  }

  @Test
  void readyRemovalAndInboundDeliveryArePairedEvenWhenPhysicalCapacityIsFull() {
    DriverLogisticsTask removal = scheduledTask(DriverTaskKind.REMOVE_FROM_REPAIR);
    DriverLogisticsTask inbound = scheduledTask(DriverTaskKind.DELIVER_TO_REPAIR);
    LogisticsDependencyGateway.DriverBoardTask removalBoard = boardTask(removal, today, 0);
    LogisticsDependencyGateway.DriverBoardTask inboundBoard = boardTask(inbound, today, 1);
    configureBoard(List.of(removalBoard, inboundBoard));
    configureLocal(removal, inbound);
    when(dependencies.readRepairPlaces(warehouseId))
        .thenReturn(repairPlaces(1, 0, 0, 0, 1));
    LogisticsDependencyGateway.RepairPlaceAllocation reservation =
        allocation(inbound, "RESERVED", 0);
    when(dependencies.transitionRepairPlace(
            any(), eq(warehouseId), eq(inbound.getRepairId()), eq(0L), eq("reserve")))
        .thenReturn(reservation);
    when(dependencies.setDriverTaskLane(
            removal.getExternalTaskId(), removalBoard.taskVersion(), "CURRENT"))
        .thenReturn(current(removalBoard));
    when(dependencies.setDriverTaskLane(
            inbound.getExternalTaskId(), inboundBoard.taskVersion(), "CURRENT"))
        .thenReturn(current(inboundBoard));

    scheduler.reconcileAndPromote(warehouseId);

    verify(dependencies)
        .setDriverTaskLane(
            removal.getExternalTaskId(), removalBoard.taskVersion(), "CURRENT");
    verify(dependencies)
        .setDriverTaskLane(
            inbound.getExternalTaskId(), inboundBoard.taskVersion(), "CURRENT");
    verify(store).confirmReservation(inbound.getId(), reservation);
  }

  @Test
  void manualRemovalHoldPreventsRelayFromImmediatelyRefillingCurrentLane() {
    DriverLogisticsTask inbound = scheduledTask(DriverTaskKind.DELIVER_TO_REPAIR);
    LogisticsDependencyGateway.DriverBoardTask inboundBoard = boardTask(inbound, today, 0);
    configureBoard(List.of(inboundBoard));
    configureLocal(inbound);
    when(dependencies.readRepairPlaces(warehouseId)).thenReturn(repairPlaces(1));
    when(tasks.existsByWarehouseIdAndStateAndManualPromotionHoldUntilAfter(
            eq(warehouseId), eq(DriverTaskState.SCHEDULED), any(OffsetDateTime.class)))
        .thenReturn(true);

    scheduler.reconcileAndPromote(warehouseId);

    verify(dependencies, never()).setDriverTaskLane(any(), any(Long.class), any());
    verify(dependencies, never())
        .transitionRepairPlace(any(), any(), any(), any(Long.class), any());
  }

  @Test
  void explicitPromotionReleasesExactlyOneManualHoldBeforeUsingTheFreedPlace() {
    DriverLogisticsTask held = scheduledTask(DriverTaskKind.DELIVER_TO_REPAIR);
    UUID heldAllocationId = UUID.randomUUID();
    held.reserveRepairPlace(heldAllocationId, 0);
    held.moveToCurrent(1, held.getTaskBoardEntryId(), "WAITING");
    held.observeBoardTask(
        held.getTaskBoardTaskId(),
        2,
        held.getTaskBoardEntryId(),
        "WAITING",
        today.plusDays(1),
        "SCHEDULED",
        "ACTIVE",
        null);
    held.markManualPromotionHold(5);

    DriverLogisticsTask secondHeld = scheduledTask(DriverTaskKind.REMOVE_FROM_REPAIR);
    secondHeld.moveToCurrent(1, secondHeld.getTaskBoardEntryId(), "WAITING");
    secondHeld.observeBoardTask(
        secondHeld.getTaskBoardTaskId(),
        2,
        secondHeld.getTaskBoardEntryId(),
        "WAITING",
        today.plusDays(1),
        "SCHEDULED",
        "ACTIVE",
        null);
    secondHeld.markManualPromotionHold(5);

    DriverLogisticsTask replacement = scheduledTask(DriverTaskKind.DELIVER_TO_REPAIR);
    LogisticsDependencyGateway.DriverBoardTask replacementBoard =
        boardTask(replacement, today, 0);
    configureBoard(List.of(replacementBoard));
    configureLocal(held, replacement);
    when(tasks.findById(held.getId())).thenReturn(Optional.of(held));
    when(tasks.findById(replacement.getId())).thenReturn(Optional.of(replacement));
    when(tasks
            .findAllByWarehouseIdAndStateAndManualPromotionHoldUntilAfterOrderByManualPromotionHoldUntilAscIdAsc(
                eq(warehouseId), eq(DriverTaskState.SCHEDULED), any(OffsetDateTime.class)))
        .thenReturn(List.of(held, secondHeld));
    doAnswer(
            invocation -> {
              UUID processed = invocation.getArgument(0);
              if (held.getId().equals(processed)) {
                held.releaseRepairPlaceReservation(heldAllocationId, 1);
              }
              return 1;
            })
        .when(processor)
        .processUntilIdle(any());

    LogisticsDependencyGateway.RepairPlaceProjection places = repairPlaces(1);
    LogisticsDependencyGateway.RepairPlaceAllocation replacementReservation =
        allocation(replacement, "RESERVED", 0);
    when(dependencies.readRepairPlaces(warehouseId)).thenReturn(places);
    when(dependencies.transitionRepairPlace(
            any(), eq(warehouseId), eq(replacement.getRepairId()), eq(0L), eq("reserve")))
        .thenReturn(replacementReservation);
    when(dependencies.setDriverTaskLane(
            replacement.getExternalTaskId(), replacementBoard.taskVersion(), "CURRENT"))
        .thenReturn(current(replacementBoard));

    scheduler.promoteRequested(replacement.getId());

    assertThat(held.hasManualPromotionHold()).isFalse();
    assertThat(secondHeld.hasManualPromotionHold()).isTrue();
    verify(tasks).saveAndFlush(held);
    verify(dependencies)
        .setDriverTaskLane(
            replacement.getExternalTaskId(), replacementBoard.taskVersion(), "CURRENT");
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
            null,
            "БЫТ-" + repairId.toString().substring(0, 4),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64));
    ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
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
      when(dependencies.readDriverTask(task.getExternalTaskId())).thenReturn(boardTask(task, today, 0));
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
    return repairPlaces(1, available);
  }

  private LogisticsDependencyGateway.RepairPlaceProjection repairPlaces(
      int capacity, long available) {
    long occupied = Math.max(0, capacity - available);
    return repairPlaces(capacity, available, 0, occupied, 0);
  }

  private LogisticsDependencyGateway.RepairPlaceProjection repairPlaces(
      int capacity, long available, long reserved, long occupied, long ready) {
    return new LogisticsDependencyGateway.RepairPlaceProjection(
        warehouseId,
        capacity,
        5,
        reserved,
        occupied,
        ready,
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
