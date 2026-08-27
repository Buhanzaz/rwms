package dev.buhanzaz.rwms.worker.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
interface WorkerSessionDao {
    @Upsert
    suspend fun upsert(session: WorkerSessionEntity)

    @Query("SELECT * FROM worker_session WHERE userId = :userId")
    suspend fun session(userId: String): WorkerSessionEntity?

    @Query("SELECT * FROM worker_session WHERE userId = :userId")
    fun observeSession(userId: String): Flow<WorkerSessionEntity?>

    @Query("UPDATE worker_session SET cacheHidden = :hidden WHERE userId = :userId")
    suspend fun setCacheHidden(userId: String, hidden: Boolean)
}

@Dao
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
interface WorkerGroupDao {
    @Upsert
    suspend fun upsertAll(groups: List<WorkerGroupEntity>)

    @Query("DELETE FROM worker_group WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)

    @Query("SELECT * FROM worker_group WHERE userId = :userId ORDER BY name")
    fun observeGroups(userId: String): Flow<List<WorkerGroupEntity>>
}

@Dao
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
interface WorkerCategoryDao {
    @Upsert
    suspend fun upsertAll(categories: List<WorkerCategoryEntity>)

    @Query("SELECT * FROM worker_category WHERE userId = :userId ORDER BY sortOrder, queueId")
    fun observeCategories(userId: String): Flow<List<WorkerCategoryEntity>>

    @Query("SELECT * FROM worker_category WHERE userId = :userId ORDER BY sortOrder, queueId")
    suspend fun categories(userId: String): List<WorkerCategoryEntity>

    @Query("DELETE FROM worker_category WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)
}

@Dao
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
interface WorkerTaskDao {
    @Upsert
    suspend fun upsertAll(tasks: List<WorkerTaskEntity>)

    @Query("SELECT * FROM worker_task WHERE userId = :userId ORDER BY categorySortOrder, queuePosition, priority DESC")
    fun observeTasks(userId: String): Flow<List<WorkerTaskEntity>>

    @Query("SELECT * FROM worker_task WHERE userId = :userId AND entryId = :entryId LIMIT 1")
    suspend fun task(userId: String, entryId: String): WorkerTaskEntity?

    @Query("SELECT * FROM worker_task WHERE userId = :userId")
    suspend fun tasks(userId: String): List<WorkerTaskEntity>

    @Query("UPDATE worker_task SET status = :status, version = :optimisticVersion, locallyPending = 1, updatedAtEpochMillis = :now WHERE userId = :userId AND entryId = :entryId")
    suspend fun markOptimistic(userId: String, entryId: String, status: String, optimisticVersion: Long, now: Long)

    @Query("DELETE FROM worker_task WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)

    @Query("DELETE FROM worker_task WHERE userId = :userId AND entryId = :entryId")
    suspend fun deleteForEntry(userId: String, entryId: String)
}

@Dao
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
interface WorkerAssignmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(assignments: List<WorkerAssignmentEntity>)

    @Query("SELECT * FROM worker_assignment WHERE userId = :userId AND entryId = :entryId ORDER BY assignedAt")
    fun observeForEntry(userId: String, entryId: String): Flow<List<WorkerAssignmentEntity>>

    @Query("SELECT * FROM worker_assignment WHERE userId = :userId ORDER BY entryId, assignedAt")
    fun observeAll(userId: String): Flow<List<WorkerAssignmentEntity>>

    @Query("DELETE FROM worker_assignment WHERE userId = :userId AND entryId = :entryId")
    suspend fun deleteForEntry(userId: String, entryId: String)

    @Query("DELETE FROM worker_assignment WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)
}

@Dao
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
interface WorkerTaskDetailDao {
    @Upsert
    suspend fun upsert(detail: WorkerTaskDetailEntity)

    @Query("SELECT * FROM worker_task_detail WHERE userId = :userId AND entryId = :entryId LIMIT 1")
    fun observeDetail(userId: String, entryId: String): Flow<WorkerTaskDetailEntity?>

    @Query("SELECT * FROM worker_task_detail WHERE userId = :userId AND entryId = :entryId LIMIT 1")
    suspend fun detail(userId: String, entryId: String): WorkerTaskDetailEntity?

    @Query("DELETE FROM worker_task_detail WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)

    @Query("DELETE FROM worker_task_detail WHERE userId = :userId AND entryId = :entryId")
    suspend fun deleteForEntry(userId: String, entryId: String)

    @Query("DELETE FROM worker_task_detail WHERE userId = :userId AND entryId NOT IN (:visibleEntryIds)")
    suspend fun deleteNotVisible(userId: String, visibleEntryIds: List<String>)
}

@Dao
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
interface WorkerOutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(operation: WorkerOutboxEntity)

    @Query("SELECT * FROM worker_outbox WHERE userId = :userId AND state IN ('PENDING', 'RETRY') ORDER BY createdAtEpochMillis ASC")
    suspend fun pending(userId: String): List<WorkerOutboxEntity>

