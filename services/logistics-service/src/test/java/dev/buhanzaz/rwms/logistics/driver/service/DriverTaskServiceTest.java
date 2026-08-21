package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.CreateDriverTaskRequest;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTaskResponse;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.mapper.DriverTaskResponseMapper;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionKind;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseOperationMarkStore;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DriverTaskServiceTest {
  private final DriverLogisticsTaskRepository tasks = mock(DriverLogisticsTaskRepository.class);
  private final DriverTaskResponseMapper mapper = mock(DriverTaskResponseMapper.class);
  private final DriverTripProjectionService tripProjection =
      mock(DriverTripProjectionService.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final LogisticsWarehouseLifecycle warehouseLifecycle =
      mock(LogisticsWarehouseLifecycle.class);
  private final LogisticsWarehouseOperationMarkStore warehouseOperationMarks =
      mock(LogisticsWarehouseOperationMarkStore.class);
  private final LogisticsTransactionLock transactionLock = mock(LogisticsTransactionLock.class);
  private final DriverTaskService service =
      new DriverTaskService(
          tasks,
          mapper,
          tripProjection,
          dependencies,
          warehouseLifecycle,
          warehouseOperationMarks,
          transactionLock);

  @Test
  void rediscoveryReusesExistingCurrentRemovalDespiteChecksumDrift() {
    UUID warehouseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    DriverLogisticsTask existing = currentRemoval(warehouseId, repairId, cabinId);
    DriverTaskResponse response = mock(DriverTaskResponse.class);

    when(tasks.findByCreatedBySubjectIdAndIdempotencyKey(any(), any()))
        .thenReturn(Optional.of(existing));
    when(mapper.toResponse(existing)).thenReturn(response);

    DriverTaskService.CreateResult result =
        service.ensureRemovalTask(warehouseId, repairId, cabinId, 1);

    assertThat(result.replayed()).isTrue();
    assertThat(result.activateNow()).isFalse();
    assertThat(result.response()).isSameAs(response);
    verify(transactionLock)
        .acquire("driver-task:create:REPAIR_PLACE:" + repairId + ":REMOVE_FROM_REPAIR");
    verify(tasks, never())
        .findActiveBySourceTypeAndSourceIdAndKind(
            DriverTaskSourceType.REPAIR_PLACE, repairId, DriverTaskKind.REMOVE_FROM_REPAIR);
    verifyNoInteractions(dependencies);
  }

  @Test
  void rediscoveryReusesMatchingRemovalFoundBySource() {
    UUID warehouseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    DriverLogisticsTask existing = currentRemoval(warehouseId, repairId, cabinId);
    DriverTaskResponse response = mock(DriverTaskResponse.class);

    when(tasks.findByCreatedBySubjectIdAndIdempotencyKey(any(), any()))
        .thenReturn(Optional.empty());
    when(tasks.findActiveBySourceTypeAndSourceIdAndKind(
            DriverTaskSourceType.REPAIR_PLACE, repairId, DriverTaskKind.REMOVE_FROM_REPAIR))
        .thenReturn(Optional.of(existing));
    when(mapper.toResponse(existing)).thenReturn(response);

    DriverTaskService.CreateResult result =
        service.ensureRemovalTask(warehouseId, repairId, cabinId, 2);

    assertThat(result.replayed()).isTrue();
    assertThat(result.response()).isSameAs(response);
    verifyNoInteractions(dependencies);
  }

  @Test
  void rediscoveryRejectsExistingRemovalForAnotherCabin() {
    UUID warehouseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    DriverLogisticsTask conflicting = currentRemoval(warehouseId, repairId, UUID.randomUUID());

    when(tasks.findByCreatedBySubjectIdAndIdempotencyKey(any(), any()))
        .thenReturn(Optional.of(conflicting));

    assertThatThrownBy(() -> service.ensureRemovalTask(warehouseId, repairId, UUID.randomUUID(), 3))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("не совпадает");

    verifyNoInteractions(mapper, dependencies);
  }

  @Test
  void fixedDateCapitalMovementRejectsMissingDateBeforeReplayOrDependencyCalls() {
    assertThatThrownBy(
            () ->
                service.createCapitalMovement(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    DriverTaskPlanningMode.FIXED_DATE,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scheduled date");

    verifyNoInteractions(tasks, dependencies);
  }

  @Test
  void maintenanceIntakeNormalizesOverdueFixedDateToWarehouseToday() {
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    LocalDate today = LocalDate.of(2026, 8, 20);
    CreateDriverTaskRequest request =
        new CreateDriverTaskRequest(
            warehouseId,
            cabinId,
            repairId,
            DriverTaskSourceType.REPAIR,
            repairId,
            DriverTaskKind.DELIVER_TO_REPAIR,
            DriverTaskPlanningMode.FIXED_DATE,
            today.minusDays(3),
            2,
            false,
            "maintenance intake");
    UUID key = UUID.randomUUID();
    when(tasks.findByCreatedBySubjectIdAndIdempotencyKey(DriverTaskService.maintenanceActorId(), key))
        .thenReturn(Optional.empty());
    when(tasks.findActiveBySourceTypeAndSourceIdAndKind(
            request.sourceType(), request.sourceId(), request.kind()))
        .thenReturn(Optional.empty());
    when(dependencies.readWarehouseDriverQueue(warehouseId))
        .thenReturn(new LogisticsDependencyGateway.WarehouseDriverQueue(
            warehouseId, UUID.randomUUID(), UUID.randomUUID()));
    when(dependencies.readRentalItemSnapshot(cabinId))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                cabinId, 1, warehouseId, "БЫТ-1", "AVAILABLE", List.of()));
    when(tasks.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(mapper.toResponse(any())).thenReturn(mock(DriverTaskResponse.class));

    DriverTaskService.CreateResult result =
        service.createFromMaintenance(key, request, admission(warehouseId, today));

    assertThat(result.replayed()).isFalse();
    assertThat(result.response()).isNotNull();
    verify(tasks)
        .saveAndFlush(
            org.mockito.ArgumentMatchers.argThat(
                task ->
                    task.getScheduledDate().equals(today)
                        && task.getPlanningMode() == DriverTaskPlanningMode.FIXED_DATE
                        && task.getSourceType() == DriverTaskSourceType.REPAIR));
  }

  @Test
  void publicCreateStillRejectsOverdueFixedDate() {
    UUID warehouseId = UUID.randomUUID();
    LocalDate today = LocalDate.of(2026, 8, 20);
    CreateDriverTaskRequest request =
        new CreateDriverTaskRequest(
            warehouseId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            DriverTaskSourceType.REPAIR,
            UUID.randomUUID(),
            DriverTaskKind.DELIVER_TO_REPAIR,
            DriverTaskPlanningMode.FIXED_DATE,
            today.minusDays(1),
            2,
            false,
            null);

    assertThatThrownBy(
            () ->
                service.create(
                    UUID.randomUUID(), UUID.randomUUID(), request, admission(warehouseId, today)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("прошлом");
  }

  @Test
  void maintenanceReplayReturnsStoredDateAfterWarehouseDateMovesForward() {
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    LocalDate storedDate = LocalDate.of(2026, 8, 20);
    LocalDate requestedDate = storedDate.minusDays(3);
    CreateDriverTaskRequest originalRequest =
        requestForReplay(warehouseId, cabinId, repairId, requestedDate);
    DriverLogisticsTask existing =
        DriverLogisticsTask.create(
            warehouseId,
            cabinId,
            repairId,
            DriverTaskSourceType.REPAIR,
            repairId,
            DriverTaskKind.DELIVER_TO_REPAIR,
            DriverTaskPlanningMode.FIXED_DATE,
            storedDate,
            2,
            "maintenance intake",
            "БЫТ-1",
            queueId,
            DriverTaskService.maintenanceActorId(),
            key,
            "b".repeat(64));
    ReflectionTestUtils.setField(
        existing,
        "requestSha256",
        ReflectionTestUtils.invokeMethod(
            service,
            "checksum",
            originalRequest,
            "БЫТ-1",
            queueId));
    when(tasks.findByCreatedBySubjectIdAndIdempotencyKey(DriverTaskService.maintenanceActorId(), key))
        .thenReturn(Optional.of(existing));
    when(mapper.toResponse(existing)).thenReturn(mock(DriverTaskResponse.class));

    DriverTaskService.CreateResult result =
        service.createFromMaintenance(
            key, originalRequest, admission(warehouseId, storedDate.plusDays(1)));

    assertThat(result.replayed()).isTrue();
    assertThat(result.response()).isNotNull();
    assertThatThrownBy(
            () ->
                service.createFromMaintenance(
                    key,
                    requestForReplay(warehouseId, cabinId, repairId, requestedDate.minusDays(1)),
                    admission(warehouseId, storedDate.plusDays(1))))
        .isInstanceOf(LogisticsConflictException.class);
    verify(tasks, never()).saveAndFlush(any());
  }

  private static AdmissionTicket admission(UUID warehouseId, LocalDate localDate) {
    List<AdmissionRequirement> requirements =
        List.of(
            new AdmissionRequirement(
                warehouseId,
                LogisticsDependencyGateway.WarehouseOperationDirection.INCOMING));
    return new AdmissionTicket(
        UUID.randomUUID(),
        requirements,
        OffsetDateTime.now(),
        Map.of(warehouseId, localDate),
        true,
        AdmissionKind.TEST_ONLY);
  }

  private static CreateDriverTaskRequest requestForReplay(
      UUID warehouseId, UUID cabinId, UUID repairId, LocalDate scheduledDate) {
    return new CreateDriverTaskRequest(
        warehouseId,
        cabinId,
        repairId,
        DriverTaskSourceType.REPAIR,
        repairId,
        DriverTaskKind.DELIVER_TO_REPAIR,
        DriverTaskPlanningMode.FIXED_DATE,
        scheduledDate,
        2,
        false,
        "maintenance intake");
  }

  private static DriverLogisticsTask currentRemoval(UUID warehouseId, UUID repairId, UUID cabinId) {
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
            "БЫТ-СТАРАЯ",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "b".repeat(64));
    ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(task, "scheduledDate", LocalDate.now(ZoneOffset.UTC).minusDays(1));
    task.registerBoardTask(UUID.randomUUID(), 3, UUID.randomUUID(), "WAITING", "CURRENT", null);
    return task;
  }
}
