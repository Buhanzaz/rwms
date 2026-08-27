package dev.buhanzaz.rwms.worker.core.database

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** All user-visible projections are tenant-scoped by [userId]. */
@Entity(tableName = "worker_session")
data class WorkerSessionEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val login: String?,
    val warehouseId: String?,
    val leaseId: String?,
    val leaseExpiresAtEpochMillis: Long?,
    val serverEpochMillis: Long?,
    val elapsedRealtimeAtSyncMillis: Long?,
    val revision: Long,
    val feedEtag: String?,
    val cacheHidden: Boolean,
    val updatedAtEpochMillis: Long,
    val currentGroupId: String? = null,
    val currentGroupName: String? = null,
    @ColumnInfo(defaultValue = "'AVAILABLE'")
    val operationalAvailability: String = "AVAILABLE",
    /** Exact server-issued palette JSON; null means the service has no KPI color policy. */
    val kpiPaletteJson: String? = null,
)

@Entity(tableName = "worker_group", indices = [Index(value = ["userId", "name"])])
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
data class WorkerGroupEntity(
    @PrimaryKey val localId: String,
    val userId: String,
    val groupId: String,
    val name: String,
    val workerClassId: String,
    val workerClassName: String,
)

/**
 * Authorized queue metadata is stored independently from its tasks so Room
 * can render an empty queue and can remove a revoked binding atomically with a
 * full feed replacement.
 */
@Entity(
    tableName = "worker_category",
    indices = [
        Index(value = ["userId", "queueId"], unique = true),
        Index(value = ["userId", "sortOrder", "queueId"]),
    ],
)
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
data class WorkerCategoryEntity(
    @PrimaryKey val localId: String,
    val userId: String,
    val queueId: String,
    val name: String,
    val type: String,
    @ColumnInfo(defaultValue = "'GENERAL'")
    val queuePurpose: String,
    @ColumnInfo(defaultValue = "''")
    val groupIdsKey: String = "",
    val sortOrder: Int,
    val audienceModesKey: String,
    val resultPhotoMinCount: Int,
    val lastServerRevision: Long,
)

@Entity(
    tableName = "worker_task",
    indices = [Index(value = ["userId", "categorySortOrder", "queuePosition"]), Index(value = ["userId", "entryId"], unique = true)],
)
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
data class WorkerTaskEntity(
    @PrimaryKey val localId: String,
    val userId: String,
    val entryId: String,
    val taskId: String,
    val version: Long,
    val categoryId: String,
    val categoryName: String,
    val categorySortOrder: Int,
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
    val readyEvidenceCount: Int,
    val resultPhotoMinCount: Int,
    val lastServerRevision: Long,
    val locallyPending: Boolean,
    val updatedAtEpochMillis: Long,
    val timerCountedActiveSeconds: Long? = null,
    val timerRemainingSeconds: Long? = null,
    val timerRemainingPercent: Double? = null,
    val timerState: String? = null,
    val timerNextTransitionAt: String? = null,
    val timerServerTime: String? = null,
    /** Raw persisted route identity used by task-board evidence commands. */
    @ColumnInfo(defaultValue = "0")
    val routeIndex: Int = 0,
    /** Zero-based ordinal of this worker execution package. */
    @ColumnInfo(defaultValue = "0")
    val routeStepIndex: Int = 0,
    /** Total number of worker execution packages. */
    @ColumnInfo(defaultValue = "1")
    val routeStepCount: Int = 1,
    /** Server-owned route visibility: only REAL entries may expose worker actions. */
    @ColumnInfo(defaultValue = "'REAL'")
    val entryType: String = "REAL",
    /** Keeps a claimed or explicitly fixed REAL entry ahead of later route promotions. */
    @ColumnInfo(defaultValue = "0")
    val pinned: Boolean = false,
)

@Entity(tableName = "worker_assignment", indices = [Index(value = ["userId", "entryId"])])
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
data class WorkerAssignmentEntity(
    @PrimaryKey val localId: String,
    val userId: String,
    val entryId: String,
    val assignmentId: String,
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

@Entity(tableName = "worker_task_detail", indices = [Index(value = ["userId", "entryId"], unique = true)])
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
data class WorkerTaskDetailEntity(
    @PrimaryKey val localId: String,
    val userId: String,
    val entryId: String,
    val version: Long,
    val sanitizedDetailJson: String,
    val fetchedAtEpochMillis: Long,
)

@Entity(tableName = "worker_outbox", indices = [Index(value = ["userId", "state", "createdAtEpochMillis"]), Index(value = ["entryId"])])
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
data class WorkerOutboxEntity(
    @PrimaryKey val operationId: String,
    val userId: String,
    val entryId: String,
    val kind: String,
    val encryptedPayload: String,
    val expectedVersion: Long?,
    val state: String,
    val retryCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lastError: String?,
)

@Entity(tableName = "task_evidence", indices = [Index(value = ["userId", "entryId"]), Index(value = ["userId", "state"])])
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
data class TaskEvidenceEntity(
    @PrimaryKey val evidenceId: String,
    val userId: String,
    val entryId: String,
    val routeIndex: Int,
    val capturedAt: String,
    val encryptedFilePath: String,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val reservationOperationId: String,
    val uploadOperationId: String,
    val state: String,
    val mediaId: String?,
    val mediaGeneration: Long?,
    val reviewReason: String?,
    /** Last durable upload progress; the UI never derives it from volatile work state. */
    val uploadPercent: Int,
    val lastError: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    /** Encrypted SMALL/MEDIUM/LARGE WebP metadata required to resume an interrupted upload. */
    @ColumnInfo(defaultValue = "'[]'")
    val variantManifestJson: String = "[]",
)

/** One encrypted client-produced WebP part persisted until task-board confirms evidence READY. */
@Serializable
data class EncryptedEvidenceVariantPart(
    val kind: String,
    val encryptedPath: String,
    val contentLength: Long,
    val checksumSha256: String,
    val width: Int,
    val height: Int,
)

@Entity(tableName = "worker_sync_progress")
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
data class WorkerSyncProgressEntity(
    @PrimaryKey val userId: String,
    val stage: String,
    val completedUnits: Int,
    val totalUnits: Int,
    val activeEvidenceId: String?,
    val activeEvidencePercent: Int?,
    val message: String?,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "worker_conflict", indices = [Index(value = ["userId", "createdAtEpochMillis"])])
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
data class WorkerConflictEntity(
    @PrimaryKey val operationId: String,
    val userId: String,
    val entryId: String,
    val code: String,
    val message: String,
    val currentVersion: Long?,
    val encryptedCurrentEntry: String?,
    val createdAtEpochMillis: Long,
    val resolvedAtEpochMillis: Long?,
)

@Entity(tableName = "worker_invalidation")
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
data class WorkerInvalidationEntity(
    @PrimaryKey val eventId: String,
    val userId: String,
    val revision: Long,
    val type: String,
    val entryId: String?,
    val occurredAt: String,
)
