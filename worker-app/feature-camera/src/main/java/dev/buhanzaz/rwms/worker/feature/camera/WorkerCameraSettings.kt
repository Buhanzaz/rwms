package dev.buhanzaz.rwms.worker.feature.camera

import android.content.Context
import androidx.core.content.edit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class WorkerCameraMode(val label: String) {
    Night("НОЧЬ"),
    Photo("ФОТО"),
    Video("ВИДЕО"),
}

internal enum class WorkerCameraAspectRatio(val label: String) {
    FourThree("4:3"),
    SixteenNine("16:9");

    fun next(): WorkerCameraAspectRatio = when (this) {
        FourThree -> SixteenNine
        SixteenNine -> FourThree
    }
}

internal data class WorkerCameraSettings(
    val gridEnabled: Boolean = false,
    val aspectRatio: WorkerCameraAspectRatio = WorkerCameraAspectRatio.FourThree,
    val ultraHdrEnabled: Boolean = true,
    val motionCaptureEnabled: Boolean = false,
    val exposureEvTenths: Int = 0,
)

internal class WorkerCameraPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "worker-camera-v3-manager-0.3.29",
        Context.MODE_PRIVATE,
    )

    fun load(): WorkerCameraSettings = normalizeWorkerCameraSettings(
        WorkerCameraSettings(
            gridEnabled = preferences.getBoolean("grid", false),
            aspectRatio = preferences.getString("aspect-ratio", null)
                ?.let { value -> WorkerCameraAspectRatio.entries.firstOrNull { it.name == value } }
                ?: WorkerCameraAspectRatio.FourThree,
            ultraHdrEnabled = preferences.getBoolean("ultra-hdr", true),
            motionCaptureEnabled = preferences.getBoolean("motion", false),
            exposureEvTenths = preferences.getInt("exposure-ev-tenths", 0),
        ),
    )

    fun save(settings: WorkerCameraSettings) {
        val normalized = normalizeWorkerCameraSettings(settings)
        preferences.edit {
            putBoolean("grid", normalized.gridEnabled)
            putString("aspect-ratio", normalized.aspectRatio.name)
            putBoolean("ultra-hdr", normalized.ultraHdrEnabled)
            putBoolean("motion", normalized.motionCaptureEnabled)
            putInt("exposure-ev-tenths", normalized.exposureEvTenths)
        }
    }
}

internal fun normalizeWorkerCameraSettings(settings: WorkerCameraSettings): WorkerCameraSettings =
    settings.copy(exposureEvTenths = settings.exposureEvTenths.coerceIn(-20, 20))

internal fun workerSupportedZoomStops(minZoom: Float, maxZoom: Float): List<Float> {
    val minimum = minZoom.takeIf { it.isFinite() && it > 0f } ?: 1f
    val maximum = maxZoom.takeIf { it.isFinite() && it >= minimum } ?: minimum
    if (abs(maximum - minimum) < 0.01f) return listOf(minimum)
    return buildList {
        if (minimum < 0.99f) add(minimum)
        listOf(1f, 2f, 3f).filterTo(this) { it in minimum..maximum }
        if (isEmpty()) add(minimum)
        if (size == 1 && maximum - first() >= 0.1f) add(maximum)
    }.distinctBy { (it * 1_000).roundToInt() }.sorted()
}

internal fun workerCoerceZoom(requested: Float, minZoom: Float, maxZoom: Float): Float {
    val minimum = minZoom.takeIf { it.isFinite() && it > 0f } ?: 1f
    val maximum = maxZoom.takeIf { it.isFinite() && it >= minimum } ?: minimum
    return requested.takeIf(Float::isFinite)?.coerceIn(minimum, maximum) ?: minimum
}

internal fun workerZoomLabel(value: Float): String =
    if (abs(value - value.roundToInt()) < 0.01f) "${value.roundToInt()}×"
    else String.format(Locale.US, "%.1f×", value)

internal data class WorkerCameraModeSelection(
    val mode: WorkerCameraMode,
    val message: String?,
)

/** Worker evidence is JPEG-only; the visible 0.3.29 Video tab cannot create an MP4 payload. */
internal fun selectWorkerCameraMode(requested: WorkerCameraMode): WorkerCameraModeSelection =
    if (requested == WorkerCameraMode.Video) {
        WorkerCameraModeSelection(
            mode = WorkerCameraMode.Photo,
            message = "Видео для фотоотчёта пока не поддерживается",
        )
    } else {
        WorkerCameraModeSelection(requested, null)
    }

fun interface VolumeShutterHost {
    fun setVolumeShutterHandler(handler: (() -> Unit)?)
}
