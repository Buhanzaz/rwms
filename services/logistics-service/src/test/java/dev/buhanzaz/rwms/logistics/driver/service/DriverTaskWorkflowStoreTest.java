package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.capacity.service.CustomerDeliveryCapacityFence;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.MaintenanceDriverTaskCompensationOutcome;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class DriverTaskWorkflowStoreTest {
  @Test
  void persistedExpiryTakesPrecedenceOverCurrentTransferExecution() {
    UUID id = UUID.randomUUID();
    var task = mock(DriverLogisticsTask.class);
    when(task.getId()).thenReturn(id);
    when(task.getExternalTaskId()).thenReturn(id);
    when(task.getScheduledDate()).thenReturn(LocalDate.of(2026, 9, 4));
    when(task.getState()).thenReturn(DriverTaskState.CURRENT);
    when(task.isDue(any())).thenReturn(true);
    when(task.getTripExpiryRequestedAt()).thenReturn(OffsetDateTime.parse("2026-09-05T00:00:00Z"));
    when(tasks.findForUpdate(id)).thenReturn(Optional.of(task));
    assertThat(store.nextWork(id).orElseThrow())
        .isInstanceOf(DriverTaskWorkflowStore.ExpiryWork.class);
    org.mockito.Mockito.verifyNoInteractions(documents, documentLines);
  }

  private final DriverLogisticsTaskRepository tasks = mock(DriverLogisticsTaskRepository.class);
  private final LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
  private final LogisticsDocumentLineRepository documentLines =
      mock(LogisticsDocumentLineRepository.class);
  private final RentalOrderUnitTermRepository rentalTerms =
      mock(RentalOrderUnitTermRepository.class);
  private final CustomerDeliveryCapacityFence capacityFence =
      mock(CustomerDeliveryCapacityFence.class);
  private final DriverTaskWorkflowStore store =
      new DriverTaskWorkflowStore(
          tasks,
          documents,
          documentLines,
          rentalTerms,
          capacityFence,
          new DriverTaskWorkerContentCodec(new ObjectMapper()));

  @Test
  void reconciliationWithdrawsExpiryOnlyForAnAuthoritativelyRescheduledWaitingTrip() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    for (boolean moved : List.of(false, true)) {
      for (LogisticsDocumentType type : LogisticsDocumentType.values()) {
        UUID warehouseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID cabinId = UUID.randomUUID();
        LocalDate originalDate = today.minusDays(1);
        LocalDate observedDate = moved ? today.plusDays(1) : originalDate;
        LogisticsDocument document = scheduledDocument(type, warehouseId, documentId, originalDate);
        DriverLogisticsTask task =
            DriverLogisticsTask.createGroupedDocument(
                warehouseId,
                cabinId,
                documentId,
                DriverTaskKind.valueOf(type.name()),
                originalDate,
                1,
                3,
                "Ходка",
                "Клиент",
                "1 бытовка",
                UUID.randomUUID(),
                DriverTaskAudienceMode.WAREHOUSE_DRIVERS,
                null,
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "b".repeat(64));
        task.addGroupedDocumentMember(UUID.randomUUID(), cabinId, "БТ-1", 1);
        UUID taskId = UUID.randomUUID();
        UUID boardTaskId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        ReflectionTestUtils.setField(task, "id", taskId);
        task.registerBoardTask(boardTaskId, 0, entryId, "WAITING", "SCHEDULED", null);
        task.requestTripExpiry(today, OffsetDateTime.now(ZoneOffset.UTC));
        task.requireReconciliation("TASK_BOARD_DEPENDENCY_PERMANENT_REJECTION");
        when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
        when(tasks.findById(taskId)).thenReturn(Optional.of(task));
        when(documents.findForUpdate(documentId)).thenReturn(Optional.of(document));
        if (type == LogisticsDocumentType.SHIPMENT) {
          when(documentLines.findAllByDocument_IdOrderByLineNumber(documentId))
              .thenReturn(
                  List.of(
                      LogisticsDocumentLine.create(
                          document, 1, cabinId, 0, "Клиент", null, warehouseId)));
        }

        store.confirmReconciliationStatus(
            taskId,
            new LogisticsDependencyGateway.DriverBoardTask(
                boardTaskId,
                1,
                warehouseId,
                task.getExternalTaskId(),
                "Ходка",
                "1 бытовка",
                "Клиент",
                new LogisticsDependencyGateway.DriverTaskAudience(
                    DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null),
                "ACTIVE",
                observedDate,
                "SCHEDULED",
                3,
                false,
                null,
                entryId,
                1,
                "WAITING",
                0));

        assertThat(task.getScheduledDate()).isEqualTo(observedDate);
        assertThat(document.getScheduledDate()).isEqualTo(observedDate);
        assertThat(task.getTripExpiryRequestedAt() == null)
            .as("%s moved=%s", type, moved)
            .isEqualTo(moved);
        assertThat(task.getState()).isEqualTo(DriverTaskState.SCHEDULED);
        ReflectionTestUtils.setField(
            task, "nextAttemptAt", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        assertThat(store.nextWork(taskId).orElseThrow())
            .isInstanceOf(
                moved
                    ? DriverTaskWorkflowStore.StatusWork.class
                    : DriverTaskWorkflowStore.ExpiryWork.class);
      }
    }
  }

  @Test
  void furnitureCargoTransferUsesTheExistingDriverRegistrationWorkItem() {
    UUID taskId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID workerId = UUID.randomUUID();
    UUID queueDefinitionId = UUID.randomUUID();
    DriverLogisticsTask task =
        DriverLogisticsTask.createFurnitureCargoTransfer(
            warehouseId,
            UUID.randomUUID(),
            LocalDate.of(2026, 8, 30),
            3,
            "Межскладской груз: 12 предметов мебели",
            "12 предметов мебели",
            queueDefinitionId,
            DriverTaskAudienceMode.ASSIGNED_DRIVER,
            workerId,
            "Петров Алексей",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "f".repeat(64));
    ReflectionTestUtils.setField(task, "id", taskId);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));

    DriverTaskWorkflowStore.RegisterWork work =
        (DriverTaskWorkflowStore.RegisterWork) store.nextWork(taskId).orElseThrow();

    assertThat(work.taskId()).isEqualTo(taskId);
    assertThat(work.warehouseId()).isEqualTo(warehouseId);
    assertThat(work.queueDefinitionId()).isEqualTo(queueDefinitionId);
    assertThat(work.title()).isEqualTo("Переместить мебель между складами");
    assertThat(work.description()).isEqualTo("Межскладской груз: 12 предметов мебели");
    assertThat(work.unitNumber()).isEqualTo("12 предметов мебели");
    assertThat(work.driverAudience().mode()).isEqualTo(DriverTaskAudienceMode.ASSIGNED_DRIVER);
    assertThat(work.driverAudience().workerId()).isEqualTo(workerId);
    assertThat(work.workerContent().isEmpty()).isTrue();
  }

  @Test
  void statusRecoveryMovesEveryGroupedDocumentTypeWithoutChangingMembers() {
    LocalDate originalDate = LocalDate.now(ZoneOffset.UTC).plusDays(1);
    LocalDate movedDate = originalDate.plusDays(2);
    UUID warehouseId = UUID.randomUUID();
    for (LogisticsDocumentType type : LogisticsDocumentType.values()) {
      UUID documentId = UUID.randomUUID();
      LogisticsDocument document =
          scheduledDocument(type, warehouseId, documentId, originalDate);
      DriverTaskKind kind = DriverTaskKind.valueOf(type.name());
      UUID cabinId = UUID.randomUUID();
      DriverLogisticsTask task =
          DriverLogisticsTask.createGroupedDocument(
              warehouseId,
              cabinId,
              documentId,
              kind,
              originalDate,
              1,
              3,
              "Ходка",
              "Клиент",
              "1 бытовка",
              UUID.randomUUID(),
              DriverTaskAudienceMode.WAREHOUSE_DRIVERS,
              null,
              null,
              UUID.randomUUID(),
              UUID.randomUUID(),
              "b".repeat(64));
      task.addGroupedDocumentMember(UUID.randomUUID(), cabinId, "БТ-1", 1);
      UUID taskId = UUID.randomUUID();
      UUID boardTaskId = UUID.randomUUID();
      UUID entryId = UUID.randomUUID();
      ReflectionTestUtils.setField(task, "id", taskId);
      task.registerBoardTask(boardTaskId, 0, entryId, "WAITING", "SCHEDULED", null);
      when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
      when(documents.findForUpdate(documentId)).thenReturn(Optional.of(document));
      if (type == LogisticsDocumentType.SHIPMENT) {
        when(documentLines.findAllByDocument_IdOrderByLineNumber(documentId))
            .thenReturn(
                java.util.List.of(
                    LogisticsDocumentLine.create(
                        document, 1, cabinId, 0, "Клиент", null, warehouseId)));
      }

      store.confirmStatus(
          taskId,
          new LogisticsDependencyGateway.DriverBoardTask(
              boardTaskId,
              1,
              warehouseId,
              task.getExternalTaskId(),
              "Ходка",
              "1 бытовка",
              "Клиент",
              new LogisticsDependencyGateway.DriverTaskAudience(
                  DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null),
              "ACTIVE",
              movedDate,
              "SCHEDULED",
              3,
              false,
              null,
              entryId,
              1,
              "WAITING",
              0));

      assertThat(task.getScheduledDate()).isEqualTo(movedDate);
      assertThat(task.getMembers())
          .singleElement()
          .satisfies(member -> assertThat(member.getCabinId()).isEqualTo(cabinId));
      assertThat(document.getScheduledDate()).isEqualTo(movedDate);
    }
    verify(documents, times(3)).saveAndFlush(any(LogisticsDocument.class));
    verify(documentLines).findAllByDocument_IdOrderByLineNumber(any());
    verify(rentalTerms, never()).saveAllAndFlush(any());
    verify(capacityFence, times(3)).acquireDay(warehouseId, movedDate);
  }

  @Test
  void groupedRegionalShipmentReschedulesAgainstItsPhysicalSourceWarehouse() {
    LocalDate originalDate = LocalDate.now(ZoneOffset.UTC).plusDays(1);
    LocalDate movedDate = originalDate.plusDays(1);
    UUID serviceWarehouseId = UUID.randomUUID();
    UUID inventorySourceWarehouseId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    LogisticsDocument document =
        scheduledDocument(
            LogisticsDocumentType.SHIPMENT, serviceWarehouseId, documentId, originalDate);
    LogisticsDocumentLine line =
        LogisticsDocumentLine.create(
            document, 1, cabinId, 0, "Региональный клиент", null, inventorySourceWarehouseId);
    DriverLogisticsTask task =
        DriverLogisticsTask.createGroupedDocument(
            inventorySourceWarehouseId,
            cabinId,
            documentId,
            DriverTaskKind.SHIPMENT,
            originalDate,
            1,
            3,
            "Региональная ходка",
            "Региональный клиент",
            "1 бытовка",
            UUID.randomUUID(),
            DriverTaskAudienceMode.WAREHOUSE_DRIVERS,
            null,
            null,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "d".repeat(64));
    task.addGroupedDocumentMember(UUID.randomUUID(), cabinId, "БТ-1", 1);
    UUID taskId = UUID.randomUUID();
    UUID boardTaskId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    ReflectionTestUtils.setField(task, "id", taskId);
    task.registerBoardTask(boardTaskId, 0, entryId, "WAITING", "SCHEDULED", null);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
    when(documents.findForUpdate(documentId)).thenReturn(Optional.of(document));
    when(documentLines.findAllByDocument_IdOrderByLineNumber(documentId))
        .thenReturn(java.util.List.of(line));

    store.confirmStatus(
        taskId,
        new LogisticsDependencyGateway.DriverBoardTask(
            boardTaskId,
            1,
            inventorySourceWarehouseId,
            task.getExternalTaskId(),
            "Региональная ходка",
            "1 бытовка",
            "Региональный клиент",
            new LogisticsDependencyGateway.DriverTaskAudience(
                DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null),
            "ACTIVE",
            movedDate,
            "SCHEDULED",
            3,
            false,
            null,
            entryId,
            1,
            "WAITING",
            0));

    assertThat(document.getWarehouseId()).isEqualTo(serviceWarehouseId);
    assertThat(document.getScheduledDate()).isEqualTo(movedDate);
    assertThat(task.getWarehouseId()).isEqualTo(inventorySourceWarehouseId);
    assertThat(task.getScheduledDate()).isEqualTo(movedDate);
    verify(capacityFence).acquireDay(inventorySourceWarehouseId, movedDate);
  }

  @Test
  void localStartedDocumentFailsThePreflightWithoutChangingItsDate() {
    LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(1);
    UUID warehouseId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    LogisticsDocument document =
        scheduledDocument(LogisticsDocumentType.SHIPMENT, warehouseId, documentId, date);
    document.beginShipmentPreparation();
    document.awaitShipmentConfirmation();
    document.beginShipmentConfirmation();
    DriverLogisticsTask task =
        DriverLogisticsTask.createGroupedDocument(
            warehouseId,
            UUID.randomUUID(),
            documentId,
            DriverTaskKind.SHIPMENT,
            date,
            1,
            3,
            "Ходка",
            "Клиент",
            "1 бытовка",
            UUID.randomUUID(),
            DriverTaskAudienceMode.WAREHOUSE_DRIVERS,
            null,
            null,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "c".repeat(64));
    UUID taskId = UUID.randomUUID();
    ReflectionTestUtils.setField(task, "id", taskId);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
    when(documents.findForUpdate(documentId)).thenReturn(Optional.of(document));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> store.requireGroupedDocumentMovePreStart(taskId))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("Начатую ходку");

    assertThat(document.getScheduledDate()).isEqualTo(date);
    verify(documents, never()).saveAndFlush(any());
  }

  @Test
  void manualCurrentToDateMoveReleasesInboundReservationButKeepsAutomaticFillPaused() {
    UUID taskId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID boardTaskId = UUID.randomUUID();
    UUID boardEntryId = UUID.randomUUID();
    UUID allocationId = UUID.randomUUID();
    LocalDate scheduledDate = LocalDate.now(ZoneOffset.UTC).plusDays(1);
    DriverLogisticsTask task =
        DriverLogisticsTask.create(
            warehouseId,
            cabinId,
            repairId,
            DriverTaskSourceType.REPAIR,
            repairId,
            DriverTaskKind.DELIVER_TO_REPAIR,
            DriverTaskPlanningMode.AUTO,
            scheduledDate,
            3,
            null,
            "БЫТ-101",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64));
    ReflectionTestUtils.setField(task, "id", taskId);
    task.registerBoardTask(boardTaskId, 0, boardEntryId, "WAITING", "SCHEDULED", null);
    task.reserveRepairPlace(allocationId, 0);
    task.moveToCurrent(1, boardEntryId, "WAITING");
    task.observeBoardTask(
        boardTaskId, 2, boardEntryId, "WAITING", scheduledDate, "SCHEDULED", "ACTIVE", null);
    task.markRepairPlaceReleasePending();
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));

    DriverTaskWorkflowStore.Work next = store.nextWork(taskId).orElseThrow();

    assertThat(next)
        .isEqualTo(
            new DriverTaskWorkflowStore.ReservationReleaseWork(
                taskId, warehouseId, repairId, allocationId, 0));

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    LogisticsDependencyGateway.RepairPlaceAllocation released =
        new LogisticsDependencyGateway.RepairPlaceAllocation(
            allocationId, 1, warehouseId, repairId, cabinId, "RELEASED", null, null, 3, now, now);
    store.confirmReservationRelease(taskId, released);

    assertThat(task.getRepairPlaceAllocationId()).isNull();
    assertThat(task.getRepairPlaceAllocationVersion()).isNull();
    assertThat(task.hasPendingRepairPlaceRelease()).isFalse();
    verify(tasks).saveAndFlush(task);
  }

  @Test
  void transientFailuresRemainRetryableAfterTheCounterSaturates() {
    UUID taskId = UUID.randomUUID();
    DriverLogisticsTask task = repairDelivery(taskId);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

    for (int attempt = 0; attempt < 100; attempt++) {
      store.recordFailure(
          taskId,
          new LogisticsDependencyException(
              LogisticsDependencyException.FailureKind.TRANSIENT, "task-board is unavailable"));
    }
    OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);

    assertThat(task.getState()).isEqualTo(DriverTaskState.REGISTERING);
    assertThat(task.getRetryCount()).isEqualTo(DriverTaskWorkflowStore.MAX_TRANSIENT_RETRY_COUNT);
    assertThat(task.getFailureCode()).isEqualTo("DEPENDENCY_TRANSIENT");
    assertThat(task.getNextAttemptAt())
        .isAfter(before)
        .isBeforeOrEqualTo(
            after.plusSeconds(DriverTaskWorkflowStore.MAX_TRANSIENT_RETRY_DELAY_SECONDS + 1));
    verify(tasks, times(100)).saveAndFlush(task);
  }

  @Test
  void transientBackoffIsSafeForAnAlreadyOverflowedLegacyCounter() {
    UUID taskId = UUID.randomUUID();
    DriverLogisticsTask task = repairDelivery(taskId);
    ReflectionTestUtils.setField(task, "retryCount", Integer.MAX_VALUE);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

    store.recordFailure(
        taskId,
        new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.TRANSIENT, "task-board is unavailable"));
    OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);

    assertThat(task.getState()).isEqualTo(DriverTaskState.REGISTERING);
    assertThat(task.getRetryCount()).isEqualTo(DriverTaskWorkflowStore.MAX_TRANSIENT_RETRY_COUNT);
    assertThat(task.getNextAttemptAt())
        .isAfter(before)
        .isBeforeOrEqualTo(
            after.plusSeconds(DriverTaskWorkflowStore.MAX_TRANSIENT_RETRY_DELAY_SECONDS + 1));
  }

  @Test
  void successfulBoardStatusConfirmationClearsTransientFailureAndRestoresNormalDueTime() {
    UUID taskId = UUID.randomUUID();
    DriverLogisticsTask task = repairDelivery(taskId);
    UUID boardTaskId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    task.registerBoardTask(boardTaskId, 0, entryId, "WAITING", "SCHEDULED", null);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
    store.recordFailure(
        taskId,
        new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.TRANSIENT, "task-board is unavailable"));
    OffsetDateTime beforeConfirmation = OffsetDateTime.now(ZoneOffset.UTC);

    store.confirmStatus(
        taskId,
        new LogisticsDependencyGateway.DriverBoardTask(
            boardTaskId,
            1,
            task.getWarehouseId(),
            task.getExternalTaskId(),
            "Доставить бытовку в ремонт",
            task.getUnitNumber(),
            "Доставить бытовку в ремонт",
            "ACTIVE",
            task.getScheduledDate(),
            "CURRENT",
            task.getPriority(),
            false,
            null,
            entryId,
            1,
            "WAITING",
            0));
    OffsetDateTime afterConfirmation = OffsetDateTime.now(ZoneOffset.UTC);

    assertThat(task.getState()).isEqualTo(DriverTaskState.CURRENT);
    assertThat(task.getRetryCount()).isZero();
    assertThat(task.getFailureCode()).isNull();
    assertThat(task.getNextAttemptAt())
        .isAfter(beforeConfirmation)
        .isBeforeOrEqualTo(afterConfirmation.plusSeconds(2));
    verify(tasks, times(2)).saveAndFlush(task);
  }

  @Test
  void identicalBoardStatusDoesNotAdvanceTheLocalWorkflowVersion() {
    UUID taskId = UUID.randomUUID();
    DriverLogisticsTask task = repairDelivery(taskId);
    UUID boardTaskId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    task.registerBoardTask(boardTaskId, 3, entryId, "WAITING", "SCHEDULED", null);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
    when(tasks.deferStatusPoll(any(), anyLong(), any(), any())).thenReturn(1);
    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

    store.confirmStatus(
        taskId,
        new LogisticsDependencyGateway.DriverBoardTask(
            boardTaskId,
            3,
            task.getWarehouseId(),
            task.getExternalTaskId(),
            "Доставить бытовку в ремонт",
            task.getUnitNumber(),
            "Доставить бытовку в ремонт",
            "ACTIVE",
            task.getScheduledDate(),
            "SCHEDULED",
            task.getPriority(),
            false,
            null,
            entryId,
            12,
            "WAITING",
            4));

    assertThat(task.getState()).isEqualTo(DriverTaskState.SCHEDULED);
    verify(tasks, never()).saveAndFlush(any());
    ArgumentCaptor<OffsetDateTime> nextPoll = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(tasks)
        .deferStatusPoll(
            eq(taskId),
            eq(task.getVersion()),
            eq(DriverTaskState.SCHEDULED),
            nextPoll.capture());
    assertThat(nextPoll.getValue())
        .isAfterOrEqualTo(
            before.plusSeconds(DriverTaskWorkflowStore.UNCHANGED_STATUS_POLL_DELAY_SECONDS))
        .isBeforeOrEqualTo(
            OffsetDateTime.now(ZoneOffset.UTC)
                .plusSeconds(DriverTaskWorkflowStore.UNCHANGED_STATUS_POLL_DELAY_SECONDS + 1));
  }

  @Test
  void identicalRemovalAllocationDoesNotAdvanceTheLocalWorkflowVersion() {
    UUID taskId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID allocationId = UUID.randomUUID();
    DriverLogisticsTask task =
        DriverLogisticsTask.create(
            warehouseId,
            cabinId,
            repairId,
            DriverTaskSourceType.REPAIR_PLACE,
            repairId,
            DriverTaskKind.REMOVE_FROM_REPAIR,
            DriverTaskPlanningMode.AUTO,
            LocalDate.now(ZoneOffset.UTC),
            3,
            null,
            "БЫТ-202",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64));
    ReflectionTestUtils.setField(task, "id", taskId);
    task.bindRemovalRepairPlace(allocationId, 4);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    store.bindReleaseAllocation(
        taskId,
        new LogisticsDependencyGateway.RepairPlaceAllocation(
            allocationId,
            4,
            warehouseId,
            repairId,
            cabinId,
            "READY_TO_RELEASE",
            null,
            null,
            3,
            now,
            now));

    verify(tasks, never()).saveAndFlush(any());
  }

  @Test
  void occupiedTransitionResponseRebindsCompletedCompensationProjectionToItsExactVersion() {
    UUID taskId = UUID.randomUUID();
    UUID reservedAllocationId = UUID.randomUUID();
    UUID occupiedAllocationId = UUID.randomUUID();
    long occupiedAllocationVersion = 11L;
    DriverLogisticsTask task = finalizingRepairDelivery(taskId, reservedAllocationId);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    LogisticsDependencyGateway.RepairPlaceAllocation occupied =
        new LogisticsDependencyGateway.RepairPlaceAllocation(
            occupiedAllocationId,
            occupiedAllocationVersion,
            task.getWarehouseId(),
            task.getRepairId(),
            task.getCabinId(),
            "OCCUPIED",
            null,
            null,
            task.getPriority(),
            now,
            now);

    store.confirmRepairPlaceEffect(taskId, occupied);

    assertThat(task.getRepairPlaceAllocationId()).isEqualTo(occupiedAllocationId);
    assertThat(task.getRepairPlaceAllocationVersion()).isEqualTo(occupiedAllocationVersion);
    assertThat(store.nextWork(taskId)).isEmpty();
    assertThat(task.getState()).isEqualTo(DriverTaskState.COMPLETED);

    when(tasks.findFirstByRepairIdAndKindOrderByCreatedAtDescIdDesc(
            task.getRepairId(), DriverTaskKind.DELIVER_TO_REPAIR))
        .thenReturn(Optional.of(task));
    var compensation =
        new MaintenanceDriverTaskCompensationService(
                tasks, mock(LogisticsDependencyGateway.class), mock(LogisticsTransactionLock.class))
            .lookup(task.getRepairId(), DriverTaskKind.DELIVER_TO_REPAIR);

    assertThat(compensation.outcome())
        .isEqualTo(MaintenanceDriverTaskCompensationOutcome.COMPLETED);
    assertThat(compensation.repairPlaceAllocationId()).isEqualTo(occupiedAllocationId);
    assertThat(compensation.repairPlaceAllocationVersion()).isEqualTo(occupiedAllocationVersion);
    verify(tasks, times(2)).saveAndFlush(task);
  }

  @Test
  void configurationAndPermanentRejectionsStillRequireReconciliation() {
    UUID configurationTaskId = UUID.randomUUID();
    DriverLogisticsTask configurationTask = repairDelivery(configurationTaskId);
    UUID rejectionTaskId = UUID.randomUUID();
    DriverLogisticsTask rejectionTask = repairDelivery(rejectionTaskId);
    when(tasks.findForUpdate(configurationTaskId)).thenReturn(Optional.of(configurationTask));
    when(tasks.findForUpdate(rejectionTaskId)).thenReturn(Optional.of(rejectionTask));

    store.recordFailure(
        configurationTaskId,
        new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.CONFIGURATION,
            "queue configuration is invalid"));
    store.recordFailure(
        rejectionTaskId,
        new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.PERMANENT_REJECTION, "task was rejected"));

    assertThat(configurationTask.getState()).isEqualTo(DriverTaskState.RECONCILIATION_REQUIRED);
    assertThat(configurationTask.getFailureCode()).isEqualTo("DEPENDENCY_CONFIGURATION");
    assertThat(configurationTask.getNextAttemptAt()).isNull();
    assertThat(rejectionTask.getState()).isEqualTo(DriverTaskState.RECONCILIATION_REQUIRED);
    assertThat(rejectionTask.getFailureCode()).isEqualTo("DEPENDENCY_PERMANENT_REJECTION");
    assertThat(rejectionTask.getNextAttemptAt()).isNull();
  }

  @Test
  void coverEffectRejectionCannotBeReopenedFromAnUnrelatedTaskBoardSnapshot() {
    UUID taskId = UUID.randomUUID();
    DriverLogisticsTask task = repairDelivery(taskId);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
    when(tasks.findById(taskId)).thenReturn(Optional.of(task));

    store.recordFailure(
        new DriverTaskWorkflowStore.CoverWork(
            taskId, task.getCabinId(), UUID.randomUUID(), UUID.randomUUID(), false),
        new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
            "media ownership rejected the cover"));

    assertThat(task.getState()).isEqualTo(DriverTaskState.RECONCILIATION_REQUIRED);
    assertThat(task.getFailureCode())
        .isEqualTo("COVER_EFFECT_DEPENDENCY_PERMANENT_REJECTION");
    assertThat(store.recoverableReconciliationExternalTaskId(taskId)).isEmpty();
  }

  @Test
  void genericDependencyReconciliationResumesFromAMatchingActiveBoardTask() {
    UUID taskId = UUID.randomUUID();
    DriverLogisticsTask task = repairDelivery(taskId);
    UUID boardTaskId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    task.registerBoardTask(boardTaskId, 1, entryId, "WAITING", "SCHEDULED", null);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
    when(tasks.findById(taskId)).thenReturn(Optional.of(task));
    store.recordFailure(
        taskId,
        new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
            "temporary remote rejection"));
    LocalDate recoveredDate = LocalDate.now(ZoneOffset.UTC);
    LogisticsDependencyGateway.DriverBoardTask board =
        new LogisticsDependencyGateway.DriverBoardTask(
            boardTaskId,
            2,
            task.getWarehouseId(),
            task.getExternalTaskId(),
            "Доставить бытовку",
            task.getUnitNumber(),
            "Доставить бытовку",
            new LogisticsDependencyGateway.DriverTaskAudience(
                DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null),
            "ACTIVE",
            recoveredDate,
            "SCHEDULED",
            task.getPriority(),
            false,
            null,
            entryId,
            2,
            "WAITING",
            0);

    assertThat(store.recoverableReconciliationExternalTaskId(taskId))
        .contains(task.getExternalTaskId());
    store.confirmReconciliationStatus(taskId, board);

    assertThat(task.getState()).isEqualTo(DriverTaskState.SCHEDULED);
    assertThat(task.getFailureCode()).isNull();
    assertThat(task.getScheduledDate()).isEqualTo(recoveredDate);
    verify(tasks, times(2)).saveAndFlush(task);
  }

  @Test
  void registrationReconciliationBindsAnAlreadyCreatedRemoteTask() {
    UUID taskId = UUID.randomUUID();
    DriverLogisticsTask task = repairDelivery(taskId);
    UUID boardTaskId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
    store.recordFailure(
        taskId,
        new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.CONFIGURATION,
            "registration response was not retained"));
    LocalDate recoveredDate = LocalDate.now(ZoneOffset.UTC);
    LogisticsDependencyGateway.DriverBoardTask board =
        new LogisticsDependencyGateway.DriverBoardTask(
            boardTaskId,
            3,
            task.getWarehouseId(),
            task.getExternalTaskId(),
            "Доставить бытовку",
            task.getUnitNumber(),
            "Доставить бытовку",
            new LogisticsDependencyGateway.DriverTaskAudience(
                DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null),
            "ACTIVE",
            recoveredDate,
            "SCHEDULED",
            task.getPriority(),
            false,
            null,
            entryId,
            4,
            "WAITING",
            0);

    store.confirmReconciliationStatus(taskId, board);

    assertThat(task.getState()).isEqualTo(DriverTaskState.SCHEDULED);
    assertThat(task.getTaskBoardTaskId()).isEqualTo(boardTaskId);
    assertThat(task.getTaskBoardEntryId()).isEqualTo(entryId);
    assertThat(task.getScheduledDate()).isEqualTo(recoveredDate);
    assertThat(task.getFailureCode()).isNull();
  }

  @Test
  void businessReconciliationIsNeverReopenedByTheDependencyRecoveryPass() {
    UUID taskId = UUID.randomUUID();
    DriverLogisticsTask task = repairDelivery(taskId);
    task.requireReconciliation("MAINTENANCE_COMPENSATION_GUARD_UNKNOWN");
    when(tasks.findById(taskId)).thenReturn(Optional.of(task));

    assertThat(store.recoverableReconciliationExternalTaskId(taskId)).isEmpty();
  }

  @Test
  void currentTransferStartsItsFirstPendingCabinLine() {
    UUID taskId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID driverId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    LogisticsDocument document =
        LogisticsDocument.createTransfer(
            warehouseId,
            UUID.randomUUID(),
            LocalDate.now(ZoneOffset.UTC),
            UUID.randomUUID(),
            UUID.randomUUID());
    ReflectionTestUtils.setField(document, "id", documentId);
    LogisticsDocumentLine line =
        LogisticsDocumentLine.create(document, 1, cabinId, 0, null);
    ReflectionTestUtils.setField(line, "id", lineId);
    DriverLogisticsTask task =
        currentTransferTask(taskId, documentId, lineId, warehouseId, cabinId, driverId, false);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
    when(documents.findByIdAndDocumentType(documentId, LogisticsDocumentType.TRANSFER))
        .thenReturn(Optional.of(document));
    when(documentLines.findAllByDocument_IdOrderByLineNumber(documentId))
        .thenReturn(List.of(line));

    assertThat(store.nextWork(taskId))
        .contains(
            new DriverTaskWorkflowStore.TransferDepartureWork(
                taskId,
                driverId,
                documentId,
                lineId,
                document.getVersion(),
                line.getVersion()));
  }

  @Test
  void sharedTransferWithoutAnOwnedDriverKeepsTheExistingTaskBoardStatusFlow() {
    UUID taskId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    DriverLogisticsTask task =
        currentTransferTask(taskId, documentId, lineId, warehouseId, cabinId, null, false);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));

    assertThat(store.nextWork(taskId))
        .hasValueSatisfying(
            work -> assertThat(work).isInstanceOf(DriverTaskWorkflowStore.StatusWork.class));
    verify(documents, never())
        .findByIdAndDocumentType(documentId, LogisticsDocumentType.TRANSFER);
    verify(documentLines, never()).findAllByDocument_IdOrderByLineNumber(documentId);
  }

  @Test
  void completedBoardTaskFreezesCabinCoverBeforeTransferArrival() {
    UUID taskId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID driverId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    LogisticsDocument document =
        LogisticsDocument.createTransfer(
            warehouseId,
            UUID.randomUUID(),
            LocalDate.now(ZoneOffset.UTC),
            UUID.randomUUID(),
            UUID.randomUUID());
    ReflectionTestUtils.setField(document, "id", documentId);
    document.beginTransferDeparture();
    document.markTransferInTransit();
    LogisticsDocumentLine line =
        LogisticsDocumentLine.create(document, 1, cabinId, 0, null);
    ReflectionTestUtils.setField(line, "id", lineId);
    line.beginDeparture();
    line.markDeparted();
    DriverLogisticsTask task =
        currentTransferTask(taskId, documentId, lineId, warehouseId, cabinId, driverId, true);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
    when(documents.findByIdAndDocumentType(documentId, LogisticsDocumentType.TRANSFER))
        .thenReturn(Optional.of(document));
    when(documentLines.findAllByDocument_IdOrderByLineNumber(documentId))
        .thenReturn(List.of(line));

    DriverTaskWorkflowStore.CoverWork cover =
        (DriverTaskWorkflowStore.CoverWork) store.nextWork(taskId).orElseThrow();
    store.confirmCover(
        taskId,
        new LogisticsDependencyGateway.CabinCoverChange(
            cabinId,
            warehouseId,
            task.getCompletionMediaId(),
            task.getCompletionMediaGeneration(),
            task.getCompletionEntryId(),
            1,
            OffsetDateTime.now(ZoneOffset.UTC)));

    DriverTaskWorkflowStore.TransferArrivalWork arrival =
        (DriverTaskWorkflowStore.TransferArrivalWork) store.nextWork(taskId).orElseThrow();

    assertThat(cover.cabinId()).isEqualTo(cabinId);
    assertThat(arrival.taskId()).isEqualTo(taskId);
    assertThat(arrival.actorId()).isEqualTo(driverId);
    assertThat(arrival.documentId()).isEqualTo(documentId);
    assertThat(arrival.lineId()).isEqualTo(lineId);
    assertThat(arrival.mediaId()).isEqualTo(task.getCompletionMediaId());
    assertThat(arrival.mediaGeneration()).isEqualTo(task.getCompletionMediaGeneration());
    verify(tasks).saveAndFlush(task);
  }

  @Test
  void groupedShipmentCompletesOnlyAfterEveryCabinCoverAndRetriesTheSameMemberSafely() {
    UUID taskId = UUID.randomUUID();
    UUID firstCabinId = UUID.randomUUID();
    UUID secondCabinId = UUID.randomUUID();
    UUID boardTaskId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    DriverLogisticsTask task =
        finalizingGroupedShipment(
            taskId, firstCabinId, secondCabinId, boardTaskId, entryId, mediaId);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    LogisticsDependencyGateway.CabinCoverChange firstCover =
        new LogisticsDependencyGateway.CabinCoverChange(
            firstCabinId, task.getWarehouseId(), mediaId, 1, entryId, 0, now);

    assertThat(store.nextWork(taskId))
        .contains(
            new DriverTaskWorkflowStore.CoverWork(taskId, firstCabinId, entryId, mediaId, true));
    store.confirmCover(taskId, firstCover);
    store.confirmCover(taskId, firstCover);

    assertThat(task.isCoverApplied()).isFalse();
    assertThat(task.getMembers().getFirst().isCoverApplied()).isTrue();
    assertThat(task.getMembers().get(1).isCoverApplied()).isFalse();
    assertThat(store.nextWork(taskId))
        .contains(
            new DriverTaskWorkflowStore.CoverWork(taskId, secondCabinId, entryId, mediaId, true));

    store.confirmCover(
        taskId,
        new LogisticsDependencyGateway.CabinCoverChange(
            secondCabinId, task.getWarehouseId(), mediaId, 1, entryId, 0, now));

    assertThat(task.isCoverApplied()).isTrue();
    assertThat(task.getMembers())
        .allSatisfy(member -> assertThat(member.isCoverApplied()).isTrue());
    assertThat(store.nextWork(taskId)).isEmpty();
    assertThat(task.getState()).isEqualTo(DriverTaskState.COMPLETED);
  }

  @Test
  void groupedShipmentRegistersAPluralTitleCountSummaryAndFullClientCabinText() {
    UUID taskId = UUID.randomUUID();
    UUID firstCabinId = UUID.randomUUID();
    UUID secondCabinId = UUID.randomUUID();
    DriverLogisticsTask task = registeringGroupedShipment(taskId, firstCabinId, secondCabinId);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));

    DriverTaskWorkflowStore.Work work = store.nextWork(taskId).orElseThrow();
    assertThat(work)
        .isInstanceOfSatisfying(
            DriverTaskWorkflowStore.RegisterWork.class,
            registration -> {
              assertThat(registration.title()).isEqualTo("Отгрузить бытовки");
              assertThat(registration.unitNumber()).isEqualTo("2 бытовки");
              assertThat(registration.description())
                  .isEqualTo("Клиент: ООО Клиент. Бытовки: БТ-301, БТ-302");
              assertThat(registration.priority()).isEqualTo(3);
            });
  }

  private static DriverLogisticsTask repairDelivery(UUID taskId) {
    UUID repairId = UUID.randomUUID();
    DriverLogisticsTask task =
        DriverLogisticsTask.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            repairId,
            DriverTaskSourceType.REPAIR,
            repairId,
            DriverTaskKind.DELIVER_TO_REPAIR,
            DriverTaskPlanningMode.AUTO,
            LocalDate.now(ZoneOffset.UTC),
            3,
            null,
            "БЫТ-201",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64));
    ReflectionTestUtils.setField(task, "id", taskId);
    return task;
  }

  private static DriverLogisticsTask currentTransferTask(
      UUID taskId,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      UUID cabinId,
      UUID driverId,
      boolean done) {
    DriverLogisticsTask task =
        DriverLogisticsTask.createGroupedDocument(
            warehouseId,
            cabinId,
            documentId,
            DriverTaskKind.TRANSFER,
            LocalDate.now(ZoneOffset.UTC),
            1,
            3,
            "Межскладское перемещение",
            null,
            "1 бытовка",
            UUID.randomUUID(),
            driverId == null
                ? DriverTaskAudienceMode.WAREHOUSE_DRIVERS
                : DriverTaskAudienceMode.ASSIGNED_DRIVER,
            driverId,
            driverId == null ? null : "Петров Алексей",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "d".repeat(64));
    ReflectionTestUtils.setField(task, "id", taskId);
    task.addGroupedDocumentMember(lineId, cabinId, "БТ-172", 1);
    UUID boardTaskId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    task.registerBoardTask(boardTaskId, 0, entryId, "WAITING", "SCHEDULED", null);
    task.moveToCurrent(1, entryId, "WAITING");
    if (done) {
      task.observeBoardTask(
          boardTaskId,
          2,
          entryId,
          "DONE",
          task.getScheduledDate(),
          "CURRENT",
          "DONE",
          OffsetDateTime.now(ZoneOffset.UTC));
      task.captureEvidence(UUID.randomUUID(), UUID.randomUUID(), 2, entryId);
    }
    ReflectionTestUtils.setField(
        task, "nextAttemptAt", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
    return task;
  }

  private static LogisticsDocument scheduledDocument(
      LogisticsDocumentType type,
      UUID warehouseId,
      UUID documentId,
      LocalDate date) {
    UUID subjectId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    LogisticsDocument document =
        switch (type) {
          case SHIPMENT -> {
            LogisticsDocument shipment =
                LogisticsDocument.createShipment(
                    warehouseId, "Клиент", "Водитель", subjectId, correlationId);
            shipment.scheduleShipment("Водитель", null, date);
            yield shipment;
          }
          case RETURN -> {
            LogisticsDocument pickup =
                LogisticsDocument.createReturn(warehouseId, subjectId, correlationId);
            pickup.scheduleReturn("Водитель", null, date);
            yield pickup;
          }
          case TRANSFER ->
              LogisticsDocument.createTransfer(
                  warehouseId, UUID.randomUUID(), date, subjectId, correlationId);
        };
    ReflectionTestUtils.setField(document, "id", documentId);
    return document;
  }

  private static DriverLogisticsTask finalizingRepairDelivery(
      UUID taskId, UUID reservedAllocationId) {
    DriverLogisticsTask task = repairDelivery(taskId);
    UUID boardTaskId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    task.registerBoardTask(boardTaskId, 0, entryId, "WAITING", "SCHEDULED", null);
    task.reserveRepairPlace(reservedAllocationId, 4);
    task.moveToCurrent(1, entryId, "WAITING");
    task.observeBoardTask(
        boardTaskId,
        2,
        entryId,
        "DONE",
        task.getScheduledDate(),
        "CURRENT",
        "DONE",
        OffsetDateTime.now(ZoneOffset.UTC));
    task.captureEvidence(UUID.randomUUID(), UUID.randomUUID(), 1, entryId);
    task.markCoverApplied();
    return task;
  }

  private static DriverLogisticsTask finalizingGroupedShipment(
      UUID taskId,
      UUID firstCabinId,
      UUID secondCabinId,
      UUID boardTaskId,
      UUID entryId,
      UUID mediaId) {
    DriverLogisticsTask task = registeringGroupedShipment(taskId, firstCabinId, secondCabinId);
    task.registerBoardTask(
        boardTaskId, 0, entryId, "DONE", "CURRENT", OffsetDateTime.now(ZoneOffset.UTC));
    task.captureEvidence(UUID.randomUUID(), mediaId, 1, entryId);
    return task;
  }

  private static DriverLogisticsTask registeringGroupedShipment(
      UUID taskId, UUID firstCabinId, UUID secondCabinId) {
    UUID warehouseId = UUID.randomUUID();
    DriverLogisticsTask task =
        DriverLogisticsTask.createGroupedShipment(
            warehouseId,
            firstCabinId,
            UUID.randomUUID(),
            LocalDate.now(ZoneOffset.UTC),
            3,
            "Клиент: ООО Клиент. Бытовки: БТ-301, БТ-302",
            "ООО Клиент",
            "2 бытовки",
            UUID.randomUUID(),
            DriverTaskAudienceMode.ASSIGNED_DRIVER,
            UUID.randomUUID(),
            "Иван Петров",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "c".repeat(64));
    ReflectionTestUtils.setField(task, "id", taskId);
    task.addGroupedShipmentMember(UUID.randomUUID(), firstCabinId, "БТ-301", 1);
    task.addGroupedShipmentMember(UUID.randomUUID(), secondCabinId, "БТ-302", 2);
    return task;
  }
}
