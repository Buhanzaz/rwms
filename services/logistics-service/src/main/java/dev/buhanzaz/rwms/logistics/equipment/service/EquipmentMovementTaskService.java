package dev.buhanzaz.rwms.logistics.equipment.service;

import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CancelEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CreateEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CreateMaintenanceEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementLineRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementTaskResponse;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.MaintenanceEquipmentMovementLineRequest;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskLine;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementLocationKind;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskLimits;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskOwnerType;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.mapper.EquipmentMovementTaskResponseMapper;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskLineRepository;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseOperationMarkStore;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
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
  private static final UUID MAINTENANCE_ACTOR =
      UUID.nameUUIDFromBytes("rwms:maintenance-service".getBytes(StandardCharsets.UTF_8));

  private final EquipmentMovementTaskRepository tasks;
  private final EquipmentMovementTaskLineRepository lines;
  private final EquipmentMovementTaskResponseMapper mapper;
  private final LogisticsWarehouseLifecycle warehouseLifecycle;
  private final LogisticsWarehouseOperationMarkStore warehouseOperationMarks;
  private final LogisticsTransactionLock transactionLock;

  public EquipmentMovementTaskResponse get(UUID taskId) {
    EquipmentMovementTask task = required(taskId);
    return response(task, lines.findAllByTask_IdOrderByLineNumberAsc(taskId));
  }

  /** Returns only a task owned by the maintenance disposition that requested it. */
  public EquipmentMovementTaskResponse getMaintenance(UUID taskId) {
    EquipmentMovementTask task = required(taskId);
    if (task.getOwnerType() != EquipmentMovementTaskOwnerType.MAINTENANCE_DISPOSITION) {
      throw new LogisticsNotFoundException();
    }
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
    return create(
        actorSubjectId,
        idempotencyKey,
        request,
        warehouseLifecycle.disabledTicket(
            actorSubjectId,
            CREATE_OPERATION,
            idempotencyKey,
            admissionRequirements(request)));
  }

  @Transactional
  public CreateResult create(
      UUID actorSubjectId,
      UUID idempotencyKey,
      CreateEquipmentMovementTaskRequest request,
      AdmissionTicket admission) {
    if (actorSubjectId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Movement actor, request and Idempotency-Key are required");
    }
    return createInternal(
        actorSubjectId,
        idempotencyKey,
        request,
        EquipmentMovementTaskOwnerType.USER_REQUEST,
        null,
        admission);
  }

  /**
   * Creates exactly one movement for a maintenance disposition. The decision remains the durable
   * idempotency owner even if a generic request key would otherwise be reused.
   */
  @Transactional
  public CreateResult createFromMaintenance(
      UUID idempotencyKey, CreateMaintenanceEquipmentMovementTaskRequest request) {
    if (idempotencyKey == null) {
      throw new IllegalArgumentException(
          "Maintenance equipment movement Idempotency-Key is required");
    }
    validateMaintenanceRequest(request);
    CreateEquipmentMovementTaskRequest taskRequest =
        new CreateEquipmentMovementTaskRequest(
            request.warehouseId(),
            null,
            request.unitNumber(),
            request.plannedDurationMinutes(),
            request.deadlineAt(),
            request.lines().stream().map(EquipmentMovementTaskService::toMovementLine).toList());
    return createFromMaintenance(
        idempotencyKey,
        request,
        warehouseLifecycle.disabledTicket(
            MAINTENANCE_ACTOR,
            CREATE_OPERATION,
            idempotencyKey,
            admissionRequirements(taskRequest)));
  }

  @Transactional
  public CreateResult createFromMaintenance(
      UUID idempotencyKey,
      CreateMaintenanceEquipmentMovementTaskRequest request,
      AdmissionTicket admission) {
    if (idempotencyKey == null) {
      throw new IllegalArgumentException(
          "Maintenance equipment movement Idempotency-Key is required");
    }
    validateMaintenanceRequest(request);
    CreateEquipmentMovementTaskRequest taskRequest =
        new CreateEquipmentMovementTaskRequest(
            request.warehouseId(),
            null,
            request.unitNumber(),
            request.plannedDurationMinutes(),
            request.deadlineAt(),
            request.lines().stream().map(EquipmentMovementTaskService::toMovementLine).toList());
    return createInternal(
        MAINTENANCE_ACTOR,
        idempotencyKey,
        taskRequest,
        EquipmentMovementTaskOwnerType.MAINTENANCE_DISPOSITION,
        request.decisionId(),
        admission);
  }

  private CreateResult createInternal(
      UUID actorSubjectId,
      UUID idempotencyKey,
      CreateEquipmentMovementTaskRequest request,
      EquipmentMovementTaskOwnerType ownerType,
      UUID ownerId,
      AdmissionTicket admission) {
    validateRequest(request);
    String checksum = creationChecksum(request, ownerType, ownerId);
    if (ownerType == EquipmentMovementTaskOwnerType.MAINTENANCE_DISPOSITION) {
      transactionLock.acquire("equipment-movement:maintenance-disposition:" + ownerId);
      EquipmentMovementTask owned = tasks.findByOwnerTypeAndOwnerId(ownerType, ownerId).orElse(null);
      if (owned != null) {
        if (!owned.matchesRequest(checksum)) {
          throw new LogisticsConflictException(
              "Maintenance disposition already owns a different equipment movement task");
        }
        return new CreateResult(
            response(owned, lines.findAllByTask_IdOrderByLineNumberAsc(owned.getId())), true);
      }
    }
    transactionLock.acquire("equipment-movement:create:" + actorSubjectId + ":" + idempotencyKey);
    EquipmentMovementTask replay =
        tasks.findByCreatedBySubjectIdAndIdempotencyKey(actorSubjectId, idempotencyKey).orElse(null);
    if (replay != null) {
      if (!matchesCreateRequest(replay, request, ownerType, ownerId, checksum)) {
        throw new LogisticsConflictException("Idempotency-Key is already used for a different movement task");
      }
      return new CreateResult(
          response(replay, lines.findAllByTask_IdOrderByLineNumberAsc(replay.getId())), true);
    }

    List<AdmissionRequirement> requirements = admissionRequirements(request);
    if (admission == null || !sorted(requirements).equals(admission.requirements())) {
      throw new LogisticsConflictException(
          "Warehouse admission ticket does not match the equipment movement");
    }
    warehouseLifecycle.consume(admission);

    EquipmentMovementTask task =
        tasks.saveAndFlush(
            EquipmentMovementTask.create(
                request.warehouseId(),
                request.unitNumber(),
                request.plannedDurationMinutes(),
                request.deadlineAt(),
                actorSubjectId,
                idempotencyKey,
                ownerType,
                ownerId,
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
    warehouseOperationMarks.enqueue(
        request.warehouseId(),
        task.getId(),
        admission.occurredAt(),
        admission.evidenceFor(request.warehouseId()).orElse(null));
    if (!request.warehouseId().equals(targetWarehouseId)) {
      warehouseOperationMarks.enqueue(
          targetWarehouseId,
          task.getId(),
          admission.occurredAt(),
          admission.evidenceFor(targetWarehouseId).orElse(null));
    }
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
    transactionLock.acquire("equipment-movement:cancel:" + actorSubjectId + ":" + idempotencyKey);
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
        base.ownerType(),
        base.ownerId(),
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
        || request.plannedDurationMinutes() == null
        || request.plannedDurationMinutes() < 1
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
      if (workerOperationCount > EquipmentMovementTaskLimits.MAX_WORKER_OPERATIONS) {
        throw new IllegalArgumentException("Equipment movement has too many worker operations");
      }
    }
  }

  private static void validateMaintenanceRequest(
      CreateMaintenanceEquipmentMovementTaskRequest request) {
    if (request == null
        || request.decisionId() == null
        || request.warehouseId() == null
        || request.unitNumber() == null
        || request.unitNumber().isBlank()
        || request.unitNumber().trim().length() > 64
        || request.plannedDurationMinutes() == null
        || request.plannedDurationMinutes() < 1
        || request.deadlineAt() == null
        || !request.deadlineAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))
        || request.lines() == null
        || request.lines().isEmpty()
        || request.lines().size() > EquipmentMovementTaskLimits.MAX_WORKER_OPERATIONS) {
      throw new IllegalArgumentException("Maintenance equipment movement task request is invalid");
    }
    Set<String> uniqueSources = new HashSet<>();
    for (MaintenanceEquipmentMovementLineRequest line : request.lines()) {
      if (line == null
          || line.equipmentId() == null
          || line.sourceRentalItemId() == null
          || line.expectedSourceBalanceVersion() == null
          || line.expectedSourceBalanceVersion() < 0
          || line.quantity() == null
          || line.quantity() < 1) {
        throw new IllegalArgumentException("Maintenance equipment movement line is invalid");
      }
      String sourceKey = line.equipmentId() + ":" + line.sourceRentalItemId();
      if (!uniqueSources.add(sourceKey)) {
        throw new IllegalArgumentException(
            "Maintenance equipment movement may contain each equipment source only once");
      }
    }
  }

  private static EquipmentMovementLineRequest toMovementLine(
      MaintenanceEquipmentMovementLineRequest line) {
    return new EquipmentMovementLineRequest(
        line.equipmentId(),
        line.sourceRentalItemId(),
        EquipmentMovementLocationKind.CABIN_NON_RENTED,
        line.expectedSourceBalanceVersion(),
        null,
        EquipmentMovementLocationKind.STOCK,
        line.quantity());
  }

  public static List<AdmissionRequirement> admissionRequirements(
      CreateEquipmentMovementTaskRequest request) {
    if (request == null || request.warehouseId() == null) {
      throw new IllegalArgumentException("Equipment movement warehouse is required");
    }
    UUID target =
        request.targetWarehouseId() == null ? request.warehouseId() : request.targetWarehouseId();
    if (request.warehouseId().equals(target)) {
      return List.of(
          new AdmissionRequirement(
              request.warehouseId(), WarehouseOperationDirection.OUTGOING));
    }
    return List.of(
        new AdmissionRequirement(
            request.warehouseId(), WarehouseOperationDirection.OUTGOING),
        new AdmissionRequirement(target, WarehouseOperationDirection.INCOMING));
  }

  private static List<AdmissionRequirement> sorted(List<AdmissionRequirement> requirements) {
    return requirements.stream()
        .sorted(Comparator.comparing(AdmissionRequirement::warehouseId))
        .toList();
  }

  private static String creationChecksum(
      CreateEquipmentMovementTaskRequest request,
      EquipmentMovementTaskOwnerType ownerType,
      UUID ownerId) {
    List<String> values = new ArrayList<>();
    values.add(ownerType.name());
    values.add(ownerId == null ? null : ownerId.toString());
    values.addAll(requestChecksumValues(request));
    return EquipmentMovementTaskChecksum.sha256(CREATE_OPERATION, values);
  }

  private static boolean matchesCreateRequest(
      EquipmentMovementTask task,
      CreateEquipmentMovementTaskRequest request,
      EquipmentMovementTaskOwnerType ownerType,
      UUID ownerId,
      String checksum) {
    if (!task.isOwnedBy(ownerType, ownerId)) return false;
    if (task.matchesRequest(checksum)) return true;
    // V36 adds durable owner metadata. A public task created before that migration still has the
    // former payload-only checksum, so preserve an exact retry without weakening the new fence.
    return ownerType == EquipmentMovementTaskOwnerType.USER_REQUEST
        && ownerId == null
        && task.matchesRequest(legacyPublicCreationChecksum(request));
  }

  private static String legacyPublicCreationChecksum(CreateEquipmentMovementTaskRequest request) {
    return EquipmentMovementTaskChecksum.sha256(CREATE_OPERATION, requestChecksumValues(request));
  }

  private static List<String> requestChecksumValues(CreateEquipmentMovementTaskRequest request) {
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
    return values;
  }

  public record CreateResult(EquipmentMovementTaskResponse response, boolean replayed) {}

  public record MutationResult(EquipmentMovementTaskResponse response, boolean replayed) {}
}
