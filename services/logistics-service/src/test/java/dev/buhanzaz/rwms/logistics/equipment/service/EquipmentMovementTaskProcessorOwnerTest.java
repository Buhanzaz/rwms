package dev.buhanzaz.rwms.logistics.equipment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskOwnerType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EquipmentMovementTaskProcessorOwnerTest {
  private final EquipmentMovementWorkflowStore store = mock(EquipmentMovementWorkflowStore.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final EquipmentMovementTaskProcessor processor =
      new EquipmentMovementTaskProcessor(store, dependencies);

  @Test
  void maintenanceDecisionIdIsUsedForEveryAssetMovementCall() {
    UUID taskId = UUID.randomUUID();
    UUID decisionId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    EquipmentMovementWorkflowStore.ReserveWork reserve =
        new EquipmentMovementWorkflowStore.ReserveWork(
            taskId,
            decisionId,
            EquipmentMovementTaskOwnerType.MAINTENANCE_DISPOSITION,
            lineId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "CABIN_NON_RENTED",
            3L,
            2L,
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
    EquipmentMovementWorkflowStore.ExecuteWork execute =
        new EquipmentMovementWorkflowStore.ExecuteWork(
            taskId,
            decisionId,
            List.of(
                new LogisticsDependencyGateway.EquipmentMovementExecutionRequestLine(
                    reservationId, 4L, lineId, UUID.randomUUID(), null, "STOCK")));
    EquipmentMovementWorkflowStore.ReleaseWork release =
        new EquipmentMovementWorkflowStore.ReleaseWork(taskId, decisionId, lineId, reservationId, 4L);
    AtomicInteger workIndex = new AtomicInteger();
    when(store.nextWork(taskId))
        .thenAnswer(
            ignored ->
                switch (workIndex.getAndIncrement()) {
                  case 0 -> Optional.of(reserve);
                  case 1 -> Optional.of(execute);
                  case 2 -> Optional.of(release);
                  default -> Optional.empty();
                });

    int processed = processor.processUntilIdle(taskId);

    assertThat(processed).isEqualTo(3);
    verify(dependencies)
        .acquireEquipmentMovementReservation(
            eq(lineId),
            eq(decisionId),
            eq(lineId),
            eq(reserve.equipmentId()),
            eq(reserve.sourceWarehouseId()),
            eq(reserve.sourceRentalItemId()),
            eq(reserve.sourceLocationKind()),
            eq(reserve.expectedSourceBalanceVersion()),
            eq(reserve.quantity()),
            eq(reserve.reservedUntil()),
            eq(LogisticsDependencyGateway.EquipmentMovementPurpose.MAINTENANCE_DISPOSITION));
    verify(dependencies).executeEquipmentMovement(eq(taskId), eq(decisionId), eq(execute.lines()));
    verify(dependencies)
        .releaseEquipmentMovementReservation(
            org.mockito.ArgumentMatchers.any(),
            eq(reservationId),
            eq(4L),
            eq(decisionId),
            eq(lineId));
  }

  @Test
  void userRequestedMovementUsesAllocatableRebalancePurpose() {
    UUID taskId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    EquipmentMovementWorkflowStore.ReserveWork reserve =
        new EquipmentMovementWorkflowStore.ReserveWork(
            taskId,
            taskId,
            EquipmentMovementTaskOwnerType.USER_REQUEST,
            lineId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            "STOCK",
            3L,
            2L,
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
    AtomicInteger workIndex = new AtomicInteger();
    when(store.nextWork(taskId))
        .thenAnswer(
            ignored ->
                workIndex.getAndIncrement() == 0 ? Optional.of(reserve) : Optional.empty());

    int processed = processor.processUntilIdle(taskId);

    assertThat(processed).isEqualTo(1);
    verify(dependencies)
        .acquireEquipmentMovementReservation(
            eq(lineId),
            eq(taskId),
            eq(lineId),
            eq(reserve.equipmentId()),
            eq(reserve.sourceWarehouseId()),
            eq(reserve.sourceRentalItemId()),
            eq(reserve.sourceLocationKind()),
            eq(reserve.expectedSourceBalanceVersion()),
            eq(reserve.quantity()),
            eq(reserve.reservedUntil()),
            eq(LogisticsDependencyGateway.EquipmentMovementPurpose.ALLOCATABLE_REBALANCE));
  }
}
