package dev.buhanzaz.rwms.logistics.integration;

import static dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.*;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.FailurePolicy.DEFAULT;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.malformed;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Private task-board client for equipment movement and driver-board logistics tasks.
 *
 * <p>Task-board version and route validation remains here so the facade and other remote-owner
 * clients cannot accidentally reinterpret worker queue state.
 */
final class LogisticsTaskBoardDependencyClient {
  private static final String TASK_BOARD_CLIENT = "logistics-task-board";
  private static final String TASK_BOARD_SCOPE = "task-board.logistics";
  private static final Set<String> CONTRACTOR_EVIDENCE_STATES =
      Set.of("RESERVED", "UPLOADING", "READY", "REVIEW_REQUIRED", "REJECTED");
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
  static final String DRIVER_SHIFT_PLAN_CLIENT =
      "logistics-task-board-driver-shift-plan";
  static final String DRIVER_SHIFT_PLAN_SCOPE = "task-board.driver-shifts.plan";

  private final LogisticsOAuthHttpTransport transport;
  private final String taskBoardBase;
  private final String taskBoardEquipmentMovementBase;
  private final String taskBoardTaskBase;
  private final String taskBoardDriverBase;
  private final String taskBoardDriverTaskBase;
  private final String taskBoardContractorExecutionBase;
  private final String taskBoardOperationalAssignmentBase;
  private final String taskBoardDriverShiftPlanBase;
  private final String taskBoardPlanningReplacementBase;
  private final String taskBoardPlanningReplanHoldBase;

  LogisticsTaskBoardDependencyClient(LogisticsOAuthHttpTransport transport, String taskBoardBase) {
    this.transport = transport;
    this.taskBoardBase = taskBoardBase;
    taskBoardEquipmentMovementBase =
        taskBoardBase + "/api/internal/task-board/v1/logistics/equipment-movement-tasks";
    taskBoardTaskBase = taskBoardBase + "/api/internal/task-board/v1/tasks";
    taskBoardDriverBase = taskBoardBase + "/api/internal/task-board/v1/logistics/warehouses";
    taskBoardDriverTaskBase = taskBoardBase + "/api/internal/task-board/v1/logistics/tasks";
    taskBoardContractorExecutionBase =
        taskBoardBase + "/api/internal/task-board/v1/logistics/contractor-execution/workers";
    taskBoardOperationalAssignmentBase =
        taskBoardBase + "/api/internal/task-board/v1/logistics/operational-assignments";
    taskBoardDriverShiftPlanBase = taskBoardBase + "/api/internal/task-board/v1/driver-shift-plans";
    taskBoardPlanningReplacementBase =
        taskBoardBase + "/api/internal/task-board/v1/logistics/planning-assignments";
    taskBoardPlanningReplanHoldBase =
        taskBoardBase + "/api/internal/task-board/v1/logistics/planning-replan-holds";
  }

  List<WarehouseDriverIdentity> listWarehouseDrivers(UUID warehouseId) {
    return listWarehouseDrivers(warehouseId, null, false);
  }

  List<WarehouseDriverIdentity> listWarehouseDrivers(
      UUID warehouseId, OffsetDateTime at, boolean includeIncoming) {
    if (warehouseId == null) {
      throw new IllegalArgumentException("Warehouse driver directory identity is required");
    }
    StringBuilder path =
        new StringBuilder(taskBoardDriverBase)
            .append("/")
            .append(warehouseId)
            .append("/drivers?includeIncoming=")
            .append(includeIncoming);
    if (at != null) path.append("&at=").append(at.toInstant());
    List<WarehouseDriverIdentityResponse> response =
        transport.getList(
            path.toString(),
            new ParameterizedTypeReference<>() {},
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Task-board returned an empty driver directory",
            DEFAULT);
    if (response.stream()
            .anyMatch(value -> invalidDriverIdentity(value, warehouseId, includeIncoming))
        || response.stream().map(WarehouseDriverIdentityResponse::workerId).distinct().count()
            != response.size()) {
      throw malformed("Task-board returned an invalid warehouse driver directory");
    }
    return response.stream()
        .map(
            value ->
                new WarehouseDriverIdentity(
                    value.workerId(),
                    value.displayName().trim(),
                    value.employmentType(),
                    normalize(value.phone()),
                    value.operationalWarehouseId(),
                    value.availableFrom(),
                    value.availableUntil(),
                    value.availabilityKind()))
        .toList();
  }

  /** Publishes one exact planner snapshot with a dedicated least-privilege service token. */
  void registerDriverShiftPlan(
      UUID idempotencyKey, UUID sourceShiftId, DriverShiftPlanSnapshot plan) {
    if (idempotencyKey == null || sourceShiftId == null || plan == null) {
      throw new IllegalArgumentException("Driver shift plan command identity is required");
    }
    transport.putBodiless(
        taskBoardDriverShiftPlanBase + "/" + sourceShiftId,
        idempotencyKey,
        plan,
        DRIVER_SHIFT_PLAN_CLIENT,
        DRIVER_SHIFT_PLAN_SCOPE,
        DEFAULT);
  }

