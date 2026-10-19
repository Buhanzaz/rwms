package dev.buhanzaz.rwms.driver.feature.taskdetail

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.database.DriverAssignmentEntity
import dev.buhanzaz.rwms.driver.core.database.DriverCategoryEntity
import dev.buhanzaz.rwms.driver.core.database.DriverDatabase
import dev.buhanzaz.rwms.driver.core.database.DriverLocalStore
import dev.buhanzaz.rwms.driver.core.database.DriverSessionEntity
import dev.buhanzaz.rwms.driver.core.database.DriverShiftSnapshotEntity
import dev.buhanzaz.rwms.driver.core.database.DriverTaskEntity
import dev.buhanzaz.rwms.driver.core.database.PendingDriverAction
import dev.buhanzaz.rwms.driver.core.database.PendingEvidenceReservation
import dev.buhanzaz.rwms.driver.core.database.PendingPayloadCodec
import dev.buhanzaz.rwms.driver.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.driver.core.network.DriverGatewayApi
import dev.buhanzaz.rwms.driver.core.network.DriverGatewayClient
import dev.buhanzaz.rwms.driver.core.network.DriverShiftWarehouseDto
import dev.buhanzaz.rwms.driver.core.network.DriverTaskDetailDto
import dev.buhanzaz.rwms.driver.core.network.DriverTaskTripDetailsResponseDto
import dev.buhanzaz.rwms.driver.core.network.DriverTripDetailsDto
import dev.buhanzaz.rwms.driver.core.network.TaskSourceReferenceDto
import dev.buhanzaz.rwms.driver.core.network.TodayDriverShiftDto
import dev.buhanzaz.rwms.driver.core.sync.DriverProjectionWriter
import dev.buhanzaz.rwms.driver.core.sync.DriverWarehouseClock
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises driver task commands from the ViewModel through the durable encrypted outbox. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TaskDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private val viewModels = mutableListOf<TaskDetailViewModel>()
    private lateinit var database: DriverDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DriverDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `expired lease rejects task action without outbox or sync request`() = runTest(dispatcher) {
        val fixture = fixture(leaseExpiresAt = 0)
        fixture.awaitError(WAREHOUSE_DATE_FAILURE)

        fixture.viewModel.perform("TAKE")
        runCurrent()
        fixture.awaitError(ACTION_QUEUE_FAILURE)

        assertThat(fixture.store.pendingOutbox(USER)).isEmpty()
        assertThat(fixture.syncRequests).isEmpty()
        assertThat(fixture.viewModel.uiState.value.error).isEqualTo(ACTION_QUEUE_FAILURE)
    }

    @Test
    fun `allowed action writes exact driver entry version lease and schedules once`() = runTest(dispatcher) {
        val fixture = fixture()
        fixture.awaitError(CARD_REFRESH_FAILURE)

        fixture.viewModel.perform("TAKE")
        val action = fixture.awaitAction()
        runCurrent()
        val outbox = fixture.store.pendingOutbox(USER).single()
        val optimisticTask = database.taskDao().task(USER, ENTRY)

        assertThat(action.action).isEqualTo("TAKE")
        assertThat(action.expectedVersion).isEqualTo(VERSION)
        assertThat(action.driverGroupId).isEqualTo(GROUP)
        assertThat(action.offlineLeaseId).isEqualTo(LEASE)
        assertThat(outbox.operationId).isEqualTo(action.operationId)
        assertThat(outbox.userId).isEqualTo(USER)
        assertThat(outbox.entryId).isEqualTo(ENTRY)
        assertThat(outbox.kind).isEqualTo(DriverLocalStore.OUTBOX_ACTION)
        assertThat(outbox.expectedVersion).isEqualTo(VERSION)
        assertThat(optimisticTask?.status).isEqualTo("IN_PROGRESS")
        assertThat(optimisticTask?.version).isEqualTo(VERSION + 1)
        assertThat(optimisticTask?.locallyPending).isTrue()
        assertThat(fixture.syncRequests).containsExactly(USER)
    }

    @Test
    fun `logistics completion requires persisted ready evidence`() = runTest(dispatcher) {
        val fixture = fixture(queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE, status = "IN_PROGRESS", assigned = true)
        fixture.awaitError(CARD_REFRESH_FAILURE)

        fixture.viewModel.perform("COMPLETE")
        fixture.awaitError(MISSING_EVIDENCE_FAILURE)

        assertThat(fixture.store.pendingOutbox(USER)).isEmpty()
        assertThat(fixture.syncRequests).isEmpty()
        assertThat(fixture.viewModel.uiState.value.error).isEqualTo(MISSING_EVIDENCE_FAILURE)
    }

    @Test
    fun `completion after persisted evidence includes that evidence identity`() = runTest(dispatcher) {
        val fixture = fixture(queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE, status = "IN_PROGRESS", assigned = true)
        fixture.awaitError(CARD_REFRESH_FAILURE)
        database.evidenceDao().upsert(evidence("captured-evidence"))
        fixture.awaitReadyEvidence("captured-evidence")

        fixture.viewModel.perform("COMPLETE")
        val action = fixture.awaitAction()
        runCurrent()

        assertThat(action.action).isEqualTo("COMPLETE")
        assertThat(action.evidenceId).isEqualTo("captured-evidence")
        assertThat(fixture.syncRequests).containsExactly(USER)
    }

    @Test
    fun `assigned driver audience refuses extra task claim without mutation or sync`() = runTest(dispatcher) {
        val calls = GatewayCalls()
        val fixture = fixture(
            driverAudienceMode = ASSIGNED_DRIVER_AUDIENCE_MODE,
            gateway = responseGateway(calls = calls, detail = logisticsDetail()),
        )
        fixture.awaitDetail()
        fixture.awaitTripDetails("preview")

        fixture.viewModel.claimExtraTask()
        fixture.awaitError(EXTRA_TASK_DATE_FAILURE)

        assertThat(calls.detailEntryIds).contains(ENTRY)
        assertThat(calls.logisticsTaskIds).containsExactly(LOGISTICS_SOURCE)
        assertThat(calls.claimTaskIds).isEmpty()
        assertThat(fixture.store.pendingOutbox(USER)).isEmpty()
        assertThat(fixture.syncRequests).isEmpty()
        assertThat(fixture.viewModel.uiState.value.extraTaskClaimed).isFalse()
        assertThat(fixture.viewModel.uiState.value.error).isEqualTo(EXTRA_TASK_DATE_FAILURE)
    }

    @Test
    fun `future shared task claim sends exact source id and exposes response`() = runTest(dispatcher) {
        val calls = GatewayCalls()
        val claimedTrip = tripDetails(taskNumber = "claimed-trip")
        val fixture = fixture(
            driverAudienceMode = WAREHOUSE_DRIVERS_AUDIENCE_MODE,
            gateway = responseGateway(
                calls = calls,
                detail = logisticsDetail(),
                claimedTrip = claimedTrip,
            ),
        )
        fixture.awaitDetail()
        fixture.awaitTripDetails("preview")

        fixture.viewModel.claimExtraTask()
        runCurrent()
        fixture.awaitClaimedState(claimedTrip.taskNumber)

        assertThat(calls.detailEntryIds).contains(ENTRY)
        assertThat(calls.logisticsTaskIds).contains(LOGISTICS_SOURCE)
        assertThat(calls.claimTaskIds).containsExactly(LOGISTICS_SOURCE)
        assertThat(fixture.viewModel.uiState.value.tripDetails).isEqualTo(claimedTrip)
        assertThat(fixture.syncRequests).containsExactly(USER)
        assertThat(fixture.store.pendingOutbox(USER)).isEmpty()
    }

    @Test
    fun `retry evidence schedules only review required durable reservation`() = runTest(dispatcher) {
        val fixture = fixture()
        fixture.awaitError(CARD_REFRESH_FAILURE)
        val payload = PendingEvidenceReservation(
            operationId = RETRY_EVIDENCE,
            evidenceId = RETRY_EVIDENCE,
            routeIndex = 0,
            capturedAt = "2026-09-06T10:00:00Z",
            offlineLeaseId = LEASE,
            contentType = "image/jpeg",
            sizeBytes = 1,
            sha256 = "hash",
        )
        fixture.store.enqueueEvidenceReservation(
            userId = USER,
            entryId = ENTRY,
            evidenceId = RETRY_EVIDENCE,
            encryptedFilePath = "file",
            fileName = "file.jpg",
            routeIndex = payload.routeIndex,
            capturedAt = payload.capturedAt,
            sizeBytes = payload.sizeBytes,
            sha256 = payload.sha256,
            reservationPayload = json.encodeToString(payload),
        )
        fixture.awaitEvidenceState(RETRY_EVIDENCE, "CAPTURED")

        fixture.viewModel.retryEvidence(RETRY_EVIDENCE)
        assertThat(fixture.syncRequests).isEmpty()

        database.evidenceDao().updateState(
            evidenceId = RETRY_EVIDENCE,
            state = "REVIEW_REQUIRED",
            mediaId = null,
            generation = null,
            reviewReason = "retry",
            now = System.currentTimeMillis(),
        )
        fixture.awaitEvidenceState(RETRY_EVIDENCE, "REVIEW_REQUIRED")

        fixture.viewModel.retryEvidence(RETRY_EVIDENCE)
        fixture.awaitNoError()

        assertThat(fixture.syncRequests).containsExactly(USER)
        assertThat(fixture.store.pendingOutbox(USER)).hasSize(1)
        assertThat(fixture.viewModel.uiState.value.error).isNull()
    }

    private suspend fun TestScope.fixture(
        leaseExpiresAt: Long = Long.MAX_VALUE,
        queuePurpose: String = "GENERAL",
        status: String = "WAITING",
        assigned: Boolean = false,
        driverAudienceMode: String? = null,
        gateway: DriverGatewayApi = failingGateway(),
    ): Fixture {
        val store = DriverLocalStore(database, plainTextCodec(), json)
        val now = System.currentTimeMillis()
        database.sessionDao().upsert(
            DriverSessionEntity(
                userId = USER, displayName = "Driver", login = "driver", warehouseId = WAREHOUSE,
                leaseId = LEASE, leaseExpiresAtEpochMillis = leaseExpiresAt, serverEpochMillis = SERVER_EPOCH_MILLIS,
                elapsedRealtimeAtSyncMillis = SystemClock.elapsedRealtime(), revision = 1, feedEtag = null,
                cacheHidden = false, updatedAtEpochMillis = now, currentGroupId = GROUP, currentGroupName = "Group",
            ),
        )
        database.shiftSnapshotDao().upsert(
            DriverShiftSnapshotEntity(
                userId = USER, shiftId = null, workDate = "2026-09-06", enabled = true,
                nextRequiredAction = "NONE", serializedTodayShift = json.encodeToString(
                    TodayDriverShiftDto(
                        enabled = true, serverTime = "2026-09-06T10:00:00Z", nextRequiredAction = "NONE",
                        warehouse = DriverShiftWarehouseDto(WAREHOUSE, "Warehouse", "Moscow", timeZone = "Europe/Moscow"),
                    ),
                ),
                serverTime = "2026-09-06T10:00:00Z", updatedAtEpochMillis = now,
            ),
        )
        database.categoryDao().upsertAll(
            listOf(DriverCategoryEntity("$USER:queue", USER, "queue", "Queue", "GENERAL", queuePurpose, "", 1, "", 0, 1)),
        )
        database.taskDao().upsertAll(
            listOf(
                DriverTaskEntity(
                    "$USER:$ENTRY", USER, ENTRY, "task", VERSION, "queue", "Queue", 1, "Task", null, null,
                    "2026-09-06", null, 1, 1, status, "AVAILABLE", null, null, 0, 0, 0, 1, false, now,
                    driverAudienceMode = driverAudienceMode,
                ),
            ),
        )
        if (assigned) {
            database.assignmentDao().upsertAll(
                listOf(DriverAssignmentEntity("$USER:$ENTRY", USER, ENTRY, "assignment", USER, "Driver", GROUP, "Group", "ACTIVE", "2026-09-06T10:00:00Z", null, null, null)),
            )
        }
        val syncRequests = mutableListOf<String>()
        val viewModel = TaskDetailViewModel(
            store, DriverGatewayClient(gateway, json), DriverProjectionWriter(database, json),
            DriverWarehouseClock(store, json), json, syncRequests::add,
        )
        viewModels += viewModel
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        viewModel.bind(USER, ENTRY)
        runCurrent()
        val fixture = Fixture(store, viewModel, syncRequests)
        fixture.awaitLoadedState(queuePurpose = queuePurpose, assigned = assigned)
        return fixture
    }

    /** Room invalidation is asynchronous, so command tests wait for its real projection emission. */
    private suspend fun Fixture.awaitLoadedState(queuePurpose: String, assigned: Boolean) {
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                viewModel.uiState.first { state ->
                    state.task?.entryId == ENTRY &&
                        state.session?.userId == USER &&
                        state.queuePurpose == queuePurpose &&
                        (!assigned || state.assignments.any { it.driverId == USER && it.status == "ACTIVE" })
                }
            }
        }
    }

    private suspend fun Fixture.awaitAction(): PendingDriverAction {
        val operation = withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                store.observePendingOutbox(USER).first { operations ->
                    operations.any { it.kind == DriverLocalStore.OUTBOX_ACTION }
                }.single { it.kind == DriverLocalStore.OUTBOX_ACTION }
            }
        }
        return json.decodeFromString(store.decryptOutboxPayload(operation))
    }

    private suspend fun Fixture.awaitDetail() {
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                viewModel.uiState.first { state -> state.detail?.entryId == ENTRY }
            }
        }
    }

    private suspend fun Fixture.awaitError(expected: String) {
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                viewModel.uiState.first { state -> state.error == expected }
            }
        }
    }

    private suspend fun Fixture.awaitNoError() {
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                viewModel.uiState.first { state -> state.error == null }
            }
        }
    }

    private suspend fun Fixture.awaitReadyEvidence(evidenceId: String) {
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                viewModel.uiState.first { state ->
                    state.evidence.any { it.evidenceId == evidenceId && it.state == "READY" }
                }
            }
        }
    }

    private suspend fun Fixture.awaitTripDetails(taskNumber: String) {
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                viewModel.uiState.first { state -> state.tripDetails?.taskNumber == taskNumber }
            }
        }
    }

    private suspend fun Fixture.awaitClaimedState(taskNumber: String) {
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                viewModel.uiState.first { state ->
                    state.extraTaskClaimed && state.tripDetails?.taskNumber == taskNumber
                }
            }
        }
    }

    private suspend fun Fixture.awaitEvidenceState(evidenceId: String, state: String) {
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                viewModel.uiState.first { uiState ->
                    uiState.evidence.any { it.evidenceId == evidenceId && it.state == state } &&
                        evidenceId in uiState.retryableEvidenceIds
                }
            }
        }
    }

    private fun failingGateway(): DriverGatewayApi = Proxy.newProxyInstance(
        DriverGatewayApi::class.java.classLoader, arrayOf(DriverGatewayApi::class.java),
    ) { _, _, _ -> throw IOException("offline") } as DriverGatewayApi

    private fun responseGateway(
        calls: GatewayCalls,
        detail: DriverTaskDetailDto,
        claimedTrip: DriverTripDetailsDto = tripDetails(taskNumber = "claimed"),
    ): DriverGatewayApi = Proxy.newProxyInstance(
        DriverGatewayApi::class.java.classLoader, arrayOf(DriverGatewayApi::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "driverTaskDetail" -> {
                calls.detailEntryIds += arguments.orEmpty().first() as String
                successfulResponse(detail)
            }
            "logisticsDriverTask" -> {
                calls.logisticsTaskIds += arguments.orEmpty().first() as String
                val trip = if (calls.claimTaskIds.isEmpty()) {
                    tripDetails(taskNumber = "preview")
                } else {
                    claimedTrip
                }
                successfulResponse(DriverTaskTripDetailsResponseDto(trip))
            }
            "claimFutureLogisticsTask" -> {
                calls.claimTaskIds += arguments.orEmpty().first() as String
                successfulResponse(DriverTaskTripDetailsResponseDto(claimedTrip))
            }
            else -> throw IOException("Unexpected gateway call: ${method.name}")
        }
    } as DriverGatewayApi

    /** Keeps this feature test on the module's existing transitive Retrofit runtime only. */
    private fun successfulResponse(body: Any): Any = Class.forName("retrofit2.Response")
        .getMethod("success", Any::class.java)
        .invoke(null, body)

    private fun plainTextCodec() = object : PendingPayloadCodec {
        override fun encrypt(plainText: String) = plainText
        override fun decrypt(encoded: String) = encoded
    }

    private fun evidence(id: String) = TaskEvidenceEntity(
        evidenceId = id, userId = USER, entryId = ENTRY, routeIndex = 0, capturedAt = "2026-09-06T10:00:00Z",
        encryptedFilePath = "file", fileName = "file.jpg", contentType = "image/jpeg", sizeBytes = 1, sha256 = "hash",
        reservationOperationId = id, uploadOperationId = id, state = "READY", mediaId = "media", mediaGeneration = 1,
        reviewReason = null, uploadPercent = 100, lastError = null, createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
    )

    private fun logisticsDetail() = DriverTaskDetailDto(
        entryId = ENTRY,
        version = VERSION,
        taskId = "task",
        source = TaskSourceReferenceDto(type = "LOGISTICS_DRIVER_TASK", sourceId = LOGISTICS_SOURCE),
        routeIndex = 0,
        title = "Task",
        description = null,
        taskObject = null,
        taskText = null,
        scheduledDate = "2026-09-07",
        deadlineAt = null,
        priority = 1,
        queuePosition = 1,
        status = "WAITING",
        availabilityMode = "AVAILABLE",
        plannedDurationMinutes = null,
        activeStartedAt = null,
        activeWorkSeconds = 0,
        audienceSelectors = emptyList(),
        assignments = emptyList(),
        materials = emptyList(),
        comments = emptyList(),
        sourceMedia = emptyList(),
        evidence = emptyList(),
        relatedSteps = emptyList(),
        resultPhotoMinCount = 1,
        completionAllowed = false,
    )

    private fun tripDetails(taskNumber: String) = DriverTripDetailsDto(
        taskNumber = taskNumber,
        tripNumber = 1,
        operationType = "SHIPMENT",
        customerDeliveryPurpose = null,
        clientName = "Client",
        address = null,
        latitude = null,
        longitude = null,
        primaryContactName = null,
        primaryContactPhone = null,
        additionalContacts = emptyList(),
        comment = null,
        desiredDeliveryWindows = emptyList(),
        scheduledDate = "2026-09-07",
        cabins = emptyList(),
    )

    private data class GatewayCalls(
        val detailEntryIds: MutableList<String> = mutableListOf(),
        val logisticsTaskIds: MutableList<String> = mutableListOf(),
        val claimTaskIds: MutableList<String> = mutableListOf(),
    )

    private data class Fixture(
        val store: DriverLocalStore,
        val viewModel: TaskDetailViewModel,
        val syncRequests: MutableList<String>,
    )

    private companion object {
        const val USER = "driver"
        const val ENTRY = "entry"
        const val VERSION = 9L
        const val GROUP = "group"
        const val LEASE = "lease"
        const val WAREHOUSE = "warehouse"
        const val LOGISTICS_SOURCE = "logistics-source"
        const val RETRY_EVIDENCE = "11111111-2222-4333-8444-555555555555"
        const val SERVER_EPOCH_MILLIS = 1_788_688_800_000L
        const val WAREHOUSE_DATE_FAILURE = "Не удалось определить дату склада. Синхронизируйте данные."
        const val CARD_REFRESH_FAILURE = "Не удалось обновить карточку. Повторим при синхронизации."
        const val ACTION_QUEUE_FAILURE =
            "Не удалось поставить действие в очередь. Синхронизируйте задание и повторите попытку."
        const val MISSING_EVIDENCE_FAILURE =
            "Для завершения логистического задания добавьте фотографию"
        const val EXTRA_TASK_DATE_FAILURE = "Дополнительную ходку можно взять только на будущую дату"
    }
}
