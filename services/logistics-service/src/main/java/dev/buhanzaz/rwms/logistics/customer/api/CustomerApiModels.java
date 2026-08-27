package dev.buhanzaz.rwms.logistics.customer.api;

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
      String additionalInfo) {}

  /** Active warehouse that is configured for CustomerApp route-capacity calculation. */
  public record CustomerWarehouseResponse(
      UUID id,
      String name,
      String city,
      String address,
      String timezone,
      BigDecimal depotLatitude,
      BigDecimal depotLongitude) {}

  /** Starts a new customer cart at one delivery-enabled warehouse. */
  public record CreateCustomerInquiryRequest(@NotNull UUID warehouseId) {}

  /** Customer inquiry identity and optimistic cart version. */
  public record CustomerInquiryResponse(
      UUID inquiryId, UUID warehouseId, long selectionVersion, String state) {}

  /** Protected photo references for one cabin card. */
  public record CustomerCabinPhoto(
      UUID photoId, long generation, String thumbnailUrl, String url) {}

  /** Facts shown on one free-cabin card; no dossier/passport navigation path is exposed. */
  public record CustomerCabinResponse(
      UUID unitId,
      long version,
      String accountingNo,
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
      long totalPages) {}

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

  /** Cart projection used by the floating basket and checkout screen. */
  public record CustomerCartResponse(
      UUID inquiryId,
      UUID warehouseId,
      long version,
      String state,
      List<CustomerCabinResponse> cabins,
      List<CustomerCabinEquipmentSelection> equipment,
      UUID deliverySlotId) {}

  /** Address and map coordinate used to calculate only feasible delivery slots. */
  public record DeliverySlotSearchRequest(
      @NotNull UUID inquiryId,
      @NotBlank @Size(max = 1_000) String address,
      @NotNull @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 2, fraction = 6)
          BigDecimal latitude,
      @NotNull @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6)
          BigDecimal longitude) {}

  /** One server-calculated offer from the fixed working-day window set. */
  public record CustomerDeliverySlotResponse(
      UUID slotId,
      long version,
      LocalDate date,
      LocalTime start,
      LocalTime end,
      int travelZoneHours,
      int capacityRemaining,
      OffsetDateTime expiresAt,
      String state) {}

  /** Fences a short-lived delivery offer against the latest cart version. */
  public record HoldCustomerDeliverySlotRequest(
      @NotNull UUID inquiryId, @NotNull @Min(0) Long expectedVersion) {}

  /** Held delivery slot together with the cart version containing its identity. */
  public record HeldCustomerDeliverySlotResponse(
      long cartVersion, CustomerDeliverySlotResponse slot) {}

  /** Confirms the current cart, held route window and initial rental duration. */
  public record CustomerCheckoutRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull UUID slotId,
      @NotNull @Min(0) Long slotVersion,
      @NotNull @Min(1) @Max(120) Long rentalMonths) {}

  /** Durable presentation booking created by checkout. */
  public record CustomerBookingResponse(
      UUID bookingId,
      UUID orderId,
      String status,
      String errorCode,
      UUID inquiryId,
      UUID slotId) {}
}
