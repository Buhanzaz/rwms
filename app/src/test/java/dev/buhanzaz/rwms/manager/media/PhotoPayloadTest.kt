package dev.buhanzaz.rwms.manager.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PhotoPayloadTest {
    @Test
    fun `exact jpeg keeps every source byte including metadata segment`() {
        val source = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xe1.toByte(),
            0x00, 0x0a,
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(),
            0x00, 0x00,
            0x01, 0x02, 0x03,
            0xff.toByte(), 0xd9.toByte(),
        )

        val payload = PhotoPayload.exactJpeg("camera.jpg", source)

        assertThat(payload.bytes).isSameInstanceAs(source)
        assertThat(payload.bytes.toList()).containsExactlyElementsIn(source.toList()).inOrder()
        assertThat(payload.contentType).isEqualTo("image/jpeg")
        assertThat(payload.checksumSha256).hasLength(64)
    }

    @Test
    fun `image type is derived from exact bytes instead of an unreliable provider label`() {
        assertThat(
            detectImageContentType(
                byteArrayOf(
                    0x89.toByte(), 0x50, 0x4e, 0x47,
                    0x0d, 0x0a, 0x1a, 0x0a,
                ),
            ),
        ).isEqualTo("image/png")
        assertThat(
            detectImageContentType(
                "RIFF0000WEBP".encodeToByteArray(),
            ),
        ).isEqualTo("image/webp")
        assertThat(detectImageContentType("not-an-image".encodeToByteArray())).isNull()
    }

    @Test
    fun `media reader policy accepts only contract video signatures`() {
        assertThat(
            detectMediaContentType(
                byteArrayOf(
                    0x00, 0x00, 0x00, 0x18,
                    'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                    'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                ),
            ),
        ).isEqualTo("video/mp4")
        assertThat(
            detectMediaContentType(
                byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()),
            ),
        ).isEqualTo("video/webm")
        assertThat(detectMediaContentType("not-media".encodeToByteArray())).isNull()
    }
}
