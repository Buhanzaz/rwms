package dev.buhanzaz.rwms.taskboard.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskTimerSnapshot;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WorkerApiModels {
  private WorkerApiModels() {}

  public record WorkerIdentity(UUID id, UUID warehouseId, String login, String displayName) {}

  public record WorkerGroupSummary(
      UUID id, String name, UUID workerClassId, String workerClassName) {}

  public record WorkerQualificationSummary(UUID workerClassId, String name) {}

  public record WorkerCategory(
      UUID queueId,
      String name,
      String type,
      String queuePurpose,
      int sortOrder,
      List<String> audienceModes,
      int resultPhotoMinCount) {
    public WorkerCategory(
        UUID queueId,
        String name,
        String type,
        int sortOrder,
        List<String> audienceModes,
        int resultPhotoMinCount) {
      this(queueId, name, type, "GENERAL", sortOrder, audienceModes, resultPhotoMinCount);
    }
  }

  public record WorkerOfflineLease(
      UUID id, OffsetDateTime issuedAt, OffsetDateTime expiresAt, long syncRevision) {}

  public record WorkerContext(
      WorkerIdentity worker,
      WorkerGroupSummary currentGroup,
      String operationalAvailability,
      List<WorkerGroupSummary> groups,
      List<WorkerQualificationSummary> qualifications,
      List<WorkerCategory> categories,
      OffsetDateTime serverTime,
      long revision,
      WorkerOfflineLease offlineLease) {}

  public record WorkerAssignmentSnapshot(
      UUID id,
      UUID workerId,
      String workerName,
      UUID workerGroupId,
      String workerGroupName,
      String status,
      OffsetDateTime assignedAt,
      OffsetDateTime startedAt,
      OffsetDateTime pausedAt,
      OffsetDateTime finishedAt) {}

  public record WorkerFeedEntry(
      UUID entryId,
      long version,
      UUID taskId,
      int routeIndex,
      String title,
      String unitNumber,
      String taskText,
      LocalDate scheduledDate,
      OffsetDateTime deadlineAt,
      int priority,
      int queuePosition,
      String status,
      String availabilityMode,
      Integer plannedDurationMinutes,
      OffsetDateTime activeStartedAt,
      long activeWorkSeconds,
      TaskTimerSnapshot timerSnapshot,
      List<WorkerAssignmentSnapshot> assignments,
      int readyEvidenceCount,
      int resultPhotoMinCount) {}

  public record WorkerFeedCategory(WorkerCategory category, List<WorkerFeedEntry> entries) {}

  public record WorkerFeed(
      long revision,
      OffsetDateTime serverTime,
      List<WorkerFeedCategory> categories,
      String nextCursor) {}

  public record WorkerTaskObject(String kind, String id, String label) {}

  public record WorkerMaterial(UUID id, String name, double quantity, String unit) {}

  public record WorkerWork(
      UUID id,
      String name,
      double quantity,
      String unit,
      Integer durationMinutes,
      String comment) {}

  public record WorkerVisibleComment(
      UUID id, String text, String authorDisplayName, OffsetDateTime createdAt) {}

  public record WorkerMediaReference(
      UUID mediaId,
      long generation,
      String kind,
      String contentType,
      String readPath,
      String thumbnailPath,
      OffsetDateTime capturedAt,
      OffsetDateTime recordedAt) {}

  public record WorkerRelatedStep(
      UUID entryId, int routeIndex, String queueName, String taskText, String status) {}

  public record TaskEvidence(
      UUID evidenceId,
      long version,
      UUID entryId,
      int routeIndex,
      UUID workerId,
      UUID workerGroupId,
      OffsetDateTime capturedAt,
      OffsetDateTime recordedAt,
      String state,
      UUID mediaId,
      Long mediaGeneration,
      String reviewReason,
      String contentType,
      String readPath,
      String thumbnailPath) {}

  public record AudienceSelector(
      String kind,
      UUID id,
      String mode,
      boolean interruptOnTake,
      boolean notifyOnPrimaryTake) {
    public AudienceSelector(
        String kind, UUID id, String mode, boolean interruptOnTake) {
      this(kind, id, mode, interruptOnTake, false);
    }
  }

  public record WorkerTaskDetail(
      UUID entryId,
      long version,
      UUID taskId,
      int routeIndex,
      String title,
      String description,
      @JsonProperty("object") WorkerTaskObject taskObject,
      String taskText,
      LocalDate scheduledDate,
      OffsetDateTime deadlineAt,
      int priority,
      int queuePosition,
      String status,
      String availabilityMode,
      Integer plannedDurationMinutes,
      OffsetDateTime activeStartedAt,
      long activeWorkSeconds,
      TaskTimerSnapshot timerSnapshot,
      List<AudienceSelector> audienceSelectors,
      List<WorkerAssignmentSnapshot> assignments,
      List<WorkerWork> works,
      List<WorkerMaterial> materials,
      List<WorkerVisibleComment> comments,
      List<WorkerMediaReference> sourceMedia,
      List<TaskEvidence> evidence,
      List<WorkerRelatedStep> relatedSteps,
      int resultPhotoMinCount,
      boolean completionAllowed) {}

  public record WorkerActionRequest(
      @NotNull UUID operationId,
      @NotNull WorkerAction action,
      @Min(0) long expectedVersion,
      UUID workerGroupId,
      @NotNull OffsetDateTime occurredAt,
      @NotNull UUID offlineLeaseId,
      UUID evidenceId) {
    public WorkerActionRequest(
        UUID operationId,
        WorkerAction action,
        long expectedVersion,
        UUID workerGroupId,
        OffsetDateTime occurredAt,
        UUID offlineLeaseId) {
      this(
          operationId,
          action,
          expectedVersion,
          workerGroupId,
          occurredAt,
          offlineLeaseId,
          null);
    }
  }

  public enum WorkerAction {
    TAKE,
    JOIN,
    PAUSE,
    RESUME,
    COMPLETE
  }

  public record WorkerActionAppliedResult(
      String outcome, long currentVersion, WorkerTaskDetail entry) {}

  public record EvidenceReservationRequest(
      @NotNull UUID operationId,
      @NotNull UUID evidenceId,
      @Min(0) int routeIndex,
      @NotNull OffsetDateTime capturedAt,
      @NotNull UUID offlineLeaseId,
      @NotBlank String contentType,
      @Min(1) @Max(15_728_640) long sizeBytes,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sha256) {}

  public record WorkerInvalidationEvent(
      UUID eventId, long revision, String type, UUID entryId, OffsetDateTime occurredAt) {}

  public record WorkerDeviceRegistrationRequest(
      @NotBlank String provider,
      @NotBlank @Size(max = 4096) String token,
      @NotBlank @Size(max = 64) String appVersion,
      @Min(23) @Max(1000) int sdkInt,
      @NotBlank @Size(min = 2, max = 35) String locale) {}

  public record WorkerDeviceRegistration(
      String installationId,
      String provider,
      String status,
      OffsetDateTime registeredAt,
      OffsetDateTime updatedAt) {}
}
