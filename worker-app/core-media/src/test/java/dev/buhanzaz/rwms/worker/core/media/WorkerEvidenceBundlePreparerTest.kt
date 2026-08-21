package dev.buhanzaz.rwms.worker.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins the byte budget, physical orientation and manifest identity of WorkerApp evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkerEvidenceBundlePreparerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `upright webp bundle contains three ordered variants below one mebibyte`() {
        val source = temporaryFolder.newFile("sideways.jpg")
        val bitmap = Bitmap.createBitmap(1_200, 800, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until bitmap.height step 16) {
                for (x in 0 until bitmap.width step 16) {
                    bitmap.setPixel(x, y, 0xFF000000.toInt() or (x shl 8) xor y)
                }
            }
            source.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
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

        val bundle = encodeWorkerEvidenceBundle(
            source = source,
            outputDirectory = temporaryFolder.newFolder("output"),
            evidenceId = "123e4567-e89b-12d3-a456-426614174000",
        )
        try {
            assertThat(bundle.variants.map { it.kind })
                .containsExactly("SMALL", "MEDIUM", "LARGE").inOrder()
            assertThat(bundle.aggregateContentLength)
                .isEqualTo(bundle.variants.sumOf { it.contentLength })
            assertThat(bundle.aggregateContentLength)
                .isAtMost(EncryptedEvidenceFileStore.MAX_UPLOAD_BUNDLE_BYTES)
            assertThat(bundle.variants.all { it.file.extension == "webp" }).isTrue()
            val originalBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(bundle.original.path, originalBounds)
            assertThat(originalBounds.outWidth).isEqualTo(800)
            assertThat(originalBounds.outHeight).isEqualTo(1_200)
            bundle.variants.forEach { variant ->
                assertThat(variant.width).isGreaterThan(0)
                assertThat(variant.height).isGreaterThan(0)
                assertThat(variant.checksumSha256).hasLength(64)
            }
        } finally {
            bundle.original.delete()
            bundle.variants.forEach { it.file.delete() }
        }
    }

    @Test
    fun `manifest is sorted and byte stable`() {
        val parts = listOf(
            part("LARGE", 30, "c", 1_600, 1_200),
            part("SMALL", 10, "a", 320, 240),
            part("MEDIUM", 20, "b", 960, 720),
        )

        assertThat(workerEvidenceManifestPayload(parts)).isEqualTo(
            "rwms-image-variants-v1\n" +
                "SMALL:10:${"a".repeat(64)}:320x240\n" +
                "MEDIUM:20:${"b".repeat(64)}:960x720\n" +
                "LARGE:30:${"c".repeat(64)}:1600x1200\n",
        )
        assertThat(workerEvidenceManifestSha256(parts))
            .isEqualTo("39cc5a3535d14189ab5b915721cae2c982cadf417ae34bb4b6b1fb3383b0b419")
    }

    private fun part(
        kind: String,
        length: Long,
        checksumCharacter: String,
        width: Int,
        height: Int,
    ) = PlainEvidenceVariantPart(
        kind = kind,
        file = File(temporaryFolder.root, "$kind.webp"),
        contentLength = length,
        checksumSha256 = checksumCharacter.repeat(64),
        width = width,
        height = height,
    )
}

/** Pins stable per-part retry keys without depending on Retrofit implementation details. */
@RunWith(JUnit4::class)
class VariantUploadOperationIdTest {
    @Test
    fun `each webp part has a stable distinct idempotency key`() {
        val operationId = "123e4567-e89b-12d3-a456-426614174000"
        val smallKey = stableVariantUploadOperationId(operationId, "SMALL")

        assertThat(UUID.fromString(smallKey).toString()).isEqualTo(smallKey)
        assertThat(stableVariantUploadOperationId(operationId, "SMALL")).isEqualTo(smallKey)
        val partKeys = EncryptedEvidenceFileStore.REQUIRED_VARIANT_KINDS.map { kind ->
            stableVariantUploadOperationId(operationId, kind)
        }.toSet()
        assertThat(partKeys).hasSize(3)
        assertThat(partKeys).doesNotContain(operationId)
    }
}
