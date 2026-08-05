package dev.buhanzaz.rwms.maintenance.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Administrator-facing view and reviewed commands for durable furniture auto-link work. */
public final class FurnitureEquipmentLinkApiModels {
  private FurnitureEquipmentLinkApiModels() {}

  public enum FurnitureEquipmentLinkState {
    PENDING,
    IN_FLIGHT,
    RETRY_PENDING,
    CONFIRMED,
    REVIEW_REQUIRED,
    ABANDONED
  }

  public enum FurnitureEquipmentLinkReviewAction { RETRY, ABANDON }

  public record FurnitureEquipmentLinkReviewRequest(
      @NotNull @Min(0) Long expectedReviewVersion,
      @NotNull FurnitureEquipmentLinkReviewAction action,
      @NotBlank @Size(max = 2000) String reason) {}

  public record FurnitureEquipmentLinkResponse(
      UUID nodeId,
      UUID warehouseId,
      UUID sourceCatalogVersionId,
      long sourceCatalogExpectedVersion,
      String requestedName,
      FurnitureEquipmentLinkState state,
      UUID equipmentId,
      String equipmentName,
      UUID observedEquipmentId,
      String observedEquipmentName,
      int attemptCount,
      OffsetDateTime nextAttemptAt,
      OffsetDateTime claimUntil,
      String lastErrorCode,
      String lastErrorDetail,
      long reviewVersion,
      UUID reviewedBySubjectId,
      FurnitureEquipmentLinkReviewAction reviewAction,
      String reviewReason,
      OffsetDateTime reviewedAt,
      OffsetDateTime confirmedAt,
      OffsetDateTime abandonedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}
}
