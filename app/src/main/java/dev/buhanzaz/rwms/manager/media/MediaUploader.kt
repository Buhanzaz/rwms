package dev.buhanzaz.rwms.manager.media

import android.content.ContentResolver
import android.net.Uri
import dev.buhanzaz.rwms.manager.network.CreateUploadSessionRequest
import dev.buhanzaz.rwms.manager.network.FinalizeUploadRequest
import dev.buhanzaz.rwms.manager.network.MediaAssetDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RwmsApi
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/**
 * Encapsulates manager media transfer or retrieval through the public gateway.
 */
data class MediaOwner(
    val ownerType: String,
    val warehouseId: String,
    val context: String,
    val ownerId: String? = null,
    val documentId: String? = null,
    val lineId: String? = null,
)

/**
 * Encapsulates manager media transfer or retrieval through the public gateway.
 */
data class PhotoPayload(
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
    val checksumSha256: String,
) {
    companion object {
        fun exactJpeg(fileName: String, bytes: ByteArray): PhotoPayload {
            require(bytes.isNotEmpty()) { "Фотография пуста" }
            return PhotoPayload(
                fileName = fileName,
                contentType = "image/jpeg",
                bytes = bytes,
                checksumSha256 = bytes.sha256(),
            )
        }
    }
}

/**
 * Stable client identities for one exact local upload attempt.
 *
 * The URI is deliberately part of the fingerprint: two photos with identical bytes are still
 * distinct user choices and must not be collapsed into one media asset.  The checksum and order
 * keep a reused local file path from replaying a request whose actual content changed.
 */
internal data class MediaUploadIdentity(
    val folderId: String,
    val createSessionKey: String,
    /** The media API requires the same key when content upload is finalized. */
    val contentAndFinalizeKey: String,
)

internal fun mediaUploadIdentity(
    owner: MediaOwner,
    localUri: String,
    photo: PhotoPayload,
    sortOrder: Int,
): MediaUploadIdentity {
    require(sortOrder >= 0) { "Порядок фотографии не может быть отрицательным" }
    val fingerprint = listOf(
        "rwms-mobile-media-upload-v1",
        owner.ownerType,
        owner.ownerId.orEmpty(),
        owner.documentId.orEmpty(),
        owner.lineId.orEmpty(),
        owner.warehouseId,
        owner.context,
        localUri,
        photo.fileName,
        photo.contentType,
        photo.bytes.size.toString(),
        photo.checksumSha256,
        sortOrder.toString(),
    ).joinToString(separator = "\u001f")
    return MediaUploadIdentity(
        folderId = stableMediaUploadUuid("folder", fingerprint),
        createSessionKey = stableMediaUploadUuid("create", fingerprint),
        contentAndFinalizeKey = stableMediaUploadUuid("content-finalize", fingerprint),
    )
}

private fun stableMediaUploadUuid(scope: String, fingerprint: String): String =
    UUID.nameUUIDFromBytes(
        "rwms-mobile-media:$scope:$fingerprint".toByteArray(StandardCharsets.UTF_8),
    ).toString()

/**
 * Encapsulates manager media transfer or retrieval through the public gateway.
 */
class PhotoPayloadReader(
    private val contentResolver: ContentResolver,
) {
    fun read(uriText: String): PhotoPayload {
        val uri = Uri.parse(uriText)
        val bytes = when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> File(requireNotNull(uri.path)).readBytes()
            else -> contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("Не удалось прочитать фотографию")
        }
        val contentType = detectMediaContentType(bytes)
            ?: throw IllegalArgumentException(
                "Поддерживаются только изображения JPEG, PNG, WebP и видео MP4, WebM",
            )
        val fileName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
            ?: "rwms-media-${stableMediaUploadUuid("file-name", uriText)}" +
                mediaFileExtension(contentType)

        // Camera/gallery intake has already normalized still-photo pixels upright. Keep this
        // exact byte read so the durable outbox uploads precisely that app-owned original.
        return PhotoPayload(
            fileName = fileName,
            contentType = contentType,
            bytes = bytes,
            checksumSha256 = bytes.sha256(),
        )
    }
}

/**
 * Encapsulates manager media transfer or retrieval through the public gateway.
 */
