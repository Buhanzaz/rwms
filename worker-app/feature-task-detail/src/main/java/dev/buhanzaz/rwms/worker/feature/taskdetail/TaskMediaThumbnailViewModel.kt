package dev.buhanzaz.rwms.worker.feature.taskdetail

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import dev.buhanzaz.rwms.worker.core.ui.decodeWorkerBitmap
import dev.buhanzaz.rwms.worker.core.ui.readWorkerImageBytes
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Defines worker feature UI state; server data and authorization remain authoritative.
 */
sealed interface TaskMediaThumbnail {
    data object Loading : TaskMediaThumbnail
    data class Ready(val bitmap: Bitmap) : TaskMediaThumbnail
    data class Failed(val message: String) : TaskMediaThumbnail
}

/**
 * Downloads server-provided media paths through the authenticated gateway.
 * Thumbnails never use MinIO/storage URLs directly and are bounded before
 * decoding so an unexpected original cannot exhaust the worker app.
 */
@HiltViewModel
class TaskMediaThumbnailViewModel @Inject constructor(
    private val gateway: WorkerGatewayClient,
) : ViewModel() {
    private val mutableThumbnails = MutableStateFlow<Map<String, TaskMediaThumbnail>>(emptyMap())
    val thumbnails: StateFlow<Map<String, TaskMediaThumbnail>> = mutableThumbnails.asStateFlow()
    private val admittedPaths = linkedSetOf<String>()
    private val requestedGenerations = mutableMapOf<String, Long>()
    private var loadGeneration = 0L

    /** Loads one thumbnail while bounding the task-detail cache to recent visible media. */
    fun load(path: String) {
        if (path.isBlank()) return
        val previousPaths = admittedPaths.toSet()
        val nextPaths = workerThumbnailCachePaths(
            currentPaths = admittedPaths,
            requestedPath = path,
            maxEntries = MAX_CACHED_THUMBNAILS,
        )
        admittedPaths.clear()
        admittedPaths.addAll(nextPaths)
        val evicted = previousPaths - admittedPaths
        if (evicted.isNotEmpty()) {
            requestedGenerations.keys.removeAll(evicted)
            mutableThumbnails.value = mutableThumbnails.value - evicted
        }
        if (mutableThumbnails.value.containsKey(path)) return
        loadGeneration += 1
        val generation = loadGeneration
        requestedGenerations[path] = generation
        mutableThumbnails.value = mutableThumbnails.value + (path to TaskMediaThumbnail.Loading)
        viewModelScope.launch {
            val next: TaskMediaThumbnail = try {
                withContext(Dispatchers.IO) {
                    gateway.mediaContent(path).use { body ->
                        val declaredSize = body.contentLength()
                        require(declaredSize < 0 || declaredSize <= MAX_MEDIA_BYTES) {
                            "Файл слишком большой для миниатюры"
                        }
                        val bytes = body.byteStream().use { input ->
                            input.readWorkerImageBytes(MAX_MEDIA_BYTES)
                        }
                        decodeWorkerBitmap(bytes, MAX_THUMBNAIL_PIXELS)
                    }
                }
                    .let(TaskMediaThumbnail::Ready)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                TaskMediaThumbnail.Failed(error.message ?: "Не удалось загрузить фото")
            }
            if (path !in admittedPaths || requestedGenerations[path] != generation) {
                if (next is TaskMediaThumbnail.Ready && !next.bitmap.isRecycled) {
                    next.bitmap.recycle()
                }
                return@launch
            }
            mutableThumbnails.value = mutableThumbnails.value + (path to next)
        }
    }

    private companion object {
        const val MAX_MEDIA_BYTES = 15 * 1024 * 1024
        const val MAX_THUMBNAIL_PIXELS = 256_000L
        const val MAX_CACHED_THUMBNAILS = 16
    }
}

/** Returns a recency-ordered, duplicate-free thumbnail cache bounded by [maxEntries]. */
internal fun workerThumbnailCachePaths(
    currentPaths: Collection<String>,
    requestedPath: String,
    maxEntries: Int,
): List<String> {
    require(maxEntries > 0) { "Thumbnail cache size must be positive" }
    val ordered = LinkedHashSet(currentPaths)
    ordered.remove(requestedPath)
    ordered.add(requestedPath)
    while (ordered.size > maxEntries) ordered.remove(ordered.first())
    return ordered.toList()
}
