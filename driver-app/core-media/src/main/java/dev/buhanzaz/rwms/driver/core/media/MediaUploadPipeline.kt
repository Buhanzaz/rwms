package dev.buhanzaz.rwms.driver.core.media

import dev.buhanzaz.rwms.driver.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.driver.core.database.DriverDatabase
import dev.buhanzaz.rwms.driver.core.network.CreateUploadSessionRequestDto
import dev.buhanzaz.rwms.driver.core.network.FinalizeUploadRequestDto
import dev.buhanzaz.rwms.driver.core.network.DriverGatewayClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Encapsulates driver evidence/media recovery behavior; server confirmation remains authoritative.
 */
sealed interface EvidenceUploadResult {
    /** Evidence cannot upload until task-board has persisted its reservation. */
    data object WaitingForReservation : EvidenceUploadResult
    /** Media accepted the bytes and is still producing the authoritative asset. */
    data object Processing : EvidenceUploadResult
    /** Media finalized an asset generation that task-board may attach. */
    data class Ready(val mediaId: String, val generation: Long) : EvidenceUploadResult
    /** Media requires an explicit retry or operator decision. */
    data class ReviewRequired(val reason: String?) : EvidenceUploadResult
}

/** Allows sync ordering to be tested without a real media transfer. */
interface DriverEvidenceUploader {
    /**
     * Continues the durable reservation/upload/finalize pipeline for one evidence row and reports
     * only a server-confirmed terminal state or a state that still requires recovery.
     */
    suspend fun uploadReservedEvidence(userId: String, evidence: TaskEvidenceEntity): EvidenceUploadResult
}

/**
 * Upload sessions remain stack-local to one sync attempt. If they expire, the
 * next attempt creates a new session using the same evidence and operation IDs.
 */
