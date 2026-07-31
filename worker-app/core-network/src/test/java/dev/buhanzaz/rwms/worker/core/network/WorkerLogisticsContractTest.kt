package dev.buhanzaz.rwms.worker.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class WorkerLogisticsContractTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `worker category decodes its stable logistics purpose`() {
        val category = json.decodeFromString<WorkerCategoryDto>(
            """
            {
              "queueId":"queue-drivers",
              "name":"Водители",
              "type":"MOVEMENT",
              "queuePurpose":"LOGISTICS_DRIVER",
              "sortOrder":10,
              "audienceModes":["AVAILABLE","OPTIONAL_JOIN"],
              "resultPhotoMinCount":1
            }
            """.trimIndent(),
        )

        assertThat(category.queuePurpose).isEqualTo("LOGISTICS_DRIVER")
        assertThat(category.audienceModes).contains("OPTIONAL_JOIN")
    }

    @Test
    fun `complete action sends the selected ready evidence`() {
        val request = WorkerActionRequestDto(
            operationId = "operation",
            action = "COMPLETE",
            expectedVersion = 8,
            workerGroupId = null,
            evidenceId = "evidence-ready",
            occurredAt = "2026-07-30T10:00:00Z",
            offlineLeaseId = "lease",
        )

        val payload = json.encodeToString(request)

        assertThat(payload).contains("\"action\":\"COMPLETE\"")
        assertThat(payload).contains("\"evidenceId\":\"evidence-ready\"")
        assertThat(payload).doesNotContain("workerGroupId")
    }

    @Test
    fun `audience selector decodes notification policy`() {
        val selector = json.decodeFromString<AudienceSelectorDto>(
            """
            {
              "kind":"WORKER_CLASS",
              "id":"class-slingers",
              "mode":"OPTIONAL_JOIN",
              "interruptOnTake":false,
              "notifyOnPrimaryTake":true
            }
            """.trimIndent(),
        )

        assertThat(selector.mode).isEqualTo("OPTIONAL_JOIN")
        assertThat(selector.notifyOnPrimaryTake).isTrue()
    }
}
