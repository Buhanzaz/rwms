package dev.buhanzaz.rwms.worker.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class WorkerMediaContractTest {
    private val json = Json { explicitNulls = false; encodeDefaults = true }

    @Test
    fun `worker evidence upload is bound to task board work result owner`() {
        val evidenceId = "123e4567-e89b-12d3-a456-426614174000"
        val request = CreateUploadSessionRequestDto(
            ownerId = "223e4567-e89b-12d3-a456-426614174000",
            warehouseId = "323e4567-e89b-12d3-a456-426614174000",
            clientReferenceId = evidenceId,
            fileName = "$evidenceId.webp",
            imageVariants = listOf(
                ImageVariantUploadRequestDto("SMALL", 10, "a".repeat(64), 320, 240),
                ImageVariantUploadRequestDto("MEDIUM", 14, "b".repeat(64), 960, 720),
                ImageVariantUploadRequestDto("LARGE", 18, "c".repeat(64), 1_600, 1_200),
            ),
        )

        assertThat(request.ownerType).isEqualTo("TASK_BOARD_ENTRY")
        assertThat(request.context).isEqualTo("WORK_RESULT")
        assertThat(request.clientReferenceId).isEqualTo(evidenceId)
        assertThat(requireNotNull(request.imageVariants).map { it.kind })
            .containsExactly("SMALL", "MEDIUM", "LARGE").inOrder()
    }

    @Test
    fun `pre-upgrade encrypted JPEG remains representable for bounded recovery`() {
        val request = CreateUploadSessionRequestDto(
            ownerId = "223e4567-e89b-12d3-a456-426614174000",
            warehouseId = "323e4567-e89b-12d3-a456-426614174000",
            clientReferenceId = "123e4567-e89b-12d3-a456-426614174000",
            fileName = "evidence.jpg",
            contentType = "image/jpeg",
            contentLength = 128,
            checksumSha256 = "a".repeat(64),
        )

        assertThat(request.imageVariants).isNull()
        assertThat(request.contentType).isEqualTo("image/jpeg")
        assertThat(request.contentLength).isEqualTo(128)
        assertThat(json.parseToJsonElement(json.encodeToString(request)).jsonObject.keys)
            .doesNotContain("imageVariants")

        val finalize = FinalizeUploadRequestDto(
            objectVersionId = "opaque-version",
            etag = "opaque-etag",
            checksumSha256 = "a".repeat(64),
        )
        assertThat(json.parseToJsonElement(json.encodeToString(finalize)).jsonObject.keys)
            .doesNotContain("variants")
    }

    @Test
    fun `profile avatar upload has a server-owned scope and no task evidence reference`() {
        val folderId = "423e4567-e89b-12d3-a456-426614174000"
        val request = CreateUploadSessionRequestDto(
            ownerType = "TASK_BOARD_WORKER_PROFILE",
            ownerId = "223e4567-e89b-12d3-a456-426614174000",
            warehouseId = "323e4567-e89b-12d3-a456-426614174000",
            context = "PROFILE_AVATAR",
            folderId = folderId,
            fileName = "avatar.jpg",
            contentType = "image/jpeg",
            contentLength = 128,
            checksumSha256 = "a".repeat(64),
        )

        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        assertThat(request.clientReferenceId).isNull()
        assertThat(encoded.keys).doesNotContain("clientReferenceId")
        assertThat(encoded["folderId"].toString()).isEqualTo("\"$folderId\"")
    }

    @Test
    fun `task source photo keeps gateway read paths for full screen viewer`() {
        val source = WorkerMediaReferenceDto(
            mediaId = "123e4567-e89b-12d3-a456-426614174000",
            generation = 4,
            kind = "SOURCE",
            contentType = "image/jpeg",
            readPath =
                "/api/media/v1/assets/123e4567-e89b-12d3-a456-426614174000/original" +
                    "?ownerType=TASK_BOARD_ENTRY&generation=4",
            thumbnailPath =
                "/api/media/v1/assets/123e4567-e89b-12d3-a456-426614174000/variants/SMALL/content" +
                    "?ownerType=TASK_BOARD_ENTRY&generation=4",
            capturedAt = "2026-07-26T10:00:00Z",
            recordedAt = "2026-07-26T10:00:01Z",
        )

        assertThat(source.kind).isEqualTo("SOURCE")
        assertThat(source.readPath).contains("/api/media/v1/assets/")
        assertThat(source.thumbnailPath).contains("/variants/SMALL/content")
    }
}
