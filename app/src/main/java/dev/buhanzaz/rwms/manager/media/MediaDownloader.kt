package dev.buhanzaz.rwms.manager.media

import android.net.Uri
import dev.buhanzaz.rwms.manager.network.RwmsApi
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody

private val MEDIA_PROMOTION_LOCKS = Array(16) { Any() }

/** Returns the process-wide stripe guarding visibility of one final cache path. */
private fun mediaPromotionLock(target: File): Any {
    val index = (target.absolutePath.hashCode() and Int.MAX_VALUE) % MEDIA_PROMOTION_LOCKS.size
    return MEDIA_PROMOTION_LOCKS[index]
}

/**
 * Promotes a fully downloaded cache part and falls back to a verified stream copy when Android's
 * legacy [File.renameTo] implementation refuses an otherwise valid same-directory move. A fixed
 * path lock prevents a failed concurrent writer from deleting an already complete immutable file.
 */
internal fun promoteCompleteMediaCacheFile(
    temporary: File,
    target: File,
    rename: (File, File) -> Boolean = { source, destination -> source.renameTo(destination) },
) {
    synchronized(mediaPromotionLock(target)) {
        val expectedLength = temporary.length()
        require(expectedLength > 0L) { "Нельзя сохранить пустой медиафайл" }
        if (target.length() > 0L) {
            temporary.delete()
            return
        }
        if (target.exists() && !target.delete()) {
            error("Не удалось удалить повреждённый файл временного кэша")
        }
        if (rename(temporary, target)) {
            check(target.length() == expectedLength) {
                target.delete()
                "Не удалось полностью сохранить медиафайл во временный кэш"
            }
            return
        }

        try {
            temporary.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            check(target.length() == expectedLength) {
                "Не удалось полностью сохранить медиафайл во временный кэш"
            }
            temporary.delete()
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }
}

/**
 * Retrieves manager media through the public gateway and persists streaming bodies off the UI
 * dispatcher before exposing an app-private file URI.
 */
class MediaDownloader(
    private val api: RwmsApi,
    cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mediaCacheDir = File(cacheDir, "manager-remote-media")
    private val cacheLocks = List(16) { Mutex() }

    /**
     * Reauthorizes the exact owner, media identity and generation through the streaming original
     * endpoint before any previously cached immutable bytes may be reused.
     */
    suspend fun downloadOriginal(
        mediaId: String,
        generation: Long,
        ownerType: String,
        ownerId: String? = null,
        documentId: String? = null,
        lineId: String? = null,
        warehouseId: String,
        context: String,
        expectedContentType: String? = null,
    ): String {
        val safeMediaId = mediaId.replace(UNSAFE_FILE_NAME, "_")
        val stem = "$safeMediaId-$generation"

        return retryMediaReadAfterOwnerProof {
            val body = api.originalMedia(
                mediaId = mediaId,
                generation = generation,
                ownerType = ownerType,
                ownerId = ownerId,
                documentId = documentId,
                lineId = lineId,
                warehouseId = warehouseId,
                context = context,
            )
            // A disconnect can happen while a successful HTTP response is being copied to the
            // local cache, not just before headers arrive. Retry the whole idempotent read so a
            // supplement does not lose a photo merely because its response body was interrupted.
            try {
                withContext(ioDispatcher) {
                    cacheLock(stem).withLock {
                        cachedMedia(stem)?.let { cached ->
                            Uri.fromFile(cached).toString()
                        } ?: persistBody(stem, body, expectedContentType)
                    }
                }
            } finally {
                withContext(NonCancellable + ioDispatcher) { body.close() }
            }
        }
    }

    /**
     * Reauthorizes the owner-scoped content path before reusing a variant cache entry and
     * serializes concurrent first writes for the same immutable generation.
     */
    suspend fun downloadVariant(
        mediaId: String,
        generation: Long,
        contentPath: String,
    ): String {
        require(
            contentPath.startsWith("/api/media/v1/assets/$mediaId/variants/") &&
                "://" !in contentPath &&
                '\n' !in contentPath &&
                '\r' !in contentPath,
        ) {
            "Медиасервис вернул небезопасный путь медиафайла"
        }
        val safeMediaId = mediaId.replace(UNSAFE_FILE_NAME, "_")
        val variantKey = contentPath
            .substringAfter("/variants/")
            .substringBefore('/')
            .replace(UNSAFE_FILE_NAME, "_")
        val stem = "$safeMediaId-$generation-$variantKey"
        return retryMediaReadAfterOwnerProof {
            val body = api.mediaVariantContent(contentPath)
            try {
                withContext(ioDispatcher) {
                    cacheLock(stem).withLock {
                        cachedMedia(stem)?.let { cached ->
                            Uri.fromFile(cached).toString()
                        } ?: persistBody(stem, body)
                    }
                }
            } finally {
                withContext(NonCancellable + ioDispatcher) { body.close() }
            }
        }
    }

    /** Uses bounded striped locks so concurrent requests for one immutable generation promote once. */
    private fun cacheLock(stem: String): Mutex =
        cacheLocks[(stem.hashCode() and Int.MAX_VALUE) % cacheLocks.size]

    /**
     * Returns only a completed cache entry while sharing the promotion stripe across downloader
     * instances; a fallback copy cannot become visible at a partial length.
     */
    private fun cachedMedia(stem: String): File? = mediaCacheDir.listFiles()
        ?.firstNotNullOfOrNull { file ->
            if (file.extension == "part" || file.nameWithoutExtension != stem) {
                null
            } else {
                synchronized(mediaPromotionLock(file)) {
                    file.takeIf { candidate -> candidate.exists() && candidate.length() > 0L }
                }
            }
        }

    /** Writes a response to a random part, validates it, then exposes one complete cache target. */
    private fun persistBody(
        stem: String,
        body: ResponseBody,
        expectedContentType: String? = null,
    ): String {
        val contentType = body.contentType()?.toString()?.substringBefore(';')
            ?: expectedContentType?.substringBefore(';')
        val extension = when (contentType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            else -> "jpg"
        }
        check(mediaCacheDir.isDirectory || mediaCacheDir.mkdirs()) {
            "Не удалось подготовить временный кэш медиафайлов"
        }
        val target = File(mediaCacheDir, "$stem.$extension")
        val temporary = File(mediaCacheDir, "$stem-${UUID.randomUUID()}.$extension.part")
        try {
            val expectedLength = body.contentLength()
            body.use { response ->
                response.byteStream().use { input ->
                    temporary.outputStream().use(input::copyTo)
                }
            }
            require(temporary.length() > 0L) { "Медиасервис вернул пустой медиафайл" }
            require(expectedLength < 0L || temporary.length() == expectedLength) {
                "Медиасервис передал медиафайл не полностью"
            }
            promoteCompleteMediaCacheFile(temporary, target)
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
        return Uri.fromFile(target).toString()
    }

    private companion object {
        val UNSAFE_FILE_NAME = Regex("[^A-Za-z0-9._-]")
    }
}
