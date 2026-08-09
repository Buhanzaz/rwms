package dev.buhanzaz.rwms.manager.uploads

import android.content.Context
import android.os.Looper
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises the real upload coordinator and WorkManager handoff across two verified accounts that
 * share one warehouse, without starting transport or depending on an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class BackgroundUploadCoordinatorIdentityTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var workerExecutor: ExecutorService
    private lateinit var workerProbe: ControlledWorkerProbe

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        BackgroundUploadStore.resetForTests()
        BackgroundUploadDraftScopeRegistry.replace(null)
        deleteDurableUploadStorage()

        workerProbe = ControlledWorkerProbe()
        workerExecutor = Executors.newFixedThreadPool(2)
        val configuration = Configuration.Builder()
            .setExecutor(workerExecutor)
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(ControlledWorkerFactory(workerProbe))
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            configuration,
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        if (::workManager.isInitialized) {
            runCatching { workManager.cancelAllWork().result.get() }
            runCatching { WorkManagerTestInitHelper.closeWorkDatabase() }
        }
        if (::workerExecutor.isInitialized) {
            workerExecutor.shutdownNow()
            workerExecutor.awaitTermination(5, TimeUnit.SECONDS)
        }
        BackgroundUploadStore.resetForTests()
        BackgroundUploadDraftScopeRegistry.replace(null)
        if (::context.isInitialized) deleteDurableUploadStorage()
    }

    @Test
    fun `account handoff cancels old work before exposing the replacement scope`() =
        runBlocking {
            val coordinator = BackgroundUploadCoordinator(context)
            coordinator.initialize()
            coordinator.activateVerifiedScope(SCOPE_A.ownerAccountId, SCOPE_A.warehouseId)
            val staleAccountADraft = draft()
            val accountAOperationId = coordinator.enqueue(staleAccountADraft)
            val accountAWorkName = BackgroundUploadCoordinator.workName(
                SCOPE_A,
                accountAOperationId,
            )
            val accountAScopeTag = BackgroundUploadCoordinator.workScopeTag(SCOPE_A)
            val accountAWork = onlyWork(accountAWorkName)

            assertThat(accountAWork.state).isEqualTo(WorkInfo.State.ENQUEUED)
            assertThat(accountAWork.tags).containsExactly(
                BackgroundUploadWorker.WORK_TAG,
                accountAScopeTag,
                BackgroundUploadWorker::class.java.name,
            )
            assertThat(accountAWorkName).doesNotContain(SCOPE_A.ownerAccountId)
            assertThat(accountAWorkName).doesNotContain(SCOPE_A.warehouseId)
            assertThat(accountAScopeTag).doesNotContain(SCOPE_A.ownerAccountId)
            assertThat(accountAScopeTag).doesNotContain(SCOPE_A.warehouseId)
            assertThat(
                workManager.getWorkInfosForUniqueWork(
                    BackgroundUploadCoordinator.workName(SCOPE_B, accountAOperationId),
                ).get(),
            ).isEmpty()
            assertThat(workManager.getWorkInfosByTag(accountAScopeTag).get())
                .containsExactly(accountAWork)
            assertThat(coordinator.operations.value.map(BackgroundUploadOperation::id))
                .containsExactly(accountAOperationId)

            requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
                .setAllConstraintsMet(accountAWork.id)
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(workerProbe.started.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(onlyWork(accountAWorkName).state).isEqualTo(WorkInfo.State.RUNNING)

            coordinator.activateVerifiedScope(SCOPE_B.ownerAccountId, SCOPE_B.warehouseId)

            assertThat(workerProbe.stopped.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(onlyWork(accountAWorkName).state).isEqualTo(WorkInfo.State.CANCELLED)
            assertThat(coordinator.operations.value).isEmpty()

            val staleDraftFailure = runCatching {
                coordinator.enqueue(staleAccountADraft)
            }.exceptionOrNull()
            assertThat(staleDraftFailure).isInstanceOf(IllegalArgumentException::class.java)

            coordinator.retry(accountAOperationId)
            coordinator.resumePending()
            assertThat(workManager.getWorkInfosForUniqueWork(accountAWorkName).get())
                .containsExactly(onlyWork(accountAWorkName))
            assertThat(onlyWork(accountAWorkName).state).isEqualTo(WorkInfo.State.CANCELLED)

            val accountBOperationId = coordinator.enqueue(draft())
            val accountBWorkName = BackgroundUploadCoordinator.workName(
                SCOPE_B,
                accountBOperationId,
            )
            val accountBScopeTag = BackgroundUploadCoordinator.workScopeTag(SCOPE_B)
            coordinator.resumePending()

            assertThat(accountBWorkName).doesNotContain(SCOPE_B.ownerAccountId)
            assertThat(accountBWorkName).doesNotContain(SCOPE_B.warehouseId)
            assertThat(accountBScopeTag).isNotEqualTo(accountAScopeTag)
            assertThat(onlyWork(accountBWorkName).state).isEqualTo(WorkInfo.State.ENQUEUED)
            assertThat(workManager.getWorkInfosForUniqueWork(accountBWorkName).get()).hasSize(1)
            assertThat(workManager.getWorkInfosByTag(accountBScopeTag).get())
                .containsExactly(onlyWork(accountBWorkName))
            assertThat(coordinator.operations.value.map(BackgroundUploadOperation::id))
                .containsExactly(accountBOperationId)

            coordinator.pause()
            assertThat(coordinator.operations.value).isEmpty()
            assertThat(onlyWork(accountBWorkName).state).isEqualTo(WorkInfo.State.CANCELLED)

            BackgroundUploadStore.resetForTests()
            val reloadedCoordinator = BackgroundUploadCoordinator(context)
            reloadedCoordinator.initialize()
            reloadedCoordinator.activateVerifiedScope(
                SCOPE_B.ownerAccountId,
                SCOPE_B.warehouseId,
            )

            assertThat(reloadedCoordinator.operations.value.map(BackgroundUploadOperation::id))
                .containsExactly(accountBOperationId)
            assertThat(reloadedCoordinator.operations.value.single().ownerAccountId)
                .isEqualTo(SCOPE_B.ownerAccountId)
            assertThat(reloadedCoordinator.operations.value.single().warehouseId)
                .isEqualTo(SCOPE_B.warehouseId)

            reloadedCoordinator.activateVerifiedScope(
                SCOPE_A.ownerAccountId,
                SCOPE_A.warehouseId,
            )
            assertThat(reloadedCoordinator.operations.value.map(BackgroundUploadOperation::id))
                .containsExactly(accountAOperationId)
            Unit
        }

    private fun onlyWork(uniqueWorkName: String): WorkInfo =
        workManager.getWorkInfosForUniqueWork(uniqueWorkName).get().single()

    private fun draft() = BackgroundUploadDraft(
        area = BackgroundUploadArea.ACCEPTANCE,
        title = "Приёмка",
        acceptance = AcceptanceUploadCommand(
            repairId = "repair-1",
            warehouseId = SHARED_WAREHOUSE_ID,
            expectedVersion = 3,
            idempotencyKey = "command-1",
        ),
    )

    private fun deleteDurableUploadStorage() {
        context.filesDir.resolve("background-uploads").deleteRecursively()
        context.filesDir.resolve("background-uploads-v2").deleteRecursively()
        context.filesDir.resolve("background-uploads-quarantine").deleteRecursively()
    }

    /** Creates controlled workers only for the production background-upload request class. */
    private class ControlledWorkerFactory(
        private val probe: ControlledWorkerProbe,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = if (workerClassName == BackgroundUploadWorker::class.java.name) {
            ControlledBackgroundUploadWorker(appContext, workerParameters, probe)
        } else {
            null
        }
    }

    /**
     * Runs until WorkManager cancellation and records its start/stop lifecycle without performing
     * transport or persistence side effects.
     */
    private class ControlledBackgroundUploadWorker(
        applicationContext: Context,
        workerParameters: WorkerParameters,
        private val probe: ControlledWorkerProbe,
    ) : CoroutineWorker(applicationContext, workerParameters) {
        override suspend fun doWork(): Result {
            probe.started.countDown()
            try {
                awaitCancellation()
            } finally {
                probe.stopped.countDown()
            }
        }
    }

    /** Thread-safe lifecycle evidence shared by the fake worker and the account-handoff test. */
    private class ControlledWorkerProbe {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
    }

    /** Stable account identities sharing one warehouse for the coordinator boundary test. */
    private companion object {
        const val SHARED_WAREHOUSE_ID = "warehouse-1"
        val SCOPE_A = BackgroundUploadScope("account-a", SHARED_WAREHOUSE_ID)
        val SCOPE_B = BackgroundUploadScope("account-b", SHARED_WAREHOUSE_ID)
    }
}
