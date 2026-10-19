package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTaskResponse;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifies qualification-fenced, idempotent reservation of shared future driver work. */
class FutureDriverTaskClaimServiceTest {
  private final UUID warehouseId = UUID.randomUUID();
  private final UUID workerId = UUID.randomUUID();
  private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
  private final DriverTaskService tasks = mock(DriverTaskService.class);
  private final DriverTaskWorkflowStore workflow = mock(DriverTaskWorkflowStore.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final FutureDriverTaskClaimService service =
      new FutureDriverTaskClaimService(tasks, workflow, dependencies);

  @BeforeEach
  void warehouseClock() {
    when(dependencies.warehouseTimeZoneAt(
            eq(warehouseId), any(OffsetDateTime.class)))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.WarehouseTimeZone(
                    warehouseId, "UTC", invocation.getArgument(1)));
  }

  @Test
  void claimsSharedFutureTaskThroughTaskBoardAndConfirmsLocalProjection() {
    DriverLogisticsTask task = sharedTask(today.plusDays(1));
    DriverTaskResponse response = mock(DriverTaskResponse.class);
    LogisticsDependencyGateway.DriverBoardTask shared =
        boardTask(
            task,
            4,
            7,
            new LogisticsDependencyGateway.DriverTaskAudience(
                DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null));
    LogisticsDependencyGateway.DriverTaskAudience requestedAudience =
        new LogisticsDependencyGateway.DriverTaskAudience(
            DriverTaskAudienceMode.ASSIGNED_DRIVER, workerId, null);
    LogisticsDependencyGateway.DriverTaskAudience assignedAudience =
        new LogisticsDependencyGateway.DriverTaskAudience(
            DriverTaskAudienceMode.ASSIGNED_DRIVER, workerId, "Иванов Иван");
    LogisticsDependencyGateway.DriverBoardTask assigned =
        boardTask(task, 5, 8, assignedAudience);
    when(tasks.required(task.getId())).thenReturn(task);
    when(tasks.get(task.getId())).thenReturn(response);
    when(dependencies.readDriverTask(task.getExternalTaskId())).thenReturn(shared);
    when(dependencies.moveDriverTask(
            task.getExternalTaskId(),
            shared.taskVersion(),
            shared.entryVersion(),
            "SCHEDULED",
            task.getScheduledDate(),
            shared.queuePosition(),
            requestedAudience))
        .thenReturn(assigned);

    assertThat(service.claim(task.getId(), workerId)).isSameAs(response);

    verify(dependencies)
        .moveDriverTask(
            task.getExternalTaskId(),
            shared.taskVersion(),
            shared.entryVersion(),
            "SCHEDULED",
            task.getScheduledDate(),
            shared.queuePosition(),
            requestedAudience);
    verify(workflow).confirmStatus(task.getId(), assigned);
  }

  @Test
  void retryAfterLostResponseAcceptsExistingAssignmentForSameWorker() {
    DriverLogisticsTask task = sharedTask(today.plusDays(2));
    DriverTaskResponse response = mock(DriverTaskResponse.class);
    LogisticsDependencyGateway.DriverBoardTask assigned =
        boardTask(
            task,
            5,
            8,
            new LogisticsDependencyGateway.DriverTaskAudience(
                DriverTaskAudienceMode.ASSIGNED_DRIVER, workerId, "Иванов Иван"));
    when(tasks.required(task.getId())).thenReturn(task);
    when(tasks.get(task.getId())).thenReturn(response);
    when(dependencies.readDriverTask(task.getExternalTaskId())).thenReturn(assigned);

    assertThat(service.claim(task.getId(), workerId)).isSameAs(response);

    verify(dependencies, never())
        .moveDriverTask(any(), any(Long.class), any(Long.class), any(), any(), any(Integer.class), any());
    verify(workflow).confirmStatus(task.getId(), assigned);
  }

  @Test
  void anotherWorkerCannotClaimAlreadyAssignedTask() {
    DriverLogisticsTask assigned = assignedTask(today.plusDays(1), UUID.randomUUID());
    when(tasks.required(assigned.getId())).thenReturn(assigned);

    assertThatThrownBy(() -> service.claim(assigned.getId(), workerId))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("другой водитель");

    verify(dependencies, never()).readDriverTask(any());
  }

  @Test
  void todaySharedTaskCannotBePreviewedOrClaimed() {
    DriverLogisticsTask task = sharedTask(today);
    when(tasks.required(task.getId())).thenReturn(task);

    assertThat(service.isPreviewable(task)).isFalse();
    assertThatThrownBy(() -> service.claim(task.getId(), workerId))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("будущий день");

    verify(dependencies, never()).readDriverTask(any());
  }

  private DriverLogisticsTask sharedTask(LocalDate date) {
    return task(date, DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null);
  }

  private DriverLogisticsTask assignedTask(LocalDate date, UUID assignedWorkerId) {
    return task(date, DriverTaskAudienceMode.ASSIGNED_DRIVER, assignedWorkerId, "Другой водитель");
  }

  private DriverLogisticsTask task(
      LocalDate date, DriverTaskAudienceMode audienceMode, UUID assignedWorkerId, String name) {
    UUID cabinId = UUID.randomUUID();
    DriverLogisticsTask task =
        DriverLogisticsTask.createGroupedDocument(
            warehouseId,
            cabinId,
            UUID.randomUUID(),
            DriverTaskKind.SHIPMENT,
            date,
            1,
            3,
            "Доставка клиенту",
            "Клиент",
            "БЫТ-101",
            UUID.randomUUID(),
            audienceMode,
            assignedWorkerId,
            name,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64));
    ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
    task.registerBoardTask(UUID.randomUUID(), 4, UUID.randomUUID(), "WAITING", "SCHEDULED", null);
    return task;
  }

  private LogisticsDependencyGateway.DriverBoardTask boardTask(
      DriverLogisticsTask task,
      long taskVersion,
      long entryVersion,
      LogisticsDependencyGateway.DriverTaskAudience audience) {
    return new LogisticsDependencyGateway.DriverBoardTask(
        task.getTaskBoardTaskId(),
        taskVersion,
        warehouseId,
        task.getExternalTaskId(),
        "Доставка клиенту",
        task.getUnitNumber(),
        "Доставка клиенту",
        audience,
        "ACTIVE",
        task.getScheduledDate(),
        "SCHEDULED",
        task.getPriority(),
        false,
        null,
        task.getTaskBoardEntryId(),
        entryVersion,
        "WAITING",
        2);
  }
}
