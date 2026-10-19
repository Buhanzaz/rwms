package dev.buhanzaz.rwms.logistics.driver.settings.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * HTTP transport models for warehouse-scoped shipment task grouping settings.
 */
public final class ShipmentTaskSettingsApiModels {
  private ShipmentTaskSettingsApiModels() {}

  /** Read model for one warehouse's effective cabin-count cap. */
  public record ShipmentTaskSettingsResponse(
      UUID warehouseId,
      long version,
      int maxCabinsPerShipmentTask,
      UUID updatedBy,
      OffsetDateTime updatedAt) {}

  /** Version-fenced mutation of the maximum number of cabins in one new shipment task. */
  public record UpdateShipmentTaskSettingsRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Min(1) @Max(100) Integer maxCabinsPerShipmentTask) {}
}
