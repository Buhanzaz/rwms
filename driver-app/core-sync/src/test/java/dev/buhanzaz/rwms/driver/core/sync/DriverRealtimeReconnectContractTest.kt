package dev.buhanzaz.rwms.driver.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Verifies that SSE reconnects request a fresh authoritative driver sync. */
class DriverRealtimeReconnectContractTest {
    @Test
    fun `reconnect schedules authoritative REST refresh without a cursor`() {
        val users = mutableListOf<String>()

        requestAuthoritativeRefreshOnReconnect("driver-7", users::add)

        assertThat(users).containsExactly("driver-7")
    }
}