@Singleton
class MediaUploadPipeline @Inject constructor(
    private val database: DriverDatabase,
    private val gateway: DriverGatewayClient,
    private val fileStore: EncryptedEvidenceFileStore,
) : DriverEvidenceUploader {
    override suspend fun uploadReservedEvidence(userId: String, evidence: TaskEvidenceEntity): EvidenceUploadResult {
        if (evidence.state == "CAPTURED") return EvidenceUploadResult.WaitingForReservation
        if (evidence.state in terminalStates) return evidence.toTerminalResult()
        // Finalize has already committed the immutable object. A new session
        // would duplicate work; poll task-board until its evidence fact is READY.
        if (evidence.state == "PROCESSING") return awaitTaskBoardEvidence(evidence)

        return try {
            database.evidenceDao().updateUploadProgress(evidence.evidenceId, 5, null, System.currentTimeMillis())
            val session = requireNotNull(database.sessionDao().session(userId)) { "Driver context is unavailable" }
            val warehouseId = requireNotNull(session.warehouseId) { "Driver warehouse is unavailable" }
            val upload = gateway.createUploadSession(
                idempotencyKey = evidence.uploadOperationId,
                request = CreateUploadSessionRequestDto(
                    ownerType = evidence.ownerType,
                    ownerId = evidence.entryId,
                    warehouseId = warehouseId,
                    context = evidence.mediaContext,
                    clientReferenceId = evidence.evidenceId,
                    fileName = evidence.fileName,
                    contentLength = evidence.sizeBytes,
                    checksumSha256 = evidence.sha256,
                ),
            )
            database.evidenceDao().updateState(
                evidence.evidenceId,
                "UPLOADING",
                upload.mediaId,
                null,
                null,
                System.currentTimeMillis(),
            )
            database.evidenceDao().updateUploadProgress(evidence.evidenceId, 15, null, System.currentTimeMillis())
            val uploaded = gateway.uploadMediaContent(
                sameOriginContentPath = upload.contentUploadUrl,
                idempotencyKey = evidence.uploadOperationId,
                content = EncryptedJpegRequestBody(evidence, fileStore) { written, total ->
                    val percent = uploadContentProgressPercent(written, total)
                    // OkHttp calls RequestBody on its own thread; persist each
                    // throttled update before more bytes are accepted so the
                    // progress survives process death.
                    runBlocking {
                        database.evidenceDao().updateUploadProgress(
                            evidence.evidenceId,
                            percent,
                            null,
                            System.currentTimeMillis(),
                        )
                    }
                },
            )
            database.evidenceDao().updateUploadProgress(evidence.evidenceId, 99, null, System.currentTimeMillis())
            val media = gateway.finalizeUploadSession(
                uploadSessionId = upload.uploadSessionId,
                idempotencyKey = evidence.uploadOperationId,
                request = FinalizeUploadRequestDto(uploaded.objectVersionId, uploaded.etag, uploaded.checksumSha256),
            )
            database.evidenceDao().updateState(
                evidence.evidenceId,
                "PROCESSING",
                media.id,
                media.generation,
                null,
                System.currentTimeMillis(),
            )
            database.evidenceDao().updateUploadProgress(evidence.evidenceId, 100, null, System.currentTimeMillis())
            awaitTaskBoardEvidence(evidence)
        } catch (error: Throwable) {
            database.evidenceDao().updateUploadError(
                evidence.evidenceId,
                error.message ?: "Не удалось загрузить фотографию",
                System.currentTimeMillis(),
            )
            throw error
        }
    }

    /** Polling deliberately gates completion until task-board has observed Media READY. */
    private suspend fun awaitTaskBoardEvidence(evidence: TaskEvidenceEntity): EvidenceUploadResult {
        repeat(8) {
            val remote = if (evidence.ownerType == "DRIVER_SHIFT") {
                gateway.todayDriverShift().photos
                    .firstOrNull { it.evidenceId == evidence.evidenceId }
                    ?.let { photo ->
                        RemoteEvidenceState(
                            state = photo.state,
                            mediaId = photo.mediaId,
                            mediaGeneration = photo.mediaGeneration,
                            reviewReason = if (photo.state == "REVIEW_REQUIRED") {
                                "Фотография требует проверки"
                            } else {
                                null
                            },
                        )
                    }
            } else {
                gateway.detail(evidence.entryId).evidence
                    .firstOrNull { it.evidenceId == evidence.evidenceId }
                    ?.let { taskEvidence ->
                        RemoteEvidenceState(
                            state = taskEvidence.state,
                            mediaId = taskEvidence.mediaId,
                            mediaGeneration = taskEvidence.mediaGeneration,
                            reviewReason = taskEvidence.reviewReason,
                        )
                    }
            }
            if (remote != null) {
                when (remote.state) {
                    "READY" -> {
                        val mediaId = requireNotNull(remote.mediaId)
                        val mediaGeneration = requireNotNull(remote.mediaGeneration)
                        database.evidenceDao().updateState(
                            evidence.evidenceId,
                            "READY",
                            mediaId,
                            mediaGeneration,
                            null,
                            System.currentTimeMillis(),
                        )
                        database.evidenceDao().updateUploadProgress(evidence.evidenceId, 100, null, System.currentTimeMillis())
                        return EvidenceUploadResult.Ready(mediaId, mediaGeneration)
                    }
                    "REVIEW_REQUIRED" -> {
                        database.evidenceDao().updateState(
                            evidence.evidenceId,
                            "REVIEW_REQUIRED",
                            remote.mediaId,
                            remote.mediaGeneration,
                            remote.reviewReason,
                            System.currentTimeMillis(),
                        )
                        database.evidenceDao().updateUploadProgress(evidence.evidenceId, 100, remote.reviewReason, System.currentTimeMillis())
                        return EvidenceUploadResult.ReviewRequired(remote.reviewReason)
                    }
                    "REJECTED" -> {
                        database.evidenceDao().updateState(
                            evidence.evidenceId,
                            "REVIEW_REQUIRED",
                            remote.mediaId,
                            remote.mediaGeneration,
                            remote.reviewReason ?: "Фото отклонено сервером",
                            System.currentTimeMillis(),
                        )
                        database.evidenceDao().updateUploadProgress(
                            evidence.evidenceId,
                            100,
                            remote.reviewReason ?: "Фото отклонено сервером",
                            System.currentTimeMillis(),
                        )
                        return EvidenceUploadResult.ReviewRequired(remote.reviewReason)
                    }
                }
            }
            delay(2_000)
        }
        return EvidenceUploadResult.Processing
    }

    private fun TaskEvidenceEntity.toTerminalResult(): EvidenceUploadResult = when (state) {
        "READY" -> EvidenceUploadResult.Ready(requireNotNull(mediaId), requireNotNull(mediaGeneration))
        "REVIEW_REQUIRED", "REJECTED" -> EvidenceUploadResult.ReviewRequired(reviewReason)
        else -> EvidenceUploadResult.Processing
    }

    private companion object {
        val terminalStates = setOf("READY", "REVIEW_REQUIRED", "REJECTED")
    }
}

/** Minimal common projection used while polling task and Driver Shift evidence owners. */
private data class RemoteEvidenceState(
    val state: String,
    val mediaId: String?,
    val mediaGeneration: Long?,
    val reviewReason: String?,
)

@Module
@InstallIn(SingletonComponent::class)
/**
 * Encapsulates driver evidence/media recovery behavior; server confirmation remains authoritative.
 */
abstract class DriverEvidenceUploaderModule {
    @Binds
    abstract fun bindDriverEvidenceUploader(implementation: MediaUploadPipeline): DriverEvidenceUploader
}

/** Maps streaming bytes to a non-regressing durable stage range of 15..95. */
internal fun uploadContentProgressPercent(writtenBytes: Long, totalBytes: Long): Int =
    (15 + (uploadProgressPercent(writtenBytes, totalBytes) * 80 / 99)).coerceIn(15, 95)
