package dev.buhanzaz.rwms.logistics.equipment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementLocationKind;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskLine;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskOwnerType;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskLineRepository;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EquipmentMovementWorkflowStoreOwnerTest {
  private final EquipmentMovementTaskRepository tasks = mock(EquipmentMovementTaskRepository.class);
  private final EquipmentMovementTaskLineRepository lines =
      mock(EquipmentMovementTaskLineRepository.class);
  private final EquipmentMovementWorkflowStore store = new EquipmentMovementWorkflowStore(tasks, lines);

  @Test
  void maintenanceTaskUsesDecisionIdForReservationWorkAndRejectsALocalTaskIdResponse() {
    UUID taskId = UUID.randomUUID();
    UUID decisionId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    EquipmentMovementTask task =
        EquipmentMovementTask.create(
            warehouseId,
            "БЫТ-811",
            30,
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
            UUID.randomUUID(),
            UUID.randomUUID(),
            EquipmentMovementTaskOwnerType.MAINTENANCE_DISPOSITION,
            decisionId,
            "d".repeat(64));
    ReflectionTestUtils.setField(task, "id", taskId);
    EquipmentMovementTaskLine line =
        EquipmentMovementTaskLine.plan(
            task,
            1,
            equipmentId,
            warehouseId,
            cabinId,
            EquipmentMovementLocationKind.CABIN_NON_RENTED,
            4L,
            warehouseId,
            null,
            EquipmentMovementLocationKind.STOCK,
            2L);
    ReflectionTestUtils.setField(line, "id", lineId);
    when(tasks.findForUpdate(taskId)).thenReturn(Optional.of(task));
    when(lines.findAllByTask_IdOrderByLineNumberAsc(taskId)).thenReturn(List.of(line));
    when(lines.findById(lineId)).thenReturn(Optional.of(line));

    EquipmentMovementWorkflowStore.Work next = store.nextWork(taskId).orElseThrow();

    assertThat(next)
        .isEqualTo(
            new EquipmentMovementWorkflowStore.ReserveWork(
                taskId,
                decisionId,
                lineId,
                equipmentId,
                warehouseId,
                cabinId,
                EquipmentMovementLocationKind.CABIN_NON_RENTED.name(),
                4L,
                2L,
                task.getDeadlineAt()));

    assertThatThrownBy(
            () -> store.confirmReservation(taskId, lineId, reservation(taskId, lineId, line, task)))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("mismatched");

    store.confirmReservation(taskId, lineId, reservation(decisionId, lineId, line, task));

    assertThat(line.getReservationId()).isNotNull();
    verify(lines).saveAndFlush(line);
  }

  private static LogisticsDependencyGateway.EquipmentMovementReservation reservation(
      UUID movementId,
      UUID lineId,
      EquipmentMovementTaskLine line,
      EquipmentMovementTask task) {
    return new LogisticsDependencyGateway.EquipmentMovementReservation(
        UUID.randomUUID(),
        0L,
        "LOGISTICS_EQUIPMENT_MOVEMENT",
        movementId,
        lineId,
        line.getEquipmentId(),
        "Стол",
        UUID.randomUUID(),
        line.getSourceWarehouseId(),
        line.getSourceRentalItemId(),
        line.getSourceLocationKind().name(),
        line.getQuantity(),
        "ACTIVE",
        task.getDeadlineAt().minusMinutes(1),
        null);
  }
}
