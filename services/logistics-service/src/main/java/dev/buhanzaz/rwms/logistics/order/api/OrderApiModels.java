package dev.buhanzaz.rwms.logistics.order.api;

import dev.buhanzaz.rwms.logistics.domain.CustomerDeliveryPurpose;
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
 * Defines transport models for rental-order HTTP operations; these values are not persistence
 * entities.
 */
public final class OrderApiModels {
  private OrderApiModels() {}

  /** Name and phone accepted for one non-primary client or order contact. */
  public record AdditionalContactInput(
      @NotBlank @Size(max = 255) String name, @NotBlank @Size(max = 32) String phone) {}

  /** Canonical additional-contact projection returned by logistics. */
  public record AdditionalContactResponse(String name, String phone) {}

  /** Inclusive client-requested receiving date range, independent from the logistics-assigned date. */
  public record DesiredDeliveryWindowInput(
      @NotNull LocalDate startDate, @NotNull LocalDate endDate) {
    @AssertTrue(message = "endDate must not precede startDate")
    public boolean hasOrderedDates() {
      return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }
  }

  /** Advisory client receiving date range returned independently from the logistics-assigned date. */
  public record DesiredDeliveryWindowResponse(LocalDate startDate, LocalDate endDate) {}

  /** Inline client facts accepted only when an order creates a client in the same transaction. */
  public record NewClientInput(
      @NotNull ClientType clientType,
      @NotBlank @Size(max = 512) String displayName,
      @NotBlank @Size(max = 32) String phone,
      @Size(min = 1, max = 255) String contactPerson,
      @Email @Size(max = 320) String email,
      @Size(min = 1, max = 2_000) String comment,
      @Size(min = 1, max = 255) String source,
      List<@NotNull @Valid AdditionalContactInput> additionalContacts) {
    public NewClientInput(
        ClientType clientType,
        String displayName,
        String phone,
        String contactPerson,
        String email,
        String comment,
        String source) {
      this(clientType, displayName, phone, contactPerson, email, comment, source, List.of());
    }

    @AssertTrue(message = "contactPerson is required for this client type")
    public boolean hasRequiredContactPerson() {
      return clientType == null
          || !clientType.requiresContactPerson()
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
      @Size(min = 1, max = 255) String source,
      List<@NotNull @Valid AdditionalContactInput> additionalContacts) {
    public CreateClientRequest(
        ClientType clientType,
        String displayName,
        String phone,
        String contactPerson,
        String email,
        String comment,
        String source) {
      this(clientType, displayName, phone, contactPerson, email, comment, source, List.of());
    }

    @AssertTrue(message = "contactPerson is required for this client type")
    public boolean hasRequiredContactPerson() {
      return clientType == null
          || !clientType.requiresContactPerson()
          || (contactPerson != null && !contactPerson.isBlank());
    }
  }

  /**
   * Creates an editable draft for exactly one existing or inline-created client; the client later
   * supplies its desired receiving window in the ordinary presentation confirmation.
   */
  public record CreateOrderRequest(
      UUID clientId,
      @Valid NewClientInput newClient,
      @Size(min = 1, max = 32) String contactPhone,
      @Size(min = 1, max = 2_000) String comment) {
    @AssertTrue(message = "Exactly one of clientId or newClient is required")
    public boolean hasExactlyOneClient() {
      return (clientId == null) != (newClient == null);
    }
  }

  /** Replaces the editable draft's selected client and manager-entered primary contact/comment. */
  public record UpdateOrderRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull UUID clientId,
      @Size(min = 1, max = 32) String contactPhone,
      @Size(min = 1, max = 2_000) String comment) {}

