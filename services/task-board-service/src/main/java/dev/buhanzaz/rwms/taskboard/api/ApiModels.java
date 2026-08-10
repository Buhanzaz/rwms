package dev.buhanzaz.rwms.taskboard.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.AudienceMode;
import dev.buhanzaz.rwms.taskboard.domain.CredentialStatus;
import dev.buhanzaz.rwms.taskboard.domain.DriverTaskAudienceMode;
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

/**
 * Transport models for the manager, public operational and private service task-board APIs.
 *
 * <p>They are boundary types, not persistence entities. Mutable commands carry an observed version
 * or a stable external identity where the contract requires replay safety.
 */
public final class ApiModels {
  private ApiModels() {}

  /**
   * Version-fenced command for a global worker qualification class.
   *
   * @param version observed class version; use zero when creating
   * @param name human-readable class name
   * @param description optional class description
   * @param comment optional administrator note
   * @param sortOrder display order
   * @param active whether the class is selectable for new bindings
   */
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

  /**
   * Declares how a qualified worker class participates in a queue.
   *
   * @param workerClassId global worker-class identity
   * @param order deterministic binding order
   * @param stopTaskOnTake whether taking the step interrupts compatible work
   * @param participationPolicy primary or optional participation policy
   * @param notifyOnPrimaryTake whether primary take sends an availability signal
   */
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

  /**
   * Version-fenced definition of the shared {@code GENERAL} queue standard.
   *
   * <p>The binding order and participation policy are process rules shared by every derived
   * warehouse projection.
   *
   * @param version observed definition version; use zero when creating
   * @param name global queue name
   * @param description optional queue description
   * @param type operational queue type
   * @param purpose global or logistics-driver purpose
   * @param sortOrder requested catalog order
   * @param active whether new work may use the queue
   * @param hidden whether the queue is hidden from normal board display
   * @param collapsed whether the board initially collapses the queue
   * @param holdingPeriodMinutes optional terminal holding period
   * @param notificationThreshold optional queue notification threshold
   * @param notifyWhenThresholdReached whether threshold notification is enabled
   * @param resultPhotoMinCount minimum result-photo count for completion
   * @param bindings qualified worker-class process bindings
   */
  public record QueueDefinitionRequest(
      @NotNull @Min(0) Long version,
      @NotBlank @Size(max = 128) String name,
      @Size(max = 1000) String description,
      @NotNull QueueType type,
      @NotNull QueuePurpose purpose,
      @Min(0) int sortOrder,
      boolean active,
      boolean hidden,
      boolean collapsed,
      @Min(0) Integer holdingPeriodMinutes,
      @Min(0) Integer notificationThreshold,
      boolean notifyWhenThresholdReached,
      @NotNull @Min(0) @Max(20) Integer resultPhotoMinCount,
      @NotNull List<@Valid QueueBindingRequest> bindings) {}

