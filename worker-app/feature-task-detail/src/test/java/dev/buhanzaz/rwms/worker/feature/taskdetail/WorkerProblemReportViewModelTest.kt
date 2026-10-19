package dev.buhanzaz.rwms.worker.feature.taskdetail

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.PendingPayloadCipher
import dev.buhanzaz.rwms.worker.core.database.PendingWorkerProblemReport
import dev.buhanzaz.rwms.worker.core.database.WorkerDatabase
import dev.buhanzaz.rwms.worker.core.database.WorkerProblemReportStore
import dev.buhanzaz.rwms.worker.core.database.WorkerSessionEntity
import dev.buhanzaz.rwms.worker.core.media.EncryptedEvidenceFileStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises ordered report edits through the actual Room store and ViewModel lifecycle. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkerProblemReportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private val viewModels = mutableListOf<WorkerProblemReportViewModel>()
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
    fun `rapid comments survive ViewModelStore clear with the latest durable value`() = runTest(dispatcher) {
        val fixture = fixture()
        val owner = ViewModelStore().also { it.put("report", fixture.viewModel) }

        fixture.viewModel.updateComment(USER, "первый текст")
        fixture.viewModel.updateComment(USER, "последний текст")
        owner.clear()

        assertThat(fixture.awaitStoredReport("последний текст").comment).isEqualTo("последний текст")
    }

    @Test
    fun `submit joins queued comment save and trims the immutable payload`() = runTest(dispatcher) {
        val fixture = fixture()

        fixture.viewModel.updateComment(USER, "устаревший")
        fixture.viewModel.updateComment(USER, "  последняя проблема  ")
        fixture.viewModel.submit(USER)

        val operation = fixture.awaitOperation(WorkerProblemReportStore.OUTBOX_PENDING)
        val payload = json.decodeFromString<PendingWorkerProblemReport>(fixture.cipher.decrypt(operation.encryptedPayload))
        assertThat(payload.comment).isEqualTo("последняя проблема")
        assertThat(fixture.syncRequests).containsExactly(USER)
    }

    @Test
    fun `reopen selects pending report then creates a new draft only after reported`() = runTest(dispatcher) {
        val fixture = fixture()
        fixture.viewModel.updateComment(USER, "проблема")
        fixture.viewModel.submit(USER)
        val pending = fixture.awaitOperation(WorkerProblemReportStore.OUTBOX_PENDING)

        fixture.viewModel.release()
        fixture.viewModel.bind(USER, ENTRY, ROUTE_INDEX)
        assertThat(fixture.awaitUiReport().reportId).isEqualTo(pending.operationId)

        check(
            database.outboxDao().updateProblemReport(
                operationId = pending.operationId,
                encryptedPayload = pending.encryptedPayload,
                state = WorkerProblemReportStore.OUTBOX_REPORTED,
                retryCount = pending.retryCount,
                lastError = null,
                now = System.currentTimeMillis(),
            ) == 1,
        )
        fixture.viewModel.release()
        fixture.viewModel.bind(USER, ENTRY, ROUTE_INDEX)

        val reopened = fixture.awaitUiReport { it.reportId != pending.operationId }
        assertThat(reopened.reportId).isNotEqualTo(pending.operationId)
        assertThat(reopened.state).isEqualTo(WorkerProblemReportStore.OUTBOX_DRAFT)
    }

    private suspend fun fixture(): Fixture {
        database.sessionDao().upsert(
            WorkerSessionEntity(
                userId = USER,
                displayName = "Worker",
                login = "worker",
                warehouseId = "warehouse",
                leaseId = UUID.randomUUID().toString(),
                leaseExpiresAtEpochMillis = Long.MAX_VALUE,
                serverEpochMillis = System.currentTimeMillis(),
                elapsedRealtimeAtSyncMillis = SystemClock.elapsedRealtime(),
                revision = 1,
                feedEtag = null,
                cacheHidden = false,
                updatedAtEpochMillis = 1,
            ),
        )
        val cipher = testPendingPayloadCipher()
        val reports = WorkerProblemReportStore(database, cipher, json)
        val syncRequests = mutableListOf<String>()
        val viewModel = WorkerProblemReportViewModel(
            problemReports = reports,
            evidenceFileStore = EncryptedEvidenceFileStore(RuntimeEnvironment.getApplication()),
            json = json,
            requestSync = syncRequests::add,
        )
        viewModels += viewModel
        viewModel.bind(USER, ENTRY, ROUTE_INDEX)
        val fixture = Fixture(viewModel, reports, cipher, syncRequests)
        fixture.awaitUiReport()
        return fixture
    }

    private suspend fun Fixture.awaitUiReport(
        condition: (dev.buhanzaz.rwms.worker.core.database.WorkerProblemReportDraftSnapshot) -> Boolean = { true },
    ) = withContext(Dispatchers.IO) {
        withTimeout(5_000) {
            viewModel.uiState.first { state -> state.report?.let(condition) == true }.report!!
        }
    }

    private suspend fun Fixture.awaitOperation(state: String) = withContext(Dispatchers.IO) {
        withTimeout(5_000) {
            database.outboxDao().observeEntryProblemReports(USER, ENTRY)
                .first { operations -> operations.any { it.state == state } }
                .first { it.state == state }
        }
    }

    private suspend fun Fixture.awaitStoredReport(comment: String) = withContext(Dispatchers.IO) {
        withTimeout(5_000) {
            reports.observeEntryReports(USER, ENTRY)
                .first { snapshots -> snapshots.singleOrNull()?.comment == comment }
                .single()
        }
    }

    private fun testPendingPayloadCipher(): PendingPayloadCipher {
        val constructor = PendingPayloadCipher::class.java.getDeclaredConstructor(SecretKey::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(SecretKeySpec(ByteArray(32) { 0x2a }, "AES"))
    }

    private data class Fixture(
        val viewModel: WorkerProblemReportViewModel,
        val reports: WorkerProblemReportStore,
        val cipher: PendingPayloadCipher,
        val syncRequests: MutableList<String>,
    )

    private companion object {
        const val USER = "worker"
        const val ENTRY = "entry"
        const val ROUTE_INDEX = 3
    }
}
