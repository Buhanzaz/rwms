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
    maxCacheFiles: Int = DEFAULT_MAX_CACHE_FILES,
    maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
) {
    private val mediaCacheDir = File(cacheDir, "manager-remote-media")
    private val cacheLocks = List(16) { Mutex() }
    private val cache = RetainedMediaCache(mediaCacheDir, maxCacheFiles, maxCacheBytes)

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
            cache.begin(stem)
            try {
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
                // A disconnect can happen while a successful HTTP response is being copied to
                // the local cache, not just before headers arrive. Retry the whole idempotent read
                // so a supplement does not lose a photo because its body was interrupted.
                val uri = try {
                    withContext(ioDispatcher) {
                        cacheLock(stem).withLock {
                            cache.find(stem)?.let(Uri::fromFile)?.toString()
                                ?: persistBody(stem, body, expectedContentType)
                        }
                    }
                } finally {
                    withContext(NonCancellable + ioDispatcher) { body.close() }
                }
                check(cache.retain(uri)) { "Медиафайл исчез из временного кэша" }
                uri
            } finally {
                cache.end(stem)
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
            cache.begin(stem)
            try {
                val body = api.mediaVariantContent(contentPath)
                val uri = try {
                    withContext(ioDispatcher) {
                        cacheLock(stem).withLock {
                            cache.find(stem)?.let(Uri::fromFile)?.toString()
                                ?: persistBody(stem, body)
                        }
                    }
                } finally {
                    withContext(NonCancellable + ioDispatcher) { body.close() }
                }
                check(cache.retain(uri)) { "Медиафайл исчез из временного кэша" }
                uri
            } finally {
                cache.end(stem)
            }
        }
    }

    /** Adds one ownership claim for an indexed remote-media URI. */
    internal fun retain(uri: String): Boolean = cache.retain(uri)

    /** Drops one ownership claim and evicts least-recently-used unowned entries when over budget. */
    internal fun release(uri: String) = cache.release(uri)

    /** Uses bounded striped locks so concurrent requests for one immutable generation promote once. */
    private fun cacheLock(stem: String): Mutex =
        cacheLocks[(stem.hashCode() and Int.MAX_VALUE) % cacheLocks.size]

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
            cache.register(stem, target)
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
        return Uri.fromFile(target).toString()
    }

    private companion object {
        const val DEFAULT_MAX_CACHE_FILES = 256
        const val DEFAULT_MAX_CACHE_BYTES = 256L * 1024L * 1024L
        val UNSAFE_FILE_NAME = Regex("[^A-Za-z0-9._-]")
    }
}

/** In-memory ownership and LRU index for the manager's remote-media cache directory. */
internal class RetainedMediaCache(
    private val directory: File,
    private val maxFiles: Int,
    private val maxBytes: Long,
    private val deleteFile: (File) -> Boolean = File::delete,
) {
    private data class Entry(
        val stem: String,
        val file: File,
        val size: Long,
        var lastAccess: Long,
        var references: Int = 0,
    )

    private val entriesByStem = linkedMapOf<String, Entry>()
    private val entriesByPath = mutableMapOf<String, Entry>()
    private val inFlightByStem = mutableMapOf<String, Int>()
    private var accessSequence = 0L

    init {
        require(maxFiles > 0) { "Лимит количества файлов кэша должен быть положительным" }
        require(maxBytes > 0L) { "Лимит размера кэша должен быть положительным" }
        directory.listFiles().orEmpty()
            .asSequence()
            .filter { file -> file.isFile && file.extension != "part" && file.length() > 0L }
            .sortedBy(File::lastModified)
            .forEach { file -> index(file.nameWithoutExtension, file) }
        prune()
    }

    @Synchronized
    fun begin(stem: String) {
        inFlightByStem[stem] = inFlightByStem.getOrDefault(stem, 0) + 1
    }

    @Synchronized
    fun end(stem: String) {
        val remaining = inFlightByStem.getOrDefault(stem, 0) - 1
        check(remaining >= 0) { "Завершена неизвестная загрузка медиафайла" }
        if (remaining == 0) inFlightByStem.remove(stem) else inFlightByStem[stem] = remaining
        prune()
    }

    @Synchronized
    fun find(stem: String): File? {
        val entry = entriesByStem[stem] ?: return null
        if (!entry.file.isFile || entry.file.length() <= 0L) {
            remove(entry, delete = false)
            return null
        }
        entry.lastAccess = nextAccess()
        return entry.file
    }

    @Synchronized
    fun register(stem: String, file: File) {
        check(file.isFile && file.length() > 0L) { "Нельзя зарегистрировать пустой медиафайл" }
        val entry = index(stem, file)
        entry.lastAccess = nextAccess()
        prune()
    }

    @Synchronized
    fun retain(uri: String): Boolean {
        val path = Uri.parse(uri).path ?: return false
        val entry = entriesByPath[File(path).absolutePath] ?: return false
        if (!entry.file.isFile || entry.file.length() <= 0L) {
            remove(entry, delete = false)
            return false
        }
        entry.references++
        entry.lastAccess = nextAccess()
        return true
    }

    @Synchronized
    fun release(uri: String) {
        val path = Uri.parse(uri).path ?: return
        val entry = entriesByPath[File(path).absolutePath] ?: return
        if (entry.references > 0) entry.references--
        prune()
    }

    private fun index(stem: String, file: File): Entry {
        val path = file.absolutePath
        entriesByPath[path]?.let { indexed ->
            if (indexed.stem == stem) return indexed
        }
        entriesByStem[stem]?.let { previous ->
            if (previous.file.absolutePath != file.absolutePath) remove(previous, delete = true)
        }
        entriesByPath[path]?.let { previous ->
            if (previous.stem != stem) remove(previous, delete = false)
        }
        return Entry(stem, file, file.length(), nextAccess()).also { entry ->
            entriesByStem[stem] = entry
            entriesByPath[path] = entry
        }
    }

    private fun prune() {
        var totalBytes = entriesByStem.values.sumOf(Entry::size)
        val failedDeletionPaths = mutableSetOf<String>()
        while (entriesByStem.size > maxFiles || totalBytes > maxBytes) {
            val candidate = entriesByStem.values
                .asSequence()
                .filter { entry ->
                    entry.references == 0 && inFlightByStem.getOrDefault(entry.stem, 0) == 0
                }
                .filterNot { entry -> entry.file.absolutePath in failedDeletionPaths }
                .minByOrNull(Entry::lastAccess)
                ?: return
            if (remove(candidate, delete = true)) {
                totalBytes -= candidate.size
            } else {
                failedDeletionPaths += candidate.file.absolutePath
            }
        }
    }

    private fun remove(entry: Entry, delete: Boolean): Boolean {
        if (delete && entry.file.exists() && !deleteFile(entry.file)) return false
        if (entriesByStem[entry.stem] === entry) entriesByStem.remove(entry.stem)
        if (entriesByPath[entry.file.absolutePath] === entry) {
            entriesByPath.remove(entry.file.absolutePath)
        }
        return true
    }

    private fun nextAccess(): Long = ++accessSequence
}
