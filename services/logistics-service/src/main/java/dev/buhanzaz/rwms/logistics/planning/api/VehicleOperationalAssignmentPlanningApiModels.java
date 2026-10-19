package dev.buhanzaz.rwms.logistics.planning.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Transport models for logistics-owned live vehicle chain facts exposed to route planning. */
public final class VehicleOperationalAssignmentPlanningApiModels {
  private VehicleOperationalAssignmentPlanningApiModels() {}

  /** Planner-facing reservation and post-arrival placement semantics. */
  public enum PlanningVehicleOperationalAssignmentMode {
    TRIP_ONLY,
    TEMPORARY,
    PERMANENT
  }

  /** Planner-facing assignment lifecycle values; the live endpoint returns only non-terminal rows. */
  public enum PlanningVehicleOperationalAssignmentStatus {
    PLANNED,
    IN_TRANSIT,
    ACTIVE,
    COMPLETED,
    CANCELLED
  }

  /**
   * Raw logistics-owned assignment facts. The planner overlays these live chain rows on its
   * vehicle catalog without treating a stale catalog home as current placement.
   */
  public record PlanningVehicleOperationalAssignment(
      UUID assignmentId,
      long version,
      UUID transferId,
      UUID vehicleId,
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      PlanningVehicleOperationalAssignmentMode mode,
      PlanningVehicleOperationalAssignmentStatus status,
      OffsetDateTime travelStartsAt,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveUntil,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}
}
