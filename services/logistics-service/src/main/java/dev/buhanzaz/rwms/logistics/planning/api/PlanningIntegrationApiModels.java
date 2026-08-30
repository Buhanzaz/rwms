package dev.buhanzaz.rwms.logistics.planning.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Transport models for the private, service-authenticated route-planning boundary. */
public final class PlanningIntegrationApiModels {
  private PlanningIntegrationApiModels() {}

  /** Explicit planner intent for a concrete driver or a future warehouse-driver pool task. */
  public enum PlanningDriverAudienceMode {
    ASSIGNED_DRIVER,
    WAREHOUSE_DRIVERS
  }

  /** Task-board-owned employment category relevant to route-resource selection. */
  public enum PlanningDriverEmploymentType {
    STAFF,
    CONTRACTOR
  }

  /** Provenance of guaranteed or incoming operational availability at the requested warehouse. */
  public enum PlanningDriverAvailabilityKind {
    HOME,
    ACTIVE_ASSIGNMENT,
    INCOMING
  }

  /** Distinguishes primary outbound deliveries from secondary return-leg pickups. */
  public enum PlanningCapacityTaskType {
    DELIVERY,
    PICKUP
  }

  /**
   * One client-approved delivery day exported to the route planner. Fixed CustomerApp bookings
   * carry hard bounds; a DURING_DAY booking and manager-entered date-only choices remain soft and
   * omit bounds. Customer bookings retain their informational depot travel-time ring in either
   * form.
   */
  public record PlanningDateOption(
      LocalDate date,
      int priority,
      boolean isHard,
      LocalTime windowStart,
      LocalTime windowEnd,
      Integer travelZoneHours) {
    /** Preserves the existing date-only constructor for non-customer planning sources. */
    public PlanningDateOption(LocalDate date, int priority, boolean isHard) {
      this(date, priority, isHard, null, null, null);
    }
  }

  /** One unscheduled rental-order remainder exported without contact or furniture details. */
  public record PlanningRequestResponse(
      UUID orderId,
      long orderVersion,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sourceRevision,
      String orderNumber,
      String clientName,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      int quantity,
      List<UUID> unitIds,
      List<PlanningDateOption> dateOptions,
      Boolean trailerAccessAllowed,
      OffsetDateTime createdAt) {}

  /** Warehouse-scoped deterministic planning feed and its authoritative timezone. */
  public record PlanningRequestFeedResponse(
      UUID warehouseId,
      String timeZone,
      OffsetDateTime generatedAt,
      List<PlanningRequestResponse> requests) {}

  /** One anonymous simulator transport task that participates in an exact local-day schedule. */
  public record PlanningCapacityJobRequest(
      @NotNull UUID sourceJobId,
      @NotNull PlanningCapacityTaskType taskType,
      @NotNull LocalDate deliveryDate,
      @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") @Digits(integer = 2, fraction = 6)
          BigDecimal latitude,
      @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") @Digits(integer = 3, fraction = 6)
          BigDecimal longitude,
      @Min(1) int cabinCount,
      @NotNull LocalTime windowStart,
      @NotNull LocalTime windowEnd,
      @Min(1) int serviceMinutes,
      boolean trailerAccessAllowed,
      @Min(0) int priority,
      boolean mandatory) {
    public PlanningCapacityJobRequest {
      if (windowStart != null && windowEnd != null && !windowStart.isBefore(windowEnd)) {
        throw new IllegalArgumentException("Planning capacity window is invalid");
      }
    }

  }

  /** One anonymous simulator driver shift available to serve an exact warehouse-local day. */
  public record PlanningCapacityShiftRequest(
      @NotNull UUID sourceShiftId,
      @NotNull LocalDate deliveryDate,
      @NotNull LocalTime shiftStart,
      @NotNull LocalTime shiftEnd,
      @Min(0) @Max(720) int breakMinutes,
      @Min(1) @Max(2) int cabinCapacity) {
    public PlanningCapacityShiftRequest {
      if (shiftStart != null && shiftEnd != null && !shiftStart.isBefore(shiftEnd)) {
        throw new IllegalArgumentException("Planning capacity shift is invalid");
      }
      if (shiftStart != null
          && shiftEnd != null
          && breakMinutes >= java.time.Duration.between(shiftStart, shiftEnd).toMinutes()) {
        throw new IllegalArgumentException("Planning capacity shift break consumes the shift");
      }
    }
  }

  /** One configured hourly road-time tariff; the greatest tier is the delivery boundary. */
  public record PlanningCapacityIsochroneTariff(
      @Min(60) @Max(720) int travelMinutes, @Min(0) long priceRubles) {
    public PlanningCapacityIsochroneTariff {
      if (travelMinutes < 60 || travelMinutes > 720 || travelMinutes % 60 != 0) {
        throw new IllegalArgumentException(
            "Planning isochrone travel minutes must be a whole hour from 60 to 720");
      }
      if (priceRubles < 0) {
        throw new IllegalArgumentException("Planning isochrone price must be non-negative");
      }
    }
  }

