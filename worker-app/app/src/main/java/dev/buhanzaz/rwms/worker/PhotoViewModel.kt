package dev.buhanzaz.rwms.worker

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import dev.buhanzaz.rwms.worker.core.ui.decodeWorkerBitmap
import dev.buhanzaz.rwms.worker.core.ui.readWorkerImageBytes
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Defines worker application UI or lifecycle state; it does not decide a server task transition.
 */
data class PhotoUiState(
    val bitmaps: Map<String, Bitmap> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    val loading: Set<String> = emptySet(),
)

@HiltViewModel
/**
 * Defines worker application UI or lifecycle state; it does not decide a server task transition.
 */
class PhotoViewModel @Inject constructor(
    private val gateway: WorkerGatewayClient,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PhotoUiState())
    val state: StateFlow<PhotoUiState> = mutableState.asStateFlow()
    private var requestedGeneration = 0L
    private var requestedPaths: Set<String> = emptySet()
    private val downloadPermits = Semaphore(MAX_PARALLEL_FULL_SCREEN_IMAGES)

    /**
     * Retains only the current pager window and loads its missing images. A cabin archive can
     * contain hundreds of photos, so decoding the complete route eagerly would exhaust the
     * Android heap even though the pager displays only one image at a time.
     */
    fun show(paths: List<String>) {
        val requested = paths.filter(String::isNotBlank).distinct()
        requestedGeneration += 1
        val generation = requestedGeneration
        requestedPaths = requested.toSet()
        val current = mutableState.value
        mutableState.value = current.copy(
            bitmaps = current.bitmaps.filterKeys(requestedPaths::contains),
            errors = current.errors.filterKeys(requestedPaths::contains),
            // A retained path may still belong to the previous generation's in-flight request.
            // Clear the marker so this generation owns a request that can publish its result.
            loading = emptySet(),
        )
        loadMissing(requested, generation)
    }

    private fun loadMissing(paths: List<String>, generation: Long) {
        val current = mutableState.value
        val missing = paths.filterNot {
            it in current.bitmaps || it in current.loading || it in current.errors
        }
        if (missing.isEmpty()) return
        mutableState.value = current.copy(loading = current.loading + missing)
        viewModelScope.launch {
            val loaded = supervisorScope {
                missing.map { path ->
                    async {
                        path to try {
                            val bitmap = downloadPermits.withPermit {
                                withContext(Dispatchers.IO) {
                                    gateway.mediaContent(path).use { body ->
                                        val declaredSize = body.contentLength()
                                        require(declaredSize < 0 || declaredSize <= MAX_IMAGE_BYTES) {
                                            "Файл слишком большой для просмотра"
                                        }
                                        val bytes = body.byteStream().use { input ->
                                            input.readWorkerImageBytes(MAX_IMAGE_BYTES)
                                        }
                                        decodeWorkerBitmap(bytes, MAX_FULL_SCREEN_PIXELS)
                                    }
                                }
                            }
                            Result.success(bitmap)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                    }
                }.map { request -> request.await() }
            }
            loaded.forEach { (path, result) ->
                result.fold(
                    onSuccess = { bitmap ->
                        if (generation != requestedGeneration || path !in requestedPaths) {
                            bitmap.recycle()
                            return@fold
                        }
                        val next = mutableState.value
                        mutableState.value = next.copy(
                            bitmaps = next.bitmaps + (path to bitmap),
                            errors = next.errors - path,
                            loading = next.loading - path,
                        )
                    },
                    onFailure = { error ->
                        if (generation != requestedGeneration || path !in requestedPaths) {
                            return@fold
                        }
                        val next = mutableState.value
                        mutableState.value = next.copy(
                            errors = next.errors +
                                (path to (error.message ?: "Не удалось открыть фото")),
                            loading = next.loading - path,
                        )
                    },
                )
            }
        }
    }

    /** Clears a path-local failure before making one explicit retry. */
    fun retry(path: String) {
        if (path !in requestedPaths) return
        mutableState.value = mutableState.value.copy(errors = mutableState.value.errors - path)
        loadMissing(listOf(path), requestedGeneration)
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 15 * 1024 * 1024
        const val MAX_FULL_SCREEN_PIXELS = 4_000_000L
        const val MAX_PARALLEL_FULL_SCREEN_IMAGES = 2
    }
}
