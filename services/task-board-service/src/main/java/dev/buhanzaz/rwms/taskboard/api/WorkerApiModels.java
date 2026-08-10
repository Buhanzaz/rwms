package dev.buhanzaz.rwms.taskboard.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.DriverTaskAudienceDto;
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

/**
 * Transport models for the worker-token task feed, actions, evidence and invalidation stream.
 *
 * <p>These types intentionally expose only data reachable by the authenticated worker. They are
 * not persistence entities and must be reloaded after an SSE invalidation.
 */
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
      List<UUID> groupIds,
      int resultPhotoMinCount) {
    public WorkerCategory(
        UUID queueId,
        String name,
        String type,
        String queuePurpose,
        int sortOrder,
        List<String> audienceModes,
        int resultPhotoMinCount) {
      this(
          queueId,
          name,
          type,
          queuePurpose,
          sortOrder,
          audienceModes,
          List.of(),
          resultPhotoMinCount);
    }

    public WorkerCategory(
        UUID queueId,
        String name,
        String type,
        int sortOrder,
        List<String> audienceModes,
        int resultPhotoMinCount) {
      this(
          queueId,
          name,
          type,
          "GENERAL",
          sortOrder,
          audienceModes,
          List.of(),
          resultPhotoMinCount);
    }
  }

  public record WorkerKpiPaletteRange(int fromPercent, int toPercent, String color) {}

  public record WorkerKpiPalette(
      List<WorkerKpiPaletteRange> ranges, String overdueColor) {}

  public record WorkerOfflineLease(
      UUID id, OffsetDateTime issuedAt, OffsetDateTime expiresAt, long syncRevision) {}

  /**
   * Authenticated worker context, queue capabilities, current time and a short-lived offline lease.
   *
   * @param worker authenticated worker identity
   * @param currentGroup currently selected group, if any
   * @param operationalAvailability current worker availability
   * @param groups groups available to the worker
   * @param qualifications active worker qualifications
   * @param categories queue categories visible to the worker
   * @param kpiPalette display palette issued by the warehouse
   * @param serverTime authoritative server-time anchor
   * @param revision current worker-feed revision
   * @param offlineLease time-bounded lease for offline actions
   */
  public record WorkerContext(
      WorkerIdentity worker,
      WorkerGroupSummary currentGroup,
      String operationalAvailability,
      List<WorkerGroupSummary> groups,
      List<WorkerQualificationSummary> qualifications,
      List<WorkerCategory> categories,
      WorkerKpiPalette kpiPalette,
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

  /**
   * One worker-visible route entry with a server-owned classification for ordinary versus
   * logistics-driver tables.
   *
   * @param driverAudience logistics driver audience, or {@code null} for ordinary work
   */
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
      @JsonInclude(JsonInclude.Include.ALWAYS) DriverTaskAudienceDto driverAudience,
      Integer plannedDurationMinutes,
      OffsetDateTime activeStartedAt,
      long activeWorkSeconds,
      TaskTimerSnapshot timerSnapshot,
      List<WorkerAssignmentSnapshot> assignments,
      int readyEvidenceCount,
      int resultPhotoMinCount) {}

  public record WorkerFeedCategory(WorkerCategory category, List<WorkerFeedEntry> entries) {}

  /**
   * Revision-fenced cursor page of tasks visible to the authenticated worker.
   *
   * @param revision feed revision shared by every page in the traversal
   * @param serverTime authoritative server-time anchor
   * @param categories visible categories and their page entries
   * @param nextCursor opaque cursor for the following page, if any
   */
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
      String comment,
      List<UUID> sourceMediaIds) {}

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

  /**
   * Reserved or uploaded evidence associated with one route entry and its authorized worker.
   *
   * @param evidenceId stable evidence identity
   * @param version current evidence version
   * @param entryId route entry that owns the evidence
   * @param routeIndex immutable route-step index
   * @param workerId worker attributed by the server
   * @param workerGroupId group attributed by the server
   * @param capturedAt actual capture time
   * @param recordedAt authoritative reservation or upload time
   * @param state reservation or review state
   * @param mediaId media-service object identity, if finalized
   * @param mediaGeneration immutable media revision, if finalized
   * @param reviewReason reason a late or invalid result needs review
   * @param contentType declared media content type
   * @param readPath authorized media read path
   * @param thumbnailPath authorized thumbnail read path
   */
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

  /**
   * Complete worker-visible detail for one currently authorized task entry.
   *
   * @param entryId stable route-entry identity
   * @param version current entry version for worker commands
   * @param taskId parent task identity
   * @param routeIndex immutable route-step index
   * @param title task title
   * @param description worker-visible task description
   * @param taskObject optional domain object summary
   * @param taskText text of this route step
   * @param scheduledDate operational scheduled date
   * @param deadlineAt optional deadline
   * @param priority task priority
   * @param queuePosition current zero-based queue position
   * @param status current entry status
   * @param availabilityMode worker's authorized participation mode
   * @param plannedDurationMinutes optional planned duration
   * @param activeStartedAt latest active-work start time
   * @param activeWorkSeconds accumulated active work time
   * @param timerSnapshot server-calculated timer state
   * @param audienceSelectors selectors that authorize the worker to see the entry
   * @param assignments current and historic assignment snapshots
   * @param works work snapshots supplied by the source
   * @param materials material snapshots supplied by the source
   * @param comments worker-visible comments
   * @param sourceMedia immutable source-media references
   * @param evidence worker-visible result evidence
   * @param relatedSteps sibling route-step summaries
   * @param resultPhotoMinCount minimum result-photo count for completion
   * @param completionAllowed whether current completion prerequisites are met
   */
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

  /**
   * Replay-safe offline-capable worker action.
   *
   * <p>{@code operationId}, {@code offlineLeaseId}, observed entry version and occurrence time let
   * the service distinguish an exact reconnect replay from a stale or divergent command.
   *
   * @param operationId stable caller-generated action identity
   * @param action requested worker transition
   * @param expectedVersion observed entry version
   * @param workerGroupId optional selected group for an eligible action
   * @param occurredAt actual action time within the lease
   * @param offlineLeaseId issued offline lease identity
   * @param evidenceId optional evidence selected by completion
   */
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

  /**
   * Replay-safe request that reserves an evidence upload before bytes are sent to media-service.
   *
   * @param operationId stable caller-generated reservation identity
   * @param evidenceId stable evidence identity to reserve
   * @param routeIndex route step to which evidence belongs
   * @param capturedAt actual capture time within the lease
   * @param offlineLeaseId issued offline lease identity
   * @param contentType declared media content type
   * @param sizeBytes declared byte length
   * @param sha256 lowercase SHA-256 checksum of the upload
   */
  public record EvidenceReservationRequest(
      @NotNull UUID operationId,
      @NotNull UUID evidenceId,
      @Min(0) int routeIndex,
      @NotNull OffsetDateTime capturedAt,
      @NotNull UUID offlineLeaseId,
      @NotBlank String contentType,
      @Min(1) @Max(15_728_640) long sizeBytes,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sha256) {}

  /**
   * SSE invalidation signal; clients must refresh authorized state rather than trust it as data.
   *
   * @param eventId stable event identity
   * @param revision worker-feed revision after the change
   * @param type invalidation category
   * @param entryId affected entry when the category permits disclosure
   * @param occurredAt server event time
   */
  public record WorkerInvalidationEvent(
      UUID eventId, long revision, String type, UUID entryId, OffsetDateTime occurredAt) {}

  /**
   * Worker-owned push-device installation declaration.
   *
   * @param provider push provider identifier
   * @param token provider device token
   * @param appVersion worker-app version
   * @param sdkInt device SDK level
   * @param locale device locale tag
   */
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
