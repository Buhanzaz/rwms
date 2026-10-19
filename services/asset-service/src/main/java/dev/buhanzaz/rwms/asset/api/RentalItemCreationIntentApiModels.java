package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.domain.RentalItemCreationIntentState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Transport records for the asset-owned mandatory-photo cabin creation workflow. */
public final class RentalItemCreationIntentApiModels {
  private RentalItemCreationIntentApiModels() {}

  /** Requests atomic creation of a cabin plus a durable mandatory-photo intent. */
  public record CreateRentalItemWithPhotoIntentRequest(
      @NotNull @Valid CreateRentalItemRequest rentalItem,
      @NotEmpty @Size(max = 20)
          List<@NotNull @Valid RentalItemCreationPhotoManifestInput> photoManifest) {}

  /** One ordered source-photo identity supplied before any media upload is created. */
  public record RentalItemCreationPhotoManifestInput(
      @NotNull @Min(0) @Max(19) Integer photoIndex,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String checksumSha256,
      @NotBlank @Pattern(regexp = "^image/(?:jpeg|png|webp)$") String contentType,
      @NotNull @Positive Long contentLength) {}

  /** Returns the atomically created cabin and its resumable photo intent. */
  public record CreateRentalItemWithPhotoIntentResponse(
      RentalItemResponse rentalItem, RentalItemCreationIntentResponse intent) {}

  /** Version fence shared by completion and explicit abandonment commands. */
  public record RentalItemCreationIntentCommand(
      @NotNull @Min(0) Long expectedVersion) {}

  /** Public resumable state without internal lease credentials or storage locations. */
  public record RentalItemCreationIntentResponse(
      UUID id,
      long version,
      UUID rentalItemId,
      UUID warehouseId,
      RentalItemCreationIntentState state,
      int expectedPhotoCount,
      UUID mediaFolderId,
      UUID mediaCommandId,
      String photoManifestSha256,
      List<RentalItemCreationPhotoManifestEntry> photoManifest,
      UUID coverMediaId,
      String mediaProofSha256,
      OffsetDateTime createdAt,
      OffsetDateTime completedAt,
      OffsetDateTime abandonedAt) {}

  /** One persisted manifest row with the stable command key for its upload session. */
  public record RentalItemCreationPhotoManifestEntry(
      int photoIndex,
      UUID uploadCommandId,
      String checksumSha256,
      String contentType,
      long contentLength) {}

  /** Bounded warehouse-scoped page of pending creation intents. */
  public record RentalItemCreationIntentPage(
      List<RentalItemCreationIntentResponse> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}
}
