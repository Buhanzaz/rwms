package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsEquipmentMovementReservationResponse;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * HTTP transport model container for order asset.
 * Its records are boundary representations, not persistence entities.
 */
public final class OrderAssetApiModels {
  private OrderAssetApiModels() {}

  public record OrderActorRequest(
      @NotNull UUID actorSubjectId,
      @NotBlank
          @Size(max = 32)
          @Pattern(
              regexp =
                  "^(SYSTEM_ADMIN|WMS_ADMIN|WAREHOUSE_MANAGER|RENTAL_MANAGER|CUSTOMER|VIEWER)$")
          String actorRole) {}

  public record ReserveOrderUnitRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID rentalItemId,
      @NotNull UUID clientId,
      @NotBlank @Size(max = 512) String tenantSnapshot,
      OffsetDateTime draftReservationExpiresAt,
      @NotNull UUID actorSubjectId,
      @NotBlank
          @Size(max = 32)
          @Pattern(
              regexp =
                  "^(SYSTEM_ADMIN|WMS_ADMIN|WAREHOUSE_MANAGER|RENTAL_MANAGER|CUSTOMER|VIEWER)$")
          String actorRole) {}

  public record OrderEquipmentRequirement(
      @NotNull UUID equipmentId, @NotNull @Min(1) Long quantity) {}

  /** Equipment requested for one existing order cabin. */
  public record OrderUnitEquipmentRequirements(
      @NotNull UUID rentalItemId,
      @NotNull @Size(max = 100) List<@NotNull @Valid OrderEquipmentRequirement> requirements) {}

  /** Authoritative full-order per-unit furniture composition for one atomic reservation replace. */
  public record ReplaceOrderEquipmentReservationsRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID actorSubjectId,
      @NotBlank
          @Size(max = 32)
          @Pattern(
              regexp =
                  "^(SYSTEM_ADMIN|WMS_ADMIN|WAREHOUSE_MANAGER|RENTAL_MANAGER|CUSTOMER|VIEWER)$")
              String actorRole,
      @NotNull @Size(max = 100) List<@NotNull @Valid OrderUnitEquipmentRequirements> units) {}

  /** Shared reservation view with live global availability and the catalog per-cabin maximum. */
  public record OrderEquipmentReservationView(
      UUID equipmentId,
      String equipmentName,
      long quantity,
      long availableQuantity,
      Integer maximumPerCabin) {}

  /** Full-order composition used to plan one cabin's exact physical equipment delta. */
  public record OrderFurnitureMovementPlanRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID rentalItemId,
      UUID replacementForRentalItemId,
      @NotNull @Size(max = 100) List<@NotNull @Valid OrderEquipmentRequirement> requirements,
      @NotNull @Size(max = 100) List<@NotNull @Valid OrderUnitEquipmentRequirements> units) {}

  public record OrderFurnitureMovementPlanLine(
      UUID equipmentId,
      String equipmentName,
      UUID sourceBalanceId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      BalanceLocationKind sourceLocationKind,
      long expectedSourceBalanceVersion,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      BalanceLocationKind targetLocationKind,
      long quantity) {}

  public record OrderFurnitureMovementPlan(
      UUID orderId,
      UUID rentalItemId,
      String unitNumber,
      List<OrderFurnitureMovementPlanLine> lines) {}

  /** Existing logistics movement identity and exact source lines fenced by a unit replacement. */
  public record OrderUnitReplacementMovementBundle(
      @NotNull UUID movementId,
      @NotNull OffsetDateTime reservedUntil,
      @NotNull @Size(min = 1, max = 100)
          List<@NotNull @Valid OrderUnitReplacementMovementLine> lines) {}

  /** One deterministic existing movement-task line from the old cabin to its replacement. */
  public record OrderUnitReplacementMovementLine(
      @NotNull UUID lineId,
      @NotNull UUID equipmentId,
      @NotNull UUID sourceBalanceId,
      @NotNull @Min(0) Long expectedSourceBalanceVersion,
      @NotNull UUID targetRentalItemId,
      @NotNull @Min(1) Long quantity) {}

  /** One ordered old-to-new cabin pair and its optional exact furniture movement bundle. */
  public record OrderUnitReplacement(
      @NotNull UUID rentalItemId,
      @NotNull UUID replacementRentalItemId,
      @Valid OrderUnitReplacementMovementBundle movement) {}

  /** Authoritative post-replacement composition for one atomic multi-cabin swap. */
  public record ReplaceOrderUnitsRequest(
      @NotNull UUID warehouseId,
      UUID presentationId,
      @NotNull UUID actorSubjectId,
      @NotBlank
          @Size(max = 32)
          @Pattern(
              regexp =
                  "^(SYSTEM_ADMIN|WMS_ADMIN|WAREHOUSE_MANAGER|RENTAL_MANAGER|CUSTOMER|VIEWER)$")
          String actorRole,
      @NotNull @Size(min = 1, max = 100)
          List<@NotNull @Valid OrderUnitEquipmentRequirements> units,
      @NotNull @Size(min = 1, max = 100)
          List<@NotNull @Valid OrderUnitReplacement> replacements) {}

  /** Result for one pair within an all-or-nothing replacement receipt. */
  public record OrderUnitReplacementReceipt(
      OrderUnitReservationView releasedReservation,
      OrderUnitReservationView replacementReservation,
      List<LogisticsEquipmentMovementReservationResponse> movementReservations,
      boolean contentReady) {}

  /** Ordered all-or-nothing receipt for one multi-cabin replacement command. */
  public record OrderUnitsReplacementReceipt(
      List<OrderUnitReplacementReceipt> replacements, boolean replayed) {}

  public record OrderEquipmentContent(
      UUID equipmentId,
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

}
