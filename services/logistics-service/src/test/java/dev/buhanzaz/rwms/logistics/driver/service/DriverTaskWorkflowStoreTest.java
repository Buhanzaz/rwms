package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DriverTaskWorkflowStoreTest {
  private final DriverLogisticsTaskRepository tasks =
      mock(DriverLogisticsTaskRepository.class);
  private final DriverTaskWorkflowStore store = new DriverTaskWorkflowStore(tasks);

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
    task.registerBoardTask(
        boardTaskId, 0, boardEntryId, "WAITING", "SCHEDULED", null);
    task.reserveRepairPlace(allocationId, 0);
    task.moveToCurrent(1, boardEntryId, "WAITING");
    task.observeBoardTask(
        boardTaskId,
        2,
        boardEntryId,
        "WAITING",
        scheduledDate,
        "SCHEDULED",
        "ACTIVE",
        null);
    task.markManualPromotionHold(5);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));

    DriverTaskWorkflowStore.Work next = store.nextWork(taskId).orElseThrow();

    assertThat(next)
        .isEqualTo(
            new DriverTaskWorkflowStore.ManualReservationReleaseWork(
                taskId, warehouseId, repairId, allocationId, 0));

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    LogisticsDependencyGateway.RepairPlaceAllocation released =
        new LogisticsDependencyGateway.RepairPlaceAllocation(
            allocationId,
            1,
            warehouseId,
            repairId,
            cabinId,
            "RELEASED",
            null,
            null,
            3,
            now,
            now);
    store.confirmManualReservationRelease(taskId, released);

    assertThat(task.getRepairPlaceAllocationId()).isNull();
    assertThat(task.getRepairPlaceAllocationVersion()).isNull();
    assertThat(task.hasManualPromotionHold()).isTrue();
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
    assertThat(task.getRetryCount())
        .isEqualTo(DriverTaskWorkflowStore.MAX_TRANSIENT_RETRY_COUNT);
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
    assertThat(task.getRetryCount())
        .isEqualTo(DriverTaskWorkflowStore.MAX_TRANSIENT_RETRY_COUNT);
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

    when(
            tasks.findFirstByRepairIdAndKindOrderByCreatedAtDescIdDesc(
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
            LogisticsDependencyException.FailureKind.CONFIGURATION, "queue configuration is invalid"));
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
            firstCabinId,
            task.getWarehouseId(),
            mediaId,
            1,
            entryId,
            0,
            now);

    assertThat(store.nextWork(taskId))
        .contains(
            new DriverTaskWorkflowStore.CoverWork(
                taskId, firstCabinId, entryId, mediaId, true));
    store.confirmCover(taskId, firstCover);
    store.confirmCover(taskId, firstCover);

    assertThat(task.isCoverApplied()).isFalse();
    assertThat(task.getMembers().getFirst().isCoverApplied()).isTrue();
    assertThat(task.getMembers().get(1).isCoverApplied()).isFalse();
    assertThat(store.nextWork(taskId))
        .contains(
            new DriverTaskWorkflowStore.CoverWork(
                taskId, secondCabinId, entryId, mediaId, true));

    store.confirmCover(
        taskId,
        new LogisticsDependencyGateway.CabinCoverChange(
            secondCabinId,
            task.getWarehouseId(),
            mediaId,
            1,
            entryId,
            0,
            now));

    assertThat(task.isCoverApplied()).isTrue();
    assertThat(task.getMembers()).allSatisfy(member -> assertThat(member.isCoverApplied()).isTrue());
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
        boardTaskId,
        0,
        entryId,
        "DONE",
        "CURRENT",
        OffsetDateTime.now(ZoneOffset.UTC));
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
