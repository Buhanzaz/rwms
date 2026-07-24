package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferFurnitureReplacementRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.TransferFurnitureMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CancelEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.repository.TransferFurnitureMovementTaskRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the transfer-specific links, readiness gate and cancellation of cabin furniture tasks. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransferFurnitureTaskService {
  private final TransferFurnitureMovementTaskRepository links;
  private final CabinFurnitureTaskService cabinFurnitureTasks;
  private final EquipmentMovementTaskService movementTasks;

  @Transactional
  public void createForTransfer(
      UUID actorSubjectId,
      LogisticsDocument document,
      LocalDate scheduledDate,
      List<TransferFurnitureReplacementRequest> replacements) {
    if (replacements == null || replacements.isEmpty()) {
      return;
    }
    for (TransferFurnitureReplacementRequest replacement : replacements) {
      var task =
          cabinFurnitureTasks.create(
              actorSubjectId,
              taskIdempotencyKey(document.getId(), replacement.assetId()),
              document.getWarehouseId(),
              replacement.assetId(),
              scheduledDate,
              replacement.contents());
      if (task.taskId() == null) {
        continue;
      }
      links.saveAndFlush(
          TransferFurnitureMovementTask.create(
              document,
              task.rentalItemId(),
              task.unitNumber(),
              task.taskId(),
              task.lineCount()));
    }
  }

  public void requireReadyForDeparture(UUID documentId, UUID rentalItemId) {
    TransferFurnitureMovementTask link =
        links.findByDocument_IdAndRentalItemId(documentId, rentalItemId).orElse(null);
    if (link == null) {
      return;
    }
    var task = movementTasks.required(link.getEquipmentMovementTaskId());
    if (task.getState() == EquipmentMovementTaskState.COMPLETED) {
      return;
    }
    if (task.getState().isTerminal()) {
      throw new LogisticsConflictException(
          "Задание на изменение наполнения бытовки завершилось без выполнения");
    }
    throw new LogisticsConflictException(
        "Перед перемещением завершите задание на изменение наполнения бытовки");
  }

  @Transactional
  public void cancelForTransfer(UUID actorSubjectId, UUID documentId) {
    for (TransferFurnitureMovementTask link : links.findAllByDocument_IdOrderByUnitNumberAsc(documentId)) {
      var task = movementTasks.required(link.getEquipmentMovementTaskId());
      if (task.getState() == EquipmentMovementTaskState.CANCELLING
          || task.getState() == EquipmentMovementTaskState.CANCELLED) {
        continue;
      }
      if (task.getState() != EquipmentMovementTaskState.RESERVING
          && task.getState() != EquipmentMovementTaskState.REGISTERING_TASK
          && task.getState() != EquipmentMovementTaskState.AWAITING_WORKER) {
        throw new LogisticsConflictException(
            "Задание на изменение наполнения больше нельзя отменить");
      }
      movementTasks.cancel(
          actorSubjectId,
          task.getId(),
          cancellationIdempotencyKey(documentId, link.getRentalItemId()),
          new CancelEquipmentMovementTaskRequest(task.getVersion()));
    }
  }

  private static UUID taskIdempotencyKey(UUID documentId, UUID rentalItemId) {
    return UUID.nameUUIDFromBytes(
        ("rwms:transfer-furniture:" + documentId + ":" + rentalItemId)
            .getBytes(StandardCharsets.UTF_8));
  }

  private static UUID cancellationIdempotencyKey(UUID documentId, UUID rentalItemId) {
    return UUID.nameUUIDFromBytes(
        ("rwms:transfer-furniture-cancel:" + documentId + ":" + rentalItemId)
            .getBytes(StandardCharsets.UTF_8));
  }
}
