package dev.buhanzaz.rwms.worker.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Transport models deliberately mirror the public worker OpenAPI contracts. */
@Serializable
data class WorkerIdentityDto(
    val id: String,
    val warehouseId: String,
    val login: String,
    val displayName: String,
)

@Serializable
data class WorkerGroupSummaryDto(
    val id: String,
    val name: String,
    val workerClassId: String,
    val workerClassName: String,
)

@Serializable
data class WorkerQualificationSummaryDto(
    val workerClassId: String,
    val name: String,
)

@Serializable
data class WorkerCategoryDto(
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
data class WorkerKpiRangeDto(
    val fromPercent: Int,
    val toPercent: Int,
    val color: String,
)

@Serializable
data class WorkerKpiPaletteDto(
    val ranges: List<WorkerKpiRangeDto>,
    val overdueColor: String,
)

@Serializable
data class WorkerOfflineLeaseDto(
    val id: String,
    val issuedAt: String,
    val expiresAt: String,
    val syncRevision: Long,
)

@Serializable
data class WorkerContextDto(
    val worker: WorkerIdentityDto,
    val groups: List<WorkerGroupSummaryDto>,
    val qualifications: List<WorkerQualificationSummaryDto>,
    val categories: List<WorkerCategoryDto>,
    val kpiPalette: WorkerKpiPaletteDto?,
    val serverTime: String,
    val revision: Long,
    val offlineLease: WorkerOfflineLeaseDto,
    // Defaults are a JSON-cache compatibility boundary only. Runtime actions
    // never invent a group when the current gateway response has none.
    val currentGroup: WorkerGroupSummaryDto? = null,
    val operationalAvailability: String = "AVAILABLE",
)

@Serializable
data class WorkerTaskTimerSnapshotDto(
    val countedActiveSeconds: Long,
    val remainingSeconds: Long?,
    val remainingPercent: Double?,
    val timerState: String,
    val nextTransitionAt: String?,
    val serverTime: String,
)

@Serializable
data class WorkerAssignmentDto(
    val id: String,
    val workerId: String?,
    val workerName: String?,
    val workerGroupId: String?,
    val workerGroupName: String?,
    val status: String,
    val assignedAt: String,
    val startedAt: String?,
    val pausedAt: String?,
    val finishedAt: String?,
)

@Serializable
data class WorkerFeedEntryDto(
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
    val assignments: List<WorkerAssignmentDto>,
    val readyEvidenceCount: Int,
    val resultPhotoMinCount: Int,
    // Older persisted feed fixtures have no schedule-aware timer. Null keeps
    // them decodable, but the UI does not continue their wall-clock timer.
    val timerSnapshot: WorkerTaskTimerSnapshotDto? = null,
)

@Serializable
data class WorkerFeedCategoryDto(
    val category: WorkerCategoryDto,
    val entries: List<WorkerFeedEntryDto>,
)

@Serializable
data class WorkerFeedDto(
    val revision: Long,
    val serverTime: String,
    val categories: List<WorkerFeedCategoryDto>,
    val nextCursor: String?,
)

@Serializable
data class WorkerTaskObjectDto(
    val kind: String,
    val id: String?,
    val label: String,
)

@Serializable
data class WorkerMaterialDto(
    val id: String,
    val name: String,
    val quantity: Double,
    val unit: String?,
)

/**
 * A planned maintenance/estimate work item. Costs intentionally do not cross
 * the worker boundary: a worker only needs the quantity, unit and planned
 * duration required to perform the work.
 */
@Serializable
data class WorkerWorkDto(
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
data class WorkerVisibleCommentDto(
    val id: String,
    val text: String,
    val authorDisplayName: String?,
    val createdAt: String,
)

@Serializable
data class WorkerMediaReferenceDto(
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
data class WorkerRelatedStepDto(
    val entryId: String,
    val routeIndex: Int,
    val queueName: String,
    val taskText: String?,
    val status: String,
)

@Serializable
data class TaskEvidenceDto(
    val evidenceId: String,
    val version: Long,
    val entryId: String,
    val routeIndex: Int,
    val workerId: String,
    val workerGroupId: String?,
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
data class AudienceSelectorDto(
    val kind: String,
    val id: String,
    val mode: String,
    val interruptOnTake: Boolean,
    // Default is only for previously cached sanitized detail JSON.
    val notifyOnPrimaryTake: Boolean = false,
)

@Serializable
data class WorkerTaskDetailDto(
    val entryId: String,
    val version: Long,
    val taskId: String,
    val routeIndex: Int,
    val title: String,
    val description: String?,
    @SerialName("object") val taskObject: WorkerTaskObjectDto?,
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
    val assignments: List<WorkerAssignmentDto>,
    val materials: List<WorkerMaterialDto>,
    // Default preserves the first-release sanitized detail JSON, which did
    // not contain maintenance/estimate works yet.
    val works: List<WorkerWorkDto> = emptyList(),
    val comments: List<WorkerVisibleCommentDto>,
    val sourceMedia: List<WorkerMediaReferenceDto>,
    val evidence: List<TaskEvidenceDto>,
    val relatedSteps: List<WorkerRelatedStepDto>,
    val resultPhotoMinCount: Int,
    val completionAllowed: Boolean,
    val timerSnapshot: WorkerTaskTimerSnapshotDto? = null,
)

@Serializable
data class WorkerActionRequestDto(
    val operationId: String,
    val action: String,
    val expectedVersion: Long,
    val workerGroupId: String?,
    val evidenceId: String?,
    val occurredAt: String,
    val offlineLeaseId: String,
)

@Serializable
data class WorkerActionResultDto(
    val outcome: String,
    val currentVersion: Long,
    val entry: WorkerTaskDetailDto,
)

@Serializable
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
data class WorkerInvalidationEventDto(
    val eventId: String,
    val revision: Long,
    val type: String,
    val entryId: String?,
    val occurredAt: String,
)

@Serializable
data class WorkerDeviceRegistrationRequestDto(
    val provider: String = "FCM",
    val token: String,
    val appVersion: String,
    val sdkInt: Int,
    val locale: String,
)

@Serializable
data class WorkerDeviceRegistrationDto(
    val installationId: String,
    val provider: String,
    val status: String,
    val registeredAt: String,
    val updatedAt: String,
)

@Serializable
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
data class UploadSessionDto(
    val uploadSessionId: String,
    val mediaId: String,
    val expiresAt: String,
    val contentUploadUrl: String,
)

@Serializable
data class UploadedObjectDto(
    val objectVersionId: String,
    val etag: String,
    val checksumSha256: String,
)

@Serializable
data class FinalizeUploadRequestDto(
    val objectVersionId: String,
    val etag: String,
    val checksumSha256: String,
)

@Serializable
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
data class SafeMediaVariantDto(
    val kind: String,
    val contentType: String,
    val contentPath: String,
    val width: Int?,
    val height: Int?,
)

@Serializable
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
    val currentEntry: WorkerTaskDetailDto? = null,
)

@Serializable
data class FieldViolationDto(val field: String, val code: String, val message: String)

@Serializable
data class CorrelationContextDto(val correlationId: String, val causationId: String? = null)
