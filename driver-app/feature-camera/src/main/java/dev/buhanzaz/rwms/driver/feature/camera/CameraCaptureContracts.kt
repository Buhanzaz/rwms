package dev.buhanzaz.rwms.driver.feature.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import dev.buhanzaz.rwms.driver.core.media.EncryptedEvidenceFileStore
import java.io.File

/**
 * The three permission states the capture screen can render. Keeping this
 * decision outside Compose makes first denial and the permanent-denial path
 * deterministic on API 23+.
 */
internal enum class CameraPermissionGate {
    GRANTED,
    REQUESTABLE,
    PERMANENTLY_DENIED,
}

internal fun cameraPermissionGate(
    granted: Boolean,
    requestedAtLeastOnce: Boolean,
    shouldShowRationale: Boolean,
): CameraPermissionGate = when {
    granted -> CameraPermissionGate.GRANTED
    requestedAtLeastOnce && !shouldShowRationale -> CameraPermissionGate.PERMANENTLY_DENIED
    else -> CameraPermissionGate.REQUESTABLE
}

/**
 * CameraX reports a successful callback even when an OEM camera writes an
 * unusable zero-byte output. Validate the exact private-cache target before
 * offering confirmation so a user can retake instead of reaching a dead end.
 */
internal sealed interface CameraXSaveResult {
    /** CameraX produced a non-empty JPEG within the evidence size limit. */
    data class Saved(val file: File) : CameraXSaveResult

    /** Capture output is absent, empty or too large to reserve safely. */
    data class Failed(val message: String) : CameraXSaveResult
}

internal fun validateCameraXSave(target: File): CameraXSaveResult = when {
    !target.isFile -> CameraXSaveResult.Failed("Камера не сохранила фотографию")
    target.length() <= 0L -> CameraXSaveResult.Failed("Камера сохранила пустую фотографию")
    target.length() > EncryptedEvidenceFileStore.MAX_JPEG_BYTES ->
        CameraXSaveResult.Failed("Фотография больше 15 МБ. Снимите её ещё раз")
    else -> CameraXSaveResult.Saved(target)
}

/** Returns a valid CameraX target rotation while the preview is attaching to its display. */
internal fun driverCaptureTargetRotation(displayRotation: Int?): Int = when (displayRotation) {
    Surface.ROTATION_0,
    Surface.ROTATION_90,
    Surface.ROTATION_180,
    Surface.ROTATION_270,
    -> displayRotation

    else -> Surface.ROTATION_0
}

/**
 * Maps the physical device position to CameraX rotation. The driver app keeps its camera UI
 * portrait, so [Surface] display rotation alone is not enough to describe a landscape capture.
 */
internal fun driverCaptureTargetRotationForOrientation(
    orientationDegrees: Int,
    fallbackRotation: Int,
): Int = when (orientationDegrees) {
    in 45 until 135 -> Surface.ROTATION_270
    in 135 until 225 -> Surface.ROTATION_180
    in 225 until 315 -> Surface.ROTATION_90
    in 0 until 360 -> Surface.ROTATION_0
    else -> driverCaptureTargetRotation(fallbackRotation)
}

