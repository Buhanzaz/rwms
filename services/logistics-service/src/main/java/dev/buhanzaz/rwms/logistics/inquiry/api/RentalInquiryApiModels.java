package dev.buhanzaz.rwms.logistics.inquiry.api;

import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentationMode;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingManagerAction;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.AdditionalContactInput;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.ClientResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.DesiredDeliveryWindowInput;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.DesiredDeliveryWindowResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.NewClientInput;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderEquipmentContentResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Defines transport models for rental inquiry, presentation and booking HTTP operations. */
public final class RentalInquiryApiModels {
  private RentalInquiryApiModels() {}

  public record CreateRentalInquiryRequest(
      UUID conversationId, UUID clientId, @Valid NewClientInput newClient, UUID rentalOrderId) {
    public CreateRentalInquiryRequest(
        UUID conversationId, UUID clientId, NewClientInput newClient) {
      this(conversationId, clientId, newClient, null);
    }

    @AssertTrue(message = "Exactly one of clientId or newClient is required")
    public boolean hasExactlyOneClient() {
      return (clientId == null) != (newClient == null);
    }

    @AssertTrue(message = "Manual inquiry requires an existing client and target order")
    public boolean hasManualTarget() {
      return conversationId != null
          || (rentalOrderId != null && clientId != null && newClient == null);
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
      UUID rentalOrderId,
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
      List<OrderEquipmentContentResponse> contents,
      OffsetDateTime updatedAt) {}

  public record CabinSearchGroupResult(
      CabinSearchGroup group, List<AvailableCabinResponse> cabins) {}

  public record CabinSearchResponse(
      UUID warehouseId, OffsetDateTime expiresAt, List<CabinSearchGroupResult> groups) {}

  /** Complete desired chat selection; an empty list releases every inquiry hold immediately. */
  public record CabinSelectionRequest(
      @NotNull UUID warehouseId, @NotNull @Size(max = 100) List<@NotNull UUID> rentalItemIds) {
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

  /** One asset-owned cabin availability decision and optional sanitized rejection reason. */
  public record CabinAvailabilityItem(UUID rentalItemId, boolean available, String reason) {}

  /** Warehouse-scoped availability decisions for the exact requested cabin set. */
  public record CabinAvailabilityResponse(UUID warehouseId, List<CabinAvailabilityItem> items) {}

  public record PresentationGroupInput(
      @NotBlank @Size(max = 128) String key,
      @NotBlank @Size(max = 255) String label,
      @NotNull @Size(min = 1, max = 30) List<@NotNull UUID> rentalItemIds) {}

  public record PublishClientPresentationRequest(
      @NotNull UUID warehouseId,
      @NotNull @Size(min = 1, max = 5) List<@NotNull @Valid PresentationGroupInput> groups,
      UUID manualBookingDraftId,
      ClientPresentationMode mode,
      @Size(max = 100) List<@NotNull UUID> replacementUnitIds) {
    public PublishClientPresentationRequest(
        UUID warehouseId, List<PresentationGroupInput> groups, UUID manualBookingDraftId) {
      this(warehouseId, groups, manualBookingDraftId, null, List.of());
    }
  }

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
      List<OrderEquipmentContentResponse> currentContents,
      List<PresentationPhoto> photos) {}

  /** One immutable presentation group and its held cabin snapshots. */
  public record PresentationGroup(String key, String label, List<PresentationCabin> cabins) {}

  /** Live asset-owned furniture availability shown uniformly in every presentation read. */
  public record PresentationEquipmentAvailability(
      UUID equipmentId, String equipmentName, long availableQuantity, Integer maximumPerCabin) {}

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
      ClientPresentationMode mode,
      List<UUID> replacementUnitIds,
      Integer requiredSelectionCount,
      boolean requiresDesiredDeliveryWindows,
      List<DesiredDeliveryWindowResponse> desiredDeliveryWindows,
      List<PresentationEquipmentAvailability> equipmentAvailability,
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
      UUID draftId, UUID warehouseId, OffsetDateTime expiresAt, List<UUID> rentalItemIds) {}

  /** One selected furniture quantity associated with one selected cabin. */
  public record PresentationEquipmentSelectionInput(
      @NotNull UUID equipmentId, @NotNull @Min(1) Long quantity) {}

  /** One cabin selected from the presentation together with its own furniture composition. */
  public record PresentationCabinSelectionInput(
      @NotNull UUID rentalItemId,
      @NotNull @Size(max = 100)
          List<@NotNull @Valid PresentationEquipmentSelectionInput> equipment) {}

  public record ConfirmClientPresentationRequest(
      @NotNull @Size(min = 1, max = 100)
          List<@NotNull @Valid PresentationCabinSelectionInput> selections,
      List<@NotNull @Valid DesiredDeliveryWindowInput> desiredDeliveryWindows,
      @Min(1) Long rentalMonths,
      @Size(min = 1, max = 1_000) String deliveryAddress,
      @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 2, fraction = 6) BigDecimal latitude,
      @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6)
          BigDecimal longitude,
      List<@NotNull @Valid AdditionalContactInput> additionalContacts) {
    public ConfirmClientPresentationRequest(List<PresentationCabinSelectionInput> selections) {
      this(selections, null, null, null, null, null, null);
    }

    public ConfirmClientPresentationRequest(
        List<PresentationCabinSelectionInput> selections,
        List<DesiredDeliveryWindowInput> desiredDeliveryWindows,
        Long rentalMonths) {
      this(selections, desiredDeliveryWindows, rentalMonths, null, null, null, null);
    }

    @AssertTrue(message = "latitude and longitude must be provided together")
    public boolean hasCoordinatePair() {
      return (latitude == null) == (longitude == null);
    }
  }

  public record PublicClientPresentationResponse(
      UUID id,
      long revision,
      String state,
      OffsetDateTime expiresAt,
      OffsetDateTime viewUntil,
      boolean viewOnly,
      ClientPresentationMode mode,
      Integer requiredSelectionCount,
      boolean requiresDesiredDeliveryWindows,
      List<DesiredDeliveryWindowResponse> desiredDeliveryWindows,
      List<PresentationEquipmentAvailability> equipmentAvailability,
      List<PresentationGroup> groups,
      UUID bookedOrderId) {}

  public record PresentationBookingResponse(
      UUID bookingId, String state, UUID orderId, String statusPath, String errorCode) {}

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
      @NotNull @Min(0) Long expectedVersion, @NotNull PresentationBookingManagerAction action) {}
}