  /** Calls the task-board-owned all-or-nothing replacement boundary with the existing scope. */
  PlanningReplacementResult replacePlanningAssignments(
      UUID sourcePlanId, UUID idempotencyKey, PlanningReplacementSnapshot replacement) {
    if (sourcePlanId == null || idempotencyKey == null || replacement == null) {
      throw new IllegalArgumentException("Planner replacement identity is required");
    }
    PlanningReplacementResponse response =
        transport.put(
            taskBoardPlanningReplacementBase + "/" + sourcePlanId,
            idempotencyKey,
            new PlanningReplacementRequest(
                replacement.warehouseId(),
                replacement.date(),
                replacement.expectedSourcePlanVersion(),
                replacement.replacementPlanVersion(),
                replacement.assignments().stream()
                    .map(
                        item ->
                            new PlanningReplacementTaskRequest(
                                item.externalTaskId(),
                                item.sourceTaskId(),
                                item.taskWarehouseId(),
                                item.scheduledDate(),
                                item.expectedTaskVersion(),
                                item.expectedEntryVersion(),
                                item.targetQueuePosition(),
                                audienceRequest(item.driverAudience())))
                    .toList(),
                replacement.driverShiftPlans().stream()
                    .map(
                        item ->
                            new PlanningReplacementShiftRequest(
                                item.sourceShiftId(), item.plan()))
                    .toList()),
            PlanningReplacementResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Task-board returned an empty planner replacement",
            DEFAULT);
    if (response == null
        || !("APPLIED".equals(response.outcome()) || "REPLAYED".equals(response.outcome()))
        || !sourcePlanId.equals(response.sourcePlanId())
        || response.sourcePlanVersion() != replacement.replacementPlanVersion()
        || !replacement.warehouseId().equals(response.warehouseId())
        || !replacement.date().equals(response.date())
        || response.assignments() == null
        || response.driverShiftPlans() == null) {
      throw malformed("Task-board returned an invalid planner replacement");
    }
    return new PlanningReplacementResult(
        response.outcome(),
        response.sourcePlanId(),
        response.sourcePlanVersion(),
        response.warehouseId(),
        response.date(),
        response.assignments().stream()
            .map(
                item ->
                    new PlanningReplacementTaskResult(
                        item.externalTaskId(),
                        item.taskVersion(),
                        item.entryId(),
                        item.entryVersion(),
                        item.queuePosition()))
            .toList(),
        response.driverShiftPlans().stream()
            .map(
                item ->
                    new PlanningReplacementShiftResult(
                        item.sourceShiftId(),
                        item.shiftPlanVersion(),
                        item.sourcePlanVersion()))
            .toList());
  }

  /** Creates or exactly replays task-board's pre-start execution hold. */
  PlanningReplanPrepareResult preparePlanningReschedule(
      UUID sourcePlanId, UUID idempotencyKey, PlanningReplanPrepareSnapshot request) {
    if (sourcePlanId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Published reschedule preparation identity is required");
    }
    PlanningReplanPrepareResponse response =
        transport.post(
            taskBoardPlanningReplanHoldBase + "/" + sourcePlanId,
            idempotencyKey,
            new PlanningReplanPrepareRequest(
                request.warehouseId(),
                request.date(),
                request.expectedSourcePlanVersion(),
                request.replacementPlanVersion(),
                new PlanningRemovedTaskRequest(
                    request.removedAssignment().externalTaskId(),
                    request.removedAssignment().sourceTaskId(),
                    request.removedAssignment().taskWarehouseId(),
                    request.removedAssignment().scheduledDate(),
                    request.removedAssignment().expectedTaskVersion(),
                    request.removedAssignment().expectedEntryVersion()),
                request.remainingAssignments().stream()
                    .map(
                        item ->
                            new PlanningReplacementTaskRequest(
                                item.externalTaskId(),
                                item.sourceTaskId(),
                                item.taskWarehouseId(),
                                item.scheduledDate(),
                                item.expectedTaskVersion(),
                                item.expectedEntryVersion(),
                                item.targetQueuePosition(),
                                audienceRequest(item.driverAudience())))
                    .toList(),
                request.driverShiftPlans().stream()
                    .map(
                        item ->
                            new PlanningReplacementShiftRequest(
                                item.sourceShiftId(), item.plan()))
                    .toList()),
            PlanningReplanPrepareResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Task-board returned an empty published reschedule hold",
            DEFAULT);
    if (response == null
        || !Set.of("PREPARED", "REPLAYED").contains(response.outcome())
        || response.holdId() == null
        || !sourcePlanId.equals(response.sourcePlanId())
        || response.sourcePlanVersion() != request.expectedSourcePlanVersion()
        || !request.removedAssignment().externalTaskId().equals(response.removedExternalTaskId())) {
      throw malformed("Task-board returned an invalid published reschedule hold");
    }
    return new PlanningReplanPrepareResult(
        response.outcome(),
        response.holdId(),
        response.sourcePlanId(),
        response.sourcePlanVersion(),
        response.removedExternalTaskId());
  }