/** EXIF orientation used only to repair a malformed camera HAL result. */
internal fun driverExifOrientationForRotationDegrees(rotationDegrees: Int): Int =
    when (((rotationDegrees % 360) + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

internal fun driverExifOrientationNeedsRepair(orientation: Int): Boolean = orientation !in 1..8

internal fun driverResolvedCameraExifOrientation(
    recordedOrientation: Int,
    fallbackOrientation: Int,
): Int = when {
    !driverExifOrientationNeedsRepair(recordedOrientation) -> recordedOrientation
    !driverExifOrientationNeedsRepair(fallbackOrientation) -> fallbackOrientation
    else -> ExifInterface.ORIENTATION_NORMAL
}

/**
 * Rewrites a CameraX capture into evidence bytes that every consumer can display without an EXIF
 * or server-side rotation step. A physically upright portrait remains untouched except for an
 * explicit `Orientation=1`; a transformed JPEG is written beside the original so a failed
 * rewrite never destroys the camera result.
 */
internal fun normalizeDriverCameraJpegOrientation(
    source: File,
    fallbackOrientation: Int,
): File? = try {
    val sourceExif = ExifInterface(source)
    val recordedOrientation = sourceExif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_UNDEFINED,
    )
    val orientation = driverResolvedCameraExifOrientation(
        recordedOrientation = recordedOrientation,
        fallbackOrientation = fallbackOrientation,
    )
    if (orientation == ExifInterface.ORIENTATION_NORMAL) {
        // Some vendor HALs use 0 or omit the tag for an already upright capture. Persist the
        // explicit value before this file is encrypted and leaves the phone.
        if (recordedOrientation != ExifInterface.ORIENTATION_NORMAL) {
            driverWriteCameraExifOrientation(source, ExifInterface.ORIENTATION_NORMAL)
        }
        source
    } else {
        val parent = requireNotNull(source.parentFile) { "Capture file has no parent directory" }
        // BitmapFactory behavior around EXIF varies by API/vendor. Decode a disposable copy that
        // declares Orientation=1, then apply the original transform exactly once ourselves.
        val decodeInput = File.createTempFile("decode-unoriented-", ".image", parent)
        try {
            source.inputStream().use { input ->
                decodeInput.outputStream().use { output -> input.copyTo(output) }
            }
            driverWriteCameraExifOrientation(decodeInput, ExifInterface.ORIENTATION_NORMAL)
            val decodePlan = driverCameraOrientationDecodePlan(decodeInput)
            val sourceBitmap = BitmapFactory.decodeFile(decodeInput.path, decodePlan)
                ?: error("Unable to decode JPEG")
            try {
                val orientedBitmap = Bitmap.createBitmap(
                    sourceBitmap,
                    0,
                    0,
                    sourceBitmap.width,
                    sourceBitmap.height,
                    driverExifOrientationMatrix(orientation),
                    true,
                )
                try {
                    val output = File.createTempFile("upright-", ".jpg", parent)
                    var outputReady = false
                    try {
                        output.outputStream().buffered().use { stream ->
                            check(
                                orientedBitmap.compress(
                                    Bitmap.CompressFormat.JPEG,
                                    DRIVER_NORMALIZED_JPEG_QUALITY,
                                    stream,
                                ),
                            ) { "Unable to encode normalized JPEG" }
                        }
                        driverWriteCameraExifOrientation(output, ExifInterface.ORIENTATION_NORMAL)
                        outputReady = true
                        output
                    } finally {
                        if (!outputReady) output.delete()
                    }
                } finally {
                    if (orientedBitmap !== sourceBitmap) orientedBitmap.recycle()
                }
            } finally {
                sourceBitmap.recycle()
            }
        } finally {
            decodeInput.delete()
        }
    }
} catch (_: Exception) {
    null
}

internal fun driverExifOrientationMatrix(orientation: Int): Matrix = Matrix().apply {
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            setRotate(180f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            setRotate(90f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            setRotate(-90f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        else -> Unit
    }
}

private fun driverCameraOrientationDecodePlan(file: File): BitmapFactory.Options {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unable to read JPEG dimensions" }
    return BitmapFactory.Options().apply {
        inSampleSize = driverOrientationNormalizationSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
}

/**
 * Orientation processing holds both the decoded source and its rotated destination in memory.
 * Cap each side at about 8 MP (roughly 32 MiB ARGB_8888) so a 48–50 MP camera never exhausts
 * the driver process while producing evidence bounded to 15 MiB.
 */
internal fun driverOrientationNormalizationSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0) { "JPEG dimensions must be positive" }
    var sampleSize = 1
    while (driverSampledPixelCount(width, height, sampleSize) >
        DRIVER_MAX_ORIENTATION_NORMALIZATION_PIXELS
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun driverSampledPixelCount(width: Int, height: Int, sampleSize: Int): Long {
    val sampledWidth = (width.toLong() + sampleSize - 1L) / sampleSize
    val sampledHeight = (height.toLong() + sampleSize - 1L) / sampleSize
    return sampledWidth * sampledHeight
}

private fun driverWriteCameraExifOrientation(file: File, orientation: Int) {
    ExifInterface(file).run {
        setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        saveAttributes()
    }
    val persisted = ExifInterface(file).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_UNDEFINED,
    )
    check(persisted == orientation) { "EXIF orientation was not persisted" }
}

private const val DRIVER_NORMALIZED_JPEG_QUALITY = 92
private const val DRIVER_MAX_ORIENTATION_NORMALIZATION_PIXELS = 8_000_000L
