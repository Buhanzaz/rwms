package dev.buhanzaz.rwms.driver.core.database

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class PendingDriverActionCompatibilityTest {
    @Test
    fun `queued action written before evidence selection remains decodable`() {
        val pending = Json.decodeFromString<PendingDriverAction>(
            """
            {
              "operationId":"operation",
              "action":"TAKE",
              "expectedVersion":1,
              "driverGroupId":null,
              "occurredAt":"2026-07-30T10:00:00Z",
              "offlineLeaseId":"lease"
            }
            """.trimIndent(),
        )

        assertThat(pending.evidenceId).isNull()
    }

    @Test
    fun `selected completion evidence survives encrypted outbox serialization`() {
        val pending = PendingDriverAction(
            operationId = "operation",
            action = "COMPLETE",
            expectedVersion = 4,
            driverGroupId = null,
            evidenceId = "evidence-ready",
            occurredAt = "2026-07-30T10:00:00Z",
            offlineLeaseId = "lease",
        )

        val restored = Json.decodeFromString<PendingDriverAction>(Json.encodeToString(pending))

        assertThat(restored.evidenceId).isEqualTo("evidence-ready")
    }
}