  /** Commits or exactly replays the task-board half of a published reschedule. */
  PlanningReplanCommitResult commitPlanningReschedule(UUID holdId, UUID idempotencyKey) {
    PlanningReplanCommitResponse response =
        transport.post(
            taskBoardPlanningReplanHoldBase + "/" + holdId + "/commit",
            idempotencyKey,
            Map.of(),
            PlanningReplanCommitResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Task-board returned an empty published reschedule commit",
            DEFAULT);
    if (response == null
        || !Set.of("APPLIED", "REPLAYED").contains(response.outcome())
        || !holdId.equals(response.holdId())
        || response.sourcePlanId() == null
        || response.sourcePlanVersion() < 2
        || response.removedAssignment() == null
        || response.remainingAssignments() == null
        || response.driverShiftPlans() == null) {
      throw malformed("Task-board returned an invalid published reschedule commit");
    }
    return new PlanningReplanCommitResult(
        response.outcome(),
        response.holdId(),
        response.sourcePlanId(),
        response.sourcePlanVersion(),
        new PlanningReplanRemovedTaskResult(
            response.removedAssignment().externalTaskId(),
            response.removedAssignment().taskVersion(),
            response.removedAssignment().status()),
        response.remainingAssignments().stream()
            .map(
                item ->
                    new PlanningReplacementTaskResult(
                        item.externalTaskId(),
                        item.taskVersion(),
                        item.entryId(),
                        item.entryVersion(),
                        item.queuePosition()))
            .toList(),
        response.driverShiftPlans().stream()
            .map(
                item ->
                    new PlanningReplacementShiftResult(
                        item.sourceShiftId(),
                        item.shiftPlanVersion(),
                        item.sourcePlanVersion()))
            .toList());
  }

  /** Releases or exactly replays a hold before the owner commitment changes. */
  PlanningReplanReleaseResult releasePlanningReschedule(UUID holdId, UUID idempotencyKey) {
    PlanningReplanReleaseResponse response =
        transport.post(
            taskBoardPlanningReplanHoldBase + "/" + holdId + "/release",
            idempotencyKey,
            Map.of(),
            PlanningReplanReleaseResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Task-board returned an empty published reschedule release",
            DEFAULT);
    if (response == null
        || !Set.of("RELEASED", "REPLAYED").contains(response.outcome())
        || !holdId.equals(response.holdId())
        || response.sourcePlanId() == null) {
      throw malformed("Task-board returned an invalid published reschedule release");
    }
    return new PlanningReplanReleaseResult(
        response.outcome(), response.holdId(), response.sourcePlanId());
  }

  WorkerOperationalAssignment createWorkerOperationalAssignment(
      UUID transferId,
      UUID workerId,
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      String mode,
      OffsetDateTime travelStartsAt,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveUntil) {
    WorkerOperationalAssignmentResponse response =
        transport.postWithoutIdempotency(
            taskBoardOperationalAssignmentBase,
            new CreateWorkerOperationalAssignmentRequest(
                transferId,
                workerId,
                sourceWarehouseId,
                destinationWarehouseId,
                mode,
                travelStartsAt,
                effectiveFrom,
                effectiveUntil),
            WorkerOperationalAssignmentResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Task-board returned an empty operational driver assignment",
            DEFAULT);
    return operationalAssignment(response);
  }

  WorkerOperationalAssignment transitionWorkerOperationalAssignment(
      UUID assignmentId, long expectedVersion, String targetStatus) {
    WorkerOperationalAssignmentResponse response =
        transport.postWithoutIdempotency(
            taskBoardOperationalAssignmentBase + "/" + assignmentId + "/transition",
            new TransitionWorkerOperationalAssignmentRequest(expectedVersion, targetStatus),
            WorkerOperationalAssignmentResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Task-board returned an empty operational driver assignment transition",
            DEFAULT);
    WorkerOperationalAssignment assignment = operationalAssignment(response);
    if (!assignmentId.equals(assignment.assignmentId())
        || assignment.version() < expectedVersion
        || !targetStatus.equals(assignment.status())) {
      throw malformed("Task-board returned a mismatched operational assignment transition");
    }
    return assignment;
  }

