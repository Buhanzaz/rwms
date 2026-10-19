package dev.buhanzaz.rwms.driver.core.database

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

/** All user-visible projections are tenant-scoped by [userId]. */
@Entity(tableName = "driver_session")
data class DriverSessionEntity(
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

@Entity(tableName = "driver_group", indices = [Index(value = ["userId", "name"])])
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
data class DriverGroupEntity(
    @PrimaryKey val localId: String,
    val userId: String,
    val groupId: String,
    val name: String,
    val driverClassId: String,
    val driverClassName: String,
)

/**
 * Authorized queue metadata is stored independently from its tasks so Room
 * can render an empty queue and can remove a revoked binding atomically with a
 * full feed replacement.
 */
@Entity(
    tableName = "driver_category",
    indices = [
        Index(value = ["userId", "queueId"], unique = true),
        Index(value = ["userId", "sortOrder", "queueId"]),
    ],
)
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
data class DriverCategoryEntity(
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
    tableName = "driver_task",
    indices = [Index(value = ["userId", "categorySortOrder", "queuePosition"]), Index(value = ["userId", "entryId"], unique = true)],
)
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
data class DriverTaskEntity(
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
    /** Server-owned driver audience used only to select the correct driver table. */
    val driverAudienceMode: String? = null,
)

@Entity(tableName = "driver_assignment", indices = [Index(value = ["userId", "entryId"])])
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
data class DriverAssignmentEntity(
    @PrimaryKey val localId: String,
    val userId: String,
    val entryId: String,
    val assignmentId: String,
    val driverId: String?,
    val driverName: String?,
    val driverGroupId: String?,
    val driverGroupName: String?,
    val status: String,
    val assignedAt: String,
    val startedAt: String?,
    val pausedAt: String?,
    val finishedAt: String?,
)

@Entity(tableName = "driver_task_detail", indices = [Index(value = ["userId", "entryId"], unique = true)])
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
data class DriverTaskDetailEntity(
    @PrimaryKey val localId: String,
    val userId: String,
    val entryId: String,
    val version: Long,
    val sanitizedDetailJson: String,
    val fetchedAtEpochMillis: Long,
)

@Entity(tableName = "driver_outbox", indices = [Index(value = ["userId", "state", "createdAtEpochMillis"]), Index(value = ["entryId"])])
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
data class DriverOutboxEntity(
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

@Entity(
    tableName = "task_evidence",
    indices = [
        Index(value = ["userId", "entryId"]),
        Index(value = ["userId", "state"]),
        Index(value = ["userId", "ownerType", "entryId"]),
    ],
)
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
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
    @ColumnInfo(defaultValue = "'TASK_BOARD_ENTRY'")
    val ownerType: String = "TASK_BOARD_ENTRY",
    @ColumnInfo(defaultValue = "'WORK_RESULT'")
    val mediaContext: String = "WORK_RESULT",
    val photoRole: String? = null,
    val defectId: String? = null,
    val inspectionItemId: String? = null,
)

/** Cached server startup aggregate plus explicit pending overlay for one authenticated driver. */
@Entity(tableName = "driver_shift_snapshot")
data class DriverShiftSnapshotEntity(
    @PrimaryKey val userId: String,
    val shiftId: String?,
    val workDate: String?,
    val enabled: Boolean,
    val nextRequiredAction: String,
    val serializedTodayShift: String,
    val serverTime: String,
    /** Raw version from the last accepted server response; null for an upgraded snapshot with unknown authority. */
    val authoritativeShiftVersion: Long? = null,
    val updatedAtEpochMillis: Long,
)

/** Process-death-safe closing input that remains a draft until the server accepts the report. */
@Entity(
    tableName = "driver_shift_draft",
    indices = [Index(value = ["userId", "shiftId"], unique = true)],
)
data class DriverShiftDraftEntity(
    @PrimaryKey val localId: String,
    val userId: String,
    val shiftId: String,
    val step: String,
    val vehicleCondition: String?,
    val endOdometerText: String,
    val fuelLevelPercent: Int?,
    val defectId: String?,
    val defectDescription: String,
    val confirmSuspiciousOdometer: Boolean,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "driver_sync_progress")
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
data class DriverSyncProgressEntity(
    @PrimaryKey val userId: String,
    val stage: String,
    val completedUnits: Int,
    val totalUnits: Int,
    val activeEvidenceId: String?,
    val activeEvidencePercent: Int?,
    val message: String?,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "driver_conflict", indices = [Index(value = ["userId", "createdAtEpochMillis"])])
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
data class DriverConflictEntity(
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

@Entity(tableName = "driver_invalidation")
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
data class DriverInvalidationEntity(
    @PrimaryKey val eventId: String,
    val userId: String,
    val revision: Long,
    val type: String,
    val entryId: String?,
    val occurredAt: String,
)
