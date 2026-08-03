package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
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
}
