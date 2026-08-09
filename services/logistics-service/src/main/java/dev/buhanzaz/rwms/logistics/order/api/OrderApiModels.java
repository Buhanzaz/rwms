package dev.buhanzaz.rwms.logistics.order.api;

import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Defines transport models for rental-order HTTP operations; these values are not persistence entities.
 */
public final class OrderApiModels {
  private OrderApiModels() {}

  /** Inline client facts accepted only when an order creates a client in the same transaction. */
  public record NewClientInput(
      @NotNull ClientType clientType,
      @NotBlank @Size(max = 512) String displayName,
      @NotBlank @Size(max = 32) String phone,
      @Size(min = 1, max = 255) String contactPerson,
      @Email @Size(max = 320) String email,
      @Size(min = 1, max = 2_000) String comment,
      @Size(min = 1, max = 255) String source) {
    @AssertTrue(message = "contactPerson is required for this client type")
    public boolean hasRequiredContactPerson() {
      return clientType == null
          || clientType != ClientType.LEGAL_ENTITY
          || (contactPerson != null && !contactPerson.isBlank());
    }
  }

  /** Standalone client-create command; responsible-manager fields are deliberately server-owned. */
  public record CreateClientRequest(
      @NotNull ClientType clientType,
      @NotBlank @Size(max = 512) String displayName,
      @NotBlank @Size(max = 32) String phone,
      @Size(min = 1, max = 255) String contactPerson,
      @Email @Size(max = 320) String email,
      @Size(min = 1, max = 2_000) String comment,
      @Size(min = 1, max = 255) String source) {
    @AssertTrue(message = "contactPerson is required for this client type")
    public boolean hasRequiredContactPerson() {
      return clientType == null
          || clientType != ClientType.LEGAL_ENTITY
          || (contactPerson != null && !contactPerson.isBlank());
    }
  }

