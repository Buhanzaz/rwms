package dev.buhanzaz.rwms.driver.feature.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.view.Surface
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.ByteBuffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriverCameraOrientationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `physical device orientation drives capture while the driver screen stays portrait`() {
        assertThat(driverCaptureTargetRotationForOrientation(0, Surface.ROTATION_90))
            .isEqualTo(Surface.ROTATION_0)
        assertThat(driverCaptureTargetRotationForOrientation(90, Surface.ROTATION_0))
            .isEqualTo(Surface.ROTATION_270)
        assertThat(driverCaptureTargetRotationForOrientation(180, Surface.ROTATION_0))
            .isEqualTo(Surface.ROTATION_180)
        assertThat(driverCaptureTargetRotationForOrientation(270, Surface.ROTATION_0))
            .isEqualTo(Surface.ROTATION_90)
        assertThat(driverCaptureTargetRotationForOrientation(-1, Surface.ROTATION_90))
            .isEqualTo(Surface.ROTATION_90)
    }

    @Test
    fun `landscape capture is rewritten into upright JPEG pixels with normal EXIF`() {
        val capture = temporaryFolder.newFile("landscape.jpg")
        writeTestJpeg(capture, width = 60, height = 40)
        ExifInterface(capture).run {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val normalized = requireNotNull(
            normalizeDriverCameraJpegOrientation(capture, ExifInterface.ORIENTATION_NORMAL),
        )
        val bitmap = requireNotNull(BitmapFactory.decodeFile(normalized.path))
        try {
            assertThat(normalized).isNotEqualTo(capture)
            assertThat(bitmap.width).isEqualTo(40)
            assertThat(bitmap.height).isEqualTo(60)
            assertThat(exifOrientation(normalized)).isEqualTo(ExifInterface.ORIENTATION_NORMAL)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `CameraX rotation corrects raw landscape pixels even when vendor EXIF says normal`() {
        val rawCapture = temporaryFolder.newFile("raw-landscape.jpg")
        writeTestJpeg(rawCapture, width = 60, height = 40)
        ExifInterface(rawCapture).run {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            saveAttributes()
        }
        val proxy = FakeJpegImageProxy(
            jpegBytes = rawCapture.readBytes(),
            proxyRotationDegrees = 90,
            proxyWidth = 60,
            proxyHeight = 40,
        )
        val target = File(temporaryFolder.root, "camera-x-landscape.jpg")

        val normalized = requireNotNull(persistDriverCameraImageProxy(proxy, target))

        assertThat(proxy.closed).isTrue()
        val bitmap = requireNotNull(BitmapFactory.decodeFile(normalized.path))
        try {
            assertThat(bitmap.width).isEqualTo(40)
            assertThat(bitmap.height).isEqualTo(60)
            assertThat(exifOrientation(normalized)).isEqualTo(ExifInterface.ORIENTATION_NORMAL)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `unsupported CameraX proxy is closed without writing a capture`() {
        val proxy = FakeJpegImageProxy(
            jpegBytes = byteArrayOf(0x01, 0x02),
            proxyRotationDegrees = 90,
            proxyFormat = ImageFormat.YUV_420_888,
            proxyWidth = 1,
            proxyHeight = 1,
        )
        val target = File(temporaryFolder.root, "unsupported.jpg")

        val normalized = persistDriverCameraImageProxy(proxy, target)

        assertThat(normalized).isNull()
        assertThat(proxy.closed).isTrue()
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `portrait capture remains upright and carries explicit normal EXIF`() {
        val capture = temporaryFolder.newFile("portrait.jpg")
        writeTestJpeg(capture, width = 40, height = 60)
        ExifInterface(capture).run {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            saveAttributes()
        }

        val normalized = requireNotNull(
            normalizeDriverCameraJpegOrientation(capture, ExifInterface.ORIENTATION_ROTATE_90),
        )
        val bitmap = requireNotNull(BitmapFactory.decodeFile(normalized.path))
        try {
            assertThat(normalized).isEqualTo(capture)
            assertThat(bitmap.width).isEqualTo(40)
            assertThat(bitmap.height).isEqualTo(60)
            assertThat(exifOrientation(normalized)).isEqualTo(ExifInterface.ORIENTATION_NORMAL)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `undefined vendor EXIF uses the physical CameraX fallback before encryption`() {
        val capture = temporaryFolder.newFile("undefined.jpg")
        writeTestJpeg(capture, width = 60, height = 40)
        ExifInterface(capture).run {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED.toString())
            saveAttributes()
        }

        val normalized = requireNotNull(
            normalizeDriverCameraJpegOrientation(capture, ExifInterface.ORIENTATION_ROTATE_90),
        )
        val bitmap = requireNotNull(BitmapFactory.decodeFile(normalized.path))
        try {
            assertThat(bitmap.width).isEqualTo(40)
            assertThat(bitmap.height).isEqualTo(60)
            assertThat(exifOrientation(normalized)).isEqualTo(ExifInterface.ORIENTATION_NORMAL)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `orientation processing caps large camera images before holding two bitmaps`() {
        assertThat(driverOrientationNormalizationSampleSize(4_000, 2_000)).isEqualTo(1)
        assertThat(driverOrientationNormalizationSampleSize(4_000, 3_000)).isEqualTo(2)
        assertThat(driverOrientationNormalizationSampleSize(8_000, 6_000)).isEqualTo(4)
    }

    @Test
    fun `wide zoom is 0_6x only when the active camera supports it`() {
        assertThat(driverCaptureMinimumZoom(0.5f, 4f)).isEqualTo(0.6f)
        assertThat(driverCoerceCaptureZoom(0.4f, 0.5f, 4f)).isEqualTo(0.6f)
        assertThat(driverSupportedZoomStops(driverCaptureMinimumZoom(0.5f, 4f), 4f))
            .containsExactly(0.6f, 1f, 2f, 3f)
            .inOrder()
        assertThat(driverCaptureMinimumZoom(1f, 4f)).isEqualTo(1f)
        assertThat(driverCaptureMinimumZoom(0.4f, 0.5f)).isEqualTo(0.4f)
    }

    private fun exifOrientation(file: File): Int = ExifInterface(file).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_UNDEFINED,
    )

    private fun writeTestJpeg(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
        }
        bitmap.recycle()
    }

    private class FakeJpegImageProxy(
        private val jpegBytes: ByteArray,
        private val proxyRotationDegrees: Int,
        private val proxyFormat: Int = ImageFormat.JPEG,
        private val proxyWidth: Int,
        private val proxyHeight: Int,
    ) : ImageProxy {
        var closed = false
            private set

        private var storedCropRect = Rect(0, 0, proxyWidth, proxyHeight)
        private val plane = object : ImageProxy.PlaneProxy {
            override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(jpegBytes).asReadOnlyBuffer()

            override fun getPixelStride(): Int = 1

            override fun getRowStride(): Int = jpegBytes.size
        }
        private val fakeImageInfo = object : ImageInfo {
            override fun getRotationDegrees(): Int = proxyRotationDegrees

            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()

            override fun getTimestamp(): Long = 0L

            override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
        }

        override fun close() {
            closed = true
        }

        override fun getCropRect(): Rect = storedCropRect

        override fun setCropRect(rect: Rect?) {
            storedCropRect = rect?.let(::Rect) ?: Rect()
        }

        override fun getFormat(): Int = proxyFormat

        override fun getHeight(): Int = proxyHeight

        override fun getWidth(): Int = proxyWidth

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(plane)

        override fun getImageInfo(): ImageInfo = fakeImageInfo

        override fun getImage(): Image? = null
    }
}