  /** Complete replacement of one warehouse's active, simulator-only capacity projection. */
  public record ReplacePlanningCapacitySnapshotRequest(
      @Min(1) long sourceGeneration,
      @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sourceRevision,
      @NotNull @Size(max = 1_000) List<@NotNull @Valid PlanningCapacityJobRequest> jobs,
      @NotNull @Size(max = 2_000) List<@NotNull @Valid PlanningCapacityShiftRequest> shifts,
      @NotNull @Size(min = 1, max = 12)
          List<@NotNull @Valid PlanningCapacityIsochroneTariff> isochroneTariffs) {
    public ReplacePlanningCapacitySnapshotRequest {
      if (jobs != null
          && jobs.stream().map(PlanningCapacityJobRequest::sourceJobId).distinct().count()
              != jobs.size()) {
        throw new IllegalArgumentException("Planning capacity source job IDs must be unique");
      }
      if (shifts != null
          && shifts.stream().map(PlanningCapacityShiftRequest::sourceShiftId).distinct().count()
              != shifts.size()) {
        throw new IllegalArgumentException("Planning capacity source shift IDs must be unique");
      }
      if (isochroneTariffs == null) {
        throw new IllegalArgumentException("Planning requires isochrone tariffs");
      }
      if (isochroneTariffs.isEmpty() || isochroneTariffs.size() > 12) {
        throw new IllegalArgumentException("Planning requires between one and twelve tariffs");
      }
      for (int index = 0; index < isochroneTariffs.size(); index++) {
        PlanningCapacityIsochroneTariff tariff = isochroneTariffs.get(index);
        if (tariff == null || tariff.travelMinutes() != (index + 1) * 60) {
          throw new IllegalArgumentException(
              "Planning isochrone tariffs must be contiguous hourly tiers starting at 60 minutes");
        }
      }
      isochroneTariffs = List.copyOf(isochroneTariffs);
    }
  }

  /** Applied active-capacity revision returned to the standalone simulator. */
  public record PlanningCapacitySnapshotResponse(
      UUID warehouseId,
      long sourceGeneration,
      long version,
      String sourceRevision,
      int jobCount,
      int shiftCount,
      int isochroneTariffCount,
      boolean replayed,
      OffsetDateTime updatedAt) {}

  /** Active RWMS warehouse identity available to the standalone planner. */
  public record PlanningWarehouseResource(
      UUID warehouseId,
      long warehouseVersion,
      String name,
      String city,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      String timeZone,
      boolean representative,
      boolean routingReady) {
    /** Preserves source compatibility for pre-coordinate display-only callers. */
    public PlanningWarehouseResource(
        UUID warehouseId, String name, String city, String address, String timeZone) {
      this(
          warehouseId,
          0,
          name,
          city,
          address,
          null,
          null,
          timeZone,
          false,
          false);
    }
  }

  /** Calendar-filtered directed support edge available to route candidate generation. */
  public record PlanningWarehouseSupportLinkResource(
      UUID supportLinkId,
      long supportLinkVersion,
      PlanningWarehouseResource supportWarehouse,
      PlanningWarehouseResource servedWarehouse,
      int priority,
      boolean allowDrivers,
      boolean allowVehicles,
      boolean allowInventory,
      boolean allowDirectFulfillment,
      boolean allowInterwarehouseTransfer,
      boolean allowContractorFallback,
      Set<DayOfWeek> allowedWeekdays,
      Set<LocalDate> allowedDates,
      Set<LocalDate> excludedDates,
      LocalTime serviceStart,
      LocalTime serviceEnd) {}

  /** Active qualified task-board driver availability exposed to the standalone planner. */
  public record PlanningDriverResource(
      UUID workerId,
      String displayName,
      PlanningDriverEmploymentType employmentType,
      String phone,
      UUID operationalWarehouseId,
      OffsetDateTime availableFrom,
      OffsetDateTime availableUntil,
      PlanningDriverAvailabilityKind availabilityKind) {
    /** Preserves source compatibility for tests and adapters that only display a staff identity. */
    public PlanningDriverResource(UUID workerId, String displayName) {
      this(
          workerId,
          displayName,
          PlanningDriverEmploymentType.STAFF,
          null,
          null,
          null,
          null,
          PlanningDriverAvailabilityKind.HOME);
    }
  }

