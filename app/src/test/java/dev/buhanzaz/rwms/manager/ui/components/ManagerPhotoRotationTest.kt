package dev.buhanzaz.rwms.manager.ui.components

import android.graphics.Bitmap
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import java.io.File
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
    fun `invalid vendor exif orientation is repaired from CameraX sensor rotation`() {
        assertThat(managerExifOrientationNeedsRepair(0)).isTrue()
        assertThat(managerExifOrientationNeedsRepair(1)).isFalse()
        assertThat(managerExifOrientationNeedsRepair(8)).isFalse()
        assertThat(managerExifOrientationForRotationDegrees(0)).isEqualTo(1)
        assertThat(managerExifOrientationForRotationDegrees(90)).isEqualTo(6)
        assertThat(managerExifOrientationForRotationDegrees(180)).isEqualTo(3)
        assertThat(managerExifOrientationForRotationDegrees(270)).isEqualTo(8)
    }

    @Test
    fun `invalid exif zero is persisted as a valid orientation without recompressing capture`() {
        val capture = temporaryFolder.newFile("capture.jpg")
        writeTestJpeg(capture)
        ExifInterface(capture).run {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED.toString())
            saveAttributes()
        }

        assertThat(
            repairManagerCameraExifOrientation(
                capture,
                ExifInterface.ORIENTATION_ROTATE_90,
            ),
        ).isTrue()
        assertThat(
            ExifInterface(capture).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            ),
        ).isEqualTo(ExifInterface.ORIENTATION_ROTATE_90)
    }

    private fun writeTestJpeg(file: File) {
        val bitmap = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
        }
        bitmap.recycle()
    }
}