  /**
   * Global queue-definition representation returned by the registry API.
   *
   * @param id stable definition identity
   * @param version current definition version
   * @param name global queue name
   * @param description optional queue description
   * @param type operational queue type
   * @param purpose global or logistics-driver purpose
   * @param sortOrder canonical catalog order
   * @param active whether new work may use the queue
   * @param hidden whether the queue is hidden from normal board display
   * @param collapsed whether the board initially collapses the queue
   * @param holdingPeriodMinutes optional terminal holding period
   * @param notificationThreshold optional queue notification threshold
   * @param notifyWhenThresholdReached whether threshold notification is enabled
   * @param resultPhotoMinCount minimum result-photo count for completion
   * @param bindings qualified worker-class process bindings
   */
  public record QueueDefinitionDto(
      UUID id,
      long version,
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

  public record QueueDefinitionOrderItem(
      @NotNull UUID definitionId, @NotNull @Min(0) Long expectedVersion) {}

  /**
   * Complete version-fenced order declaration for all global {@code GENERAL} definitions.
   *
   * @param definitions complete ordered global-definition identity and version list
   */
  public record QueueDefinitionOrderRequest(
      @NotEmpty List<@Valid QueueDefinitionOrderItem> definitions) {}

  /**
   * Version-fenced configuration for one warehouse's dedicated logistics-driver queue.
   *
   * @param expectedVersion observed physical queue version
   * @param active whether new driver work may use the queue
   * @param hidden whether the queue is hidden from normal display
   * @param collapsed whether the board initially collapses the queue
   * @param holdingPeriodMinutes optional terminal holding period
   * @param notificationThreshold optional queue notification threshold
   * @param notifyWhenThresholdReached whether threshold notification is enabled
   * @param resultPhotoMinCount minimum result-photo count for completion
   * @param bindings qualified worker-class process bindings
   */
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

  /**
   * Stable physical queue projection used for local assignments and historic route references.
   *
   * @param id stable physical queue identity
   * @param version current physical queue version
   * @param warehouseId owning warehouse identity
   * @param definitionId source global definition identity, if derived
   * @param definitionVersion version of the source definition
   * @param name display name
   * @param description optional description
   * @param type operational queue type
   * @param purpose global or logistics-driver purpose
   * @param sortOrder display order
   * @param active whether new work may use the queue
   * @param hidden whether the queue is hidden from normal display
   * @param collapsed whether the board initially collapses the queue
   * @param holdingPeriodMinutes optional terminal holding period
   * @param notificationThreshold optional queue notification threshold
   * @param notifyWhenThresholdReached whether threshold notification is enabled
   * @param resultPhotoMinCount minimum result-photo count for completion
   * @param bindings qualified worker-class process bindings
   */
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

  public record QualificationRequest(
      @NotNull UUID workerClassId, boolean active, @Size(max = 1000) String comment) {}

  /**
   * Version-fenced worker profile and qualification declaration.
   *
   * <p>The password is redacted from {@link #toString()} and must never be copied into events or
   * logs.
   *
   * @param version observed worker version; use zero when creating
   * @param displayName display name
   * @param firstName optional first name
   * @param lastName optional last name
   * @param middleName optional middle name
   * @param active whether the worker may be assigned new work
   * @param comment optional administrator note
   * @param appLogin optional worker-app login
   * @param password optional credential secret, redacted in string output
   * @param qualifications worker-class qualification declarations
   */
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

  /**
   * Version-fenced selection of a worker's active group, or {@code null} to clear it.
   *
   * @param expectedVersion observed worker version
   * @param workerGroupId selected group identity, or null
   */
  public record SetCurrentGroupRequest(
      @NotNull @Min(0) Long expectedVersion, UUID workerGroupId) {}

  /**
   * Version-fenced password command whose string representation redacts the secret.
   *
   * @param expectedVersion observed worker version
   * @param password new credential secret
   */
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

  /**
   * Version-fenced warehouse group with one worker class and declarative memberships.
   *
   * @param version observed group version; use zero when creating
   * @param workerClassId globally defined class for all group members
   * @param name display name
   * @param description optional description
   * @param active whether the group may accept work
   * @param members active or inactive membership declarations
   */
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

  /**
   * Version-fenced operational availability command; disabling requires a human-readable reason.
   *
   * @param expectedVersion observed group version
   * @param reason unavailable reason, required when disabling
   */
  public record GroupAvailabilityRequest(
      @NotNull @Min(0) Long expectedVersion,
      @Size(max = 1000) String reason) {}

  public record MaintenanceRoutingQueueRequirement(
      @NotNull UUID queueDefinitionId,
      @NotNull QueueType type) {}

  /**
   * Read-only maintenance request that validates UUID-based global queue prerequisites.
   *
   * @param queues exact required global queue-definition identities and types
   */
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

  /**
   * Typed catalog preflight result that separates missing definitions from incompatible ones.
   *
   * @param ready whether every requested definition is usable
   * @param missingQueueDefinitionIds unknown global definition identities
   * @param mismatches known definitions with incompatible properties
   * @param resolvedDefinitions resolved compatible definitions
   */
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

  /**
   * Read-only maintenance routing prerequisite check for a target warehouse.
   *
   * <p>Only exact queue-definition UUIDs are resolved; names and legacy aliases are deliberately
   * not accepted as routing identities.
   *
   * @param warehouseId warehouse whose physical bindings are checked
   * @param queues required global queue-definition identities and types
   */
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

  /**
   * Complete routing preflight result, including missing global and local bindings separately.
   *
   * @param warehouseId checked warehouse identity
   * @param ready whether all requested routes are usable
   * @param missingQueueDefinitionIds unknown global definitions
   * @param missingWarehouseBindingDefinitionIds definitions missing a physical binding
   * @param mismatches known definitions or bindings with incompatible properties
   * @param resolvedQueues resolved physical queues for compatible requirements
   */
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

  /**
   * Read-only active queue capabilities used by maintenance and logistics routing clients.
   *
   * @param warehouseId checked warehouse identity
   * @param movementQueueDefinitions active movement definition-to-queue mappings
   */
  public record WarehouseQueueCapabilities(
      UUID warehouseId,
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

  /**
   * User-originated board task with a complete ordered route.
   *
   * @param externalTaskId optional stable external correlation identity
   * @param title task title
   * @param unitNumber optional human-facing unit identifier
   * @param description optional task description
   * @param plannedDurationMinutes optional planned duration
   * @param deadlineAt optional deadline
   * @param route complete ordered route declaration
   * @param scheduledDate optional initial operational date
   * @param priority optional priority from one through five
   */
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

  /**
   * Stable worker audience planned for one logistics driver task.
   *
   * <p>Only {@code ASSIGNED_DRIVER} carries an exact worker identity. Shared warehouse-driver work
   * remains identity-free until task-board records an actual assignment.
   *
   * @param mode visibility rule owned by task-board
   * @param workerId optional task-board worker identity
   * @param workerName optional authoritative display-name snapshot
   */
  public record DriverTaskAudienceDto(
      @NotNull DriverTaskAudienceMode mode,
      UUID workerId,
      @Size(max = 512) String workerName) {}

  /**
   * Source-service request to register externally owned work.
   *
   * <p>{@code externalTaskId}, together with the authenticated source client, is the idempotent
   * task identity. The source describes work; task-board owns execution state and queue entries.
   *
   * @param warehouseId warehouse that owns the initial route
   * @param externalTaskId stable source-owned idempotency identity
   * @param title task title
   * @param unitNumber optional human-facing unit identifier
   * @param description optional task description
   * @param plannedDurationMinutes optional planned duration
   * @param deadlineAt optional deadline
   * @param route complete ordered route declaration
   * @param scheduledDate optional initial operational date
   * @param priority optional priority from one through five
   * @param dailyCapacity optional source planning capacity
   * @param source optional immutable source-domain reference
   * @param lane requested initial task lane
   * @param driverAudience optional logistics driver audience; omitted legacy driver tasks are
   *     shared with qualified warehouse drivers
   */
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
      TaskLane lane,
      @Valid DriverTaskAudienceDto driverAudience) {
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
        Integer dailyCapacity,
        TaskSourceReferenceDto source,
        TaskLane lane) {
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
          source,
          lane,
          null);
    }

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
   *
   * @param warehouseId warehouse that owns the movement work
   * @param externalTaskId stable logistics task identity
   * @param unitNumber optional human-facing unit identifier
   * @param plannedDurationMinutes optional planned duration
   * @param deadlineAt required movement deadline
   * @param operations immutable equipment movement facts
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

