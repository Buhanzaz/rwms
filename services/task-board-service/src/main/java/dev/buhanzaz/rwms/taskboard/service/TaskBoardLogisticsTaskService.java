package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.EquipmentMovementDirection;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.mapper.LogisticsTaskResponseMapper;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueRepository;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.OperationDirection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Adapts immutable logistics equipment-movement facts to typed task-board operations.
 *
 * <p>Generic source registration remains in {@link TaskBoardExternalRegistrationService}; this
 * coordinator owns only the typed validation, deterministic fingerprint and response mapping
 * required by the logistics boundary.
 */
@Service
class TaskBoardLogisticsTaskService {
  private static final String LOGISTICS_EQUIPMENT_MOVEMENT_TITLE = "Перемещение мебели";
  private static final String LOGISTICS_EQUIPMENT_MOVEMENT_CANCEL_REASON =
      "LOGISTICS_EQUIPMENT_MOVEMENT_CANCELLED";
  private static final String LOGISTICS_EQUIPMENT_MOVEMENT_FINGERPRINT_SCHEMA =
      "task-board-logistics-equipment-movement:v1";
  private static final int MAX_EQUIPMENT_MOVEMENT_OPERATIONS = 10;
  private static final int EQUIPMENT_MOVEMENT_DISPLAY_NAME_LENGTH = 64;

  private final TaskBoardExternalRegistrationService externalTasks;
  private final TaskBoardExternalMutationService externalMutations;
  private final WorkQueueRepository queues;
  private final LogisticsTaskResponseMapper logisticsTaskMapper;
  private final TransactionTemplate lifecycleMutations;

  TaskBoardLogisticsTaskService(
      TaskBoardExternalRegistrationService externalTasks,
      TaskBoardExternalMutationService externalMutations,
      WorkQueueRepository queues,
      LogisticsTaskResponseMapper logisticsTaskMapper,
      PlatformTransactionManager transactionManager) {
    this.externalTasks = externalTasks;
    this.externalMutations = externalMutations;
    this.queues = queues;
    this.logisticsTaskMapper = logisticsTaskMapper;
    this.lifecycleMutations = new TransactionTemplate(transactionManager);
  }

  /** Creates the task-board representation of immutable logistics equipment-movement facts. */
  LogisticsTaskSnapshot registerLogisticsEquipmentMovementTask(
      RegisterLogisticsEquipmentMovementTaskRequest request) {
    NormalizedEquipmentMovementRequest normalized = normalizeEquipmentMovementRequest(request);
    UUID furnitureMovementQueueDefinitionId =
        inLifecycleMutation(
            () -> requireFurnitureMovementQueue(request.warehouseId()).getDefinition().getId());
    BoardTask task =
        externalTasks.createLogisticsEquipmentMovementTask(
            request.warehouseId(),
            new CreateBoardTaskRequest(
                request.externalTaskId(),
                equipmentMovementTitle(normalized.unitNumber()),
                normalized.unitNumber(),
                equipmentMovementText(normalized.operations()),
                request.plannedDurationMinutes(),
                request.deadlineAt(),
                List.of(
                    new RouteStepRequest(
                        furnitureMovementQueueDefinitionId,
                        equipmentMovementText(normalized.operations()),
                        request.plannedDurationMinutes()))),
            equipmentMovementFingerprint(request, normalized),
            equipmentMovementAdmissionDirection(normalized));
    return inLifecycleMutation(
        () ->
            logisticsTaskMapper.toLogisticsTaskSnapshot(
                requireEquipmentMovementTask(
                    externalMutations.requireOwnedExternalTask(
                        TaskBoardExternalRegistrationService.LOGISTICS_SOURCE_CLIENT_ID,
                        task.getExternalTaskId()))));
  }

  /** Resolves the typed equipment-movement snapshot for logistics. */
  LogisticsTaskSnapshot logisticsEquipmentMovementTask(UUID externalTaskId) {
    return logisticsTaskMapper.toLogisticsTaskSnapshot(
        requireEquipmentMovementTask(
            externalMutations.requireOwnedExternalTask(
                TaskBoardExternalRegistrationService.LOGISTICS_SOURCE_CLIENT_ID, externalTaskId)));
  }

