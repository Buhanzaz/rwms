package dev.buhanzaz.rwms.worker.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class WorkerLogisticsContractTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `worker category decodes its stable logistics purpose`() {
        val category = json.decodeFromString<WorkerCategoryDto>(
            """
            {
              "queueId":"queue-drivers",
              "name":"Водители",
              "type":"MOVEMENT",
              "queuePurpose":"LOGISTICS_DRIVER",
              "groupIds":[],
              "sortOrder":10,
              "audienceModes":["AVAILABLE","OPTIONAL_JOIN"],
              "resultPhotoMinCount":1
            }
            """.trimIndent(),
        )

        assertThat(category.queuePurpose).isEqualTo("LOGISTICS_DRIVER")
        assertThat(category.groupIds).isEmpty()
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
        assertThat(payload).contains("\"workerGroupId\":null")
    }

    @Test
    fun `worker action keeps both required nullable keys and round trips exact values`() {
        val nullableRequest = WorkerActionRequestDto(
            operationId = "11111111-1111-1111-1111-111111111111",
            action = "PAUSE",
            expectedVersion = 8,
            workerGroupId = null,
            evidenceId = null,
            occurredAt = "2026-08-09T10:00:00Z",
            offlineLeaseId = "22222222-2222-2222-2222-222222222222",
        )
        val nullablePayload = json.encodeToString(nullableRequest)
        val nullableObject = json.parseToJsonElement(nullablePayload).jsonObject

        assertThat(nullableObject.keys).containsExactly(
            "operationId",
            "action",
            "expectedVersion",
            "workerGroupId",
            "evidenceId",
            "occurredAt",
            "offlineLeaseId",
        )
        assertThat(nullablePayload).contains("\"workerGroupId\":null")
        assertThat(nullablePayload).contains("\"evidenceId\":null")
        assertThat(json.decodeFromString<WorkerActionRequestDto>(nullablePayload))
            .isEqualTo(nullableRequest)

        val selectedRequest = nullableRequest.copy(
            action = "COMPLETE",
            workerGroupId = "33333333-3333-3333-3333-333333333333",
            evidenceId = "44444444-4444-4444-4444-444444444444",
        )
        assertThat(
            json.decodeFromString<WorkerActionRequestDto>(json.encodeToString(selectedRequest)),
        ).isEqualTo(selectedRequest)
    }

    @Test
    fun `worker action rejects missing and additional contract properties`() {
        val canonical =
            """
            {
              "operationId":"11111111-1111-1111-1111-111111111111",
              "action":"PAUSE",
              "expectedVersion":8,
              "workerGroupId":null,
              "evidenceId":null,
              "occurredAt":"2026-08-09T10:00:00Z",
              "offlineLeaseId":"22222222-2222-2222-2222-222222222222"
            }
            """.trimIndent()
        val malformedPayloads = listOf(
            canonical.replace("\"workerGroupId\":null,", ""),
            canonical.dropLast(1) + ",\"internalOwner\":\"task-board\"}",
        )

        malformedPayloads.forEach { payload ->
            val failure = runCatching {
                json.decodeFromString<WorkerActionRequestDto>(payload)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SerializationException::class.java)
        }
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
