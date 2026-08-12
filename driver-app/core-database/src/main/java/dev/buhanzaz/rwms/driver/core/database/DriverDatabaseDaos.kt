package dev.buhanzaz.rwms.driver.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
interface DriverSessionDao {
    @Upsert
    suspend fun upsert(session: DriverSessionEntity)

    @Query("SELECT * FROM driver_session WHERE userId = :userId")
    suspend fun session(userId: String): DriverSessionEntity?

    @Query("SELECT * FROM driver_session WHERE userId = :userId")
    fun observeSession(userId: String): Flow<DriverSessionEntity?>

    @Query("UPDATE driver_session SET cacheHidden = :hidden WHERE userId = :userId")
    suspend fun setCacheHidden(userId: String, hidden: Boolean)
}

@Dao
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
interface DriverGroupDao {
    @Upsert
    suspend fun upsertAll(groups: List<DriverGroupEntity>)

    @Query("DELETE FROM driver_group WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)

    @Query("SELECT * FROM driver_group WHERE userId = :userId ORDER BY name")
    fun observeGroups(userId: String): Flow<List<DriverGroupEntity>>
}

@Dao
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
interface DriverCategoryDao {
    @Upsert
    suspend fun upsertAll(categories: List<DriverCategoryEntity>)

    @Query("SELECT * FROM driver_category WHERE userId = :userId ORDER BY sortOrder, queueId")
    fun observeCategories(userId: String): Flow<List<DriverCategoryEntity>>

    @Query("SELECT * FROM driver_category WHERE userId = :userId ORDER BY sortOrder, queueId")
    suspend fun categories(userId: String): List<DriverCategoryEntity>

    @Query("DELETE FROM driver_category WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)
}

@Dao
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
interface DriverTaskDao {
    @Upsert
    suspend fun upsertAll(tasks: List<DriverTaskEntity>)

    @Query("SELECT * FROM driver_task WHERE userId = :userId ORDER BY categorySortOrder, queuePosition, priority DESC")
    fun observeTasks(userId: String): Flow<List<DriverTaskEntity>>

    @Query("SELECT * FROM driver_task WHERE userId = :userId AND entryId = :entryId LIMIT 1")
    suspend fun task(userId: String, entryId: String): DriverTaskEntity?

    @Query("SELECT * FROM driver_task WHERE userId = :userId")
    suspend fun tasks(userId: String): List<DriverTaskEntity>

    @Query("UPDATE driver_task SET status = :status, version = :optimisticVersion, locallyPending = 1, updatedAtEpochMillis = :now WHERE userId = :userId AND entryId = :entryId")
    suspend fun markOptimistic(userId: String, entryId: String, status: String, optimisticVersion: Long, now: Long)

    @Query("DELETE FROM driver_task WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)

    @Query("DELETE FROM driver_task WHERE userId = :userId AND entryId = :entryId")
    suspend fun deleteForEntry(userId: String, entryId: String)
}

@Dao
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
interface DriverAssignmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(assignments: List<DriverAssignmentEntity>)

    @Query("SELECT * FROM driver_assignment WHERE userId = :userId AND entryId = :entryId ORDER BY assignedAt")
    fun observeForEntry(userId: String, entryId: String): Flow<List<DriverAssignmentEntity>>

    @Query("SELECT * FROM driver_assignment WHERE userId = :userId ORDER BY entryId, assignedAt")
    fun observeAll(userId: String): Flow<List<DriverAssignmentEntity>>

    @Query("DELETE FROM driver_assignment WHERE userId = :userId AND entryId = :entryId")
    suspend fun deleteForEntry(userId: String, entryId: String)

    @Query("DELETE FROM driver_assignment WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)
}

@Dao
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
interface DriverTaskDetailDao {
    @Upsert
    suspend fun upsert(detail: DriverTaskDetailEntity)

    @Query("SELECT * FROM driver_task_detail WHERE userId = :userId AND entryId = :entryId LIMIT 1")
    fun observeDetail(userId: String, entryId: String): Flow<DriverTaskDetailEntity?>

    @Query("SELECT * FROM driver_task_detail WHERE userId = :userId AND entryId = :entryId LIMIT 1")
    suspend fun detail(userId: String, entryId: String): DriverTaskDetailEntity?

    @Query("DELETE FROM driver_task_detail WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)

    @Query("DELETE FROM driver_task_detail WHERE userId = :userId AND entryId = :entryId")
    suspend fun deleteForEntry(userId: String, entryId: String)

    @Query("DELETE FROM driver_task_detail WHERE userId = :userId AND entryId NOT IN (:visibleEntryIds)")
    suspend fun deleteNotVisible(userId: String, visibleEntryIds: List<String>)
}

@Dao
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
interface DriverOutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(operation: DriverOutboxEntity)

