package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferFurnitureReplacementRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferFurnitureReadinessState;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferFurnitureReadinessView;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferFurnitureTaskStatusView;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.TransferFurnitureMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CancelEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.mapper.TransferFurnitureTaskResponseMapper;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.TransferFurnitureMovementTaskRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
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
  private final LogisticsDocumentRepository documents;
  private final TransferFurnitureMovementTaskRepository links;
  private final CabinFurnitureTaskService cabinFurnitureTasks;
  private final EquipmentMovementTaskService movementTasks;
  private final TransferFurnitureTaskResponseMapper mapper;

  /**
   * Returns the durable furniture-task readiness for the complete transfer.
   * Departure remains guarded per cabin by {@link #requireReadyForDeparture(UUID, UUID)}.
   */
  public TransferFurnitureReadinessView readiness(UUID documentId) {
    LogisticsDocument transfer = requiredTransfer(documentId);
    List<TransferFurnitureMovementTask> taskLinks =
        links.findAllByDocument_IdOrderByUnitNumberAsc(transfer.getId());
    if (taskLinks.isEmpty()) {
      return new TransferFurnitureReadinessView(
          transfer.getId(),
          transfer.getVersion(),
          TransferFurnitureReadinessState.NOT_REQUIRED,
          List.of());
    }

    boolean awaitingTaskCompletion = false;
    boolean blocked = false;
    List<TransferFurnitureTaskStatusView> tasks = new ArrayList<>(taskLinks.size());
    for (TransferFurnitureMovementTask link : taskLinks) {
      EquipmentMovementTask task = movementTasks.required(link.getEquipmentMovementTaskId());
      tasks.add(mapper.toStatusView(link, task));
      if (task.getState() == EquipmentMovementTaskState.COMPLETED) {
        continue;
      }
      if (task.getState().isTerminal()) {
        blocked = true;
      } else {
        awaitingTaskCompletion = true;
      }
    }

    TransferFurnitureReadinessState state =
        blocked
            ? TransferFurnitureReadinessState.BLOCKED
            : awaitingTaskCompletion
              ? TransferFurnitureReadinessState.AWAITING_TASK_COMPLETION
              : TransferFurnitureReadinessState.READY;
    return new TransferFurnitureReadinessView(
        transfer.getId(), transfer.getVersion(), state, List.copyOf(tasks));
  }

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

  private LogisticsDocument requiredTransfer(UUID documentId) {
    return documents
        .findByIdAndDocumentType(documentId, LogisticsDocumentType.TRANSFER)
        .orElseThrow(LogisticsNotFoundException::new);
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
