package dev.buhanzaz.rwms.worker

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import dev.buhanzaz.rwms.worker.core.ui.decodeWorkerBitmap
import dev.buhanzaz.rwms.worker.core.ui.readWorkerImageBytes
import java.util.Collections
import java.util.IdentityHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** One pager item with its fast SMALL preview and immutable original media path. */
internal data class PhotoMediaItem(
    val previewPath: String,
    val readPath: String,
) {
    init {
        require(previewPath.isNotBlank()) { "Photo preview path must not be blank" }
        require(readPath.isNotBlank()) { "Photo read path must not be blank" }
    }
}

/** Loads and bounds one authenticated bitmap without introducing an unencrypted disk cache. */
interface PhotoBitmapSource {
    /** Downloads [path] and decodes it inside the supplied byte and pixel budgets. */
    suspend fun load(path: String, maxBytes: Int, maxPixels: Long): Bitmap
}

/** Public-gateway implementation of [PhotoBitmapSource]. */
internal class GatewayPhotoBitmapSource(
    private val gateway: WorkerGatewayClient,
) : PhotoBitmapSource {
    override suspend fun load(path: String, maxBytes: Int, maxPixels: Long): Bitmap {
        var decoded: Bitmap? = null
        return try {
            withContext(Dispatchers.IO) {
                gateway.mediaContent(path).use { body ->
                    val declaredSize = body.contentLength()
                    require(declaredSize < 0 || declaredSize <= maxBytes) {
                        "Файл слишком большой для просмотра"
                    }
                    val bytes = body.byteStream().use { input ->
                        input.readWorkerImageBytes(maxBytes)
                    }
                    decodeWorkerBitmap(bytes, maxPixels).also { decoded = it }
                }
            }
        } catch (error: CancellationException) {
            decoded?.recycleSafely()
            throw error
        } catch (error: Throwable) {
            decoded?.recycleSafely()
            throw error
        }
    }
}

/** Defines the progressively loaded full-screen photo state. */
data class PhotoUiState(
    val bitmaps: Map<String, Bitmap> = emptyMap(),
    val originalPaths: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val loadingOriginals: Set<String> = emptySet(),
)

/**
 * Owns route-local preview/original jobs and bounded bitmap caches. Each path is cancelled
 * independently, so swiping cannot invalidate a still-useful neighbour or a viewed preview.
 */
