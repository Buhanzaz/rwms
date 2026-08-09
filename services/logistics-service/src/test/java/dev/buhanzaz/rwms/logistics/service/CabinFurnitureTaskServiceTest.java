package dev.buhanzaz.rwms.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CabinFurnitureRequirement;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CreateEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementLineRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementTaskResponse;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementLocationKind;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionKind;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CabinFurnitureTaskServiceTest {
  private static final UUID ACTOR =
      UUID.fromString("00000000-0000-0000-0000-000000009301");
  private static final UUID IDEMPOTENCY_KEY =
      UUID.fromString("00000000-0000-0000-0000-000000009302");
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000009303");
  private static final UUID RENTAL_ITEM =
      UUID.fromString("00000000-0000-0000-0000-000000009304");
  private static final UUID EQUIPMENT =
      UUID.fromString("00000000-0000-0000-0000-000000009305");
  private static final UUID BALANCE =
      UUID.fromString("00000000-0000-0000-0000-000000009306");
  private static final UUID TASK =
      UUID.fromString("00000000-0000-0000-0000-000000009307");

  @Test
  void changedDesiredCompositionCreatesAWorkerTaskFromTheAssetDelta() {
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    EquipmentMovementTaskService movementTasks = mock(EquipmentMovementTaskService.class);
    LogisticsWarehouseLifecycle warehouseLifecycle = mock(LogisticsWarehouseLifecycle.class);
    CabinFurnitureTaskService service =
        new CabinFurnitureTaskService(dependencies, movementTasks, warehouseLifecycle);
    AdmissionTicket admission = admission();
    when(warehouseLifecycle.disabledTicket(any(), any(), any(), any())).thenReturn(admission);
    var plan = changedPlan();
    when(
            dependencies.planCabinFurnitureMovements(
                WAREHOUSE,
                RENTAL_ITEM,
                List.of(new LogisticsDependencyGateway.CabinFurnitureRequirement(EQUIPMENT, 2L))))
        .thenReturn(plan);
    when(movementTasks.create(eq(ACTOR), eq(IDEMPOTENCY_KEY), any(), eq(admission)))
        .thenReturn(
            new EquipmentMovementTaskService.CreateResult(createdTaskResponse(), false));

    var result =
        service.create(
            ACTOR,
            IDEMPOTENCY_KEY,
            WAREHOUSE,
            RENTAL_ITEM,
            LocalDate.now(ZoneOffset.UTC).plusDays(1),
            List.of(new CabinFurnitureRequirement(EQUIPMENT, 2L)));

    assertThat(result.rentalItemId()).isEqualTo(RENTAL_ITEM);
    assertThat(result.unitNumber()).isEqualTo("БЫТ-930");
    assertThat(result.taskId()).isEqualTo(TASK);
    assertThat(result.lineCount()).isOne();

    ArgumentCaptor<CreateEquipmentMovementTaskRequest> request =
        ArgumentCaptor.forClass(CreateEquipmentMovementTaskRequest.class);
    verify(movementTasks)
        .create(eq(ACTOR), eq(IDEMPOTENCY_KEY), request.capture(), eq(admission));
    assertThat(request.getValue().warehouseId()).isEqualTo(WAREHOUSE);
    assertThat(request.getValue().unitNumber()).isEqualTo("БЫТ-930");
    assertThat(request.getValue().plannedDurationMinutes()).isEqualTo(60);
    assertThat(request.getValue().lines())
        .containsExactly(
            new EquipmentMovementLineRequest(
                EQUIPMENT,
                null,
                EquipmentMovementLocationKind.STOCK,
                4L,
                RENTAL_ITEM,
                EquipmentMovementLocationKind.CABIN_NON_RENTED,
                2L));
  }

  @Test
  void matchingDesiredCompositionDoesNotCreateAnUnneededWorkerTask() {
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    EquipmentMovementTaskService movementTasks = mock(EquipmentMovementTaskService.class);
    LogisticsWarehouseLifecycle warehouseLifecycle = mock(LogisticsWarehouseLifecycle.class);
    CabinFurnitureTaskService service =
        new CabinFurnitureTaskService(dependencies, movementTasks, warehouseLifecycle);
    AdmissionTicket admission = admission();
    when(warehouseLifecycle.disabledTicket(any(), any(), any(), any())).thenReturn(admission);
    when(
            dependencies.planCabinFurnitureMovements(
                WAREHOUSE,
                RENTAL_ITEM,
                List.of(new LogisticsDependencyGateway.CabinFurnitureRequirement(EQUIPMENT, 2L))))
        .thenReturn(
            new LogisticsDependencyGateway.CabinFurnitureMovementPlan(
                RENTAL_ITEM, "БЫТ-930", List.of()));

    var result =
        service.create(
            ACTOR,
            IDEMPOTENCY_KEY,
            WAREHOUSE,
            RENTAL_ITEM,
            LocalDate.now(ZoneOffset.UTC).plusDays(1),
            List.of(new CabinFurnitureRequirement(EQUIPMENT, 2L)));

    assertThat(result.rentalItemId()).isEqualTo(RENTAL_ITEM);
    assertThat(result.unitNumber()).isEqualTo("БЫТ-930");
    assertThat(result.taskId()).isNull();
    assertThat(result.lineCount()).isZero();
    verifyNoInteractions(movementTasks);
    verify(warehouseLifecycle).consume(admission);
  }

  private static AdmissionTicket admission() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    List<AdmissionRequirement> requirements =
        List.of(
            new AdmissionRequirement(
                WAREHOUSE, WarehouseOperationDirection.OUTGOING));
    return new AdmissionTicket(
        UUID.randomUUID(),
        requirements,
        now,
        Map.of(WAREHOUSE, now.toLocalDate()),
        true,
        AdmissionKind.TEST_ONLY);
  }

  private static LogisticsDependencyGateway.CabinFurnitureMovementPlan changedPlan() {
    return new LogisticsDependencyGateway.CabinFurnitureMovementPlan(
        RENTAL_ITEM,
        "БЫТ-930",
        List.of(
            new LogisticsDependencyGateway.CabinFurnitureMovementPlanLine(
                EQUIPMENT,
                "Стул",
                BALANCE,
                WAREHOUSE,
                null,
                "STOCK",
                4L,
                WAREHOUSE,
                RENTAL_ITEM,
                "CABIN_NON_RENTED",
                2L)));
  }

  private static EquipmentMovementTaskResponse createdTaskResponse() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return new EquipmentMovementTaskResponse(
        TASK,
        0L,
        WAREHOUSE,
        null,
        null,
        null,
        null,
        "БЫТ-930",
        null,
        now.plusDays(1),
        EquipmentMovementTaskState.AWAITING_WORKER,
        null,
        null,
        List.of(),
        now,
        now);
  }
}
