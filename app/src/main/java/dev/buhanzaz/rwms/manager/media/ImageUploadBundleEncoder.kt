package dev.buhanzaz.rwms.manager.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/** Canonical order and local encoding budget for one client-prepared image variant. */
internal enum class ImageUploadVariantKind(
    val maximumEdge: Int,
    val maximumBytes: Long,
) {
    SMALL(maximumEdge = 480, maximumBytes = 96L * 1024L),
    MEDIUM(maximumEdge = 1_280, maximumBytes = 288L * 1024L),
    LARGE(maximumEdge = 2_048, maximumBytes = 640L * 1024L),
}

/** One orientation-correct WebP part stored beside its durable app-private original. */
internal data class ImageUploadVariant(
    val kind: ImageUploadVariantKind,
    val file: File,
    val contentLength: Long,
    val checksumSha256: String,
    val width: Int,
    val height: Int,
)

/** Three upload parts that represent one logical still image to the user and media service. */
internal data class ImageUploadBundle(
    override val fileName: String,
    val variants: List<ImageUploadVariant>,
) : MediaUploadPayload {
    init {
        require(variants.map(ImageUploadVariant::kind) == ImageUploadVariantKind.entries) {
            "Пакет изображения должен содержать SMALL, MEDIUM и LARGE"
        }
        require(variants.sumOf(ImageUploadVariant::contentLength) <= MAX_IMAGE_BUNDLE_BYTES) {
            "Общий размер вариантов изображения превышает 1 МиБ"
        }
    }
}

/**
 * Converts one durable original into a restart-safe SMALL/MEDIUM/LARGE WebP bundle.
 *
 * EXIF orientation is resolved before scaling. Decode size, each variant budget, aggregate size,
 * and quality/resolution fallback are bounded. Completed files use stable names and are reused
 * after process death, preserving their checksums for idempotent part retries.
 */
internal class ImageUploadBundleEncoder {
    fun encode(source: File): ImageUploadBundle {
        require(source.isFile && source.length() > 0L) { "Фотография пуста или недоступна" }
        reusableBundle(source)?.let { return it }

        val variantDirectory = imageVariantDirectoryFor(source)
        variantDirectory.deleteRecursively()
        check(variantDirectory.mkdirs()) { "Не удалось подготовить варианты фотографии" }
        val decodeInput = File(variantDirectory, "decode-source.image")
        return try {
            source.inputStream().buffered().use { input ->
                decodeInput.outputStream().buffered().use(input::copyTo)
            }
            val orientation = runCatching {
                ExifInterface(source).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            if (orientation in EXIF_TRANSFORM_ORIENTATIONS) {
                ExifInterface(decodeInput).run {
                    setAttribute(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL.toString(),
                    )
                    saveAttributes()
                }
            }
            val decoded = decodeBoundedBitmap(decodeInput)
            val oriented = try {
                if (orientation in EXIF_TRANSFORM_ORIENTATIONS) {
                    Bitmap.createBitmap(
                        decoded,
                        0,
                        0,
                        decoded.width,
                        decoded.height,
                        imageExifOrientationMatrix(orientation),
                        true,
                    )
                } else {
                    decoded
                }
            } catch (failure: Throwable) {
                decoded.recycle()
                throw failure
            }
            try {
                val variants = ImageUploadVariantKind.entries.map { kind ->
                    encodeVariant(oriented, variantDirectory, kind)
                }
                ImageUploadBundle(
                    fileName = "${source.nameWithoutExtension.ifBlank { "rwms-image" }}.webp",
                    variants = variants,
                )
            } finally {
                if (oriented !== decoded) oriented.recycle()
                decoded.recycle()
            }
        } catch (failure: Throwable) {
            variantDirectory.deleteRecursively()
            throw failure
        } finally {
            decodeInput.delete()
        }
    }

    private fun reusableBundle(source: File): ImageUploadBundle? {
        val directory = imageVariantDirectoryFor(source)
        if (!directory.isDirectory) return null
        val variants = ImageUploadVariantKind.entries.map { kind ->
            reusableVariant(directory, kind) ?: return null
        }
        if (variants.sumOf(ImageUploadVariant::contentLength) > MAX_IMAGE_BUNDLE_BYTES) return null
        return ImageUploadBundle(
            fileName = "${source.nameWithoutExtension.ifBlank { "rwms-image" }}.webp",
            variants = variants,
        )
    }

    private fun reusableVariant(
        directory: File,
        kind: ImageUploadVariantKind,
    ): ImageUploadVariant? {
        val file = File(directory, "${kind.name}.webp")
        val length = file.length()
        if (!file.isFile || length !in 1L..kind.maximumBytes) {
            return null
        }
        val dimensions = file.webpDimensions() ?: return null
        return ImageUploadVariant(
            kind = kind,
            file = file,
            contentLength = length,
            checksumSha256 = file.sha256(),
            width = dimensions.first,
            height = dimensions.second,
        )
    }

    private fun decodeBoundedBitmap(source: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Не удалось прочитать размеры фотографии"
        }
        var sampleSize = 1
        while (
            sampledPixelCount(bounds.outWidth, bounds.outHeight, sampleSize) >
            MAX_IMAGE_DECODE_PIXELS ||
            sampledMaximumEdge(bounds.outWidth, bounds.outHeight, sampleSize) >
            MAX_IMAGE_DECODE_EDGE
        ) {
            sampleSize *= 2
        }
        return requireNotNull(
            BitmapFactory.decodeFile(
                source.path,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ),
        ) { "Не удалось декодировать фотографию" }
    }

