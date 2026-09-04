package dev.buhanzaz.rwms.worker.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Verifies that SSE reconnects request a fresh authoritative worker sync. */
class WorkerRealtimeReconnectContractTest {
    @Test
    fun `reconnect schedules authoritative REST refresh without a cursor`() {
        val users = mutableListOf<String>()

        requestAuthoritativeRefreshOnReconnect("worker-7", users::add)

        assertThat(users).containsExactly("worker-7")
    }
}
