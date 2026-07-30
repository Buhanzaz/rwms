package dev.buhanzaz.rwms.worker.core.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServerTimeAnchorTest {
    @Test
    fun `lease uses elapsed realtime not wall clock`() {
        val anchor = ServerTimeAnchor(
            serverEpochMillis = 1_000L,
            elapsedRealtimeAtSyncMillis = 100L,
            leaseExpiresAtEpochMillis = 1_500L,
        )

        assertThat(anchor.estimatedServerNow(400L)).isEqualTo(1_300L)
        assertThat(anchor.isLeaseActive(600L)).isTrue()
        assertThat(anchor.isLeaseActive(601L)).isFalse()
    }

    @Test
    fun `lease fails closed when elapsed realtime reset indicates reboot`() {
        val anchor = ServerTimeAnchor(
            serverEpochMillis = 1_000L,
            elapsedRealtimeAtSyncMillis = 10_000L,
            leaseExpiresAtEpochMillis = 87_400_000L,
        )

        assertThat(anchor.isLeaseActive(200L)).isFalse()
    }
}
