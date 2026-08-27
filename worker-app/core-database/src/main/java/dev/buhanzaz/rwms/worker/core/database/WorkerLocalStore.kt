package dev.buhanzaz.rwms.worker.core.database

import android.os.SystemClock
import androidx.room.withTransaction
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
data class PendingWorkerAction(
    val operationId: String,
    val action: String,
    val expectedVersion: Long,
    val workerGroupId: String?,
    val evidenceId: String? = null,
    val occurredAt: String,
    val offlineLeaseId: String,
)

/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
data class OptimisticAction(
    val userId: String,
    val entryId: String,
    val statusAfterAction: String,
    val payload: PendingWorkerAction,
)

/**
 * Kept in the encrypted outbox rather than as a plain transport DTO so this
 * module stays independent from Retrofit. The server derives actor metadata
 * from the bearer token; none is persisted here.
 */
@Serializable
data class PendingEvidenceReservation(
    val operationId: String,
    val evidenceId: String,
    val routeIndex: Int,
    val capturedAt: String,
    val offlineLeaseId: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * Defines account-scoped worker local recovery state. Room is a client projection, never the backend source of truth.
 */
data class ServerTimeAnchor(
    val serverEpochMillis: Long,
    val elapsedRealtimeAtSyncMillis: Long,
    val leaseExpiresAtEpochMillis: Long,
) {
    fun estimatedServerNow(elapsedRealtimeMillis: Long): Long =
        serverEpochMillis + (elapsedRealtimeMillis - elapsedRealtimeAtSyncMillis)

    fun isLeaseActive(elapsedRealtimeMillis: Long): Boolean =
        // elapsedRealtime resets on reboot. A lower value would otherwise move
        // server time backwards and incorrectly extend the offline lease.
        elapsedRealtimeMillis >= elapsedRealtimeAtSyncMillis &&
            estimatedServerNow(elapsedRealtimeMillis) <= leaseExpiresAtEpochMillis
}

/**
 * This is the only write entry point for worker commands. It keeps the
 * optimistic projection and durable encrypted outbox atomically consistent.
 */
@Singleton
class WorkerLocalStore @Inject constructor(
    private val database: WorkerDatabase,
    private val pendingPayloadCipher: PendingPayloadCipher,
    private val json: Json,
) {
    fun observeTasks(userId: String): Flow<List<WorkerTaskEntity>> = database.taskDao().observeTasks(userId)

    fun observeCategories(userId: String): Flow<List<WorkerCategoryEntity>> =
        database.categoryDao().observeCategories(userId)

    fun observeSession(userId: String): Flow<WorkerSessionEntity?> = database.sessionDao().observeSession(userId)

    fun observeGroups(userId: String): Flow<List<WorkerGroupEntity>> = database.groupDao().observeGroups(userId)

    fun observeAssignments(userId: String, entryId: String): Flow<List<WorkerAssignmentEntity>> =
        database.assignmentDao().observeForEntry(userId, entryId)

    fun observeAssignments(userId: String): Flow<List<WorkerAssignmentEntity>> =
        database.assignmentDao().observeAll(userId)

    fun observeDetail(userId: String, entryId: String): Flow<WorkerTaskDetailEntity?> =
        database.detailDao().observeDetail(userId, entryId)

    fun observeProgress(userId: String): Flow<WorkerSyncProgressEntity?> = database.syncProgressDao().observe(userId)

    fun observeEvidence(userId: String): Flow<List<TaskEvidenceEntity>> = database.evidenceDao().observeAll(userId)

    fun observePendingOutbox(userId: String): Flow<List<WorkerOutboxEntity>> =
        database.outboxDao().observePending(userId)

    fun observeConflicts(userId: String): Flow<List<WorkerConflictEntity>> = database.conflictDao().observeOpen(userId)

    /**
     * Applies one fenced optimistic task state and its encrypted outbox command atomically.
     * A stale cached version or another pending action for the same task is rejected locally.
     */
    suspend fun applyOptimisticAction(action: OptimisticAction) {
        requireActiveLease(action.userId)
        val now = System.currentTimeMillis()
        database.withTransaction {
            check(database.outboxDao().pendingActionCount(action.userId, action.entryId) == 0) {
                "Действие по этому заданию уже ожидает синхронизации"
            }
            val task = requireNotNull(database.taskDao().task(action.userId, action.entryId)) {
                "Cannot queue an action for a task outside the current user cache"
            }
            require(task.version == action.payload.expectedVersion) {
                "The task changed before the action could be queued"
            }
            database.taskDao().markOptimistic(
                userId = action.userId,
                entryId = action.entryId,
                status = action.statusAfterAction,
                optimisticVersion = task.version + 1,
                now = now,
            )
            database.outboxDao().insert(
                WorkerOutboxEntity(
                    operationId = action.payload.operationId,
                    userId = action.userId,
                    entryId = action.entryId,
                    kind = OUTBOX_ACTION,
                    encryptedPayload = pendingPayloadCipher.encrypt(json.encodeToString(action.payload)),
                    expectedVersion = action.payload.expectedVersion,
                    state = OUTBOX_PENDING,
                    retryCount = 0,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    lastError = null,
                ),
            )
        }
    }

    /**
     * Atomically records the encrypted evidence row and its reservation outbox effect under the
     * active offline lease, preserving a stable operation ID for retry after process death.
     */
    suspend fun enqueueEvidenceReservation(
        userId: String,
        entryId: String,
        evidenceId: String,
        encryptedFilePath: String,
        fileName: String,
        routeIndex: Int,
        capturedAt: String,
        sizeBytes: Long,
        sha256: String,
        variantManifestJson: String,
        reservationPayload: String,
    ) {
        requireActiveLease(userId)
        val now = System.currentTimeMillis()
        val operationId = UUID.fromString(evidenceId).toString()
        database.withTransaction {
            database.evidenceDao().upsert(
                TaskEvidenceEntity(
                    evidenceId = evidenceId,
                    userId = userId,
                    entryId = entryId,
                    routeIndex = routeIndex,
                    capturedAt = capturedAt,
                    encryptedFilePath = encryptedFilePath,
                    fileName = fileName,
                    contentType = "image/webp",
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    reservationOperationId = operationId,
                    // media-service binds worker uploads to clientReferenceId;
                    // its idempotency key must be this stable evidence ID.
                    uploadOperationId = stableMediaUploadOperationId(evidenceId),
                    state = EVIDENCE_CAPTURED,
                    mediaId = null,
                    mediaGeneration = null,
                    reviewReason = null,
                    uploadPercent = 0,
                    lastError = null,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    variantManifestJson = variantManifestJson,
                ),
            )
            database.outboxDao().insert(
                WorkerOutboxEntity(
                    operationId = operationId,
                    userId = userId,
                    entryId = entryId,
                    kind = OUTBOX_EVIDENCE_RESERVATION,
                    encryptedPayload = pendingPayloadCipher.encrypt(reservationPayload),
                    expectedVersion = null,
                    state = OUTBOX_PENDING,
                    retryCount = 0,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    lastError = null,
                ),
            )
        }
    }

    /**
     * Restores only result-photo reservations rejected by the former strict offline-lease limit.
     *
     * <p>The caller has just obtained a new authenticated server context. This keeps the original
     * evidence ID, capture time and bytes, but re-encrypts the deterministic reservation with that
     * fresh lease so an already-assigned worker can finish an overdue task. Other review-required
     * evidence is deliberately left for an explicit recovery decision.
     */
    suspend fun requeueExpiredLeaseEvidenceReservations(userId: String, freshLeaseId: String): Int {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            var restored = 0
            for (evidence in database.evidenceDao().reviewRequiredByReason(userId, EXPIRED_OFFLINE_LEASE_ERROR)) {
                val payload = PendingEvidenceReservation(
                    operationId = evidence.reservationOperationId,
                    evidenceId = evidence.evidenceId,
                    routeIndex = evidence.routeIndex,
                    capturedAt = evidence.capturedAt,
                    offlineLeaseId = freshLeaseId,
                    contentType = evidence.contentType,
                    sizeBytes = evidence.sizeBytes,
                    sha256 = evidence.sha256,
                )
                database.evidenceDao().upsert(
                    evidence.copy(
                        state = EVIDENCE_CAPTURED,
                        reviewReason = null,
                        uploadPercent = 0,
                        lastError = null,
                        updatedAtEpochMillis = now,
                    ),
                )
                val encryptedPayload = pendingPayloadCipher.encrypt(json.encodeToString(payload))
                val recovered = database.outboxDao().recoverEvidenceReservation(
                    operationId = evidence.reservationOperationId,
                    userId = userId,
                    entryId = evidence.entryId,
                    encryptedPayload = encryptedPayload,
                    now = now,
                )
                if (recovered == 0) {
                    database.outboxDao().insert(
                        WorkerOutboxEntity(
                            operationId = evidence.reservationOperationId,
                            userId = userId,
                            entryId = evidence.entryId,
                            kind = OUTBOX_EVIDENCE_RESERVATION,
                            encryptedPayload = encryptedPayload,
                            expectedVersion = null,
                            state = OUTBOX_PENDING,
                            retryCount = 0,
                            createdAtEpochMillis = now,
                            updatedAtEpochMillis = now,
                            lastError = null,
                        ),
                    )
                }
                restored += 1
            }
            restored
        }
    }

    /**
     * Stops replay after the server proves that the owning task is terminal. The encrypted local
     * photo and its metadata remain intact for recovery; only the unfulfillable outbox effect is
     * completed and the row is removed from active upload projections.
     */
    suspend fun supersedeTerminalTaskEvidence(
        userId: String,
        evidenceId: String,
        reservationOperationId: String,
        reason: String,
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            check(database.evidenceDao().markSuperseded(userId, evidenceId, reason, now) == 1) {
                "Terminal task evidence is missing from the current account"
            }
            database.outboxDao().delete(reservationOperationId)
        }
    }

    suspend fun pendingOutbox(userId: String): List<WorkerOutboxEntity> = database.outboxDao().pending(userId)

    fun decryptOutboxPayload(operation: WorkerOutboxEntity): String =
        pendingPayloadCipher.decrypt(operation.encryptedPayload)

    suspend fun markOutboxRetry(operation: WorkerOutboxEntity, error: String) {
        database.outboxDao().updateState(
            operation.operationId,
            OUTBOX_RETRY,
            operation.retryCount + 1,
            error,
            System.currentTimeMillis(),
        )
    }

    suspend fun markOutboxComplete(operationId: String) = database.outboxDao().delete(operationId)

    suspend fun recordConflict(
        userId: String,
        operationId: String,
        entryId: String,
        code: String,
        message: String,
        currentVersion: Long?,
        currentEntryJson: String?,
    ) {
        database.conflictDao().upsert(
            WorkerConflictEntity(
                operationId = operationId,
                userId = userId,
                entryId = entryId,
                code = code,
                message = message,
                currentVersion = currentVersion,
                encryptedCurrentEntry = currentEntryJson?.let(pendingPayloadCipher::encrypt),
                createdAtEpochMillis = System.currentTimeMillis(),
                resolvedAtEpochMillis = null,
            ),
        )
    }

    fun decryptConflictCurrentEntry(conflict: WorkerConflictEntity): String? =
        conflict.encryptedCurrentEntry?.let(pendingPayloadCipher::decrypt)

    /**
     * Records the worker's explicit acceptance of the latest authoritative
     * server state; background/full-feed refreshes never acknowledge conflicts.
     */
    suspend fun acknowledgeOpenConflicts(userId: String): Int =
        database.conflictDao().resolveOpenForUser(userId, System.currentTimeMillis())

    suspend fun updateProgress(progress: WorkerSyncProgressEntity) = database.syncProgressDao().upsert(progress)

    suspend fun setSession(session: WorkerSessionEntity) = database.sessionDao().upsert(session)

    suspend fun leaseFor(userId: String): ServerTimeAnchor? = database.sessionDao().session(userId)?.let { session ->
        val server = session.serverEpochMillis ?: return@let null
        val elapsed = session.elapsedRealtimeAtSyncMillis ?: return@let null
        val expires = session.leaseExpiresAtEpochMillis ?: return@let null
        ServerTimeAnchor(server, elapsed, expires)
    }

    suspend fun currentLeaseId(userId: String): String? = database.sessionDao().session(userId)?.leaseId

    suspend fun cachedSession(userId: String): WorkerSessionEntity? = database.sessionDao().session(userId)

    /**
     * Refuses offline mutation when elapsed-realtime cannot prove that the server-issued lease is
     * still valid; reboot/reset cannot extend the lease.
     */
    suspend fun requireActiveLease(userId: String) {
        val lease = requireNotNull(leaseFor(userId)) { "Сначала обновите данные задания" }
        require(lease.isLeaseActive(SystemClock.elapsedRealtime())) {
            "Срок офлайн-доступа истёк; подключитесь к RWMS и синхронизируйте данные"
        }
    }

    /**
     * Hides and removes only reusable projections once a lease expires. Durable
     * encrypted outbox/evidence rows deliberately remain for their original
     * user and are never exposed through another user's queries.
     */
    suspend fun hideExpiredCacheIfNeeded(userId: String, elapsedRealtimeMillis: Long = SystemClock.elapsedRealtime()) {
        val lease = leaseFor(userId) ?: return
        if (lease.isLeaseActive(elapsedRealtimeMillis)) return
        database.withTransaction {
            database.categoryDao().deleteForUser(userId)
            database.taskDao().deleteForUser(userId)
            database.assignmentDao().deleteForUser(userId)
            database.detailDao().deleteForUser(userId)
            database.sessionDao().setCacheHidden(userId, true)
        }
    }

    companion object {
        const val OUTBOX_ACTION = "ACTION"
        const val OUTBOX_EVIDENCE_RESERVATION = "EVIDENCE_RESERVATION"
        const val OUTBOX_PENDING = "PENDING"
        const val OUTBOX_RETRY = "RETRY"
        const val EVIDENCE_CAPTURED = "CAPTURED"
        const val EVIDENCE_SUPERSEDED = "SUPERSEDED"
        const val EXPIRED_OFFLINE_LEASE_ERROR = "Действие создано вне срока offline lease"
    }
}

/** The media API binds the worker upload idempotency key to clientReferenceId. */
internal fun stableMediaUploadOperationId(evidenceId: String): String =
    UUID.fromString(evidenceId).toString()
