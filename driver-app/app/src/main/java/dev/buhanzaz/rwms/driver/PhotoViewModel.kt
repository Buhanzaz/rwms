package dev.buhanzaz.rwms.driver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.driver.core.network.DriverGatewayClient
import dev.buhanzaz.rwms.driver.core.network.toDriverUserMessage
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Defines driver application UI or lifecycle state; it does not decide a server task transition.
 */
data class PhotoUiState(
    val bitmaps: Map<String, Bitmap> = emptyMap(),
    val error: String? = null,
)

@HiltViewModel
/**
 * Defines driver application UI or lifecycle state; it does not decide a server task transition.
 */
class PhotoViewModel @Inject constructor(
    private val gateway: DriverGatewayClient,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PhotoUiState())
    val state: StateFlow<PhotoUiState> = mutableState.asStateFlow()

    fun load(paths: List<String>) {
        val missing = paths.filterNot { mutableState.value.bitmaps.containsKey(it) }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            val fetched = mutableState.value.bitmaps.toMutableMap()
            missing.forEach { path ->
                runCatching {
                    gateway.mediaContent(path).use { body ->
                        val declaredSize = body.contentLength()
                        require(declaredSize < 0 || declaredSize <= MAX_IMAGE_BYTES) {
                            "Файл слишком большой для просмотра"
                        }
                        val bytes = body.byteStream().use { input -> input.readBounded(MAX_IMAGE_BYTES) }
                        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "RWMS вернул не изображение" }
                    }
                }.onSuccess { bitmap -> fetched[path] = bitmap }
                    .onFailure { error ->
                        mutableState.value = mutableState.value.copy(
                            error = error.toDriverUserMessage(
                                "Не удалось открыть фотографию. Обновите данные и повторите.",
                            ),
                        )
                    }
            }
            mutableState.value = mutableState.value.copy(bitmaps = fetched)
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 15 * 1024 * 1024
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
        require(total <= maxBytes) { "Файл слишком большой для просмотра" }
        output.write(buffer, 0, count)
    }
}
