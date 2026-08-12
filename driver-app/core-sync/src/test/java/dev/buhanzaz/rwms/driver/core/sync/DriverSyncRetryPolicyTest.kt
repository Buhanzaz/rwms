package dev.buhanzaz.rwms.driver.core.sync

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

/** Verifies the sync driver's finite retry budget and bounded positive jitter. */
class DriverSyncRetryPolicyTest {
    @Test
    fun `retry budget permits only three WorkManager retries after the initial run`() {
        assertThat(DriverSyncRetryPolicy.shouldUseWorkManagerRetry(0)).isTrue()
        assertThat(DriverSyncRetryPolicy.shouldUseWorkManagerRetry(1)).isTrue()
        assertThat(DriverSyncRetryPolicy.shouldUseWorkManagerRetry(2)).isTrue()
        assertThat(DriverSyncRetryPolicy.shouldUseWorkManagerRetry(3)).isFalse()
    }

    @Test
    fun `initial WorkManager backoff is bounded and jittered`() {
        val backoff = DriverSyncRetryPolicy.jitteredInitialBackoffMillis(Random(7))

        assertThat(backoff).isAtLeast(10_000L)
        assertThat(backoff).isAtMost(15_000L)
    }
}
