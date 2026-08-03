package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardLane;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.MoveDriverBoardTaskRequest;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DriverBoardServiceTest {
  private final UUID warehouseId = UUID.randomUUID();
  private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
  private final DriverLogisticsTaskRepository tasks =
      mock(DriverLogisticsTaskRepository.class);
  private final LogisticsDependencyGateway dependencies =
      mock(LogisticsDependencyGateway.class);
  private final DriverTaskWorkflowStore workflowStore =
      mock(DriverTaskWorkflowStore.class);
  private final DriverTaskProcessor processor = mock(DriverTaskProcessor.class);
  private final DriverQueueScheduler scheduler = mock(DriverQueueScheduler.class);
  private final DriverBoardService service =
      new DriverBoardService(tasks, dependencies, workflowStore, processor, scheduler);

  @BeforeEach
  void warehouseClock() {
    when(dependencies.readWarehouseIdentity(warehouseId))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(
                warehouseId, 0, true, "UTC"));
  }

  @Test
  void scheduledCardIsPromotedThenInsertedAtTheRequestedCurrentPosition() {
    DriverLogisticsTask task = scheduledTask();
    LogisticsDependencyGateway.DriverBoardTask scheduled = boardTask(task, 0, 0, "SCHEDULED", 4);
    LogisticsDependencyGateway.DriverBoardTask promoted = boardTask(task, 1, 0, "CURRENT", 3);
    LogisticsDependencyGateway.DriverBoardTask reordered = boardTask(task, 1, 1, "CURRENT", 0);
    when(tasks.findByExternalTaskId(task.getExternalTaskId())).thenReturn(Optional.of(task));
    when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
    when(dependencies.readDriverTask(task.getExternalTaskId()))
        .thenReturn(scheduled, promoted);
    when(dependencies.moveDriverTask(
            task.getExternalTaskId(), 1, 0, "CURRENT", today, 0))
        .thenReturn(reordered);

    var response =
        service.move(
            task.getExternalTaskId(),
            new MoveDriverBoardTaskRequest(
                warehouseId, 0L, 0L, DriverBoardLane.CURRENT, today, 0));

    verify(scheduler).promoteRequested(task.getId());
    verify(dependencies)
        .moveDriverTask(
            task.getExternalTaskId(), 1, 0, "CURRENT", today, 0);
    assertThat(response.lane()).isEqualTo("CURRENT");
    assertThat(response.position()).isZero();
  }

  @Test
  void currentInboundMovedBackToDateCreatesDurableHoldAndRunsReservationRelease() {
    DriverLogisticsTask task = scheduledTask();
    UUID allocationId = UUID.randomUUID();
    task.reserveRepairPlace(allocationId, 0);
    task.moveToCurrent(1, task.getTaskBoardEntryId(), "WAITING");
    LogisticsDependencyGateway.DriverBoardTask current = boardTask(task, 1, 0, "CURRENT", 0);
    LocalDate targetDate = today.plusDays(2);
    LogisticsDependencyGateway.DriverBoardTask moved =
        boardTask(task, 2, 1, "SCHEDULED", 2, targetDate);
    when(tasks.findByExternalTaskId(task.getExternalTaskId())).thenReturn(Optional.of(task));
    when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
    when(tasks.saveAndFlush(task)).thenReturn(task);
    when(dependencies.readDriverTask(task.getExternalTaskId())).thenReturn(current);
    when(dependencies.moveDriverTask(
            task.getExternalTaskId(), 1, 0, "SCHEDULED", targetDate, 2))
        .thenReturn(moved);
    when(dependencies.readRepairPlaces(warehouseId))
        .thenReturn(repairPlaces());
    doAnswer(
            invocation -> {
              LogisticsDependencyGateway.DriverBoardTask board = invocation.getArgument(1);
              task.observeBoardTask(
                  board.taskId(),
                  board.taskVersion(),
                  board.entryId(),
                  board.entryStatus(),
                  board.scheduledDate(),
                  board.lane(),
                  board.status(),
                  board.doneAt());
              return null;
            })
        .when(workflowStore)
        .confirmStatus(task.getId(), moved);

    service.move(
        task.getExternalTaskId(),
        new MoveDriverBoardTaskRequest(
            warehouseId, 1L, 0L, DriverBoardLane.SCHEDULED, targetDate, 2));

    assertThat(task.hasManualPromotionHold()).isTrue();
    assertThat(task.getPlanningMode()).isEqualTo(DriverTaskPlanningMode.FIXED_DATE);
    assertThat(task.getFixedDateLowerBound()).isEqualTo(targetDate);
    verify(processor).processUntilIdle(task.getId());
    verify(tasks).saveAndFlush(task);
  }

  private DriverLogisticsTask scheduledTask() {
    UUID repairId = UUID.randomUUID();
    DriverLogisticsTask task =
        DriverLogisticsTask.create(
            warehouseId,
            UUID.randomUUID(),
            repairId,
            DriverTaskSourceType.REPAIR,
            repairId,
            DriverTaskKind.DELIVER_TO_REPAIR,
            DriverTaskPlanningMode.AUTO,
            today,
            3,
            null,
            "БЫТ-101",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64));
    ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
    task.registerBoardTask(
        UUID.randomUUID(), 0, UUID.randomUUID(), "WAITING", "SCHEDULED", null);
    return task;
  }

  private LogisticsDependencyGateway.DriverBoardTask boardTask(
      DriverLogisticsTask task,
      long taskVersion,
      long entryVersion,
      String lane,
      int position) {
    return boardTask(task, taskVersion, entryVersion, lane, position, today);
  }

  private LogisticsDependencyGateway.DriverBoardTask boardTask(
      DriverLogisticsTask task,
      long taskVersion,
      long entryVersion,
      String lane,
      int position,
      LocalDate scheduledDate) {
    return new LogisticsDependencyGateway.DriverBoardTask(
        task.getTaskBoardTaskId(),
        taskVersion,
        warehouseId,
        task.getExternalTaskId(),
        "Доставить бытовку в ремонт",
        task.getUnitNumber(),
        "Доставить бытовку в ремонт",
        "ACTIVE",
        scheduledDate,
        lane,
        task.getPriority(),
        false,
        null,
        task.getTaskBoardEntryId(),
        entryVersion,
        "WAITING",
        position);
  }

  private LogisticsDependencyGateway.RepairPlaceProjection repairPlaces() {
    return new LogisticsDependencyGateway.RepairPlaceProjection(
        warehouseId, 6, 5, 0, 0, 0, 6, false, java.util.List.of());
  }
}
