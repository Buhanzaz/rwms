package dev.buhanzaz.rwms.manager.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Base64
import kotlin.random.Random
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric coverage for orientation, size, ordering, and process-death reuse of WebP parts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageUploadBundleEncoderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `exif orientation is applied before all three webp variants are encoded`() {
        val source = temporaryFolder.newFile("sideways.jpg")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            source.outputStream().use { output ->
                assertThat(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)).isTrue()
            }
        } finally {
            bitmap.recycle()
        }
        ExifInterface(source).run {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            saveAttributes()
        }

        val bundle = ImageUploadBundleEncoder().encode(source)

        assertThat(bundle.variants.map(ImageUploadVariant::kind))
            .containsExactlyElementsIn(ImageUploadVariantKind.entries)
            .inOrder()
        bundle.variants.forEach { variant ->
            assertThat(variant.width).isEqualTo(40)
            assertThat(variant.height).isEqualTo(80)
            assertThat(variant.file.extension).isEqualTo("webp")
            assertThat(variant.file.length()).isGreaterThan(0L)
        }
    }

    @Test
    fun `quality and resolution fallback keep noisy aggregate within one mebibyte`() {
        val source = temporaryFolder.newFile("noise.jpg")
        writeNoiseJpeg(source, width = 1_800, height = 1_200)

        val bundle = ImageUploadBundleEncoder().encode(source)

        assertThat(bundle.variants).hasSize(3)
        assertThat(bundle.variants.sumOf(ImageUploadVariant::contentLength))
            .isAtMost(MAX_IMAGE_BUNDLE_BYTES)
        bundle.variants.forEach { variant ->
            assertThat(variant.contentLength).isAtMost(variant.kind.maximumBytes)
            assertThat(maxOf(variant.width, variant.height))
                .isAtMost(variant.kind.maximumEdge)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(variant.file.path, bounds)
            assertThat(bounds.outWidth).isEqualTo(variant.width)
            assertThat(bounds.outHeight).isEqualTo(variant.height)
        }
    }

    @Test
    fun `complete variant files are reused unchanged after process death`() {
        val source = temporaryFolder.newFile("resume.jpg")
        writeNoiseJpeg(source, width = 640, height = 480)
        val webp = Base64.getDecoder().decode(
            "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA",
        )
        imageVariantDirectoryFor(source).apply { mkdirs() }.let { directory ->
            ImageUploadVariantKind.entries.forEach { kind ->
                directory.resolve("${kind.name}.webp").writeBytes(webp)
            }
        }
        val first = ImageUploadBundleEncoder().encode(source)
        val initial = first.variants.associate { variant ->
            variant.kind to Triple(
                variant.file.canonicalPath,
                variant.checksumSha256,
                variant.file.lastModified(),
            )
        }

        val restored = ImageUploadBundleEncoder().encode(source)

        restored.variants.forEach { variant ->
            val expected = checkNotNull(initial[variant.kind])
            assertThat(variant.file.canonicalPath).isEqualTo(expected.first)
            assertThat(variant.checksumSha256).isEqualTo(expected.second)
            assertThat(variant.file.lastModified()).isEqualTo(expected.third)
        }
    }

    private fun writeNoiseJpeg(file: File, width: Int, height: Int) {
        val random = Random(17)
        val pixels = IntArray(width * height) {
            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            file.outputStream().buffered().use { output ->
                assertThat(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)).isTrue()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
