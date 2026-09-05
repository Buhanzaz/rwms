package dev.buhanzaz.rwms.client.ui

import android.app.Application
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNativeImageDecoder

/** Real bitmap checks for selected pixels, encoded orientation, memory bounds and metadata removal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [CustomerAvatarImageSourceShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomerAvatarCropTest {
    @Test
    fun `square crop fills portrait and landscape and clamps dragged edges`() {
        listOf(1600 to 800, 800 to 1600, 900 to 900).forEach { (width, height) ->
            val original = CustomerAvatarCrop().sourceRect(width, height)
            assertThat(original.width).isEqualTo(minOf(width, height).toFloat())
            assertThat(original.height).isEqualTo(original.width)
            val moved = CustomerAvatarCrop().transformed(
                width, height, Size(400f, 600f), Offset(200f, 300f), Offset(100_000f, -100_000f), 3f,
            ).sourceRect(width, height)
            assertThat(moved.left).isAtLeast(0f)
            assertThat(moved.top).isAtLeast(0f)
            assertThat(moved.right).isAtMost(width.toFloat())
            assertThat(moved.bottom).isAtMost(height.toFloat())
            assertThat(moved.width).isWithin(0.001f).of(moved.height)
        }
    }

    @Test
    fun `pinch preserves its image anchor and clamps zoom range`() {
        val viewport = Size(400f, 400f)
        val initial = CustomerAvatarCrop()
        val anchor = Offset(250f, 200f)
        val before = initial.sourceRect(1000, 1000)
        val zoomed = initial.transformed(1000, 1000, viewport, anchor, Offset.Zero, 2f)
        val after = zoomed.sourceRect(1000, 1000)
        assertThat(before.center.x + 50f * before.width / 360f)
            .isWithin(0.001f).of(after.center.x + 50f * after.width / 360f)
        assertThat(initial.transformed(1000, 1000, viewport, anchor, Offset.Zero, 100f).zoom).isEqualTo(6f)
        assertThat(initial.transformed(1000, 1000, viewport, anchor, Offset.Zero, 0.01f).zoom).isEqualTo(1f)
    }

    @Test
    fun `export is a square JPEG containing the chosen side of the photo`() {
        val source = twoColorAvatar()
        try {
            val bytes = renderCustomerAvatarJpeg(source, CustomerAvatarCrop(centerX = 0.75f))
            val result = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                assertThat(result.width).isEqualTo(1024)
                assertThat(result.height).isEqualTo(1024)
                assertThat(bytes.take(2)).containsExactly(0xff.toByte(), 0xd8.toByte()).inOrder()
                val selected = result.getPixel(512, 512)
                assertThat(Color.blue(selected)).isAtLeast(240)
                assertThat(Color.red(selected)).isAtMost(15)
            } finally {
                result.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun `Android decoder applies source orientation and export omits source metadata`() {
        val source = twoColorAvatar()
        val raw = ByteArrayOutputStream().use { output ->
            source.compress(Bitmap.CompressFormat.JPEG, 100, output)
            output.toByteArray()
        }
        source.recycle()
        // APP1 Exif, little-endian TIFF with orientation 6 (rotate 90 degrees clockwise).
        val exif = "ffe1002245786966000049492a0008000000010012010300010000000600000000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val oriented = raw.copyOfRange(0, 2) + exif + raw.copyOfRange(2, raw.size)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File.createTempFile("avatar-orientation-", ".jpg", context.cacheDir)
        try {
            file.writeBytes(oriented)
            val decoded = decodeCustomerAvatar(context, Uri.fromFile(file))
            try {
                assertThat(decoded.width).isEqualTo(100)
                assertThat(decoded.height).isEqualTo(200)
                assertThat(Color.red(decoded.getPixel(50, 25))).isAtLeast(240)
                assertThat(Color.blue(decoded.getPixel(50, 175))).isAtLeast(240)
                val exported = renderCustomerAvatarJpeg(decoded, CustomerAvatarCrop())
                assertThat(exported.toString(Charsets.ISO_8859_1)).doesNotContain("Exif")
            } finally {
                decoded.recycle()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `large decoded images stay within the editor memory bound`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val original = Bitmap.createBitmap(3000, 100, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("avatar-size-", ".png", context.cacheDir)
        try {
            file.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val decoded = decodeCustomerAvatar(context, Uri.fromFile(file))
            try {
                assertThat(decoded.width).isEqualTo(2048)
                assertThat(decoded.height).isAtMost(2048)
                assertThat(decoded.config).isNotEqualTo(Bitmap.Config.HARDWARE)
            } finally {
                decoded.recycle()
            }
        } finally {
            original.recycle()
            file.delete()
        }
    }
}

/** Synthetic colored image makes crop selection observable without customer photos or network data. */
internal fun twoColorAvatar(): Bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).also { bitmap ->
    Canvas(bitmap).apply {
        drawColor(Color.RED)
        drawRect(100f, 0f, 200f, 100f, Paint().apply { color = Color.BLUE })
    }
}

/**
 * Bridges only URI source creation around Robolectric's Android-only native file descriptors.
 * Pixel decoding, orientation, decoder options and JPEG export still use the native graphics runtime.
 */
@Implements(value = ImageDecoder::class, callNativeMethodsByDefault = true)
class CustomerAvatarImageSourceShadow : ShadowNativeImageDecoder() {
    companion object {
        @Implementation
        @JvmStatic
        fun createSource(resolver: ContentResolver, uri: Uri): ImageDecoder.Source {
            val bytes = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
            return ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        }
    }
}