    private fun encodeVariant(
        source: Bitmap,
        directory: File,
        kind: ImageUploadVariantKind,
    ): ImageUploadVariant {
        val output = File(directory, "${kind.name}.webp")
        val candidate = File(directory, ".${kind.name}.part")
        var targetEdge = minOf(kind.maximumEdge, max(source.width, source.height))
        while (true) {
            val (width, height) = scaledDimensions(source.width, source.height, targetEdge)
            val scaled = if (width == source.width && height == source.height) {
                source
            } else {
                Bitmap.createScaledBitmap(source, width, height, true)
            }
            try {
                for (quality in WEBP_QUALITY_STEPS) {
                    FileOutputStream(candidate, false).buffered().use { stream ->
                        check(scaled.compress(webpFormat(), quality, stream)) {
                            "Не удалось закодировать ${kind.name} WebP"
                        }
                    }
                    if (candidate.length() in 1L..kind.maximumBytes) {
                        if (output.exists()) check(output.delete()) {
                            "Не удалось заменить ${kind.name} WebP"
                        }
                        check(candidate.renameTo(output)) {
                            "Не удалось сохранить ${kind.name} WebP"
                        }
                        return ImageUploadVariant(
                            kind = kind,
                            file = output,
                            contentLength = output.length(),
                            checksumSha256 = output.sha256(),
                            width = width,
                            height = height,
                        )
                    }
                }
            } finally {
                candidate.delete()
                if (scaled !== source) scaled.recycle()
            }
            check(targetEdge > MIN_IMAGE_VARIANT_EDGE) {
                "Не удалось уложить ${kind.name} WebP в допустимый размер"
            }
            targetEdge = max(
                MIN_IMAGE_VARIANT_EDGE,
                (targetEdge * IMAGE_RESOLUTION_FALLBACK_FACTOR).roundToInt(),
            ).coerceAtMost(targetEdge - 1)
        }
    }
}

/** Stable sibling directory for restart-safe, generated image parts. */
internal fun imageVariantDirectoryFor(source: File): File =
    File(requireNotNull(source.parentFile), ".rwms-image-variants-${source.name}")

