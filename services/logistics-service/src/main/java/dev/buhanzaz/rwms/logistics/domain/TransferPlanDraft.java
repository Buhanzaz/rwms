package dev.buhanzaz.rwms.logistics.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Typed draft input for a transfer plan. Catalog and resource identifiers remain opaque references
 * to their owning services.
 */
public record TransferPlanDraft(
    OffsetDateTime plannedDepartureAt,
    OffsetDateTime plannedArrivalAt,
    String logisticsComment,
    UUID tripDriverId,
    UUID tripVehicleId,
    ResourceIntent driverReposition,
    ResourceIntent vehicleReposition,
    List<CargoGroup> cabinGroups,
    List<LooseFurniture> looseFurniture) {

  /** Post-arrival operational assignment intent, independent from the resource executing the trip. */
  public record ResourceIntent(
      UUID resourceId, TransferResourceRepositionMode mode, OffsetDateTime until) {}

  /** One configurable requirement group and its optional concrete cabin allocation. */
  public record CargoGroup(
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      List<UUID> characteristicIds,
      Boolean linoleum,
      int quantity,
      List<Furniture> furniturePerCabin,
      List<Allocation> allocatedCabins) {}

  /** One furniture catalog requirement per physical cabin in a group. */
  public record Furniture(UUID furnitureCatalogItemId, long quantityPerCabin) {}

  /** One concrete cabin selected for a requirement group with its optimistic asset version. */
  public record Allocation(UUID assetId, long assetVersion) {}

  /** Furniture transported independently from any cabin composition requirement. */
  public record LooseFurniture(UUID furnitureCatalogItemId, long quantity) {}
}
