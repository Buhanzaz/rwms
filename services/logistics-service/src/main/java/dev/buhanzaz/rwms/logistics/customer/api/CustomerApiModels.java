package dev.buhanzaz.rwms.logistics.customer.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotKind;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerEntityType;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Transport models for the authenticated CustomerApp rental boundary. */
public final class CustomerApiModels {
  private CustomerApiModels() {}

  /** Creates the logistics profile linked to the authenticated customer subject. */
  public record CustomerProfileRequest(
      @NotNull CustomerEntityType entityType,
      @Size(max = 255) String firstName,
      @Size(max = 255) String lastName,
      @Size(max = 512) String companyName,
      @NotBlank @Size(max = 32) String phone,
      @Size(max = 320) String email,
      @Size(max = 2_000) String additionalInfo) {
    /** Enforces the exact required identity fields for each customer entity type. */
    @AssertTrue(message = "Required customer identity fields are missing")
    public boolean hasRequiredIdentity() {
      if (entityType == null) return true;
      return entityType == CustomerEntityType.INDIVIDUAL
          ? firstName != null
              && !firstName.isBlank()
              && lastName != null
              && !lastName.isBlank()
              && (companyName == null || companyName.isBlank())
          : companyName != null && !companyName.isBlank();
    }
  }

  /** Updates mutable profile fields while preserving the established entity type and bindings. */
  public record UpdateCustomerProfileRequest(
      @NotNull @Min(0) Long expectedVersion,
      @Size(max = 255) String firstName,
      @Size(max = 255) String lastName,
      @Size(max = 512) String companyName,
      @NotBlank @Size(max = 32) String phone,
      @Size(max = 320) String email,
      @Size(max = 2_000) String additionalInfo) {}

  /** Initializes the profile avatar owner proof at one validated customer warehouse. */
  public record PrepareCustomerProfileAvatarUploadRequest(
      @NotNull @Min(0) Long expectedVersion, @NotNull UUID warehouseId) {}

  /** Exact media owner facts returned to CustomerApp for one profile-avatar upload. */
  public record CustomerProfileAvatarUploadScope(
      long profileVersion,
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      String context) {}

