package dev.buhanzaz.rwms.worker.core.database

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class PendingWorkerActionCompatibilityTest {
    @Test
    fun `queued action written before evidence selection remains decodable`() {
        val pending = Json.decodeFromString<PendingWorkerAction>(
            """
            {
              "operationId":"operation",
              "action":"TAKE",
              "expectedVersion":1,
              "workerGroupId":null,
              "occurredAt":"2026-07-30T10:00:00Z",
              "offlineLeaseId":"lease"
            }
            """.trimIndent(),
        )

        assertThat(pending.evidenceId).isNull()
    }

    @Test
    fun `selected completion evidence survives encrypted outbox serialization`() {
        val pending = PendingWorkerAction(
            operationId = "operation",
            action = "COMPLETE",
            expectedVersion = 4,
            workerGroupId = null,
            evidenceId = "evidence-ready",
            occurredAt = "2026-07-30T10:00:00Z",
            offlineLeaseId = "lease",
        )

        val restored = Json.decodeFromString<PendingWorkerAction>(Json.encodeToString(pending))

        assertThat(restored.evidenceId).isEqualTo("evidence-ready")
    }
}
