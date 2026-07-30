package dev.buhanzaz.rwms.manager.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManagerPhotoRotationTest {
    @Test
    fun `local original is rewritten at the same URI with normalized EXIF orientation`() {
        val original = File.createTempFile("rwms-photo-rotation", ".jpg")
        try {
            val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
            try {
                original.outputStream().use { output ->
                    assertThat(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)).isTrue()
                }
            } finally {
                bitmap.recycle()
            }
            ExifInterface(original.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val uri = Uri.fromFile(original).toString()

            val result = rotateManagerPhotoInPlace(uri)

            assertThat(result.localInPlaceHandled).isTrue()
            assertThat(Uri.parse(uri).path).isEqualTo(original.path)
            assertThat(ExifInterface(original.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            )).isEqualTo(ExifInterface.ORIENTATION_NORMAL)
            val output = BitmapFactory.decodeFile(original.path)
            assertThat(output.width).isEqualTo(20)
            assertThat(output.height).isEqualTo(10)
            output.recycle()
        } finally {
            original.delete()
        }
    }

    @Test
    fun `non local media keeps the server rotation path`() {
        assertThat(
            rotateManagerPhotoInPlace("https://example.test/api/media/v1/assets/photo").localInPlaceHandled,
        ).isFalse()
    }
}
