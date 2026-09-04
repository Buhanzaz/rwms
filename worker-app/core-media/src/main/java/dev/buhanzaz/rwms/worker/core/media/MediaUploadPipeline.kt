package dev.buhanzaz.rwms.worker.core.media

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.buhanzaz.rwms.worker.core.database.EncryptedEvidenceVariantPart
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerDatabase
import dev.buhanzaz.rwms.worker.core.network.CreateUploadSessionRequestDto
import dev.buhanzaz.rwms.worker.core.network.FinalizeImageVariantDto
import dev.buhanzaz.rwms.worker.core.network.FinalizeUploadRequestDto
import dev.buhanzaz.rwms.worker.core.network.ImageVariantUploadRequestDto
import dev.buhanzaz.rwms.worker.core.network.UploadedObjectDto
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import dev.buhanzaz.rwms.worker.core.network.safeWorkerUserMessage
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Server-confirmed result of one durable evidence upload attempt. */
sealed interface EvidenceUploadResult {
    data object WaitingForReservation : EvidenceUploadResult
    data object Processing : EvidenceUploadResult
    data class Ready(val mediaId: String, val generation: Long) : EvidenceUploadResult
    data class ReviewRequired(val reason: String?) : EvidenceUploadResult
}

/** Allows sync ordering to be tested without a real media transfer. */
interface WorkerEvidenceUploader {
    /** Continues one durable upload and never reports READY before task-board confirms it. */
    suspend fun uploadReservedEvidence(userId: String, evidence: TaskEvidenceEntity): EvidenceUploadResult
}

/** Uploaded object acknowledgement paired with its stable image-variant kind. */
private data class UploadedEvidenceVariant(
    val kind: String,
    val objectResult: UploadedObjectDto,
)

/**
 * Uploads client-produced WebP variants through same-origin paths. Upload sessions remain local to
 * an attempt; encrypted parts and stable per-part idempotency keys make process-death retry safe.
 */
