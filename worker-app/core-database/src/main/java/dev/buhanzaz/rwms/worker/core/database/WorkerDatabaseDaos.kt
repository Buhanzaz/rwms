package dev.buhanzaz.rwms.worker.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
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
interface WorkerGroupDao {
    @Upsert
    suspend fun upsertAll(groups: List<WorkerGroupEntity>)

    @Query("DELETE FROM worker_group WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)

    @Query("SELECT * FROM worker_group WHERE userId = :userId ORDER BY name")
    fun observeGroups(userId: String): Flow<List<WorkerGroupEntity>>
}

@Dao
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
interface WorkerAssignmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(assignments: List<WorkerAssignmentEntity>)

    @Query("SELECT * FROM worker_assignment WHERE userId = :userId AND entryId = :entryId ORDER BY assignedAt")
    fun observeForEntry(userId: String, entryId: String): Flow<List<WorkerAssignmentEntity>>

    @Query("DELETE FROM worker_assignment WHERE userId = :userId AND entryId = :entryId")
    suspend fun deleteForEntry(userId: String, entryId: String)

    @Query("DELETE FROM worker_assignment WHERE userId = :userId")
    suspend fun deleteForUser(userId: String)
}

@Dao
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

    @Query("DELETE FROM worker_outbox WHERE operationId = :operationId")
    suspend fun delete(operationId: String)
}

@Dao
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
interface WorkerSyncProgressDao {
    @Upsert
    suspend fun upsert(progress: WorkerSyncProgressEntity)

    @Query("SELECT * FROM worker_sync_progress WHERE userId = :userId")
    fun observe(userId: String): Flow<WorkerSyncProgressEntity?>
}

@Dao
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
interface WorkerInvalidationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: WorkerInvalidationEntity): Long

    @Query("SELECT MAX(revision) FROM worker_invalidation WHERE userId = :userId")
    suspend fun latestRevision(userId: String): Long?

    @Query("SELECT eventId FROM worker_invalidation WHERE userId = :userId ORDER BY revision DESC LIMIT 1")
    suspend fun latestEventId(userId: String): String?
}
