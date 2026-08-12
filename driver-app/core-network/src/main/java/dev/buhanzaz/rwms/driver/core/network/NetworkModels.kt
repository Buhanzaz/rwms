package dev.buhanzaz.rwms.driver.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Transport models deliberately mirror the public driver OpenAPI contracts. */
@Serializable
data class DriverIdentityDto(
    val id: String,
    val warehouseId: String,
    val login: String,
    val displayName: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverGroupSummaryDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverGroupSummaryDto(
    val id: String,
    val name: String,
    @SerialName("workerClassId")
    val driverClassId: String,
    @SerialName("workerClassName")
    val driverClassName: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverQualificationSummaryDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverQualificationSummaryDto(
    @SerialName("workerClassId")
    val driverClassId: String,
    val name: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverCategoryDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverCategoryDto(
    val queueId: String,
    val name: String,
    val type: String,
    val queuePurpose: String,
    val groupIds: List<String>,
    val sortOrder: Int,
    val audienceModes: List<String>,
    val resultPhotoMinCount: Int,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverKpiRangeDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverKpiRangeDto(
    val fromPercent: Int,
    val toPercent: Int,
    val color: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverKpiPaletteDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverKpiPaletteDto(
    val ranges: List<DriverKpiRangeDto>,
    val overdueColor: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverOfflineLeaseDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverOfflineLeaseDto(
    val id: String,
    val issuedAt: String,
    val expiresAt: String,
    val syncRevision: Long,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverContextDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverContextDto(
    @SerialName("worker")
    val driver: DriverIdentityDto,
    val groups: List<DriverGroupSummaryDto>,
    val qualifications: List<DriverQualificationSummaryDto>,
    val categories: List<DriverCategoryDto>,
    val kpiPalette: DriverKpiPaletteDto?,
    val serverTime: String,
    val revision: Long,
    val offlineLease: DriverOfflineLeaseDto,
    // Defaults are a JSON-cache compatibility boundary only. Runtime actions
    // never invent a group when the current gateway response has none.
    val currentGroup: DriverGroupSummaryDto? = null,
    val operationalAvailability: String = "AVAILABLE",
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverTaskTimerSnapshotDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverTaskTimerSnapshotDto(
    val countedActiveSeconds: Long,
    val remainingSeconds: Long?,
    val remainingPercent: Double?,
    val timerState: String,
    val nextTransitionAt: String?,
    val serverTime: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverAssignmentDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverAssignmentDto(
    val id: String,
    @SerialName("workerId")
    val driverId: String?,
    @SerialName("workerName")
    val driverName: String?,
    @SerialName("workerGroupId")
    val driverGroupId: String?,
    @SerialName("workerGroupName")
    val driverGroupName: String?,
    val status: String,
    val assignedAt: String,
    val startedAt: String?,
    val pausedAt: String?,
    val finishedAt: String?,
)

@Serializable
/**
 * Server-owned driver visibility attached to one driver-feed entry.
 *
 * The authenticated feed is already authorization-filtered. DriverApp uses
 * only the mode to place an entry in the personal logistics or shared
 * movement table; it never broadens visibility locally.
 */
data class DriverTaskAudienceDto(
    val mode: String,
    @SerialName("workerId")
    val driverId: String?,
    @SerialName("workerName")
    val driverName: String?,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverFeedEntryDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverFeedEntryDto(
    val entryId: String,
    val version: Long,
    val taskId: String,
    val routeIndex: Int,
    val title: String,
    val unitNumber: String?,
    val taskText: String?,
    val scheduledDate: String,
    val deadlineAt: String?,
    val priority: Int,
    val queuePosition: Int,
    val status: String,
    val availabilityMode: String,
    val plannedDurationMinutes: Int?,
    val activeStartedAt: String?,
    val activeWorkSeconds: Long,
    val assignments: List<DriverAssignmentDto>,
    val readyEvidenceCount: Int,
    val resultPhotoMinCount: Int,
    val driverAudience: DriverTaskAudienceDto?,
    // Older persisted feed fixtures have no schedule-aware timer. Null keeps
    // them decodable, but the UI does not continue their wall-clock timer.
    val timerSnapshot: DriverTaskTimerSnapshotDto? = null,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverFeedCategoryDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverFeedCategoryDto(
    val category: DriverCategoryDto,
    val entries: List<DriverFeedEntryDto>,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverFeedDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverFeedDto(
    val revision: Long,
    val serverTime: String,
    val categories: List<DriverFeedCategoryDto>,
    val nextCursor: String?,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverTaskObjectDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverTaskObjectDto(
    val kind: String,
    val id: String?,
    val label: String,
)

/** Immutable owning-aggregate reference supplied by task-board. */
@Serializable
data class TaskSourceReferenceDto(
    val type: String,
    val sourceId: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverMaterialDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverMaterialDto(
    val id: String,
    val name: String,
    val quantity: Double,
    val unit: String?,
)

/**
 * A planned maintenance/estimate work item. Costs intentionally do not cross
 * the driver boundary: a driver only needs the quantity, unit and planned
 * duration required to perform the work.
 */
@Serializable
data class DriverWorkDto(
    val id: String,
    val name: String,
    val quantity: Double,
    val unit: String?,
    val durationMinutes: Int?,
    val comment: String?,
    // Old sanitized Room details predate work-level media binding.
    val sourceMediaIds: List<String> = emptyList(),
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverVisibleCommentDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverVisibleCommentDto(
    val id: String,
    val text: String,
    val authorDisplayName: String?,
    val createdAt: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverMediaReferenceDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverMediaReferenceDto(
    val mediaId: String,
    val generation: Long,
    val kind: String,
    val contentType: String,
    val readPath: String,
    val thumbnailPath: String?,
    val capturedAt: String?,
    val recordedAt: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverRelatedStepDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverRelatedStepDto(
    val entryId: String,
    val routeIndex: Int,
    val queueName: String,
    val taskText: String?,
    val status: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for TaskEvidenceDto. It is a transport boundary model, not persisted domain state.
 */
data class TaskEvidenceDto(
    val evidenceId: String,
    val version: Long,
    val entryId: String,
    val routeIndex: Int,
    @SerialName("workerId")
    val driverId: String,
    @SerialName("workerGroupId")
    val driverGroupId: String?,
    val capturedAt: String,
    val recordedAt: String,
    val state: String,
    val mediaId: String?,
    val mediaGeneration: Long?,
    val reviewReason: String?,
    // These fields were added after the first offline detail cache format.
    // Defaults keep cached v1 JSON decodable until the next authenticated
    // detail refresh supplies the media read links.
    val contentType: String? = null,
    val readPath: String? = null,
    val thumbnailPath: String? = null,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for AudienceSelectorDto. It is a transport boundary model, not persisted domain state.
 */
data class AudienceSelectorDto(
    val kind: String,
    val id: String,
    val mode: String,
    val interruptOnTake: Boolean,
    // Default is only for previously cached sanitized detail JSON.
    val notifyOnPrimaryTake: Boolean = false,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverTaskDetailDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverTaskDetailDto(
    val entryId: String,
    val version: Long,
    val taskId: String,
    // Default is only for task detail JSON cached before task-board exposed
    // the required nullable source reference.
    val source: TaskSourceReferenceDto? = null,
    val routeIndex: Int,
    val title: String,
    val description: String?,
    @SerialName("object") val taskObject: DriverTaskObjectDto?,
    val taskText: String?,
    val scheduledDate: String,
    val deadlineAt: String?,
    val priority: Int,
    val queuePosition: Int,
    val status: String,
    val availabilityMode: String,
    val plannedDurationMinutes: Int?,
    val activeStartedAt: String?,
    val activeWorkSeconds: Long,
    val audienceSelectors: List<AudienceSelectorDto>,
    val assignments: List<DriverAssignmentDto>,
    val materials: List<DriverMaterialDto>,
    // Default preserves the first-release sanitized detail JSON, which did
    // not contain maintenance/estimate works yet.
    val works: List<DriverWorkDto> = emptyList(),
    val comments: List<DriverVisibleCommentDto>,
    val sourceMedia: List<DriverMediaReferenceDto>,
    val evidence: List<TaskEvidenceDto>,
    val relatedSteps: List<DriverRelatedStepDto>,
    val resultPhotoMinCount: Int,
    val completionAllowed: Boolean,
    val timerSnapshot: DriverTaskTimerSnapshotDto? = null,
)

/** Additional client or order contact visible to the assigned driver. */
@Serializable
data class DriverTripAdditionalContactDto(
    val name: String,
    val phone: String,
)

/** Advisory client-requested delivery date or date range; it contains no time of day. */
@Serializable
data class DriverTripDesiredDeliveryWindowDto(
    val startDate: String,
    val endDate: String,
)

/** Furniture requested for one cabin in the order. */
@Serializable
data class DriverTripDesiredEquipmentDto(
    val equipmentId: String,
    val equipmentName: String,
    val quantity: Long,
)

/** Current physical furniture observed in one cabin. */
@Serializable
data class DriverTripActualEquipmentDto(
    val equipmentId: String,
    val equipmentName: String?,
    val quantity: Long,
    val locationKind: String,
)

/** One cabin member of a grouped logistics trip and its live filling readiness. */
@Serializable
data class DriverTripCabinDto(
    val cabinId: String,
    val unitNumber: String,
    val desiredContents: List<DriverTripDesiredEquipmentDto>,
    val actualContents: List<DriverTripActualEquipmentDto>,
    val movementTaskCreated: Boolean,
    val movementTaskCompleted: Boolean,
    val contentReady: Boolean,
)

/**
 * Logistics-owned structured facts needed by the assigned driver to execute one trip.
 *
 * The public driver payload deliberately exposes client preferences and the assigned trip
 * as dates only; it does not expose time-of-day fields.
 */
@Serializable
data class DriverTripDetailsDto(
    val taskNumber: String,
    val tripNumber: Int,
    val operationType: String,
    val clientName: String,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val primaryContactName: String?,
    val primaryContactPhone: String?,
    val additionalContacts: List<DriverTripAdditionalContactDto>,
    val comment: String?,
    val desiredDeliveryWindows: List<DriverTripDesiredDeliveryWindowDto>,
    val scheduledDate: String,
    val cabins: List<DriverTripCabinDto>,
)

/** Minimal read boundary for the public logistics driver-task response. */
@Serializable
data class DriverTaskTripDetailsResponseDto(
    val tripDetails: DriverTripDetailsDto?,
)

@Serializable(with = DriverActionRequestDtoSerializer::class)
/**
 * Exact seven-field public driver action command. The canonical required-null
 * `workerGroupId` and `evidenceId` keys are emitted by [DriverActionRequestDtoSerializer].
 */
data class DriverActionRequestDto(
    val operationId: String,
    val action: String,
    val expectedVersion: Long,
    val driverGroupId: String?,
    val evidenceId: String?,
    val occurredAt: String,
    val offlineLeaseId: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverActionResultDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverActionResultDto(
    val outcome: String,
    val currentVersion: Long,
    val entry: DriverTaskDetailDto,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for EvidenceReservationRequestDto. It is a transport boundary model, not persisted domain state.
 */
data class EvidenceReservationRequestDto(
    val operationId: String,
    val evidenceId: String,
    val routeIndex: Int,
    val capturedAt: String,
    val offlineLeaseId: String,
    val contentType: String = "image/jpeg",
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverInvalidationEventDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverInvalidationEventDto(
    val eventId: String,
    val revision: Long,
    val type: String,
    val entryId: String?,
    val occurredAt: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverDeviceRegistrationRequestDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverDeviceRegistrationRequestDto(
    val provider: String = "FCM",
    /** Selects Firebase Installation ID routing instead of the legacy registration-token route. */
    val targetKind: String = "FID",
    val token: String,
    val appVersion: String,
    val sdkInt: Int,
    val locale: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for DriverDeviceRegistrationDto. It is a transport boundary model, not persisted domain state.
 */
data class DriverDeviceRegistrationDto(
    val installationId: String,
    val provider: String,
    val status: String,
    val registeredAt: String,
    val updatedAt: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for CreateUploadSessionRequestDto. It is a transport boundary model, not persisted domain state.
 */
data class CreateUploadSessionRequestDto(
    val ownerType: String = "TASK_BOARD_ENTRY",
    val ownerId: String,
    val warehouseId: String,
    val context: String = "WORK_RESULT",
    val clientReferenceId: String,
    val fileName: String,
    val contentType: String = "image/jpeg",
    val contentLength: Long,
    val checksumSha256: String,
    val sortOrder: Int = 0,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for UploadSessionDto. It is a transport boundary model, not persisted domain state.
 */
data class UploadSessionDto(
    val uploadSessionId: String,
    val mediaId: String,
    val expiresAt: String,
    val contentUploadUrl: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for UploadedObjectDto. It is a transport boundary model, not persisted domain state.
 */
data class UploadedObjectDto(
    val objectVersionId: String,
    val etag: String,
    val checksumSha256: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for FinalizeUploadRequestDto. It is a transport boundary model, not persisted domain state.
 */
data class FinalizeUploadRequestDto(
    val objectVersionId: String,
    val etag: String,
    val checksumSha256: String,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for MediaAssetDto. It is a transport boundary model, not persisted domain state.
 */
data class MediaAssetDto(
    val id: String,
    val folderId: String,
    val clientReferenceId: String?,
    val fileName: String,
    val contentType: String,
    val kind: String,
    val status: String,
    val version: Long,
    val generation: Long,
    val rotationDegrees: Int,
    val sortOrder: Int,
    val sizeBytes: Long?,
    val createdAt: String,
    val variants: List<SafeMediaVariantDto>,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for SafeMediaVariantDto. It is a transport boundary model, not persisted domain state.
 */
data class SafeMediaVariantDto(
    val kind: String,
    val contentType: String,
    val contentPath: String,
    val width: Int?,
    val height: Int?,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for ApiProblemDto. It is a transport boundary model, not persisted domain state.
 */
data class ApiProblemDto(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
    val code: String,
    val violations: List<FieldViolationDto> = emptyList(),
    val correlation: CorrelationContextDto? = null,
    val outcome: String? = null,
    val currentVersion: Long? = null,
    val currentEntry: DriverTaskDetailDto? = null,
)

@Serializable
/**
 * Public-driver-gateway response/read payload for FieldViolationDto. It is a transport boundary model, not persisted domain state.
 */
data class FieldViolationDto(val field: String, val code: String, val message: String)

@Serializable
/**
 * Public-driver-gateway response/read payload for CorrelationContextDto. It is a transport boundary model, not persisted domain state.
 */
data class CorrelationContextDto(val correlationId: String, val causationId: String? = null)
