package dev.buhanzaz.rwms.taskboard.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.buhanzaz.rwms.taskboard.domain.DefectSeverity;
import dev.buhanzaz.rwms.taskboard.domain.DefectStatus;
import dev.buhanzaz.rwms.taskboard.domain.DriverShiftRouteOperationKind;
import dev.buhanzaz.rwms.taskboard.domain.DriverShiftStatus;
import dev.buhanzaz.rwms.taskboard.domain.EndVehicleCondition;
import dev.buhanzaz.rwms.taskboard.domain.InspectionItemState;
import dev.buhanzaz.rwms.taskboard.domain.MedicalConfirmationType;
import dev.buhanzaz.rwms.taskboard.domain.ReturnConfirmationType;
import dev.buhanzaz.rwms.taskboard.domain.ShiftPhotoRole;
import dev.buhanzaz.rwms.taskboard.domain.ShiftPhotoState;
import dev.buhanzaz.rwms.taskboard.domain.VehicleConfigurationType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Canonical request and response records for the Driver Up daily-shift boundary. */
public final class DriverShiftApiModels {
  private DriverShiftApiModels() {}

  /** Server-selected mutually exclusive action that Driver Up must render next. */
  public enum NextRequiredAction {
    SHIFT_NOT_AVAILABLE,
    SHOW_DAILY_BRIEFING,
    COMPLETE_MEDICAL_CHECK,
    COMPLETE_VEHICLE_INSPECTION,
    START_SHIFT,
    SHOW_TASKS,
    START_SHIFT_CLOSING,
    CONFIRM_WAREHOUSE_RETURN,
    COMPLETE_END_OF_SHIFT_REPORT,
    CLOSE_SHIFT,
    SHIFT_CLOSED
  }

  /** Result of applying an internal driver-shift plan projection. */
  public enum PlanApplyResult {
    CREATED,
    REPLAYED,
    REPLACED
  }

  /** One-shot startup aggregate that drives all Driver Up shift navigation. */
  public record TodayShiftResponse(
      boolean enabled,
      @NotNull OffsetDateTime serverTime,
      @Min(1) long suspiciousOdometerJumpKm,
      @JsonInclude(JsonInclude.Include.ALWAYS) OffsetDateTime nextAvailableAt,
      @NotNull NextRequiredAction nextRequiredAction,
      @JsonInclude(JsonInclude.Include.ALWAYS) DriverShiftView shift,
      @JsonInclude(JsonInclude.Include.ALWAYS) WarehouseView warehouse,
      @JsonInclude(JsonInclude.Include.ALWAYS) DailyBriefingView briefing,
      @JsonInclude(JsonInclude.Include.ALWAYS) VehicleView vehicle,
      @JsonInclude(JsonInclude.Include.ALWAYS) InspectionView inspection,
      @JsonInclude(JsonInclude.Include.ALWAYS) TaskSummaryView taskSummary,
      @JsonInclude(JsonInclude.Include.ALWAYS) ClosingReportView closingReport,
      @NotNull List<RouteOperationView> operations,
      @NotNull List<ShiftPhotoView> photos) {}

  /** One immutable exact-time operation in the plan-version route execution order. */
  public record RouteOperationView(
      @Min(1) int sequence,
      @NotNull DriverShiftRouteOperationKind kind,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID warehouseId,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID sourceTaskId,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID sourceTransferId,
      @NotBlank String locationLabel,
      @NotNull OffsetDateTime plannedArrival,
      @NotNull OffsetDateTime plannedDeparture,
      @Min(0) int loadBefore,
      @Min(0) int loadAfter) {
    /** Preserves source compatibility for operations created before transfer cargo identity. */
    public RouteOperationView(
        int sequence,
        DriverShiftRouteOperationKind kind,
        UUID warehouseId,
        UUID sourceTaskId,
        String locationLabel,
        OffsetDateTime plannedArrival,
        OffsetDateTime plannedDeparture,
        int loadBefore,
        int loadAfter) {
      this(
          sequence,
          kind,
          warehouseId,
          sourceTaskId,
          null,
          locationLabel,
          plannedArrival,
          plannedDeparture,
          loadBefore,
          loadAfter);
    }
  }

