package dev.buhanzaz.rwms.worker.core.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.UUID

class EvidenceFileNameTest {
    @Test
    fun `evidence identifiers are normalized UUIDs before becoming file names`() {
        val id = UUID.randomUUID().toString().uppercase()
        assertThat(EncryptedEvidenceFileStore.safeUuid(id)).isEqualTo(id.lowercase())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `file names reject path traversal values`() {
        EncryptedEvidenceFileStore.safeUuid("../../photo")
    }
}
