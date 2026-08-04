package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.MaintenanceDriverTaskCompensationOutcome;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.MaintenanceDriverTaskCompensationResponse;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class MaintenanceDriverTaskCompensationServiceTest {
  private static final String PRE_START_REASON =
      "Компенсация незапущенного логистического перемещения";

  private final DriverLogisticsTaskRepository tasks = mock(DriverLogisticsTaskRepository.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final MaintenanceDriverTaskCompensationService service =
      new MaintenanceDriverTaskCompensationService(tasks, dependencies);

  @Test
  void cancelsOnlyAnUnregisteredLocalIntentAndPreventsProcessorRegistration() {
    UUID repairId = UUID.randomUUID();
    DriverLogisticsTask task = task(repairId);
    when(tasks.findByRepairIdAndKindForUpdate(repairId, DriverTaskKind.DELIVER_TO_REPAIR))
        .thenReturn(List.of(task));

    MaintenanceDriverTaskCompensationResponse result =
        service.cancel(repairId, DriverTaskKind.DELIVER_TO_REPAIR, UUID.randomUUID());

    assertThat(result.outcome()).isEqualTo(MaintenanceDriverTaskCompensationOutcome.CANCELLED);
    assertThat(result.state()).isEqualTo(DriverTaskState.CANCELLED);
    assertThat(task.getState()).isEqualTo(DriverTaskState.CANCELLED);
    verify(tasks).acquireTransactionLock("maintenance-driver-compensation:" + repairId + ":DELIVER_TO_REPAIR");
    verify(tasks).saveAndFlush(task);
    verifyNoInteractions(dependencies);
  }

  @Test
  void guardedCancellationReleasesReservedRepairPlaceOnceThenCancelsLocalTask() {
    UUID repairId = UUID.randomUUID();
    DriverLogisticsTask task = scheduledTask(repairId);
    UUID allocationId = UUID.randomUUID();
    task.reserveRepairPlace(allocationId, 6);
    when(tasks.findByRepairIdAndKindForUpdate(repairId, DriverTaskKind.DELIVER_TO_REPAIR))
        .thenReturn(List.of(task));
    when(
            dependencies.cancelDriverTaskIfPreStart(
                task.getExternalTaskId(), 4, PRE_START_REASON))
        .thenReturn(
            guard(
                task,
                LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome.CANCELLED,
                5,
                "CANCELLED",
                OffsetDateTime.now(ZoneOffset.UTC)));
    when(dependencies.readRepairPlaces(task.getWarehouseId())).thenReturn(reservedPlaces(task));
    when(
            dependencies.transitionRepairPlace(
                any(),
                eq(task.getWarehouseId()),
                eq(repairId),
                eq(6L),
                eq("release")))
        .thenReturn(released(task, 7));

    MaintenanceDriverTaskCompensationResponse result =
        service.cancel(repairId, DriverTaskKind.DELIVER_TO_REPAIR, UUID.randomUUID());

    ArgumentCaptor<UUID> releaseKey = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies)
        .transitionRepairPlace(
            releaseKey.capture(),
            eq(task.getWarehouseId()),
            eq(repairId),
            eq(6L),
            eq("release"));
    assertThat(releaseKey.getValue())
        .isEqualTo(
            UUID.nameUUIDFromBytes(
                ("driver-task:maintenance-compensation-release:" + task.getId())
                    .getBytes(StandardCharsets.UTF_8)));
    assertThat(result.outcome()).isEqualTo(MaintenanceDriverTaskCompensationOutcome.CANCELLED);
    assertThat(result.state()).isEqualTo(DriverTaskState.CANCELLED);
    assertThat(result.taskBoardTaskVersion()).isEqualTo(5);
    assertThat(result.taskBoardStatus()).isEqualTo("CANCELLED");
    assertThat(task.getRepairPlaceAllocationId()).isNull();
    assertThat(task.getRepairPlaceAllocationVersion()).isNull();
    verify(tasks).saveAndFlush(task);
    verify(dependencies, never()).cancelDriverTask(any(), anyLong());
  }

  @Test
  void lostGuardResponseReconcilesThroughAlreadyCancelledAndDoesNotReleaseTwice() {
    UUID repairId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    DriverLogisticsTask task = scheduledTask(repairId);
    task.reserveRepairPlace(UUID.randomUUID(), 6);
    when(tasks.findByRepairIdAndKindForUpdate(repairId, DriverTaskKind.DELIVER_TO_REPAIR))
        .thenReturn(List.of(task));
    when(
            dependencies.cancelDriverTaskIfPreStart(
                task.getExternalTaskId(), 4, PRE_START_REASON))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.TRANSIENT,
                "Task-board response was lost"))
        .thenReturn(
            guard(
                task,
                LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome.ALREADY_CANCELLED,
                5,
                "CANCELLED",
                OffsetDateTime.now(ZoneOffset.UTC)));
    when(dependencies.readRepairPlaces(task.getWarehouseId())).thenReturn(reservedPlaces(task));
    when(
            dependencies.transitionRepairPlace(
                any(),
                eq(task.getWarehouseId()),
                eq(repairId),
                eq(6L),
                eq("release")))
        .thenReturn(released(task, 7));

    MaintenanceDriverTaskCompensationResponse first =
        service.cancel(repairId, DriverTaskKind.DELIVER_TO_REPAIR, idempotencyKey);

    assertThat(first.outcome())
        .isEqualTo(MaintenanceDriverTaskCompensationOutcome.RECONCILIATION_REQUIRED);
    assertThat(task.getState()).isEqualTo(DriverTaskState.RECONCILIATION_REQUIRED);
    verify(dependencies, never()).transitionRepairPlace(any(), any(), any(), anyLong(), any());

    MaintenanceDriverTaskCompensationResponse recovered =
        service.cancel(repairId, DriverTaskKind.DELIVER_TO_REPAIR, idempotencyKey);
    MaintenanceDriverTaskCompensationResponse replay =
        service.cancel(repairId, DriverTaskKind.DELIVER_TO_REPAIR, idempotencyKey);

    assertThat(recovered.outcome()).isEqualTo(MaintenanceDriverTaskCompensationOutcome.CANCELLED);
    assertThat(replay.outcome()).isEqualTo(MaintenanceDriverTaskCompensationOutcome.CANCELLED);
    assertThat(task.getState()).isEqualTo(DriverTaskState.CANCELLED);
    verify(dependencies, times(2))
        .cancelDriverTaskIfPreStart(task.getExternalTaskId(), 4, PRE_START_REASON);
    verify(dependencies, times(1))
        .transitionRepairPlace(any(), eq(task.getWarehouseId()), eq(repairId), eq(6L), eq("release"));
    verify(tasks, times(2)).saveAndFlush(task);
    verify(dependencies, never()).cancelDriverTask(any(), anyLong());
  }

  @Test
  void lostReleaseResponseReconcilesReleasedAllocationWithoutCallingReleaseAgain() {
    UUID repairId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    DriverLogisticsTask task = scheduledTask(repairId);
    task.reserveRepairPlace(UUID.randomUUID(), 6);
    when(tasks.findByRepairIdAndKindForUpdate(repairId, DriverTaskKind.DELIVER_TO_REPAIR))
        .thenReturn(List.of(task));
    when(
            dependencies.cancelDriverTaskIfPreStart(
                task.getExternalTaskId(), 4, PRE_START_REASON))
        .thenReturn(
            guard(
                task,
                LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome.CANCELLED,
                5,
                "CANCELLED",
                OffsetDateTime.now(ZoneOffset.UTC)))
        .thenReturn(
            guard(
                task,
                LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome.ALREADY_CANCELLED,
                5,
                "CANCELLED",
                OffsetDateTime.now(ZoneOffset.UTC)));
    when(dependencies.readRepairPlaces(task.getWarehouseId()))
        .thenReturn(reservedPlaces(task), repairPlaces(task, "RELEASED", 7));
    when(
            dependencies.transitionRepairPlace(
                any(),
                eq(task.getWarehouseId()),
                eq(repairId),
                eq(6L),
                eq("release")))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.TRANSIENT,
                "Maintenance release response was lost"));

    MaintenanceDriverTaskCompensationResponse first =
        service.cancel(repairId, DriverTaskKind.DELIVER_TO_REPAIR, idempotencyKey);
    MaintenanceDriverTaskCompensationResponse recovered =
        service.cancel(repairId, DriverTaskKind.DELIVER_TO_REPAIR, idempotencyKey);

    assertThat(first.outcome())
        .isEqualTo(MaintenanceDriverTaskCompensationOutcome.RECONCILIATION_REQUIRED);
    assertThat(recovered.outcome()).isEqualTo(MaintenanceDriverTaskCompensationOutcome.CANCELLED);
    assertThat(task.getState()).isEqualTo(DriverTaskState.CANCELLED);
    assertThat(task.getRepairPlaceAllocationId()).isNull();
    assertThat(task.getRepairPlaceAllocationVersion()).isNull();
    verify(dependencies, times(2)).readRepairPlaces(task.getWarehouseId());
    verify(dependencies, times(1))
        .transitionRepairPlace(any(), eq(task.getWarehouseId()), eq(repairId), eq(6L), eq("release"));
    verify(dependencies, never()).cancelDriverTask(any(), anyLong());
    verify(tasks, times(2)).saveAndFlush(task);
  }

  @Test
  void guardedCapitalCancellationPreservesRepairPlaceAllocationWithoutReleasingIt() {
    UUID repairId = UUID.randomUUID();
    DriverLogisticsTask task = scheduledCapitalTask(repairId);
    UUID allocationId = UUID.randomUUID();
    ReflectionTestUtils.setField(task, "repairPlaceAllocationId", allocationId);
    ReflectionTestUtils.setField(task, "repairPlaceAllocationVersion", 9L);
    when(tasks.findByRepairIdAndKindForUpdate(repairId, DriverTaskKind.CAPITAL_TO_PRODUCTION))
        .thenReturn(List.of(task));
    when(
            dependencies.cancelDriverTaskIfPreStart(
                task.getExternalTaskId(), 4, PRE_START_REASON))
        .thenReturn(
            guard(
                task,
                LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome.CANCELLED,
                5,
                "CANCELLED",
                OffsetDateTime.now(ZoneOffset.UTC)));

    MaintenanceDriverTaskCompensationResponse result =
        service.cancel(repairId, DriverTaskKind.CAPITAL_TO_PRODUCTION, UUID.randomUUID());

    assertThat(result.outcome()).isEqualTo(MaintenanceDriverTaskCompensationOutcome.CANCELLED);
    assertThat(task.getState()).isEqualTo(DriverTaskState.CANCELLED);
    assertThat(task.getRepairPlaceAllocationId()).isEqualTo(allocationId);
    assertThat(task.getRepairPlaceAllocationVersion()).isEqualTo(9);
    verify(dependencies, never()).readRepairPlaces(any());
    verify(dependencies, never()).transitionRepairPlace(any(), any(), any(), anyLong(), any());
    verify(dependencies, never()).cancelDriverTask(any(), anyLong());
    verify(tasks).saveAndFlush(task);
  }

  @Test
  void guardedDeliveryDoesNotReleaseAnAllocationThatIsNoLongerReserved() {
    UUID repairId = UUID.randomUUID();
    DriverLogisticsTask task = scheduledTask(repairId);
    task.reserveRepairPlace(UUID.randomUUID(), 6);
    when(tasks.findByRepairIdAndKindForUpdate(repairId, DriverTaskKind.DELIVER_TO_REPAIR))
        .thenReturn(List.of(task));
    when(
            dependencies.cancelDriverTaskIfPreStart(
                task.getExternalTaskId(), 4, PRE_START_REASON))
        .thenReturn(
            guard(
                task,
                LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome.CANCELLED,
                5,
                "CANCELLED",
                OffsetDateTime.now(ZoneOffset.UTC)));
    when(dependencies.readRepairPlaces(task.getWarehouseId()))
        .thenReturn(repairPlaces(task, "OCCUPIED", 6));

    MaintenanceDriverTaskCompensationResponse result =
        service.cancel(repairId, DriverTaskKind.DELIVER_TO_REPAIR, UUID.randomUUID());

    assertThat(result.outcome())
        .isEqualTo(MaintenanceDriverTaskCompensationOutcome.RECONCILIATION_REQUIRED);
    assertThat(task.getState()).isEqualTo(DriverTaskState.RECONCILIATION_REQUIRED);
    assertThat(task.getRepairPlaceAllocationId()).isNotNull();
    verify(dependencies, never()).transitionRepairPlace(any(), any(), any(), anyLong(), any());
    verify(dependencies, never()).cancelDriverTask(any(), anyLong());
    verify(tasks).saveAndFlush(task);
  }

  @Test
  void startThatWinsTheGuardRaceIsReportedWithoutCancellationOrRelease() {
    UUID repairId = UUID.randomUUID();
    DriverLogisticsTask task = scheduledTask(repairId);
    task.reserveRepairPlace(UUID.randomUUID(), 6);
    when(tasks.findByRepairIdAndKindForUpdate(repairId, DriverTaskKind.DELIVER_TO_REPAIR))
        .thenReturn(List.of(task));
    when(
            dependencies.cancelDriverTaskIfPreStart(
                task.getExternalTaskId(), 4, PRE_START_REASON))
        .thenReturn(
            guard(
                task,
                LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome.STARTED,
                5,
                "ACTIVE",
                null));

    MaintenanceDriverTaskCompensationResponse result =
        service.cancel(repairId, DriverTaskKind.DELIVER_TO_REPAIR, UUID.randomUUID());

    assertThat(result.outcome()).isEqualTo(MaintenanceDriverTaskCompensationOutcome.STARTED);
    assertThat(result.taskBoardTaskVersion()).isEqualTo(5);
    assertThat(task.getState()).isEqualTo(DriverTaskState.SCHEDULED);
    assertThat(task.getRepairPlaceAllocationId()).isNotNull();
    verify(dependencies, never()).transitionRepairPlace(any(), any(), any(), anyLong(), any());
    verify(dependencies, never()).cancelDriverTask(any(), anyLong());
    verify(tasks, never()).saveAndFlush(task);
  }

  @Test
  void versionConflictReadsFreshWaitingTruthAndPerformsOnlyOneGuardRetry() {
    UUID repairId = UUID.randomUUID();
    DriverLogisticsTask task = scheduledTask(repairId);
    when(tasks.findByRepairIdAndKindForUpdate(repairId, DriverTaskKind.DELIVER_TO_REPAIR))
        .thenReturn(List.of(task));
    when(
            dependencies.cancelDriverTaskIfPreStart(
                task.getExternalTaskId(), 4, PRE_START_REASON))
        .thenReturn(
            guard(
                task,
                LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome.VERSION_CONFLICT,
                5,
                "ACTIVE",
                null));
    when(dependencies.readDriverTask(task.getExternalTaskId()))
        .thenReturn(board(task, "ACTIVE", "CURRENT", "WAITING", 6));
    when(
            dependencies.cancelDriverTaskIfPreStart(
                task.getExternalTaskId(), 6, PRE_START_REASON))
        .thenReturn(
            guard(
                task,
                LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome.CANCELLED,
                7,
                "CANCELLED",
                OffsetDateTime.now(ZoneOffset.UTC)));

    MaintenanceDriverTaskCompensationResponse result =
        service.cancel(repairId, DriverTaskKind.DELIVER_TO_REPAIR, UUID.randomUUID());

    assertThat(result.outcome()).isEqualTo(MaintenanceDriverTaskCompensationOutcome.CANCELLED);
    assertThat(task.getState()).isEqualTo(DriverTaskState.CANCELLED);
    verify(dependencies)
        .cancelDriverTaskIfPreStart(task.getExternalTaskId(), 4, PRE_START_REASON);
    verify(dependencies)
        .cancelDriverTaskIfPreStart(task.getExternalTaskId(), 6, PRE_START_REASON);
    verify(dependencies, never()).transitionRepairPlace(any(), any(), any(), anyLong(), any());
    verify(dependencies, never()).cancelDriverTask(any(), anyLong());
  }

  @Test
  void versionConflictThatRereadsStartedWorkDoesNotIssueAnotherCancellation() {
    UUID repairId = UUID.randomUUID();
    DriverLogisticsTask task = scheduledTask(repairId);
    task.reserveRepairPlace(UUID.randomUUID(), 6);
    when(tasks.findByRepairIdAndKindForUpdate(repairId, DriverTaskKind.DELIVER_TO_REPAIR))
        .thenReturn(List.of(task));
    when(
            dependencies.cancelDriverTaskIfPreStart(
                task.getExternalTaskId(), 4, PRE_START_REASON))
        .thenReturn(
            guard(
                task,
                LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome.VERSION_CONFLICT,
                5,
                "ACTIVE",
                null));
    when(dependencies.readDriverTask(task.getExternalTaskId()))
        .thenReturn(board(task, "ACTIVE", "CURRENT", "IN_PROGRESS", 6));

    MaintenanceDriverTaskCompensationResponse result =
        service.cancel(repairId, DriverTaskKind.DELIVER_TO_REPAIR, UUID.randomUUID());

    assertThat(result.outcome()).isEqualTo(MaintenanceDriverTaskCompensationOutcome.STARTED);
    assertThat(task.getState()).isEqualTo(DriverTaskState.SCHEDULED);
    assertThat(task.getRepairPlaceAllocationId()).isNotNull();
    verify(dependencies)
        .cancelDriverTaskIfPreStart(task.getExternalTaskId(), 4, PRE_START_REASON);
    verify(dependencies, never())
        .cancelDriverTaskIfPreStart(task.getExternalTaskId(), 6, PRE_START_REASON);
    verify(dependencies, never()).transitionRepairPlace(any(), any(), any(), anyLong(), any());
    verify(dependencies, never()).cancelDriverTask(any(), anyLong());
    verify(tasks, never()).saveAndFlush(task);
  }

  @Test
  void secondVersionConflictStopsAtReconciliationWithoutAnUnsafeLoop() {
    UUID repairId = UUID.randomUUID();
    DriverLogisticsTask task = scheduledTask(repairId);
    when(tasks.findByRepairIdAndKindForUpdate(repairId, DriverTaskKind.DELIVER_TO_REPAIR))
        .thenReturn(List.of(task));
    when(
            dependencies.cancelDriverTaskIfPreStart(
                task.getExternalTaskId(), 4, PRE_START_REASON))
        .thenReturn(
            guard(
                task,
                LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome.VERSION_CONFLICT,
                5,
                "ACTIVE",
                null));
    when(dependencies.readDriverTask(task.getExternalTaskId()))
        .thenReturn(board(task, "ACTIVE", "SCHEDULED", "WAITING", 6));
    when(
            dependencies.cancelDriverTaskIfPreStart(
                task.getExternalTaskId(), 6, PRE_START_REASON))
        .thenReturn(
            guard(
                task,
                LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome.VERSION_CONFLICT,
                7,
                "ACTIVE",
                null));

    MaintenanceDriverTaskCompensationResponse result =
        service.cancel(repairId, DriverTaskKind.DELIVER_TO_REPAIR, UUID.randomUUID());

    assertThat(result.outcome())
        .isEqualTo(MaintenanceDriverTaskCompensationOutcome.RECONCILIATION_REQUIRED);
    assertThat(task.getState()).isEqualTo(DriverTaskState.RECONCILIATION_REQUIRED);
    verify(dependencies, times(2))
        .cancelDriverTaskIfPreStart(any(), anyLong(), eq(PRE_START_REASON));
    verify(dependencies, never()).transitionRepairPlace(any(), any(), any(), anyLong(), any());
    verify(dependencies, never()).cancelDriverTask(any(), anyLong());
    verify(tasks).saveAndFlush(task);
  }

  @Test
  void absentRepairMovementHasAStableStructuredOutcome() {
    UUID repairId = UUID.randomUUID();
    when(tasks.findFirstByRepairIdAndKindOrderByCreatedAtDescIdDesc(
            repairId, DriverTaskKind.CAPITAL_TO_PRODUCTION))
        .thenReturn(java.util.Optional.empty());

    MaintenanceDriverTaskCompensationResponse result =
        service.lookup(repairId, DriverTaskKind.CAPITAL_TO_PRODUCTION);

    assertThat(result.outcome()).isEqualTo(MaintenanceDriverTaskCompensationOutcome.ABSENT);
    assertThat(result.taskId()).isNull();
    assertThat(result.state()).isNull();
    verifyNoInteractions(dependencies);
  }

  private static DriverLogisticsTask task(UUID repairId) {
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
            "БЫТ-001",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64));
    ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
    return task;
  }

  private static DriverLogisticsTask scheduledTask(UUID repairId) {
    DriverLogisticsTask task = task(repairId);
    task.registerBoardTask(UUID.randomUUID(), 4, UUID.randomUUID(), "WAITING", "SCHEDULED", null);
    return task;
  }

  private static DriverLogisticsTask scheduledCapitalTask(UUID repairId) {
    DriverLogisticsTask task =
        DriverLogisticsTask.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            repairId,
            DriverTaskSourceType.CAPITAL_REPAIR,
            repairId,
            DriverTaskKind.CAPITAL_TO_PRODUCTION,
            DriverTaskPlanningMode.AUTO,
            LocalDate.now(ZoneOffset.UTC),
            3,
            null,
            "БЫТ-КАП-001",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64));
    ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
    task.registerBoardTask(UUID.randomUUID(), 4, UUID.randomUUID(), "WAITING", "SCHEDULED", null);
    return task;
  }

  private static LogisticsDependencyGateway.DriverTaskPreStartCancellation guard(
      DriverLogisticsTask task,
      LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome outcome,
      long taskVersion,
      String status,
      OffsetDateTime cancelledAt) {
    return new LogisticsDependencyGateway.DriverTaskPreStartCancellation(
        outcome,
        task.getTaskBoardTaskId(),
        task.getExternalTaskId(),
        taskVersion,
        status,
        cancelledAt);
  }

  private static LogisticsDependencyGateway.DriverBoardTask board(
      DriverLogisticsTask task,
      String status,
      String lane,
      String entryStatus,
      long taskVersion) {
    return new LogisticsDependencyGateway.DriverBoardTask(
        task.getTaskBoardTaskId(),
        taskVersion,
        task.getWarehouseId(),
        task.getExternalTaskId(),
        "Доставка в ремонт",
        task.getUnitNumber(),
        "Доставка в ремонт",
        status,
        task.getScheduledDate(),
        lane,
        task.getPriority(),
        false,
        "DONE".equals(entryStatus) ? OffsetDateTime.now(ZoneOffset.UTC) : null,
        task.getTaskBoardEntryId(),
        2,
        entryStatus,
        0);
  }

  private static LogisticsDependencyGateway.RepairPlaceAllocation released(
      DriverLogisticsTask task, long version) {
    return allocation(task, "RELEASED", version);
  }

  private static LogisticsDependencyGateway.RepairPlaceProjection reservedPlaces(
      DriverLogisticsTask task) {
    return repairPlaces(task, "RESERVED", task.getRepairPlaceAllocationVersion());
  }

  private static LogisticsDependencyGateway.RepairPlaceProjection repairPlaces(
      DriverLogisticsTask task, String state, long version) {
    return new LogisticsDependencyGateway.RepairPlaceProjection(
        task.getWarehouseId(),
        3,
        5,
        "RESERVED".equals(state) ? 1 : 0,
        "OCCUPIED".equals(state) ? 1 : 0,
        0,
        2,
        false,
        List.of(allocation(task, state, version)));
  }

  private static LogisticsDependencyGateway.RepairPlaceAllocation allocation(
      DriverLogisticsTask task, String state, long version) {
    return new LogisticsDependencyGateway.RepairPlaceAllocation(
        task.getRepairPlaceAllocationId(),
        version,
        task.getWarehouseId(),
        task.getRepairId(),
        task.getCabinId(),
        state,
        null,
        null,
        task.getPriority(),
        OffsetDateTime.now(ZoneOffset.UTC),
        OffsetDateTime.now(ZoneOffset.UTC));
  }
}