  /**
   * Warehouse-manager command replacing one cabin while retaining the order's regional warehouse.
   * A null source preserves the historical same-warehouse behavior.
   */
  public record ReplaceOrderUnitRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull UUID replacementRentalItemId,
      @NotBlank @Size(max = 2000) String reason,
      UUID inventorySourceWarehouseId) {
    public ReplaceOrderUnitRequest(
        Long expectedVersion, UUID replacementRentalItemId, String reason) {
      this(expectedVersion, replacementRentalItemId, reason, null);
    }
  }

  public record OrderDesiredEquipmentInput(
      @NotNull UUID equipmentId, @NotNull @Min(1) Long quantity) {}

  public record SetOrderUnitDesiredEquipmentRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Size(max = 100) List<@NotNull @Valid OrderDesiredEquipmentInput> requirements) {}

  public record OrderRentalTermExtensionInput(
      @NotNull UUID unitId, @NotNull @Min(1) Long additionalMonths) {}

  /** Extends one or more already shipped cabins without changing their shipment dates. */
  public record ExtendOrderRentalTermsRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Size(min = 1, max = 100)
          List<@NotNull @Valid OrderRentalTermExtensionInput> terms) {}

  /** Creates one independently scheduled shipment containing exactly the selected cabins. */
  public record CreateOrderRentalShipmentRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotBlank @Size(max = 512) String driverSnapshot,
      UUID driverWorkerId,
      @NotNull LocalDate scheduledDate,
      @NotNull @Size(min = 1, max = 100) List<@NotNull UUID> unitIds,
      Boolean warehouseDriverPool,
      UUID inventorySourceWarehouseId) {
    public CreateOrderRentalShipmentRequest {
      warehouseDriverPool = Boolean.TRUE.equals(warehouseDriverPool);
    }

    public CreateOrderRentalShipmentRequest(
        Long expectedVersion,
        String driverSnapshot,
        UUID driverWorkerId,
        LocalDate scheduledDate,
        List<UUID> unitIds) {
      this(expectedVersion, driverSnapshot, driverWorkerId, scheduledDate, unitIds, false);
    }

    public CreateOrderRentalShipmentRequest(
        Long expectedVersion, String driverSnapshot, LocalDate scheduledDate, List<UUID> unitIds) {
      this(expectedVersion, driverSnapshot, null, scheduledDate, unitIds, false);
    }

    /** Preserves source compatibility for callers that only select driver-pool publication. */
    public CreateOrderRentalShipmentRequest(
        Long expectedVersion,
        String driverSnapshot,
        UUID driverWorkerId,
        LocalDate scheduledDate,
        List<UUID> unitIds,
        Boolean warehouseDriverPool) {
      this(
          expectedVersion,
          driverSnapshot,
          driverWorkerId,
          scheduledDate,
          unitIds,
          warehouseDriverPool,
          null);
    }
  }

  /**
   * Logistics-owned rental-client projection. A historical responsible-manager display snapshot may
   * be null, while its truthful subject identifier is always present.
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
      List<AdditionalContactResponse> additionalContacts,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record ClientPageResponse(
      List<ClientResponse> content, long page, long size, long totalElements, long totalPages) {}

  /** Server-derived order command affordances; clients must not reconstruct role rules. */
  public record OrderPermissions(
      boolean canEdit,
      boolean canReplaceUnits,
      boolean canExtendRentalTerms,
      boolean canViewOtherManagers) {}

  /** Server-list projection including the delivery metadata needed to identify and filter work. */
  public record OrderSummaryResponse(
      UUID id,
      long version,
      String number,
      RentalOrderStatus status,
      CustomerDeliveryPurpose customerDeliveryPurpose,
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
      List<AdditionalContactResponse> additionalContacts,
      List<DesiredDeliveryWindowResponse> desiredDeliveryWindows,
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
      UUID equipmentId, String equipmentName, long quantity, String locationKind) {}

  public record OrderDesiredEquipmentResponse(
      UUID equipmentId, String equipmentName, long quantity, String reservationState) {}

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
      String reservationState,
      OrderRentalItemResponse unit,
      List<OrderDesiredEquipmentResponse> desiredContents,
      OrderRentalTermResponse rentalTerm) {}

  public record OrderUnitPageResponse(
      List<OrderUnitResponse> content, long page, long size, long totalElements, long totalPages) {}

  /** Cabin movement fact retaining the physical inventory source independently from the region. */
  public record OrderMovementCabinResponse(
      UUID rentalItemId, UUID inventorySourceWarehouseId, String lineState) {}

  /**
   * Shipment or return timeline fact owned by logistics. {@code scheduledDate} stays null for an
   * automatically created return until it is planned. {@code actualAt} is the completion time of
   * the shipped, accepted, or estimate-requested terminal branch, not an independently captured
   * physical-arrival time; maintenance estimates and repairs are deliberately not copied here.
   */
  public record OrderMovementResponse(
      UUID documentId,
      String documentType,
      CustomerDeliveryPurpose customerDeliveryPurpose,
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
      CustomerDeliveryPurpose customerDeliveryPurpose,
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
      List<AdditionalContactResponse> additionalContacts,
      List<DesiredDeliveryWindowResponse> desiredDeliveryWindows,
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
