package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.capacity.service.CustomerDeliveryCapacityFence;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.DriverBoardLane;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.MoveDriverBoardTaskRequest;
import dev.buhanzaz.rwms.logistics.driver.api.DriverBoardApiModels.ReturnCapitalRepairRequest;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DriverBoardServiceTest {
  private final UUID warehouseId = UUID.randomUUID();
  private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
  private final DriverLogisticsTaskRepository tasks = mock(DriverLogisticsTaskRepository.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final DriverTaskWorkflowStore workflowStore = mock(DriverTaskWorkflowStore.class);
  private final DriverTaskProcessor processor = mock(DriverTaskProcessor.class);
  private final DriverQueueScheduler scheduler = mock(DriverQueueScheduler.class);
  private final DriverTaskService driverTaskService = mock(DriverTaskService.class);
  private final DriverTripProjectionService tripProjection =
      mock(DriverTripProjectionService.class);
  private final LogisticsTransactionLock transactionLock = mock(LogisticsTransactionLock.class);
  private final CustomerDeliveryCapacityFence capacityFence =
      mock(CustomerDeliveryCapacityFence.class);
  private final DriverBoardService service =
      new DriverBoardService(
          tasks,
          dependencies,
          workflowStore,
          processor,
          scheduler,
          driverTaskService,
          tripProjection,
          transactionLock,
          capacityFence);

  @BeforeEach
  void warehouseClock() {
    when(dependencies.readWarehouseIdentity(warehouseId))
        .thenReturn(new LogisticsDependencyGateway.WarehouseIdentity(warehouseId, 0, true, "UTC"));
    when(dependencies.warehouseTimeZoneAt(
            org.mockito.ArgumentMatchers.eq(warehouseId),
            org.mockito.ArgumentMatchers.any(OffsetDateTime.class)))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.WarehouseTimeZone(
                    warehouseId, "UTC", invocation.getArgument(1)));
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
    when(dependencies.readDriverTask(task.getExternalTaskId())).thenReturn(scheduled, promoted);
    when(dependencies.moveDriverTask(task.getExternalTaskId(), 1, 0, "CURRENT", today, 0, null))
        .thenReturn(reordered);

    var response =
        service.move(
            task.getExternalTaskId(),
            new MoveDriverBoardTaskRequest(warehouseId, 0L, 0L, DriverBoardLane.CURRENT, today, 0));

    verify(scheduler).promoteRequested(task.getId());
    verify(dependencies).moveDriverTask(task.getExternalTaskId(), 1, 0, "CURRENT", today, 0, null);
    assertThat(response.lane()).isEqualTo("CURRENT");
    assertThat(response.position()).isZero();
  }

  @Test
  void scheduledRepairDeliveryCannotBeManuallyMovedToCurrent() {
    DriverLogisticsTask task = scheduledTask();
    LogisticsDependencyGateway.DriverBoardTask scheduled = boardTask(task, 0, 0, "SCHEDULED", 4);
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
  void currentInboundMovedBackToDateReleasesItsPlaceAndRefillsImmediately() {
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
            task.getExternalTaskId(), 1, 0, "SCHEDULED", targetDate, 2, null))
        .thenReturn(moved);
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
    doAnswer(
            invocation -> {
              LogisticsDependencyGateway.RepairPlaceAllocation released =
                  invocation.getArgument(1);
              task.releaseRepairPlaceReservation(released.id(), released.version());
              return null;
            })
        .when(workflowStore)
        .confirmReservationRelease(eq(task.getId()), any());
    OffsetDateTime releasedAt = OffsetDateTime.now(ZoneOffset.UTC);
    LogisticsDependencyGateway.RepairPlaceAllocation released =
        new LogisticsDependencyGateway.RepairPlaceAllocation(
            allocationId,
            1,
            warehouseId,
            task.getRepairId(),
            task.getCabinId(),
            "RELEASED",
            null,
            null,
            task.getPriority(),
            releasedAt,
            releasedAt);
    when(workflowStore.nextWork(task.getId()))
        .thenReturn(
            Optional.of(
                new DriverTaskWorkflowStore.ReservationReleaseWork(
                    task.getId(),
                    warehouseId,
                    task.getRepairId(),
                    allocationId,
                    0)),
            Optional.empty());
    when(dependencies.transitionRepairPlace(
            any(), eq(warehouseId), eq(task.getRepairId()), eq(0L), eq("release")))
        .thenReturn(released);
    DriverTaskProcessor immediateProcessor =
        new DriverTaskProcessor(
            workflowStore, dependencies, mock(DriverTransferExecutionService.class));
    DriverBoardService immediateService =
        new DriverBoardService(
            tasks,
            dependencies,
            workflowStore,
            immediateProcessor,
            scheduler,
            driverTaskService,
            tripProjection,
            transactionLock,
            capacityFence);

    immediateService.move(
        task.getExternalTaskId(),
        new MoveDriverBoardTaskRequest(
            warehouseId, 1L, 0L, DriverBoardLane.SCHEDULED, targetDate, 2));

    assertThat(task.hasPendingRepairPlaceRelease()).isFalse();
    assertThat(task.getRepairPlaceAllocationId()).isNull();
    assertThat(task.getRepairPlaceAllocationVersion()).isNull();
    assertThat(task.getPlanningMode()).isEqualTo(DriverTaskPlanningMode.FIXED_DATE);
    assertThat(task.getFixedDateLowerBound()).isEqualTo(targetDate);
    var immediateRefill = inOrder(dependencies, scheduler);
    immediateRefill
        .verify(dependencies)
        .transitionRepairPlace(
            any(), eq(warehouseId), eq(task.getRepairId()), eq(0L), eq("release"));
    immediateRefill.verify(scheduler).reconcileAndPromote(warehouseId);
    verify(tasks).saveAndFlush(task);
  }

  @Test
  void transferMovePreservesIdentityFreeWarehouseAudience() {
    DriverLogisticsTask task = transferTask();
    LogisticsDependencyGateway.DriverBoardTask current = boardTask(task, 1, 0, "SCHEDULED", 0);
    LocalDate targetDate = today.plusDays(1);
    LogisticsDependencyGateway.DriverBoardTask moved =
        boardTask(task, 2, 1, "SCHEDULED", 0, targetDate);
    when(tasks.findForUpdateByExternalTaskId(task.getExternalTaskId()))
        .thenReturn(Optional.of(task));
    when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
    when(dependencies.readDriverTask(task.getExternalTaskId())).thenReturn(current);
    when(dependencies.moveDriverTask(
            task.getExternalTaskId(), 1, 0, "SCHEDULED", targetDate, 0, null))
        .thenReturn(moved);

    service.move(
        task.getExternalTaskId(),
        new MoveDriverBoardTaskRequest(
            warehouseId, 1L, 0L, DriverBoardLane.SCHEDULED, targetDate, 0));

    verify(dependencies)
        .moveDriverTask(task.getExternalTaskId(), 1, 0, "SCHEDULED", targetDate, 0, null);
    verify(capacityFence).acquireDay(warehouseId, targetDate);
  }

  @Test
  void shipmentCanOnlyBeReorderedInsideItsAssignedDriverDateAndLane() {
    UUID driverId = UUID.randomUUID();
    DriverLogisticsTask task = shipmentTask(driverId);
    LogisticsDependencyGateway.DriverTaskAudience assigned =
        new LogisticsDependencyGateway.DriverTaskAudience(
            DriverTaskAudienceMode.ASSIGNED_DRIVER, driverId, "Петров Пётр");
    LogisticsDependencyGateway.DriverBoardTask current =
        boardTask(task, 1, 0, "SCHEDULED", 3, today, assigned);
    LogisticsDependencyGateway.DriverBoardTask moved =
        boardTask(task, 2, 1, "SCHEDULED", 1, today, assigned);
    when(tasks.findForUpdateByExternalTaskId(task.getExternalTaskId()))
        .thenReturn(Optional.of(task));
    when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
    when(dependencies.readDriverTask(task.getExternalTaskId())).thenReturn(current);
    when(dependencies.moveDriverTask(task.getExternalTaskId(), 1, 0, "SCHEDULED", today, 1, null))
        .thenReturn(moved);

    var response =
        service.move(
            task.getExternalTaskId(),
            new MoveDriverBoardTaskRequest(
                warehouseId, 1L, 0L, DriverBoardLane.SCHEDULED, today, 1));

    assertThat(response.position()).isEqualTo(1);
    assertThat(response.driverAudience().workerId()).isEqualTo(driverId);
    verify(dependencies)
        .moveDriverTask(task.getExternalTaskId(), 1, 0, "SCHEDULED", today, 1, null);
  }

  @Test
  void groupedShipmentMovesAcrossDatesAsOneTaskButCannotBeForcedIntoCurrentLane() {
    UUID driverId = UUID.randomUUID();
    DriverLogisticsTask task = shipmentTask(driverId);
    LogisticsDependencyGateway.DriverTaskAudience assigned =
        new LogisticsDependencyGateway.DriverTaskAudience(
            DriverTaskAudienceMode.ASSIGNED_DRIVER, driverId, "Петров Пётр");
    LogisticsDependencyGateway.DriverBoardTask current =
        boardTask(task, 1, 0, "SCHEDULED", 3, today, assigned);
    LogisticsDependencyGateway.DriverBoardTask moved =
        boardTask(task, 2, 1, "SCHEDULED", 0, today.plusDays(1), assigned);
    when(tasks.findForUpdateByExternalTaskId(task.getExternalTaskId()))
        .thenReturn(Optional.of(task));
    when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
    when(dependencies.readDriverTask(task.getExternalTaskId())).thenReturn(current);
    when(dependencies.moveDriverTask(
            task.getExternalTaskId(), 1, 0, "SCHEDULED", today.plusDays(1), 0, null))
        .thenReturn(moved);

    var response =
        service.move(
            task.getExternalTaskId(),
            new MoveDriverBoardTaskRequest(
                warehouseId, 1L, 0L, DriverBoardLane.SCHEDULED, today.plusDays(1), 0));

    assertThat(response.scheduledDate()).isEqualTo(today.plusDays(1));
    verify(dependencies)
        .moveDriverTask(task.getExternalTaskId(), 1, 0, "SCHEDULED", today.plusDays(1), 0, null);
    assertThatThrownBy(
            () ->
                service.move(
                    task.getExternalTaskId(),
                    new MoveDriverBoardTaskRequest(
                        warehouseId, 1L, 0L, DriverBoardLane.CURRENT, today, 0)))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("Текущие задания");
  }

  @Test
  void rejectedWholeTripMoveDoesNotMutateTheLocalTaskOrMembers() {
    UUID driverId = UUID.randomUUID();
    DriverLogisticsTask task = shipmentTask(driverId);
    int memberCount = task.getMembers().size();
    LogisticsDependencyGateway.DriverTaskAudience assigned =
        new LogisticsDependencyGateway.DriverTaskAudience(
            DriverTaskAudienceMode.ASSIGNED_DRIVER, driverId, "Петров Пётр");
    LogisticsDependencyGateway.DriverBoardTask current =
        boardTask(task, 1, 0, "SCHEDULED", 3, today, assigned);
    when(tasks.findForUpdateByExternalTaskId(task.getExternalTaskId()))
        .thenReturn(Optional.of(task));
    when(dependencies.readDriverTask(task.getExternalTaskId())).thenReturn(current);
    when(dependencies.moveDriverTask(
            task.getExternalTaskId(), 1, 0, "SCHEDULED", today.plusDays(1), 0, null))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.PERMANENT_REJECTION, "task has started"));

    assertThatThrownBy(
            () ->
                service.move(
                    task.getExternalTaskId(),
                    new MoveDriverBoardTaskRequest(
                        warehouseId, 1L, 0L, DriverBoardLane.SCHEDULED, today.plusDays(1), 0)))
        .isInstanceOf(LogisticsDependencyException.class);

    assertThat(task.getScheduledDate()).isEqualTo(today);
    assertThat(task.getMembers()).hasSize(memberCount);
    verify(workflowStore, never()).confirmStatus(any(), any());
    verify(tasks, never()).saveAndFlush(task);
  }

  @Test
  void remoteMoveSuccessRemainsRecoverableWhenLocalConfirmationRollsBack() {
    UUID driverId = UUID.randomUUID();
    DriverLogisticsTask task = shipmentTask(driverId);
    LogisticsDependencyGateway.DriverTaskAudience assigned =
        new LogisticsDependencyGateway.DriverTaskAudience(
            DriverTaskAudienceMode.ASSIGNED_DRIVER, driverId, "Петров Пётр");
    LocalDate movedDate = today.plusDays(1);
    LogisticsDependencyGateway.DriverBoardTask current =
        boardTask(task, 1, 0, "SCHEDULED", 3, today, assigned);
    LogisticsDependencyGateway.DriverBoardTask moved =
        boardTask(task, 2, 1, "SCHEDULED", 0, movedDate, assigned);
    when(tasks.findForUpdateByExternalTaskId(task.getExternalTaskId()))
        .thenReturn(Optional.of(task));
    when(dependencies.readDriverTask(task.getExternalTaskId())).thenReturn(current);
    when(dependencies.moveDriverTask(
            task.getExternalTaskId(), 1, 0, "SCHEDULED", movedDate, 0, null))
        .thenReturn(moved);
    doThrow(new IllegalStateException("local commit failed"))
        .doNothing()
        .when(workflowStore)
        .confirmStatus(task.getId(), moved);

    assertThatThrownBy(
            () ->
                service.move(
                    task.getExternalTaskId(),
                    new MoveDriverBoardTaskRequest(
                        warehouseId, 1L, 0L, DriverBoardLane.SCHEDULED, movedDate, 0)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("local commit failed");

    verify(dependencies)
        .moveDriverTask(task.getExternalTaskId(), 1, 0, "SCHEDULED", movedDate, 0, null);
    assertThat(task.getScheduledDate()).isEqualTo(today);

    // The existing status poll reads the authoritative task-board snapshot and repeats the same
    // idempotent local confirmation; DriverTaskWorkflowStoreTest proves the document/date update.
    workflowStore.confirmStatus(task.getId(), moved);
    verify(workflowStore, org.mockito.Mockito.times(2)).confirmStatus(task.getId(), moved);
  }

  @Test
  void localStartedDocumentPreflightPreventsRemoteMoveEvenWhenBoardStillWaits() {
    UUID driverId = UUID.randomUUID();
    DriverLogisticsTask task = shipmentTask(driverId);
    LogisticsDependencyGateway.DriverTaskAudience assigned =
        new LogisticsDependencyGateway.DriverTaskAudience(
            DriverTaskAudienceMode.ASSIGNED_DRIVER, driverId, "Петров Пётр");
    when(tasks.findForUpdateByExternalTaskId(task.getExternalTaskId()))
        .thenReturn(Optional.of(task));
    org.mockito.Mockito.doThrow(new LogisticsConflictException("Начатую ходку нельзя перенести"))
        .when(workflowStore)
        .requireGroupedDocumentMovePreStart(task.getId());

    assertThatThrownBy(
            () ->
                service.move(
                    task.getExternalTaskId(),
                    new MoveDriverBoardTaskRequest(
                        warehouseId, 1L, 0L, DriverBoardLane.SCHEDULED, today.plusDays(1), 0)))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("Начатую ходку");

    verify(dependencies, never()).readDriverTask(any());
    verify(dependencies, never())
        .moveDriverTask(
            any(), any(Long.class), any(Long.class), any(), any(), any(Integer.class), any());
    assertThat(task.getScheduledDate()).isEqualTo(today);
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
        .satisfies(
            card -> {
              assertThat(card.repairId()).isEqualTo(task.getSourceId());
              assertThat(card.assetVersion()).isEqualTo(1);
            });
  }

  @Test
  void boardMergesOverdueScheduledWorkIntoTheWarehouseCurrentDate() {
    DriverLogisticsTask overdue = scheduledTask();
    DriverLogisticsTask current = scheduledTask();
    LogisticsDependencyGateway.DriverBoardTask overdueBoardTask =
        boardTask(overdue, 3, 4, "SCHEDULED", 0, today.minusDays(1));
    LogisticsDependencyGateway.DriverBoardTask currentBoardTask =
        boardTask(current, 1, 2, "SCHEDULED", 0, today);
    when(tasks.findAllByWarehouseIdOrderByCreatedAtAscIdAsc(warehouseId))
        .thenReturn(java.util.List.of(overdue, current));
    when(dependencies.readDriverBoard(warehouseId))
        .thenReturn(
            new LogisticsDependencyGateway.DriverBoardSnapshot(
                warehouseId,
                UUID.randomUUID(),
                0,
                java.util.List.of(),
                java.util.List.of(
                    new LogisticsDependencyGateway.DriverBoardDateColumn(
                        today.minusDays(1), java.util.List.of(overdueBoardTask)),
                    new LogisticsDependencyGateway.DriverBoardDateColumn(
                        today, java.util.List.of(currentBoardTask)))));
    when(dependencies.readRepairPlaces(warehouseId)).thenReturn(repairPlaces());
    when(dependencies.readCapitalRepairs(warehouseId, 0, 200))
        .thenReturn(
            new LogisticsDependencyGateway.CapitalRepairPage(java.util.List.of(), 0, 200, 0));
    when(tripProjection.boardDetails(any())).thenReturn(java.util.Map.of());

    var response = service.board(warehouseId);

    assertThat(response.currentDate()).isEqualTo(today);
    assertThat(response.dates())
        .singleElement()
        .satisfies(
            column -> {
              assertThat(column.date()).isEqualTo(today);
              assertThat(column.tasks()).hasSize(2);
              assertThat(column.tasks())
                  .allSatisfy(card -> assertThat(card.scheduledDate()).isEqualTo(today));
            });
  }

  @Test
  void boardMoveRejectsAPastWarehouseDateBeforeCallingTheRemoteMove() {
    DriverLogisticsTask task = transferTask();
    LogisticsDependencyGateway.DriverBoardTask current =
        boardTask(task, 1, 0, "SCHEDULED", 0);
    when(tasks.findForUpdateByExternalTaskId(task.getExternalTaskId()))
        .thenReturn(Optional.of(task));
    when(dependencies.readDriverTask(task.getExternalTaskId())).thenReturn(current);

    assertThatThrownBy(
            () ->
                service.move(
                    task.getExternalTaskId(),
                    new MoveDriverBoardTaskRequest(
                        warehouseId,
                        1L,
                        0L,
                        DriverBoardLane.SCHEDULED,
                        today.minusDays(1),
                        0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("не может быть в прошлом");

    verify(dependencies, never())
        .moveDriverTask(any(), any(Long.class), any(Long.class), any(), any(), any(Integer.class), any());
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
                warehouseId, 6, 1, 0, 0, 5, false, java.util.List.of(reserved)));
    when(dependencies.readCapitalRepairs(warehouseId, 0, 200))
        .thenReturn(
            new LogisticsDependencyGateway.CapitalRepairPage(java.util.List.of(), 0, 200, 0));
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
                1,
                1,
                1,
                3,
                false,
                java.util.List.of(reserved, occupied, readyToRelease)));
    when(dependencies.readCapitalRepairs(warehouseId, 0, 200))
        .thenReturn(
            new LogisticsDependencyGateway.CapitalRepairPage(java.util.List.of(), 0, 200, 0));
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
    task.registerBoardTask(UUID.randomUUID(), 0, UUID.randomUUID(), "WAITING", "SCHEDULED", null);
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
    task.registerBoardTask(UUID.randomUUID(), 0, UUID.randomUUID(), "WAITING", "SCHEDULED", null);
    return task;
  }

  private DriverLogisticsTask transferTask() {
    UUID cabinId = UUID.randomUUID();
    DriverLogisticsTask task =
        DriverLogisticsTask.createGroupedDocument(
            warehouseId,
            cabinId,
            UUID.randomUUID(),
            DriverTaskKind.TRANSFER,
            today,
            1,
            3,
            "Перемещение между складами",
            null,
            "БЫТ-ПЕР",
            UUID.randomUUID(),
            DriverTaskAudienceMode.WAREHOUSE_DRIVERS,
            null,
            null,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "c".repeat(64));
    task.addGroupedDocumentMember(UUID.randomUUID(), cabinId, "БЫТ-ПЕР", 1);
    ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
    task.registerBoardTask(UUID.randomUUID(), 1, UUID.randomUUID(), "WAITING", "SCHEDULED", null);
    return task;
  }

  private DriverLogisticsTask shipmentTask(UUID driverId) {
    UUID cabinId = UUID.randomUUID();
    DriverLogisticsTask task =
        DriverLogisticsTask.createGroupedDocument(
            warehouseId,
            cabinId,
            UUID.randomUUID(),
            DriverTaskKind.SHIPMENT,
            today,
            1,
            3,
            "Отгрузка бытовки",
            "ООО Клиент",
            "БЫТ-ОТГ",
            UUID.randomUUID(),
            DriverTaskAudienceMode.ASSIGNED_DRIVER,
            driverId,
            "Петров Пётр",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "d".repeat(64));
    task.addGroupedDocumentMember(UUID.randomUUID(), cabinId, "БЫТ-ОТГ", 1);
    ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
    task.registerBoardTask(UUID.randomUUID(), 1, UUID.randomUUID(), "WAITING", "SCHEDULED", null);
    return task;
  }

  private LogisticsDependencyGateway.DriverBoardTask boardTask(
      DriverLogisticsTask task, long taskVersion, long entryVersion, String lane, int position) {
    return boardTask(task, taskVersion, entryVersion, lane, position, today);
  }

  private LogisticsDependencyGateway.DriverBoardTask boardTask(
      DriverLogisticsTask task,
      long taskVersion,
      long entryVersion,
      String lane,
      int position,
      LocalDate scheduledDate) {
    return boardTask(
        task,
        taskVersion,
        entryVersion,
        lane,
        position,
        scheduledDate,
        new LogisticsDependencyGateway.DriverTaskAudience(
            DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null));
  }

  private LogisticsDependencyGateway.DriverBoardTask boardTask(
      DriverLogisticsTask task,
      long taskVersion,
      long entryVersion,
      String lane,
      int position,
      LocalDate scheduledDate,
      LogisticsDependencyGateway.DriverTaskAudience audience) {
    return new LogisticsDependencyGateway.DriverBoardTask(
        task.getTaskBoardTaskId(),
        taskVersion,
        warehouseId,
        task.getExternalTaskId(),
        "Доставить бытовку в ремонт",
        task.getUnitNumber(),
        "Доставить бытовку в ремонт",
        audience,
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
        warehouseId, 6, 0, 0, 0, 6, false, java.util.List.of());
  }
}
