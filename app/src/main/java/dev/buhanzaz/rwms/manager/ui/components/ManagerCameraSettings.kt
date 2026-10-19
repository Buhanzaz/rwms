package dev.buhanzaz.rwms.manager.ui.components

import android.content.Context
import android.util.Size
import androidx.core.content.edit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Defines manager UI or local cache state; it does not own a server-side business transition.
 */
internal enum class ManagerCameraMode(val label: String) {
    Night("НОЧЬ"),
    Photo("ФОТО"),
    Video("ВИДЕО"),
}

/**
 * Defines manager UI or local cache state; it does not own a server-side business transition.
 */
internal enum class ManagerCameraAspectRatio(val label: String) {
    FourThree("4:3"),
    SixteenNine("16:9"),
    ;

    fun next(): ManagerCameraAspectRatio = when (this) {
        FourThree -> SixteenNine
        SixteenNine -> FourThree
    }
}

/**
 * Defines manager UI or local cache state; it does not own a server-side business transition.
 */
internal enum class ManagerVideoQuality(val label: String) {
    Uhd("4K"),
    Fhd("1080P"),
    Hd("720P"),
}

/**
 * Defines manager UI or local cache state; it does not own a server-side business transition.
 */
internal data class ManagerCameraSettings(
    val gridEnabled: Boolean = false,
    val aspectRatio: ManagerCameraAspectRatio = ManagerCameraAspectRatio.FourThree,
    val requestedMegapixels: Int? = null,
    val ultraHdrEnabled: Boolean = false,
    val videoHdrEnabled: Boolean = false,
    val motionCaptureEnabled: Boolean = false,
    val exposureEvTenths: Int = 0,
    val videoQuality: ManagerVideoQuality = ManagerVideoQuality.Fhd,
    val videoFramesPerSecond: Int = 30,
)

/**
 * Stores non-authoritative camera preferences and applies the one-time fast-photo default. Users
 * can explicitly enable HDR again when its slower multi-frame processing is appropriate.
 */
internal class ManagerCameraPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): ManagerCameraSettings {
        val defaultsVersion = preferences.getInt(KEY_CAPTURE_DEFAULTS_VERSION, 0)
        val migrateToFastPhoto = defaultsVersion < FAST_PHOTO_DEFAULTS_VERSION
        if (migrateToFastPhoto) {
            preferences.edit {
                putBoolean(KEY_ULTRA_HDR, false)
                putInt(KEY_CAPTURE_DEFAULTS_VERSION, FAST_PHOTO_DEFAULTS_VERSION)
            }
        }
        return normalizeManagerCameraSettings(
            ManagerCameraSettings(
                gridEnabled = preferences.getBoolean(KEY_GRID, false),
                aspectRatio = preferences.enumValue(
                    KEY_ASPECT_RATIO,
                    ManagerCameraAspectRatio.FourThree,
                ),
                requestedMegapixels = preferences.getInt(KEY_MEGAPIXELS, 0)
                    .takeIf { it > 0 },
                ultraHdrEnabled = preferences.getBoolean(KEY_ULTRA_HDR, false),
                videoHdrEnabled = preferences.getBoolean(KEY_VIDEO_HDR, false),
                motionCaptureEnabled = preferences.getBoolean(KEY_MOTION, false),
                exposureEvTenths = preferences.getInt(KEY_EXPOSURE, 0),
                videoQuality = preferences.enumValue(
                    KEY_VIDEO_QUALITY,
                    ManagerVideoQuality.Fhd,
                ),
                videoFramesPerSecond = preferences.getInt(KEY_VIDEO_FPS, 30),
            ),
        )
    }

    fun save(settings: ManagerCameraSettings) {
        val normalized = normalizeManagerCameraSettings(settings)
        preferences.edit {
            putBoolean(KEY_GRID, normalized.gridEnabled)
            putString(KEY_ASPECT_RATIO, normalized.aspectRatio.name)
            putInt(KEY_MEGAPIXELS, normalized.requestedMegapixels ?: 0)
            putBoolean(KEY_ULTRA_HDR, normalized.ultraHdrEnabled)
            putBoolean(KEY_VIDEO_HDR, normalized.videoHdrEnabled)
            putBoolean(KEY_MOTION, normalized.motionCaptureEnabled)
            putInt(KEY_EXPOSURE, normalized.exposureEvTenths)
            putString(KEY_VIDEO_QUALITY, normalized.videoQuality.name)
            putInt(KEY_VIDEO_FPS, normalized.videoFramesPerSecond)
            putInt(KEY_CAPTURE_DEFAULTS_VERSION, FAST_PHOTO_DEFAULTS_VERSION)
        }
    }

    private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enumValue(
        key: String,
        fallback: T,
    ): T = getString(key, null)
        ?.let { value -> enumValues<T>().firstOrNull { it.name == value } }
        ?: fallback

    private companion object {
        const val PREFERENCES_NAME = "manager-camera-v2"
        const val KEY_GRID = "grid"
        const val KEY_ASPECT_RATIO = "aspect-ratio"
        const val KEY_MEGAPIXELS = "megapixels"
        const val KEY_ULTRA_HDR = "ultra-hdr"
        const val KEY_VIDEO_HDR = "video-hdr"
        const val KEY_MOTION = "motion"
        const val KEY_EXPOSURE = "exposure-ev-tenths"
        const val KEY_VIDEO_QUALITY = "video-quality"
        const val KEY_VIDEO_FPS = "video-fps"
        const val KEY_CAPTURE_DEFAULTS_VERSION = "capture-defaults-version"
        const val FAST_PHOTO_DEFAULTS_VERSION = 1
    }
}

