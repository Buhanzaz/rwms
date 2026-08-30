package dev.buhanzaz.rwms.driver.core.database

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
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
data class PendingDriverAction(
    val operationId: String,
    val action: String,
    val expectedVersion: Long,
    val driverGroupId: String?,
    val evidenceId: String? = null,
    val occurredAt: String,
    val offlineLeaseId: String,
)

/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
 */
data class OptimisticAction(
    val userId: String,
    val entryId: String,
    val statusAfterAction: String,
    val payload: PendingDriverAction,
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

/** Encrypted, replay-safe Driver Shift command independent from Retrofit transport classes. */
@Serializable
data class PendingShiftCommand(
    val operationId: String,
    val shiftId: String,
    val action: String,
    val expectedVersion: Long,
    val clientCompletedAt: String? = null,
    val itemId: String? = null,
    val expectedItemVersion: Long? = null,
    val result: String? = null,
    val defectId: String? = null,
    val defectDescription: String? = null,
    val confirmationType: String? = null,
    val vehicleCondition: String? = null,
    val endOdometer: Long? = null,
    val fuelLevelPercent: Int? = null,
    val confirmSuspiciousOdometer: Boolean = false,
    val clientReferenceId: String? = null,
    val evidenceId: String? = null,
    val photoRole: String? = null,
    val inspectionItemId: String? = null,
    val capturedAt: String? = null,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
)

/**
 * Defines account-scoped driver local recovery state. Room is a client projection, never the backend source of truth.
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
 * This is the only write entry point for driver commands. It keeps the
 * optimistic projection and durable encrypted outbox atomically consistent.
 */
@Singleton
class DriverLocalStore @Inject constructor(
    private val database: DriverDatabase,
    private val pendingPayloadCipher: PendingPayloadCodec,
    private val json: Json,
) {
    fun observeTasks(userId: String): Flow<List<DriverTaskEntity>> = database.taskDao().observeTasks(userId)

    fun observeCategories(userId: String): Flow<List<DriverCategoryEntity>> =
        database.categoryDao().observeCategories(userId)

    fun observeSession(userId: String): Flow<DriverSessionEntity?> = database.sessionDao().observeSession(userId)

    fun observeGroups(userId: String): Flow<List<DriverGroupEntity>> = database.groupDao().observeGroups(userId)

    fun observeAssignments(userId: String, entryId: String): Flow<List<DriverAssignmentEntity>> =
        database.assignmentDao().observeForEntry(userId, entryId)

    fun observeAssignments(userId: String): Flow<List<DriverAssignmentEntity>> =
        database.assignmentDao().observeAll(userId)

    fun observeDetail(userId: String, entryId: String): Flow<DriverTaskDetailEntity?> =
        database.detailDao().observeDetail(userId, entryId)

    fun observeProgress(userId: String): Flow<DriverSyncProgressEntity?> = database.syncProgressDao().observe(userId)

    fun observeEvidence(userId: String): Flow<List<TaskEvidenceEntity>> = database.evidenceDao().observeAll(userId)

    fun observeShiftSnapshot(userId: String): Flow<DriverShiftSnapshotEntity?> =
        database.shiftSnapshotDao().observe(userId)

    fun observeShiftDraft(userId: String, shiftId: String): Flow<DriverShiftDraftEntity?> =
        database.shiftDraftDao().observe(userId, shiftId)

    fun observeShiftEvidence(userId: String, shiftId: String): Flow<List<TaskEvidenceEntity>> =
        database.evidenceDao().observeShiftEvidence(userId, shiftId)

    fun observePendingOutbox(userId: String): Flow<List<DriverOutboxEntity>> =
        database.outboxDao().observePending(userId)

    fun observeConflicts(userId: String): Flow<List<DriverConflictEntity>> = database.conflictDao().observeOpen(userId)

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
                DriverOutboxEntity(
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
            if (
                action.statusAfterAction == STATUS_IN_PROGRESS &&
                action.payload.action in ACTIONS_STARTING_WORK
            ) {
                recoverRejectedEvidenceAfterWorkStarted(action, now)
            }
        }
    }

    /**
     * Recreates only reservation effects rejected before TAKE/RESUME reached the server. The
     * original capture time and stable identities are retained, while ACTION is ordered first.
     */
    private suspend fun recoverRejectedEvidenceAfterWorkStarted(
        action: OptimisticAction,
        actionCreatedAtEpochMillis: Long,
    ) {
        val session = requireNotNull(database.sessionDao().session(action.userId)) {
            "Сначала обновите данные задания"
        }
        val leaseId = session.leaseId ?: return
        val leaseExpiresAt = session.leaseExpiresAtEpochMillis ?: return
        val leaseIssuedAt = leaseExpiresAt - OFFLINE_LEASE_DURATION_MILLIS
        database.evidenceDao()
            .recoverableReviewEvidence(action.userId, action.entryId)
            .filter { evidence -> evidence.isValidForReservationRecovery(leaseIssuedAt, leaseExpiresAt) }
            .forEachIndexed { index, evidence ->
                if (database.outboxDao().operationCount(evidence.reservationOperationId) != 0) {
                    return@forEachIndexed
                }
                val reservationCreatedAt = actionCreatedAtEpochMillis + index + 1L
                val payload = PendingEvidenceReservation(
                    operationId = evidence.reservationOperationId,
                    evidenceId = evidence.evidenceId,
                    routeIndex = evidence.routeIndex,
                    capturedAt = evidence.capturedAt,
                    offlineLeaseId = leaseId,
                    contentType = evidence.contentType,
                    sizeBytes = evidence.sizeBytes,
                    sha256 = evidence.sha256,
                )
                database.outboxDao().insert(
                    DriverOutboxEntity(
                        operationId = evidence.reservationOperationId,
                        userId = action.userId,
                        entryId = action.entryId,
                        kind = OUTBOX_EVIDENCE_RESERVATION,
                        encryptedPayload = pendingPayloadCipher.encrypt(json.encodeToString(payload)),
                        expectedVersion = null,
                        state = OUTBOX_PENDING,
                        retryCount = 0,
                        createdAtEpochMillis = reservationCreatedAt,
                        updatedAtEpochMillis = reservationCreatedAt,
                        lastError = null,
                    ),
                )
                check(
                    database.evidenceDao().markReservationRecovered(
                        evidenceId = evidence.evidenceId,
                        userId = action.userId,
                        entryId = action.entryId,
                        now = reservationCreatedAt,
                    ) == 1,
                ) { "Evidence changed while its reservation was being recovered" }
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
                    contentType = "image/jpeg",
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    reservationOperationId = operationId,
                    // media-service binds driver uploads to clientReferenceId;
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
                ),
            )
            database.outboxDao().insert(
                DriverOutboxEntity(
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

    suspend fun pendingOutbox(userId: String): List<DriverOutboxEntity> = database.outboxDao().pending(userId)

    suspend fun cachedShiftSnapshot(userId: String): DriverShiftSnapshotEntity? =
        database.shiftSnapshotDao().snapshot(userId)

    /** Replaces only the cached server projection; pending commands remain durable and visible. */
    suspend fun saveShiftSnapshot(snapshot: DriverShiftSnapshotEntity) =
        database.shiftSnapshotDao().upsert(snapshot)

    /** Atomically saves an optimistic resume projection and its encrypted state-machine command. */
    suspend fun enqueueShiftCommand(
        userId: String,
        command: PendingShiftCommand,
        optimisticSnapshot: DriverShiftSnapshotEntity,
    ) {
        require(command.shiftId == optimisticSnapshot.shiftId) { "Shift command and snapshot must match" }
        val now = System.currentTimeMillis()
        database.withTransaction {
            database.outboxDao().insert(
                DriverOutboxEntity(
                    operationId = command.operationId,
                    userId = userId,
                    entryId = command.shiftId,
                    kind = OUTBOX_SHIFT_COMMAND,
                    encryptedPayload = pendingPayloadCipher.encrypt(json.encodeToString(command)),
                    expectedVersion = command.expectedVersion,
                    state = OUTBOX_PENDING,
                    retryCount = 0,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    lastError = null,
                ),
            )
            database.shiftSnapshotDao().upsert(optimisticSnapshot.copy(updatedAtEpochMillis = now))
        }
    }

    /**
     * Persists an encrypted JPEG, its shift reservation and the optimistic photo projection in one
     * Room transaction. The caller deletes the encrypted file if this transaction fails.
     */
    suspend fun enqueueShiftPhotoReservation(
        userId: String,
        command: PendingShiftCommand,
        optimisticSnapshot: DriverShiftSnapshotEntity,
        encryptedFilePath: String,
        fileName: String,
    ) {
        requireActiveLease(userId)
        val evidenceId = requireNotNull(command.evidenceId)
        require(evidenceId == command.clientReferenceId) { "Shift media identity must be stable" }
        require(evidenceId == command.operationId) { "Shift reservation idempotency must equal evidence identity" }
        val capturedAt = requireNotNull(command.capturedAt)
        val contentType = requireNotNull(command.contentType)
        val sizeBytes = requireNotNull(command.sizeBytes)
        val sha256 = requireNotNull(command.sha256)
        val now = System.currentTimeMillis()
        database.withTransaction {
            database.evidenceDao().upsert(
                TaskEvidenceEntity(
                    evidenceId = evidenceId,
                    userId = userId,
                    entryId = command.shiftId,
                    routeIndex = 0,
                    capturedAt = capturedAt,
                    encryptedFilePath = encryptedFilePath,
                    fileName = fileName,
                    contentType = contentType,
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    reservationOperationId = evidenceId,
                    uploadOperationId = stableMediaUploadOperationId(evidenceId),
                    state = EVIDENCE_CAPTURED,
                    mediaId = null,
                    mediaGeneration = null,
                    reviewReason = null,
                    uploadPercent = 0,
                    lastError = null,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    ownerType = EVIDENCE_OWNER_DRIVER_SHIFT,
                    mediaContext = EVIDENCE_CONTEXT_SHIFT,
                    photoRole = command.photoRole,
                    defectId = command.defectId,
                    inspectionItemId = command.inspectionItemId,
                ),
            )
            database.outboxDao().insert(
                DriverOutboxEntity(
                    operationId = command.operationId,
                    userId = userId,
                    entryId = command.shiftId,
                    kind = OUTBOX_SHIFT_COMMAND,
                    encryptedPayload = pendingPayloadCipher.encrypt(json.encodeToString(command)),
                    expectedVersion = command.expectedVersion,
                    state = OUTBOX_PENDING,
                    retryCount = 0,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    lastError = null,
                ),
            )
            database.shiftSnapshotDao().upsert(optimisticSnapshot.copy(updatedAtEpochMillis = now))
        }
    }

    suspend fun saveShiftDraft(draft: DriverShiftDraftEntity) = database.shiftDraftDao().upsert(draft)

    /** Creates the closing draft once without overwriting input entered by a concurrent UI event. */
    suspend fun ensureShiftDraft(draft: DriverShiftDraftEntity) =
        database.shiftDraftDao().insertIfAbsent(draft)

    suspend fun shiftDraft(userId: String, shiftId: String): DriverShiftDraftEntity? =
        database.shiftDraftDao().draft(userId, shiftId)

    suspend fun clearShiftDraft(userId: String, shiftId: String) =
        database.shiftDraftDao().delete(userId, shiftId)

    fun decryptOutboxPayload(operation: DriverOutboxEntity): String =
        pendingPayloadCipher.decrypt(operation.encryptedPayload)

    suspend fun markOutboxRetry(operation: DriverOutboxEntity, error: String) {
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
            DriverConflictEntity(
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

    fun decryptConflictCurrentEntry(conflict: DriverConflictEntity): String? =
        conflict.encryptedCurrentEntry?.let(pendingPayloadCipher::decrypt)

    suspend fun updateProgress(progress: DriverSyncProgressEntity) = database.syncProgressDao().upsert(progress)

    suspend fun setSession(session: DriverSessionEntity) = database.sessionDao().upsert(session)

    suspend fun leaseFor(userId: String): ServerTimeAnchor? = database.sessionDao().session(userId)?.let { session ->
        val server = session.serverEpochMillis ?: return@let null
        val elapsed = session.elapsedRealtimeAtSyncMillis ?: return@let null
        val expires = session.leaseExpiresAtEpochMillis ?: return@let null
        ServerTimeAnchor(server, elapsed, expires)
    }

    suspend fun currentLeaseId(userId: String): String? = database.sessionDao().session(userId)?.leaseId

    suspend fun cachedSession(userId: String): DriverSessionEntity? = database.sessionDao().session(userId)

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
        const val OUTBOX_SHIFT_COMMAND = "SHIFT_COMMAND"
        const val OUTBOX_PENDING = "PENDING"
        const val OUTBOX_RETRY = "RETRY"
        const val EVIDENCE_CAPTURED = "CAPTURED"
        const val EVIDENCE_OWNER_DRIVER_SHIFT = "DRIVER_SHIFT"
        const val EVIDENCE_CONTEXT_SHIFT = "SHIFT_EVIDENCE"

        private const val STATUS_IN_PROGRESS = "IN_PROGRESS"
        private const val OFFLINE_LEASE_DURATION_MILLIS = 24L * 60L * 60L * 1_000L
        private val ACTIONS_STARTING_WORK = setOf("TAKE", "RESUME")
    }
}

/** Returns whether persisted metadata can safely reproduce the original reservation request. */
private fun TaskEvidenceEntity.isValidForReservationRecovery(
    leaseIssuedAtEpochMillis: Long,
    leaseExpiresAtEpochMillis: Long,
): Boolean {
    val capturedAtEpochMillis = runCatching { Instant.parse(capturedAt).toEpochMilli() }.getOrNull() ?: return false
    return capturedAtEpochMillis in leaseIssuedAtEpochMillis..leaseExpiresAtEpochMillis &&
        reviewReason == PRE_ACTIVATION_EVIDENCE_REJECTION &&
        routeIndex >= 0 &&
        encryptedFilePath.isNotBlank() &&
        fileName.isNotBlank() &&
        contentType == "image/jpeg" &&
        sizeBytes in 1..MAX_RECOVERABLE_EVIDENCE_BYTES &&
        RECOVERABLE_EVIDENCE_SHA_256.matches(sha256) &&
        runCatching { UUID.fromString(evidenceId) }.isSuccess &&
        reservationOperationId == evidenceId &&
        uploadOperationId == stableMediaUploadOperationId(evidenceId)
}

private const val PRE_ACTIVATION_EVIDENCE_REJECTION =
    "Добавить новую фотографию можно только к заданию в работе"
private const val MAX_RECOVERABLE_EVIDENCE_BYTES = 15L * 1_024L * 1_024L
private val RECOVERABLE_EVIDENCE_SHA_256 = Regex("^[0-9a-f]{64}$")

/** The media API binds the driver upload idempotency key to clientReferenceId. */
internal fun stableMediaUploadOperationId(evidenceId: String): String =
    UUID.fromString(evidenceId).toString()
