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

  public record CabinFacetWarehouse(
      UUID warehouseId,
      String name,
      String city,
      List<String> cabinTypes,
      List<String> finishes,
      List<String> dimensions,
      List<String> categories) {}

  public record CabinFacetsResponse(List<CabinFacetWarehouse> warehouses) {}

  public record CabinSearchGroup(
      @Size(max = 255) String cabinType,
      @Size(max = 255) String finish,
      @Size(max = 255) String dimensions,
      @Size(max = 255) String category,
      @Size(max = 2_000) String characteristics,
      Boolean linoleum,
      @NotNull @Min(1) @Max(30) Integer quantity) {}

  public record CabinSearchRequest(
      @NotNull UUID warehouseId,
      @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid CabinSearchGroup> groups) {}

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
