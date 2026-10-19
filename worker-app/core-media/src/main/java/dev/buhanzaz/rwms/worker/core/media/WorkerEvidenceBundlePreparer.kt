package dev.buhanzaz.rwms.worker.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.buhanzaz.rwms.worker.core.database.EncryptedEvidenceVariantPart
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Immutable size policy for one client-produced still-image upload part. */
private data class EvidenceVariantSpec(
    val kind: String,
    val maximumEdge: Int,
)

/** A scaled in-memory bitmap and whether this function owns its allocation. */
private data class EvidenceVariantBitmap(
    val spec: EvidenceVariantSpec,
    val bitmap: Bitmap,
    val owned: Boolean,
)

/**
 * Converts one camera/gallery source to upright WebP pixels before encrypting a local original and
 * exactly three upload variants. At most one source image is decoded at a time.
 */
@Singleton
class WorkerEvidenceBundlePreparer @Inject constructor(
    @ApplicationContext context: Context,
    private val fileStore: EncryptedEvidenceFileStore,
) {
    private val preparationDirectory = File(context.cacheDir, "worker-evidence-preparation")

    /** Takes ownership of [source], returning only encrypted durable files. */
    fun prepareAndPersist(
        userId: String,
        evidenceId: String,
        source: File,
    ): EncryptedEvidenceBundle {
        require(source.isFile) { "Captured image is missing" }
        require(source.length() in 1..EncryptedEvidenceFileStore.MAX_SOURCE_IMAGE_BYTES) {
            "Captured image exceeds 15 MiB"
        }
        check(preparationDirectory.mkdirs() || preparationDirectory.isDirectory) {
            "Could not create evidence preparation cache"
        }
        return try {
            val plain = encodeWorkerEvidenceBundle(source, preparationDirectory, evidenceId)
            fileStore.persistWebpBundle(userId, evidenceId, plain)
        } finally {
            source.delete()
        }
    }
}

/**
 * Produces a plaintext bundle for immediate encryption. Pixels are physically oriented before any
 * WebP is encoded, and variant quality/dimensions are reduced until their aggregate is at most 1 MiB.
 */
internal fun encodeWorkerEvidenceBundle(
    source: File,
    outputDirectory: File,
    evidenceId: String,
): PlainEvidenceBundle {
    require(source.isFile && source.length() > 0L) { "Evidence source is empty" }
    check(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
        "Could not create evidence output directory"
    }
    val originalOutput = File(outputDirectory, "$evidenceId-original.webp")
    val variantOutputs = EncryptedEvidenceFileStore.REQUIRED_VARIANT_KINDS.associateWith { kind ->
        File(outputDirectory, "$evidenceId-${kind.lowercase()}.webp")
    }
    val decoded = decodeEvidenceBitmap(source)
    val orientation = readEvidenceOrientation(source)
    val upright = orientEvidenceBitmap(decoded, orientation)
    try {
        writeBoundedOriginalWebp(upright, originalOutput)
        var edgeScale = 1.0
        repeat(MAX_DIMENSION_ATTEMPTS) {
            val bitmaps = BASE_VARIANT_SPECS.map { spec ->
                val scaledSpec = spec.copy(
                    maximumEdge = (spec.maximumEdge * edgeScale).roundToInt().coerceAtLeast(MINIMUM_EDGE),
                )
                val dimensions = boundedEvidenceDimensions(
                    upright.width,
                    upright.height,
                    scaledSpec.maximumEdge,
                )
                val bitmap = if (dimensions.first == upright.width && dimensions.second == upright.height) {
                    upright
                } else {
                    Bitmap.createScaledBitmap(upright, dimensions.first, dimensions.second, true)
                }
                EvidenceVariantBitmap(scaledSpec, bitmap, bitmap !== upright)
            }
            try {
                for (quality in VARIANT_QUALITIES) {
                    bitmaps.forEach { variant ->
                        writeWebp(
                            bitmap = variant.bitmap,
                            output = requireNotNull(variantOutputs[variant.spec.kind]),
                            quality = quality,
                        )
                    }
                    val aggregate = variantOutputs.values.sumOf(File::length)
                    if (aggregate in 1..EncryptedEvidenceFileStore.MAX_UPLOAD_BUNDLE_BYTES) {
                        val variants = bitmaps.map { variant ->
                            val file = requireNotNull(variantOutputs[variant.spec.kind])
                            PlainEvidenceVariantPart(
                                kind = variant.spec.kind,
                                file = file,
                                contentLength = file.length(),
                                checksumSha256 = EncryptedEvidenceFileStore.sha256(file),
                                width = variant.bitmap.width,
                                height = variant.bitmap.height,
                            )
                        }
                        return PlainEvidenceBundle(
                            original = originalOutput,
                            variants = variants,
                            aggregateContentLength = aggregate,
                            manifestSha256 = workerEvidenceManifestSha256(variants),
                        )
                    }
                }
            } finally {
                bitmaps.filter(EvidenceVariantBitmap::owned).forEach { variant -> variant.bitmap.recycle() }
            }
            edgeScale *= DIMENSION_REDUCTION_FACTOR
        }
        error("Could not fit evidence variants inside 1 MiB")
    } catch (error: Throwable) {
        originalOutput.delete()
        variantOutputs.values.forEach(File::delete)
        throw error
    } finally {
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
    }
}

