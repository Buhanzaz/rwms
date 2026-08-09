package dev.buhanzaz.rwms.logistics.inquiry.api;

import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingManagerAction;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.ClientResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.NewClientInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Defines transport models for rental inquiry, presentation and booking HTTP operations.
 */
public final class RentalInquiryApiModels {
  private RentalInquiryApiModels() {}

  public record CreateRentalInquiryRequest(
      @NotNull UUID conversationId, UUID clientId, @Valid NewClientInput newClient) {
    @AssertTrue(message = "Exactly one of clientId or newClient is required")
    public boolean hasExactlyOneClient() {
      return (clientId == null) != (newClient == null);
    }
  }

  public record RentalInquiryResponse(
      UUID id,
      long version,
      UUID conversationId,
      ClientResponse client,
      UUID managerId,
      String managerDisplayName,
      String managerRole,
      UUID warehouseId,
      String state,
      UUID bookedOrderId,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** Warehouse identity plus exact asset-owned facet values and type-to-dimension relations. */
  public record CabinFacetWarehouse(
      UUID warehouseId,
      String name,
      String city,
      List<String> cabinTypes,
      List<String> finishes,
      List<String> dimensions,
      List<String> categories,
      List<String> characteristics,
      List<CabinTypeDimensionRelation> typeDimensions) {}

  /** Exact available dimension names related to one cabin type. */
  public record CabinTypeDimensionRelation(String cabinType, List<String> dimensions) {}

  /** Available cabin facets for the inquiry's currently authorized warehouse set. */
  public record CabinFacetsResponse(List<CabinFacetWarehouse> warehouses) {}

  public record CabinSearchGroup(
      @Size(max = 255) String cabinType,
      @Size(max = 255) String finish,
      @Size(max = 255) String dimensions,
      @Size(max = 255) String category,
      @Size(max = 2_000) String characteristics,
      Boolean linoleum,
      @NotNull @Min(1) @Max(30) Integer quantity) {}

  /** Controls whether a cabin search replaces or explicitly extends the inquiry selection. */
  public enum CabinSearchResultMode {
    APPEND,
    REPLACE
  }

  /** Structured cabin search whose omitted result mode safely replaces the previous selection. */
  public record CabinSearchRequest(
      @NotNull UUID warehouseId,
      CabinSearchResultMode resultMode,
      @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid CabinSearchGroup> groups) {
    public CabinSearchRequest(UUID warehouseId, List<CabinSearchGroup> groups) {
      this(warehouseId, CabinSearchResultMode.REPLACE, groups);
    }

    /** Returns REPLACE for omitted legacy requests; APPEND must always be explicit. */
    public CabinSearchResultMode effectiveResultMode() {
      return resultMode == null ? CabinSearchResultMode.REPLACE : resultMode;
    }
  }

  public record AvailableCabinResponse(
      UUID id,
      long version,
      UUID warehouseId,
      String status,
      String number,
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      String characteristics,
      Boolean linoleum,
      Map<String, Object> passport,
      List<String> tags,
      OffsetDateTime updatedAt) {}

  public record CabinSearchGroupResult(
      CabinSearchGroup group, List<AvailableCabinResponse> cabins) {}

  public record CabinSearchResponse(
      UUID warehouseId, OffsetDateTime expiresAt, List<CabinSearchGroupResult> groups) {}

  /** Complete desired chat selection; an empty list releases every inquiry hold immediately. */
  public record CabinSelectionRequest(
      @NotNull UUID warehouseId,
      @NotNull @Size(max = 100) List<@NotNull UUID> rentalItemIds) {
    @AssertTrue(message = "rentalItemIds must be unique")
    public boolean hasUniqueRentalItemIds() {
      return rentalItemIds == null
          || rentalItemIds.size() == new java.util.LinkedHashSet<>(rentalItemIds).size();
    }
  }

  /** Authoritative active holds and current cabin snapshots for one inquiry. */
  public record CabinSelectionResponse(
      UUID inquiryId,
      UUID warehouseId,
      OffsetDateTime expiresAt,
      List<UUID> rentalItemIds,
      List<AvailableCabinResponse> items) {}

  /** Bounded facts-only cabin catalog page; reading it never creates or renews a hold. */
  public record CabinCatalogResponse(
      UUID warehouseId,
      List<AvailableCabinResponse> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}

  public record CabinAvailabilityRequest(
      @NotNull UUID warehouseId,
      @NotNull @Size(min = 1, max = 100) List<@NotNull UUID> rentalItemIds) {}

  public record CabinAvailabilityItem(
      UUID rentalItemId, boolean available, String reason) {}

  public record CabinAvailabilityResponse(
      UUID warehouseId, List<CabinAvailabilityItem> items) {}

  public record PresentationGroupInput(
      @NotBlank @Size(max = 128) String key,
      @NotBlank @Size(max = 255) String label,
      @NotNull @Size(min = 1, max = 30) List<@NotNull UUID> rentalItemIds) {}

  public record PublishClientPresentationRequest(
      @NotNull UUID warehouseId,
      @NotNull @Size(min = 1, max = 5)
          List<@NotNull @Valid PresentationGroupInput> groups,
      UUID manualBookingDraftId) {}

  public record PresentationPhoto(
      UUID mediaId,
      long generation,
      int sortOrder,
      List<String> availableVariants,
      String thumbnailUrl,
      String contentUrl) {}

  public record PresentationCabin(
      UUID id,
      String number,
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      String characteristics,
      Boolean linoleum,
      Map<String, Object> passport,
      List<String> tags,
      List<PresentationPhoto> photos) {}

  public record PresentationGroup(
      String key, String label, List<PresentationCabin> cabins) {}

  public record ClientPresentationResponse(
      UUID id,
      long version,
      long revision,
      UUID inquiryId,
      UUID warehouseId,
      String state,
      OffsetDateTime expiresAt,
      OffsetDateTime viewUntil,
      boolean canConfirm,
      String publicPath,
      UUID bookedOrderId,
      List<PresentationGroup> groups) {}

  public record RentalSettingsResponse(
      long version,
      int chatSelectionHoldMinutes,
      int manualBookingHoldMinutes,
      int presentationHoldMinutes,
      int draftReservationHoldMinutes,
      UUID updatedBy,
      OffsetDateTime updatedAt) {}

  public record UpdateRentalSettingsRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Min(1) @Max(1_440) Integer chatSelectionHoldMinutes,
      @NotNull @Min(5) @Max(1_440) Integer manualBookingHoldMinutes,
      @NotNull @Min(5) @Max(1_440) Integer presentationHoldMinutes,
      @NotNull @Min(1_440) @Max(14_400) Integer draftReservationHoldMinutes) {}

