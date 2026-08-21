package dev.buhanzaz.rwms.logistics.integration;

import static dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.*;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.FailurePolicy.DEFAULT;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.malformed;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Private task-board client for equipment movement and driver-board logistics tasks.
 *
 * <p>Task-board version and route validation remains here so the facade and other remote-owner
 * clients cannot accidentally reinterpret worker queue state.
 */
final class LogisticsTaskBoardDependencyClient {
  private static final String TASK_BOARD_CLIENT = "logistics-task-board";
  private static final String TASK_BOARD_SCOPE = "task-board.logistics";

  private final LogisticsOAuthHttpTransport transport;
  private final String taskBoardBase;
  private final String taskBoardEquipmentMovementBase;
  private final String taskBoardTaskBase;
  private final String taskBoardDriverBase;
  private final String taskBoardDriverTaskBase;

  LogisticsTaskBoardDependencyClient(LogisticsOAuthHttpTransport transport, String taskBoardBase) {
    this.transport = transport;
    this.taskBoardBase = taskBoardBase;
    taskBoardEquipmentMovementBase =
        taskBoardBase + "/api/internal/task-board/v1/logistics/equipment-movement-tasks";
    taskBoardTaskBase = taskBoardBase + "/api/internal/task-board/v1/tasks";
    taskBoardDriverBase = taskBoardBase + "/api/internal/task-board/v1/logistics/warehouses";
    taskBoardDriverTaskBase = taskBoardBase + "/api/internal/task-board/v1/logistics/tasks";
  }