  /** Binds one exact media-service-validated avatar generation under the profile fence. */
  public record SetCustomerProfileAvatarRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull UUID mediaId,
      @NotNull @Min(1) Long generation) {}

  /** Current profile avatar with same-origin authenticated media paths. */
  public record CustomerProfileAvatarResponse(
      UUID mediaId, long generation, UUID warehouseId, String thumbnailUrl, String url) {}

  /** Customer profile projection without authentication or normalized search internals. */
  public record CustomerProfileResponse(
      UUID id,
      long version,
      CustomerEntityType entityType,
      String firstName,
      String lastName,
      String companyName,
      String phone,
      String email,
      String additionalInfo,
      CustomerProfileAvatarResponse avatar) {}

  /** Customer-visible active warehouse with its canonical route-origin coordinates. */
  public record CustomerWarehouseResponse(
      UUID id,
      String name,
      String city,
      String address,
      String timezone,
      BigDecimal depotLatitude,
      BigDecimal depotLongitude) {}

  /** Durable subject-owned inbox entry; the subject binding itself is never returned. */
  public record CustomerNotificationResponse(
      UUID id,
      UUID orderId,
      UUID bookingId,
      String kind,
      String message,
      OffsetDateTime createdAt,
      OffsetDateTime readAt) {}

  /** Starts a new customer cart at one delivery-enabled warehouse. */
  public record CreateCustomerInquiryRequest(@NotNull UUID warehouseId) {}

  /** Customer inquiry identity and optimistic cart version. */
  public record CustomerInquiryResponse(
      UUID inquiryId, UUID warehouseId, long selectionVersion, String state) {}

  /** Protected photo references for one cabin card. */
  public record CustomerCabinPhoto(
      UUID photoId, long generation, String thumbnailUrl, String url) {}

  /** Free/held cabin facts and current informational monthly rent, separate from delivery price. */
  public record CustomerCabinResponse(
      UUID unitId,
      long version,
      String accountingNo,
      long pricingVersion,
      @JsonFormat(shape = JsonFormat.Shape.STRING) long monthlyPriceRubles,
      String type,
      String finish,
      String dimensions,
      String category,
      Boolean linoleum,
      List<String> characteristics,
      Map<String, Object> facts,
      List<CustomerCabinPhoto> photos) {}

  /** Bounded free-cabin page returned after server-side filtering. */
  public record CustomerCabinPage(
      List<CustomerCabinResponse> content,
      long page,
      long size,
      long totalElements,
      long totalPages,
      List<LocalDate> estimatedDeliveryDates) {}

  /** Replaces the complete cabin selection under an optimistic cart fence. */
  public record ReplaceCustomerCabinsRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Size(max = 100) List<@NotNull UUID> cabinUnitIds) {
    /** Rejects duplicate cabin identities before a remotely effective hold command is prepared. */
    @AssertTrue(message = "cabinUnitIds must be unique")
    public boolean hasUniqueCabins() {
      return cabinUnitIds == null
          || cabinUnitIds.size() == new java.util.LinkedHashSet<>(cabinUnitIds).size();
    }
  }

  /** Current authoritative cabin hold set and resulting cart version. */
  public record CustomerCabinSelectionResponse(
      long version, OffsetDateTime expiresAt, List<CustomerCabinResponse> cabins) {}

  /** One furniture catalogue item with positive warehouse stock only. */
  public record CustomerEquipmentAvailability(
      UUID inventoryItemId,
      String name,
      String category,
      long availableQuantity,
      Integer maximumPerCabin) {}

  /** Furniture quantity to move from warehouse stock into one selected cabin. */
  public record CustomerCabinEquipmentSelection(
      @NotNull UUID cabinUnitId,
      @NotNull UUID inventoryItemId,
      @NotNull @Min(1) Long quantity) {}

  /** Replaces the complete per-cabin furniture intent under the cart fence. */
  public record ReplaceCustomerEquipmentRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Size(max = 500)
          List<@NotNull @Valid CustomerCabinEquipmentSelection> selections) {}

  /** Persisted furniture intent and resulting cart version. */
  public record CustomerEquipmentSelectionResponse(
      long version, List<CustomerCabinEquipmentSelection> selections) {}

  /** Initial rental duration selected independently for one cabin in the cart. */
  public record CustomerCabinRentalTerm(
      @NotNull UUID cabinUnitId, @NotNull @Min(1) @Max(120) Long rentalMonths) {}

  /** Replaces the complete per-cabin rental term set under the cart fence. */
  public record ReplaceCustomerRentalTermsRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid CustomerCabinRentalTerm> terms) {}

  /** Persisted per-cabin rental terms and resulting cart version. */
  public record CustomerRentalTermsResponse(
      long version, List<CustomerCabinRentalTerm> terms) {}

  /** Cart projection used by the floating basket and checkout screen. */
  public record CustomerCartResponse(
      UUID inquiryId,
      UUID warehouseId,
      long version,
      String state,
      List<CustomerCabinResponse> cabins,
      List<CustomerCabinEquipmentSelection> equipment,
      List<CustomerCabinRentalTerm> rentalTerms,
      UUID deliverySlotId) {}

  /** Address and map coordinate used to calculate feasible dates before route attestations. */
  public record DeliverySlotSearchRequest(
      @NotNull UUID inquiryId,
      @NotBlank @Size(max = 1_000) String address,
      @NotNull @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 2, fraction = 6)
          BigDecimal latitude,
      @NotNull @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6)
          BigDecimal longitude,
      @Min(1) @Max(2) int siteCabinCapacity,
      boolean privateSiteAccessConfirmed,
      boolean failedTripChargeAcknowledged) {}

  /** Frozen truck-and-trailer dimensions used by the public-road routing decision. */
  public record CustomerRouteProfile(
      double combinationHeightMeters,
      double combinationWidthMeters,
      double combinationLengthMeters,
      double combinationWeightTons,
      double axleLoadTons,
      int axleCount) {}

  /** One server-calculated fixed-window or full-delivery-day offer. */
  public record CustomerDeliverySlotResponse(
      UUID slotId,
      long version,
      LocalDate date,
      CustomerDeliverySlotKind kind,
      LocalTime start,
      LocalTime end,
      int travelZoneHours,
      int capacityRemaining,
      int siteCabinCapacity,
      Long deliveryPriceRubles,
      UUID priceZoneId,
      Integer priceIsochroneMinutes,
      boolean roadRouteConfirmed,
      boolean privateSiteAccessConfirmed,
      boolean failedTripChargeAcknowledged,
      CustomerRouteProfile routeProfile,
      OffsetDateTime expiresAt,
      String state) {
    /** Preserves source compatibility for response assemblers predating ordinary tariff tiers. */
    public CustomerDeliverySlotResponse(
        UUID slotId,
        long version,
        LocalDate date,
        CustomerDeliverySlotKind kind,
        LocalTime start,
        LocalTime end,
        int travelZoneHours,
        int capacityRemaining,
        int siteCabinCapacity,
        Long deliveryPriceRubles,
        UUID priceZoneId,
        boolean roadRouteConfirmed,
        boolean privateSiteAccessConfirmed,
        boolean failedTripChargeAcknowledged,
        CustomerRouteProfile routeProfile,
        OffsetDateTime expiresAt,
        String state) {
      this(
          slotId,
          version,
          date,
          kind,
          start,
          end,
          travelZoneHours,
          capacityRemaining,
          siteCabinCapacity,
          deliveryPriceRubles,
          priceZoneId,
          null,
          roadRouteConfirmed,
          privateSiteAccessConfirmed,
          failedTripChargeAcknowledged,
          routeProfile,
          expiresAt,
          state);
    }
  }

  /** Fences a short-lived offer and supplies attestations not already made during search. */
  public record HoldCustomerDeliverySlotRequest(
      @NotNull UUID inquiryId,
      @NotNull @Min(0) Long expectedVersion,
      @Min(1) @Max(2) int siteCabinCapacity,
      @AssertTrue(message = "Private-site truck and trailer access must be confirmed")
          Boolean privateSiteAccessConfirmed,
      @AssertTrue(message = "Failed-trip responsibility must be acknowledged")
          Boolean failedTripChargeAcknowledged) {}

  /** Held delivery slot together with the cart version containing its identity. */
  public record HeldCustomerDeliverySlotResponse(
      long cartVersion, CustomerDeliverySlotResponse slot) {}

  /** Confirms the current cart and held route window after per-cabin terms are complete. */
  public record CustomerCheckoutRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull UUID slotId,
      @NotNull @Min(0) Long slotVersion) {}

  /** Cancels one completed booking under its current CustomerApp projection version. */
  public record CancelCustomerBookingRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull UUID changeQuoteId,
      @NotNull @Min(0) Long changeQuoteVersion,
      boolean testPaymentRequested) {
    public CancelCustomerBookingRequest(Long expectedVersion) {
      this(expectedVersion, null, null, false);
    }
  }

  /** Searches replacement delivery slots without reopening the original cart or its contents. */
  public record SearchCustomerBookingRescheduleRequest(
      @NotNull @Min(0) Long expectedVersion) {}

  /** Atomically swaps one completed booking to a freshly recalculated delivery slot. */
  public record RescheduleCustomerBookingRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull UUID slotId,
      @NotNull @Min(0) Long slotVersion,
      @NotNull UUID changeQuoteId,
      @NotNull @Min(0) Long changeQuoteVersion,
      boolean testPaymentRequested) {
    /** Dispatcher recovery is company-initiated and never enters customer fee admission. */
    public RescheduleCustomerBookingRequest(Long expectedVersion, UUID slotId, Long slotVersion) {
      this(expectedVersion, slotId, slotVersion, null, null, false);
    }
  }

  /** Durable presentation booking created by checkout. */
  public record CustomerBookingResponse(
      UUID bookingId,
      long version,
      UUID orderId,
      String status,
      String errorCode,
      UUID inquiryId,
      UUID slotId,
      UUID warehouseId,
      String deliveryAddress,
      LocalDate deliveryDate,
      LocalTime windowStart,
      LocalTime windowEnd,
      Long cancellationFeeRubles,
      List<CustomerBookingCabin> cabins) {
    /** Preserves source compatibility for checkout assemblers predating booking mutations. */
    public CustomerBookingResponse(
        UUID bookingId,
        UUID orderId,
        String status,
        String errorCode,
        UUID inquiryId,
        UUID slotId,
        UUID warehouseId,
        String deliveryAddress,
        LocalDate deliveryDate,
        LocalTime windowStart,
        LocalTime windowEnd,
        List<CustomerBookingCabin> cabins) {
      this(
          bookingId,
          0,
          orderId,
          status,
          errorCode,
          inquiryId,
          slotId,
          warehouseId,
          deliveryAddress,
          deliveryDate,
          windowStart,
          windowEnd,
          null,
          cabins);
    }
  }

  /** Exact shipment media owner that permits subject-bound customer evidence uploads. */
  public record CustomerShipmentMediaOwner(
      String ownerType, UUID documentId, UUID lineId, UUID warehouseId, String context) {}

  /** One normalized point in a full-screen customer signature stroke. */
  public record CustomerSignaturePoint(
      @DecimalMin("0") @DecimalMax("1") double x,
      @DecimalMin("0") @DecimalMax("1") double y,
      @Min(0) @Max(600_000) long elapsedMillis) {}

  /** One ordered stroke of the customer's drawn acceptance signature. */
  public record CustomerSignatureStroke(
      @NotNull @Size(min = 1, max = 256)
          List<@NotNull @Valid CustomerSignaturePoint> points) {}

  /** Drawn signature submitted only after the exact cabin's driver task has arrived. */
  public record AcceptCustomerCabinRequest(
      @NotNull @Size(min = 1, max = 32)
          List<@NotNull @Valid CustomerSignatureStroke> strokes) {
    /** Bounds the entire signature independently from per-stroke validation. */
    @AssertTrue(message = "Signature contains too many points")
    public boolean hasBoundedPointCount() {
      return strokes == null
          || strokes.stream().mapToLong(stroke -> stroke.points().size()).sum() <= 8_192;
    }
  }

  /** Immutable customer acceptance fact without exposing raw signature coordinates. */
  public record CustomerCabinAcceptanceResponse(
      UUID acceptanceId, long version, OffsetDateTime acceptedAt, int signaturePointCount) {}

  /** Ready media generation attached to a customer-reported cabin problem. */
  public record CustomerProblemMediaReference(
      @NotNull UUID mediaId, @Min(1) long generation) {}

  /** Supported customer problem categories for an arrived cabin. */
  public enum CustomerCabinProblemCategory {
    MISSING_EQUIPMENT,
    UNSUITABLE_CABIN,
    OTHER
  }

  /** Whether a problem was recorded before or after signed customer acceptance. */
  public enum CustomerCabinProblemPhase {
    BEFORE_ACCEPTANCE,
    AFTER_ACCEPTANCE
  }

  /** Customer problem report linked to ready evidence belonging to the exact shipment line. */
  public record ReportCustomerCabinProblemRequest(
      @NotNull CustomerCabinProblemCategory category,
      @NotBlank @Size(max = 2_000) String description,
      @NotNull @Size(max = 20)
          List<@NotNull @Valid CustomerProblemMediaReference> mediaReferences) {
    /** Rejects duplicate media generations before the owning service validates them. */
    @AssertTrue(message = "Problem media references must be unique")
    public boolean hasUniqueMediaReferences() {
      return mediaReferences == null
          || mediaReferences.size()
              == new java.util.LinkedHashSet<>(mediaReferences).size();
    }
  }

  /** Immutable problem report shown in the cabin's reception history. */
  public record CustomerCabinProblemResponse(
      UUID problemId,
      CustomerCabinProblemCategory category,
      CustomerCabinProblemPhase phase,
      String description,
      List<CustomerProblemMediaReference> mediaReferences,
      OffsetDateTime reportedAt) {}

  /** One cabin in My Orders with server-owned arrival, acceptance and problem state. */
  public record CustomerBookingCabin(
      UUID cabinUnitId,
      String accountingNo,
      long rentalMonths,
      String deliveryState,
      boolean arrivalEligible,
      CustomerShipmentMediaOwner mediaOwner,
      CustomerCabinAcceptanceResponse acceptance,
      List<CustomerCabinProblemResponse> problems) {}
}