  /** Authoritative state and audit timestamps for one warehouse-local work date. */
  public record DriverShiftView(
      @NotNull UUID id,
      @Min(0) long version,
      @NotNull UUID driverId,
      @NotBlank String driverName,
      @NotNull UUID warehouseId,
      @NotNull LocalDate workDate,
      @NotBlank String timeZone,
      @NotNull DriverShiftStatus status,
      @JsonInclude(JsonInclude.Include.ALWAYS) OffsetDateTime briefingSeenAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) MedicalCheckView medicalCheck,
      @JsonInclude(JsonInclude.Include.ALWAYS) OffsetDateTime vehicleInspectionCompletedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) OffsetDateTime startedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) OffsetDateTime closingStartedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) OffsetDateTime returnedToWarehouseAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) ReturnConfirmationType returnConfirmationType,
      @JsonInclude(JsonInclude.Include.ALWAYS) OffsetDateTime closedAt) {}

  /** Warehouse-service-owned metadata needed by briefing and return screens. */
  public record WarehouseView(
      @NotNull UUID id,
      @NotBlank String name,
      @NotBlank String city,
      @JsonInclude(JsonInclude.Include.ALWAYS) String address,
      @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal latitude,
      @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal longitude,
      @NotBlank String timeZone) {}

  /** Briefing content normalized independently of any weather provider wire schema. */
  public record DailyBriefingView(
      @NotBlank String locationName, @NotNull DailyWeatherBriefing weather) {}

  /** Provider-neutral weather summary; unavailable data never blocks a shift transition. */
  public record DailyWeatherBriefing(
      boolean available,
      @NotBlank String attribution,
      @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal currentTempC,
      @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal feelsLikeC,
      @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal minTempC,
      @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal maxTempC,
      @JsonInclude(JsonInclude.Include.ALWAYS) String condition,
      @JsonInclude(JsonInclude.Include.ALWAYS) String icon,
      @JsonInclude(JsonInclude.Include.ALWAYS) Integer precipitationProbabilityPercent,
      @JsonInclude(JsonInclude.Include.ALWAYS) String precipitationType,
      @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal windSpeedMetersPerSecond,
      @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal windGustMetersPerSecond,
      @NotNull List<WeatherHazardView> hazards) {}

  /**
   * One advisory derived from provider facts rather than attributed to an official alert source.
   */
  public record WeatherHazardView(
      @NotBlank String type,
      @NotBlank String severity,
      @NotBlank String title,
      @NotBlank String description) {}

  /** Pre-trip medical-check audit fact with future external-provider fields. */
  public record MedicalCheckView(
      @NotNull UUID driverId,
      @NotNull UUID shiftId,
      @NotNull LocalDate workDate,
      @NotNull MedicalConfirmationType confirmationType,
      @NotNull OffsetDateTime completedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID externalCheckId,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID doctorId,
      @JsonInclude(JsonInclude.Include.ALWAYS) String provider,
      @JsonInclude(JsonInclude.Include.ALWAYS) OffsetDateTime checkedAt) {}

  /** Assigned vehicle snapshot frozen from the registered logistics plan. */
  public record VehicleView(
      @NotNull UUID id,
      @NotBlank String name,
      @NotBlank String registrationNumber,
      @JsonInclude(JsonInclude.Include.ALWAYS) String vehicleType,
      @JsonInclude(JsonInclude.Include.ALWAYS) String manufacturer,
      @JsonInclude(JsonInclude.Include.ALWAYS) String model,
      @NotNull VehicleConfigurationType configurationType,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Min(1) @Max(2) Integer cabinCapacity,
      @JsonInclude(JsonInclude.Include.ALWAYS) Long startOdometer,
      @JsonInclude(JsonInclude.Include.ALWAYS) TrailerView trailer) {
    /** Preserves source compatibility for response assembly before cabin capacity was exposed. */
    public VehicleView(
        UUID id,
        String name,
        String registrationNumber,
        String vehicleType,
        String manufacturer,
        String model,
        VehicleConfigurationType configurationType,
        Long startOdometer,
        TrailerView trailer) {
      this(
          id,
          name,
          registrationNumber,
          vehicleType,
          manufacturer,
          model,
          configurationType,
          null,
          startOdometer,
          trailer);
    }
  }

  /** Optional trailer snapshot attached to a vehicle plan. */
  public record TrailerView(
      @NotNull UUID id, @NotBlank String name, @NotBlank String registrationNumber) {}

  /** Persisted progress for one template-snapshotted pre-trip inspection. */
  public record InspectionView(
      @NotNull UUID id,
      @Min(0) long version,
      @JsonInclude(JsonInclude.Include.ALWAYS) OffsetDateTime completedAt,
      @Min(0) int totalRequired,
      @Min(0) int checkedRequired,
      @Min(0) int blockingDefectCount,
      @NotNull List<InspectionItemView> items) {}

  /** One snapshotted inspection item and its independently fenced result. */
  public record InspectionItemView(
      @NotNull UUID id,
      @Min(0) long version,
      @NotBlank String templateItemCode,
      @NotBlank String section,
      @NotBlank String label,
      boolean required,
      @Min(0) int sortOrder,
      @NotNull InspectionItemState state,
      @JsonInclude(JsonInclude.Include.ALWAYS) VehicleDefectView defect) {}

  /** Shared vehicle defect projection used by both pre-trip and closing flows. */
  public record VehicleDefectView(
      @NotNull UUID id,
      @NotNull UUID shiftId,
      @NotNull UUID vehicleId,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID inspectionItemId,
      @NotBlank String description,
      @NotNull DefectSeverity severity,
      @NotNull DefectStatus status,
      @NotNull List<UUID> photoIds,
      @NotNull OffsetDateTime createdAt) {}

  /** Today-task summary calculated by task-board rather than from an Android array. */
  public record TaskSummaryView(
      @Min(0) int totalCount,
      @Min(0) int activeCount,
      @Min(0) int completedCount,
      @Min(0) int tripCount,
      @Min(0) long routeDistanceMeters,
      boolean canStartClosing) {}

  /** Persisted end-of-shift report used by the final confirmation screen. */
  public record ClosingReportView(
      @NotNull EndVehicleCondition vehicleCondition,
      @Min(0) long endOdometer,
      @JsonInclude(JsonInclude.Include.ALWAYS) Long odometerDistance,
      @Min(0) @Max(100) int fuelLevelPercent,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID defectId,
      @Min(0) int photoCount,
      @NotNull OffsetDateTime completedAt) {}

  /** One durable shift-photo reservation and terminal media reference. */
  public record ShiftPhotoView(
      @NotNull UUID id,
      @NotNull UUID clientReferenceId,
      @NotNull UUID evidenceId,
      @NotNull ShiftPhotoRole role,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID defectId,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID inspectionItemId,
      @NotNull ShiftPhotoState state,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID mediaId,
      @JsonInclude(JsonInclude.Include.ALWAYS) Long mediaGeneration,
      @NotNull OffsetDateTime capturedAt,
      @NotBlank String contentType,
      @Min(1) long sizeBytes,
      @Pattern(regexp = "^[0-9a-f]{64}$") String sha256) {}

  /** Common idempotent and version-fenced transition command. */
  public record ShiftTransitionRequest(@NotNull UUID operationId, @Min(0) long expectedVersion) {}

  /**
   * Medical self-confirmation command; client time is retained only as non-authoritative evidence.
   */
  public record ConfirmMedicalCheckRequest(
      @NotNull UUID operationId,
      @Min(0) long expectedVersion,
      @JsonInclude(JsonInclude.Include.ALWAYS) OffsetDateTime clientCompletedAt) {}

  /** Independently fenced update of one inspection item. */
  public record UpdateInspectionItemRequest(
      @NotNull UUID operationId,
      @Min(0) long expectedVersion,
      @Min(0) long expectedItemVersion,
      @NotNull InspectionItemState result,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID defectId,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Size(max = 2000) String defectDescription) {}

  /** Manual warehouse-return confirmation with an extensible evidence type. */
  public record ReturnToWarehouseRequest(
      @NotNull UUID operationId,
      @Min(0) long expectedVersion,
      @NotNull ReturnConfirmationType confirmationType) {}

  /** Validated closing report; explicit confirmation is required for a suspicious odometer jump. */
  public record SubmitClosingReportRequest(
      @NotNull UUID operationId,
      @Min(0) long expectedVersion,
      @NotNull EndVehicleCondition vehicleCondition,
      @Min(0) long endOdometer,
      @Min(0) @Max(100) int fuelLevelPercent,
      boolean confirmSuspiciousOdometer,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID defectId,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Size(max = 2000) String defectDescription) {}

  /** One upload reservation tied to the shift owner proof before bytes reach media-service. */
  public record ReserveShiftPhotoRequest(
      @NotNull UUID operationId,
      @Min(0) long expectedVersion,
      @NotNull UUID clientReferenceId,
      @NotNull UUID evidenceId,
      @NotNull ShiftPhotoRole role,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID defectId,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID inspectionItemId,
      @NotNull OffsetDateTime capturedAt,
      @NotBlank String contentType,
      @Min(1) @Max(15_728_640) long sizeBytes,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sha256) {}

  /** Logistics-owned planner projection applied to one stable source shift identity. */
  public record PutDriverShiftPlanRequest(
      @NotNull UUID sourcePlanId,
      @Min(1) long sourcePlanVersion,
      @NotNull UUID warehouseId,
      @NotNull UUID driverId,
      @NotBlank @Size(max = 256) String driverName,
      @NotNull LocalDate workDate,
      @NotNull @Valid PlannedVehicle vehicle,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Valid PlannedTrailer trailer,
      @Min(0) int tripCount,
      @Min(0) long routeDistanceMeters,
      @NotNull @Size(max = 1000) List<@NotNull @Valid RouteOperationView> operations) {
    public PutDriverShiftPlanRequest {
      operations = operations == null ? List.of() : List.copyOf(operations);
    }

    /** Preserves source compatibility for callers predating executable route operations. */
    public PutDriverShiftPlanRequest(
        UUID sourcePlanId,
        long sourcePlanVersion,
        UUID warehouseId,
        UUID driverId,
        String driverName,
        LocalDate workDate,
        PlannedVehicle vehicle,
        PlannedTrailer trailer,
        int tripCount,
        long routeDistanceMeters) {
      this(
          sourcePlanId,
          sourcePlanVersion,
          warehouseId,
          driverId,
          driverName,
          workDate,
          vehicle,
          trailer,
          tripCount,
          routeDistanceMeters,
          List.of());
    }
  }

  /** Vehicle snapshot supplied by the owning logistics plan. */
  public record PlannedVehicle(
      @NotNull UUID id,
      @NotBlank @Size(max = 256) String name,
      @NotBlank @Size(max = 64) String registrationNumber,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Size(max = 64) String vehicleType,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Size(max = 128) String manufacturer,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Size(max = 128) String model,
      @NotNull VehicleConfigurationType configurationType,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Min(1) @Max(2) Integer cabinCapacity,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Min(0) Long startOdometer) {
    /** Preserves source compatibility for vehicle snapshots created before cabin capacity. */
    public PlannedVehicle(
        UUID id,
        String name,
        String registrationNumber,
        String vehicleType,
        String manufacturer,
        String model,
        VehicleConfigurationType configurationType,
        Long startOdometer) {
      this(
          id,
          name,
          registrationNumber,
          vehicleType,
          manufacturer,
          model,
          configurationType,
          null,
          startOdometer);
    }
  }

  /** Optional trailer snapshot supplied by the owning logistics plan. */
  public record PlannedTrailer(
      @NotNull UUID id,
      @NotBlank @Size(max = 256) String name,
      @NotBlank @Size(max = 64) String registrationNumber) {}

  /** Acknowledgement for replay-safe planner projection writes. */
  public record DriverShiftPlanResponse(
      @NotNull UUID sourceShiftId,
      @NotNull UUID sourcePlanId,
      @Min(1) long sourcePlanVersion,
      @NotNull PlanApplyResult result) {}
}