  public record ManualBookingDraftHoldsRequest(
      @NotNull UUID warehouseId,
      @NotNull @Size(min = 1, max = 100) List<@NotNull UUID> rentalItemIds) {}

  public record ManualBookingDraftHoldsResponse(
      UUID draftId,
      UUID warehouseId,
      OffsetDateTime expiresAt,
      List<UUID> rentalItemIds) {}

  public record ConfirmClientPresentationRequest(
      @NotNull @Size(min = 1, max = 100) List<@NotNull UUID> selectedRentalItemIds) {}

  public record PublicClientPresentationResponse(
      UUID id,
      long revision,
      String state,
      OffsetDateTime expiresAt,
      OffsetDateTime viewUntil,
      boolean viewOnly,
      List<PresentationGroup> groups,
      UUID bookedOrderId) {}

  public record PresentationBookingResponse(
      UUID bookingId,
      String state,
      UUID orderId,
      String statusPath,
      String errorCode) {}

  public record RentalBookingAlertResponse(
      UUID bookingId,
      long version,
      UUID inquiryId,
      UUID orderId,
      ClientResponse client,
      OffsetDateTime confirmedAt,
      List<RentalBookingAlertCabin> cabins) {}

  public record RentalBookingAlertCabin(
      UUID id,
      String number,
      String rentalType,
      String dimensions,
      String finishing,
      String category) {}

  public record RentalBookingAlertActionRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull PresentationBookingManagerAction action) {}
}
