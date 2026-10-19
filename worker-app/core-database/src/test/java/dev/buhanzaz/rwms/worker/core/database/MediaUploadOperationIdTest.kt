package dev.buhanzaz.rwms.worker.core.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaUploadOperationIdTest {
    @Test
    fun `media upload idempotency key is the stable evidence client reference`() {
        val evidenceId = "123e4567-e89b-12d3-a456-426614174000"

        assertThat(stableMediaUploadOperationId(evidenceId)).isEqualTo(evidenceId)
    }
}