class MediaUploader private constructor(
    private val api: RwmsApi,
    private val payloadLoader: (String) -> PhotoPayload,
) {
    private val uploadPermits = Semaphore(MEDIA_UPLOAD_PARALLELISM)

    constructor(
        api: RwmsApi,
        payloadReader: PhotoPayloadReader,
    ) : this(api, payloadReader::read)

    internal constructor(
        api: RwmsApi,
        payloadLoader: (String) -> PhotoPayload,
        @Suppress("UNUSED_PARAMETER") testContract: Unit = Unit,
    ) : this(api, payloadLoader)

    suspend fun upload(
        owner: MediaOwner,
        photoUris: List<String>,
        sortOrderByUri: Map<String, Int> = photoUris.withIndex().associate { (index, uri) ->
            uri to index
        },
        onReady: suspend (uri: String, reference: MediaReferenceDto) -> Unit = { _, _ -> },
    ): List<MediaReferenceDto> {
        require(photoUris.isNotEmpty()) { "Добавьте хотя бы одну фотографию" }
        require(photoUris.distinct().size == photoUris.size) {
            "Одна фотография не может быть добавлена дважды"
        }
        val uploadRequests = photoUris.map { uri ->
            uri to requireNotNull(sortOrderByUri[uri]) {
                "Не задан порядок фотографии"
            }
        }
        return uploadAcceptedBoundedParallelOrdered(
            inputs = uploadRequests,
            permits = uploadPermits,
            accept = { (uri, sortOrder) ->
                acceptOne(
                    owner = owner,
                    localUri = uri,
                    sortOrder = sortOrder,
                    photo = payloadLoader(uri),
                )
            },
            complete = { _, mediaId -> awaitReady(owner, mediaId) },
            onReady = { (uri, _), reference ->
                // Persisting this callback immediately lets an interrupted batch resume from
                // the remaining local URIs instead of creating another attachment for the
                // already READY original.
                onReady(uri, reference)
            },
        )
    }

    private suspend fun acceptOne(
        owner: MediaOwner,
        localUri: String,
        sortOrder: Int,
        photo: PhotoPayload,
    ): String {
        val identity = mediaUploadIdentity(owner, localUri, photo, sortOrder)
        val completedMediaId = try {
            retryMediaCommandAfterOwnerProof {
                val session = api.createUploadSession(
                    identity.createSessionKey,
                    CreateUploadSessionRequest(
                        ownerType = owner.ownerType,
                        ownerId = owner.ownerId,
                        documentId = owner.documentId,
                        lineId = owner.lineId,
                        warehouseId = owner.warehouseId,
                        context = owner.context,
                        folderId = identity.folderId,
                        fileName = photo.fileName,
                        contentType = photo.contentType,
                        contentLength = photo.bytes.size.toLong(),
                        checksumSha256 = photo.checksumSha256,
                        sortOrder = sortOrder,
                    ),
                )
                val expectedContentPath =
                    "/api/media/v1/upload-sessions/${session.uploadSessionId}/content"
                if (session.contentUploadUrl != expectedContentPath) {
                    throw IllegalStateException(
                        "Медиасервис вернул небезопасный путь загрузки фотографии",
                    )
                }
                val uploaded = api.uploadContent(
                    contentPath = session.contentUploadUrl,
                    idempotencyKey = identity.contentAndFinalizeKey,
                    body = photo.bytes.toRequestBody(photo.contentType.toMediaType()),
                )
                val completed = api.finalizeUpload(
                    uploadSessionId = session.uploadSessionId,
                    idempotencyKey = identity.contentAndFinalizeKey,
                    request = FinalizeUploadRequest(
                        objectVersionId = uploaded.objectVersionId,
                        etag = uploaded.etag,
                        checksumSha256 = uploaded.checksumSha256,
                    ),
                )
                if (completed.id != session.mediaId) {
                    throw IllegalStateException(
                        "Медиасервис вернул другую фотографию после завершения загрузки",
                    )
                }
                if (completed.status == "FAILED" || completed.status == "DELETED") {
                    throw IllegalStateException(MEDIA_PROCESSING_FAILED_MESSAGE)
                }
                session.mediaId
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            if (!shouldRecoverAcceptedMedia(failure)) throw failure
            // A phone can lose the response after media-service has accepted the bytes.  The
            // stable one-photo folder makes recovery exact without treating another URI with the
            // same checksum as the same user-selected photo.
            recoverAcceptedMediaId(owner, identity) ?: throw failure
        }
        return completedMediaId
    }

    private suspend fun awaitReady(
        owner: MediaOwner,
        completedMediaId: String,
    ): MediaReferenceDto = awaitReadyMediaReference(completedMediaId) {
            api.ownerMedia(
                ownerType = owner.ownerType,
                ownerId = owner.ownerId,
                documentId = owner.documentId,
                lineId = owner.lineId,
                warehouseId = owner.warehouseId,
                context = owner.context,
            ).items.firstOrNull { it.id == completedMediaId }
        }

    private suspend fun recoverAcceptedMediaId(
        owner: MediaOwner,
        identity: MediaUploadIdentity,
    ): String? = try {
        retryMediaReadAfterOwnerProof {
            api.ownerMedia(
                ownerType = owner.ownerType,
                ownerId = owner.ownerId,
                documentId = owner.documentId,
                lineId = owner.lineId,
                warehouseId = owner.warehouseId,
                context = owner.context,
            ).items.firstOrNull { media ->
                media.folderId == identity.folderId &&
                    media.status in setOf("PROCESSING", "READY")
            }?.id
        }
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        null
    }
}

/**
 * Executes the expensive upload transport with a small bounded parallelism, but exposes an
 * ordered, serialized completion stream to stateful callers.
 */
internal suspend fun <Input, Output> uploadBoundedParallelOrdered(
    inputs: List<Input>,
    parallelism: Int = MEDIA_UPLOAD_PARALLELISM,
    permits: Semaphore = Semaphore(parallelism),
    upload: suspend (Input) -> Output,
    onReady: suspend (Input, Output) -> Unit,
): List<Output> {
    return uploadAcceptedBoundedParallelOrdered(
        inputs = inputs,
        parallelism = parallelism,
        permits = permits,
        accept = upload,
        complete = { _, accepted -> accepted },
        onReady = onReady,
    )
}

/**
 * Limits only byte-heavy upload/finalize transport. Processing polls continue independently, so
 * a slow server-side transform cannot leave the mobile uplink idle while later media wait.
 */
internal suspend fun <Input, Accepted, Output> uploadAcceptedBoundedParallelOrdered(
    inputs: List<Input>,
    parallelism: Int = MEDIA_UPLOAD_PARALLELISM,
    permits: Semaphore = Semaphore(parallelism),
    accept: suspend (Input) -> Accepted,
    complete: suspend (Input, Accepted) -> Output,
    onReady: suspend (Input, Output) -> Unit,
): List<Output> {
    require(parallelism > 0) { "Параллелизм загрузки должен быть положительным" }
    return supervisorScope {
        val uploads = inputs.map { input ->
            async {
                try {
                    val accepted = permits.withPermit { accept(input) }
                    Result.success(complete(input, accepted))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Result.failure(failure)
                }
            }
        }

        /*
         * Upload work is concurrent, while callbacks deliberately remain ordered and
         * serialized. Callers use onReady to persist individual references into editor state;
         * invoking it concurrently would make a successful batch race its UI state.
         */
        var firstFailure: Throwable? = null
        val outputs = mutableListOf<Output>()
        uploads.forEachIndexed { index, upload ->
            upload.await().fold(
                onSuccess = { output ->
                    onReady(inputs[index], output)
                    outputs += output
                },
                onFailure = { failure ->
                    if (firstFailure == null) firstFailure = failure
                },
            )
        }
        firstFailure?.let { throw it }
        outputs
    }
}

private fun shouldRecoverAcceptedMedia(failure: Throwable): Boolean = when (failure) {
    is IOException -> true
    is HttpException -> failure.code() == 409 || failure.code() in 500..599
    else -> failure.cause
        ?.takeUnless { it === failure }
        ?.let(::shouldRecoverAcceptedMedia)
        ?: false
}

internal suspend fun awaitReadyMediaReference(
    mediaId: String,
    loadOwnerMedia: suspend () -> MediaAssetDto?,
): MediaReferenceDto {
    val media = awaitReadyMediaAsset(mediaId, loadOwnerMedia)
    return MediaReferenceDto(media.id, media.generation)
}

internal suspend fun awaitReadyMediaAsset(
    mediaId: String,
    loadOwnerMedia: suspend () -> MediaAssetDto?,
): MediaAssetDto {
    repeat(MEDIA_READY_ATTEMPTS) { attempt ->
        delay(mediaReadyPollDelayMillis(attempt))
        val media = try {
            loadOwnerMedia()
        } catch (_: IOException) {
            // A temporary disconnect while media-service projects an accepted upload is not a
            // terminal image-processing failure. Keep polling within the bounded window.
            null
        } catch (error: HttpException) {
            if (!isRetryableMediaOwnerFailure(error)) throw error
            null
        }
        when (media?.status) {
            "READY" -> {
                if (media.id == mediaId && media.generation > 0) {
                    return media
                }
            }
            "FAILED", "DELETED" ->
                throw IllegalStateException(MEDIA_PROCESSING_FAILED_MESSAGE)
        }
    }
    throw IllegalStateException(MEDIA_READY_TIMEOUT_MESSAGE)
}

internal suspend fun <T> retryInventoryCommitAfterMediaReady(
    operation: suspend () -> T,
): T {
    for (failureCount in 0..INVENTORY_MEDIA_READY_MAX_RETRIES) {
        try {
            return operation()
        } catch (error: HttpException) {
            if (error.code() != 422 ||
                error.problemCode() != INVENTORY_MEDIA_NOT_READY_CODE
            ) {
                throw error
            }
            if (failureCount >= INVENTORY_MEDIA_READY_MAX_RETRIES) {
                throw IllegalStateException(
                    INVENTORY_MEDIA_READY_TIMEOUT_MESSAGE,
                    error,
                )
            }
            delay(mediaReadyPollDelayMillis(failureCount))
        }
    }
    error("Недостижимое состояние ожидания готовности фотографии")
}

internal suspend fun <T> retryMediaCommandAfterOwnerProof(
    operation: suspend () -> T,
): T = retryMediaOperationAfterOwnerProof(
    exhaustedMessage = MEDIA_OWNER_RETRY_EXHAUSTED_MESSAGE,
    operation = operation,
)

internal suspend fun <T> retryMediaReadAfterOwnerProof(
    operation: suspend () -> T,
): T = retryMediaOperationAfterOwnerProof(
    exhaustedMessage = MEDIA_OWNER_READ_RETRY_EXHAUSTED_MESSAGE,
    operation = operation,
)

private suspend fun <T> retryMediaOperationAfterOwnerProof(
    exhaustedMessage: String,
    operation: suspend () -> T,
): T {
    for (failureCount in 0..OWNER_PROOF_MAX_RETRIES) {
        try {
            return operation()
        } catch (error: IOException) {
            if (failureCount >= OWNER_PROOF_MAX_RETRIES) {
                throw IllegalStateException(exhaustedMessage, error)
            }
            delay(ownerProofRetryDelayMillis(failureCount))
        } catch (error: HttpException) {
            if (!isRetryableMediaOwnerFailure(error)) {
                throw error
            }
            if (failureCount >= OWNER_PROOF_MAX_RETRIES) {
                throw IllegalStateException(exhaustedMessage, error)
            }
            delay(ownerProofRetryDelayMillis(failureCount))
        }
    }
    error("Недостижимое состояние повтора загрузки")
}

internal fun ownerProofRetryDelayMillis(failureCount: Int): Long =
    (OWNER_PROOF_RETRY_INITIAL_DELAY_MILLIS shl failureCount.coerceAtLeast(0))
        .coerceAtMost(OWNER_PROOF_RETRY_MAX_DELAY_MILLIS)

internal fun mediaReadyPollDelayMillis(failureCount: Int): Long =
    (MEDIA_READY_POLL_INITIAL_MILLIS shl failureCount.coerceIn(0, 3))
        .coerceAtMost(MEDIA_READY_POLL_MAX_MILLIS)

internal fun isRetryableMediaOwnerFailure(error: HttpException): Boolean =
    when (error.code()) {
        // Requests in these helpers are either reads or idempotent upload commands. A short,
        // bounded retry is safe for temporary gateway/service failures and owner-projection lag.
        408, 409, 425, 429, 500, 502, 503, 504 -> true
        403 -> error.problemCode() == "MEDIA_OWNER_PROOF_REQUIRED"
        else -> false
    }

internal fun HttpException.problemCode(): String? {
    val source = response()?.errorBody()?.source() ?: return null
    val raw = runCatching {
        source.request(Long.MAX_VALUE)
        source.buffer.clone().readUtf8()
    }.getOrNull() ?: return null
    return PROBLEM_CODE_PATTERN.find(raw)?.groupValues?.get(1)
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

internal fun detectImageContentType(bytes: ByteArray): String? = when {
    bytes.size >= 3 &&
        bytes[0] == 0xff.toByte() &&
        bytes[1] == 0xd8.toByte() &&
        bytes[2] == 0xff.toByte() -> "image/jpeg"

    bytes.size >= 8 &&
        bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4e,
                0x47,
                0x0d,
                0x0a,
                0x1a,
                0x0a,
            ),
        ) -> "image/png"

    bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).contentEquals("RIFF".encodeToByteArray()) &&
        bytes.copyOfRange(8, 12).contentEquals("WEBP".encodeToByteArray()) -> "image/webp"

    else -> null
}

