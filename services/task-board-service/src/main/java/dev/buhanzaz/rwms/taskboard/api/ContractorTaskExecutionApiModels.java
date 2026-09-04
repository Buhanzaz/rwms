package dev.buhanzaz.rwms.taskboard.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskCommentSnapshotRequest;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskMaterialSnapshotRequest;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskSourceMediaSnapshotRequest;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskSourceReferenceDto;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskWorkSnapshotRequest;
import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Private logistics-service transport models for one exact contractor's task execution. */
public final class ContractorTaskExecutionApiModels {
  private ContractorTaskExecutionApiModels() {}

  /** Supported contractor transitions; task-board maps them to its existing worker state machine. */
  public enum ContractorTaskAction {
    START,
    COMPLETE
  }

  /** Media owner type fixed by task-board for contractor result evidence. */
  public enum ContractorEvidenceOwnerType {
    TASK_BOARD_ENTRY
  }

  /**
   * Bounded task-result evidence facts belonging to the exact contractor in the snapshot.
   *
   * @param evidenceId stable task-board evidence identity
   * @param version current evidence projection version
   * @param capturedAt actual capture time supplied at reservation
   * @param recordedAt authoritative latest projection time
   * @param state reservation, ready or review state
   * @param mediaId media-service identity when processing produced one
   * @param mediaGeneration immutable ready media generation when available
   * @param reviewReason task-board-owned review explanation when applicable
   * @param contentType declared result-image content type
   */
  public record ContractorTaskEvidence(
      @NotNull UUID evidenceId,
      @Min(0) long version,
      @NotNull OffsetDateTime capturedAt,
      @NotNull OffsetDateTime recordedAt,
      @NotNull String state,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID mediaId,
      @JsonInclude(JsonInclude.Include.ALWAYS) Long mediaGeneration,
      @JsonInclude(JsonInclude.Include.ALWAYS) String reviewReason,
      @NotBlank String contentType) {}

  /**
   * One ordered route entry containing only task-board-owned worker-visible facts.
   *
   * @param entryId stable route-entry identity
   * @param version current entry version for the next command
   * @param routeIndex immutable raw route index
   * @param routeStepIndex zero-based index in this contractor route snapshot
   * @param routeStepCount total route-entry count
   * @param queueName worker-facing queue name
   * @param taskText worker-facing step instruction; any address remains source-supplied text
   * @param status current task-board execution state
   * @param plannedDurationMinutes optional planned duration
   * @param works immutable source work snapshots
   * @param materials immutable source material snapshots
   * @param comments immutable worker-visible source comments
   * @param sourceMedia immutable source media identities without bearer-only read paths
   * @param resultPhotoMinCount configured minimum number of ready result photos
   * @param evidence newest bounded task-result evidence belonging to this exact contractor
   * @param completionAllowed whether the current state, assignment and evidence allow completion
   */
  public record ContractorTaskRouteEntry(
      @NotNull UUID entryId,
      @Min(0) long version,
      @Min(0) int routeIndex,
      @Min(0) int routeStepIndex,
      @Min(1) int routeStepCount,
      @NotNull String queueName,
      @JsonInclude(JsonInclude.Include.ALWAYS) String taskText,
      @NotNull EntryStatus status,
      @JsonInclude(JsonInclude.Include.ALWAYS) Integer plannedDurationMinutes,
      @NotNull List<TaskWorkSnapshotRequest> works,
      @NotNull List<TaskMaterialSnapshotRequest> materials,
      @NotNull List<TaskCommentSnapshotRequest> comments,
      @NotNull List<TaskSourceMediaSnapshotRequest> sourceMedia,
      @Min(0) int resultPhotoMinCount,
      @NotNull List<ContractorTaskEvidence> evidence,
      boolean completionAllowed) {
    public ContractorTaskRouteEntry {
      works = List.copyOf(works);
      materials = List.copyOf(materials);
      comments = List.copyOf(comments);
      sourceMedia = List.copyOf(sourceMedia);
      evidence = List.copyOf(evidence);
    }
  }