  /** One planner-selected shipment bound to an exact order version and concrete cabins. */
  public record PlanningAssignmentRequest(
      @NotNull UUID orderId,
      UUID serviceWarehouseId,
      @NotNull @Min(0) Long expectedOrderVersion,
      @NotNull LocalDate scheduledDate,
      PlanningDriverAudienceMode driverAudienceMode,
      UUID driverWorkerId,
      @NotBlank @Size(max = 512) String driverName,
      @NotNull @Size(min = 1, max = 2) List<@NotNull UUID> unitIds) {
    public PlanningAssignmentRequest {
      if (driverAudienceMode == null) {
        driverAudienceMode = PlanningDriverAudienceMode.ASSIGNED_DRIVER;
      }
    }

    public PlanningAssignmentRequest(
        UUID orderId,
        Long expectedOrderVersion,
        LocalDate scheduledDate,
        PlanningDriverAudienceMode driverAudienceMode,
        UUID driverWorkerId,
        String driverName,
        List<UUID> unitIds) {
      this(
          orderId,
          null,
          expectedOrderVersion,
          scheduledDate,
          driverAudienceMode,
          driverWorkerId,
          driverName,
          unitIds);
    }

    public PlanningAssignmentRequest(
        UUID orderId,
        Long expectedOrderVersion,
        LocalDate scheduledDate,
        UUID driverWorkerId,
        String driverName,
        List<UUID> unitIds) {
      this(
          orderId,
          null,
          expectedOrderVersion,
          scheduledDate,
          PlanningDriverAudienceMode.ASSIGNED_DRIVER,
          driverWorkerId,
          driverName,
          unitIds);
    }

    /** Constructor for an explicit regional service warehouse within a root planning group. */
    public PlanningAssignmentRequest(
        UUID orderId,
        UUID serviceWarehouseId,
        Long expectedOrderVersion,
        LocalDate scheduledDate,
        UUID driverWorkerId,
        String driverName,
        List<UUID> unitIds) {
      this(
          orderId,
          serviceWarehouseId,
          expectedOrderVersion,
          scheduledDate,
          PlanningDriverAudienceMode.ASSIGNED_DRIVER,
          driverWorkerId,
          driverName,
          unitIds);
    }
  }

  /** Vehicle configuration that selects the server-owned inspection template in Driver Up. */
  public enum PlanningDriverShiftVehicleConfiguration {
    TRUCK,
    TRUCK_WITH_TRAILER,
    TRUCK_WITH_CRANE
  }

  /** Immutable vehicle identity transported from the standalone planner without fleet ownership. */
  public record PlanningDriverShiftVehicleRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 64) String registrationNumber,
      @Size(max = 64) String vehicleType,
      @Size(max = 100) String manufacturer,
      @Size(max = 100) String model,
      @NotNull PlanningDriverShiftVehicleConfiguration configurationType,
      @Min(0) Long startOdometer) {}

  /** Optional immutable trailer identity transported with one assigned vehicle snapshot. */
  public record PlanningDriverShiftTrailerRequest(
      @NotNull UUID id,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 64) String registrationNumber) {}

  /**
   * One exact planner shift, assigned vehicle, and unrounded int64 route-meter summary for a Driver
   * Up workday.
   */
  public record PlanningDriverShiftPlanRequest(
      @NotNull UUID sourceShiftId,
      @NotNull UUID sourcePlanId,
      @NotNull @Min(1) Long sourcePlanVersion,
      @NotNull UUID warehouseId,
      @NotNull UUID driverId,
      @NotBlank @Size(max = 256) String driverName,
      @NotNull LocalDate workDate,
      @NotNull @Valid PlanningDriverShiftVehicleRequest vehicle,
      @Valid PlanningDriverShiftTrailerRequest trailer,
      @Min(0) int tripCount,
      @Min(0) long routeDistanceMeters) {}

  /** Idempotent plan application command; plan identity participates in stable command keys. */
  public record ApplyPlanningAssignmentsRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID planId,
      @NotNull @Min(1) Long planVersion,
      @NotNull @Size(max = 500) List<@NotNull @Valid PlanningAssignmentRequest> assignments,
      @NotNull @Size(max = 500)
          List<@NotNull @Valid PlanningDriverShiftPlanRequest> driverShiftPlans) {
    public ApplyPlanningAssignmentsRequest {
      if (driverShiftPlans == null) driverShiftPlans = List.of();
      driverShiftPlans = List.copyOf(driverShiftPlans);
    }

    /** Preserves source and JSON compatibility for callers created before shift publication. */
    public ApplyPlanningAssignmentsRequest(
        UUID warehouseId,
        UUID planId,
        Long planVersion,
        List<PlanningAssignmentRequest> assignments) {
      this(warehouseId, planId, planVersion, assignments, List.of());
    }
  }

  /** Successfully created or replayed logistics document for one planned order part. */
  public record AppliedPlanningAssignment(UUID orderId, UUID documentId, boolean replayed) {}

  /** Current RWMS assignment and driver-task projection for one planner-created shipment part. */
  public record PlanningAssignmentStatus(
      UUID orderId,
      UUID documentId,
      LocalDate scheduledDate,
      List<UUID> unitIds,
      PlanningDriverAudienceMode driverAudienceMode,
      UUID driverWorkerId,
      String driverName,
      String taskState) {}

  /** Date-bounded assignment status snapshot consumed by the standalone planning UI. */
  public record PlanningAssignmentStatusResponse(
      UUID warehouseId, LocalDate date, List<PlanningAssignmentStatus> assignments) {}

  /** Rejected assignment with a stable machine code and safe operator-facing message. */
  public record RejectedPlanningAssignment(UUID orderId, String code, String message) {}

  /** Complete non-silent result of applying a planner batch. */
  public record ApplyPlanningAssignmentsResponse(
      List<AppliedPlanningAssignment> applied, List<RejectedPlanningAssignment> rejected) {}
}
