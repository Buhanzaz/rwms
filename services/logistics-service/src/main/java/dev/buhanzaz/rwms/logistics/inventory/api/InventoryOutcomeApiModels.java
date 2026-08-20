package dev.buhanzaz.rwms.logistics.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Exact private transport contract for authoritative completed-inventory logistics outcomes. */
public final class InventoryOutcomeApiModels {
  private InventoryOutcomeApiModels() {}

  /** The only asset states that a completed inventory may publish into operational workflows. */
  public enum InventoryDesiredStatus {
    FREE,
    REPAIR,
    CAPITAL_REPAIR
  }

  /** One already-resolved finding; asset identity is canonical and never matched by display text. */
  public record InventoryAssetOutcome(
      @NotNull UUID findingId,
      @NotNull UUID assetId,
      @NotNull InventoryDesiredStatus desiredStatus) {}

  /** Immutable final-plan identity and its complete warehouse-scoped asset outcomes. */
  public record ApplyInventoryOutcomeRequest(
      @NotNull UUID warehouseId,
      @NotNull OffsetDateTime inventoryCompletedAt,
      @Positive long finalPlanVersion,
      @NotNull @Pattern(regexp = "[0-9a-f]{64}") String finalPlanSha256,
      @NotNull @Size(min = 1, max = 5000) List<@Valid InventoryAssetOutcome> outcomes) {}

  /** Frozen successful result; lists are sorted and contain unique logistics-owned identifiers. */
  public record ApplyInventoryOutcomeResponse(
      UUID inventoryId,
      long finalPlanVersion,
      List<UUID> supersededDocumentIds,
      List<UUID> supersededRentalOrderIds,
      List<UUID> cancelledDriverTaskIds,
      long supersededLineCount,
      long supersededRentalUnitCount,
      boolean replay) {}
}
