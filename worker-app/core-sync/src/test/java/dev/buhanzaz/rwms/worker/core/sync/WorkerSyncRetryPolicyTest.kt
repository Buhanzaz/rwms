package dev.buhanzaz.rwms.worker.core.sync

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

/** Verifies the sync worker's finite retry budget and bounded positive jitter. */
class WorkerSyncRetryPolicyTest {
    @Test
    fun `retry budget permits only three WorkManager retries after the initial run`() {
        assertThat(WorkerSyncRetryPolicy.shouldUseWorkManagerRetry(0)).isTrue()
        assertThat(WorkerSyncRetryPolicy.shouldUseWorkManagerRetry(1)).isTrue()
        assertThat(WorkerSyncRetryPolicy.shouldUseWorkManagerRetry(2)).isTrue()
        assertThat(WorkerSyncRetryPolicy.shouldUseWorkManagerRetry(3)).isFalse()
    }

    @Test
    fun `initial WorkManager backoff is bounded and jittered`() {
        val backoff = WorkerSyncRetryPolicy.jitteredInitialBackoffMillis(Random(7))

        assertThat(backoff).isAtLeast(10_000L)
        assertThat(backoff).isAtMost(15_000L)
    }

    @Test
    fun `persisted conflict completes WorkManager run for explicit user acknowledgement`() {
        assertThat(workerRunDisposition(WorkerSyncOutcome.Conflict("stale"), runAttemptCount = 0))
            .isEqualTo(WorkerRunDisposition.SUCCESS)
    }

    @Test
    fun `only retry outcome consumes bounded WorkManager retry budget`() {
        assertThat(workerRunDisposition(WorkerSyncOutcome.Retry("offline"), runAttemptCount = 2))
            .isEqualTo(WorkerRunDisposition.RETRY)
        assertThat(workerRunDisposition(WorkerSyncOutcome.Retry("offline"), runAttemptCount = 3))
            .isEqualTo(WorkerRunDisposition.FAILURE)
    }
}
