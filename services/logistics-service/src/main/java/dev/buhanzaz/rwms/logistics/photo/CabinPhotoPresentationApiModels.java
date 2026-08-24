package dev.buhanzaz.rwms.logistics.photo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Transport-only request and response records for authenticated and anonymous photo routes. */
public final class CabinPhotoPresentationApiModels {
  private CabinPhotoPresentationApiModels() {}

  /** Command fencing presentation creation against the cabin card currently shown to the user. */
  public record CreateCabinPhotoPresentationRequest(
      @NotNull UUID warehouseId, @NotNull @Min(0) Long expectedRentalItemVersion) {}

  /** Authenticated result containing only manager-safe presentation metadata and public path. */
  public record CabinPhotoPresentationResponse(
      UUID id,
      long version,
      UUID cabinId,
      String cabinNumber,
      int photoCount,
      OffsetDateTime createdAt,
      String publicPath) {}

  /** One public photo with presentation-scoped thumbnail and content URLs. */
  public record CabinPhotoPresentationPhotoResponse(
      UUID mediaId,
      long generation,
      int sortOrder,
      String thumbnailUrl,
      String contentUrl) {}

  /**
   * Anonymous allowlisted view that excludes warehouse, actor, asset version, status, rental type
   * and the unrestricted cabin passport.
   */
  public record PublicCabinPhotoPresentationResponse(
      UUID id,
      String cabinNumber,
      String dimensions,
      String finishing,
      String category,
      List<String> characteristics,
      Boolean linoleum,
      OffsetDateTime createdAt,
      List<CabinPhotoPresentationPhotoResponse> photos) {}
}
