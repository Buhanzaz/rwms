package dev.buhanzaz.rwms.logistics.order.api;

import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OrderApiModels {
  private OrderApiModels() {}

  public record NewClientInput(
      @NotNull ClientType clientType,
      @NotBlank @Size(max = 512) String displayName,
      @NotBlank @Size(max = 32) String phone,
      @Email @Size(max = 320) String email) {}

  public record CreateClientRequest(
      @NotNull ClientType clientType,
      @NotBlank @Size(max = 512) String displayName,
      @NotBlank @Size(max = 32) String phone,
      @Email @Size(max = 320) String email) {}

  public record CreateOrderRequest(UUID clientId, @Valid NewClientInput newClient) {
    @AssertTrue(message = "Exactly one of clientId or newClient is required")
    public boolean hasExactlyOneClient() {
      return (clientId == null) != (newClient == null);
    }
  }

  public record UpdateOrderRequest(
      @NotNull @Min(0) Long expectedVersion, @NotNull UUID clientId) {}

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

  public record ClientResponse(
      UUID id,
      long version,
      ClientType type,
      String displayName,
      String phone,
      String email,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record ClientPageResponse(
      List<ClientResponse> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}

  public record OrderPermissions(boolean canEdit, boolean canViewOtherManagers) {}

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
      long unitCount,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      List<OrderUnitResponse> units,
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