internal fun normalizeManagerCameraSettings(
    settings: ManagerCameraSettings,
): ManagerCameraSettings = settings.copy(
    requestedMegapixels = settings.requestedMegapixels?.takeIf { it in 1..200 },
    exposureEvTenths = settings.exposureEvTenths.coerceIn(-20, 20),
    videoFramesPerSecond = settings.videoFramesPerSecond.takeIf { it == 60 } ?: 30,
)

/** Uses a capture-efficient sensor size unless the manager explicitly selects another size. */
internal fun managerEffectivePhotoMegapixels(requestedMegapixels: Int?): Int =
    requestedMegapixels ?: 12

internal fun <T> managerPhysicalLensOutputs(
    physicalOutputs: List<T>,
    logicalCameraOutputs: List<T>,
): List<T> = physicalOutputs.ifEmpty { logicalCameraOutputs }

/** Discrete lens positions mirror the native camera UI; pinch zoom remains continuous. */
internal fun managerSupportedZoomStops(minZoom: Float, maxZoom: Float): List<Float> {
    val minimum = normalizedManagerZoomMinimum(minZoom)
    val maximum = normalizedManagerZoomMaximum(minimum, maxZoom)
    if (abs(maximum - minimum) < 0.01f) return listOf(minimum)

    val stops = buildList {
        if (minimum < 0.99f) add(minimum)
        listOf(1f, 2f, 3f).forEach { stop ->
            if (stop in minimum..maximum) add(stop)
        }
        if (isEmpty()) add(minimum)
        if (size == 1 && maximum - first() >= 0.1f) add(maximum)
    }
    return stops
        .distinctBy { (it * 1_000f).roundToInt() }
        .sorted()
}

internal fun managerCoerceZoom(requestedZoom: Float, minZoom: Float, maxZoom: Float): Float {
    val minimum = normalizedManagerZoomMinimum(minZoom)
    val maximum = normalizedManagerZoomMaximum(minimum, maxZoom)
    return requestedZoom.takeIf(Float::isFinite)?.coerceIn(minimum, maximum) ?: minimum
}

internal fun managerZoomLabel(value: Float): String =
    if (abs(value - value.roundToInt()) < 0.01f) {
        "${value.roundToInt()}×"
    } else {
        String.format(Locale.US, "%.1f×", value)
    }

internal fun managerNormalizedLensZoomRatio(value: Float): Float {
    val finiteValue = value.takeIf { it.isFinite() && it > 0f } ?: 1f
    return ((finiteValue.coerceIn(0.1f, 10f) * 10f).roundToInt() / 10f)
        .coerceAtLeast(0.1f)
}

/**
 * Defines manager UI or local cache state; it does not own a server-side business transition.
 */
internal data class ManagerCameraLensProfile(
    val key: String,
    val displayZoom: Float,
    val pixelCounts: List<Long>,
    val isDefault: Boolean = false,
)

