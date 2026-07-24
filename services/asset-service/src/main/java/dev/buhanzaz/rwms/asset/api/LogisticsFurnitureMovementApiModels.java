package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Private, logistics-owned request and response types for cabin furniture planning. */
public final class LogisticsFurnitureMovementApiModels {
  private LogisticsFurnitureMovementApiModels() {}

  /** The desired complete furniture composition of one cabin. An empty list clears furniture. */
  public record CabinFurnitureMovementPlanRequest(
      @NotNull UUID warehouseId,
      @NotNull
          @Size(max = 100)
          List<@NotNull @Valid CabinFurnitureRequirement> requirements) {}

  public record CabinFurnitureRequirement(@NotNull UUID equipmentId, @NotNull @Min(1) Long quantity) {}

  public record CabinFurnitureMovementPlanLine(
      UUID equipmentId,
      String equipmentCode,
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

  public record CabinFurnitureMovementPlan(
      UUID rentalItemId, String unitNumber, List<CabinFurnitureMovementPlanLine> lines) {}
}
