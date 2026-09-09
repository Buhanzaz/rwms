package dev.buhanzaz.rwms.worker.core.database

import android.os.SystemClock
import androidx.room.withTransaction
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Immutable worker declaration persisted before report photos may be uploaded. */
@Serializable
data class PendingWorkerProblemReport(
    val operationId: String,
    val routeIndex: Int,
    val comment: String,
    val occurredAt: String,
    val offlineLeaseId: String,
    val attachments: List<PendingEvidenceReservation>,
)

/** Metadata for one encrypted bundle prepared by feature-camera before it enters a report draft. */
data class DraftProblemReportEvidenceInput(
    val evidenceId: String,
    val encryptedFilePath: String,
    val fileName: String,
    val routeIndex: Int,
    val capturedAt: String,
    val sizeBytes: Long,
    val sha256: String,
    val variantManifestJson: String,
)

/** UI-facing local draft state; paths remain on [attachments] for the owning feature to decode. */
data class WorkerProblemReportDraftSnapshot(
    val reportId: String,
    val entryId: String,
    val routeIndex: Int,
    val comment: String,
    val occurredAt: String,
    val state: String,
    val lastError: String?,
    val attachments: List<TaskEvidenceEntity>,
)

/**
 * Owns only client-durable report declaration state. Sync owns POST/GET replay and media owns
 * encrypted bundle preparation/deletion; neither concern is duplicated here.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class WorkerProblemReportStore @Inject constructor(
    private val database: WorkerDatabase,
    private val pendingPayloadCipher: PendingPayloadCipher,
    private val json: Json,
) {
    fun observeDraft(userId: String, entryId: String): Flow<WorkerProblemReportDraftSnapshot?> =
        database.outboxDao().observeEntryProblemReports(userId, entryId).flatMapLatest { reports ->
            val operation = reports.firstOrNull { it.state != OUTBOX_REPORTED }
            operation ?: return@flatMapLatest flowOf(null)
            database.evidenceDao().observeForProblemReport(userId, operation.operationId).map { attachments ->
                operation.toDraftSnapshot(attachments)
            }
        }

    fun observeEntryReports(userId: String, entryId: String): Flow<List<WorkerProblemReportDraftSnapshot>> =
        observeReportSnapshots(userId, database.outboxDao().observeEntryProblemReports(userId, entryId))

    fun observeSubmittedReports(userId: String): Flow<List<WorkerProblemReportDraftSnapshot>> =
        observeReportSnapshots(userId, database.outboxDao().observeSubmittedProblemReports(userId))

    suspend fun openOrCreateDraft(
        userId: String,
        entryId: String,
        routeIndex: Int,
    ): WorkerProblemReportDraftSnapshot {
        database.outboxDao().latestUnresolvedProblemReport(userId, entryId)?.let { existing ->
            return existing.toDraftSnapshot(database.evidenceDao().forProblemReport(userId, existing.operationId))
        }
        val lease = requireNotNull(leaseFor(userId)) { "Сначала синхронизируйте задание" }
        val leaseId = requireNotNull(database.sessionDao().session(userId)?.leaseId) {
            "Офлайн-доступ ещё не подготовлен"
        }
        val now = System.currentTimeMillis()
        val payload = PendingWorkerProblemReport(
            operationId = UUID.randomUUID().toString(),
            routeIndex = routeIndex,
            comment = "",
            occurredAt = Instant.ofEpochMilli(lease.estimatedServerNow(SystemClock.elapsedRealtime())).toString(),
            offlineLeaseId = leaseId,
            attachments = emptyList(),
        )
        val operation = WorkerOutboxEntity(
            operationId = payload.operationId,
            userId = userId,
            entryId = entryId,
            kind = OUTBOX_PROBLEM_REPORT,
            encryptedPayload = pendingPayloadCipher.encrypt(json.encodeToString(payload)),
            expectedVersion = null,
            state = OUTBOX_DRAFT,
            retryCount = 0,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            lastError = null,
        )
        return database.withTransaction {
            val selected = database.outboxDao().latestUnresolvedProblemReport(userId, entryId) ?: operation.also {
                database.outboxDao().insert(it)
            }
            selected.toDraftSnapshot(database.evidenceDao().forProblemReport(userId, selected.operationId))
        }
    }

    suspend fun updateDraftComment(userId: String, reportId: String, comment: String) {
        database.withTransaction {
            val operation = requireDraft(userId, reportId)
            val payload = operation.payload()
            updateDraft(operation, payload.copy(comment = comment))
        }
    }

    suspend fun attachPreparedPhoto(userId: String, reportId: String, input: DraftProblemReportEvidenceInput): String {
        UUID.fromString(input.evidenceId)
        database.withTransaction {
            val operation = requireDraft(userId, reportId)
            val payload = operation.payload()
            require(payload.attachments.size < MAX_ATTACHMENTS) { "К обращению можно добавить не более 10 фото" }
            require(payload.attachments.none { it.evidenceId == input.evidenceId }) { "Фотография уже добавлена" }
            val attachment = PendingEvidenceReservation(
                operationId = input.evidenceId,
                evidenceId = input.evidenceId,
                routeIndex = input.routeIndex,
                capturedAt = input.capturedAt,
                offlineLeaseId = payload.offlineLeaseId,
                contentType = "image/webp",
                sizeBytes = input.sizeBytes,
                sha256 = input.sha256,
            )
            val now = System.currentTimeMillis()
            database.evidenceDao().upsert(
                TaskEvidenceEntity(
                    evidenceId = input.evidenceId,
                    userId = userId,
                    entryId = operation.entryId,
                    routeIndex = input.routeIndex,
                    capturedAt = input.capturedAt,
                    encryptedFilePath = input.encryptedFilePath,
                    fileName = input.fileName,
                    contentType = attachment.contentType,
                    sizeBytes = input.sizeBytes,
                    sha256 = input.sha256,
                    reservationOperationId = attachment.operationId,
                    uploadOperationId = stableMediaUploadOperationId(input.evidenceId),
                    state = EVIDENCE_DRAFT,
                    mediaId = null,
                    mediaGeneration = null,
                    reviewReason = null,
                    uploadPercent = 0,
                    lastError = null,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    variantManifestJson = input.variantManifestJson,
                    problemReportId = reportId,
                ),
            )
            updateDraft(operation, payload.copy(attachments = payload.attachments + attachment))
        }
        return input.evidenceId
    }

    suspend fun removeDraftPhoto(userId: String, reportId: String, evidenceId: String): TaskEvidenceEntity =
        database.withTransaction {
            val operation = requireDraft(userId, reportId)
            val payload = operation.payload()
            val evidence = requireNotNull(database.evidenceDao().evidence(userId, evidenceId)) {
                "Фотография черновика недоступна"
            }
            require(evidence.problemReportId == reportId && evidence.state == EVIDENCE_DRAFT) {
                "Фотография уже отправлена"
            }
            check(database.evidenceDao().deleteDraftProblemReportEvidence(userId, reportId, evidenceId) == 1) {
                "Черновик обращения изменился"
            }
            updateDraft(operation, payload.copy(attachments = payload.attachments.filterNot { it.evidenceId == evidenceId }))
            evidence
        }

    /** Freezes an immutable command and makes its photos eligible only as one report reservation. */
    suspend fun submitDraft(userId: String, reportId: String) {
        database.withTransaction {
            val operation = requireDraft(userId, reportId)
            val payload = operation.payload()
            require(payload.comment.trim().length in 1..MAX_COMMENT_LENGTH) { "Опишите проблему от 1 до 2000 символов" }
            require(payload.attachments.size <= MAX_ATTACHMENTS) { "К обращению можно добавить не более 10 фото" }
            require(database.evidenceDao().forProblemReport(userId, reportId).size == payload.attachments.size) {
                "Черновик фотографий повреждён"
            }
            val now = System.currentTimeMillis()
            check(
                database.outboxDao().updateProblemReport(
                    operationId = reportId,
                    encryptedPayload = pendingPayloadCipher.encrypt(json.encodeToString(payload.copy(comment = payload.comment.trim()))),
                    state = OUTBOX_PENDING,
                    retryCount = 0,
                    lastError = null,
                    now = now,
                ) == 1,
            ) { "Черновик обращения изменился" }
            database.evidenceDao().submitProblemReportEvidence(userId, reportId, now)
        }
    }

    /** Explicit retry preserves the immutable submitted body and stable report identity. */
    suspend fun retrySubmittedReport(userId: String, reportId: String) {
        database.withTransaction {
            val operation = requireNotNull(database.outboxDao().problemReport(userId, reportId)) {
                "Обращение недоступно"
            }
            require(operation.state in setOf(OUTBOX_REVIEW_REQUIRED, OUTBOX_CONFLICT)) {
                "Обращение уже передаётся"
            }
            check(
                database.outboxDao().updateProblemReport(
                    operationId = reportId,
                    encryptedPayload = operation.encryptedPayload,
                    state = OUTBOX_PENDING,
                    retryCount = 0,
                    lastError = null,
                    now = System.currentTimeMillis(),
                ) == 1,
            ) { "Обращение изменилось" }
        }
    }

    private suspend fun requireDraft(userId: String, reportId: String): WorkerOutboxEntity =
        requireNotNull(database.outboxDao().problemReport(userId, reportId)) {
            "Черновик обращения недоступен"
        }.also { require(it.state == OUTBOX_DRAFT) { "Обращение уже отправлено" } }

    private suspend fun updateDraft(
        operation: WorkerOutboxEntity,
        payload: PendingWorkerProblemReport,
    ) {
        check(
            database.outboxDao().updateProblemReport(
                operationId = operation.operationId,
                encryptedPayload = pendingPayloadCipher.encrypt(json.encodeToString(payload)),
                state = OUTBOX_DRAFT,
                retryCount = 0,
                lastError = null,
                now = System.currentTimeMillis(),
            ) == 1,
        ) { "Черновик обращения изменился" }
    }

    private fun WorkerOutboxEntity.payload(): PendingWorkerProblemReport =
        runCatching { json.decodeFromString<PendingWorkerProblemReport>(pendingPayloadCipher.decrypt(encryptedPayload)) }
            .getOrElse { throw IllegalStateException("Черновик обращения повреждён", it) }

    private fun WorkerOutboxEntity.toDraftSnapshot(
        attachments: List<TaskEvidenceEntity>,
    ): WorkerProblemReportDraftSnapshot {
        val payload = payload()
        return WorkerProblemReportDraftSnapshot(
            reportId = operationId,
            entryId = entryId,
            routeIndex = payload.routeIndex,
            comment = payload.comment,
            occurredAt = payload.occurredAt,
            state = state,
            lastError = lastError,
            attachments = attachments,
        )
    }

    private fun observeReportSnapshots(
        userId: String,
        reports: Flow<List<WorkerOutboxEntity>>,
    ): Flow<List<WorkerProblemReportDraftSnapshot>> = reports.flatMapLatest { operations ->
        if (operations.isEmpty()) return@flatMapLatest flowOf(emptyList())
        combine(operations.map { operation ->
            database.evidenceDao().observeForProblemReport(userId, operation.operationId).map { attachments ->
                operation.toDraftSnapshot(attachments)
            }
        }) { snapshots -> snapshots.toList() }
    }

    private suspend fun leaseFor(userId: String): ServerTimeAnchor? = database.sessionDao().session(userId)?.let { session ->
        val server = session.serverEpochMillis ?: return@let null
        val elapsed = session.elapsedRealtimeAtSyncMillis ?: return@let null
        val expires = session.leaseExpiresAtEpochMillis ?: return@let null
        ServerTimeAnchor(server, elapsed, expires)
    }

    companion object {
        const val OUTBOX_PROBLEM_REPORT = "PROBLEM_REPORT"
        const val OUTBOX_DRAFT = "DRAFT"
        const val OUTBOX_PENDING = "PENDING"
        const val OUTBOX_RETRY = "RETRY"
        const val OUTBOX_REPORTED = "REPORTED"
        const val OUTBOX_REVIEW_REQUIRED = "REVIEW_REQUIRED"
        const val OUTBOX_CONFLICT = "CONFLICT"
        const val EVIDENCE_DRAFT = "DRAFT"
        const val MAX_ATTACHMENTS = 10
        const val MAX_COMMENT_LENGTH = 2_000
    }
}
