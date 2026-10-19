package dev.buhanzaz.rwms.worker.core.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies one image selected through Android's photo picker into private cache and normalizes it
 * into an upright, bounded transient JPEG. The bundle preparer then converts its pixels to WebP;
 * the returned source never becomes durable evidence and is deleted after encrypted persistence.
 */
@Singleton
class WorkerGalleryJpegImporter @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val applicationContext = context.applicationContext

    /**
     * Imports [uri] on an I/O dispatcher. Only picker-style `content` URIs are accepted; all
     * intermediate files are removed on success and every temporary file is removed on failure.
     */
    suspend fun `import`(uri: Uri): File = withContext(Dispatchers.IO) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) {
            "Gallery image must use a content URI"
        }

        val importDirectory = File(applicationContext.cacheDir, WORKER_GALLERY_IMPORT_DIRECTORY).also {
            check(it.mkdirs() || it.isDirectory) { "Could not create gallery import cache" }
        }
        val source = File.createTempFile("source-", ".image", importDirectory)
        var output: File? = null
        try {
            val resolver = applicationContext.contentResolver
            val copiedBytes = resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(source).buffered().use { destination ->
                    copyWorkerGalleryStream(input, destination, MAX_SOURCE_BYTES)
                }
            } ?: error("Selected gallery image is unavailable")
            require(copiedBytes > 0L) { "Selected gallery image is empty" }

            val orientation = workerGalleryExifOrientation(source)
            val decodeOptions = workerGalleryDecodeOptions(source)
            val decoded = BitmapFactory.decodeFile(source.path, decodeOptions)
                ?: error("Selected gallery item is not a supported image")
            try {
                val bounded = workerGalleryBoundBitmap(decoded)
                try {
                    val upright = workerGalleryUprightBitmap(bounded, orientation)
                    try {
                        val normalized = File.createTempFile("gallery-", ".jpg", importDirectory)
                        output = normalized
                        workerWriteBoundedGalleryJpeg(upright, normalized)
                        require(normalized.length() in 1..EncryptedEvidenceFileStore.MAX_SOURCE_IMAGE_BYTES) {
                            "Normalized gallery JPEG exceeds 15 MiB"
                        }
                        require(workerGalleryExifOrientation(normalized) == ExifInterface.ORIENTATION_NORMAL) {
                            "Normalized gallery JPEG orientation was not persisted"
                        }
                        normalized
                    } finally {
                        if (upright !== bounded) upright.recycle()
                    }
                } finally {
                    if (bounded !== decoded) bounded.recycle()
                }
            } finally {
                decoded.recycle()
            }
        } catch (error: Throwable) {
            output?.delete()
            throw error
        } finally {
            source.delete()
        }
    }
}

internal fun copyWorkerGalleryStream(
    input: InputStream,
    output: java.io.OutputStream,
    maxBytes: Long,
): Long {
    require(maxBytes > 0L) { "Gallery stream limit must be positive" }
    var copied = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        copied += read
        require(copied <= maxBytes) { "Selected gallery image exceeds 64 MiB" }
        output.write(buffer, 0, read)
    }
    return copied
}

internal fun workerGalleryDecodeSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0) { "Gallery image dimensions must be positive" }
    var sampleSize = 1
    while (workerGalleryPixelCount(width, height, sampleSize) > MAX_NORMALIZED_PIXELS) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun workerGalleryDecodeOptions(file: File): BitmapFactory.Options {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    check(bounds.outWidth > 0 && bounds.outHeight > 0) {
        "Selected gallery item is not a supported image"
    }
    return BitmapFactory.Options().apply {
        inSampleSize = workerGalleryDecodeSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
}

private fun workerGalleryBoundBitmap(bitmap: Bitmap): Bitmap {
    if (bitmap.width.toLong() * bitmap.height.toLong() <= MAX_NORMALIZED_PIXELS) return bitmap
    val dimensions = workerGalleryBoundDimensions(bitmap.width, bitmap.height)
    return Bitmap.createScaledBitmap(bitmap, dimensions.first, dimensions.second, true)
}

internal fun workerGalleryBoundDimensions(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "Gallery image dimensions must be positive" }
    val pixels = width.toLong() * height.toLong()
    if (pixels <= MAX_NORMALIZED_PIXELS) return width to height

    val scale = kotlin.math.sqrt(MAX_NORMALIZED_PIXELS.toDouble() / pixels.toDouble())
    var targetWidth = (width * scale).toInt().coerceAtLeast(1)
    var targetHeight = (height * scale).toInt().coerceAtLeast(1)
    while (targetWidth.toLong() * targetHeight.toLong() > MAX_NORMALIZED_PIXELS) {
        if (targetWidth >= targetHeight) targetWidth-- else targetHeight--
    }
    return targetWidth to targetHeight
}

private fun workerGalleryUprightBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
    if (orientation == ExifInterface.ORIENTATION_NORMAL) return bitmap
    return Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        workerGalleryOrientationMatrix(orientation),
        true,
    )
}

internal fun workerGalleryOrientationMatrix(orientation: Int): Matrix = Matrix().apply {
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
    }
}

private fun workerWriteBoundedGalleryJpeg(bitmap: Bitmap, output: File) {
    for (quality in JPEG_QUALITIES) {
        FileOutputStream(output, false).buffered().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                "Could not encode gallery image"
            }
        }
        workerWriteNormalExifOrientation(output)
        if (output.length() in 1..EncryptedEvidenceFileStore.MAX_SOURCE_IMAGE_BYTES) return
    }
    error("Normalized gallery JPEG exceeds 15 MiB")
}

private fun workerGalleryExifOrientation(file: File): Int = try {
    ExifInterface(file).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    ).takeIf { it in ExifInterface.ORIENTATION_NORMAL..ExifInterface.ORIENTATION_ROTATE_270 }
        ?: ExifInterface.ORIENTATION_NORMAL
} catch (_: Exception) {
    ExifInterface.ORIENTATION_NORMAL
}

private fun workerWriteNormalExifOrientation(file: File) {
    ExifInterface(file).run {
        setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        saveAttributes()
    }
}

private fun workerGalleryPixelCount(width: Int, height: Int, sampleSize: Int): Long {
    val sampledWidth = ceil(width.toDouble() / sampleSize.toDouble()).toLong()
    val sampledHeight = ceil(height.toDouble() / sampleSize.toDouble()).toLong()
    return sampledWidth * sampledHeight
}

internal const val WORKER_GALLERY_IMPORT_DIRECTORY = "worker-gallery-import"
private const val MAX_SOURCE_BYTES = 64L * 1024L * 1024L
private const val MAX_NORMALIZED_PIXELS = 8_000_000L
private val JPEG_QUALITIES = intArrayOf(92, 84, 76, 68, 60, 52, 44)