/** Applies all mirrored and rotated EXIF orientations exactly once. */
internal fun imageExifOrientationMatrix(orientation: Int): Matrix = Matrix().apply {
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

private fun scaledDimensions(width: Int, height: Int, maximumEdge: Int): Pair<Int, Int> {
    val longest = max(width, height)
    if (longest <= maximumEdge) return width to height
    val scale = maximumEdge.toDouble() / longest.toDouble()
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

private fun sampledPixelCount(width: Int, height: Int, sampleSize: Int): Long =
    ((width.toLong() + sampleSize - 1L) / sampleSize) *
        ((height.toLong() + sampleSize - 1L) / sampleSize)

private fun sampledMaximumEdge(width: Int, height: Int, sampleSize: Int): Long =
    (max(width, height).toLong() + sampleSize - 1L) / sampleSize

private fun webpFormat(): Bitmap.CompressFormat =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }

/** Reads dimensions from the bounded WebP container header without decoding its pixels. */
private fun File.webpDimensions(): Pair<Int, Int>? = runCatching {
    inputStream().use { input ->
        val header = ByteArray(30)
        var count = 0
        while (count < header.size) {
            val read = input.read(header, count, header.size - count)
            if (read <= 0) break
            count += read
        }
        if (count < 25 ||
            !header.copyOfRange(0, 4).contentEquals("RIFF".encodeToByteArray()) ||
            !header.copyOfRange(8, 12).contentEquals("WEBP".encodeToByteArray())
        ) {
            return@use null
        }
        val dimensions = when (header.copyOfRange(12, 16).decodeToString()) {
            "VP8X" -> if (count >= 30) {
                (1 + header.unsigned24(24)) to (1 + header.unsigned24(27))
            } else {
                null
            }
            "VP8 " -> if (
                count >= 30 &&
                header[23] == 0x9d.toByte() &&
                header[24] == 0x01.toByte() &&
                header[25] == 0x2a.toByte()
            ) {
                ((header.unsigned16(26) and 0x3fff) to
                    (header.unsigned16(28) and 0x3fff))
            } else {
                null
            }
            "VP8L" -> if (count >= 25 && header[20] == 0x2f.toByte()) {
                val packed = header[21].unsigned().toLong() or
                    (header[22].unsigned().toLong() shl 8) or
                    (header[23].unsigned().toLong() shl 16) or
                    (header[24].unsigned().toLong() shl 24)
                ((packed and 0x3fff).toInt() + 1) to
                    (((packed shr 14) and 0x3fff).toInt() + 1)
            } else {
                null
            }
            else -> null
        }
        dimensions?.takeIf { (width, height) -> width > 0 && height > 0 }
    }
}.getOrNull()

private fun ByteArray.unsigned16(offset: Int): Int =
    this[offset].unsigned() or (this[offset + 1].unsigned() shl 8)

private fun ByteArray.unsigned24(offset: Int): Int =
    this[offset].unsigned() or
        (this[offset + 1].unsigned() shl 8) or
        (this[offset + 2].unsigned() shl 16)

private fun Byte.unsigned(): Int = toInt() and 0xff

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(FileInputStream(this), digest).use { input ->
        val buffer = ByteArray(FILE_DIGEST_BUFFER_BYTES)
        while (input.read(buffer) != -1) Unit
    }
    return digest.digest().joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

internal const val MAX_IMAGE_BUNDLE_BYTES = 1_048_576L
private const val MAX_IMAGE_DECODE_PIXELS = 6_000_000L
private const val MAX_IMAGE_DECODE_EDGE = 4_096L
private const val MIN_IMAGE_VARIANT_EDGE = 96
private const val IMAGE_RESOLUTION_FALLBACK_FACTOR = 0.82
private const val FILE_DIGEST_BUFFER_BYTES = 32 * 1024
private val WEBP_QUALITY_STEPS = listOf(84, 76, 68, 60, 52, 44, 36, 28, 20)
private val EXIF_TRANSFORM_ORIENTATIONS = setOf(
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
    ExifInterface.ORIENTATION_ROTATE_180,
    ExifInterface.ORIENTATION_FLIP_VERTICAL,
    ExifInterface.ORIENTATION_TRANSPOSE,
    ExifInterface.ORIENTATION_ROTATE_90,
    ExifInterface.ORIENTATION_TRANSVERSE,
    ExifInterface.ORIENTATION_ROTATE_270,
)
