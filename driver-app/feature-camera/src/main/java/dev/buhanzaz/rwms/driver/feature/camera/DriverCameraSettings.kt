package dev.buhanzaz.rwms.driver.feature.camera

import android.content.Context
import androidx.core.content.edit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Defines driver UI/presentation state; it does not decide a server task transition.
 */
internal enum class DriverCameraMode(val label: String) {
    Night("НОЧЬ"),
    Photo("ФОТО"),
    Video("ВИДЕО"),
}

/**
 * Defines driver UI/presentation state; it does not decide a server task transition.
 */
internal enum class DriverCameraAspectRatio(val label: String) {
    FourThree("4:3"),
    SixteenNine("16:9");

    fun next(): DriverCameraAspectRatio = when (this) {
        FourThree -> SixteenNine
        SixteenNine -> FourThree
    }
}

/**
 * Defines driver UI/presentation state; it does not decide a server task transition.
 */
internal data class DriverCameraSettings(
    val gridEnabled: Boolean = false,
    val aspectRatio: DriverCameraAspectRatio = DriverCameraAspectRatio.FourThree,
    val motionCaptureEnabled: Boolean = false,
    val exposureEvTenths: Int = 0,
)

/**
 * Defines driver UI/presentation state; it does not decide a server task transition.
 */
internal class DriverCameraPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "driver-camera-v3-manager-0.3.29",
        Context.MODE_PRIVATE,
    )

    fun load(): DriverCameraSettings = normalizeDriverCameraSettings(
        DriverCameraSettings(
            gridEnabled = preferences.getBoolean("grid", false),
            aspectRatio = preferences.getString("aspect-ratio", null)
                ?.let { value -> DriverCameraAspectRatio.entries.firstOrNull { it.name == value } }
                ?: DriverCameraAspectRatio.FourThree,
            motionCaptureEnabled = preferences.getBoolean("motion", false),
            exposureEvTenths = preferences.getInt("exposure-ev-tenths", 0),
        ),
    )

    fun save(settings: DriverCameraSettings) {
        val normalized = normalizeDriverCameraSettings(settings)
        preferences.edit {
            putBoolean("grid", normalized.gridEnabled)
            putString("aspect-ratio", normalized.aspectRatio.name)
            putBoolean("motion", normalized.motionCaptureEnabled)
            putInt("exposure-ev-tenths", normalized.exposureEvTenths)
        }
    }
}

internal fun normalizeDriverCameraSettings(settings: DriverCameraSettings): DriverCameraSettings =
    settings.copy(exposureEvTenths = settings.exposureEvTenths.coerceIn(-20, 20))

private const val DRIVER_WIDE_ZOOM_TARGET = 0.6f

/**
 * Expose a common 0.6× floor only when the currently bound camera really supports it. Some
 * front/physical cameras start at 1× (or another value); inventing a ratio there crashes or
 * silently ignores the request on several OEM devices.
 */
internal fun driverCaptureMinimumZoom(hardwareMinimum: Float?, hardwareMaximum: Float?): Float {
    val minimum = driverSupportedMinimumZoom(hardwareMinimum, hardwareMaximum)
    val maximum = driverCaptureMaximumZoom(hardwareMinimum, hardwareMaximum)
    return DRIVER_WIDE_ZOOM_TARGET.takeIf { it in minimum..maximum } ?: minimum
}

internal fun driverCaptureMaximumZoom(hardwareMinimum: Float?, hardwareMaximum: Float?): Float {
    val minimum = driverSupportedMinimumZoom(hardwareMinimum, hardwareMaximum)
    return hardwareMaximum
        ?.takeIf { zoom -> zoom.isFinite() && zoom >= minimum }
        ?: minimum
}

internal fun driverCoerceCaptureZoom(
    requested: Float,
    hardwareMinimum: Float?,
    hardwareMaximum: Float?,
): Float = driverCoerceZoom(
    requested = requested,
    minZoom = driverCaptureMinimumZoom(hardwareMinimum, hardwareMaximum),
    maxZoom = driverCaptureMaximumZoom(hardwareMinimum, hardwareMaximum),
)

private fun driverSupportedMinimumZoom(hardwareMinimum: Float?, hardwareMaximum: Float?): Float {
    val reportedMinimum = hardwareMinimum?.takeIf { zoom -> zoom.isFinite() && zoom > 0f }
    val reportedMaximum = hardwareMaximum?.takeIf { zoom -> zoom.isFinite() && zoom > 0f }
    return when {
        reportedMinimum != null && reportedMaximum != null && reportedMaximum >= reportedMinimum ->
            reportedMinimum
        reportedMinimum != null -> reportedMinimum
        // If the HAL gives us only one usable maximum, it is the only known-safe request.
        reportedMaximum != null -> reportedMaximum
        else -> 1f
    }
}

internal fun driverSupportedZoomStops(minZoom: Float, maxZoom: Float): List<Float> {
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

internal fun driverCoerceZoom(requested: Float, minZoom: Float, maxZoom: Float): Float {
    val minimum = minZoom.takeIf { it.isFinite() && it > 0f } ?: 1f
    val maximum = maxZoom.takeIf { it.isFinite() && it >= minimum } ?: minimum
    return requested.takeIf(Float::isFinite)?.coerceIn(minimum, maximum) ?: minimum
}

internal fun driverZoomLabel(value: Float): String =
    if (abs(value - value.roundToInt()) < 0.01f) "${value.roundToInt()}×"
    else String.format(Locale.US, "%.1f×", value)

/**
 * Defines driver UI/presentation state; it does not decide a server task transition.
 */
internal data class DriverCameraModeSelection(
    val mode: DriverCameraMode,
    val message: String?,
)

/** Driver evidence is JPEG-only; the visible 0.3.29 Video tab cannot create an MP4 payload. */
internal fun selectDriverCameraMode(requested: DriverCameraMode): DriverCameraModeSelection =
    if (requested == DriverCameraMode.Video) {
        DriverCameraModeSelection(
            mode = DriverCameraMode.Photo,
            message = "Видео для фотоотчёта пока не поддерживается",
        )
    } else {
        DriverCameraModeSelection(requested, null)
    }

/** Installs or removes the foreground camera action invoked by a hardware volume key. */
fun interface VolumeShutterHost {
    fun setVolumeShutterHandler(handler: (() -> Unit)?)
}
