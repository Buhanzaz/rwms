package dev.buhanzaz.rwms.logistics.planning.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
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

  /** Exceptional polygon semantics accepted by customer delivery planning. */
  public enum PlanningCapacityRestrictionKind {
    FORBIDDEN,
    NO_TRAILER
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

  /** Strict GeoJSON MultiPolygon shared by tariff and route-restriction facts. */
  public record PlanningGeoJsonMultiPolygon(
      @NotBlank String type,
      @NotNull List<List<List<List<Double>>>> coordinates) {
    public PlanningGeoJsonMultiPolygon {
      if (!"MultiPolygon".equals(type)) {
        throw new IllegalArgumentException("Planning zone geometry must be a MultiPolygon");
      }
      validateCoordinates(coordinates);
    }

    private static void validateCoordinates(List<List<List<List<Double>>>> polygons) {
      if (polygons == null || polygons.isEmpty()) {
        throw new IllegalArgumentException("Planning zone geometry is empty");
      }
      for (List<List<List<Double>>> polygon : polygons) {
        if (polygon == null || polygon.isEmpty()) {
          throw new IllegalArgumentException("Planning zone polygon is empty");
        }
        for (List<List<Double>> ring : polygon) {
          if (ring == null || ring.size() < 4) {
            throw new IllegalArgumentException("Planning zone ring is invalid");
          }
          for (List<Double> position : ring) {
            if (position == null || position.size() != 2) {
              throw new IllegalArgumentException("Planning zone position is invalid");
            }
            double longitude = position.get(0);
            double latitude = position.get(1);
            if (!Double.isFinite(longitude)
                || !Double.isFinite(latitude)
                || longitude < -180
                || longitude > 180
                || latitude < -90
                || latitude > 90) {
              throw new IllegalArgumentException("Planning zone coordinate is invalid");
            }
          }
          if (!ring.get(0).equals(ring.get(ring.size() - 1))) {
            throw new IllegalArgumentException("Planning zone ring must be closed");
          }
        }
      }
    }
  }

  /** One versioned tariff polygon; it is deliberately absent from route-feasibility inputs. */
  public record PlanningCapacityPriceZoneRequest(
      @NotNull UUID sourceZoneId,
      @Min(0) long sourceZoneVersion,
      @Min(0) long deliveryPriceRubles,
      @Min(0) long pickupPriceRubles,
      @NotNull @Valid PlanningGeoJsonMultiPolygon geometry) {}

  /** One versioned route-restriction polygon copied from the warehouse planner. */
  public record PlanningCapacityRestrictionZoneRequest(
      @NotNull UUID sourceZoneId,
      @Min(0) long sourceZoneVersion,
      @NotNull PlanningCapacityRestrictionKind kind,
      @NotNull @Valid PlanningGeoJsonMultiPolygon geometry) {}

  /** Complete replacement of one warehouse's active, simulator-only capacity projection. */
  public record ReplacePlanningCapacitySnapshotRequest(
      @Min(1) long sourceGeneration,
      @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sourceRevision,
      @NotNull @Size(max = 1_000) List<@NotNull @Valid PlanningCapacityJobRequest> jobs,
      @NotNull @Size(max = 2_000) List<@NotNull @Valid PlanningCapacityShiftRequest> shifts,
      @NotNull @Size(max = 500) List<@NotNull @Valid PlanningCapacityPriceZoneRequest> priceZones,
      @Min(0) Long isochronePrice60Minutes,
      @Min(0) Long isochronePrice120Minutes,
      @Min(0) Long isochronePrice180Minutes,
      @Min(0) Long isochronePrice240Minutes,
      @Size(max = 500)
          List<@NotNull @Valid PlanningCapacityRestrictionZoneRequest> restrictionZones) {
    public ReplacePlanningCapacitySnapshotRequest {
      isochronePrice60Minutes =
          isochronePrice60Minutes == null ? 10_000L : isochronePrice60Minutes;
      isochronePrice120Minutes =
          isochronePrice120Minutes == null ? 15_000L : isochronePrice120Minutes;
      isochronePrice180Minutes =
          isochronePrice180Minutes == null ? 20_000L : isochronePrice180Minutes;
      isochronePrice240Minutes =
          isochronePrice240Minutes == null ? 25_000L : isochronePrice240Minutes;
      if (isochronePrice60Minutes < 0
          || isochronePrice120Minutes < 0
          || isochronePrice180Minutes < 0
          || isochronePrice240Minutes < 0) {
        throw new IllegalArgumentException("Planning isochrone prices must be non-negative");
      }
      restrictionZones = restrictionZones == null ? List.of() : List.copyOf(restrictionZones);
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
      if (priceZones != null
          && priceZones.stream()
                  .map(PlanningCapacityPriceZoneRequest::sourceZoneId)
                  .distinct()
                  .count()
              != priceZones.size()) {
        throw new IllegalArgumentException("Planning capacity source zone IDs must be unique");
      }
      if (restrictionZones.stream()
              .map(PlanningCapacityRestrictionZoneRequest::sourceZoneId)
              .distinct()
              .count()
          != restrictionZones.size()) {
        throw new IllegalArgumentException(
            "Planning capacity restriction source zone IDs must be unique");
      }
      if (priceZones != null) {
        java.util.Set<UUID> priceZoneIds =
            priceZones.stream()
                .map(PlanningCapacityPriceZoneRequest::sourceZoneId)
                .collect(java.util.stream.Collectors.toSet());
        if (restrictionZones.stream()
            .map(PlanningCapacityRestrictionZoneRequest::sourceZoneId)
            .anyMatch(priceZoneIds::contains)) {
          throw new IllegalArgumentException(
              "Planning capacity source zone IDs must be unique across policy scopes");
        }
      }
    }

    /** Preserves requests produced before isochrone tariffs and restriction polygons were added. */
    public ReplacePlanningCapacitySnapshotRequest(
        long sourceGeneration,
        String sourceRevision,
        List<PlanningCapacityJobRequest> jobs,
        List<PlanningCapacityShiftRequest> shifts,
        List<PlanningCapacityPriceZoneRequest> priceZones) {
      this(
          sourceGeneration,
          sourceRevision,
          jobs,
          shifts,
          priceZones,
          null,
          null,
          null,
          null,
          List.of());
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
      int priceZoneCount,
      int restrictionZoneCount,
      boolean replayed,
      OffsetDateTime updatedAt) {
    /** Preserves source compatibility for callers that predate restriction-zone result counts. */
    public PlanningCapacitySnapshotResponse(
        UUID warehouseId,
        long sourceGeneration,
        long version,
        String sourceRevision,
        int jobCount,
        int shiftCount,
        int priceZoneCount,
        boolean replayed,
        OffsetDateTime updatedAt) {
      this(
          warehouseId,
          sourceGeneration,
          version,
          sourceRevision,
          jobCount,
          shiftCount,
          priceZoneCount,
          0,
          replayed,
          updatedAt);
    }
  }

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
        UUID driverWorkerId,
        String driverName,
        List<UUID> unitIds) {
      this(
          orderId,
          expectedOrderVersion,
          scheduledDate,
          PlanningDriverAudienceMode.ASSIGNED_DRIVER,
          driverWorkerId,
          driverName,
          unitIds);
    }
  }

  /** Idempotent plan application command; plan identity participates in stable command keys. */
  public record ApplyPlanningAssignmentsRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID planId,
      @NotNull @Min(1) Long planVersion,
      @NotNull @Size(max = 500) List<@NotNull @Valid PlanningAssignmentRequest> assignments) {}

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
