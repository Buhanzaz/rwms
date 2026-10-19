package dev.buhanzaz.rwms.logistics.equipment.service;

import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderUnitReplacementService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the narrow task-board snapshot and advances due movement reservations. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "rwms.logistics.equipment-movement",
    name = "relay-enabled",
    havingValue = "true")
class EquipmentMovementTaskRelay {
  private static final List<EquipmentMovementTaskState> ACTIVE_STATES =
      List.of(
          EquipmentMovementTaskState.RESERVING,
          EquipmentMovementTaskState.REGISTERING_TASK,
          EquipmentMovementTaskState.AWAITING_WORKER,
          EquipmentMovementTaskState.EXECUTING,
          EquipmentMovementTaskState.CANCELLING);

  private final EquipmentMovementTaskRepository tasks;
  private final EquipmentMovementTaskProcessor processor;
  private final RentalOrderUnitReplacementService replacements;

  @Scheduled(
      fixedDelayString = "${rwms.logistics.equipment-movement.relay-delay:1s}",
      initialDelayString = "${rwms.logistics.equipment-movement.relay-initial-delay:1s}")
  void relayDueTasks() {
    replacements.recoverPending();
    List<UUID> taskIds = tasks.findDueIds(ACTIVE_STATES, OffsetDateTime.now(ZoneOffset.UTC));
    for (UUID taskId : taskIds) {
      if (!replacements.recoverPendingMovement(taskId)) {
        processor.processUntilIdle(taskId);
      }
    }
  }
}
