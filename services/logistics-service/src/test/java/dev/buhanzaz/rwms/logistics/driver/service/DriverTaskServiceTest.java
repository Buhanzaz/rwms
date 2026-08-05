package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTaskResponse;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.mapper.DriverTaskResponseMapper;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseOperationMarkStore;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DriverTaskServiceTest {
  private final DriverLogisticsTaskRepository tasks = mock(DriverLogisticsTaskRepository.class);
  private final DriverTaskResponseMapper mapper = mock(DriverTaskResponseMapper.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final LogisticsWarehouseLifecycle warehouseLifecycle =
      mock(LogisticsWarehouseLifecycle.class);
  private final LogisticsWarehouseOperationMarkStore warehouseOperationMarks =
      mock(LogisticsWarehouseOperationMarkStore.class);
  private final DriverTaskService service =
      new DriverTaskService(
          tasks, mapper, dependencies, warehouseLifecycle, warehouseOperationMarks);

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

    DriverTaskService.CreateResult result = service.ensureRemovalTask(warehouseId, repairId, cabinId, 1);

    assertThat(result.replayed()).isTrue();
    assertThat(result.activateNow()).isFalse();
    assertThat(result.response()).isSameAs(response);
    verify(tasks)
        .acquireTransactionLock(
            "driver-task:create:REPAIR_PLACE:" + repairId + ":REMOVE_FROM_REPAIR");
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

    DriverTaskService.CreateResult result = service.ensureRemovalTask(warehouseId, repairId, cabinId, 2);

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

    assertThatThrownBy(
            () -> service.ensureRemovalTask(warehouseId, repairId, UUID.randomUUID(), 3))
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

  private static DriverLogisticsTask currentRemoval(
      UUID warehouseId, UUID repairId, UUID cabinId) {
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
    ReflectionTestUtils.setField(
        task, "scheduledDate", LocalDate.now(ZoneOffset.UTC).minusDays(1));
    task.registerBoardTask(UUID.randomUUID(), 3, UUID.randomUUID(), "WAITING", "CURRENT", null);
    return task;
  }
}