  private static boolean invalidDriverIdentity(
      WarehouseDriverIdentityResponse value, UUID warehouseId, boolean includeIncoming) {
    return value == null
        || value.workerId() == null
        || value.displayName() == null
        || value.displayName().isBlank()
        || value.displayName().length() > 256
        || !("STAFF".equals(value.employmentType())
            || "CONTRACTOR".equals(value.employmentType()))
        || value.operationalWarehouseId() == null
        || !warehouseId.equals(value.operationalWarehouseId())
        || !("HOME".equals(value.availabilityKind())
            || "ACTIVE_ASSIGNMENT".equals(value.availabilityKind())
            || "INCOMING".equals(value.availabilityKind()))
        || (!includeIncoming && "INCOMING".equals(value.availabilityKind()))
        || invalidAvailabilityRange(value.availableFrom(), value.availableUntil())
        || ("CONTRACTOR".equals(value.employmentType())
            && (value.phone() == null
                || value.phone().isBlank()));
  }

  /** Rejects an end without a start while allowing a date-free profile or open-ended assignment. */
  private static boolean invalidAvailabilityRange(
      OffsetDateTime availableFrom, OffsetDateTime availableUntil) {
    return availableUntil != null
        && (availableFrom == null || !availableFrom.isBefore(availableUntil));
  }

  private static String normalize(String value) {
    if (value == null) return null;
    String result = value.trim();
    return result.isEmpty() ? null : result;
  }

