package dev.buhanzaz.rwms.manager.ui.components

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
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManagerPhotoRotationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `camera capture keeps every current display rotation and safely defaults while attaching`() {
        listOf(
            Surface.ROTATION_0,
            Surface.ROTATION_90,
            Surface.ROTATION_180,
            Surface.ROTATION_270,
        ).forEach { rotation ->
            assertThat(managerCaptureTargetRotation(rotation)).isEqualTo(rotation)
        }
        assertThat(managerCaptureTargetRotation(null)).isEqualTo(Surface.ROTATION_0)
        assertThat(managerCaptureTargetRotation(42)).isEqualTo(Surface.ROTATION_0)
    }

    @Test
    fun `physical device orientation drives capture while portrait UI remains locked`() {
        assertThat(managerCaptureTargetRotationForOrientation(0, Surface.ROTATION_90))
            .isEqualTo(Surface.ROTATION_0)
        assertThat(managerCaptureTargetRotationForOrientation(90, Surface.ROTATION_0))
            .isEqualTo(Surface.ROTATION_270)
        assertThat(managerCaptureTargetRotationForOrientation(180, Surface.ROTATION_0))
            .isEqualTo(Surface.ROTATION_180)
        assertThat(managerCaptureTargetRotationForOrientation(270, Surface.ROTATION_0))
            .isEqualTo(Surface.ROTATION_90)
        assertThat(managerCaptureTargetRotationForOrientation(-1, Surface.ROTATION_90))
            .isEqualTo(Surface.ROTATION_90)
    }

    @Test
    fun `invalid vendor exif orientation resolves to the CameraX sensor fallback`() {
        assertThat(managerExifOrientationNeedsRepair(0)).isTrue()
        assertThat(managerExifOrientationNeedsRepair(1)).isFalse()
        assertThat(managerExifOrientationNeedsRepair(8)).isFalse()
        assertThat(managerExifOrientationForRotationDegrees(0)).isEqualTo(1)
        assertThat(managerExifOrientationForRotationDegrees(90)).isEqualTo(6)
        assertThat(managerExifOrientationForRotationDegrees(180)).isEqualTo(3)
        assertThat(managerExifOrientationForRotationDegrees(270)).isEqualTo(8)
        assertThat(
            managerResolvedCameraExifOrientation(
                recordedOrientation = ExifInterface.ORIENTATION_UNDEFINED,
                fallbackOrientation = ExifInterface.ORIENTATION_ROTATE_90,
            ),
        ).isEqualTo(ExifInterface.ORIENTATION_ROTATE_90)
        assertThat(
            managerResolvedCameraExifOrientation(
                recordedOrientation = ExifInterface.ORIENTATION_UNDEFINED,
                fallbackOrientation = ExifInterface.ORIENTATION_UNDEFINED,
            ),
        ).isEqualTo(ExifInterface.ORIENTATION_NORMAL)
    }

    @Test
    fun `landscape capture is rotated into upright jpeg pixels and marked normal`() {
        val capture = temporaryFolder.newFile("landscape.jpg")
        writeTestJpeg(capture, width = 60, height = 40)
        ExifInterface(capture).run {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val normalized = requireNotNull(
            normalizeManagerCameraJpegOrientation(
                capture,
                ExifInterface.ORIENTATION_NORMAL,
            ),
        )
        val bitmap = requireNotNull(BitmapFactory.decodeFile(normalized.path))

        assertThat(normalized).isNotEqualTo(capture)
        assertThat(bitmap.width).isEqualTo(40)
        assertThat(bitmap.height).isEqualTo(60)
        assertThat(
            ExifInterface(normalized).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            ),
        ).isEqualTo(ExifInterface.ORIENTATION_NORMAL)
        bitmap.recycle()
    }

    @Test
    fun `portrait jpeg stays upright and has an explicit normal orientation`() {
        val capture = temporaryFolder.newFile("portrait.jpg")
        writeTestJpeg(capture, width = 40, height = 60)
        ExifInterface(capture).run {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            saveAttributes()
        }

        val normalized = requireNotNull(
            normalizeManagerCameraJpegOrientation(
                capture,
                ExifInterface.ORIENTATION_ROTATE_90,
            ),
        )
        val bitmap = requireNotNull(BitmapFactory.decodeFile(normalized.path))

        assertThat(normalized).isEqualTo(capture)
        assertThat(bitmap.width).isEqualTo(40)
        assertThat(bitmap.height).isEqualTo(60)
        assertThat(
            ExifInterface(normalized).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            ),
        ).isEqualTo(ExifInterface.ORIENTATION_NORMAL)
        bitmap.recycle()
    }

    @Test
    fun `undefined camera exif is normalized with the target rotation fallback`() {
        val capture = temporaryFolder.newFile("undefined-orientation.jpg")
        writeTestJpeg(capture, width = 60, height = 40)
        ExifInterface(capture).run {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED.toString())
            saveAttributes()
        }

        val normalized = requireNotNull(
            normalizeManagerCameraJpegOrientation(
                capture,
                ExifInterface.ORIENTATION_ROTATE_90,
            ),
        )
        val bitmap = requireNotNull(BitmapFactory.decodeFile(normalized.path))

        assertThat(bitmap.width).isEqualTo(40)
        assertThat(bitmap.height).isEqualTo(60)
        assertThat(
            ExifInterface(normalized).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            ),
        ).isEqualTo(ExifInterface.ORIENTATION_NORMAL)
        bitmap.recycle()
    }

    @Test
    fun `camera capture uses CameraX rotation when a vendor wrongly marks sideways pixels normal`() {
        val capture = temporaryFolder.newFile("vendor-normal-landscape.jpg")
        writeTestJpeg(capture, width = 60, height = 40)
        ExifInterface(capture).run {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            saveAttributes()
        }

        val normalized = requireNotNull(
            normalizeManagerCameraCapturedJpegOrientation(
                source = capture,
                rotationDegrees = 90,
            ),
        )
        val bitmap = requireNotNull(BitmapFactory.decodeFile(normalized.file.path))

        assertThat(normalized.file).isNotEqualTo(capture)
        assertThat(bitmap.width).isEqualTo(40)
        assertThat(bitmap.height).isEqualTo(60)
        assertThat(
            ExifInterface(normalized.file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            ),
        ).isEqualTo(ExifInterface.ORIENTATION_NORMAL)
        bitmap.recycle()
    }

    @Test
    fun `CameraX proxy pipeline fixes vertical capture and closes the image`() {
        val rawCapture = temporaryFolder.newFile("raw-vertical.jpg")
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
        val target = File(temporaryFolder.root, "camera-x-vertical.jpg")

        val normalized = requireNotNull(persistManagerCameraImageProxy(proxy, target))
        val bitmap = requireNotNull(BitmapFactory.decodeFile(normalized.file.path))

        assertThat(proxy.closed).isTrue()
        assertThat(bitmap.width).isEqualTo(40)
        assertThat(bitmap.height).isEqualTo(60)
        assertThat(
            ExifInterface(normalized.file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            ),
        ).isEqualTo(ExifInterface.ORIENTATION_NORMAL)
        bitmap.recycle()
    }

    @Test
    fun `unsupported CameraX proxy is closed without creating a photo`() {
        val proxy = FakeJpegImageProxy(
            jpegBytes = byteArrayOf(0x01, 0x02),
            proxyRotationDegrees = 90,
            proxyFormat = ImageFormat.YUV_420_888,
            proxyWidth = 1,
            proxyHeight = 1,
        )
        val target = File(temporaryFolder.root, "unsupported.jpg")

        val normalized = persistManagerCameraImageProxy(proxy, target)

        assertThat(normalized).isNull()
        assertThat(proxy.closed).isTrue()
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `rotate 90 matrix maps raw camera pixels clockwise before output normalization`() {
        val points = floatArrayOf(
            0f,
            0f,
            60f,
            0f,
            0f,
            40f,
            60f,
            40f,
        )

        managerExifOrientationMatrix(ExifInterface.ORIENTATION_ROTATE_90).mapPoints(points)

        assertThat(points.map { value -> value.toInt() }).containsExactly(
            0,
            0,
            0,
            60,
            -40,
            0,
            -40,
            60,
        ).inOrder()
    }

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