  /** Cancels the typed logistics equipment-movement task under its task version. */
  LogisticsTaskSnapshot cancelLogisticsEquipmentMovementTask(
      UUID externalTaskId, CancelLogisticsEquipmentMovementTaskRequest request) {
    BoardTask task =
        requireEquipmentMovementTask(
            externalMutations.requireOwnedExternalTask(
                TaskBoardExternalRegistrationService.LOGISTICS_SOURCE_CLIENT_ID, externalTaskId));
    externalMutations.cancelOwnedExternalTask(
        TaskBoardExternalRegistrationService.LOGISTICS_SOURCE_CLIENT_ID,
        task.getExternalTaskId(),
        new CancelTaskRequest(
            request.expectedTaskVersion(), LOGISTICS_EQUIPMENT_MOVEMENT_CANCEL_REASON));
    return logisticsTaskMapper.toLogisticsTaskSnapshot(
        requireEquipmentMovementTask(
            externalMutations.requireOwnedExternalTask(
                TaskBoardExternalRegistrationService.LOGISTICS_SOURCE_CLIENT_ID, externalTaskId)));
  }

  private WorkQueue requireFurnitureMovementQueue(UUID warehouseId) {
    List<WorkQueue> candidates =
        queues.findAllActiveOrderedByWarehouseId(warehouseId).stream()
            .filter(queue -> !queue.isHidden())
            .filter(queue -> queue.getType() == QueueType.FURNITURE_MOVEMENT)
            .toList();
    if (candidates.size() != 1) {
      throw new ConflictException(
          "Настройте ровно одну активную видимую очередь типа «Перемещение мебели»");
    }
    return candidates.getFirst();
  }

  private <T> T inLifecycleMutation(Supplier<T> mutation) {
    T result = lifecycleMutations.execute(status -> mutation.get());
    if (result == null) {
      throw new IllegalStateException("Lifecycle task-board mutation returned no result");
    }
    return result;
  }

  private NormalizedEquipmentMovementRequest normalizeEquipmentMovementRequest(
      RegisterLogisticsEquipmentMovementTaskRequest request) {
    if (request == null
        || request.warehouseId() == null
        || request.externalTaskId() == null
        || request.deadlineAt() == null) {
      throw new IllegalArgumentException("Warehouse, external task and deadline are required");
    }
    if (request.plannedDurationMinutes() != null && request.plannedDurationMinutes() < 0) {
      throw new IllegalArgumentException("Planned duration must not be negative");
    }
    String unitNumber = trim(request.unitNumber());
    if (unitNumber != null && unitNumber.length() > 64) {
      throw new IllegalArgumentException("Unit number is too long");
    }
    if (request.operations() == null
        || request.operations().isEmpty()
        || request.operations().size() > MAX_EQUIPMENT_MOVEMENT_OPERATIONS) {
      throw new IllegalArgumentException("Equipment movement must contain from one to ten operations");
    }
    List<NormalizedEquipmentMovementOperation> operations =
        request.operations().stream().map(this::normalizeEquipmentMovementOperation).toList();
    return new NormalizedEquipmentMovementRequest(unitNumber, operations);
  }

  /** A mixed move still introduces furniture, so it is never a DRAINING drain operation. */
  private OperationDirection equipmentMovementAdmissionDirection(
      NormalizedEquipmentMovementRequest movement) {
    return movement.operations().stream()
            .allMatch(
                operation ->
                    operation.direction() == EquipmentMovementDirection.TAKE_FROM_CABIN)
        ? OperationDirection.OUTGOING
        : OperationDirection.INCOMING;
  }

