package dev.buhanzaz.rwms.logistics.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Immutable service-local read projection of one persisted transfer plan. */
public record TransferPlanSnapshot(
    UUID planId,
    long planVersion,
    TransferPlanState state,
    TransferReservationReadiness reservationReadiness,
    TransferPlanWorkflowState workflowState,
    String workflowFailureCode,
    OffsetDateTime plannedDepartureAt,
    OffsetDateTime plannedArrivalAt,
    String logisticsComment,
    UUID tripDriverId,
    UUID tripVehicleId,
    ResourceIntent driverReposition,
    ResourceIntent vehicleReposition,
    Assignment tripDriverAssignment,
    Assignment repositionedDriverAssignment,
    List<CargoGroup> cabinGroups,
    List<LooseFurniture> looseFurniture,
    int totalCabinCount,
    int auditLineCount) {

  /** Read projection of a requested operational resource assignment. */
  public record ResourceIntent(
      UUID resourceId, TransferResourceRepositionMode mode, OffsetDateTime until) {}

  /** Opaque task-board assignment checkpoint retained for replay-safe lifecycle transitions. */
  public record Assignment(UUID assignmentId, Long version, String status) {}

  /** Read projection of one ordered cabin requirement group. */
  public record CargoGroup(
      UUID groupId,
      int position,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      List<UUID> characteristicIds,
      Boolean linoleum,
      int quantity,
      List<Furniture> furniturePerCabin,
      List<Allocation> allocatedCabins) {}

  /** Per-cabin and group-total quantity for one furniture catalog item. */
  public record Furniture(
      UUID furnitureCatalogItemId, long quantityPerCabin, long totalQuantity) {}

  /** Concrete cabin allocation retained with the asset version selected by the operator. */
  public record Allocation(UUID assetId, long assetVersion) {}

  /** Independent furniture cargo that is not part of a cabin composition. */
  public record LooseFurniture(
      UUID lineId,
      int position,
      UUID furnitureCatalogItemId,
      long quantity,
      UUID sourceBalanceId,
      Long expectedSourceBalanceVersion,
      UUID reservationId,
      Long reservationVersion,
      TransferLooseFurnitureState state) {}
}