    @Query("SELECT * FROM worker_outbox WHERE userId = :userId AND state IN ('PENDING', 'RETRY') ORDER BY createdAtEpochMillis ASC")
    fun observePending(userId: String): Flow<List<WorkerOutboxEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM worker_outbox
        WHERE userId = :userId
          AND entryId = :entryId
          AND kind = 'ACTION'
          AND state IN ('PENDING', 'RETRY')
        """,
    )
    suspend fun pendingActionCount(userId: String, entryId: String): Int

    @Query("UPDATE worker_outbox SET state = :state, retryCount = :retryCount, lastError = :lastError, updatedAtEpochMillis = :now WHERE operationId = :operationId")
    suspend fun updateState(operationId: String, state: String, retryCount: Int, lastError: String?, now: Long)

    /** Rebuilds one retained evidence reservation with the current authenticated lease. */
    @Query(
        """
        UPDATE worker_outbox
        SET encryptedPayload = :encryptedPayload,
            state = 'PENDING',
            retryCount = 0,
            lastError = NULL,
            updatedAtEpochMillis = :now
        WHERE operationId = :operationId
          AND userId = :userId
          AND entryId = :entryId
          AND kind = 'EVIDENCE_RESERVATION'
        """,
    )
    suspend fun recoverEvidenceReservation(
        operationId: String,
        userId: String,
        entryId: String,
        encryptedPayload: String,
        now: Long,
    ): Int

    @Query("DELETE FROM worker_outbox WHERE operationId = :operationId")
    suspend fun delete(operationId: String)
}

@Dao
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
interface TaskEvidenceDao {
    @Upsert
    suspend fun upsert(evidence: TaskEvidenceEntity)

    @Query("SELECT * FROM task_evidence WHERE userId = :userId AND state IN ('CAPTURED', 'RESERVED', 'UPLOADING', 'PROCESSING') ORDER BY createdAtEpochMillis")
    suspend fun pending(userId: String): List<TaskEvidenceEntity>

    @Query("SELECT * FROM task_evidence WHERE evidenceId = :evidenceId AND userId = :userId LIMIT 1")
    suspend fun evidence(userId: String, evidenceId: String): TaskEvidenceEntity?

    /** Returns only deterministic pre-fix offline-lease failures eligible for safe recovery. */
    @Query(
        """
        SELECT * FROM task_evidence
        WHERE userId = :userId
          AND state = 'REVIEW_REQUIRED'
          AND mediaId IS NULL
          AND (reviewReason = :reviewReason OR lastError = :reviewReason)
        ORDER BY createdAtEpochMillis ASC
        """,
    )
    suspend fun reviewRequiredByReason(userId: String, reviewReason: String): List<TaskEvidenceEntity>

    @Query("SELECT evidenceId FROM task_evidence WHERE userId = :userId AND entryId = :entryId AND state = 'READY'")
    suspend fun readyIds(userId: String, entryId: String): List<String>

    @Query("SELECT * FROM task_evidence WHERE userId = :userId ORDER BY createdAtEpochMillis DESC")
    fun observeAll(userId: String): Flow<List<TaskEvidenceEntity>>

    @Query("UPDATE task_evidence SET state = :state, mediaId = :mediaId, mediaGeneration = :generation, reviewReason = :reviewReason, updatedAtEpochMillis = :now WHERE evidenceId = :evidenceId")
    suspend fun updateState(evidenceId: String, state: String, mediaId: String?, generation: Long?, reviewReason: String?, now: Long)

    @Query("UPDATE task_evidence SET uploadPercent = :percent, lastError = :error, updatedAtEpochMillis = :now WHERE evidenceId = :evidenceId")
    suspend fun updateUploadProgress(evidenceId: String, percent: Int, error: String?, now: Long)

    @Query("UPDATE task_evidence SET lastError = :error, updatedAtEpochMillis = :now WHERE evidenceId = :evidenceId")
    suspend fun updateUploadError(evidenceId: String, error: String, now: Long)

    /** Archives an unattachable local photo without deleting its encrypted bytes. */
    @Query(
        """
        UPDATE task_evidence
        SET state = 'SUPERSEDED',
            mediaId = NULL,
            mediaGeneration = NULL,
            reviewReason = :reason,
            uploadPercent = 0,
            lastError = NULL,
            updatedAtEpochMillis = :now
        WHERE evidenceId = :evidenceId
          AND userId = :userId
        """,
    )
    suspend fun markSuperseded(userId: String, evidenceId: String, reason: String, now: Long): Int
}

@Dao
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
interface WorkerSyncProgressDao {
    @Upsert
    suspend fun upsert(progress: WorkerSyncProgressEntity)

    @Query("SELECT * FROM worker_sync_progress WHERE userId = :userId")
    fun observe(userId: String): Flow<WorkerSyncProgressEntity?>
}

@Dao
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
interface WorkerConflictDao {
    @Upsert
    suspend fun upsert(conflict: WorkerConflictEntity)

    @Query("SELECT * FROM worker_conflict WHERE userId = :userId AND resolvedAtEpochMillis IS NULL ORDER BY createdAtEpochMillis DESC")
    fun observeOpen(userId: String): Flow<List<WorkerConflictEntity>>

    @Query("UPDATE worker_conflict SET resolvedAtEpochMillis = :now WHERE operationId = :operationId")
    suspend fun resolve(operationId: String, now: Long)

    @Query(
        """
        UPDATE worker_conflict
        SET resolvedAtEpochMillis = :now
        WHERE userId = :userId AND resolvedAtEpochMillis IS NULL
        """,
    )
    suspend fun resolveOpenForUser(userId: String, now: Long): Int
}

@Dao
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
interface WorkerInvalidationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: WorkerInvalidationEntity): Long

    @Query("SELECT MAX(revision) FROM worker_invalidation WHERE userId = :userId")
    suspend fun latestRevision(userId: String): Long?

    @Query("SELECT eventId FROM worker_invalidation WHERE userId = :userId ORDER BY revision DESC LIMIT 1")
    suspend fun latestEventId(userId: String): String?
}
