package dev.buhanzaz.rwms.driver.feature.taskdetail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.driver.core.network.DriverGatewayClient
import dev.buhanzaz.rwms.driver.core.network.toDriverUserMessage
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Defines driver feature UI state; server data and authorization remain authoritative.
 */
sealed interface TaskMediaThumbnail {
    /** The encrypted or remote thumbnail is being resolved. */
    data object Loading : TaskMediaThumbnail
    /** A decoded bitmap is safe to render. */
    data class Ready(val bitmap: Bitmap) : TaskMediaThumbnail
    /** Thumbnail loading failed without affecting task state. */
    data class Failed(val message: String) : TaskMediaThumbnail
}

/**
 * Downloads server-provided media paths through the authenticated gateway.
 * Thumbnails never use MinIO/storage URLs directly and are bounded before
 * decoding so an unexpected original cannot exhaust the driver app.
 */
@HiltViewModel
class TaskMediaThumbnailViewModel @Inject constructor(
    private val gateway: DriverGatewayClient,
) : ViewModel() {
    private val mutableThumbnails = MutableStateFlow<Map<String, TaskMediaThumbnail>>(emptyMap())
    val thumbnails: StateFlow<Map<String, TaskMediaThumbnail>> = mutableThumbnails.asStateFlow()

    fun load(path: String) {
        if (path.isBlank() || mutableThumbnails.value.containsKey(path)) return
        mutableThumbnails.value = mutableThumbnails.value + (path to TaskMediaThumbnail.Loading)
        viewModelScope.launch {
            val next: TaskMediaThumbnail = try {
                withContext(Dispatchers.IO) {
                    gateway.mediaContent(path).use { body ->
                        val declaredSize = body.contentLength()
                        require(declaredSize < 0 || declaredSize <= MAX_MEDIA_BYTES) {
                            "Файл слишком большой для миниатюры"
                        }
                        val bytes = body.byteStream().use { input -> input.readBounded(MAX_MEDIA_BYTES) }
                        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
                            "RWMS вернул не изображение"
                        }
                    }
                }
                    .let(TaskMediaThumbnail::Ready)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                TaskMediaThumbnail.Failed(
                    error.toDriverUserMessage(
                        "Не удалось загрузить фотографию. Повторите после синхронизации.",
                    ),
                )
            }
            mutableThumbnails.value = mutableThumbnails.value + (path to next)
        }
    }

    private companion object {
        const val MAX_MEDIA_BYTES = 15 * 1024 * 1024
    }
}

private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        total += count
        require(total <= maxBytes) { "Файл слишком большой для миниатюры" }
        output.write(buffer, 0, count)
    }
}