internal fun managerPreferredCameraLensKey(
    lenses: List<ManagerCameraLensProfile>,
    currentKey: String?,
    requestedMegapixels: Int?,
): String? {
    if (lenses.isEmpty()) return null
    val currentLens = lenses.firstOrNull { lens -> lens.key == currentKey }
    if (requestedMegapixels == null) {
        return currentLens?.key ?: lenses.firstOrNull(ManagerCameraLensProfile::isDefault)?.key
            ?: lenses.first().key
    }

    fun ManagerCameraLensProfile.supportsRequestedResolution(): Boolean = pixelCounts.any { pixels ->
        (pixels / 1_000_000.0).roundToInt() == requestedMegapixels
    }

    if (currentLens?.supportsRequestedResolution() == true) return currentLens.key
    return lenses
        .asSequence()
        .filter(ManagerCameraLensProfile::supportsRequestedResolution)
        .sortedWith(
            compareBy<ManagerCameraLensProfile> { abs(it.displayZoom - 1f) }
                .thenByDescending(ManagerCameraLensProfile::isDefault)
                .thenByDescending { it.pixelCounts.maxOrNull() ?: 0L },
        )
        .firstOrNull()
        ?.key
        ?: currentLens?.key
        ?: lenses.firstOrNull(ManagerCameraLensProfile::isDefault)?.key
        ?: lenses.first().key
}

internal fun managerPhotoMegapixelOptions(pixelCounts: List<Long>): List<Int> {
    val supported = pixelCounts
        .filter { it > 0L }
        .map { (it / 1_000_000.0).roundToInt().coerceAtLeast(1) }
        .distinct()
        .sortedDescending()
    if (supported.isEmpty()) return emptyList()
    val maximum = supported.first()
    return buildList {
        add(maximum)
        listOf(12, 8, 4).forEach { target ->
            supported.minByOrNull { abs(it - target) }
                ?.takeIf { abs(it - target) <= maxOf(1, (target * 0.25f).roundToInt()) }
                ?.let(::add)
        }
    }.distinct()
}

internal fun managerPreferredPixelCount(
    pixelCounts: List<Long>,
    requestedMegapixels: Int?,
): Long? {
    if (pixelCounts.isEmpty()) return null
    if (requestedMegapixels == null) return pixelCounts.maxOrNull()
    val requestedPixels = requestedMegapixels * 1_000_000L
    return pixelCounts.minByOrNull { abs(it - requestedPixels) }
}

internal fun managerPreferredPhotoSize(
    sizes: List<Size>,
    requestedMegapixels: Int?,
): Size? {
    if (sizes.isEmpty()) return null
    if (requestedMegapixels == null) {
        return sizes.maxByOrNull { size -> size.width.toLong() * size.height }
    }
    val requestedPixels = requestedMegapixels * 1_000_000L
    return sizes.minWithOrNull(
        compareBy<Size> { size ->
            abs(size.width.toLong() * size.height - requestedPixels)
        }.thenByDescending { size -> size.width.toLong() * size.height },
    )
}

internal fun managerEffectiveVideoQuality(
    requested: ManagerVideoQuality,
    supported: Set<ManagerVideoQuality>,
): ManagerVideoQuality? {
    if (requested in supported) return requested
    return listOf(ManagerVideoQuality.Fhd, ManagerVideoQuality.Hd, ManagerVideoQuality.Uhd)
        .firstOrNull { it in supported }
}

internal fun managerVideoSettingLabel(
    quality: ManagerVideoQuality,
    framesPerSecond: Int,
    hdrActive: Boolean = false,
): String = buildString {
    append(quality.label)
    append("  ·  ")
    append(framesPerSecond)
    if (hdrActive) append("  ·  HDR")
}

internal fun managerRecordingTimerLabel(durationMillis: Long): String {
    val totalSeconds = (durationMillis.coerceAtLeast(0L) / 1_000L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3_600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun normalizedManagerZoomMinimum(value: Float): Float =
    value.takeIf { it.isFinite() && it > 0f } ?: 1f

private fun normalizedManagerZoomMaximum(minimum: Float, value: Float): Float =
    value.takeIf { it.isFinite() && it >= minimum } ?: minimum
