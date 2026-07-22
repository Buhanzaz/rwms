package dev.buhanzaz.rwms.logistics.equipment.service;

import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CancelEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CreateEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementLineRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementTaskResponse;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskLine;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementLocationKind;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.mapper.EquipmentMovementTaskResponseMapper;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskLineRepository;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public command/query boundary for worker-mediated equipment movements. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentMovementTaskService {
  private static final String CREATE_OPERATION = "CREATE_EQUIPMENT_MOVEMENT_TASK";
  private static final String CANCEL_OPERATION = "CANCEL_EQUIPMENT_MOVEMENT_TASK";

  private final EquipmentMovementTaskRepository tasks;
  private final EquipmentMovementTaskLineRepository lines;
  private final EquipmentMovementTaskResponseMapper mapper;

  public EquipmentMovementTaskResponse get(UUID taskId) {
    EquipmentMovementTask task = required(taskId);
    return response(task, lines.findAllByTask_IdOrderByLineNumberAsc(taskId));
  }

  public EquipmentMovementTask required(UUID taskId) {
    return tasks
        .findById(taskId)
        .orElseThrow(LogisticsNotFoundException::new);
  }

  @Transactional
  public CreateResult create(
      UUID actorSubjectId, UUID idempotencyKey, CreateEquipmentMovementTaskRequest request) {
    if (actorSubjectId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Movement actor, request and Idempotency-Key are required");
    }
    validateRequest(request);
    String checksum = creationChecksum(request);
    tasks.acquireTransactionLock("equipment-movement:create:" + actorSubjectId + ":" + idempotencyKey);
    EquipmentMovementTask replay =
        tasks.findByCreatedBySubjectIdAndIdempotencyKey(actorSubjectId, idempotencyKey).orElse(null);
    if (replay != null) {
      if (!replay.matchesRequest(checksum)) {
        throw new LogisticsConflictException("Idempotency-Key is already used for a different movement task");
      }
      return new CreateResult(
          response(replay, lines.findAllByTask_IdOrderByLineNumberAsc(replay.getId())), true);
    }

    EquipmentMovementTask task =
        tasks.saveAndFlush(
            EquipmentMovementTask.create(
                request.warehouseId(),
                request.unitNumber(),
                request.plannedDurationMinutes(),
                request.deadlineAt(),
                actorSubjectId,
                idempotencyKey,
                checksum));
    UUID targetWarehouseId =
        request.targetWarehouseId() == null ? request.warehouseId() : request.targetWarehouseId();
    List<EquipmentMovementTaskLine> planned = new ArrayList<>();
    int lineNumber = 1;
    for (EquipmentMovementLineRequest line : request.lines()) {
      planned.add(
          EquipmentMovementTaskLine.plan(
              task,
              lineNumber++,
              line.equipmentId(),
              request.warehouseId(),
              line.sourceRentalItemId(),
              line.sourceLocationKind(),
              line.expectedSourceBalanceVersion(),
              targetWarehouseId,
              line.targetRentalItemId(),
              line.targetLocationKind(),
              line.quantity()));
    }
    List<EquipmentMovementTaskLine> savedLines = lines.saveAllAndFlush(planned);
    return new CreateResult(response(task, savedLines), false);
  }

  @Transactional
  public MutationResult cancel(
      UUID actorSubjectId,
      UUID taskId,
      UUID idempotencyKey,
      CancelEquipmentMovementTaskRequest request) {
    if (actorSubjectId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Movement cancellation actor, request and Idempotency-Key are required");
    }
    String checksum =
        EquipmentMovementTaskChecksum.sha256(
            CANCEL_OPERATION,
            List.of(taskId.toString(), Long.toString(request.expectedVersion())));
    tasks.acquireTransactionLock("equipment-movement:cancel:" + actorSubjectId + ":" + idempotencyKey);
    EquipmentMovementTask task =
        tasks
            .findForUpdate(taskId)
            .orElseThrow(LogisticsNotFoundException::new);
    if (task.matchesCancellationRequest(actorSubjectId, idempotencyKey, checksum)) {
      return new MutationResult(
          response(task, lines.findAllByTask_IdOrderByLineNumberAsc(taskId)), true);
    }
    if (task.getVersion() != request.expectedVersion()) {
      throw new LogisticsConflictException("Equipment movement task has changed");
    }
    if (task.getState().isTerminal() || task.getState() == EquipmentMovementTaskState.EXECUTING) {
      throw new LogisticsConflictException("Equipment movement task can no longer be cancelled");
    }
    task.requestCancellation(
        actorSubjectId,
        idempotencyKey,
        checksum,
        EquipmentMovementTaskState.CANCELLED,
        "CANCELLED_BY_USER");
    tasks.saveAndFlush(task);
    return new MutationResult(
        response(task, lines.findAllByTask_IdOrderByLineNumberAsc(taskId)), false);
  }

  public EquipmentMovementTaskResponse response(UUID taskId) {
    return get(taskId);
  }

  private EquipmentMovementTaskResponse response(
      EquipmentMovementTask task, List<EquipmentMovementTaskLine> taskLines) {
    EquipmentMovementTaskResponse base = mapper.toResponse(task);
    return new EquipmentMovementTaskResponse(
        base.id(),
        base.version(),
        base.warehouseId(),
        base.externalTaskId(),
        base.taskBoardTaskId(),
        base.taskBoardTaskVersion(),
        base.taskBoardDoneAt(),
        base.unitNumber(),
        base.plannedDurationMinutes(),
        base.deadlineAt(),
        base.state(),
        base.terminalState(),
        base.failureCode(),
        taskLines.stream().map(mapper::toLineResponse).toList(),
        base.createdAt(),
        base.updatedAt());
  }

  private static void validateRequest(CreateEquipmentMovementTaskRequest request) {
    if (request.warehouseId() == null
        || request.deadlineAt() == null
        || !request.deadlineAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))
        || request.lines() == null
        || request.lines().isEmpty()) {
      throw new IllegalArgumentException("Equipment movement task request is invalid");
    }
    Set<String> uniqueSources = new HashSet<>();
    int workerOperationCount = 0;
    for (EquipmentMovementLineRequest line : request.lines()) {
      if (line == null
          || line.equipmentId() == null
          || line.sourceLocationKind() == null
          || line.expectedSourceBalanceVersion() == null
          || line.targetLocationKind() == null
          || line.quantity() == null) {
        throw new IllegalArgumentException("Equipment movement line is invalid");
      }
      String sourceKey =
          line.equipmentId()
              + ":"
              + line.sourceRentalItemId()
              + ":"
              + line.sourceLocationKind();
      if (!uniqueSources.add(sourceKey)) {
        throw new IllegalArgumentException("Equipment movement may contain each source balance only once");
      }
      workerOperationCount +=
          line.sourceLocationKind() == EquipmentMovementLocationKind.STOCK
                  || line.targetLocationKind() == EquipmentMovementLocationKind.STOCK
              ? 1
              : 2;
      if (workerOperationCount > 10) {
        throw new IllegalArgumentException("Equipment movement has too many worker operations");
      }
    }
  }

  private static String creationChecksum(CreateEquipmentMovementTaskRequest request) {
    List<String> values = new ArrayList<>();
    values.add(request.warehouseId().toString());
    values.add(
        request.targetWarehouseId() == null ? null : request.targetWarehouseId().toString());
    values.add(request.unitNumber());
    values.add(request.plannedDurationMinutes() == null ? null : request.plannedDurationMinutes().toString());
    values.add(request.deadlineAt().toString());
    for (EquipmentMovementLineRequest line : request.lines()) {
      values.add(line.equipmentId().toString());
      values.add(line.sourceRentalItemId() == null ? null : line.sourceRentalItemId().toString());
      values.add(line.sourceLocationKind().name());
      values.add(line.expectedSourceBalanceVersion().toString());
      values.add(line.targetRentalItemId() == null ? null : line.targetRentalItemId().toString());
      values.add(line.targetLocationKind().name());
      values.add(line.quantity().toString());
    }
    return EquipmentMovementTaskChecksum.sha256(CREATE_OPERATION, values);
  }

  public record CreateResult(EquipmentMovementTaskResponse response, boolean replayed) {}

  public record MutationResult(EquipmentMovementTaskResponse response, boolean replayed) {}
}