  EquipmentMovementBoardTask registerEquipmentMovementTask(
      UUID warehouseId,
      UUID externalTaskId,
      String unitNumber,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<EquipmentMovementOperation> operations) {
    if (plannedDurationMinutes == null || plannedDurationMinutes < 1) {
      throw new IllegalArgumentException(
          "Equipment movement task requires a positive planned duration");
    }
    EquipmentMovementBoardTaskResponse response =
        transport.postWithoutIdempotency(
            taskBoardEquipmentMovementBase,
            new RegisterEquipmentMovementTaskRequest(
                warehouseId,
                externalTaskId,
                unitNumber,
                plannedDurationMinutes,
                deadlineAt,
                operations.stream()
                    .map(
                        operation ->
                            new EquipmentMovementOperationRequest(
                                operation.direction(),
                                operation.equipmentId(),
                                operation.equipmentName(),
                                operation.quantity()))
                    .toList()),
            EquipmentMovementBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return movementBoardTask(response);
  }

  EquipmentMovementBoardTask readEquipmentMovementTask(UUID externalTaskId) {
    EquipmentMovementBoardTaskResponse response =
        transport.get(
            taskBoardEquipmentMovementBase + "/" + externalTaskId,
            EquipmentMovementBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return movementBoardTask(response);
  }

  EquipmentMovementBoardTask cancelEquipmentMovementTask(
      UUID externalTaskId, long expectedTaskVersion) {
    EquipmentMovementBoardTaskResponse response =
        transport.postWithoutIdempotency(
            taskBoardEquipmentMovementBase + "/" + externalTaskId + "/cancel",
            new CancelEquipmentMovementTaskRequest(expectedTaskVersion),
            EquipmentMovementBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return movementBoardTask(response);
  }

  WarehouseDriverQueue readWarehouseDriverQueue(UUID warehouseId) {
    WarehouseQueueCapabilitiesResponse response = readWarehouseQueueCapabilities(warehouseId);
    if (response.warehouseId() == null
        || !warehouseId.equals(response.warehouseId())
        || response.movementQueueDefinitions() == null
        || response.movementQueueDefinitions().size() != 1) {
      throw malformed("Task-board must expose exactly one active warehouse driver queue");
    }
    MovementQueueCapabilityResponse queue = response.movementQueueDefinitions().getFirst();
    if (queue.queueDefinitionId() == null || queue.workQueueId() == null) {
      throw malformed("Task-board returned an invalid warehouse driver queue");
    }
    return new WarehouseDriverQueue(warehouseId, queue.queueDefinitionId(), queue.workQueueId());
  }

  boolean isWarehouseDriverQueueAvailable(UUID warehouseId) {
    WarehouseQueueCapabilitiesResponse response = readWarehouseQueueCapabilities(warehouseId);
    if (response.warehouseId() == null
        || !warehouseId.equals(response.warehouseId())
        || response.movementQueueDefinitions() == null) {
      throw malformed("Task-board returned invalid warehouse queue capabilities");
    }
    int queueCount = response.movementQueueDefinitions().size();
    if (queueCount > 1) {
      throw malformed("Task-board exposes more than one active warehouse driver queue");
    }
    return queueCount == 1;
  }

  DriverBoardTask registerDriverTask(
      UUID warehouseId,
      UUID externalTaskId,
      UUID sourceId,
      String title,
      String unitNumber,
      String description,
      UUID queueDefinitionId,
      LocalDate scheduledDate,
      int priority,
      DriverTaskAudience driverAudience) {
    DriverBoardTaskResponse response =
        transport.postWithoutIdempotency(
            taskBoardTaskBase,
            new RegisterDriverTaskRequest(
                warehouseId,
                externalTaskId,
                title,
                unitNumber,
                description,
                null,
                null,
                List.of(new DriverRouteStepRequest(queueDefinitionId, description, null)),
                scheduledDate,
                priority,
                new DriverTaskSourceRequest("LOGISTICS_DRIVER_TASK", sourceId),
                "SCHEDULED",
                audienceRequest(driverAudience)),
            DriverBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return driverBoardTask(response);
  }

  DriverBoardTask readDriverTask(UUID externalTaskId) {
    return driverBoardTask(
        transport.get(
            taskBoardTaskBase + "/" + externalTaskId,
            DriverBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Dependency returned an empty response",
            DEFAULT));
  }

  DriverBoardTask cancelDriverTask(UUID externalTaskId, long expectedTaskVersion) {
    return cancelDriverTask(
        externalTaskId, expectedTaskVersion, "Капитальный ремонт возвращён в отдельную очередь");
  }

  DriverBoardTask cancelDriverTask(UUID externalTaskId, long expectedTaskVersion, String reason) {
    if (externalTaskId == null
        || expectedTaskVersion < 0
        || reason == null
        || reason.isBlank()
        || reason.length() > 1_000) {
      throw new IllegalArgumentException("Invalid driver task cancellation command");
    }
    CancelDriverTaskResponse cancelled =
        transport.postWithoutIdempotency(
            taskBoardTaskBase + "/" + externalTaskId + "/cancel",
            new CancelDriverTaskRequest(expectedTaskVersion, reason.trim()),
            CancelDriverTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (cancelled == null
        || cancelled.externalTaskId() == null
        || !externalTaskId.equals(cancelled.externalTaskId())
        || cancelled.taskVersion() < expectedTaskVersion
        || !"CANCELLED".equals(cancelled.status())) {
      throw malformed("Task-board returned an invalid cancelled driver task");
    }
    return readDriverTask(externalTaskId);
  }

  DriverTaskPreStartCancellation cancelDriverTaskIfPreStart(
      UUID externalTaskId, long expectedTaskVersion, String reason) {
    if (externalTaskId == null
        || expectedTaskVersion < 0
        || reason == null
        || reason.isBlank()
        || reason.length() > 1_000) {
      throw new IllegalArgumentException("Invalid pre-start driver task cancellation command");
    }
    return preStartDriverTaskCancellation(
        transport.postWithoutIdempotency(
            taskBoardTaskBase + "/" + externalTaskId + "/cancel-if-pre-start",
            new CancelDriverTaskRequest(expectedTaskVersion, reason),
            PreStartDriverTaskCancellationResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Dependency returned an empty response",
            DEFAULT),
        externalTaskId);
  }

  DriverBoardTask setDriverTaskLane(UUID externalTaskId, long expectedTaskVersion, String lane) {
    return driverBoardTask(
        transport.postWithoutIdempotency(
            taskBoardTaskBase + "/" + externalTaskId + "/lane",
            new SetDriverTaskLaneRequest(expectedTaskVersion, lane),
            DriverBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Dependency returned an empty response",
            DEFAULT));
  }

  DriverBoardSnapshot readDriverBoard(UUID warehouseId) {
    DriverBoardSnapshotResponse response =
        transport.get(
            taskBoardDriverBase + "/" + warehouseId + "/board",
            DriverBoardSnapshotResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response.warehouseId() == null
        || !warehouseId.equals(response.warehouseId())
        || response.queueId() == null
        || response.queueVersion() < 0
        || response.current() == null
        || response.dates() == null) {
      throw malformed("Task-board returned an invalid driver board");
    }
    return new DriverBoardSnapshot(
        warehouseId,
        response.queueId(),
        response.queueVersion(),
        response.current().stream().map(entry -> driverBoardEntry(entry, warehouseId)).toList(),
        response.dates().stream()
            .map(
                column -> {
                  if (column.date() == null || column.entries() == null) {
                    throw malformed("Task-board returned an invalid driver date column");
                  }
                  return new DriverBoardDateColumn(
                      column.date(),
                      column.entries().stream()
                          .map(entry -> driverBoardEntry(entry, warehouseId))
                          .toList());
                })
            .toList());
  }

  DriverBoardTask moveDriverTask(
      UUID externalTaskId,
      long expectedTaskVersion,
      long expectedEntryVersion,
      String targetLane,
      LocalDate targetDate,
      int targetIndex,
      DriverTaskAudience targetDriverAudience) {
    return driverBoardTask(
        transport.postWithoutIdempotency(
            taskBoardDriverTaskBase + "/" + externalTaskId + "/move",
            new MoveDriverTaskRequest(
                expectedTaskVersion,
                expectedEntryVersion,
                targetLane,
                targetDate,
                targetIndex,
                targetDriverAudience == null ? null : audienceRequest(targetDriverAudience)),
            DriverBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Dependency returned an empty response",
            DEFAULT));
  }

  DriverCompletionEvidence readDriverCompletionEvidence(UUID externalTaskId) {
    DriverCompletionEvidenceResponse response =
        transport.get(
            taskBoardTaskBase + "/" + externalTaskId + "/completion-evidence",
            DriverCompletionEvidenceResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response.externalTaskId() == null
        || !externalTaskId.equals(response.externalTaskId())
        || response.taskId() == null
        || response.entryId() == null
        || response.evidenceId() == null
        || response.mediaId() == null
        || response.mediaGeneration() < 1
        || response.warehouseId() == null
        || response.recordedAt() == null) {
      throw malformed("Task-board returned invalid driver completion evidence");
    }
    return new DriverCompletionEvidence(
        response.externalTaskId(),
        response.taskId(),
        response.entryId(),
        response.evidenceId(),
        response.mediaId(),
        response.mediaGeneration(),
        response.warehouseId(),
        response.recordedAt());
  }

  private WarehouseQueueCapabilitiesResponse readWarehouseQueueCapabilities(UUID warehouseId) {
    return transport.get(
        taskBoardBase
            + "/api/internal/task-board/v1/warehouses/"
            + warehouseId
            + "/queue-capabilities",
        WarehouseQueueCapabilitiesResponse.class,
        TASK_BOARD_CLIENT,
        TASK_BOARD_SCOPE,
        "Dependency returned an empty response",
        DEFAULT);
  }

  private static EquipmentMovementBoardTask movementBoardTask(
      EquipmentMovementBoardTaskResponse response) {
    if (response == null
        || response.taskId() == null
        || response.taskVersion() < 0
        || response.warehouseId() == null
        || response.externalTaskId() == null
        || response.status() == null) {
      throw malformed("Task-board returned an invalid equipment movement task");
    }
    return new EquipmentMovementBoardTask(
        response.taskId(),
        response.taskVersion(),
        response.warehouseId(),
        response.externalTaskId(),
        response.status(),
        response.doneAt());
  }

  private static DriverBoardTask driverBoardTask(DriverBoardTaskResponse response) {
    if (response == null
        || response.taskId() == null
        || response.taskVersion() < 0
        || response.warehouseId() == null
        || response.externalTaskId() == null
        || response.status() == null
        || response.scheduledDate() == null
        || response.lane() == null
        || response.priority() < 1
        || response.priority() > 5
        || response.route() == null
        || response.route().size() != 1) {
      throw malformed("Task-board returned an invalid driver task");
    }
    DriverRouteStepResponse entry = response.route().getFirst();
    if (entry.entryId() == null
        || entry.entryVersion() < 0
        || entry.status() == null
        || entry.queuePosition() < 0) {
      throw malformed("Task-board returned an invalid driver task route");
    }
    return new DriverBoardTask(
        response.taskId(),
        response.taskVersion(),
        response.warehouseId(),
        response.externalTaskId(),
        response.title(),
        response.unitNumber(),
        entry.taskText(),
        audience(response.driverAudience()),
        response.status(),
        response.scheduledDate(),
        response.lane(),
        response.priority(),
        response.pinned(),
        response.doneAt(),
        entry.entryId(),
        entry.entryVersion(),
        entry.status(),
        entry.queuePosition());
  }

  private static DriverTaskPreStartCancellation preStartDriverTaskCancellation(
      PreStartDriverTaskCancellationResponse response, UUID externalTaskId) {
    if (response == null
        || response.outcome() == null
        || response.taskId() == null
        || response.externalTaskId() == null
        || !externalTaskId.equals(response.externalTaskId())
        || response.taskVersion() < 0
        || response.status() == null) {
      throw malformed("Task-board returned an invalid pre-start cancellation outcome");
    }
    DriverTaskPreStartCancellationOutcome outcome;
    try {
      outcome = DriverTaskPreStartCancellationOutcome.valueOf(response.outcome());
    } catch (IllegalArgumentException exception) {
      throw malformed("Task-board returned an unknown pre-start cancellation outcome");
    }
    boolean cancelled =
        outcome == DriverTaskPreStartCancellationOutcome.CANCELLED
            || outcome == DriverTaskPreStartCancellationOutcome.ALREADY_CANCELLED;
    if ((cancelled && (!"CANCELLED".equals(response.status()) || response.cancelledAt() == null))
        || (outcome == DriverTaskPreStartCancellationOutcome.STARTED
            && (!("ACTIVE".equals(response.status()) || "DONE".equals(response.status()))
                || response.cancelledAt() != null))
        || (outcome == DriverTaskPreStartCancellationOutcome.VERSION_CONFLICT
            && (!"ACTIVE".equals(response.status()) || response.cancelledAt() != null))) {
      throw malformed("Task-board returned an inconsistent pre-start cancellation outcome");
    }
    return new DriverTaskPreStartCancellation(
        outcome,
        response.taskId(),
        response.externalTaskId(),
        response.taskVersion(),
        response.status(),
        response.cancelledAt());
  }

  private static DriverBoardTask driverBoardEntry(
      DriverBoardEntryResponse response, UUID warehouseId) {
    if (response == null
        || response.taskId() == null
        || response.taskVersion() < 0
        || response.externalTaskId() == null
        || response.taskStatus() == null
        || response.scheduledDate() == null
        || response.lane() == null
        || response.priority() < 1
        || response.priority() > 5
        || response.id() == null
        || response.version() < 0
        || response.status() == null
        || response.queuePosition() < 0) {
      throw malformed("Task-board returned an invalid driver board entry");
    }
    return new DriverBoardTask(
        response.taskId(),
        response.taskVersion(),
        warehouseId,
        response.externalTaskId(),
        response.title(),
        response.unitNumber(),
        response.taskText(),
        audience(response.driverAudience()),
        response.taskStatus(),
        response.scheduledDate(),
        response.lane(),
        response.priority(),
        response.pinned(),
        response.doneAt(),
        response.id(),
        response.version(),
        response.status(),
        response.queuePosition());
  }

  /** One equipment direction and quantity rendered as an operation in a task-board task. */
  private record EquipmentMovementOperationRequest(
      String direction, UUID equipmentId, String equipmentName, long quantity) {}

  /**
   * Registration payload for an equipment movement task, including warehouse, external identity,
   * deadline, planned duration, and operator-visible operations.
   */
  private record RegisterEquipmentMovementTaskRequest(
      UUID warehouseId,
      UUID externalTaskId,
      String unitNumber,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<EquipmentMovementOperationRequest> operations) {}

  /** Optimistically fenced cancellation command for an equipment movement task. */
  private record CancelEquipmentMovementTaskRequest(long expectedTaskVersion) {}

  /** Optimistically fenced driver-task cancellation command with an auditable reason. */
  private record CancelDriverTaskRequest(long expectedTaskVersion, String reason) {}

  /** Authoritative task-board result after a driver task is cancelled. */
  private record CancelDriverTaskResponse(
      UUID taskId,
      UUID externalTaskId,
      long taskVersion,
      String status,
      OffsetDateTime cancelledAt) {}

  /**
   * Conditional cancellation result distinguishing an applied pre-start cancellation from an
   * already advanced task that logistics must reconcile instead.
   */
  private record PreStartDriverTaskCancellationResponse(
      String outcome,
      UUID taskId,
      UUID externalTaskId,
      long taskVersion,
      String status,
      OffsetDateTime cancelledAt) {}

  /** Versioned task-board snapshot for the logistics equipment movement task. */
  private record EquipmentMovementBoardTaskResponse(
      UUID taskId,
      long taskVersion,
      UUID warehouseId,
      UUID externalTaskId,
      String status,
      OffsetDateTime doneAt) {}

  /** Queue-definition and concrete work-queue identifiers usable for a movement route step. */
  private record MovementQueueCapabilityResponse(UUID queueDefinitionId, UUID workQueueId) {}

  /** Warehouse task-board capabilities used to decide whether driver routing is available. */
  private record WarehouseQueueCapabilitiesResponse(
      UUID warehouseId, List<MovementQueueCapabilityResponse> movementQueueDefinitions) {}

  /** Logistics source aggregate identity attached to a driver task for idempotent correlation. */
  private record DriverTaskSourceRequest(String type, UUID sourceId) {}

  /** Planned audience sent to task-board; only assigned work carries a driver snapshot. */
  private record DriverTaskAudienceRequest(String mode, UUID workerId, String workerName) {}

  /** Planned task audience echoed by task-board. */
  private record DriverTaskAudienceResponse(String mode, UUID workerId, String workerName) {}

  /** Desired task-board route step with queue, text, and planned effort. */
  private record DriverRouteStepRequest(
      UUID queueDefinitionId, String taskText, Integer plannedDurationMinutes) {}

  /**
   * Complete driver-task registration command carrying scheduling, route, source, lane, and
   * driver-audience data owned by the logistics workflow.
   */
  private record RegisterDriverTaskRequest(
      UUID warehouseId,
      UUID externalTaskId,
      String title,
      String unitNumber,
      String description,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<DriverRouteStepRequest> route,
      LocalDate scheduledDate,
      Integer priority,
      DriverTaskSourceRequest source,
      String lane,
      DriverTaskAudienceRequest driverAudience) {}

  /** Version-fenced command changing only a driver task's operational lane. */
  private record SetDriverTaskLaneRequest(long expectedTaskVersion, String lane) {}

  /**
   * Authoritative route-entry snapshot with both entry and queue ordering versions needed by board
   * reconciliation.
   */
  private record DriverRouteStepResponse(
      UUID entryId,
      long entryVersion,
      UUID queueDefinitionId,
      UUID workQueueId,
      String queueName,
      int routeIndex,
      int queuePosition,
      String entryType,
      String status,
      String taskText,
      Integer plannedDurationMinutes) {}

  /**
   * Task-board aggregate snapshot for a driver task, including scheduling state and its ordered
   * route entries.
   */
  private record DriverBoardTaskResponse(
      UUID taskId,
      long taskVersion,
      UUID warehouseId,
      UUID externalTaskId,
      String title,
      String unitNumber,
      String description,
      DriverTaskAudienceResponse driverAudience,
      String status,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      LocalDate scheduledDate,
      String lane,
      int priority,
      boolean pinned,
      OffsetDateTime doneAt,
      List<DriverRouteStepResponse> route) {}

  /** Flattened driver-board entry projection retaining task and entry versions for safe moves. */
  private record DriverBoardEntryResponse(
      UUID id,
      long version,
      UUID taskId,
      UUID externalTaskId,
      long taskVersion,
      UUID warehouseId,
      String title,
      String unitNumber,
      String taskText,
      DriverTaskAudienceResponse driverAudience,
      String taskStatus,
      LocalDate scheduledDate,
      String lane,
      int priority,
      boolean pinned,
      int queuePosition,
      String status,
      OffsetDateTime doneAt) {}

  /** One scheduled-date column and its ordered driver-board entries. */
  private record DriverBoardDateColumnResponse(
      LocalDate date, List<DriverBoardEntryResponse> entries) {}

  /**
   * Warehouse driver-board snapshot with queue version, current work, and dated scheduling columns.
   */
  private record DriverBoardSnapshotResponse(
      UUID warehouseId,
      UUID queueId,
      long queueVersion,
      List<DriverBoardEntryResponse> current,
      List<DriverBoardDateColumnResponse> dates) {}

  /**
   * Board move command fenced by task and entry versions and declaring the target lane, date, and
   * index.
   */
  private record MoveDriverTaskRequest(
      long expectedTaskVersion,
      long expectedEntryVersion,
      String targetLane,
      LocalDate targetDate,
      int targetIndex,
      DriverTaskAudienceRequest targetDriverAudience) {}

  private static DriverTaskAudienceRequest audienceRequest(DriverTaskAudience audience) {
    if (audience == null || audience.mode() == null) {
      throw new IllegalArgumentException("Driver task audience is required");
    }
    return new DriverTaskAudienceRequest(
        audience.mode().name(), audience.workerId(), audience.workerName());
  }

  private static DriverTaskAudience audience(DriverTaskAudienceResponse response) {
    if (response == null || response.mode() == null) {
      throw malformed("Task-board returned no driver audience");
    }
    try {
      return new DriverTaskAudience(
          dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode.valueOf(response.mode()),
          response.workerId(),
          response.workerName());
    } catch (IllegalArgumentException exception) {
      throw malformed("Task-board returned an unknown driver audience");
    }
  }

  /**
   * Completion evidence correlation that pins the task-board entry to an exact media generation and
   * warehouse for downstream verification.
   */
  private record DriverCompletionEvidenceResponse(
      UUID externalTaskId,
      UUID taskId,
      UUID entryId,
      UUID evidenceId,
      UUID mediaId,
      long mediaGeneration,
      UUID warehouseId,
      OffsetDateTime recordedAt) {}
}