  private NormalizedEquipmentMovementOperation normalizeEquipmentMovementOperation(
      EquipmentMovementOperation operation) {
    if (operation == null || operation.direction() == null || operation.quantity() == null) {
      throw new IllegalArgumentException("Equipment movement operation is incomplete");
    }
    String name = compact(operation.equipmentName());
    if (operation.equipmentId() == null) {
      throw new IllegalArgumentException("Equipment identifier is required");
    }
    if (name == null || name.length() > 255) {
      throw new IllegalArgumentException("Equipment name is invalid");
    }
    if (operation.quantity() < 1) {
      throw new IllegalArgumentException("Equipment quantity must be positive");
    }
    return new NormalizedEquipmentMovementOperation(
        operation.direction(), operation.equipmentId(), name, operation.quantity());
  }

  private String equipmentMovementTitle(String unitNumber) {
    return unitNumber == null
        ? LOGISTICS_EQUIPMENT_MOVEMENT_TITLE
        : LOGISTICS_EQUIPMENT_MOVEMENT_TITLE + " — бытовка " + unitNumber;
  }

  private String equipmentMovementText(List<NormalizedEquipmentMovementOperation> operations) {
    return operations.stream()
        .map(
            operation ->
                movementDirectionLabel(operation.direction())
                    + ": "
                    + abbreviated(operation.equipmentName(), EQUIPMENT_MOVEMENT_DISPLAY_NAME_LENGTH)
                    + " — "
                    + operation.quantity()
                    + " шт.")
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  private String movementDirectionLabel(EquipmentMovementDirection direction) {
    return switch (direction) {
      case BRING_TO_CABIN -> "Занести в бытовку";
      case TAKE_FROM_CABIN -> "Вынести из бытовки";
    };
  }

  private String abbreviated(String value, int maxLength) {
    if (value.length() <= maxLength) return value;
    return value.substring(0, maxLength - 1) + "…";
  }

  private String equipmentMovementFingerprint(
      RegisterLogisticsEquipmentMovementTaskRequest request,
      NormalizedEquipmentMovementRequest normalized) {
    var canonical = new StringBuilder(LOGISTICS_EQUIPMENT_MOVEMENT_FINGERPRINT_SCHEMA);
    appendFingerprint(canonical, request.warehouseId());
    appendFingerprint(canonical, request.externalTaskId());
    appendFingerprint(canonical, normalized.unitNumber());
    appendFingerprint(canonical, request.plannedDurationMinutes());
    appendFingerprint(canonical, request.deadlineAt().toInstant().toString());
    appendFingerprint(canonical, normalized.operations().size());
    for (NormalizedEquipmentMovementOperation operation : normalized.operations()) {
      appendFingerprint(canonical, operation.direction());
      appendFingerprint(canonical, operation.equipmentId());
      appendFingerprint(canonical, operation.equipmentName());
      appendFingerprint(canonical, operation.quantity());
    }
    return fingerprintDigest(canonical);
  }


  private String fingerprintDigest(StringBuilder canonical) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Не удалось вычислить fingerprint задачи", exception);
    }
  }

  private void appendFingerprint(StringBuilder canonical, Object value) {
    if (value == null) {
      canonical.append("|-1:");
      return;
    }
    String text = String.valueOf(value);
    canonical.append('|').append(text.length()).append(':').append(text);
  }



  private String trim(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String compact(String value) {
    String trimmed = trim(value);
    return trimmed == null ? null : trimmed.replaceAll("\\s+", " ");
  }

  private BoardTask requireEquipmentMovementTask(BoardTask task) {
    if (!task.isCompletionDeadlineEnforced()) {
      throw new NotFoundException("Задача не найдена");
    }
    return task;
  }

  /**
   * Canonical equipment-movement command used for deterministic fingerprints, admission direction
   * and task text after all logistics input has been validated.
   */
  private record NormalizedEquipmentMovementRequest(
      String unitNumber, List<NormalizedEquipmentMovementOperation> operations) {
    private NormalizedEquipmentMovementRequest {
      operations = List.copyOf(operations);
    }
  }

  /**
   * Canonical single equipment movement retaining the logistics-owned direction and exact
   * equipment identity, display name and positive quantity.
   */
  private record NormalizedEquipmentMovementOperation(
      EquipmentMovementDirection direction, UUID equipmentId, String equipmentName, long quantity) {}
}
