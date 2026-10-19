package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Logistics-projected daily driver, vehicle and route plan from which an actual shift is frozen.
 *
 * <p>The plan warehouse remains the driver's physical route origin and execution scope. An
 * immutable child operation snapshot carries any distinct warehouse served between positioning
 * legs. Effective cabin capacity remains nullable for legacy plans and fences every capacity-aware
 * route load when present.
 */
@Entity
@Table(
    name = "driver_shift_plan",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_driver_shift_plan_source_shift",
          columnNames = "source_shift_id"),
      @UniqueConstraint(
          name = "uk_driver_shift_plan_driver_date",
          columnNames = {"active_driver_key", "work_date"})
    },
    indexes =
        @Index(
            name = "idx_driver_shift_plan_warehouse_date",
            columnList = "warehouse_id,work_date"))
public class DriverShiftPlan extends AbstractVersionedEntity {
  @Column(name = "source_shift_id", nullable = false)
  private UUID sourceShiftId;

  @Column(name = "source_plan_id", nullable = false)
  private UUID sourcePlanId;

  @Column(name = "source_plan_version", nullable = false)
  private long sourcePlanVersion;

  @Column(name = "request_fingerprint", nullable = false, length = 64)
  private String requestFingerprint;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "driver_id", nullable = false)
  private UUID driverId;

  @Column(name = "active_driver_key")
  private UUID activeDriverKey;

  @Column(name = "driver_name", nullable = false, length = 256)
  private String driverName;

  @Column(name = "work_date", nullable = false)
  private LocalDate workDate;

  @Column(name = "vehicle_id", nullable = false)
  private UUID vehicleId;

  @Column(name = "vehicle_name", nullable = false, length = 256)
  private String vehicleName;

  @Column(name = "vehicle_registration_number", nullable = false, length = 64)
  private String vehicleRegistrationNumber;

  @Column(name = "vehicle_type", length = 64)
  private String vehicleType;

  @Column(name = "vehicle_manufacturer", length = 128)
  private String vehicleManufacturer;

  @Column(name = "vehicle_model", length = 128)
  private String vehicleModel;

  @Enumerated(EnumType.STRING)
  @Column(name = "configuration_type", nullable = false, length = 32)
  private VehicleConfigurationType configurationType;

  @Min(1)
  @Max(2)
  @Column(name = "cabin_capacity")
  private Integer cabinCapacity;

  @Column(name = "start_odometer")
  private Long startOdometer;

  @Column(name = "trailer_id")
  private UUID trailerId;

  @Column(name = "trailer_name", length = 256)
  private String trailerName;

  @Column(name = "trailer_registration_number", length = 64)
  private String trailerRegistrationNumber;

  @Column(name = "trip_count", nullable = false)
  private int tripCount;

  @Column(name = "route_distance_meters", nullable = false)
  private long routeDistanceMeters;

  @Column(name = "frozen_shift_id")
  private UUID frozenShiftId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "withdrawn_at")
  private OffsetDateTime withdrawnAt;

  @Column(name = "withdrawn_source_plan_version")
  private Long withdrawnSourcePlanVersion;

  /** Initializes a reviewed source identity before first persistence. */
  public void initialize(UUID sourceShiftId, UUID sourcePlanId, OffsetDateTime now) {
    this.sourceShiftId = sourceShiftId;
    this.sourcePlanId = sourcePlanId;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /** Replaces a not-yet-frozen plan snapshot with a strictly newer source version. */
  public void replace(
      long sourcePlanVersion,
      String fingerprint,
      UUID warehouseId,
      UUID driverId,
      String driverName,
      LocalDate workDate,
      UUID vehicleId,
      String vehicleName,
      String registrationNumber,
      String vehicleType,
      String manufacturer,
      String model,
      VehicleConfigurationType configurationType,
      Integer cabinCapacity,
      Long startOdometer,
      UUID trailerId,
      String trailerName,
      String trailerRegistrationNumber,
      int tripCount,
      long routeDistanceMeters,
      OffsetDateTime now) {
    if (frozenShiftId != null || withdrawnAt != null)
      throw new IllegalStateException("Driver shift plan is already frozen");
    this.sourcePlanVersion = sourcePlanVersion;
    this.requestFingerprint = fingerprint;
    this.warehouseId = warehouseId;
    this.driverId = driverId;
    this.activeDriverKey = driverId;
    this.driverName = driverName.trim();
    this.workDate = workDate;
    this.vehicleId = vehicleId;
    this.vehicleName = vehicleName.trim();
    this.vehicleRegistrationNumber = registrationNumber.trim();
    this.vehicleType = trim(vehicleType);
    this.vehicleManufacturer = trim(manufacturer);
    this.vehicleModel = trim(model);
    this.configurationType = configurationType;
    this.cabinCapacity = cabinCapacity;
    this.startOdometer = startOdometer;
    this.trailerId = trailerId;
    this.trailerName = trim(trailerName);
    this.trailerRegistrationNumber = trim(trailerRegistrationNumber);
    this.tripCount = tripCount;
    this.routeDistanceMeters = routeDistanceMeters;
    this.updatedAt = now;
  }

  /** Preserves source compatibility for plan snapshots created before cabin capacity. */
  public void replace(
      long sourcePlanVersion,
      String fingerprint,
      UUID warehouseId,
      UUID driverId,
      String driverName,
      LocalDate workDate,
      UUID vehicleId,
      String vehicleName,
      String registrationNumber,
      String vehicleType,
      String manufacturer,
      String model,
      VehicleConfigurationType configurationType,
      Long startOdometer,
      UUID trailerId,
      String trailerName,
      String trailerRegistrationNumber,
      int tripCount,
      long routeDistanceMeters,
      OffsetDateTime now) {
    replace(
        sourcePlanVersion,
        fingerprint,
        warehouseId,
        driverId,
        driverName,
        workDate,
        vehicleId,
        vehicleName,
        registrationNumber,
        vehicleType,
        manufacturer,
        model,
        configurationType,
        null,
        startOdometer,
        trailerId,
        trailerName,
        trailerRegistrationNumber,
        tripCount,
        routeDistanceMeters,
        now);
  }

  /** Permanently binds this source plan to the first actual shift created from it. */
  public void freeze(UUID shiftId, OffsetDateTime now) {
    if (withdrawnAt != null) throw new IllegalStateException("Driver shift plan is withdrawn");
    frozenShiftId = shiftId;
    updatedAt = now;
  }

  /** Tombstones an unfrozen shift snapshot removed by an agreed cross-date reschedule. */
  public void withdraw(long expectedPlanVersion, long replacementPlanVersion, OffsetDateTime now) {
    if (withdrawnAt != null) return;
    if (frozenShiftId != null
        || sourcePlanVersion != expectedPlanVersion
        || replacementPlanVersion <= expectedPlanVersion) {
      throw new IllegalStateException("Driver shift plan cannot be withdrawn");
    }
    activeDriverKey = null;
    withdrawnAt = now;
    withdrawnSourcePlanVersion = replacementPlanVersion;
    updatedAt = now;
  }

  private String trim(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public UUID getSourceShiftId() {
    return sourceShiftId;
  }

  public UUID getSourcePlanId() {
    return sourcePlanId;
  }

  public long getSourcePlanVersion() {
    return sourcePlanVersion;
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public UUID getDriverId() {
    return driverId;
  }

  public String getDriverName() {
    return driverName;
  }

  public LocalDate getWorkDate() {
    return workDate;
  }

  public UUID getVehicleId() {
    return vehicleId;
  }

  public String getVehicleName() {
    return vehicleName;
  }

  public String getVehicleRegistrationNumber() {
    return vehicleRegistrationNumber;
  }

  public String getVehicleType() {
    return vehicleType;
  }

  public String getVehicleManufacturer() {
    return vehicleManufacturer;
  }

  public String getVehicleModel() {
    return vehicleModel;
  }

  public VehicleConfigurationType getConfigurationType() {
    return configurationType;
  }

  public Integer getCabinCapacity() {
    return cabinCapacity;
  }

  public Long getStartOdometer() {
    return startOdometer;
  }

  public UUID getTrailerId() {
    return trailerId;
  }

  public String getTrailerName() {
    return trailerName;
  }

  public String getTrailerRegistrationNumber() {
    return trailerRegistrationNumber;
  }

  public int getTripCount() {
    return tripCount;
  }

  public long getRouteDistanceMeters() {
    return routeDistanceMeters;
  }

  public UUID getFrozenShiftId() {
    return frozenShiftId;
  }

  public OffsetDateTime getWithdrawnAt() {
    return withdrawnAt;
  }

  public Long getWithdrawnSourcePlanVersion() {
    return withdrawnSourcePlanVersion;
  }
}
