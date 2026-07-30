package dev.buhanzaz.rwms.worker.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkerMediaContractTest {
    @Test
    fun `worker evidence upload is bound to task board work result owner`() {
        val evidenceId = "123e4567-e89b-12d3-a456-426614174000"
        val request = CreateUploadSessionRequestDto(
            ownerId = "223e4567-e89b-12d3-a456-426614174000",
            warehouseId = "323e4567-e89b-12d3-a456-426614174000",
            clientReferenceId = evidenceId,
            fileName = "$evidenceId.jpg",
            contentLength = 42,
            checksumSha256 = "a".repeat(64),
        )

        assertThat(request.ownerType).isEqualTo("TASK_BOARD_ENTRY")
        assertThat(request.context).isEqualTo("WORK_RESULT")
        assertThat(request.clientReferenceId).isEqualTo(evidenceId)
        assertThat(request.contentType).isEqualTo("image/jpeg")
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