@Singleton
class MediaUploadPipeline @Inject constructor(
    private val database: WorkerDatabase,
    private val gateway: WorkerGatewayClient,
    private val fileStore: EncryptedEvidenceFileStore,
    private val json: Json,
) : WorkerEvidenceUploader {
    private val partUploadPermits = Semaphore(MAX_PARALLEL_VARIANT_UPLOADS)

    override suspend fun uploadReservedEvidence(
        userId: String,
        evidence: TaskEvidenceEntity,
    ): EvidenceUploadResult {
        if (evidence.state == "CAPTURED") return EvidenceUploadResult.WaitingForReservation
        if (evidence.state in terminalStates) return evidence.toTerminalResultWithCleanup()
        val variants = evidence.validatedVariantsOrRetainedSource()
        // Finalize has already committed immutable objects. Polling task-board avoids creating a
        // duplicate media asset after process death between finalize and READY observation.
        if (evidence.state == "PROCESSING") {
            return awaitTaskBoardEvidence(evidence, variants.orEmpty())
        }

        return try {
            database.evidenceDao().updateUploadProgress(
                evidence.evidenceId,
                5,
                null,
                System.currentTimeMillis(),
            )
            val session = requireNotNull(database.sessionDao().session(userId)) {
                "Worker context is unavailable"
            }
            val warehouseId = requireNotNull(session.warehouseId) { "Worker warehouse is unavailable" }
            if (variants == null) {
                return uploadRetainedSourceEvidence(evidence, warehouseId)
            }
            val upload = gateway.createUploadSession(
                idempotencyKey = evidence.uploadOperationId,
                request = CreateUploadSessionRequestDto(
                    ownerId = evidence.entryId,
                    warehouseId = warehouseId,
                    clientReferenceId = evidence.evidenceId,
                    fileName = evidence.fileName,
                    imageVariants = variants.map { variant ->
                        ImageVariantUploadRequestDto(
                            kind = variant.kind,
                            contentLength = variant.contentLength,
                            checksumSha256 = variant.checksumSha256,
                            width = variant.width,
                            height = variant.height,
                        )
                    },
                ),
            )
            require(upload.contentUploadUrl == null) {
                "Still-image upload unexpectedly exposed a legacy content path"
            }
            val pathsByKind = upload.variantUploadUrls.associate { it.kind to it.contentUploadUrl }
            require(pathsByKind.keys == EncryptedEvidenceFileStore.REQUIRED_VARIANT_KINDS.toSet()) {
                "Upload session did not provide all image variant paths"
            }
            database.evidenceDao().updateState(
                evidence.evidenceId,
                "UPLOADING",
                upload.mediaId,
                null,
                null,
                System.currentTimeMillis(),
            )
            database.evidenceDao().updateUploadProgress(
                evidence.evidenceId,
                15,
                null,
                System.currentTimeMillis(),
            )
            val uploaded = uploadVariants(
                evidence = evidence,
                variants = variants,
                pathsByKind = pathsByKind,
            )
            database.evidenceDao().updateUploadProgress(
                evidence.evidenceId,
                99,
                null,
                System.currentTimeMillis(),
            )
            val media = gateway.finalizeUploadSession(
                uploadSessionId = upload.uploadSessionId,
                idempotencyKey = evidence.uploadOperationId,
                request = FinalizeUploadRequestDto(
                    variants = uploaded.map { part ->
                        FinalizeImageVariantDto(
                            kind = part.kind,
                            objectVersionId = part.objectResult.objectVersionId,
                            etag = part.objectResult.etag,
                            checksumSha256 = part.objectResult.checksumSha256,
                        )
                    },
                ),
            )
            database.evidenceDao().updateState(
                evidence.evidenceId,
                "PROCESSING",
                media.id,
                media.generation,
                null,
                System.currentTimeMillis(),
            )
            database.evidenceDao().updateUploadProgress(
                evidence.evidenceId,
                100,
                null,
                System.currentTimeMillis(),
            )
            awaitTaskBoardEvidence(evidence, variants)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            database.evidenceDao().updateUploadError(
                evidence.evidenceId,
                error.safeWorkerUserMessage(
                    "Не удалось загрузить фотографию. Запустите синхронизацию ещё раз.",
                ),
                System.currentTimeMillis(),
            )
            throw error
        }
    }

    /**
     * Completes only JPEG evidence already durable before schema 9. The server pins this exact
     * source as every logical image variant; no phone or Go transformation is repeated.
     */
    private suspend fun uploadRetainedSourceEvidence(
        evidence: TaskEvidenceEntity,
        warehouseId: String,
    ): EvidenceUploadResult {
        val upload = gateway.createUploadSession(
            idempotencyKey = evidence.uploadOperationId,
            request = CreateUploadSessionRequestDto(
                ownerId = evidence.entryId,
                warehouseId = warehouseId,
                clientReferenceId = evidence.evidenceId,
                fileName = evidence.fileName,
                contentType = evidence.contentType,
                contentLength = evidence.sizeBytes,
                checksumSha256 = evidence.sha256,
            ),
        )
        val contentPath = requireNotNull(upload.contentUploadUrl) {
            "Retained source upload did not provide its content path"
        }
        require(upload.variantUploadUrls.isEmpty()) {
            "Retained source upload unexpectedly exposed image variant paths"
        }
        database.evidenceDao().updateState(
            evidence.evidenceId,
            "UPLOADING",
            upload.mediaId,
            null,
            null,
            System.currentTimeMillis(),
        )
        database.evidenceDao().updateUploadProgress(
            evidence.evidenceId,
            15,
            null,
            System.currentTimeMillis(),
        )
        val uploaded = gateway.uploadMediaContent(
            sameOriginContentPath = contentPath,
            idempotencyKey = evidence.uploadOperationId,
            content = EncryptedSourceEvidenceRequestBody(evidence, fileStore) { written, total ->
                val percent = uploadContentProgressPercent(written, total)
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
        require(uploaded.checksumSha256 == evidence.sha256) {
            "Uploaded retained evidence checksum does not match its reservation"
        }
        database.evidenceDao().updateUploadProgress(
            evidence.evidenceId,
            99,
            null,
            System.currentTimeMillis(),
        )
        val media = gateway.finalizeUploadSession(
            uploadSessionId = upload.uploadSessionId,
            idempotencyKey = evidence.uploadOperationId,
            request = FinalizeUploadRequestDto(
                objectVersionId = uploaded.objectVersionId,
                etag = uploaded.etag,
                checksumSha256 = uploaded.checksumSha256,
            ),
        )
        database.evidenceDao().updateState(
            evidence.evidenceId,
            "PROCESSING",
            media.id,
            media.generation,
            null,
            System.currentTimeMillis(),
        )
        database.evidenceDao().updateUploadProgress(
            evidence.evidenceId,
            100,
            null,
            System.currentTimeMillis(),
        )
        return awaitTaskBoardEvidence(evidence, emptyList())
    }

    private suspend fun uploadVariants(
        evidence: TaskEvidenceEntity,
        variants: List<EncryptedEvidenceVariantPart>,
        pathsByKind: Map<String, String>,
    ): List<UploadedEvidenceVariant> = coroutineScope {
        val writtenByKind = ConcurrentHashMap<String, Long>()
        val lastPersistedProgress = AtomicInteger(15)
        variants.map { variant ->
            async {
                partUploadPermits.withPermit {
                    val uploaded = gateway.uploadMediaContent(
                        sameOriginContentPath = requireNotNull(pathsByKind[variant.kind]),
                        idempotencyKey = stableVariantUploadOperationId(
                            evidence.uploadOperationId,
                            variant.kind,
                        ),
                        content = EncryptedWebpVariantRequestBody(variant, fileStore) { written, _ ->
                            writtenByKind[variant.kind] = written
                            val aggregateWritten = writtenByKind.values.sum()
                            val percent = uploadContentProgressPercent(
                                aggregateWritten,
                                evidence.sizeBytes,
                            )
                            var previous = lastPersistedProgress.get()
                            while (percent >= previous + PROGRESS_PERSIST_STEP_PERCENT) {
                                if (lastPersistedProgress.compareAndSet(previous, percent)) {
                                    runBlocking {
                                        database.evidenceDao().updateUploadProgress(
                                            evidence.evidenceId,
                                            percent,
                                            null,
                                            System.currentTimeMillis(),
                                        )
                                    }
                                    break
                                }
                                previous = lastPersistedProgress.get()
                            }
                        },
                    )
                    require(uploaded.checksumSha256 == variant.checksumSha256) {
                        "Uploaded ${variant.kind} checksum does not match prepared evidence"
                    }
                    UploadedEvidenceVariant(variant.kind, uploaded)
                }
            }
        }.awaitAll().sortedBy { uploaded ->
            EncryptedEvidenceFileStore.REQUIRED_VARIANT_KINDS.indexOf(uploaded.kind)
        }
    }

    /** Polling deliberately gates deletion and task completion on task-board's READY fact. */
    private suspend fun awaitTaskBoardEvidence(
        evidence: TaskEvidenceEntity,
        variants: List<EncryptedEvidenceVariantPart>,
    ): EvidenceUploadResult {
        repeat(8) {
            val remote = gateway.detail(evidence.entryId).evidence
                .firstOrNull { item -> item.evidenceId == evidence.evidenceId }
            if (remote != null) {
                when (remote.state) {
                    "READY" -> {
                        val mediaId = requireNotNull(remote.mediaId)
                        val mediaGeneration = requireNotNull(remote.mediaGeneration)
                        // Server READY is authoritative. Delete first so a process death can only
                        // leave a PROCESSING row that safely polls and repeats idempotent cleanup.
                        fileStore.deleteBundle(evidence.encryptedFilePath, variants)
                        database.evidenceDao().updateState(
                            evidence.evidenceId,
                            "READY",
                            mediaId,
                            mediaGeneration,
                            null,
                            System.currentTimeMillis(),
                        )
                        database.evidenceDao().updateUploadProgress(
                            evidence.evidenceId,
                            100,
                            null,
                            System.currentTimeMillis(),
                        )
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
                        database.evidenceDao().updateUploadProgress(
                            evidence.evidenceId,
                            100,
                            remote.reviewReason,
                            System.currentTimeMillis(),
                        )
                        return EvidenceUploadResult.ReviewRequired(remote.reviewReason)
                    }
                    "REJECTED" -> {
                        val reason = remote.reviewReason ?: "Фото отклонено сервером"
                        database.evidenceDao().updateState(
                            evidence.evidenceId,
                            "REVIEW_REQUIRED",
                            remote.mediaId,
                            remote.mediaGeneration,
                            reason,
                            System.currentTimeMillis(),
                        )
                        database.evidenceDao().updateUploadProgress(
                            evidence.evidenceId,
                            100,
                            reason,
                            System.currentTimeMillis(),
                        )
                        return EvidenceUploadResult.ReviewRequired(reason)
                    }
                }
            }
            delay(2_000)
        }
        return EvidenceUploadResult.Processing
    }

    private fun TaskEvidenceEntity.validatedVariantsOrRetainedSource(): List<EncryptedEvidenceVariantPart>? {
        val variants = runCatching {
            json.decodeFromString<List<EncryptedEvidenceVariantPart>>(variantManifestJson)
        }.getOrElse { throw IllegalStateException("Encrypted evidence variant manifest is invalid", it) }
            .sortedBy { EncryptedEvidenceFileStore.REQUIRED_VARIANT_KINDS.indexOf(it.kind) }
        if (variants.isEmpty()) {
            require(contentType == "image/jpeg") { "Only retained JPEG evidence may omit variants" }
            require(sizeBytes in 1..EncryptedEvidenceFileStore.MAX_SOURCE_IMAGE_BYTES) {
                "Retained evidence exceeds the source upload limit"
            }
            require(SHA256_PATTERN.matches(sha256)) { "Retained evidence checksum is invalid" }
            return null
        }
        require(variants.map(EncryptedEvidenceVariantPart::kind) ==
            EncryptedEvidenceFileStore.REQUIRED_VARIANT_KINDS
        ) { "Evidence must contain exactly three ordered WebP variants" }
        require(variants.all { it.contentLength > 0 && it.width > 0 && it.height > 0 }) {
            "Evidence variant metadata is invalid"
        }
        require(variants.sumOf(EncryptedEvidenceVariantPart::contentLength) == sizeBytes) {
            "Evidence aggregate size changed after reservation"
        }
        require(sizeBytes <= EncryptedEvidenceFileStore.MAX_UPLOAD_BUNDLE_BYTES) {
            "Evidence variants exceed 1 MiB"
        }
        require(workerEncryptedEvidenceManifestSha256(variants) == sha256) {
            "Evidence manifest changed after reservation"
        }
        return variants
    }

    private fun TaskEvidenceEntity.toTerminalResultWithCleanup(): EvidenceUploadResult = when (state) {
        "READY" -> {
            val variants = runCatching { validatedVariantsOrRetainedSource() }.getOrNull().orEmpty()
            fileStore.deleteBundle(encryptedFilePath, variants)
            EvidenceUploadResult.Ready(requireNotNull(mediaId), requireNotNull(mediaGeneration))
        }
        "REVIEW_REQUIRED", "REJECTED" -> EvidenceUploadResult.ReviewRequired(reviewReason)
        else -> EvidenceUploadResult.Processing
    }

    /** Small concurrency and progress bounds shared by all evidence transfers. */
    private companion object {
        val terminalStates = setOf("READY", "REVIEW_REQUIRED", "REJECTED")
        const val MAX_PARALLEL_VARIANT_UPLOADS = 3
        const val PROGRESS_PERSIST_STEP_PERCENT = 5
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

@Module
@InstallIn(SingletonComponent::class)
/** Binds the production WebP uploader while sync depends only on the narrow recovery port. */
abstract class WorkerEvidenceUploaderModule {
    @Binds
    abstract fun bindWorkerEvidenceUploader(implementation: MediaUploadPipeline): WorkerEvidenceUploader
}

/** Maps aggregate streaming bytes to a non-regressing durable stage range of 15..95. */
internal fun uploadContentProgressPercent(writtenBytes: Long, totalBytes: Long): Int =
    (15 + (uploadProgressPercent(writtenBytes, totalBytes) * 80 / 99)).coerceIn(15, 95)

/** Returns a deterministic UUID idempotency key for one evidence part. */
internal fun stableVariantUploadOperationId(uploadOperationId: String, kind: String): String {
    require(kind in EncryptedEvidenceFileStore.REQUIRED_VARIANT_KINDS) { "Unknown image variant" }
    val canonicalOperationId = UUID.fromString(uploadOperationId).toString()
    val identity = "rwms-image-variant-upload-v1:$canonicalOperationId:$kind"
    return UUID.nameUUIDFromBytes(identity.toByteArray(StandardCharsets.UTF_8)).toString()
}
