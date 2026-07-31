package dev.buhanzaz.rwms.worker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkerPushInvalidationTest {
    @Test
    fun `accepts only the minimal data-only invalidation contract`() {
        assertThat(
            WorkerPushInvalidation.from(
                mapOf(
                    "eventId" to "event-42",
                    "revision" to "42",
                    "type" to "TASK_JOIN_AVAILABLE",
                    "entryId" to "entry-8",
                ),
            ),
        ).isEqualTo(WorkerPushInvalidation("event-42", 42, "TASK_JOIN_AVAILABLE", "entry-8"))
    }

    @Test
    fun `rejects incomplete or malformed push data`() {
        assertThat(WorkerPushInvalidation.from(emptyMap())).isNull()
        assertThat(
            WorkerPushInvalidation.from(
                mapOf("eventId" to "e", "revision" to "-1", "type" to "UPDATE"),
            ),
        ).isNull()
    }
}
