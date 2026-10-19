package dev.buhanzaz.rwms.worker

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.buhanzaz.rwms.worker.core.database.EncryptedEvidenceVariantPart
import dev.buhanzaz.rwms.worker.core.media.EncryptedEvidenceFileStore
import dev.buhanzaz.rwms.worker.core.media.WorkerEvidenceBundlePreparer
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies on Android that WorkerApp persists three genuine, bounded WebP upload parts. */
@RunWith(AndroidJUnit4::class)
class WorkerEvidenceBundleDeviceTest {
    @Test
    fun encryptedUploadPartsDecryptToRealWebpContainersWithinTheAggregateBudget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileStore = EncryptedEvidenceFileStore(context)
        val source = File(context.cacheDir, "worker-webp-device-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(1_600, 900, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff4477aa.toInt())
        }
        val evidenceId = UUID.randomUUID().toString()
        val userId = UUID.randomUUID().toString()
        var encryptedOriginal: String? = null
        var encryptedVariants = emptyList<EncryptedEvidenceVariantPart>()
        try {
            source.outputStream().buffered().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
            }
            val bundle = WorkerEvidenceBundlePreparer(context, fileStore)
                .prepareAndPersist(userId, evidenceId, source)
            encryptedOriginal = bundle.originalEncryptedPath
            encryptedVariants = bundle.variants

            assertEquals(listOf("SMALL", "MEDIUM", "LARGE"), bundle.variants.map { it.kind })
            assertTrue(bundle.aggregateContentLength <= EncryptedEvidenceFileStore.MAX_UPLOAD_BUNDLE_BYTES)
            bundle.variants.forEach { variant ->
                val header = fileStore.openDecrypted(variant.encryptedPath).use { input ->
                    ByteArray(12).also { bytes -> assertEquals(bytes.size, input.read(bytes)) }
                }
                assertEquals("RIFF", header.copyOfRange(0, 4).decodeToString())
                assertEquals("WEBP", header.copyOfRange(8, 12).decodeToString())
                assertTrue(variant.contentLength > 0)
                assertTrue(variant.width > 0 && variant.height > 0)
            }
        } finally {
            bitmap.recycle()
            encryptedOriginal?.let { fileStore.deleteBundle(it, encryptedVariants) }
            source.delete()
        }
    }
}
