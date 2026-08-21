package dev.buhanzaz.rwms.worker.core.media

import dev.buhanzaz.rwms.worker.core.database.EncryptedEvidenceVariantPart
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

/** Streams one encrypted WebP variant to its same-origin upload path with bounded progress events. */
class EncryptedWebpVariantRequestBody(
    private val variant: EncryptedEvidenceVariantPart,
    private val fileStore: EncryptedEvidenceFileStore,
    private val onProgress: (writtenBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
) : RequestBody() {
    override fun contentType() = "image/webp".toMediaType()

    override fun contentLength(): Long = variant.contentLength

    override fun writeTo(sink: BufferedSink) {
        fileStore.openDecrypted(variant.encryptedPath).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var written = 0L
            var lastReportedPercent = -1
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                written += read
                val percent = uploadProgressPercent(written, variant.contentLength)
                if (percent >= lastReportedPercent + PROGRESS_STEP_PERCENT) {
                    lastReportedPercent = percent
                    onProgress(written, variant.contentLength)
                }
            }
            check(written == variant.contentLength) { "Encrypted WebP length changed after capture" }
        }
    }

    /** Bounds durable progress writes while OkHttp streams a part. */
    private companion object {
        const val PROGRESS_STEP_PERCENT = 5
    }
}

/**
 * Streams a JPEG captured by a pre-WebP WorkerApp version so an application upgrade cannot strand
 * already encrypted offline evidence. New captures never use this source-upload compatibility path.
 */
class EncryptedSourceEvidenceRequestBody(
    private val evidence: TaskEvidenceEntity,
    private val fileStore: EncryptedEvidenceFileStore,
    private val onProgress: (writtenBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
) : RequestBody() {
    init {
        require(evidence.contentType == "image/jpeg") { "Only retained JPEG evidence can use source upload" }
        require(evidence.sizeBytes in 1..EncryptedEvidenceFileStore.MAX_SOURCE_IMAGE_BYTES) {
            "Retained evidence exceeds the source upload limit"
        }
    }

    override fun contentType() = evidence.contentType.toMediaType()

    override fun contentLength(): Long = evidence.sizeBytes

    override fun writeTo(sink: BufferedSink) {
        fileStore.openDecrypted(evidence.encryptedFilePath).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var written = 0L
            var lastReportedPercent = -1
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                written += read
                val percent = uploadProgressPercent(written, evidence.sizeBytes)
                if (percent >= lastReportedPercent + PROGRESS_STEP_PERCENT) {
                    lastReportedPercent = percent
                    onProgress(written, evidence.sizeBytes)
                }
            }
            check(written == evidence.sizeBytes) { "Retained evidence length changed after capture" }
        }
    }

    /** Bounds durable progress writes while OkHttp streams retained evidence. */
    private companion object {
        const val PROGRESS_STEP_PERCENT = 5
    }
}

/** 100 is reserved for a successful gateway upload and finalize acknowledgement. */
internal fun uploadProgressPercent(writtenBytes: Long, totalBytes: Long): Int {
    require(totalBytes > 0)
    return ((writtenBytes * 100) / totalBytes).toInt().coerceIn(0, 99)
}
