package dev.buhanzaz.rwms.worker.core.media

import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

class EncryptedJpegRequestBody(
    private val evidence: TaskEvidenceEntity,
    private val fileStore: EncryptedEvidenceFileStore,
    private val onProgress: (writtenBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
) : RequestBody() {
    override fun contentType() = "image/jpeg".toMediaType()

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
                // Report in bounded increments and cap at 99%; 100 means the
                // gateway has accepted bytes and the finalize request succeeded.
                val percent = uploadProgressPercent(written, evidence.sizeBytes)
                if (percent >= lastReportedPercent + PROGRESS_STEP_PERCENT) {
                    lastReportedPercent = percent
                    onProgress(written, evidence.sizeBytes)
                }
            }
        }
    }

    private companion object {
        const val PROGRESS_STEP_PERCENT = 5
    }
}

/** 100 is reserved for a successful gateway upload+finalize acknowledgement. */
internal fun uploadProgressPercent(writtenBytes: Long, totalBytes: Long): Int {
    require(totalBytes > 0)
    return ((writtenBytes * 100) / totalBytes).toInt().coerceIn(0, 99)
}
