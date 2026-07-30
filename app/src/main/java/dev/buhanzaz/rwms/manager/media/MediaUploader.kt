package dev.buhanzaz.rwms.manager.media

import android.content.ContentResolver
import android.net.Uri
import dev.buhanzaz.rwms.manager.network.CreateUploadSessionRequest
import dev.buhanzaz.rwms.manager.network.FinalizeUploadRequest
import dev.buhanzaz.rwms.manager.network.MediaAssetDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RotateMediaRequest
import dev.buhanzaz.rwms.manager.network.RwmsApi
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

data class MediaOwner(
    val ownerType: String,
    val warehouseId: String,
    val context: String,
    val ownerId: String? = null,
    val documentId: String? = null,
    val lineId: String? = null,
)

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
            ?: "rwms-media-${UUID.randomUUID()}${mediaFileExtension(contentType)}"

        /*
         * This is deliberately an exact byte read. CameraX writes image orientation as
         * JPEG/EXIF metadata when needed; decoding, recompressing, or rewrapping a video
         * here would change the immutable original before media-service processes it.
         */
        return PhotoPayload(
            fileName = fileName,
            contentType = contentType,
            bytes = bytes,
            checksumSha256 = bytes.sha256(),
        )
    }
}

class MediaUploader private constructor(
    private val api: RwmsApi,
    private val payloadLoader: (String) -> PhotoPayload,
) {
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
    ): List<MediaReferenceDto> {
        require(photoUris.isNotEmpty()) { "Добавьте хотя бы одну фотографию" }
        val folderId = UUID.randomUUID().toString()
        return photoUris.mapIndexed { index, uri ->
            uploadOne(owner, folderId, index, payloadLoader(uri))
        }
    }

    /**
     * Rebuilds the canonical original for the same media ID in media-service.  It never creates
     * a second attachment or re-encodes a phone-side replacement file: media-service allocates a
     * new immutable generation of this asset and returns that generation once it is READY.
     */
    suspend fun rotate(
        owner: MediaOwner,
        asset: MediaAssetDto,
        rotationDegrees: Int,
    ): MediaReferenceDto {
        require(rotationDegrees in setOf(0, 90, 180, 270)) {
            "Недопустимый поворот фотографии"
        }
        if (asset.status == "FAILED" || asset.status == "DELETED") {
            throw IllegalStateException(MEDIA_PROCESSING_FAILED_MESSAGE)
        }
        val current = if (asset.status == "READY") {
            asset
        } else {
            awaitReadyMediaAsset(asset.id) {
                api.ownerMedia(
                    ownerType = owner.ownerType,
                    ownerId = owner.ownerId,
                    documentId = owner.documentId,
                    lineId = owner.lineId,
                    warehouseId = owner.warehouseId,
                    context = owner.context,
                ).items.firstOrNull { it.id == asset.id }
            }
        }
        if (current.rotationDegrees == rotationDegrees) {
            return MediaReferenceDto(current.id, current.generation)
        }
        require(current.generation > 0 && current.version > 0) {
            "Фотография ещё не готова к повороту"
        }
        val idempotencyKey = UUID.randomUUID().toString()
        val queued = retryMediaCommandAfterOwnerProof {
            api.rotateMedia(
                mediaId = current.id,
                idempotencyKey = idempotencyKey,
                ownerType = owner.ownerType,
                ownerId = owner.ownerId,
                documentId = owner.documentId,
                lineId = owner.lineId,
                warehouseId = owner.warehouseId,
                context = owner.context,
                request = RotateMediaRequest(
                    rotationDegrees = rotationDegrees,
                    expectedVersion = current.version,
                ),
            )
        }
        if (queued.id != current.id || queued.status == "FAILED" || queued.status == "DELETED") {
            throw IllegalStateException(MEDIA_PROCESSING_FAILED_MESSAGE)
        }
        val ready = awaitReadyMediaAsset(current.id) {
            api.ownerMedia(
                ownerType = owner.ownerType,
                ownerId = owner.ownerId,
                documentId = owner.documentId,
                lineId = owner.lineId,
                warehouseId = owner.warehouseId,
                context = owner.context,
            ).items.firstOrNull { it.id == current.id }
        }
        if (ready.rotationDegrees != rotationDegrees) {
            throw IllegalStateException("Сервис не сохранил поворот фотографии")
        }
        return MediaReferenceDto(ready.id, ready.generation)
    }

    private suspend fun uploadOne(
        owner: MediaOwner,
        folderId: String,
        sortOrder: Int,
        photo: PhotoPayload,
    ): MediaReferenceDto {
        val createSessionKey = UUID.randomUUID().toString()
        val uploadAndFinalizeKey = UUID.randomUUID().toString()
        val completedMediaId = retryMediaCommandAfterOwnerProof {
            val session = api.createUploadSession(
                createSessionKey,
                CreateUploadSessionRequest(
                    ownerType = owner.ownerType,
                    ownerId = owner.ownerId,
                    documentId = owner.documentId,
                    lineId = owner.lineId,
                    warehouseId = owner.warehouseId,
                    context = owner.context,
                    folderId = folderId,
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
                idempotencyKey = uploadAndFinalizeKey,
                body = photo.bytes.toRequestBody(photo.contentType.toMediaType()),
            )
            val completed = api.finalizeUpload(
                uploadSessionId = session.uploadSessionId,
                idempotencyKey = uploadAndFinalizeKey,
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
        return awaitReadyMediaReference(completedMediaId) {
            api.ownerMedia(
                ownerType = owner.ownerType,
                ownerId = owner.ownerId,
                documentId = owner.documentId,
                lineId = owner.lineId,
                warehouseId = owner.warehouseId,
                context = owner.context,
            ).items.firstOrNull { it.id == completedMediaId }
        }
    }
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

@Deprecated("Use retryMediaCommandAfterOwnerProof")
internal suspend fun <T> retryCreateUploadSessionAfterOwnerProof(
    operation: suspend () -> T,
): T = retryMediaCommandAfterOwnerProof(operation)

internal fun ownerProofRetryDelayMillis(failureCount: Int): Long =
    (OWNER_PROOF_RETRY_INITIAL_DELAY_MILLIS shl failureCount.coerceAtLeast(0))
        .coerceAtMost(OWNER_PROOF_RETRY_MAX_DELAY_MILLIS)

internal fun mediaReadyPollDelayMillis(failureCount: Int): Long =
    (MEDIA_READY_POLL_INITIAL_MILLIS shl failureCount.coerceIn(0, 3))
        .coerceAtMost(MEDIA_READY_POLL_MAX_MILLIS)

internal fun isRetryableMediaOwnerFailure(error: HttpException): Boolean =
    when (error.code()) {
        409, 503 -> true
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
private const val INVENTORY_MEDIA_NOT_READY_CODE = "INVENTORY_MEDIA_NOT_READY"
private const val OWNER_PROOF_RETRY_INITIAL_DELAY_MILLIS = 250L
private const val OWNER_PROOF_RETRY_MAX_DELAY_MILLIS = 2_000L
internal const val MEDIA_READY_ATTEMPTS = 20
private const val MEDIA_READY_POLL_INITIAL_MILLIS = 250L
private const val MEDIA_READY_POLL_MAX_MILLIS = 2_000L
