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
/**
 * Public-worker-gateway response/read payload for WorkerGroupSummaryDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerGroupSummaryDto(
    val id: String,
    val name: String,
    val workerClassId: String,
    val workerClassName: String,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for WorkerQualificationSummaryDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerQualificationSummaryDto(
    val workerClassId: String,
    val name: String,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for WorkerCategoryDto. It is a transport boundary model, not persisted domain state.
 */
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
/**
 * Public-worker-gateway response/read payload for WorkerKpiRangeDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerKpiRangeDto(
    val fromPercent: Int,
    val toPercent: Int,
    val color: String,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for WorkerKpiPaletteDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerKpiPaletteDto(
    val ranges: List<WorkerKpiRangeDto>,
    val overdueColor: String,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for WorkerOfflineLeaseDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerOfflineLeaseDto(
    val id: String,
    val issuedAt: String,
    val expiresAt: String,
    val syncRevision: Long,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for WorkerContextDto. It is a transport boundary model, not persisted domain state.
 */
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
/**
 * Public-worker-gateway response/read payload for WorkerTaskTimerSnapshotDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerTaskTimerSnapshotDto(
    val countedActiveSeconds: Long,
    val remainingSeconds: Long?,
    val remainingPercent: Double?,
    val timerState: String,
    val nextTransitionAt: String?,
    val serverTime: String,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for WorkerAssignmentDto. It is a transport boundary model, not persisted domain state.
 */
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
/**
 * Public-worker-gateway response/read payload for WorkerFeedEntryDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerFeedEntryDto(
    val entryId: String,
    val version: Long,
    val taskId: String,
    val routeIndex: Int,
    val routeStepCount: Int,
    val entryType: String,
    val pinned: Boolean,
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
/**
 * Public-worker-gateway response/read payload for WorkerFeedCategoryDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerFeedCategoryDto(
    val category: WorkerCategoryDto,
    val entries: List<WorkerFeedEntryDto>,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for WorkerFeedDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerFeedDto(
    val revision: Long,
    val serverTime: String,
    val categories: List<WorkerFeedCategoryDto>,
    val nextCursor: String?,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for WorkerTaskObjectDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerTaskObjectDto(
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
 * Public-worker-gateway response/read payload for WorkerMaterialDto. It is a transport boundary model, not persisted domain state.
 */
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
/**
 * Public-worker-gateway response/read payload for WorkerVisibleCommentDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerVisibleCommentDto(
    val id: String,
    val text: String,
    val authorDisplayName: String?,
    val createdAt: String,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for WorkerMediaReferenceDto. It is a transport boundary model, not persisted domain state.
 */
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
/**
 * Public-worker-gateway response/read payload for WorkerRelatedStepDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerRelatedStepDto(
    val entryId: String,
    val routeIndex: Int,
    val queueName: String,
    val taskText: String?,
    val status: String,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for TaskEvidenceDto. It is a transport boundary model, not persisted domain state.
 */
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
/**
 * Public-worker-gateway response/read payload for AudienceSelectorDto. It is a transport boundary model, not persisted domain state.
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
 * Public-worker-gateway response/read payload for WorkerTaskDetailDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerTaskDetailDto(
    val entryId: String,
    val version: Long,
    val taskId: String,
    // Default is only for task detail JSON cached before task-board exposed
    // the required nullable source reference.
    val source: TaskSourceReferenceDto? = null,
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

@Serializable(with = WorkerActionRequestDtoSerializer::class)
/**
 * Exact seven-field public worker action command. The canonical required-null
 * `workerGroupId` and `evidenceId` keys are emitted by [WorkerActionRequestDtoSerializer].
 */
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
/**
 * Public-worker-gateway response/read payload for WorkerActionResultDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerActionResultDto(
    val outcome: String,
    val currentVersion: Long,
    val entry: WorkerTaskDetailDto,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for EvidenceReservationRequestDto. It is a transport boundary model, not persisted domain state.
 */
