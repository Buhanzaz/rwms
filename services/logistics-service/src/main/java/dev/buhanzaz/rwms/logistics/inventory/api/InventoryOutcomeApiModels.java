package dev.buhanzaz.rwms.logistics.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Exact private transport contract for authoritative completed-inventory logistics outcomes. */
public final class InventoryOutcomeApiModels {
  private InventoryOutcomeApiModels() {}

  /** Logistics interpretation selected for one completed-inventory finding. */
  public enum InventoryDispositionKind {
    LOCAL,
    SHIPMENT,
    WRITE_OFF
  }

  /** The asset or logistics marker state published by a completed inventory disposition. */
  public enum InventoryDesiredStatus {
    FREE,
    REPAIR,
    CAPITAL_REPAIR,
    RENTED,
    WRITE_OFF_PENDING
  }

  /** Former rental identity retained by an inventory-created historical return. */
  public record InventoryFormerRental(
      @NotNull LocalDate returnedOn,
      @NotNull UUID clientId,
      @NotNull @Size(min = 1, max = 512) String clientSnapshot) {}

  /** One exact furniture quantity placed in an inventory-created shipment. */
  public record InventoryShipmentFurniture(
      @NotNull UUID equipmentId,
      @NotNull @PositiveOrZero Long catalogVersion,
      @NotNull @Positive Long quantity) {}

  /** Historical shipment evidence selected for a cabin that remained missing after inventory. */
  public record InventoryShipment(
      @NotNull LocalDate departedOn,
      @NotNull UUID clientId,
      @NotNull @Size(min = 1, max = 512) String clientSnapshot,
      @NotNull @Size(max = 100) List<@NotNull @Valid InventoryShipmentFurniture> furniture) {}

  /** One already-resolved finding; asset identity is canonical and never matched by display text. */
  public record InventoryAssetOutcome(
      @NotNull UUID findingId,
      @NotNull UUID assetId,
      @NotNull InventoryDispositionKind dispositionKind,
      @NotNull InventoryDesiredStatus desiredStatus,
      @Valid InventoryFormerRental formerRental,
      @Valid InventoryShipment shipment) {}

  /** Immutable final-plan identity and its complete warehouse-scoped asset outcomes. */
  public record ApplyInventoryOutcomeRequest(
      @NotNull UUID warehouseId,
      @NotNull OffsetDateTime inventoryCompletedAt,
      @Positive long finalPlanVersion,
      @NotNull @Pattern(regexp = "[0-9a-f]{64}") String finalPlanSha256,
      @NotNull @Size(min = 1, max = 5000) List<@Valid InventoryAssetOutcome> outcomes) {}

  /** Durable logistics fact created for one explicit inventory disposition. */
  public record InventoryDispositionResult(
      UUID findingId,
      UUID assetId,
      InventoryDispositionKind dispositionKind,
      UUID markerId,
      UUID documentId,
      UUID lineId) {}

  /** Frozen successful result; lists are sorted and contain unique logistics-owned identifiers. */
  public record ApplyInventoryOutcomeResponse(
      UUID inventoryId,
      long finalPlanVersion,
      List<UUID> supersededDocumentIds,
      List<UUID> supersededRentalOrderIds,
      List<UUID> cancelledDriverTaskIds,
      List<InventoryDispositionResult> dispositions,
      long supersededLineCount,
      long supersededRentalUnitCount,
      boolean replay) {}
}
