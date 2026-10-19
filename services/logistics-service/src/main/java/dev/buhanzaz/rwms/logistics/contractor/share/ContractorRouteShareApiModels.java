package dev.buhanzaz.rwms.logistics.contractor.share;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Transport-only commands and allowlisted responses for contractor route sharing. */
public final class ContractorRouteShareApiModels {
  private ContractorRouteShareApiModels() {}

  /** Explicit dispatcher command creating one bounded route capability. */
  public record CreateContractorRouteShareRequest(
      @NotNull UUID contractorWorkerId,
      @NotNull OffsetDateTime expiresAt,
      @NotNull @Size(min = 1, max = 50) List<@NotNull UUID> externalTaskIds) {
    public CreateContractorRouteShareRequest {
      externalTaskIds = externalTaskIds == null ? List.of() : List.copyOf(externalTaskIds);
    }
  }

  /** Optimistically fenced command revoking a public contractor route. */
  public record RevokeContractorRouteShareRequest(@Min(0) long expectedVersion) {}

  /** Authenticated result containing lifecycle metadata and the scoped public path. */
  public record ContractorRouteShareResponse(
      UUID id,
      long version,
      UUID warehouseId,
      UUID contractorWorkerId,
      OffsetDateTime expiresAt,
      OffsetDateTime revokedAt,
      OffsetDateTime createdAt,
      String publicPath,
      List<UUID> externalTaskIds) {}

  /** Exact START or COMPLETE request forwarded under the route-entry version fence. */
  public record ApplyContractorRouteTaskActionRequest(
      @NotBlank @Size(max = 16) String action, @Min(0) long expectedVersion, UUID evidenceId) {}

  /** One local logistics-owned cargo row captured in the exact driver-task presentation. */
  public record PublicContractorCargoItem(
      String kind, String name, double quantity, String unit, String comment) {}

  /** One proven source-photo identity with only logistics-owned public proxy paths. */
  public record PublicContractorSourceMedia(
      UUID mediaId,
      long generation,
      String contentType,
      OffsetDateTime capturedAt,
      OffsetDateTime recordedAt,
      String contentPath,
      String thumbnailPath) {}

  /** One task-board-owned work instruction attached to an exact ordered route step. */
  public record PublicContractorWork(
      UUID id,
      String name,
      double quantity,
      String unit,
      Integer durationMinutes,
      String comment,
      List<UUID> sourceMediaIds) {
    public PublicContractorWork {
      sourceMediaIds = sourceMediaIds == null ? List.of() : List.copyOf(sourceMediaIds);
    }
  }

  /** One task-board-owned material instruction attached to an exact ordered route step. */
  public record PublicContractorMaterial(UUID id, String name, double quantity, String unit) {}

  /** One worker-visible task-board comment attached to an exact ordered route step. */
  public record PublicContractorComment(
      UUID id, String text, String authorDisplayName, OffsetDateTime createdAt) {}

  /**
   * One bounded task-board evidence fact for an exact route step. Bearer paths and internal review
   * notes are deliberately absent; proxy paths exist only for exact READY evidence.
   */
  public record PublicContractorEvidence(
      UUID evidenceId,
      long version,
      OffsetDateTime capturedAt,
      OffsetDateTime recordedAt,
      String state,
      UUID mediaId,
      Long mediaGeneration,
      String contentType,
      String contentPath,
      String thumbnailPath) {}

  /** Live exact task-board route step with no unrestricted board or media bearer path. */
  public record PublicContractorRouteEntry(
      UUID entryId,
      long version,
      int routeIndex,
      int routeStepIndex,
      int routeStepCount,
      String queueName,
      String taskText,
      String status,
      Integer plannedDurationMinutes,
      List<PublicContractorWork> works,
      List<PublicContractorMaterial> materials,
      List<PublicContractorComment> comments,
      List<PublicContractorSourceMedia> sourceMedia,
      int resultPhotoMinCount,
      List<PublicContractorEvidence> evidence,
      boolean completionAllowed) {}

  /** Public contractor task composed from live execution state and logistics-owned order facts. */
  public record PublicContractorRouteTask(
      UUID externalTaskId,
      UUID taskId,
      long taskVersion,
      String title,
      String description,
      String unitNumber,
      LocalDate scheduledDate,
      OffsetDateTime deadlineAt,
      int priority,
      String status,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      String contactPhone,
      String logisticsComment,
      List<PublicContractorCargoItem> cargo,
      List<PublicContractorSourceMedia> sourceMedia,
      List<PublicContractorRouteEntry> route) {}

  /** Anonymous no-store route view for one non-expired, non-revoked capability. */
  public record PublicContractorRouteShareResponse(
      UUID id, OffsetDateTime expiresAt, List<PublicContractorRouteTask> tasks) {}

  /** Refreshed exact task returned after one idempotent contractor action. */
  public record PublicContractorRouteTaskActionResponse(
      long currentVersion, PublicContractorRouteTask task) {}

  /** Current upload outcome used while task-board converges RESERVED to UPLOADING or READY. */
  public record PublicContractorEvidenceUploadResponse(
      UUID evidenceId,
      long version,
      String state,
      UUID mediaId,
      Long mediaGeneration,
      String contentType,
      String contentPath,
      String thumbnailPath) {}
}
