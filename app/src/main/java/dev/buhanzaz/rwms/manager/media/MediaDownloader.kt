package dev.buhanzaz.rwms.manager.media

import android.net.Uri
import dev.buhanzaz.rwms.manager.network.RwmsApi
import java.io.File
import java.util.UUID
import okhttp3.ResponseBody

/**
 * Encapsulates manager media transfer or retrieval through the public gateway.
 */
class MediaDownloader(
    private val api: RwmsApi,
    cacheDir: File,
) {
    private val mediaCacheDir = File(cacheDir, "manager-remote-media")

    suspend fun downloadOriginal(
        mediaId: String,
        generation: Long,
        ownerType: String,
        ownerId: String? = null,
        documentId: String? = null,
        lineId: String? = null,
        warehouseId: String,
        context: String,
    ): String {
        val safeMediaId = mediaId.replace(UNSAFE_FILE_NAME, "_")
        val stem = "$safeMediaId-$generation"
        mediaCacheDir.listFiles()
            ?.firstOrNull { file -> file.nameWithoutExtension == stem && file.length() > 0L }
            ?.let { cached -> return Uri.fromFile(cached).toString() }

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
            persistBody(stem, body)
        }
    }

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
            "Медиасервис вернул небезопасный путь фотографии"
        }
        val safeMediaId = mediaId.replace(UNSAFE_FILE_NAME, "_")
        val stem = "$safeMediaId-$generation-preview"
        mediaCacheDir.listFiles()
            ?.firstOrNull { file -> file.nameWithoutExtension == stem && file.length() > 0L }
            ?.let { cached -> return Uri.fromFile(cached).toString() }
        return retryMediaReadAfterOwnerProof {
            persistBody(stem, api.mediaVariantContent(contentPath))
        }
    }

    private fun persistBody(stem: String, body: ResponseBody): String {
        val extension = when (body.contentType()?.toString()?.substringBefore(';')) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        mediaCacheDir.mkdirs()
        val target = File(mediaCacheDir, "$stem.$extension")
        val temporary = File(mediaCacheDir, "$stem-${UUID.randomUUID()}.$extension.part")
        try {
            body.use { response ->
                response.byteStream().use { input ->
                    temporary.outputStream().use(input::copyTo)
                }
            }
            require(temporary.length() > 0L) { "Медиасервис вернул пустую фотографию" }
            if (target.exists()) target.delete()
            check(temporary.renameTo(target)) {
                "Не удалось сохранить фотографию во временный кэш"
            }
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