  /**
   * Narrow execution snapshot for one logistics-owned task and its exact active contractor.
   *
   * @param workerId exact task-board contractor identity
   * @param externalTaskId exact logistics-service task identity
   * @param taskId task-board aggregate identity
   * @param taskVersion current task version
   * @param warehouseId physical task warehouse
   * @param title worker-facing task title
   * @param description optional worker-facing description
   * @param unitNumber optional transported-object or cabin number
   * @param scheduledDate operational date
   * @param deadlineAt optional source deadline
   * @param priority task priority
   * @param status current task state
   * @param source immutable logistics source reference
   * @param route complete ordered task route
   */
  public record ContractorTaskExecutionSnapshot(
      @NotNull UUID workerId,
      @NotNull UUID externalTaskId,
      @NotNull UUID taskId,
      @Min(0) long taskVersion,
      @NotNull UUID warehouseId,
      @NotNull String title,
      @JsonInclude(JsonInclude.Include.ALWAYS) String description,
      @JsonInclude(JsonInclude.Include.ALWAYS) String unitNumber,
      @NotNull LocalDate scheduledDate,
      @JsonInclude(JsonInclude.Include.ALWAYS) OffsetDateTime deadlineAt,
      @Min(1) int priority,
      @NotNull TaskStatus status,
      @NotNull TaskSourceReferenceDto source,
      @NotNull List<ContractorTaskRouteEntry> route) {
    public ContractorTaskExecutionSnapshot {
      route = List.copyOf(route);
    }
  }

  /**
   * Version-fenced, replay-safe contractor route-entry command.
   *
   * @param operationId stable command identity equal to the Idempotency-Key header
   * @param action requested START or COMPLETE transition
   * @param expectedVersion observed route-entry version
   * @param evidenceId selected ready result evidence required by COMPLETE
   */
  public record ContractorTaskActionRequest(
      @NotNull UUID operationId,
      @NotNull ContractorTaskAction action,
      @Min(0) long expectedVersion,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID evidenceId) {}

  /**
   * Frozen replay result containing the changed entry version and refreshed exact-task snapshot.
   *
   * @param outcome APPLIED for both first delivery and an identical replay
   * @param currentVersion changed route-entry version for the next command
   * @param task refreshed contractor task snapshot
   */
  public record ContractorTaskActionResult(
      @NotNull String outcome,
      @Min(0) long currentVersion,
      @NotNull ContractorTaskExecutionSnapshot task) {}

  /**
   * Replay-safe result-evidence declaration supplied by logistics-service before media upload.
   *
   * @param operationId stable reservation identity equal to the Idempotency-Key header
   * @param evidenceId stable evidence and media client-reference identity
   * @param capturedAt actual capture time, which cannot be in the future
   * @param contentType existing supported task-result image content type
   * @param sizeBytes declared upload byte length
   * @param sha256 lowercase SHA-256 checksum of the declared upload
   */
  public record ContractorEvidenceReservationRequest(
      @NotNull UUID operationId,
      @NotNull UUID evidenceId,
      @NotNull OffsetDateTime capturedAt,
      @NotBlank String contentType,
      @Min(1) @Max(15_728_640) long sizeBytes,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sha256) {}

  /**
   * Narrow media-owner reservation facts required for a mediated contractor evidence upload.
   *
   * @param evidenceId stable evidence identity
   * @param version current task-board evidence version
   * @param state current evidence state, initially RESERVED
   * @param entryId exact route entry that owns the evidence
   * @param ownerType fixed media owner type TASK_BOARD_ENTRY
   * @param ownerId media owner identity equal to entryId
   * @param warehouseId server-derived physical task warehouse
   * @param clientReferenceId media client reference equal to evidenceId
   * @param capturedAt declared capture time retained by task-board
   * @param contentType normalized declared image content type
   * @param sizeBytes declared upload byte length
   * @param sha256 lowercase SHA-256 checksum of the declared upload
   */
  public record ContractorEvidenceReservation(
      @NotNull UUID evidenceId,
      @Min(0) long version,
      @NotNull String state,
      @NotNull UUID entryId,
      @NotNull ContractorEvidenceOwnerType ownerType,
      @NotNull UUID ownerId,
      @NotNull UUID warehouseId,
      @NotNull UUID clientReferenceId,
      @NotNull OffsetDateTime capturedAt,
      @NotBlank String contentType,
      @Min(1) long sizeBytes,
      @NotBlank String sha256) {}
}
