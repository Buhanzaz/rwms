package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DriverQueueRollingReflowTest {
  private final UUID warehouseId = UUID.randomUUID();
  private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
  private final DriverLogisticsTaskRepository tasks = mock(DriverLogisticsTaskRepository.class);
  private final DriverTaskService taskService = mock(DriverTaskService.class);
  private final DriverTaskWorkflowStore store = mock(DriverTaskWorkflowStore.class);
  private final DriverTaskProcessor processor = mock(DriverTaskProcessor.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final DriverQueueScheduler scheduler =
      new DriverQueueScheduler(tasks, taskService, store, processor, dependencies);

  @BeforeEach
  void warehouseClock() {
    when(dependencies.isWarehouseDriverQueueAvailable(warehouseId)).thenReturn(true);
    when(dependencies.readWarehouseIdentity(warehouseId))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(warehouseId, 0, true, "UTC"));
  }

  @Test
  void rollingQueueClampsOverdueWorkAndKeepsFutureFixedDateAsLowerBound() {
    DriverLogisticsTask overdue =
        scheduledTask(
            DriverTaskSourceType.REPAIR,
            DriverTaskKind.DELIVER_TO_REPAIR,
            DriverTaskPlanningMode.AUTO,
            today.minusDays(1),
            null);
    DriverLogisticsTask secondInbound =
        scheduledTask(
            DriverTaskSourceType.REPAIR,
            DriverTaskKind.DELIVER_TO_REPAIR,
            DriverTaskPlanningMode.AUTO,
            today,
            null);
    DriverLogisticsTask fixed =
        scheduledTask(
            DriverTaskSourceType.REPAIR,
            DriverTaskKind.DELIVER_TO_REPAIR,
            DriverTaskPlanningMode.FIXED_DATE,
            today.plusDays(3),
            null);
    LogisticsDependencyGateway.DriverBoardTask overdueBoard =
        boardTask(overdue, today.minusDays(1), 0, 11, 21);
    LogisticsDependencyGateway.DriverBoardTask secondBoard =
        boardTask(secondInbound, today, 0, 12, 22);
    LogisticsDependencyGateway.DriverBoardTask fixedBoard =
        boardTask(fixed, today, 1, 13, 23);
    LogisticsDependencyGateway.DriverBoardSnapshot board =
        board(List.of(overdueBoard), List.of(secondBoard, fixedBoard));

    when(tasks.findAllByWarehouseIdOrderByCreatedAtAscIdAsc(warehouseId))
        .thenReturn(List.of(overdue, secondInbound, fixed));
    when(dependencies.readRepairPlaces(warehouseId)).thenReturn(repairPlaces(1, 1, 0, 0, 0));
    when(dependencies.readDriverBoard(warehouseId)).thenReturn(board);
    when(tasks.existsByWarehouseIdAndStateAndManualPromotionHoldUntilAfter(
            eq(warehouseId), eq(DriverTaskState.SCHEDULED), any(OffsetDateTime.class)))
        .thenReturn(true);
    when(dependencies.readDriverTask(overdue.getExternalTaskId())).thenReturn(overdueBoard);
    when(dependencies.readDriverTask(secondInbound.getExternalTaskId())).thenReturn(secondBoard);
    when(dependencies.readDriverTask(fixed.getExternalTaskId())).thenReturn(fixedBoard);
    when(dependencies.moveDriverTask(
            eq(overdue.getExternalTaskId()), eq(11L), eq(21L), eq("SCHEDULED"), eq(today), eq(0)))
        .thenReturn(boardTask(overdue, today, 0, 14, 24));
    when(dependencies.moveDriverTask(
            eq(secondInbound.getExternalTaskId()),
            eq(12L),
            eq(22L),
            eq("SCHEDULED"),
            eq(today.plusDays(1)),
            eq(0)))
        .thenReturn(boardTask(secondInbound, today.plusDays(1), 0, 15, 25));
    when(dependencies.moveDriverTask(
            eq(fixed.getExternalTaskId()),
            eq(13L),
            eq(23L),
            eq("SCHEDULED"),
            eq(today.plusDays(3)),
            eq(0)))
        .thenReturn(boardTask(fixed, today.plusDays(3), 0, 16, 26));

    scheduler.reconcileAndPromote(warehouseId);

    verify(dependencies)
        .moveDriverTask(
            overdue.getExternalTaskId(), 11, 21, "SCHEDULED", today, 0);
    verify(dependencies)
        .moveDriverTask(
            secondInbound.getExternalTaskId(), 12, 22, "SCHEDULED", today.plusDays(1), 0);
    verify(dependencies)
        .moveDriverTask(
            fixed.getExternalTaskId(), 13, 23, "SCHEDULED", today.plusDays(3), 0);
    verify(store).confirmStatus(eq(overdue.getId()), any());
    verify(store).confirmStatus(eq(secondInbound.getId()), any());
    verify(store).confirmStatus(eq(fixed.getId()), any());
  }

  @Test
  void currentTaskMovedToFutureDateIsNotPulledBackWhileItsRefillHoldIsActive() {
    LocalDate targetDate = today.plusDays(2);
    DriverLogisticsTask task =
        scheduledTask(
            DriverTaskSourceType.REPAIR,
            DriverTaskKind.DELIVER_TO_REPAIR,
            DriverTaskPlanningMode.AUTO,
            today,
            null);
    task.moveToCurrent(1, task.getTaskBoardEntryId(), "WAITING");
    task.observeBoardTask(
        task.getTaskBoardTaskId(),
        2,
        task.getTaskBoardEntryId(),
        "WAITING",
        targetDate,
        "SCHEDULED",
        "ACTIVE",
        null);
    task.markFixedDate(targetDate);
    task.markManualPromotionHold(5);
    LogisticsDependencyGateway.DriverBoardTask scheduled =
        boardTask(task, targetDate, 0, 2, 2);
    LogisticsDependencyGateway.DriverBoardSnapshot board = board(List.of(), List.of(scheduled));

    when(tasks.findAllByWarehouseIdOrderByCreatedAtAscIdAsc(warehouseId)).thenReturn(List.of(task));
    when(dependencies.readRepairPlaces(warehouseId)).thenReturn(repairPlaces(1, 1, 0, 0, 0));
    when(dependencies.readDriverBoard(warehouseId)).thenReturn(board);
    when(tasks.existsByWarehouseIdAndStateAndManualPromotionHoldUntilAfter(
            eq(warehouseId), eq(DriverTaskState.SCHEDULED), any(OffsetDateTime.class)))
        .thenReturn(true);
    when(dependencies.readDriverTask(task.getExternalTaskId())).thenReturn(scheduled);

    scheduler.reconcileAndPromote(warehouseId);

    assertThat(task.getPlanningMode()).isEqualTo(DriverTaskPlanningMode.FIXED_DATE);
    assertThat(task.getFixedDateLowerBound()).isEqualTo(targetDate);
    assertThat(task.isManualPromotionHeldAt(OffsetDateTime.now(ZoneOffset.UTC))).isTrue();
    verify(dependencies, never())
        .moveDriverTask(any(), anyLong(), anyLong(), any(), any(), anyInt());
  }

  @Test
  void pairedRemovalAndReservationConsumeOneRepairPlaceSlot() {
    LogisticsDependencyGateway.RepairPlaceProjection places = repairPlaces(6, 0, 2, 4, 2);

    assertThat(DriverQueueScheduler.usedRepairPlaceCount(places)).isEqualTo(6);
    assertThat(DriverQueueScheduler.inboundRepairPlaceAvailable(places)).isFalse();
  }

  @Test
  void manualInboundPromotionFailsWhenNoFreeOrPairedRepairPlaceExists() {
    DriverLogisticsTask inbound =
        scheduledTask(
            DriverTaskSourceType.REPAIR,
            DriverTaskKind.DELIVER_TO_REPAIR,
            DriverTaskPlanningMode.AUTO,
            today,
            null);
    when(tasks.findById(inbound.getId())).thenReturn(Optional.of(inbound));
    when(tasks
            .findAllByWarehouseIdAndStateAndManualPromotionHoldUntilAfterOrderByManualPromotionHoldUntilAscIdAsc(
                eq(warehouseId), eq(DriverTaskState.SCHEDULED), any(OffsetDateTime.class)))
        .thenReturn(List.of());
    when(dependencies.readRepairPlaces(warehouseId)).thenReturn(repairPlaces(1, 0, 0, 1, 0));

    assertThatThrownBy(() -> scheduler.promoteRequested(inbound.getId()))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("освобождаемого");

    verify(dependencies, never()).setDriverTaskLane(any(), any(Long.class), any());
  }

  @Test
  void capitalAndGeneralMovementsBypassRepairPlaceCapacity() {
    assertPromotionDoesNotReserve(
        scheduledTask(
            DriverTaskSourceType.CAPITAL_REPAIR,
            DriverTaskKind.CAPITAL_TO_PRODUCTION,
            DriverTaskPlanningMode.AUTO,
            today,
            null));
    assertPromotionDoesNotReserve(
        scheduledTask(
            DriverTaskSourceType.MANUAL,
            DriverTaskKind.GENERAL_MOVEMENT,
            DriverTaskPlanningMode.AUTO,
            today,
            "Переместить к воротам"));
  }

  private void assertPromotionDoesNotReserve(DriverLogisticsTask task) {
    LogisticsDependencyGateway.DriverBoardTask scheduled = boardTask(task, today, 0, 1, 1);
    LogisticsDependencyGateway.DriverBoardTask current = current(scheduled);
    when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
    when(tasks
            .findAllByWarehouseIdAndStateAndManualPromotionHoldUntilAfterOrderByManualPromotionHoldUntilAscIdAsc(
                eq(warehouseId), eq(DriverTaskState.SCHEDULED), any(OffsetDateTime.class)))
        .thenReturn(List.of());
    when(dependencies.readRepairPlaces(warehouseId)).thenReturn(repairPlaces(1, 0, 0, 1, 0));
    when(dependencies.readDriverBoard(warehouseId)).thenReturn(board(List.of(), List.of(scheduled)));
    when(dependencies.readDriverTask(task.getExternalTaskId())).thenReturn(scheduled);
    when(dependencies.setDriverTaskLane(task.getExternalTaskId(), 1, "CURRENT"))
        .thenReturn(current);

    scheduler.promoteRequested(task.getId());

    verify(dependencies).setDriverTaskLane(task.getExternalTaskId(), 1, "CURRENT");
    verify(dependencies, never())
        .transitionRepairPlace(any(), any(), any(), any(Long.class), any());
  }

  private DriverLogisticsTask scheduledTask(
      DriverTaskSourceType sourceType,
      DriverTaskKind kind,
      DriverTaskPlanningMode planningMode,
      LocalDate scheduledDate,
      String comment) {
    UUID repairId =
        kind.consumesRepairPlace() || kind.releasesRepairPlace() || kind == DriverTaskKind.CAPITAL_TO_PRODUCTION
            ? UUID.randomUUID()
            : null;
    UUID sourceId = repairId == null ? UUID.randomUUID() : repairId;
    DriverLogisticsTask task =
        DriverLogisticsTask.create(
            warehouseId,
            UUID.randomUUID(),
            repairId,
            sourceType,
            sourceId,
            kind,
            planningMode,
            scheduledDate,
            3,
            comment,
            "БЫТ-" + UUID.randomUUID().toString().substring(0, 4),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64));
    ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
    task.registerBoardTask(
        UUID.randomUUID(), 0, UUID.randomUUID(), "WAITING", "SCHEDULED", null);
    return task;
  }

  private LogisticsDependencyGateway.DriverBoardSnapshot board(
      List<LogisticsDependencyGateway.DriverBoardTask> firstDate,
      List<LogisticsDependencyGateway.DriverBoardTask> secondDate) {
    List<LogisticsDependencyGateway.DriverBoardDateColumn> dates = new java.util.ArrayList<>();
    if (!firstDate.isEmpty()) {
      dates.add(
          new LogisticsDependencyGateway.DriverBoardDateColumn(
              firstDate.getFirst().scheduledDate(), firstDate));
    }
    if (!secondDate.isEmpty()) {
      dates.add(
          new LogisticsDependencyGateway.DriverBoardDateColumn(
              secondDate.getFirst().scheduledDate(), secondDate));
    }
    return new LogisticsDependencyGateway.DriverBoardSnapshot(
        warehouseId, UUID.randomUUID(), 0, List.of(), dates);
  }

  private LogisticsDependencyGateway.DriverBoardTask boardTask(
      DriverLogisticsTask task,
      LocalDate scheduledDate,
      int position,
      long taskVersion,
      long entryVersion) {
    return new LogisticsDependencyGateway.DriverBoardTask(
        task.getTaskBoardTaskId(),
        taskVersion,
        warehouseId,
        task.getExternalTaskId(),
        "Перемещение",
        task.getUnitNumber(),
        task.getComment(),
        "ACTIVE",
        scheduledDate,
        "SCHEDULED",
        task.getPriority(),
        false,
        null,
        task.getTaskBoardEntryId(),
        entryVersion,
        "WAITING",
        position);
  }

  private LogisticsDependencyGateway.DriverBoardTask current(
      LogisticsDependencyGateway.DriverBoardTask scheduled) {
    return new LogisticsDependencyGateway.DriverBoardTask(
        scheduled.taskId(),
        scheduled.taskVersion() + 1,
        scheduled.warehouseId(),
        scheduled.externalTaskId(),
        scheduled.title(),
        scheduled.unitNumber(),
        scheduled.taskText(),
        scheduled.status(),
        scheduled.scheduledDate(),
        "CURRENT",
        scheduled.priority(),
        scheduled.pinned(),
        null,
        scheduled.entryId(),
        scheduled.entryVersion(),
        scheduled.entryStatus(),
        0);
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
}
