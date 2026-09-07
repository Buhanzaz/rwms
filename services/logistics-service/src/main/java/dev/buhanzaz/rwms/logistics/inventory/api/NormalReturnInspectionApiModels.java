package dev.buhanzaz.rwms.logistics.inventory.api;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Read-only logistics proof that one normal return inspection completed. */
public final class NormalReturnInspectionApiModels {
  private NormalReturnInspectionApiModels() {}

  /** Document-level terminal proof for one normal return. */
  public record NormalReturnInspectionResponse(
      UUID returnId,
      long documentVersion,
      UUID warehouseId,
      OffsetDateTime arrivedAt,
      OffsetDateTime completedAt,
      LogisticsDocumentState terminalState,
      List<NormalReturnInspectionLine> lines) {}

  /** Per-cabin asset and inspection-media proof from the completed return workflow. */
  public record NormalReturnInspectionLine(
      UUID lineId,
      UUID assetId,
      long assetVersion,
      NormalReturnAssetStatus status,
      List<NormalReturnInspectionMedia> media) {}

  /** Opaque reference to media whose external logistics-return ownership was verified. */
  public record NormalReturnInspectionMedia(
      UUID mediaId,
      long generation,
      NormalReturnMediaOwnerType ownerType,
      OffsetDateTime ownerVerifiedAt) {}

  /** Asset state confirmed by the terminal return branch. */
  public enum NormalReturnAssetStatus {
    FREE,
    WAITING_ESTIMATE_CONFIRMATION
  }

  /** External owner type retained by every returned media reference. */
  public enum NormalReturnMediaOwnerType {
    LOGISTICS_RETURN
  }
}