  /**
   * Source-owned content replacement that is valid only before execution begins.
   *
   * @param expectedTaskVersion observed task version
   * @param title replacement task title
   * @param unitNumber optional replacement unit identifier
   * @param description optional replacement description
   * @param plannedDurationMinutes optional replacement planned duration
   * @param deadlineAt optional replacement deadline
   * @param route complete replacement route declaration
   */
  public record PreStartUpdateTaskRequest(
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotBlank @Size(max = 256) String title,
      @Size(max = 64) String unitNumber,
      @Size(max = 2000) String description,
      @Min(0) Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      @NotEmpty List<@Valid RouteStepRequest> route) {}

  /**
   * Version-fenced source task relocation to a target warehouse.
   *
   * @param expectedTaskVersion observed task version
   * @param targetWarehouseId target warehouse identity
   */
  public record RelocateExternalTaskRequest(
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotNull UUID targetWarehouseId) {}

  /**
   * Version-fenced take command with optional explicit group or worker selection for manager use.
   *
   * @param expectedVersion observed entry version
   * @param workerGroupId optional target group
   * @param workerId optional target worker
   */
  public record TakeEntryRequest(
      @NotNull @Min(0) Long expectedVersion, UUID workerGroupId, UUID workerId) {}

  /**
   * Minimal optimistic-concurrency command for an entry transition.
   *
   * @param expectedVersion observed entry version
   */
  public record VersionCommand(@NotNull @Min(0) Long expectedVersion) {}

