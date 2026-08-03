package dev.buhanzaz.rwms.taskboard.api;

import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.AudienceMode;
import dev.buhanzaz.rwms.taskboard.domain.CredentialStatus;
import dev.buhanzaz.rwms.taskboard.domain.EquipmentMovementDirection;
import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.GroupOperationalStatus;
import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.QueueReferenceType;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.TaskLane;
import dev.buhanzaz.rwms.taskboard.domain.TaskSourceType;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ApiModels {
  private ApiModels() {}

  public record WorkerClassRequest(
      @NotNull @Min(0) Long version,
      @NotBlank @Size(max = 128) String name,
      @Size(max = 1000) String description,
      @Size(max = 1000) String comment,
      int sortOrder,
      boolean active) {}

  public record WorkerClassDto(
      UUID id,
      long version,
      String name,
      String description,
      String comment,
      int sortOrder,
      boolean active,
      boolean logisticsPrimary) {}

  public record QueueBindingRequest(
      @NotNull UUID workerClassId,
      @Min(0) int order,
      boolean stopTaskOnTake,
      @NotNull ParticipationPolicy participationPolicy,
      boolean notifyOnPrimaryTake) {
    public QueueBindingRequest(UUID workerClassId, boolean stopTaskOnTake) {
      this(workerClassId, 0, stopTaskOnTake, ParticipationPolicy.PRIMARY, false);
    }
  }

  public record QueueDefinitionRequest(
      @NotNull @Min(0) Long version,
      @NotBlank @Size(max = 128) String name,
      @Size(max = 1000) String description,
      @NotNull QueueType type,
      @NotNull QueuePurpose purpose) {
    public QueueDefinitionRequest(
        Long version, String name, String description, QueueType type) {
      this(version, name, description, type, QueuePurpose.GENERAL);
    }
  }

  public record QueueDefinitionDto(
      UUID id,
      long version,
      String name,
      String description,
      QueueType type,
      QueuePurpose purpose) {}

  /** A warehouse-local connection to one immutable shared queue definition. */
  public record WorkQueueRequest(
      @NotNull @Min(0) Long version,
      @NotNull UUID definitionId,
      boolean active,
      boolean hidden,
      boolean collapsed,
      @Min(0) Integer holdingPeriodMinutes,
      @Min(0) Integer notificationThreshold,
      boolean notifyWhenThresholdReached,
      @Min(0) @Max(20) Integer resultPhotoMinCount,
      List<@Valid QueueBindingRequest> bindings) {}

  public record DriverQueueRequest(
      @NotNull @Min(0) Long expectedVersion,
      boolean active,
      boolean hidden,
      boolean collapsed,
      @Min(0) Integer holdingPeriodMinutes,
      @Min(0) Integer notificationThreshold,
      boolean notifyWhenThresholdReached,
      @NotNull @Min(0) @Max(20) Integer resultPhotoMinCount,
      @NotNull List<@Valid QueueBindingRequest> bindings) {}

  public record QueueBindingDto(
      UUID id,
      long version,
      WorkerClassDto workerClass,
      int order,
      boolean primary,
      boolean stopTaskOnTake,
      ParticipationPolicy participationPolicy,
      boolean notifyOnPrimaryTake) {}

  public record WorkQueueDto(
      UUID id,
      long version,
      UUID warehouseId,
      UUID definitionId,
      long definitionVersion,
      String name,
      String description,
      QueueType type,
      QueuePurpose purpose,
      int sortOrder,
      boolean active,
      boolean hidden,
      boolean collapsed,
      Integer holdingPeriodMinutes,
      Integer notificationThreshold,
      boolean notifyWhenThresholdReached,
      int resultPhotoMinCount,
      List<QueueBindingDto> bindings) {}

  public record QueueOrderItem(
      @NotNull UUID queueId, @NotNull @Min(0) Long expectedVersion) {}

  public record QueueOrderRequest(@NotEmpty List<@Valid QueueOrderItem> queues) {}

  public record QualificationRequest(
      @NotNull UUID workerClassId, boolean active, @Size(max = 1000) String comment) {}

  public record WorkerRequest(
      @NotNull @Min(0) Long version,
      @NotBlank @Size(max = 256) String displayName,
      @Size(max = 128) String firstName,
      @Size(max = 128) String lastName,
      @Size(max = 128) String middleName,
      boolean active,
      @Size(max = 1000) String comment,
      @Size(max = 128) String appLogin,
      @Size(min = 8, max = 256) String password,
      List<@Valid QualificationRequest> qualifications) {
    @Override
    public String toString() {
      return "WorkerRequest[version="
          + version
          + ", displayName="
          + displayName
          + ", appLogin="
          + appLogin
          + ", password=<redacted>]";
    }
  }

  public record QualificationDto(
      UUID id, long version, WorkerClassDto workerClass, boolean active, String comment) {}

  public record WorkerDto(
      UUID id,
      long version,
      UUID warehouseId,
      String displayName,
      String firstName,
      String lastName,
      String middleName,
      boolean active,
      String comment,
      String appLogin,
      CredentialStatus credentialStatus,
      String credentialError,
      UUID currentGroupId,
      String currentGroupName,
      GroupOperationalStatus operationalAvailability,
      List<QualificationDto> qualifications) {}

  public record SetCurrentGroupRequest(
      @NotNull @Min(0) Long expectedVersion, UUID workerGroupId) {}

  public record CredentialPasswordRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotBlank @Size(min = 8, max = 256) String password) {
    @Override
    public String toString() {
      return "CredentialPasswordRequest[expectedVersion="
          + expectedVersion
          + ", password=<redacted>]";
    }
  }

  public record GroupMemberRequest(@NotNull UUID workerId, boolean active) {
    public GroupMemberRequest(UUID workerId, String ignoredRoleInGroup, boolean active) {
      this(workerId, active);
    }
  }

  public record WorkerGroupRequest(
      @NotNull @Min(0) Long version,
      @NotNull UUID workerClassId,
      @NotBlank @Size(max = 128) String name,
      @Size(max = 1000) String description,
      boolean active,
      List<@Valid GroupMemberRequest> members) {}

  public record GroupMemberDto(
      UUID id,
      long version,
      UUID workerId,
      String workerName,
      boolean active) {}

  public record WorkerGroupDto(
      UUID id,
      long version,
      UUID warehouseId,
      WorkerClassDto workerClass,
      String name,
      String description,
      boolean active,
      GroupOperationalStatus operationalStatus,
      OffsetDateTime unavailableSince,
      String unavailabilityReason,
      List<GroupMemberDto> members) {}

  public record GroupAvailabilityRequest(
      @NotNull @Min(0) Long expectedVersion,
      @Size(max = 1000) String reason) {}

  public record MaintenanceRoutingQueueRequirement(
      @NotNull UUID queueDefinitionId,
      @NotNull QueueType type) {}

  public record CatalogRoutingPreflightRequest(
      @NotEmpty @Size(max = 100) List<@Valid MaintenanceRoutingQueueRequirement> queues) {}

  public record CatalogRoutingMismatch(
      UUID queueDefinitionId, List<MaintenanceRoutingMismatchField> fields) {
    public CatalogRoutingMismatch {
      fields = List.copyOf(fields);
    }
  }

  public record CatalogRoutingResolvedDefinition(
      UUID queueDefinitionId, String name, QueueType type) {}

  public record CatalogRoutingPreflightResponse(
      boolean ready,
      List<UUID> missingQueueDefinitionIds,
      List<CatalogRoutingMismatch> mismatches,
      List<CatalogRoutingResolvedDefinition> resolvedDefinitions) {
    public CatalogRoutingPreflightResponse {
      missingQueueDefinitionIds = List.copyOf(missingQueueDefinitionIds);
      mismatches = List.copyOf(mismatches);
      resolvedDefinitions = List.copyOf(resolvedDefinitions);
    }
  }

  public record MaintenanceRoutingPreflightRequest(
      @NotNull UUID warehouseId,
      @NotEmpty @Size(max = 100) List<@Valid MaintenanceRoutingQueueRequirement> queues) {}

  public enum MaintenanceRoutingMismatchField {
    TYPE,
    ACTIVE,
    HIDDEN
  }

  public record MaintenanceRoutingMismatch(
      UUID queueDefinitionId, List<MaintenanceRoutingMismatchField> fields) {
    public MaintenanceRoutingMismatch {
      fields = List.copyOf(fields);
    }
  }

  public record MaintenanceRoutingResolvedQueue(
      UUID queueDefinitionId,
      UUID workQueueId,
      String name,
      QueueType type) {}

  public record MaintenanceRoutingPreflightResponse(
      UUID warehouseId,
      boolean ready,
      List<UUID> missingQueueDefinitionIds,
      List<UUID> missingWarehouseBindingDefinitionIds,
      List<MaintenanceRoutingMismatch> mismatches,
      List<MaintenanceRoutingResolvedQueue> resolvedQueues) {
    public MaintenanceRoutingPreflightResponse {
      missingQueueDefinitionIds = List.copyOf(missingQueueDefinitionIds);
      missingWarehouseBindingDefinitionIds = List.copyOf(missingWarehouseBindingDefinitionIds);
      mismatches = List.copyOf(mismatches);
      resolvedQueues = List.copyOf(resolvedQueues);
    }
  }

  public record MovementQueueCapability(UUID queueDefinitionId, UUID workQueueId) {}

  public record WarehouseQueueCapabilities(
      UUID warehouseId,
      boolean movementToShipmentAvailable,
      List<MovementQueueCapability> movementQueueDefinitions) {
    public WarehouseQueueCapabilities {
      movementQueueDefinitions = List.copyOf(movementQueueDefinitions);
    }
  }

  public record TaskMaterialSnapshotRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 1000) String name,
      @PositiveOrZero double quantity,
      @Size(max = 32) String unit) {}

  public record TaskWorkSnapshotRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 1000) String name,
      @PositiveOrZero double quantity,
      @Size(max = 32) String unit,
      @Min(0) Integer durationMinutes,
      @Size(max = 2000) String comment,
      @NotNull @Size(max = 100) List<@NotNull UUID> sourceMediaIds) {
    public TaskWorkSnapshotRequest {
      sourceMediaIds = sourceMediaIds == null ? List.of() : List.copyOf(sourceMediaIds);
      if (sourceMediaIds.stream().distinct().count() != sourceMediaIds.size()) {
        throw new IllegalArgumentException("Work source media identities must be unique");
      }
    }

    public TaskWorkSnapshotRequest(
        UUID id,
        String name,
        double quantity,
        String unit,
        Integer durationMinutes,
        String comment) {
      this(id, name, quantity, unit, durationMinutes, comment, List.of());
    }
  }

  public record TaskCommentSnapshotRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 2000) String text,
      @Size(max = 256) String authorDisplayName,
      @NotNull OffsetDateTime createdAt) {}

  public record TaskSourceMediaSnapshotRequest(
      @NotNull UUID mediaId,
      @Min(1) long generation,
      @Size(max = 128) String contentType,
      OffsetDateTime capturedAt,
      @NotNull OffsetDateTime recordedAt) {}

  public record RouteStepRequest(
      @NotNull UUID queueDefinitionId,
      @Size(max = 2000) String taskText,
      @Min(0) Integer plannedDurationMinutes,
      @Size(max = 100) List<@Valid TaskWorkSnapshotRequest> works,
      @Size(max = 100) List<@Valid TaskMaterialSnapshotRequest> materials,
      @Size(max = 100) List<@Valid TaskCommentSnapshotRequest> comments,
      @Size(max = 100) List<@Valid TaskSourceMediaSnapshotRequest> sourceMedia) {
    public RouteStepRequest {
      works = works == null ? List.of() : List.copyOf(works);
      materials = materials == null ? List.of() : List.copyOf(materials);
      comments = comments == null ? List.of() : List.copyOf(comments);
      sourceMedia = sourceMedia == null ? List.of() : List.copyOf(sourceMedia);
    }

    public RouteStepRequest(
        UUID queueDefinitionId,
        String taskText,
        Integer plannedDurationMinutes) {
      this(
          queueDefinitionId,
          taskText,
          plannedDurationMinutes,
          List.of(),
          List.of(),
          List.of(),
          List.of());
    }

    public RouteStepRequest(
        UUID queueDefinitionId,
        String taskText,
        Integer plannedDurationMinutes,
        List<TaskMaterialSnapshotRequest> materials,
        List<TaskCommentSnapshotRequest> comments,
        List<TaskSourceMediaSnapshotRequest> sourceMedia) {
      this(
          queueDefinitionId,
          taskText,
          plannedDurationMinutes,
          List.of(),
          materials,
          comments,
          sourceMedia);
    }
  }

  public record TaskWorkerContentDto(
      List<TaskWorkSnapshotRequest> works,
      List<TaskMaterialSnapshotRequest> materials,
      List<TaskCommentSnapshotRequest> comments,
      List<TaskSourceMediaSnapshotRequest> sourceMedia) {
    public TaskWorkerContentDto {
      works = List.copyOf(works);
      materials = List.copyOf(materials);
      comments = List.copyOf(comments);
      sourceMedia = List.copyOf(sourceMedia);
    }

    public TaskWorkerContentDto(
        List<TaskMaterialSnapshotRequest> materials,
        List<TaskCommentSnapshotRequest> comments,
        List<TaskSourceMediaSnapshotRequest> sourceMedia) {
      this(List.of(), materials, comments, sourceMedia);
    }
  }

  public record CreateBoardTaskRequest(
      UUID externalTaskId,
      @NotBlank @Size(max = 256) String title,
      @Size(max = 64) String unitNumber,
      @Size(max = 2000) String description,
      @Min(0) Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      @NotEmpty List<@Valid RouteStepRequest> route,
      LocalDate scheduledDate,
      @Min(1) @Max(5) Integer priority) {
    public CreateBoardTaskRequest(
        UUID externalTaskId,
        String title,
        String unitNumber,
        String description,
        Integer plannedDurationMinutes,
        OffsetDateTime deadlineAt,
        List<RouteStepRequest> route) {
      this(
          externalTaskId,
          title,
          unitNumber,
          description,
          plannedDurationMinutes,
          deadlineAt,
          route,
          null,
          null);
    }
  }

  public record TaskSourceReferenceDto(
      @NotNull TaskSourceType type, @NotNull UUID sourceId) {}

  public record RegisterExternalTaskRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID externalTaskId,
      @NotBlank @Size(max = 256) String title,
      @Size(max = 64) String unitNumber,
      @Size(max = 2000) String description,
      @Min(0) Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      @NotEmpty List<@Valid RouteStepRequest> route,
      LocalDate scheduledDate,
      @Min(1) @Max(5) Integer priority,
      @Min(1) Integer dailyCapacity,
      @Valid TaskSourceReferenceDto source,
      TaskLane lane) {
    public RegisterExternalTaskRequest(
        UUID warehouseId,
        UUID externalTaskId,
        String title,
        String unitNumber,
        String description,
        Integer plannedDurationMinutes,
        OffsetDateTime deadlineAt,
        List<RouteStepRequest> route) {
      this(
          warehouseId,
          externalTaskId,
          title,
          unitNumber,
          description,
          plannedDurationMinutes,
          deadlineAt,
          route,
          null,
          null,
          null,
          null,
          TaskLane.SCHEDULED);
    }

    public RegisterExternalTaskRequest(
        UUID warehouseId,
        UUID externalTaskId,
        String title,
        String unitNumber,
        String description,
        Integer plannedDurationMinutes,
        OffsetDateTime deadlineAt,
        List<RouteStepRequest> route,
        LocalDate scheduledDate,
        Integer priority) {
      this(
          warehouseId,
          externalTaskId,
          title,
          unitNumber,
          description,
          plannedDurationMinutes,
          deadlineAt,
          route,
          scheduledDate,
          priority,
          null,
          null,
          TaskLane.SCHEDULED);
    }

    public RegisterExternalTaskRequest(
        UUID warehouseId,
        UUID externalTaskId,
        String title,
        String unitNumber,
        String description,
        Integer plannedDurationMinutes,
        OffsetDateTime deadlineAt,
        List<RouteStepRequest> route,
        LocalDate scheduledDate,
        Integer priority,
        Integer dailyCapacity) {
      this(
          warehouseId,
          externalTaskId,
          title,
          unitNumber,
          description,
          plannedDurationMinutes,
          deadlineAt,
          route,
          scheduledDate,
          priority,
          dailyCapacity,
          null,
          TaskLane.SCHEDULED);
    }
  }

  /**
   * Logistics supplies immutable equipment facts only. Task-board owns the title,
   * description, queue and route generated from these facts.
   */
  public record RegisterLogisticsEquipmentMovementTaskRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID externalTaskId,
      @Size(max = 64) String unitNumber,
      @Min(0) Integer plannedDurationMinutes,
      @NotNull OffsetDateTime deadlineAt,
      @NotEmpty @Size(max = 10) List<@Valid EquipmentMovementOperation> operations) {}

  public record EquipmentMovementOperation(
      @NotNull EquipmentMovementDirection direction,
      @NotNull UUID equipmentId,
      @NotBlank @Size(max = 255) String equipmentName,
      @NotNull @Min(1) Long quantity) {}

  public record CancelLogisticsEquipmentMovementTaskRequest(
      @NotNull @Min(0) Long expectedTaskVersion) {}

  public record LogisticsTaskSnapshot(
      UUID taskId,
      long taskVersion,
      UUID warehouseId,
      UUID externalTaskId,
      TaskStatus status,
      OffsetDateTime doneAt) {}

  public record PreStartUpdateTaskRequest(
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotBlank @Size(max = 256) String title,
      @Size(max = 64) String unitNumber,
      @Size(max = 2000) String description,
      @Min(0) Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      @NotEmpty List<@Valid RouteStepRequest> route) {}

  public record RelocateExternalTaskRequest(
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotNull UUID targetWarehouseId) {}

  public record TakeEntryRequest(
      @NotNull @Min(0) Long expectedVersion, UUID workerGroupId, UUID workerId) {}

  public record VersionCommand(@NotNull @Min(0) Long expectedVersion) {}

  public record SetTaskLaneRequest(
      @NotNull @Min(0) Long expectedTaskVersion, @NotNull TaskLane lane) {}

  public record SelectedCompletionEvidenceDto(
      UUID externalTaskId,
      UUID taskId,
      UUID entryId,
      UUID evidenceId,
      UUID mediaId,
      long mediaGeneration,
      UUID warehouseId,
      OffsetDateTime recordedAt) {}

  public record CancelTaskRequest(
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotBlank @Size(max = 1000) String reason) {}

  public record CancelledTaskDto(
      UUID taskId,
      UUID externalTaskId,
      long taskVersion,
      TaskStatus status,
      OffsetDateTime cancelledAt) {}

  public record RegisteredRouteStepDto(
      UUID entryId,
      long entryVersion,
      UUID queueDefinitionId,
      UUID workQueueId,
      String queueName,
      int routeIndex,
      int queuePosition,
      EntryType entryType,
      EntryStatus status,
      String taskText,
      Integer plannedDurationMinutes) {}

  public record BoardTaskRegistrationDto(
      UUID taskId,
      long taskVersion,
      UUID warehouseId,
      UUID externalTaskId,
      String title,
      String unitNumber,
      String description,
      TaskStatus status,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      LocalDate scheduledDate,
      TaskLane lane,
      int priority,
      boolean pinned,
      OffsetDateTime doneAt,
      List<RegisteredRouteStepDto> route) {}

  public record PauseEntryRequest(
      @NotNull @Min(0) Long expectedVersion, @Size(max = 1000) String reason) {}

  public record MoveEntryRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotNull UUID targetQueueId,
      @NotNull @Min(0) Integer targetIndex,
      @NotNull LocalDate targetDate) {}

  public record MoveExternalLogisticsTaskRequest(
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotNull @Min(0) Long expectedEntryVersion,
      @NotNull TaskLane targetLane,
      @NotNull LocalDate targetDate,
      @NotNull @Min(0) Integer targetIndex) {}

  /**
   * Exchanges the scheduled dates assigned to two complete visual task-board date columns.
   * Every entry returned by both {@code includeShadow=true} snapshots must be represented so a
   * stale or partial browser view cannot move only part of a column.
   */
  public record SwapTaskBoardDatesRequest(
      @NotNull LocalDate firstDate,
      @NotNull LocalDate secondDate,
      @NotEmpty @Size(max = 2000) List<@Valid TaskBoardDateEntryExpectation> entries) {}

  public record TaskBoardDateEntryExpectation(
      @NotNull UUID entryId,
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Min(0) Long expectedTaskVersion) {}

  public record PinTaskRequest(
      @NotNull @Min(0) Long expectedTaskVersion, boolean pinned) {}

  public record AssignmentDto(
      UUID id,
      long version,
      UUID workerId,
      String workerName,
      UUID workerGroupId,
      String workerGroupName,
      AssignmentStatus status,
      OffsetDateTime assignedAt,
      OffsetDateTime startedAt,
      OffsetDateTime pausedAt,
      OffsetDateTime finishedAt) {}

  public enum TimerState {
    WORKING,
    BREAK,
    OFF_SHIFT,
    PAUSED,
    DONE
  }

  public record TaskTimerSnapshot(
      long countedActiveSeconds,
      Long remainingSeconds,
      BigDecimal remainingPercent,
      TimerState timerState,
      OffsetDateTime nextTransitionAt,
      OffsetDateTime serverTime) {}

  public record BoardEntryDto(
      UUID id,
      long version,
      UUID taskId,
      UUID externalTaskId,
      long taskVersion,
      String title,
      String unitNumber,
      TaskStatus taskStatus,
      LocalDate scheduledDate,
      TaskLane lane,
      int priority,
      boolean pinned,
      UUID queueId,
      QueuePurpose queuePurpose,
      int routeIndex,
      int queuePosition,
      EntryType entryType,
      EntryStatus status,
      String taskText,
      Integer plannedDurationMinutes,
      OffsetDateTime activeStartedAt,
      OffsetDateTime pausedAt,
      long activeWorkSeconds,
      List<AssignmentDto> assignments,
      TaskTimerSnapshot timerSnapshot,
      TaskSourceReferenceDto source) {}

  public record BoardColumnDto(
      UUID queueId,
      String queueName,
      QueueType queueType,
      QueuePurpose queuePurpose,
      int sortOrder,
      List<BoardEntryDto> entries) {}

  public record TaskBoardSnapshot(
      UUID warehouseId,
      LocalDate selectedDate,
      List<LocalDate> availableDates,
      List<BoardColumnDto> columns) {}

  public record LogisticsDateColumnDto(
      @NotNull LocalDate date, List<BoardEntryDto> entries) {
    public LogisticsDateColumnDto {
      entries = List.copyOf(entries);
    }
  }

  public record LogisticsBoardSnapshot(
      UUID warehouseId,
      UUID queueId,
      long queueVersion,
      List<BoardEntryDto> current,
      List<LogisticsDateColumnDto> dates) {
    public LogisticsBoardSnapshot {
      current = List.copyOf(current);
      dates = List.copyOf(dates);
    }
  }

  public record QueueReferenceRequest(
      @NotNull QueueReferenceType type, @NotBlank @Size(max = 128) String externalReferenceId) {}

  public record QueueReferenceDto(
      UUID id,
      long version,
      UUID queueDefinitionId,
      QueueReferenceType type,
      String externalReferenceId) {}

  public record TimeEventDto(
      UUID id,
      long version,
      UUID entryId,
      UUID workerId,
      String workerName,
      String workerGroupName,
      dev.buhanzaz.rwms.taskboard.domain.TimeEventType eventType,
      String reason,
      OffsetDateTime createdAt,
      UUID relatedEntryId) {}
}
