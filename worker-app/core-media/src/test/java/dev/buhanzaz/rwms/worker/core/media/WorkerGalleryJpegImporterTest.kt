package dev.buhanzaz.rwms.worker.core.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Exercises the gallery boundary, orientation normalization, size guards, and cleanup contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkerGalleryJpegImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var application: Application
    private lateinit var importDirectory: File

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        importDirectory = File(application.cacheDir, WORKER_GALLERY_IMPORT_DIRECTORY)
        importDirectory.deleteRecursively()
    }

    @After
    fun tearDown() {
        importDirectory.deleteRecursively()
    }

    @Test
    fun `content image is read off main and returned as upright bounded JPEG`() {
        val selected = temporaryFolder.newFile("selected.jpg")
        writeTestJpeg(selected, width = 60, height = 40)
        ExifInterface(selected).run {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val selectedUri = Uri.parse("content://rwms-test/gallery/rotated")
        val openedOnMainThread = AtomicBoolean(true)
        Shadows.shadowOf(application.contentResolver).registerInputStreamSupplier(selectedUri) {
            openedOnMainThread.set(Looper.myLooper() == Looper.getMainLooper())
            FileInputStream(selected)
        }

        val output = runBlocking { WorkerGalleryJpegImporter(application).`import`(selectedUri) }

        val bitmap = requireNotNull(BitmapFactory.decodeFile(output.path))
        try {
            assertThat(openedOnMainThread.get()).isFalse()
            assertThat(bitmap.width).isEqualTo(40)
            assertThat(bitmap.height).isEqualTo(60)
            assertThat(bitmap.width.toLong() * bitmap.height.toLong()).isAtMost(8_000_000L)
            assertThat(output.length()).isAtMost(EncryptedEvidenceFileStore.MAX_SOURCE_IMAGE_BYTES)
            assertThat(
                ExifInterface(output).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED,
                ),
            ).isEqualTo(ExifInterface.ORIENTATION_NORMAL)
            assertThat(importDirectory.listFiles()?.toList().orEmpty()).containsExactly(output)
        } finally {
            bitmap.recycle()
            output.delete()
        }
    }

    @Test
    fun `empty picker stream leaves no temporary files`() {
        val selectedUri = Uri.parse("content://rwms-test/gallery/empty")
        Shadows.shadowOf(application.contentResolver).registerInputStream(
            selectedUri,
            ByteArrayInputStream(byteArrayOf()),
        )

        val result = runCatching {
            runBlocking { WorkerGalleryJpegImporter(application).`import`(selectedUri) }
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(importDirectory.listFiles()?.toList().orEmpty()).isEmpty()
    }

    @Test
    fun `stream copy rejects bytes beyond its explicit source limit`() {
        val output = ByteArrayOutputStream()

        val result = runCatching {
            copyWorkerGalleryStream(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
                output = output,
                maxBytes = 4,
            )
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(output.size()).isEqualTo(0)
    }

    @Test
    fun `decode and exact scaling plans keep normalized pixels at eight megapixels`() {
        assertThat(workerGalleryDecodeSampleSize(4_000, 2_000)).isEqualTo(1)
        assertThat(workerGalleryDecodeSampleSize(4_000, 3_000)).isEqualTo(2)
        assertThat(workerGalleryDecodeSampleSize(8_000, 6_000)).isEqualTo(4)

        val dimensions = workerGalleryBoundDimensions(5_000, 3_000)
        assertThat(dimensions.first.toLong() * dimensions.second.toLong()).isAtMost(8_000_000L)
        assertThat(dimensions.first.toDouble() / dimensions.second.toDouble()).isWithin(0.01)
            .of(5.0 / 3.0)
    }

    private fun writeTestJpeg(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