  /**
   * Version-fenced source request to place a driver task into an explicit lane.
   *
   * @param expectedTaskVersion observed task version
   * @param lane requested driver lane
   */
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

  /**
   * Version-fenced cancellation with an auditable reason.
   *
   * @param expectedTaskVersion observed task version
   * @param reason human-readable cancellation reason
   */
  public record CancelTaskRequest(
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotBlank @Size(max = 1000) String reason) {}

  public enum PreStartCancellationOutcome {
    CANCELLED,
    ALREADY_CANCELLED,
    STARTED,
    VERSION_CONFLICT
  }

  public record PreStartCancellationResult(
      @NotNull PreStartCancellationOutcome outcome,
      @NotNull UUID taskId,
      @NotNull UUID externalTaskId,
      @Min(0) long taskVersion,
      @NotNull TaskStatus status,
      OffsetDateTime cancelledAt) {}

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

  /**
   * Source-facing registration that maps one external task to task-board task and route IDs.
   *
   * @param taskId stable task-board task identity
   * @param taskVersion current task version
   * @param warehouseId current owning warehouse identity
   * @param externalTaskId stable source-owned identity
   * @param title task title
   * @param unitNumber optional unit identifier
   * @param description optional task description
   * @param status current task status
   * @param plannedDurationMinutes optional planned duration
   * @param deadlineAt optional deadline
   * @param scheduledDate current operational date
   * @param lane current task lane
   * @param priority task priority
   * @param pinned whether the task is pinned
   * @param driverAudience logistics driver audience, or {@code null} for ordinary work
   * @param doneAt completion or cancellation time, if terminal
   * @param route registered route steps and their stable entry identities
   */
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
      @JsonInclude(JsonInclude.Include.ALWAYS) DriverTaskAudienceDto driverAudience,
      OffsetDateTime doneAt,
      List<RegisteredRouteStepDto> route) {}

  /**
   * Version-fenced pause command with an optional durable reason.
   *
   * @param expectedVersion observed entry version
   * @param reason optional pause reason
   */
  public record PauseEntryRequest(
      @NotNull @Min(0) Long expectedVersion, @Size(max = 1000) String reason) {}

  /**
   * Version-fenced movement of an entry to a permitted queue, date and zero-based position.
   *
   * @param expectedVersion observed entry version
   * @param expectedTaskVersion observed parent task version
   * @param targetQueueId permitted target physical queue
   * @param targetIndex zero-based target position
   * @param targetDate target operational date
   */
  public record MoveEntryRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotNull UUID targetQueueId,
      @NotNull @Min(0) Integer targetIndex,
      @NotNull LocalDate targetDate) {}

  /**
   * Version-fenced logistics movement command for a dedicated driver entry.
   *
   * @param expectedTaskVersion observed task version
   * @param expectedEntryVersion observed driver-entry version
   * @param targetLane target driver lane
   * @param targetDate target operational date
   * @param targetIndex zero-based target position
   * @param targetDriverAudience optional replacement audience; omitted preserves the current value
   */
  public record MoveExternalLogisticsTaskRequest(
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotNull @Min(0) Long expectedEntryVersion,
      @NotNull TaskLane targetLane,
      @NotNull LocalDate targetDate,
      @NotNull @Min(0) Integer targetIndex,
      @Valid DriverTaskAudienceDto targetDriverAudience) {
    public MoveExternalLogisticsTaskRequest(
        Long expectedTaskVersion,
        Long expectedEntryVersion,
        TaskLane targetLane,
        LocalDate targetDate,
        Integer targetIndex) {
      this(
          expectedTaskVersion,
          expectedEntryVersion,
          targetLane,
          targetDate,
          targetIndex,
          null);
    }
  }

  /**
   * Exchanges the scheduled dates assigned to two complete visual task-board date columns.
   * Every entry returned by both {@code includeShadow=true} snapshots must be represented so a
   * stale or partial browser view cannot move only part of a column.
   *
   * @param firstDate first visible date column
   * @param secondDate second visible date column
   * @param entries complete entry and task version expectations for both columns
   */
  public record SwapTaskBoardDatesRequest(
      @NotNull LocalDate firstDate,
      @NotNull LocalDate secondDate,
      @NotEmpty @Size(max = 2000) List<@Valid TaskBoardDateEntryExpectation> entries) {}

  public record TaskBoardDateEntryExpectation(
      @NotNull UUID entryId,
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Min(0) Long expectedTaskVersion) {}

  /**
   * Version-fenced pin declaration applied consistently to every task route entry.
   *
   * @param expectedTaskVersion observed task version
   * @param pinned requested pin state
   */
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

  /**
   * Current operational projection of one route entry.
   *
   * <p>{@code version} fences entry commands and {@code taskVersion} fences task-wide commands;
   * clients must use the version appropriate to the operation rather than substituting one for the
   * other.
   *
   * @param id stable route-entry identity
   * @param version current entry version
   * @param taskId parent task identity
   * @param externalTaskId optional source-facing task identity
   * @param taskVersion current parent task version
   * @param title task title
   * @param unitNumber optional unit identifier
   * @param taskStatus current parent task status
   * @param scheduledDate operational date
   * @param lane current task lane
   * @param priority task priority
   * @param pinned whether the task is pinned
   * @param queueId current physical queue identity
   * @param queuePurpose current queue purpose
   * @param routeIndex immutable route-step index
   * @param queuePosition current zero-based queue position
   * @param entryType route entry type
   * @param status current entry status
   * @param taskText route-step text
   * @param plannedDurationMinutes optional planned duration
   * @param activeStartedAt latest active-work start time
   * @param pausedAt latest pause time
   * @param activeWorkSeconds accumulated active-work duration
   * @param assignments assignment snapshots
   * @param timerSnapshot server-calculated timer state
   * @param source immutable source-domain reference, if any
   * @param driverAudience logistics driver audience, or {@code null} for ordinary work
   */
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
      TaskSourceReferenceDto source,
      @JsonInclude(JsonInclude.Include.ALWAYS) DriverTaskAudienceDto driverAudience) {}

  public record BoardColumnDto(
      UUID queueId,
      String queueName,
      QueueType queueType,
      QueuePurpose queuePurpose,
      int sortOrder,
      List<BoardEntryDto> entries) {}

  /**
   * Date-scoped board snapshot returned to managers and eligible workers.
   *
   * @param warehouseId warehouse that owns the board
   * @param selectedDate date resolved for this representation
   * @param availableDates operational dates with board data
   * @param columns queues and entries for the selected date
   */
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

  /**
   * Dedicated driver-board snapshot with a sticky current lane and dated future columns.
   *
   * @param warehouseId warehouse that owns the driver queue
   * @param queueId stable driver-queue identity
   * @param queueVersion current driver-queue version
   * @param current sticky current-lane entries
   * @param dates non-empty scheduled-date columns
   */
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

  /**
   * Typed private reference that prevents deletion of a queue definition still used by a source.
   *
   * @param type source-domain reference type
   * @param externalReferenceId stable reference identity in that source
   */
  public record QueueReferenceRequest(
      @NotNull QueueReferenceType type, @NotBlank @Size(max = 128) String externalReferenceId) {}

  public record QueueReferenceDto(
      UUID id,
      long version,
      UUID queueDefinitionId,
      QueueReferenceType type,
      String externalReferenceId) {}

  /**
   * Immutable timing fact for audit, work-duration and KPI calculations.
   *
   * @param id stable time-event identity
   * @param version current event version
   * @param entryId route entry that emitted the fact
   * @param workerId attributed worker, if any
   * @param workerName human-readable worker name snapshot
   * @param workerGroupName human-readable group name snapshot
   * @param eventType transition type
   * @param reason optional transition reason
   * @param createdAt authoritative event time
   * @param relatedEntryId optional related route entry
   */
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
