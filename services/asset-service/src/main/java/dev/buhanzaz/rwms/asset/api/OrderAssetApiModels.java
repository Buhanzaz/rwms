package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class OrderAssetApiModels {
  private OrderAssetApiModels() {}

  public record OrderActorRequest(
      @NotNull UUID actorSubjectId,
      @NotBlank
          @Size(max = 32)
          @Pattern(
              regexp =
                  "^(SYSTEM_ADMIN|WMS_ADMIN|WAREHOUSE_MANAGER|RENTAL_MANAGER|VIEWER)$")
          String actorRole) {}

  public record ReserveOrderUnitRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID rentalItemId,
      @NotNull UUID actorSubjectId,
      @NotBlank
          @Size(max = 32)
          @Pattern(
              regexp =
                  "^(SYSTEM_ADMIN|WMS_ADMIN|WAREHOUSE_MANAGER|RENTAL_MANAGER|VIEWER)$")
          String actorRole) {}

  public record AdjustOrderEquipmentRequest(
      @NotNull UUID actorSubjectId,
      @NotBlank
          @Size(max = 32)
          @Pattern(
              regexp =
                  "^(SYSTEM_ADMIN|WMS_ADMIN|WAREHOUSE_MANAGER|RENTAL_MANAGER|VIEWER)$")
          String actorRole,
      @NotNull @Min(0) Long expectedCurrentQuantity,
      @NotNull @Min(0) Long requiredQuantity) {}

  public record OrderEquipmentContent(
      UUID equipmentId,
      String equipmentCode,
      String equipmentName,
      long quantity,
      BalanceLocationKind locationKind) {}

  public record OrderRentalItem(
      UUID id,
      long version,
      UUID warehouseId,
      String number,
      RentalItemStatus status,
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      String characteristics,
      Boolean linoleum,
      List<String> tags,
      List<OrderEquipmentContent> contents,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record OrderUnitReservationView(
      UUID reservationId,
      long reservationVersion,
      UUID orderId,
      UUID rentalItemId,
      UUID warehouseId,
      String state,
      UUID addedBySubjectId,
      String addedByRole,
      OffsetDateTime createdAt,
      OffsetDateTime releasedAt,
      boolean replayed,
      OrderRentalItem unit) {}

  public record OrderUnitCandidate(
      UUID reservationId,
      boolean added,
      OrderRentalItem unit) {}

  public record OrderUnitCandidatePage(
      List<OrderUnitCandidate> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}

  public record OrderEquipmentMovement(
      UUID id,
      long version,
      UUID equipmentId,
      UUID sourceBalanceId,
      UUID targetBalanceId,
      long quantity,
      String kind,
      OffsetDateTime occurredAt) {}

  public record OrderEquipmentAdjustment(
      UUID orderId,
      UUID rentalItemId,
      UUID equipmentId,
      long previousQuantity,
      long requiredQuantity,
      long delta,
      long availableStock,
      OrderEquipmentMovement movement,
      OrderRentalItem unit) {}
}