    @Query("SELECT * FROM driver_outbox WHERE userId = :userId AND state IN ('PENDING', 'RETRY') ORDER BY createdAtEpochMillis ASC")
    suspend fun pending(userId: String): List<DriverOutboxEntity>

    @Query("SELECT * FROM driver_outbox WHERE userId = :userId AND state IN ('PENDING', 'RETRY') ORDER BY createdAtEpochMillis ASC")
    fun observePending(userId: String): Flow<List<DriverOutboxEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM driver_outbox
        WHERE userId = :userId
          AND entryId = :entryId
          AND kind = 'ACTION'
          AND state IN ('PENDING', 'RETRY')
        """,
    )
    suspend fun pendingActionCount(userId: String, entryId: String): Int

    @Query("UPDATE driver_outbox SET state = :state, retryCount = :retryCount, lastError = :lastError, updatedAtEpochMillis = :now WHERE operationId = :operationId")
    suspend fun updateState(operationId: String, state: String, retryCount: Int, lastError: String?, now: Long)

    @Query("DELETE FROM driver_outbox WHERE operationId = :operationId")
    suspend fun delete(operationId: String)
}

@Dao
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
interface TaskEvidenceDao {
    @Upsert
    suspend fun upsert(evidence: TaskEvidenceEntity)

    @Query("SELECT * FROM task_evidence WHERE userId = :userId AND state NOT IN ('READY', 'REJECTED', 'REVIEW_REQUIRED') ORDER BY createdAtEpochMillis")
    suspend fun pending(userId: String): List<TaskEvidenceEntity>

    @Query("SELECT * FROM task_evidence WHERE evidenceId = :evidenceId AND userId = :userId LIMIT 1")
    suspend fun evidence(userId: String, evidenceId: String): TaskEvidenceEntity?

    @Query("SELECT COUNT(*) FROM task_evidence WHERE userId = :userId AND entryId = :entryId AND state = 'READY'")
    suspend fun readyCount(userId: String, entryId: String): Int

    @Query("SELECT * FROM task_evidence WHERE userId = :userId ORDER BY createdAtEpochMillis DESC")
    fun observeAll(userId: String): Flow<List<TaskEvidenceEntity>>

    @Query("UPDATE task_evidence SET state = :state, mediaId = :mediaId, mediaGeneration = :generation, reviewReason = :reviewReason, updatedAtEpochMillis = :now WHERE evidenceId = :evidenceId")
    suspend fun updateState(evidenceId: String, state: String, mediaId: String?, generation: Long?, reviewReason: String?, now: Long)

    @Query("UPDATE task_evidence SET uploadPercent = :percent, lastError = :error, updatedAtEpochMillis = :now WHERE evidenceId = :evidenceId")
    suspend fun updateUploadProgress(evidenceId: String, percent: Int, error: String?, now: Long)

    @Query("UPDATE task_evidence SET lastError = :error, updatedAtEpochMillis = :now WHERE evidenceId = :evidenceId")
    suspend fun updateUploadError(evidenceId: String, error: String, now: Long)
}

@Dao
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
interface DriverSyncProgressDao {
    @Upsert
    suspend fun upsert(progress: DriverSyncProgressEntity)

    @Query("SELECT * FROM driver_sync_progress WHERE userId = :userId")
    fun observe(userId: String): Flow<DriverSyncProgressEntity?>
}

@Dao
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
interface DriverConflictDao {
    @Upsert
    suspend fun upsert(conflict: DriverConflictEntity)

    @Query("SELECT * FROM driver_conflict WHERE userId = :userId AND resolvedAtEpochMillis IS NULL ORDER BY createdAtEpochMillis DESC")
    fun observeOpen(userId: String): Flow<List<DriverConflictEntity>>

    @Query("UPDATE driver_conflict SET resolvedAtEpochMillis = :now WHERE operationId = :operationId")
    suspend fun resolve(operationId: String, now: Long)

    @Query(
        """
        UPDATE driver_conflict
        SET resolvedAtEpochMillis = :now
        WHERE userId = :userId AND resolvedAtEpochMillis IS NULL
        """,
    )
    suspend fun resolveOpenForUser(userId: String, now: Long): Int
}

@Dao
/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
interface DriverInvalidationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: DriverInvalidationEntity): Long

    @Query("SELECT MAX(revision) FROM driver_invalidation WHERE userId = :userId")
    suspend fun latestRevision(userId: String): Long?

    @Query("SELECT eventId FROM driver_invalidation WHERE userId = :userId ORDER BY revision DESC LIMIT 1")
    suspend fun latestEventId(userId: String): String?
}
