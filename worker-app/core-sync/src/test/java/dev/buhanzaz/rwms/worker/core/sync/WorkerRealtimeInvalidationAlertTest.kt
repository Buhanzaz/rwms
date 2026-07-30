package dev.buhanzaz.rwms.worker.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkerRealtimeInvalidationAlertTest {
    private val invalidation = WorkerRealtimeInvalidation(
        eventId = "event-42",
        revision = 42,
        type = "MANDATORY_TASK",
        entryId = "entry-8",
    )

    @Test
    fun `new invalidation schedules sync before alerting`() {
        val calls = mutableListOf<String>()

        dispatchInsertedRealtimeInvalidation(
            insertedRowId = 1L,
            invalidation = invalidation,
            scheduleSync = { calls += "sync" },
            alert = WorkerRealtimeInvalidationAlert { calls += "alert:${it.eventId}" },
        )

        assertThat(calls).containsExactly("sync", "alert:event-42").inOrder()
    }

    @Test
    fun `duplicate invalidation neither schedules nor alerts`() {
        val calls = mutableListOf<String>()

        dispatchInsertedRealtimeInvalidation(
            insertedRowId = -1L,
            invalidation = invalidation,
            scheduleSync = { calls += "sync" },
            alert = WorkerRealtimeInvalidationAlert { calls += "alert" },
        )

        assertThat(calls).isEmpty()
    }
}