data class EvidenceReservationRequestDto(
    val operationId: String,
    val evidenceId: String,
    val routeIndex: Int,
    val capturedAt: String,
    val offlineLeaseId: String,
    val contentType: String = "image/webp",
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for WorkerInvalidationEventDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerInvalidationEventDto(
    val eventId: String,
    val revision: Long,
    val type: String,
    val entryId: String?,
    val occurredAt: String,
)

/**
 * Registers a push target using the explicit server contract discriminator.
 * WorkerApp sends a Firebase Installation ID with `targetKind=FID`; `token`
 * remains the canonical transport field name and never contains an FCM token.
 */
@Serializable
data class WorkerDeviceRegistrationRequestDto(
    val provider: String = "FCM",
    val targetKind: String,
    val token: String,
    val appVersion: String,
    val sdkInt: Int,
    val locale: String,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for WorkerDeviceRegistrationDto. It is a transport boundary model, not persisted domain state.
 */
data class WorkerDeviceRegistrationDto(
    val installationId: String,
    val provider: String,
    val status: String,
    val registeredAt: String,
    val updatedAt: String,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for CreateUploadSessionRequestDto. It is a transport boundary model, not persisted domain state.
 */
data class CreateUploadSessionRequestDto(
    val ownerType: String = "TASK_BOARD_ENTRY",
    val ownerId: String,
    val warehouseId: String,
    val context: String = "WORK_RESULT",
    val clientReferenceId: String,
    val fileName: String,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val checksumSha256: String? = null,
    val imageVariants: List<ImageVariantUploadRequestDto>? = null,
    val sortOrder: Int = 0,
)

/** Declares one complete client-produced still-image WebP part before direct upload. */
@Serializable
data class ImageVariantUploadRequestDto(
    val kind: String,
    val contentLength: Long,
    val checksumSha256: String,
    val width: Int,
    val height: Int,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for UploadSessionDto. It is a transport boundary model, not persisted domain state.
 */
data class UploadSessionDto(
    val uploadSessionId: String,
    val mediaId: String,
    val expiresAt: String,
    val contentUploadUrl: String? = null,
    val variantUploadUrls: List<VariantUploadUrlDto>,
)

/** Same-origin presigned gateway path for one declared image variant. */
@Serializable
data class VariantUploadUrlDto(
    val kind: String,
    val contentUploadUrl: String,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for UploadedObjectDto. It is a transport boundary model, not persisted domain state.
 */
data class UploadedObjectDto(
    val objectVersionId: String,
    val etag: String,
    val checksumSha256: String,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for FinalizeUploadRequestDto. It is a transport boundary model, not persisted domain state.
 */
data class FinalizeUploadRequestDto(
    val objectVersionId: String? = null,
    val etag: String? = null,
    val checksumSha256: String? = null,
    val variants: List<FinalizeImageVariantDto>? = null,
)

/** Immutable object-store acknowledgement used to finalize one uploaded WebP part. */
@Serializable
data class FinalizeImageVariantDto(
    val kind: String,
    val objectVersionId: String,
    val etag: String,
    val checksumSha256: String,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for MediaAssetDto. It is a transport boundary model, not persisted domain state.
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
 * Public-worker-gateway response/read payload for SafeMediaVariantDto. It is a transport boundary model, not persisted domain state.
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
 * Public-worker-gateway response/read payload for ApiProblemDto. It is a transport boundary model, not persisted domain state.
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
    val currentEntry: WorkerTaskDetailDto? = null,
)

@Serializable
/**
 * Public-worker-gateway response/read payload for FieldViolationDto. It is a transport boundary model, not persisted domain state.
 */
data class FieldViolationDto(val field: String, val code: String, val message: String)

@Serializable
/**
 * Public-worker-gateway response/read payload for CorrelationContextDto. It is a transport boundary model, not persisted domain state.
 */
data class CorrelationContextDto(val correlationId: String, val causationId: String? = null)
