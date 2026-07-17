package dev.buhanzaz.rwms.taskboard.api;

import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.CredentialStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.QueueReferenceType;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ApiModels {
  private ApiModels() {}

  public record WorkerClassRequest(
      @NotNull @Min(0) Long version,
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 128) String name,
      @Size(max = 1000) String description,
      @Size(max = 1000) String comment,
      int sortOrder,
      boolean active) {}

  public record WorkerClassDto(
      UUID id,
      long version,
      String code,
      String name,
      String description,
      String comment,
      int sortOrder,
      boolean active) {}

  public record QueueBindingRequest(@NotNull UUID workerClassId, boolean stopTaskOnTake) {}

  public record WorkQueueRequest(
      @NotNull @Min(0) Long version,
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 128) String name,
      @Size(max = 1000) String description,
      @NotNull QueueType type,
      boolean active,
      boolean hidden,
      boolean collapsed,
      @Min(0) Integer holdingPeriodMinutes,
      @Min(0) Integer notificationThreshold,
      boolean notifyWhenThresholdReached,
      List<@Valid QueueBindingRequest> bindings) {}

  public record QueueBindingDto(
      UUID id, long version, WorkerClassDto workerClass, boolean stopTaskOnTake) {}

  public record WorkQueueDto(
      UUID id,
      long version,
      UUID warehouseId,
      String code,
      String name,
      String description,
      QueueType type,
      int sortOrder,
      boolean active,
      boolean hidden,
      boolean collapsed,
      Integer holdingPeriodMinutes,
      Integer notificationThreshold,
      boolean notifyWhenThresholdReached,
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
      List<QualificationDto> qualifications) {}

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

  public record GroupMemberRequest(
      @NotNull UUID workerId, @Size(max = 128) String roleInGroup, boolean active) {}

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
      String roleInGroup,
      boolean active) {}

  public record WorkerGroupDto(
      UUID id,
      long version,
      UUID warehouseId,
      WorkerClassDto workerClass,
      String name,
      String description,
      boolean active,
      List<GroupMemberDto> members) {}

  public record RouteStepRequest(
      UUID queueId,
      @Size(max = 64) String queueCode,
      @Size(max = 2000) String taskText,
      @Min(0) Integer plannedDurationMinutes) {}

  public record CreateBoardTaskRequest(
      UUID externalTaskId,
      @NotBlank @Size(max = 256) String title,
      @Size(max = 64) String unitNumber,
      @Size(max = 2000) String description,
      @Min(0) Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      @NotEmpty List<@Valid RouteStepRequest> route) {}

  public record RegisterExternalTaskRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID externalTaskId,
      @NotBlank @Size(max = 256) String title,
      @Size(max = 64) String unitNumber,
      @Size(max = 2000) String description,
      @Min(0) Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      @NotEmpty List<@Valid RouteStepRequest> route) {}

  public record PreStartUpdateTaskRequest(
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotBlank @Size(max = 256) String title,
      @Size(max = 64) String unitNumber,
      @Size(max = 2000) String description,
      @Min(0) Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      @NotEmpty List<@Valid RouteStepRequest> route) {}

  public record TakeEntryRequest(
      @NotNull @Min(0) Long expectedVersion, UUID workerGroupId, UUID workerId) {}

  public record VersionCommand(@NotNull @Min(0) Long expectedVersion) {}

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
      UUID queueId,
      String queueCode,
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
      OffsetDateTime doneAt,
      List<RegisteredRouteStepDto> route) {}

  public record PauseEntryRequest(
      @NotNull @Min(0) Long expectedVersion, @Size(max = 1000) String reason) {}

  public record MoveEntryRequest(
      @NotNull @Min(0) Long expectedVersion,
      UUID targetQueueId,
      @NotNull @Min(0) Integer targetIndex) {}

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

  public record BoardEntryDto(
      UUID id,
      long version,
      UUID taskId,
      UUID externalTaskId,
      long taskVersion,
      String title,
      String unitNumber,
      TaskStatus taskStatus,
      UUID queueId,
      String queueCode,
      int routeIndex,
      int queuePosition,
      EntryType entryType,
      EntryStatus status,
      String taskText,
      Integer plannedDurationMinutes,
      OffsetDateTime activeStartedAt,
      OffsetDateTime pausedAt,
      long activeWorkSeconds,
      List<AssignmentDto> assignments) {}

  public record BoardColumnDto(
      UUID queueId,
      String queueCode,
      String queueName,
      QueueType queueType,
      int sortOrder,
      boolean virtual,
      List<BoardEntryDto> entries) {}

  public record TaskBoardSnapshot(UUID warehouseId, List<BoardColumnDto> columns) {}

  public record QueueReferenceRequest(
      @NotNull QueueReferenceType type, @NotBlank @Size(max = 128) String externalReferenceId) {}

  public record QueueReferenceDto(
      UUID id, long version, UUID queueId, QueueReferenceType type, String externalReferenceId) {}

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
