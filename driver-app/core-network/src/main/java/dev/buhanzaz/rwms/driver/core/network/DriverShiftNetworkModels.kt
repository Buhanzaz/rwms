package dev.buhanzaz.rwms.driver.core.network

import kotlinx.serialization.Serializable

/** Server-driven startup aggregate for the current warehouse-local Driver Up work date. */
@Serializable
data class TodayDriverShiftDto(
    val enabled: Boolean,
    val serverTime: String,
    val suspiciousOdometerJumpKm: Long = 1_500,
    val nextAvailableAt: String? = null,
    val nextRequiredAction: String,
    val shift: DriverShiftDto? = null,
    val warehouse: DriverShiftWarehouseDto? = null,
    val briefing: DailyBriefingDto? = null,
    val vehicle: DriverShiftVehicleDto? = null,
    val inspection: DriverVehicleInspectionDto? = null,
    val taskSummary: DriverShiftTaskSummaryDto? = null,
    val closingReport: DriverShiftClosingReportDto? = null,
    val photos: List<DriverShiftPhotoDto> = emptyList(),
)

/** Authoritative shift status, fencing version, work date and audit timestamps. */
@Serializable
data class DriverShiftDto(
    val id: String,
    val version: Long,
    val driverId: String,
    val driverName: String,
    val warehouseId: String,
    val workDate: String,
    val timeZone: String,
    val status: String,
    val briefingSeenAt: String? = null,
    val medicalCheck: DriverMedicalCheckDto? = null,
    val vehicleInspectionCompletedAt: String? = null,
    val startedAt: String? = null,
    val closingStartedAt: String? = null,
    val returnedToWarehouseAt: String? = null,
    val returnConfirmationType: String? = null,
    val closedAt: String? = null,
)

/** Server-audited medical confirmation with nullable future external-provider facts. */
@Serializable
data class DriverMedicalCheckDto(
    val driverId: String,
    val shiftId: String,
    val workDate: String,
    val confirmationType: String,
    val completedAt: String,
    val externalCheckId: String? = null,
    val doctorId: String? = null,
    val provider: String? = null,
    val checkedAt: String? = null,
)

/** Warehouse-service-owned location snapshot used by briefing, workDate and return screens. */
@Serializable
data class DriverShiftWarehouseDto(
    val id: String,
    val name: String,
    val city: String,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timeZone: String,
)

/** Provider-neutral daily briefing content for one warehouse location. */
@Serializable
data class DailyBriefingDto(
    val locationName: String,
    val weather: DailyWeatherBriefingDto,
)

/** Normalized MET Norway forecast that never exposes provider wire fields or credentials. */
@Serializable
data class DailyWeatherBriefingDto(
    val available: Boolean,
    val attribution: String,
    val currentTempC: Double? = null,
    val feelsLikeC: Double? = null,
    val minTempC: Double? = null,
    val maxTempC: Double? = null,
    val condition: String? = null,
    val icon: String? = null,
    val precipitationProbabilityPercent: Int? = null,
    val precipitationType: String? = null,
    val windSpeedMetersPerSecond: Double? = null,
    val windGustMetersPerSecond: Double? = null,
    val hazards: List<WeatherHazardDto> = emptyList(),
)

/** Advisory derived from forecast measurements, without claiming an official emergency alert. */
@Serializable
data class WeatherHazardDto(
    val type: String,
    val severity: String,
    val title: String,
    val description: String,
)

/** Immutable vehicle and optional trailer snapshot supplied by the logistics plan. */
@Serializable
data class DriverShiftVehicleDto(
    val id: String,
    val name: String,
    val registrationNumber: String,
    val vehicleType: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val configurationType: String,
    val startOdometer: Long? = null,
    val trailer: DriverShiftTrailerDto? = null,
)

/** Immutable trailer identity attached to a planned Driver Up shift. */
@Serializable
data class DriverShiftTrailerDto(
    val id: String,
    val name: String,
    val registrationNumber: String,
)

