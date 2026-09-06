package dev.buhanzaz.rwms.worker.feature.taskdetail

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.PendingPayloadCipher
import dev.buhanzaz.rwms.worker.core.database.PendingWorkerAction
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerAssignmentEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerCategoryEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerDatabase
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.database.WorkerSessionEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayApi
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import dev.buhanzaz.rwms.worker.core.sync.WorkerProjectionWriter
import java.io.IOException
import java.lang.reflect.Proxy
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises command admission from the actual task-detail ViewModel into the encrypted local outbox. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TaskDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private val viewModels = mutableListOf<TaskDetailViewModel>()
    private lateinit var database: WorkerDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WorkerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `expired lease rejects action without outbox or scheduler effect`() = runTest(dispatcher) {
        val fixture = fixture(leaseExpiresAt = 0)

        fixture.viewModel.perform("TAKE")
        val queueError = workerTaskActionQueueErrorMessage(IllegalStateException())
        fixture.awaitError(queueError)

        assertThat(fixture.store.pendingOutbox(USER)).isEmpty()
        assertThat(fixture.syncRequests).isEmpty()
        assertThat(fixture.viewModel.uiState.value.error).isEqualTo(queueError)
    }

    @Test
    fun `allowed action writes the exact worker entry version lease and schedules once`() = runTest(dispatcher) {
        val fixture = fixture()

        fixture.viewModel.perform("TAKE")
        val action = fixture.awaitAction()

        assertThat(action.action).isEqualTo("TAKE")
        assertThat(action.expectedVersion).isEqualTo(VERSION)
        assertThat(action.workerGroupId).isEqualTo(GROUP)
        assertThat(action.offlineLeaseId).isEqualTo(LEASE)
        assertThat(fixture.syncRequests).containsExactly(USER)
    }

    @Test
    fun `logistics completion requires ready evidence through ordinary action`() = runTest(dispatcher) {
        val fixture = fixture(queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE, status = "IN_PROGRESS", assigned = true)

        fixture.viewModel.perform("COMPLETE")
        advanceUntilIdle()

        assertThat(fixture.store.pendingOutbox(USER)).isEmpty()
        assertThat(fixture.syncRequests).isEmpty()
        assertThat(fixture.viewModel.uiState.value.error).contains("фотографию")
    }

    @Test
    fun `completion after persisted evidence carries the pending evidence identity`() = runTest(dispatcher) {
        val fixture = fixture(queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE, status = "IN_PROGRESS", assigned = true)
        database.evidenceDao().upsert(evidence("captured-evidence"))
        fixture.awaitEvidence("captured-evidence", expectedState = "CAPTURED")

        fixture.viewModel.completeAfterEvidence("captured-evidence")
        val action = fixture.awaitAction()

        assertThat(action.action).isEqualTo("COMPLETE")
        assertThat(action.evidenceId).isEqualTo("captured-evidence")
        assertThat(fixture.syncRequests).containsExactly(USER)
    }

    private suspend fun TestScope.fixture(
        leaseExpiresAt: Long = Long.MAX_VALUE,
        queuePurpose: String = "GENERAL",
        status: String = "WAITING",
        assigned: Boolean = false,
    ): Fixture {
        val store = WorkerLocalStore(database, testPendingPayloadCipher(), json)
        database.sessionDao().upsert(
            WorkerSessionEntity(
                userId = USER, displayName = "Worker", login = "worker", warehouseId = "warehouse",
                leaseId = LEASE, leaseExpiresAtEpochMillis = leaseExpiresAt,
                serverEpochMillis = System.currentTimeMillis(), elapsedRealtimeAtSyncMillis = SystemClock.elapsedRealtime(),
                revision = 1, feedEtag = null, cacheHidden = false, updatedAtEpochMillis = 1,
                currentGroupId = GROUP, currentGroupName = "Group",
            ),
        )
        database.categoryDao().upsertAll(
            listOf(WorkerCategoryEntity("$USER:queue", USER, "queue", "Queue", "GENERAL", queuePurpose, "", 1, "", 0, 1)),
        )
        database.taskDao().upsertAll(
            listOf(
                WorkerTaskEntity(
                    "$USER:$ENTRY", USER, ENTRY, "task", VERSION, "queue", "Queue", 1, "Task", null, null,
                    "2026-09-06", null, 1, 1, status, "AVAILABLE", null, null, 0, 0, 0, 1, false, 1,
                ),
            ),
        )
        if (assigned) {
            database.assignmentDao().upsertAll(
                listOf(WorkerAssignmentEntity("$USER:$ENTRY", USER, ENTRY, "assignment", USER, "Worker", GROUP, "Group", "ACTIVE", "2026-09-06T10:00:00Z", null, null, null)),
            )
        }
        val syncRequests = mutableListOf<String>()
        val gateway = WorkerGatewayClient(failingGateway(), json)
        val viewModel = TaskDetailViewModel(
            store,
            gateway,
            WorkerProjectionWriter(database, json),
            json,
            syncRequests::add,
        )
        viewModels += viewModel
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        viewModel.bind(USER, ENTRY)
        val fixture = Fixture(store, viewModel, syncRequests, queuePurpose)
        fixture.awaitLoadedState()
        fixture.awaitError(INITIAL_REFRESH_ERROR)
        return fixture
    }

    /** Waits for the asynchronous Room projections instead of asserting the initial StateFlow. */
    private suspend fun Fixture.awaitLoadedState() {
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                viewModel.uiState.first { state ->
                    state.task?.entryId == ENTRY &&
                        state.session?.userId == USER &&
                        state.queuePurpose == queuePurpose
                }
            }
        }
    }

    private suspend fun Fixture.awaitError(expected: String) {
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                viewModel.uiState.first { it.error == expected }
            }
        }
    }

    private suspend fun Fixture.awaitEvidence(evidenceId: String, expectedState: String) {
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                viewModel.uiState.first { state ->
                    state.evidence.any { it.evidenceId == evidenceId && it.state == expectedState }
                }
            }
        }
    }

    private suspend fun Fixture.awaitAction(): PendingWorkerAction = withContext(Dispatchers.IO) {
        withTimeout(5_000) {
            val operation = store.observePendingOutbox(USER).first { it.isNotEmpty() }.single()
            json.decodeFromString(store.decryptOutboxPayload(operation))
        }
    }

    private fun testPendingPayloadCipher(): PendingPayloadCipher {
        val constructor = PendingPayloadCipher::class.java.getDeclaredConstructor(SecretKey::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(SecretKeySpec(ByteArray(32) { 0x2a }, "AES"))
    }

    private fun failingGateway(): WorkerGatewayApi = Proxy.newProxyInstance(
        WorkerGatewayApi::class.java.classLoader, arrayOf(WorkerGatewayApi::class.java),
    ) { _, _, _ -> throw IOException("offline") } as WorkerGatewayApi

    private fun evidence(id: String) = TaskEvidenceEntity(
        evidenceId = id, userId = USER, entryId = ENTRY, routeIndex = 0, capturedAt = "2026-09-06T10:00:00Z",
        encryptedFilePath = "file", fileName = "file.jpg", contentType = "image/jpeg", sizeBytes = 1, sha256 = "hash",
        reservationOperationId = id, uploadOperationId = id, state = "CAPTURED", mediaId = null, mediaGeneration = null,
        reviewReason = null, uploadPercent = 0, lastError = null, createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
    )

    private data class Fixture(
        val store: WorkerLocalStore,
        val viewModel: TaskDetailViewModel,
        val syncRequests: MutableList<String>,
        val queuePurpose: String,
    )

    private companion object {
        const val USER = "worker"
        const val ENTRY = "entry"
        const val VERSION = 9L
        const val GROUP = "group"
        const val LEASE = "lease"
        const val INITIAL_REFRESH_ERROR = "Не удалось обновить карточку. Повторим при синхронизации."
    }
}
