package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Task-board-owned daily driver shift aggregate and all authoritative transition audit facts. */
@Entity
@Table(
    name = "driver_shift",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_driver_shift_driver_date",
            columnNames = {"driver_id", "work_date"}),
    indexes =
        @Index(
            name = "idx_driver_shift_warehouse_date_status",
            columnList = "warehouse_id,work_date,status"))
public class DriverShift extends AbstractVersionedEntity {
  @Column(name = "plan_id", nullable = false)
  private UUID planId;

  @Column(name = "driver_id", nullable = false)
  private UUID driverId;

  @Column(name = "driver_name", nullable = false, length = 256)
  private String driverName;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "work_date", nullable = false)
  private LocalDate workDate;

  @Column(name = "time_zone", nullable = false, length = 64)
  private String timeZone;

  @Column(name = "vehicle_id", nullable = false)
  private UUID vehicleId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 40)
  private DriverShiftStatus status;

  @Column(name = "briefing_seen_at")
  private OffsetDateTime briefingSeenAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "medical_confirmation_type", length = 40)
  private MedicalConfirmationType medicalConfirmationType;

  @Column(name = "medical_completed_at")
  private OffsetDateTime medicalCompletedAt;

  @Column(name = "medical_client_completed_at")
  private OffsetDateTime medicalClientCompletedAt;

  @Column(name = "medical_external_check_id")
  private UUID medicalExternalCheckId;

  @Column(name = "medical_doctor_id")
  private UUID medicalDoctorId;

  @Column(name = "medical_provider", length = 256)
  private String medicalProvider;

  @Column(name = "medical_checked_at")
  private OffsetDateTime medicalCheckedAt;

  @Column(name = "vehicle_inspection_completed_at")
  private OffsetDateTime vehicleInspectionCompletedAt;

  @Column(name = "started_at")
  private OffsetDateTime startedAt;

  @Column(name = "closing_started_at")
  private OffsetDateTime closingStartedAt;

  @Column(name = "returned_to_warehouse_at")
  private OffsetDateTime returnedToWarehouseAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "return_confirmation_type", length = 24)
  private ReturnConfirmationType returnConfirmationType;

  @Enumerated(EnumType.STRING)
  @Column(name = "end_vehicle_condition", length = 32)
  private EndVehicleCondition endVehicleCondition;

  @Column(name = "end_odometer")
  private Long endOdometer;

  @Column(name = "fuel_level_percent")
  private Integer fuelLevelPercent;

  @Column(name = "closing_defect_id")
  private UUID closingDefectId;

  @Column(name = "closing_report_completed_at")
  private OffsetDateTime closingReportCompletedAt;

  @Column(name = "closed_at")
  private OffsetDateTime closedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** Freezes one registered plan into a new shift at the warehouse-local work date. */
  public void initialize(DriverShiftPlan plan, String timeZone, OffsetDateTime now) {
    planId = plan.getId();
    driverId = plan.getDriverId();
    driverName = plan.getDriverName();
    warehouseId = plan.getWarehouseId();
    workDate = plan.getWorkDate();
    this.timeZone = timeZone;
    vehicleId = plan.getVehicleId();
    status = DriverShiftStatus.DAILY_BRIEFING_REQUIRED;
    createdAt = now;
    updatedAt = now;
  }

  /** Applies one legal adjacent transition and its server timestamp. */
  public void markBriefingSeen(OffsetDateTime now) {
    require(DriverShiftStatus.DAILY_BRIEFING_REQUIRED);
    briefingSeenAt = now;
    move(DriverShiftStatus.MEDICAL_CHECK_REQUIRED, now);
  }

  /** Records self-confirmation while retaining the client clock only as secondary evidence. */
  public void confirmMedical(OffsetDateTime now, OffsetDateTime clientAt) {
    require(DriverShiftStatus.MEDICAL_CHECK_REQUIRED);
    medicalConfirmationType = MedicalConfirmationType.SELF_CONFIRMATION_TEST;
    medicalCompletedAt = now;
    medicalClientCompletedAt = clientAt;
    move(DriverShiftStatus.VEHICLE_INSPECTION_REQUIRED, now);
  }

  /** Records successful completion of the frozen pre-trip inspection. */
  public void completeInspection(OffsetDateTime now) {
    require(DriverShiftStatus.VEHICLE_INSPECTION_REQUIRED);
    vehicleInspectionCompletedAt = now;
    move(DriverShiftStatus.READY_TO_START, now);
  }

  /** Starts task execution only after all preparation gates pass. */
  public void start(OffsetDateTime now) {
    require(DriverShiftStatus.READY_TO_START);
    startedAt = now;
    move(DriverShiftStatus.SHIFT_ACTIVE, now);
  }

  /** Records task-board's proof that at least one counted task exists and every one is DONE. */
  public void markTasksComplete(OffsetDateTime now) {
    require(DriverShiftStatus.SHIFT_ACTIVE);
    move(DriverShiftStatus.SHIFT_CLOSING, now);
  }

  /** Opens the explicit closing sequence after task-board proves every counted task is DONE. */
  public void startClosing(OffsetDateTime now) {
    if (status != DriverShiftStatus.SHIFT_ACTIVE && status != DriverShiftStatus.SHIFT_CLOSING)
      throw new IllegalStateException("Shift cannot start closing");
    if (closingStartedAt == null) closingStartedAt = now;
    move(DriverShiftStatus.RETURN_TO_WAREHOUSE_REQUIRED, now);
  }

  /** Records a manual or geofence return confirmation. */
  public void confirmReturn(ReturnConfirmationType type, OffsetDateTime now) {
    require(DriverShiftStatus.RETURN_TO_WAREHOUSE_REQUIRED);
    returnedToWarehouseAt = now;
    returnConfirmationType = type;
    move(DriverShiftStatus.END_VEHICLE_CHECK_REQUIRED, now);
  }

  /** Stores the validated vehicle/odometer/fuel closing report. */
  public void submitClosing(
      EndVehicleCondition condition, long odometer, int fuel, UUID defectId, OffsetDateTime now) {
    require(DriverShiftStatus.END_VEHICLE_CHECK_REQUIRED);
    endVehicleCondition = condition;
    endOdometer = odometer;
    fuelLevelPercent = fuel;
    closingDefectId = defectId;
    closingReportCompletedAt = now;
    move(DriverShiftStatus.SHIFT_READY_TO_CLOSE, now);
  }

  /** Closes the shift irreversibly using server time. */
  public void close(OffsetDateTime now) {
    require(DriverShiftStatus.SHIFT_READY_TO_CLOSE);
    closedAt = now;
    move(DriverShiftStatus.SHIFT_CLOSED, now);
  }

  /**
   * Marks one accepted child-aggregate mutation so the root's optimistic version advances.
   *
   * <p>The monotonic fallback handles multiple server-authoritative operations that receive the
   * same clock instant; replay and content-identical no-op paths must not call this method.
   */
  public void touch(OffsetDateTime now) {
    updatedAt = updatedAt == null || now.isAfter(updatedAt) ? now : updatedAt.plusNanos(1);
  }

  private void require(DriverShiftStatus expected) {
    if (status != expected)
      throw new IllegalStateException("Shift state is " + status + ", expected " + expected);
  }

  private void move(DriverShiftStatus next, OffsetDateTime now) {
    status = next;
    updatedAt = now;
  }

  public UUID getPlanId() {
    return planId;
  }

  public UUID getDriverId() {
    return driverId;
  }

  public String getDriverName() {
    return driverName;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public LocalDate getWorkDate() {
    return workDate;
  }

  public String getTimeZone() {
    return timeZone;
  }

  public UUID getVehicleId() {
    return vehicleId;
  }

  public DriverShiftStatus getStatus() {
    return status;
  }

  public OffsetDateTime getBriefingSeenAt() {
    return briefingSeenAt;
  }

  public MedicalConfirmationType getMedicalConfirmationType() {
    return medicalConfirmationType;
  }

  public OffsetDateTime getMedicalCompletedAt() {
    return medicalCompletedAt;
  }

  public UUID getMedicalExternalCheckId() {
    return medicalExternalCheckId;
  }

  public UUID getMedicalDoctorId() {
    return medicalDoctorId;
  }

  public String getMedicalProvider() {
    return medicalProvider;
  }

  public OffsetDateTime getMedicalCheckedAt() {
    return medicalCheckedAt;
  }

  public OffsetDateTime getVehicleInspectionCompletedAt() {
    return vehicleInspectionCompletedAt;
  }

  public OffsetDateTime getStartedAt() {
    return startedAt;
  }

  public OffsetDateTime getClosingStartedAt() {
    return closingStartedAt;
  }

  public OffsetDateTime getReturnedToWarehouseAt() {
    return returnedToWarehouseAt;
  }

  public ReturnConfirmationType getReturnConfirmationType() {
    return returnConfirmationType;
  }

  public EndVehicleCondition getEndVehicleCondition() {
    return endVehicleCondition;
  }

  public Long getEndOdometer() {
    return endOdometer;
  }

  public Integer getFuelLevelPercent() {
    return fuelLevelPercent;
  }

  public UUID getClosingDefectId() {
    return closingDefectId;
  }

  public OffsetDateTime getClosingReportCompletedAt() {
    return closingReportCompletedAt;
  }

  public OffsetDateTime getClosedAt() {
    return closedAt;
  }
}