/** Returns the exact manifest text whose SHA-256 is reserved with task-board. */
internal fun workerEvidenceManifestPayload(variants: List<PlainEvidenceVariantPart>): String {
    val byKind = variants.associateBy(PlainEvidenceVariantPart::kind)
    require(byKind.keys == EncryptedEvidenceFileStore.REQUIRED_VARIANT_KINDS.toSet()) {
        "Evidence manifest must contain exactly SMALL, MEDIUM and LARGE"
    }
    return buildString {
        append("rwms-image-variants-v1\n")
        EncryptedEvidenceFileStore.REQUIRED_VARIANT_KINDS.forEach { kind ->
            val part = requireNotNull(byKind[kind])
            append(kind)
            append(':')
            append(part.contentLength)
            append(':')
            append(part.checksumSha256)
            append(':')
            append(part.width)
            append('x')
            append(part.height)
            append('\n')
        }
    }
}

/** Computes the deterministic lowercase manifest digest shared with task-board and media-service. */
internal fun workerEvidenceManifestSha256(variants: List<PlainEvidenceVariantPart>): String =
    MessageDigest.getInstance("SHA-256")
        .digest(workerEvidenceManifestPayload(variants).toByteArray(StandardCharsets.UTF_8))
        .toHex()

/** Recomputes the same reservation digest from encrypted durable part metadata. */
internal fun workerEncryptedEvidenceManifestSha256(
    variants: List<EncryptedEvidenceVariantPart>,
): String {
    val byKind = variants.associateBy(EncryptedEvidenceVariantPart::kind)
    require(byKind.keys == EncryptedEvidenceFileStore.REQUIRED_VARIANT_KINDS.toSet()) {
        "Evidence manifest must contain exactly SMALL, MEDIUM and LARGE"
    }
    val payload = buildString {
        append("rwms-image-variants-v1\n")
        EncryptedEvidenceFileStore.REQUIRED_VARIANT_KINDS.forEach { kind ->
            val part = requireNotNull(byKind[kind])
            append(kind)
            append(':')
            append(part.contentLength)
            append(':')
            append(part.checksumSha256)
            append(':')
            append(part.width)
            append('x')
            append(part.height)
            append('\n')
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(StandardCharsets.UTF_8))
        .toHex()
}

private fun decodeEvidenceBitmap(source: File): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.path, bounds)
    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Captured file is not a supported image" }
    var sampleSize = 1
    while (sampledEvidencePixels(bounds.outWidth, bounds.outHeight, sampleSize) > MAX_DECODE_PIXELS) {
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
    ) { "Could not decode captured image" }
}

private fun readEvidenceOrientation(source: File): Int = runCatching {
    ExifInterface(source).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    )
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

private fun orientEvidenceBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
    if (orientation == ExifInterface.ORIENTATION_NORMAL) return bitmap
    return Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        evidenceOrientationMatrix(orientation),
        true,
    )
}

private fun evidenceOrientationMatrix(orientation: Int): Matrix = Matrix().apply {
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

private fun writeBoundedOriginalWebp(bitmap: Bitmap, output: File) {
    for (quality in ORIGINAL_QUALITIES) {
        writeWebp(bitmap, output, quality)
        if (output.length() in 1..EncryptedEvidenceFileStore.MAX_SOURCE_IMAGE_BYTES) return
    }
    error("Prepared original exceeds 15 MiB")
}

@Suppress("DEPRECATION")
private fun writeWebp(bitmap: Bitmap, output: File, quality: Int) {
    FileOutputStream(output, false).buffered().use { stream ->
        val format = if (Build.VERSION.SDK_INT >= 30) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        check(bitmap.compress(format, quality, stream)) { "Could not encode WebP evidence" }
    }
    require(output.length() > 0L) { "Encoded WebP evidence is empty" }
}

private fun boundedEvidenceDimensions(width: Int, height: Int, maximumEdge: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && maximumEdge > 0)
    val sourceEdge = maxOf(width, height)
    if (sourceEdge <= maximumEdge) return width to height
    val scale = maximumEdge.toDouble() / sourceEdge.toDouble()
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

private fun sampledEvidencePixels(width: Int, height: Int, sampleSize: Int): Long {
    val sampledWidth = (width.toLong() + sampleSize - 1L) / sampleSize
    val sampledHeight = (height.toLong() + sampleSize - 1L) / sampleSize
    return sampledWidth * sampledHeight
}

private val BASE_VARIANT_SPECS = listOf(
    EvidenceVariantSpec("SMALL", 360),
    EvidenceVariantSpec("MEDIUM", 960),
    EvidenceVariantSpec("LARGE", 1_600),
)
private val ORIGINAL_QUALITIES = intArrayOf(92, 84, 76, 68, 60)
private val VARIANT_QUALITIES = intArrayOf(82, 74, 66, 58, 50, 42, 34, 26)
private const val MAX_DECODE_PIXELS = 8_000_000L
private const val MAX_DIMENSION_ATTEMPTS = 4
private const val DIMENSION_REDUCTION_FACTOR = 0.8
private const val MINIMUM_EDGE = 240