  /**
   * Creates an editable draft for exactly one existing or inline-created client; delivery facts may
   * be completed before save.
   */
  public record CreateOrderRequest(
      UUID clientId,
      @Valid NewClientInput newClient,
      @Size(min = 1, max = 1_000) String deliveryAddress,
      @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 2, fraction = 6)
          BigDecimal latitude,
      @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6)
          BigDecimal longitude,
      @Size(min = 1, max = 32) String contactPhone,
      @Size(min = 1, max = 2_000) String comment,
      @Size(max = 31) List<@NotNull LocalDate> acceptableDeliveryDates) {
    @AssertTrue(message = "Exactly one of clientId or newClient is required")
    public boolean hasExactlyOneClient() {
      return (clientId == null) != (newClient == null);
    }

    @AssertTrue(message = "latitude and longitude must be provided together")
    public boolean hasCoordinatePair() {
      return (latitude == null) == (longitude == null);
    }

    @AssertTrue(message = "acceptableDeliveryDates must be unique")
    public boolean hasUniqueAcceptableDeliveryDates() {
      return acceptableDeliveryDates == null
          || acceptableDeliveryDates.size()
              == new java.util.LinkedHashSet<>(acceptableDeliveryDates).size();
    }
  }

  /** Replaces the editable draft's selected client and complete delivery fact set under a fence. */
  public record UpdateOrderRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull UUID clientId,
      @Size(min = 1, max = 1_000) String deliveryAddress,
      @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 2, fraction = 6)
          BigDecimal latitude,
      @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6)
          BigDecimal longitude,
      @Size(min = 1, max = 32) String contactPhone,
      @Size(min = 1, max = 2_000) String comment,
      @Size(max = 31) List<@NotNull LocalDate> acceptableDeliveryDates) {
    @AssertTrue(message = "latitude and longitude must be provided together")
    public boolean hasCoordinatePair() {
      return (latitude == null) == (longitude == null);
    }

    @AssertTrue(message = "acceptableDeliveryDates must be unique")
    public boolean hasUniqueAcceptableDeliveryDates() {
      return acceptableDeliveryDates == null
          || acceptableDeliveryDates.size()
              == new java.util.LinkedHashSet<>(acceptableDeliveryDates).size();
    }
  }

  public record SelectWarehouseRequest(
      @NotNull @Min(0) Long expectedVersion, @NotNull UUID warehouseId) {}

  public record AddOrderUnitRequest(
      @NotNull @Min(0) Long expectedVersion, @NotNull UUID unitId) {}

  public record OrderDesiredEquipmentInput(
      @NotNull UUID equipmentId, @NotNull @Min(1) Long quantity) {}

  public record SetOrderUnitDesiredEquipmentRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull
          @Size(max = 100)
          List<@NotNull @Valid OrderDesiredEquipmentInput> requirements) {}

  public record OrderRentalTermInput(
      @NotNull UUID unitId, @NotNull @Min(1) Long rentalMonths) {}

  /** Replaces the complete duration vector for the currently selected order cabins. */
  public record SetOrderRentalTermsRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid OrderRentalTermInput> terms) {}

  public record OrderRentalTermExtensionInput(
      @NotNull UUID unitId, @NotNull @Min(1) Long additionalMonths) {}

  /** Extends one or more already shipped cabins without changing their shipment dates. */
  public record ExtendOrderRentalTermsRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull
          @Size(min = 1, max = 100)
          List<@NotNull @Valid OrderRentalTermExtensionInput> terms) {}

  /** Creates one independently scheduled shipment containing exactly the selected cabins. */
  public record CreateOrderRentalShipmentRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotBlank @Size(max = 512) String driverSnapshot,
      @NotNull LocalDate scheduledDate,
      @NotNull @Size(min = 1, max = 100) List<@NotNull UUID> unitIds) {}

  /**
   * Logistics-owned rental-client projection. A historical responsible-manager display snapshot
   * may be null, while its truthful subject identifier is always present.
   */
  public record ClientResponse(
      UUID id,
      long version,
      ClientType type,
      String displayName,
      String phone,
      String contactPerson,
      String email,
      UUID responsibleManagerId,
      String responsibleManagerDisplayName,
      String comment,
      String source,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record ClientPageResponse(
      List<ClientResponse> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}

  public record OrderPermissions(boolean canEdit, boolean canViewOtherManagers) {}

  /** Server-list projection including the delivery metadata needed to identify and filter work. */
  public record OrderSummaryResponse(
      UUID id,
      long version,
      String number,
      RentalOrderStatus status,
      ClientResponse client,
      UUID managerId,
      String managerDisplayName,
      UUID createdBy,
      String createdByDisplayName,
      UUID warehouseId,
      String deliveryAddress,
      BigDecimal latitude,
      BigDecimal longitude,
      String contactPhone,
      String comment,
      List<LocalDate> acceptableDeliveryDates,
      long unitCount,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record OrderPageResponse(
      List<OrderSummaryResponse> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}

  public record OrderEquipmentContentResponse(
      UUID equipmentId,
      String equipmentName,
      long quantity,
      String locationKind) {}

  public record OrderDesiredEquipmentResponse(
      UUID equipmentId, String equipmentName, long quantity) {}

  public record OrderRentalTermResponse(
      long rentalMonths, LocalDate shipmentDate, LocalDate returnDate) {}

  public record OrderRentalItemResponse(
      UUID id,
      long version,
      UUID warehouseId,
      String number,
      String status,
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      String characteristics,
      Boolean linoleum,
      List<String> tags,
      List<OrderEquipmentContentResponse> contents,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record OrderUnitResponse(
      UUID reservationId,
      boolean added,
      OrderRentalItemResponse unit,
      List<OrderDesiredEquipmentResponse> desiredContents,
      OrderRentalTermResponse rentalTerm) {}

  public record OrderUnitPageResponse(
      List<OrderUnitResponse> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}

  /** One cabin line in a logistics-owned shipment or return document. */
  public record OrderMovementCabinResponse(UUID rentalItemId, String lineState) {}

  /**
   * Shipment or return timeline fact owned by logistics. {@code scheduledDate} stays null for an
   * automatically created return until it is planned. {@code actualAt} is the completion time of
   * the shipped, accepted, or estimate-requested terminal branch, not an independently captured
   * physical-arrival time; maintenance estimates and repairs are deliberately not copied here.
   */
  public record OrderMovementResponse(
      UUID documentId,
      String documentType,
      String state,
      LocalDate scheduledDate,
      OffsetDateTime actualAt,
      UUID rentalShipmentId,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      List<OrderMovementCabinResponse> cabins) {}

  /**
   * Full order projection with current cabins plus logistics-owned shipment and return timeline
   * facts; it contains no maintenance-owned estimate or repair state.
   */
  public record OrderDetailResponse(
      UUID id,
      long version,
      String number,
      RentalOrderStatus status,
      ClientResponse client,
      UUID managerId,
      String managerDisplayName,
      UUID createdBy,
      String createdByDisplayName,
      UUID warehouseId,
      String deliveryAddress,
      BigDecimal latitude,
      BigDecimal longitude,
      String contactPhone,
      String comment,
      List<LocalDate> acceptableDeliveryDates,
      long unitCount,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      List<OrderUnitResponse> units,
      List<OrderMovementResponse> movements,
      OrderPermissions permissions) {}

  public record OrderHistoryEventResponse(
      UUID id,
      String eventType,
      UUID actorSubjectId,
      String actorRole,
      UUID orderId,
      String subjectType,
      String subjectId,
      Map<String, Object> previousValues,
      Map<String, Object> newValues,
      OffsetDateTime occurredAt) {}
}