/** The public media contract accepts exactly these original formats. */
internal fun detectMediaContentType(bytes: ByteArray): String? =
    detectImageContentType(bytes) ?: detectVideoContentType(bytes)

private fun detectVideoContentType(bytes: ByteArray): String? = when {
    bytes.size >= 12 &&
        bytes.copyOfRange(4, 8).contentEquals("ftyp".encodeToByteArray()) -> "video/mp4"

    bytes.size >= 4 &&
        bytes[0] == 0x1a.toByte() &&
        bytes[1] == 0x45.toByte() &&
        bytes[2] == 0xdf.toByte() &&
        bytes[3] == 0xa3.toByte() -> "video/webm"

    else -> null
}

private fun mediaFileExtension(contentType: String): String = when (contentType) {
    "image/jpeg" -> ".jpg"
    "image/png" -> ".png"
    "image/webp" -> ".webp"
    "video/mp4" -> ".mp4"
    "video/webm" -> ".webm"
    else -> error("Unsupported canonical media content type: $contentType")
}

private val PROBLEM_CODE_PATTERN = Regex(""""code"\s*:\s*"([^"]+)"""")
internal const val OWNER_PROOF_MAX_RETRIES = 8
internal const val MEDIA_OWNER_RETRY_EXHAUSTED_MESSAGE =
    "Фотография пока не может быть привязана. Подождите несколько секунд и повторите сохранение"
internal const val MEDIA_OWNER_READ_RETRY_EXHAUSTED_MESSAGE =
    "Фотография пока недоступна. Подождите несколько секунд и повторите просмотр"
internal const val MEDIA_PROCESSING_FAILED_MESSAGE =
    "Обработка фотографии завершилась ошибкой. Загрузите фотографию заново"
internal const val MEDIA_READY_TIMEOUT_MESSAGE =
    "Фотография загружена, но обработка не завершилась вовремя. Повторите сохранение"
internal const val INVENTORY_MEDIA_READY_TIMEOUT_MESSAGE =
    "Фотография обработана, но инвентаризация ещё не получила подтверждение готовности. Повторите сохранение через несколько секунд"
internal const val INVENTORY_MEDIA_READY_MAX_RETRIES = 8
internal const val MEDIA_UPLOAD_PARALLELISM = 4
private const val INVENTORY_MEDIA_NOT_READY_CODE = "INVENTORY_MEDIA_NOT_READY"
private const val OWNER_PROOF_RETRY_INITIAL_DELAY_MILLIS = 250L
private const val OWNER_PROOF_RETRY_MAX_DELAY_MILLIS = 2_000L
internal const val MEDIA_READY_ATTEMPTS = 20
private const val MEDIA_READY_POLL_INITIAL_MILLIS = 250L
private const val MEDIA_READY_POLL_MAX_MILLIS = 2_000L