  private static WorkerOperationalAssignment operationalAssignment(
      WorkerOperationalAssignmentResponse response) {
    if (response == null
        || response.id() == null
        || response.version() < 0
        || response.transferId() == null
        || response.workerId() == null
        || response.homeWarehouseId() == null
        || response.sourceWarehouseId() == null
        || response.destinationWarehouseId() == null
        || response.sourceWarehouseId().equals(response.destinationWarehouseId())
        || !("TEMPORARY".equals(response.mode())
            || "PERMANENT".equals(response.mode())
            || "TRIP_ONLY".equals(response.mode()))
        || !("PLANNED".equals(response.status())
            || "IN_TRANSIT".equals(response.status())
            || "ACTIVE".equals(response.status())
            || "COMPLETED".equals(response.status())
            || "CANCELLED".equals(response.status()))
        || response.travelStartsAt() == null
        || response.effectiveFrom() == null
        || !response.travelStartsAt().isBefore(response.effectiveFrom())
        || ("TEMPORARY".equals(response.mode())
            && (response.effectiveUntil() == null
                || !response.effectiveUntil().isAfter(response.effectiveFrom())))
        || ("PERMANENT".equals(response.mode()) && response.effectiveUntil() != null)
        || ("TRIP_ONLY".equals(response.mode())
            && !response.effectiveFrom().equals(response.effectiveUntil()))) {
      throw malformed("Task-board returned an invalid operational driver assignment");
    }
    return new WorkerOperationalAssignment(
        response.id(),
        response.version(),
        response.transferId(),
        response.workerId(),
        response.homeWarehouseId(),
        response.sourceWarehouseId(),
        response.destinationWarehouseId(),
        response.mode(),
        response.status(),
        response.travelStartsAt(),
        response.effectiveFrom(),
        response.effectiveUntil());
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
    return registerDriverTask(
        warehouseId,
        externalTaskId,
        sourceId,
        title,
        unitNumber,
        description,
        queueDefinitionId,
        scheduledDate,
        priority,
        driverAudience,
        DriverTaskWorkerContent.empty());
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
      DriverTaskAudience driverAudience,
      DriverTaskWorkerContent workerContent) {
    return registerDriverTask(
        warehouseId,
        externalTaskId,
        sourceId,
        title,
        unitNumber,
        description,
        queueDefinitionId,
        scheduledDate,
        priority,
        driverAudience,
        workerContent,
        null);
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
      DriverTaskAudience driverAudience,
      DriverTaskWorkerContent workerContent,
      DriverTaskPlannerLineage plannerLineage) {
    DriverTaskWorkerContent content =
        workerContent == null ? DriverTaskWorkerContent.empty() : workerContent;
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
                List.of(routeStep(queueDefinitionId, description, content)),
                scheduledDate,
                priority,
                new DriverTaskSourceRequest("LOGISTICS_DRIVER_TASK", sourceId),
                "SCHEDULED",
                audienceRequest(driverAudience),
                plannerLineage == null
                    ? null
                    : new PlannerTaskLineageRequest(
                        plannerLineage.sourcePlanId(),
                        plannerLineage.sourcePlanVersion(),
                        plannerLineage.sourcePlanWarehouseId(),
                        plannerLineage.sourcePlanDate())),
            DriverBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return driverBoardTask(response);
  }

  DriverBoardTask updateDriverTaskBeforeStart(
      UUID externalTaskId,
      long expectedTaskVersion,
      String title,
      String unitNumber,
      String description,
      UUID queueDefinitionId,
      DriverTaskWorkerContent workerContent) {
    if (externalTaskId == null
        || expectedTaskVersion < 0
        || title == null
        || title.isBlank()
        || queueDefinitionId == null) {
      throw new IllegalArgumentException("Invalid pre-start driver task update");
    }
    DriverTaskWorkerContent content =
        workerContent == null ? DriverTaskWorkerContent.empty() : workerContent;
    return driverBoardTask(
        transport.putWithoutIdempotency(
            taskBoardTaskBase + "/" + externalTaskId,
            new PreStartUpdateDriverTaskRequest(
                expectedTaskVersion,
                title,
                unitNumber,
                description,
                null,
                null,
                List.of(routeStep(queueDefinitionId, description, content))),
            DriverBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Dependency returned an empty response",
            DEFAULT));
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

  ContractorTaskExecution readContractorTaskExecution(UUID workerId, UUID externalTaskId) {
    requireContractorTaskIdentity(workerId, externalTaskId);
    ContractorTaskExecution response =
        transport.get(
            contractorTaskPath(workerId, externalTaskId),
            ContractorTaskExecution.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Task-board returned an empty contractor task execution",
            DEFAULT);
    return contractorExecution(response, workerId, externalTaskId);
  }

  ContractorTaskActionResult applyContractorTaskAction(
      UUID workerId,
      UUID externalTaskId,
      UUID entryId,
      UUID idempotencyKey,
      String action,
      long expectedVersion,
      UUID evidenceId) {
    requireContractorTaskIdentity(workerId, externalTaskId);
    if (entryId == null
        || idempotencyKey == null
        || expectedVersion < 0
        || !("START".equals(action) || "COMPLETE".equals(action))
        || ("START".equals(action) && evidenceId != null)) {
      throw new IllegalArgumentException("Contractor task action is invalid");
    }
    ContractorTaskActionResponse response =
        transport.post(
            contractorTaskPath(workerId, externalTaskId) + "/entries/" + entryId + "/actions",
            idempotencyKey,
            new ContractorTaskActionRequest(idempotencyKey, action, expectedVersion, evidenceId),
            ContractorTaskActionResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Task-board returned an empty contractor task action",
            DEFAULT);
    if (response == null
        || !"APPLIED".equals(response.outcome())
        || response.currentVersion() < 0) {
      throw malformed("Task-board returned an invalid contractor task action");
    }
    ContractorTaskExecution task = contractorExecution(response.task(), workerId, externalTaskId);
    boolean currentEntry =
        task.route().stream()
            .anyMatch(
                entry ->
                    entryId.equals(entry.entryId())
                        && entry.version() == response.currentVersion());
    if (!currentEntry) {
      throw malformed("Task-board returned a mismatched contractor task action");
    }
    return new ContractorTaskActionResult(response.currentVersion(), task);
  }

  ContractorEvidenceReservation reserveContractorTaskEvidence(
      UUID workerId,
      UUID externalTaskId,
      UUID entryId,
      UUID evidenceId,
      OffsetDateTime capturedAt,
      String contentType,
      long sizeBytes,
      String sha256) {
    requireContractorTaskIdentity(workerId, externalTaskId);
    if (entryId == null
        || evidenceId == null
        || capturedAt == null
        || !("image/jpeg".equals(contentType) || "image/webp".equals(contentType))
        || sizeBytes < 1
        || sizeBytes > ("image/jpeg".equals(contentType) ? 15_728_640 : 1_048_576)
        || sha256 == null
        || !SHA256.matcher(sha256).matches()) {
      throw new IllegalArgumentException("Contractor task evidence reservation is invalid");
    }
    ContractorEvidenceReservation response =
        transport.post(
            contractorTaskPath(workerId, externalTaskId)
                + "/entries/"
                + entryId
                + "/evidence-reservations",
            evidenceId,
            new ContractorEvidenceReservationRequest(
                evidenceId, evidenceId, capturedAt, contentType, sizeBytes, sha256),
            ContractorEvidenceReservation.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE,
            "Task-board returned an empty contractor evidence reservation",
            DEFAULT);
    if (response == null
        || !evidenceId.equals(response.evidenceId())
        || response.version() < 0
        || !CONTRACTOR_EVIDENCE_STATES.contains(response.state())
        || !entryId.equals(response.entryId())
        || !"TASK_BOARD_ENTRY".equals(response.ownerType())
        || !entryId.equals(response.ownerId())
        || response.warehouseId() == null
        || !evidenceId.equals(response.clientReferenceId())
        || response.capturedAt() == null
        || !capturedAt.toInstant().equals(response.capturedAt().toInstant())
        || !contentType.equals(response.contentType())
        || sizeBytes != response.sizeBytes()
        || !sha256.equals(response.sha256())) {
      throw malformed("Task-board returned an invalid contractor evidence reservation");
    }
    return response;
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

  private String contractorTaskPath(UUID workerId, UUID externalTaskId) {
    return taskBoardContractorExecutionBase + "/" + workerId + "/tasks/" + externalTaskId;
  }

  private static void requireContractorTaskIdentity(UUID workerId, UUID externalTaskId) {
    if (workerId == null || externalTaskId == null) {
      throw new IllegalArgumentException("Contractor task identity is required");
    }
  }

  private static ContractorTaskExecution contractorExecution(
      ContractorTaskExecution response, UUID workerId, UUID externalTaskId) {
    if (response == null
        || !workerId.equals(response.workerId())
        || !externalTaskId.equals(response.externalTaskId())
        || response.taskId() == null
        || response.taskVersion() < 0
        || response.warehouseId() == null
        || invalidRequiredText(response.title(), 256)
        || invalidOptionalText(response.description(), 2_000)
        || invalidOptionalText(response.unitNumber(), 64)
        || response.scheduledDate() == null
        || response.priority() < 1
        || response.priority() > 5
        || !("ACTIVE".equals(response.status())
            || "DONE".equals(response.status())
            || "CANCELLED".equals(response.status()))
        || response.source() == null
        || !"LOGISTICS_DRIVER_TASK".equals(response.source().type())
        || response.source().sourceId() == null
        || response.route() == null
        || response.route().isEmpty()) {
      throw malformed("Task-board returned an invalid contractor task execution");
    }
    Set<UUID> entryIds = new java.util.HashSet<>();
    for (int index = 0; index < response.route().size(); index++) {
      ContractorTaskRouteEntry entry = response.route().get(index);
      if (entry == null
          || entry.entryId() == null
          || !entryIds.add(entry.entryId())
          || entry.version() < 0
          || entry.routeIndex() < 0
          || entry.routeStepIndex() != index
          || entry.routeStepCount() != response.route().size()
          || entry.queueName() == null
          || entry.queueName().isBlank()
          || !("WAITING".equals(entry.status())
              || "IN_PROGRESS".equals(entry.status())
              || "PAUSED".equals(entry.status())
              || "DONE".equals(entry.status())
              || "CANCELLED".equals(entry.status()))
          || (entry.plannedDurationMinutes() != null && entry.plannedDurationMinutes() < 0)
          || invalidOptionalText(entry.taskText(), 2_000)
          || entry.resultPhotoMinCount() < 0
          || entry.works() == null
          || entry.materials() == null
          || entry.comments() == null
          || entry.sourceMedia() == null
          || entry.evidence() == null
          || entry.works().size() > 100
          || entry.materials().size() > 100
          || entry.comments().size() > 100
          || entry.sourceMedia().size() > 100
          || entry.evidence().size() > 100
          || entry.works().stream().anyMatch(java.util.Objects::isNull)
          || entry.materials().stream().anyMatch(java.util.Objects::isNull)
          || entry.comments().stream().anyMatch(java.util.Objects::isNull)
          || entry.sourceMedia().stream().anyMatch(java.util.Objects::isNull)
          || entry.evidence().stream().anyMatch(java.util.Objects::isNull)
          || invalidContractorEntryContent(entry)) {
        throw malformed("Task-board returned an invalid contractor task route");
      }
    }
    return response;
  }

  private static boolean invalidContractorEntryContent(ContractorTaskRouteEntry entry) {
    Set<UUID> sourceMediaIds = new java.util.HashSet<>();
    if (entry.sourceMedia().stream()
        .anyMatch(
            media ->
                media.mediaId() == null
                    || !sourceMediaIds.add(media.mediaId())
                    || media.generation() < 1
                    || invalidOptionalText(media.contentType(), 128)
                    || media.recordedAt() == null)) {
      return true;
    }
    Set<UUID> workIds = new java.util.HashSet<>();
    if (entry.works().stream()
        .anyMatch(
            work ->
                work.id() == null
                    || !workIds.add(work.id())
                    || invalidRequiredText(work.name(), 1_000)
                    || !Double.isFinite(work.quantity())
                    || work.quantity() < 0
                    || invalidOptionalText(work.unit(), 32)
                    || (work.durationMinutes() != null && work.durationMinutes() < 0)
                    || invalidOptionalText(work.comment(), 2_000)
                    || work.sourceMediaIds() == null
                    || work.sourceMediaIds().size() > 100
                    || work.sourceMediaIds().stream().anyMatch(java.util.Objects::isNull)
                    || work.sourceMediaIds().stream().distinct().count()
                        != work.sourceMediaIds().size()
                    || !sourceMediaIds.containsAll(work.sourceMediaIds()))) {
      return true;
    }
    Set<UUID> materialIds = new java.util.HashSet<>();
    if (entry.materials().stream()
        .anyMatch(
            material ->
                material.id() == null
                    || !materialIds.add(material.id())
                    || invalidRequiredText(material.name(), 1_000)
                    || !Double.isFinite(material.quantity())
                    || material.quantity() < 0
                    || invalidOptionalText(material.unit(), 32))) {
      return true;
    }
    Set<UUID> commentIds = new java.util.HashSet<>();
    if (entry.comments().stream()
        .anyMatch(
            comment ->
                comment.id() == null
                    || !commentIds.add(comment.id())
                    || invalidRequiredText(comment.text(), 2_000)
                    || invalidOptionalText(comment.authorDisplayName(), 256)
                    || comment.createdAt() == null)) {
      return true;
    }
    Set<UUID> evidenceIds = new java.util.HashSet<>();
    return entry.evidence().stream()
        .anyMatch(
            evidence ->
                evidence.evidenceId() == null
                    || !evidenceIds.add(evidence.evidenceId())
                    || evidence.version() < 0
                    || evidence.capturedAt() == null
                    || evidence.recordedAt() == null
                    || !("RESERVED".equals(evidence.state())
                        || "UPLOADING".equals(evidence.state())
                        || "READY".equals(evidence.state())
                        || "REVIEW_REQUIRED".equals(evidence.state())
                        || "REJECTED".equals(evidence.state()))
                    || (evidence.mediaId() == null) != (evidence.mediaGeneration() == null)
                    || (evidence.mediaGeneration() != null && evidence.mediaGeneration() < 1)
                    || ("READY".equals(evidence.state()) && evidence.mediaId() == null)
                    || invalidOptionalText(evidence.reviewReason(), 512)
                    || !("image/jpeg".equals(evidence.contentType())
                        || "image/webp".equals(evidence.contentType())));
  }

  private static boolean invalidRequiredText(String value, int maximum) {
    return value == null || value.isBlank() || value.length() > maximum;
  }

  private static boolean invalidOptionalText(String value, int maximum) {
    return value != null && value.length() > maximum;
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

  /** Least-privilege task-board directory response for one qualified worker. */
  private record WarehouseDriverIdentityResponse(
      UUID workerId,
      String displayName,
      String employmentType,
      String phone,
      UUID operationalWarehouseId,
      OffsetDateTime availableFrom,
      OffsetDateTime availableUntil,
      String availabilityKind) {}

  /** Transfer-backed driver placement creation payload. */
  private record CreateWorkerOperationalAssignmentRequest(
      UUID transferId,
      UUID workerId,
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      String mode,
      OffsetDateTime travelStartsAt,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveUntil) {}

  /** Expected-version operational placement lifecycle payload. */
  private record TransitionWorkerOperationalAssignmentRequest(
      long expectedVersion, String targetStatus) {}

  /** Authoritative task-board operational assignment wire response. */
  private record WorkerOperationalAssignmentResponse(
      UUID id,
      long version,
      UUID transferId,
      UUID workerId,
      UUID homeWarehouseId,
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      String mode,
      String status,
      OffsetDateTime travelStartsAt,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveUntil,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      String createdBy,
      String updatedBy) {}

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

  /** Task-board wire request for one existing task in a complete replacement. */
  private record PlanningReplacementTaskRequest(
      UUID externalTaskId,
      UUID sourceTaskId,
      UUID taskWarehouseId,
      LocalDate scheduledDate,
      long expectedTaskVersion,
      long expectedEntryVersion,
      int targetQueuePosition,
      DriverTaskAudienceRequest driverAudience) {}

  /** Task-board wire request for one stable driver shift identity. */
  private record PlanningReplacementShiftRequest(
      UUID sourceShiftId, DriverShiftPlanSnapshot plan) {}

  /** Complete task-board wire request for an atomic planner replacement. */
  private record PlanningReplacementRequest(
      UUID warehouseId,
      LocalDate date,
      long expectedSourcePlanVersion,
      long replacementPlanVersion,
      List<PlanningReplacementTaskRequest> assignments,
      List<PlanningReplacementShiftRequest> driverShiftPlans) {}

  /** Authoritative task-board wire result for one replaced task. */
  private record PlanningReplacementTaskResponse(
      UUID externalTaskId,
      long taskVersion,
      UUID entryId,
      long entryVersion,
      int queuePosition) {}

  /** Authoritative task-board wire result for one replaced shift plan. */
  private record PlanningReplacementShiftResponse(
      UUID sourceShiftId, long shiftPlanVersion, long sourcePlanVersion) {}

  /** Complete task-board wire result for an atomic planner replacement. */
  private record PlanningReplacementResponse(
      String outcome,
      UUID sourcePlanId,
      long sourcePlanVersion,
      UUID warehouseId,
      LocalDate date,
      List<PlanningReplacementTaskResponse> assignments,
      List<PlanningReplacementShiftResponse> driverShiftPlans) {}

  /** Wire task fence removed from the old-day source plan at COMMIT. */
  private record PlanningRemovedTaskRequest(
      UUID externalTaskId,
      UUID sourceTaskId,
      UUID taskWarehouseId,
      LocalDate scheduledDate,
      long expectedTaskVersion,
      long expectedEntryVersion) {}

  /** Wire PREPARE body for the complete old-day membership. */
  private record PlanningReplanPrepareRequest(
      UUID warehouseId,
      LocalDate date,
      long expectedSourcePlanVersion,
      long replacementPlanVersion,
      PlanningRemovedTaskRequest removedAssignment,
      List<PlanningReplacementTaskRequest> remainingAssignments,
      List<PlanningReplacementShiftRequest> driverShiftPlans) {}

  /** Wire task-board hold receipt. */
  private record PlanningReplanPrepareResponse(
      String outcome,
      UUID holdId,
      UUID sourcePlanId,
      long sourcePlanVersion,
      UUID removedExternalTaskId) {}

  /** Wire old-day task tombstone. */
  private record PlanningRemovedTaskResponse(
      UUID externalTaskId, long taskVersion, String status) {}

  /** Wire task-board COMMIT receipt. */
  private record PlanningReplanCommitResponse(
      String outcome,
      UUID holdId,
      UUID sourcePlanId,
      long sourcePlanVersion,
      PlanningRemovedTaskResponse removedAssignment,
      List<PlanningReplacementTaskResponse> remainingAssignments,
      List<PlanningReplacementShiftResponse> driverShiftPlans) {}

  /** Wire task-board RELEASE receipt. */
  private record PlanningReplanReleaseResponse(
      String outcome, UUID holdId, UUID sourcePlanId) {}

  /** Planner lineage sent only for a newly published route-planner task. */
  private record PlannerTaskLineageRequest(
      UUID sourcePlanId,
      long sourcePlanVersion,
      UUID sourcePlanWarehouseId,
      LocalDate sourcePlanDate) {}

  /** Sanitized immutable work snapshot sent through task-board to WorkerApp. */
  private record DriverWorkSnapshotRequest(
      UUID id,
      String name,
      double quantity,
      String unit,
      Integer durationMinutes,
      String comment,
      List<UUID> sourceMediaIds) {}

  /** Sanitized immutable material snapshot sent through task-board to WorkerApp. */
  private record DriverMaterialSnapshotRequest(
      UUID id, String name, double quantity, String unit) {}

  /** Sanitized immutable worker-visible logistics comment. */
  private record DriverCommentSnapshotRequest(
      UUID id, String text, String authorDisplayName, OffsetDateTime createdAt) {}

  /** Desired task-board route step with queue, text, planned effort, and worker content. */
  private record DriverRouteStepRequest(
      UUID queueDefinitionId,
      String taskText,
      Integer plannedDurationMinutes,
      List<DriverWorkSnapshotRequest> works,
      List<DriverMaterialSnapshotRequest> materials,
      List<DriverCommentSnapshotRequest> comments,
      List<DriverSourceMediaSnapshotRequest> sourceMedia) {}

  /** Immutable source-media revision resolved by task-board through its established worker path. */
  private record DriverSourceMediaSnapshotRequest(
      UUID mediaId,
      long generation,
      String contentType,
      OffsetDateTime capturedAt,
      OffsetDateTime recordedAt) {}

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
      DriverTaskAudienceRequest driverAudience,
      PlannerTaskLineageRequest plannerLineage) {}

  /** Complete source-owned route replacement accepted only before driver execution begins. */
  private record PreStartUpdateDriverTaskRequest(
      long expectedTaskVersion,
      String title,
      String unitNumber,
      String description,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<DriverRouteStepRequest> route) {}

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

  private static DriverRouteStepRequest routeStep(
      UUID queueDefinitionId, String fallbackTaskText, DriverTaskWorkerContent content) {
    String taskText = content.taskText() == null ? fallbackTaskText : content.taskText();
    return new DriverRouteStepRequest(
        queueDefinitionId,
        taskText,
        null,
        content.works().stream()
            .map(
                work ->
                    new DriverWorkSnapshotRequest(
                        work.id(),
                        work.name(),
                        work.quantity(),
                        work.unit(),
                        work.durationMinutes(),
                        work.comment(),
                        work.sourceMediaIds()))
            .toList(),
        content.materials().stream()
            .map(
                material ->
                    new DriverMaterialSnapshotRequest(
                        material.id(),
                        material.name(),
                        material.quantity(),
                        material.unit()))
            .toList(),
        content.comments().stream()
            .map(
                comment ->
                    new DriverCommentSnapshotRequest(
                        comment.id(),
                        comment.text(),
                        comment.authorDisplayName(),
                        comment.createdAt()))
            .toList(),
        content.sourceMedia().stream()
            .map(
                media ->
                    new DriverSourceMediaSnapshotRequest(
                        media.mediaId(),
                        media.generation(),
                        media.contentType(),
                        media.capturedAt(),
                        media.recordedAt()))
            .toList());
  }

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

  /** Exact immutable public-route action sent with the same operation and idempotency identity. */
  private record ContractorTaskActionRequest(
      UUID operationId, String action, long expectedVersion, UUID evidenceId) {}

  /** Exact task-board action response before owner-identity validation. */
  private record ContractorTaskActionResponse(
      String outcome, long currentVersion, ContractorTaskExecution task) {}

  /** Exact evidence reservation request whose operation and idempotency identities are equal. */
  private record ContractorEvidenceReservationRequest(
      UUID operationId,
      UUID evidenceId,
      OffsetDateTime capturedAt,
      String contentType,
      long sizeBytes,
      String sha256) {}
}
