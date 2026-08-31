package dev.buhanzaz.rwms.client.data

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/** Locks the server-derived, non-binding delivery estimate into the CustomerApp catalogue model. */
class CustomerDeliveryEstimateContractTest {
    @Test
    fun `cabin page decodes capacity aware delivery dates`() {
        val page = Json { ignoreUnknownKeys = true }.decodeFromString<CabinPage>(
            """{
              "content":[],"page":0,"size":20,"totalElements":0,"totalPages":0,
              "estimatedDeliveryDates":["2026-09-03","2026-09-05"]
            }""",
        )

        assertThat(page.estimatedDeliveryDates).containsExactly("2026-09-03", "2026-09-05").inOrder()
    }
}