/** Snapshotted pre-trip inspection and resumable required-item progress. */
@Serializable
data class DriverVehicleInspectionDto(
    val id: String,
    val version: Long,
    val completedAt: String? = null,
    val totalRequired: Int,
    val checkedRequired: Int,
    val blockingDefectCount: Int,
    val items: List<DriverVehicleInspectionItemDto>,
)

/** One independently fenced inspection result from the server-owned template snapshot. */
@Serializable
data class DriverVehicleInspectionItemDto(
    val id: String,
    val version: Long,
    val templateItemCode: String,
    val section: String,
    val label: String,
    val required: Boolean,
    val sortOrder: Int,
    val state: String,
    val defect: DriverVehicleDefectDto? = null,
)

/** Shared vehicle defect projection used by pre-trip and closing workflows. */
@Serializable
data class DriverVehicleDefectDto(
    val id: String,
    val shiftId: String,
    val vehicleId: String,
    val inspectionItemId: String? = null,
    val description: String,
    val severity: String,
    val status: String,
    val photoIds: List<String> = emptyList(),
    val createdAt: String,
)

/** Backend-calculated task/trip summary and authoritative closing eligibility. */
@Serializable
data class DriverShiftTaskSummaryDto(
    val totalCount: Int,
    val activeCount: Int,
    val completedCount: Int,
    val tripCount: Int,
    val routeDistanceMeters: Long,
    val canStartClosing: Boolean,
)

/** Persisted end-of-shift report rendered by the final close confirmation. */
@Serializable
data class DriverShiftClosingReportDto(
    val vehicleCondition: String,
    val endOdometer: Long,
    val odometerDistance: Long? = null,
    val fuelLevelPercent: Int,
    val defectId: String? = null,
    val photoCount: Int,
    val completedAt: String,
)

/** Durable Driver Shift photo reservation and media-processing projection. */
@Serializable
data class DriverShiftPhotoDto(
    val id: String,
    val clientReferenceId: String,
    val evidenceId: String,
    val role: String,
    val defectId: String? = null,
    val inspectionItemId: String? = null,
    val state: String,
    val mediaId: String? = null,
    val mediaGeneration: Long? = null,
    val capturedAt: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** Idempotent version-fenced command shared by simple shift transitions. */
@Serializable
data class ShiftTransitionRequestDto(
    val operationId: String,
    val expectedVersion: Long,
)

/** Self-confirmed medical-check command with non-authoritative client time for offline audit. */
@Serializable
data class ConfirmMedicalCheckRequestDto(
    val operationId: String,
    val expectedVersion: Long,
    val clientCompletedAt: String? = null,
)

/** Independently fenced inspection result with an optional stable client defect identity. */
@Serializable
data class UpdateInspectionItemRequestDto(
    val operationId: String,
    val expectedVersion: Long,
    val expectedItemVersion: Long,
    val result: String,
    val defectId: String? = null,
    val defectDescription: String? = null,
)

/** Manual or future geofence-backed warehouse-return confirmation. */
@Serializable
data class ReturnToWarehouseRequestDto(
    val operationId: String,
    val expectedVersion: Long,
    val confirmationType: String,
)

/** Validated closing report, including explicit acknowledgement of a suspicious odometer jump. */
@Serializable
data class SubmitClosingReportRequestDto(
    val operationId: String,
    val expectedVersion: Long,
    val vehicleCondition: String,
    val endOdometer: Long,
    val fuelLevelPercent: Int,
    val confirmSuspiciousOdometer: Boolean,
    val defectId: String? = null,
    val defectDescription: String? = null,
)

/** Shift-owned evidence reservation submitted before the existing media upload pipeline. */
@Serializable
data class ReserveShiftPhotoRequestDto(
    val operationId: String,
    val expectedVersion: Long,
    val clientReferenceId: String,
    val evidenceId: String,
    val role: String,
    val defectId: String? = null,
    val inspectionItemId: String? = null,
    val capturedAt: String,
    val contentType: String = "image/jpeg",
    val sizeBytes: Long,
    val sha256: String,
)
