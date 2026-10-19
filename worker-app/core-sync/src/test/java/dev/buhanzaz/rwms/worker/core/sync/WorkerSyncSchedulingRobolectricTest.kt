package dev.buhanzaz.rwms.worker.core.sync

import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Verifies durable-write scheduling against WorkManager's actual unique-work queue. */
@RunWith(RobolectricTestRunner::class)
class WorkerSyncSchedulingRobolectricTest {
    @Test
    fun `new durable work gets a followup while repeated refreshes stay coalesced`() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val scheduler = WorkerSyncScheduler(context)
        val workManager = WorkManager.getInstance(context)
        val userId = "worker-with-pending-command"
        val name = WorkerSyncScheduler.uniqueName(userId)

        scheduler.request(userId)
        scheduler.request(userId)
        assertThat(workManager.getWorkInfosForUniqueWork(name).get()).hasSize(1)

        scheduler.requestAfterMutation(userId)
        val queued = workManager.getWorkInfosForUniqueWork(name).get()
        assertThat(queued.map { it.state })
            .containsExactly(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)

        scheduler.request(userId)
        assertThat(workManager.getWorkInfosForUniqueWork(name).get()).hasSize(2)
        workManager.cancelAllWork().result.get()
    }
}