@HiltViewModel
class PhotoViewModel @Inject constructor(
    private val source: PhotoBitmapSource,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PhotoUiState())
    val state: StateFlow<PhotoUiState> = mutableState.asStateFlow()

    private val previewCache = BitmapLruCache(MAX_PREVIEW_CACHE_ENTRIES)
    private val originalCache = BitmapLruCache(MAX_ORIGINAL_CACHE_ENTRIES)
    private val previewJobs = mutableMapOf<String, Job>()
    private val originalJobs = mutableMapOf<String, Job>()
    private val previewFailures = mutableSetOf<String>()
    private val originalErrors = mutableMapOf<String, String>()
    private val retiredBitmaps = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
    private val downloadPermits = Semaphore(MAX_PARALLEL_PHOTO_DOWNLOADS)

    private var collection: List<PhotoMediaItem> = emptyList()
    private var catalog: Map<String, PhotoMediaItem> = emptyMap()
    private var activeKeys: Set<String> = emptySet()
    private var currentKey: String? = null

    /**
     * Selects one page, starts its SMALL preview before its original, and preloads only neighbour
     * previews. Existing per-path results remain valid and are retained by bounded LRU caches.
     */
    internal fun show(items: List<PhotoMediaItem>, selectedIndex: Int) {
        if (items != collection) replaceCollection(items)
        if (items.isEmpty()) {
            activeKeys = emptySet()
            currentKey = null
            cancelObsoleteJobs()
            publishState()
            return
        }

        val selected = photoPagerInitialPage(selectedIndex, items.size)
        val selectedItem = items[selected]
        currentKey = selectedItem.readPath
        activeKeys = photoPagerLoadWindow(items.map(PhotoMediaItem::readPath), selected).toSet()
        cancelObsoleteJobs()

        previewCache.get(selectedItem.readPath)
        originalCache.get(selectedItem.readPath)
        // A failed neighbour preview gets one fresh attempt when that page becomes selected.
        previewFailures.remove(selectedItem.readPath)
        val selectedPreviewJob = ensurePreview(selectedItem)
        activeKeys.asSequence()
            .filterNot { it == selectedItem.readPath }
            .mapNotNull(catalog::get)
            .forEach(::ensurePreview)
        ensureOriginal(selectedItem, selectedPreviewJob)
        publishState()
    }

    /** Retries only the selected path and preserves every other page's useful result. */
    fun retry(readPath: String) {
        if (readPath !in activeKeys) return
        val item = catalog[readPath] ?: return
        previewFailures.remove(readPath)
        originalErrors.remove(readPath)
        val previewJob = ensurePreview(item)
        if (readPath == currentKey) ensureOriginal(item, previewJob)
        publishState()
    }

    private fun replaceCollection(items: List<PhotoMediaItem>) {
        previewJobs.values.forEach { it.cancel() }
        originalJobs.values.forEach { it.cancel() }
        previewJobs.clear()
        originalJobs.clear()
        collection = items
        catalog = items.associateBy(PhotoMediaItem::readPath)
        activeKeys = emptySet()
        currentKey = null
        previewFailures.clear()
        originalErrors.clear()
        val retired = previewCache.clear() + originalCache.clear()
        publishState()
        recycleAfterPublication(retired)
    }

    /** Cancels only jobs whose own page is no longer useful to the current pager position. */
    private fun cancelObsoleteJobs() {
        previewJobs.keys.filterNot(activeKeys::contains).forEach { key ->
            previewJobs.remove(key)?.cancel()
        }
        originalJobs.keys.filterNot { it == currentKey }.forEach { key ->
            originalJobs.remove(key)?.cancel()
        }
    }

    private fun ensurePreview(item: PhotoMediaItem): Job? {
        val key = item.readPath
        if (
            originalCache.get(key) != null ||
            previewCache.get(key) != null ||
            key in previewFailures
        ) {
            return null
        }
        previewJobs[key]?.let { return it }
        lateinit var job: Job
        job = viewModelScope.launch {
            var decoded: Bitmap? = null
            try {
                val bitmap = downloadPermits.withPermit {
                    source.load(item.previewPath, MAX_PREVIEW_BYTES, MAX_PREVIEW_PIXELS)
                }.also { decoded = it }
                if (catalog[key] != item) {
                    bitmap.recycleSafely()
                    decoded = null
                    return@launch
                }
                previewFailures.remove(key)
                val retired = previewCache.put(key, bitmap)
                decoded = null
                publishState()
                recycleAfterPublication(retired)
            } catch (error: CancellationException) {
                decoded?.recycleSafely()
                throw error
            } catch (_: Throwable) {
                decoded?.recycleSafely()
                if (catalog[key] == item) previewFailures += key
            } finally {
                if (previewJobs[key] === job) previewJobs.remove(key)
                publishState()
            }
        }
        previewJobs[key] = job
        return job
    }

    private fun ensureOriginal(item: PhotoMediaItem, previewJob: Job?) {
        val key = item.readPath
        if (originalCache.get(key) != null || key in originalErrors || key in originalJobs) return
        lateinit var job: Job
        job = viewModelScope.launch {
            var decoded: Bitmap? = null
            try {
                previewJob?.join()
                if (currentKey != key || catalog[key] != item) return@launch
                val bitmap = downloadPermits.withPermit {
                    source.load(item.readPath, MAX_ORIGINAL_BYTES, MAX_ORIGINAL_PIXELS)
                }.also { decoded = it }
                if (currentKey != key || catalog[key] != item) {
                    bitmap.recycleSafely()
                    decoded = null
                    return@launch
                }
                originalErrors.remove(key)
                val retired = originalCache.put(key, bitmap)
                decoded = null
                publishState()
                recycleAfterPublication(retired)
            } catch (error: CancellationException) {
                decoded?.recycleSafely()
                throw error
            } catch (error: Throwable) {
                decoded?.recycleSafely()
                if (currentKey == key && catalog[key] == item) {
                    originalErrors[key] = error.message ?: "Не удалось открыть фото"
                }
            } finally {
                if (originalJobs[key] === job) originalJobs.remove(key)
                publishState()
            }
        }
        originalJobs[key] = job
    }

    private fun publishState() {
        val previews = previewCache.snapshot()
        val originals = originalCache.snapshot()
        mutableState.value = PhotoUiState(
            bitmaps = previews + originals,
            originalPaths = originals.keys,
            errors = originalErrors.toMap(),
            loading = previewJobs.keys + originalJobs.keys,
            loadingOriginals = originalJobs.keys.toSet(),
        )
    }

    /**
     * Waits for Compose to observe the cache update before recycling an evicted bitmap, preventing
     * a draw from racing the state publication while keeping temporary retirement bounded.
     */
    private fun recycleAfterPublication(bitmaps: Collection<Bitmap>) {
        val pending = bitmaps.filterNot { it.isRecycled || !retiredBitmaps.add(it) }
        if (pending.isEmpty()) return
        viewModelScope.launch {
            delay(BITMAP_RETIRE_DELAY_MILLIS)
            pending.forEach { bitmap ->
                retiredBitmaps.remove(bitmap)
                if (!previewCache.contains(bitmap) && !originalCache.contains(bitmap)) {
                    bitmap.recycleSafely()
                }
            }
        }
    }

    override fun onCleared() {
        previewJobs.values.forEach { it.cancel() }
        originalJobs.values.forEach { it.cancel() }
        val bitmaps = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>()).apply {
            addAll(previewCache.clear())
            addAll(originalCache.clear())
            addAll(retiredBitmaps)
        }
        retiredBitmaps.clear()
        mutableState.value = PhotoUiState()
        bitmaps.forEach { it.recycleSafely() }
        super.onCleared()
    }

    private companion object {
        const val MAX_PREVIEW_BYTES = 3 * 1024 * 1024
        const val MAX_ORIGINAL_BYTES = 15 * 1024 * 1024
        const val MAX_PREVIEW_PIXELS = 256_000L
        const val MAX_ORIGINAL_PIXELS = 4_000_000L
        const val MAX_PREVIEW_CACHE_ENTRIES = 16
        const val MAX_ORIGINAL_CACHE_ENTRIES = 3
        const val MAX_PARALLEL_PHOTO_DOWNLOADS = 3
        const val BITMAP_RETIRE_DELAY_MILLIS = 64L
    }
}

/** Access-ordered bitmap cache that returns every displaced value to its lifecycle owner. */
private class BitmapLruCache(
    private val maxEntries: Int,
) {
    private val entries = LinkedHashMap<String, Bitmap>(maxEntries, 0.75f, true)

    init {
        require(maxEntries > 0) { "Bitmap cache size must be positive" }
    }

    fun get(key: String): Bitmap? = entries[key]

    fun put(key: String, bitmap: Bitmap): List<Bitmap> = buildList {
        entries.put(key, bitmap)?.takeUnless { it === bitmap }?.let(::add)
        while (entries.size > maxEntries) {
            val eldestKey = entries.entries.first().key
            entries.remove(eldestKey)?.let(::add)
        }
    }

    fun snapshot(): Map<String, Bitmap> = entries.toMap()

    fun contains(bitmap: Bitmap): Boolean = entries.values.any { it === bitmap }

    fun clear(): List<Bitmap> = entries.values.toList().also { entries.clear() }
}

private fun Bitmap.recycleSafely() {
    if (!isRecycled) recycle()
}
