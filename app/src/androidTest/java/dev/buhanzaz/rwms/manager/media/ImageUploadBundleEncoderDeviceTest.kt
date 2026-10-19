package dev.buhanzaz.rwms.manager.media

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies on Android that the production encoder emits real bounded WebP containers. */
@RunWith(AndroidJUnit4::class)
class ImageUploadBundleEncoderDeviceTest {
    @Test
    fun encodedPartsAreRealWebpFilesWithinTheAggregateBudget() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "manager-webp-device-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(1_600, 900, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff4477aa.toInt())
        }
        try {
            source.outputStream().buffered().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
            }
            val bundle = ImageUploadBundleEncoder().encode(source)

            assertEquals(ImageUploadVariantKind.entries, bundle.variants.map { it.kind })
            assertTrue(bundle.variants.sumOf { it.contentLength } <= MAX_IMAGE_BUNDLE_BYTES)
            bundle.variants.forEach { variant ->
                val header = variant.file.inputStream().use { input -> ByteArray(12).also(input::read) }
                assertEquals("RIFF", header.copyOfRange(0, 4).decodeToString())
                assertEquals("WEBP", header.copyOfRange(8, 12).decodeToString())
                assertTrue(variant.contentLength in 1L..variant.kind.maximumBytes)
                assertTrue(variant.width > 0 && variant.height > 0)
            }
        } finally {
            bitmap.recycle()
            imageVariantDirectoryFor(source).deleteRecursively()
            source.delete()
        }
    }
}
