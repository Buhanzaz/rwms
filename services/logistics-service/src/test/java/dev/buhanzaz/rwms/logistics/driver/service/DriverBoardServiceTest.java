package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardLane;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.MoveDriverBoardTaskRequest;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.ReturnCapitalRepairRequest;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
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
  private final DriverTaskService driverTaskService = mock(DriverTaskService.class);
  private final DriverBoardService service =
      new DriverBoardService(
          tasks, dependencies, workflowStore, processor, scheduler, driverTaskService);

  @BeforeEach
  void warehouseClock() {
    when(dependencies.readWarehouseIdentity(warehouseId))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(
                warehouseId, 0, true, "UTC"));
  }

  @Test
  void scheduledCapitalRepairIsPromotedThenInsertedAtTheRequestedCurrentPosition() {
    DriverLogisticsTask task = capitalTask();
    LogisticsDependencyGateway.DriverBoardTask scheduled = boardTask(task, 0, 0, "SCHEDULED", 4);
    LogisticsDependencyGateway.DriverBoardTask promoted = boardTask(task, 1, 0, "CURRENT", 3);
    LogisticsDependencyGateway.DriverBoardTask reordered = boardTask(task, 1, 1, "CURRENT", 0);
    when(tasks.findForUpdateByExternalTaskId(task.getExternalTaskId()))
        .thenReturn(Optional.of(task));
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
  void scheduledRepairDeliveryCannotBeManuallyMovedToCurrent() {
    DriverLogisticsTask task = scheduledTask();
    LogisticsDependencyGateway.DriverBoardTask scheduled =
        boardTask(task, 0, 0, "SCHEDULED", 4);
    when(tasks.findForUpdateByExternalTaskId(task.getExternalTaskId()))
        .thenReturn(Optional.of(task));
    when(dependencies.readDriverTask(task.getExternalTaskId())).thenReturn(scheduled);

    assertThatThrownBy(
            () ->
                service.move(
                    task.getExternalTaskId(),
                    new MoveDriverBoardTaskRequest(
                        warehouseId, 0L, 0L, DriverBoardLane.CURRENT, today, 0)))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("только капитальный ремонт");

    verify(scheduler, never()).promoteRequested(task.getId());
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
    when(tasks.findForUpdateByExternalTaskId(task.getExternalTaskId()))
        .thenReturn(Optional.of(task));
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

  @Test
  void unfinishedCapitalMovementIsCancelledAndReturnsToCapitalRepairs() {
    DriverLogisticsTask task = capitalTask();
    task.moveToCurrent(1, task.getTaskBoardEntryId(), "WAITING");
    LogisticsDependencyGateway.DriverBoardTask cancelled =
        new LogisticsDependencyGateway.DriverBoardTask(
            task.getTaskBoardTaskId(),
            2,
            warehouseId,
            task.getExternalTaskId(),
            "Переместить бытовку на производство",
            task.getUnitNumber(),
            "Переместить бытовку на производство",
            "CANCELLED",
            today,
            "CURRENT",
            task.getPriority(),
            false,
            null,
            task.getTaskBoardEntryId(),
            1,
            "CANCELLED",
            0);
    when(tasks.findForUpdateByExternalTaskId(task.getExternalTaskId()))
        .thenReturn(Optional.of(task));
    when(dependencies.readDriverTask(task.getExternalTaskId()))
        .thenReturn(boardTask(task, 1, 0, "CURRENT", 0));
    when(dependencies.cancelDriverTask(task.getExternalTaskId(), 1)).thenReturn(cancelled);
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
        .confirmStatus(task.getId(), cancelled);

    service.returnToCapitalRepairs(
        task.getExternalTaskId(), new ReturnCapitalRepairRequest(warehouseId, 1L));

    verify(dependencies).cancelDriverTask(task.getExternalTaskId(), 1);
    assertThat(task.getState().name()).isEqualTo("CANCELLED");
  }

  @Test
  void cancelledCapitalMovementNoLongerHidesTheActiveCapitalRepair() {
    DriverLogisticsTask task = capitalTask();
    LogisticsDependencyGateway.DriverBoardTask cancelled =
        new LogisticsDependencyGateway.DriverBoardTask(
            task.getTaskBoardTaskId(),
            1,
            warehouseId,
            task.getExternalTaskId(),
            "Переместить бытовку на производство",
            task.getUnitNumber(),
            "Переместить бытовку на производство",
            "CANCELLED",
            today,
            "SCHEDULED",
            task.getPriority(),
            false,
            null,
            task.getTaskBoardEntryId(),
            1,
            "CANCELLED",
            0);
    task.observeBoardTask(
        cancelled.taskId(),
        cancelled.taskVersion(),
        cancelled.entryId(),
        cancelled.entryStatus(),
        cancelled.scheduledDate(),
        cancelled.lane(),
        cancelled.status(),
        cancelled.doneAt());
    when(tasks.findAllByWarehouseIdOrderByCreatedAtAscIdAsc(warehouseId))
        .thenReturn(java.util.List.of(task));
    when(dependencies.readDriverBoard(warehouseId))
        .thenReturn(
            new LogisticsDependencyGateway.DriverBoardSnapshot(
                warehouseId, UUID.randomUUID(), 0, java.util.List.of(), java.util.List.of()));
    when(dependencies.readRepairPlaces(warehouseId)).thenReturn(repairPlaces());
    when(dependencies.readCapitalRepairs(warehouseId, 0, 200))
        .thenReturn(
            new LogisticsDependencyGateway.CapitalRepairPage(
                java.util.List.of(
                    new LogisticsDependencyGateway.CapitalRepair(
                        task.getSourceId(),
                        task.getCabinId(),
                        warehouseId,
                        task.getPriority(),
                        new LogisticsDependencyGateway.RepairComplexitySnapshot(
                            "CAPITAL", "Капитальный ремонт", "#7C3AED", "540", true),
                        3)),
                0,
                200,
                1));
    when(dependencies.readRentalItemSnapshot(task.getCabinId()))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                task.getCabinId(),
                1,
                warehouseId,
                task.getUnitNumber(),
                "CAPITAL_REPAIR",
                java.util.List.of()));

    var response = service.board(warehouseId);

    assertThat(response.capitalRepairs())
        .singleElement()
        .satisfies(card -> assertThat(card.repairId()).isEqualTo(task.getSourceId()));
  }

  @Test
  void boardHidesReservedDeliveriesFromPhysicalRepairPlaces() {
    DriverLogisticsTask task = scheduledTask();
    UUID allocationId = UUID.randomUUID();
    LogisticsDependencyGateway.RepairPlaceAllocation reserved =
        new LogisticsDependencyGateway.RepairPlaceAllocation(
            allocationId,
            1,
            warehouseId,
            task.getRepairId(),
            task.getCabinId(),
            "RESERVED",
            null,
            null,
            task.getPriority(),
            java.time.OffsetDateTime.now(ZoneOffset.UTC),
            java.time.OffsetDateTime.now(ZoneOffset.UTC));
    when(tasks.findAllByWarehouseIdOrderByCreatedAtAscIdAsc(warehouseId))
        .thenReturn(java.util.List.of(task));
    when(dependencies.readDriverBoard(warehouseId))
        .thenReturn(
            new LogisticsDependencyGateway.DriverBoardSnapshot(
                warehouseId, UUID.randomUUID(), 0, java.util.List.of(), java.util.List.of()));
    when(dependencies.readRepairPlaces(warehouseId))
        .thenReturn(
            new LogisticsDependencyGateway.RepairPlaceProjection(
                warehouseId, 6, 5, 1, 0, 0, 5, false, java.util.List.of(reserved)));
    when(dependencies.readCapitalRepairs(warehouseId, 0, 200))
        .thenReturn(
            new LogisticsDependencyGateway.CapitalRepairPage(
                java.util.List.of(), 0, 200, 0));
    when(dependencies.readRentalItemSnapshot(task.getCabinId()))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                task.getCabinId(),
                1,
                warehouseId,
                task.getUnitNumber(),
                "IN_REPAIR",
                java.util.List.of()));

    var response = service.board(warehouseId);

    assertThat(response.usedRepairPlaceCount()).isEqualTo(1);
    assertThat(response.occupiedRepairPlaceCount()).isZero();
    assertThat(response.repairPlaces()).isEmpty();
    verify(dependencies, never()).readRentalItemSnapshot(task.getCabinId());
  }

  @Test
  void boardListsOnlyCabinsPhysicallyMovedIntoRepairPlaces() {
    DriverLogisticsTask reservedTask = scheduledTask();
    DriverLogisticsTask occupiedTask = scheduledTask();
    DriverLogisticsTask readyToReleaseTask = scheduledTask();
    LogisticsDependencyGateway.RepairPlaceAllocation reserved =
        new LogisticsDependencyGateway.RepairPlaceAllocation(
            UUID.randomUUID(),
            1,
            warehouseId,
            reservedTask.getRepairId(),
            reservedTask.getCabinId(),
            "RESERVED",
            null,
            null,
            reservedTask.getPriority(),
            java.time.OffsetDateTime.now(ZoneOffset.UTC),
            java.time.OffsetDateTime.now(ZoneOffset.UTC));
    LogisticsDependencyGateway.RepairPlaceAllocation occupied =
        new LogisticsDependencyGateway.RepairPlaceAllocation(
            UUID.randomUUID(),
            1,
            warehouseId,
            occupiedTask.getRepairId(),
            occupiedTask.getCabinId(),
            "OCCUPIED",
            "Электрика",
            "IN_PROGRESS",
            occupiedTask.getPriority(),
            java.time.OffsetDateTime.now(ZoneOffset.UTC),
            java.time.OffsetDateTime.now(ZoneOffset.UTC));
    LogisticsDependencyGateway.RepairPlaceAllocation readyToRelease =
        new LogisticsDependencyGateway.RepairPlaceAllocation(
            UUID.randomUUID(),
            1,
            warehouseId,
            readyToReleaseTask.getRepairId(),
            readyToReleaseTask.getCabinId(),
            "READY_TO_RELEASE",
            null,
            null,
            readyToReleaseTask.getPriority(),
            java.time.OffsetDateTime.now(ZoneOffset.UTC),
            java.time.OffsetDateTime.now(ZoneOffset.UTC));
    when(tasks.findAllByWarehouseIdOrderByCreatedAtAscIdAsc(warehouseId))
        .thenReturn(java.util.List.of(reservedTask, occupiedTask, readyToReleaseTask));
    when(dependencies.readDriverBoard(warehouseId))
        .thenReturn(
            new LogisticsDependencyGateway.DriverBoardSnapshot(
                warehouseId, UUID.randomUUID(), 0, java.util.List.of(), java.util.List.of()));
    when(dependencies.readRepairPlaces(warehouseId))
        .thenReturn(
            new LogisticsDependencyGateway.RepairPlaceProjection(
                warehouseId,
                6,
                5,
                1,
                1,
                1,
                3,
                false,
                java.util.List.of(reserved, occupied, readyToRelease)));
    when(dependencies.readCapitalRepairs(warehouseId, 0, 200))
        .thenReturn(
            new LogisticsDependencyGateway.CapitalRepairPage(
                java.util.List.of(), 0, 200, 0));
    when(dependencies.readRentalItemSnapshot(occupiedTask.getCabinId()))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                occupiedTask.getCabinId(),
                1,
                warehouseId,
                occupiedTask.getUnitNumber(),
                "IN_REPAIR",
                java.util.List.of()));
    when(dependencies.readRentalItemSnapshot(readyToReleaseTask.getCabinId()))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                readyToReleaseTask.getCabinId(),
                1,
                warehouseId,
                readyToReleaseTask.getUnitNumber(),
                "IN_REPAIR",
                java.util.List.of()));

    var response = service.board(warehouseId);

    assertThat(response.usedRepairPlaceCount()).isEqualTo(2);
    assertThat(response.occupiedRepairPlaceCount()).isEqualTo(2);
    assertThat(response.repairPlaces())
        .anySatisfy(
            place -> {
              assertThat(place.repairId()).isEqualTo(occupiedTask.getRepairId());
              assertThat(place.unitNumber()).isEqualTo(occupiedTask.getUnitNumber());
              assertThat(place.allocationState()).isEqualTo("OCCUPIED");
              assertThat(place.repairStageName()).isEqualTo("Электрика");
              assertThat(place.repairStageState()).isEqualTo("IN_PROGRESS");
            });
    assertThat(response.repairPlaces())
        .anySatisfy(
            place -> {
              assertThat(place.repairId()).isEqualTo(readyToReleaseTask.getRepairId());
              assertThat(place.allocationState()).isEqualTo("READY_TO_RELEASE");
              assertThat(place.repairStageName()).isNull();
              assertThat(place.repairStageState()).isNull();
            });
    assertThat(response.repairPlaces()).hasSize(2);
    verify(dependencies, never()).readRentalItemSnapshot(reservedTask.getCabinId());
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

  private DriverLogisticsTask capitalTask() {
    UUID repairId = UUID.randomUUID();
    DriverLogisticsTask task =
        DriverLogisticsTask.create(
            warehouseId,
            UUID.randomUUID(),
            null,
            DriverTaskSourceType.CAPITAL_REPAIR,
            repairId,
            DriverTaskKind.CAPITAL_TO_PRODUCTION,
            DriverTaskPlanningMode.AUTO,
            today,
            2,
            null,
            "БЫТ-КАП",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "b".repeat(64));
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
