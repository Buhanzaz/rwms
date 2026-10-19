package dev.buhanzaz.rwms.driver.core.sync

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.database.PendingPayloadCipher
import dev.buhanzaz.rwms.driver.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.driver.core.database.DriverDatabase
import dev.buhanzaz.rwms.driver.core.database.DriverLocalStore
import dev.buhanzaz.rwms.driver.core.database.DriverTaskEntity
import dev.buhanzaz.rwms.driver.core.media.EvidenceUploadResult
import dev.buhanzaz.rwms.driver.core.media.DriverEvidenceUploader
import dev.buhanzaz.rwms.driver.core.network.CreateUploadSessionRequestDto
import dev.buhanzaz.rwms.driver.core.network.ConfirmMedicalCheckRequestDto
import dev.buhanzaz.rwms.driver.core.network.DriverTaskTripDetailsResponseDto
import dev.buhanzaz.rwms.driver.core.network.EvidenceReservationRequestDto
import dev.buhanzaz.rwms.driver.core.network.FinalizeUploadRequestDto
import dev.buhanzaz.rwms.driver.core.network.MediaAssetDto
import dev.buhanzaz.rwms.driver.core.network.TaskEvidenceDto
import dev.buhanzaz.rwms.driver.core.network.UploadSessionDto
import dev.buhanzaz.rwms.driver.core.network.UploadedObjectDto
import dev.buhanzaz.rwms.driver.core.network.DriverActionRequestDto
import dev.buhanzaz.rwms.driver.core.network.DriverActionResultDto
import dev.buhanzaz.rwms.driver.core.network.DriverContextDto
import dev.buhanzaz.rwms.driver.core.network.DriverDeviceRegistrationDto
import dev.buhanzaz.rwms.driver.core.network.DriverDeviceRegistrationRequestDto
import dev.buhanzaz.rwms.driver.core.network.DriverFeedDto
import dev.buhanzaz.rwms.driver.core.network.DriverGatewayApi
import dev.buhanzaz.rwms.driver.core.network.DriverGatewayClient
import dev.buhanzaz.rwms.driver.core.network.DriverIdentityDto
import dev.buhanzaz.rwms.driver.core.network.DriverOfflineLeaseDto
import dev.buhanzaz.rwms.driver.core.network.DriverTaskDetailDto
import dev.buhanzaz.rwms.driver.core.network.ReserveShiftPhotoRequestDto
import dev.buhanzaz.rwms.driver.core.network.ReturnToWarehouseRequestDto
import dev.buhanzaz.rwms.driver.core.network.ShiftTransitionRequestDto
import dev.buhanzaz.rwms.driver.core.network.SubmitClosingReportRequestDto
import dev.buhanzaz.rwms.driver.core.network.TodayDriverShiftDto
import dev.buhanzaz.rwms.driver.core.network.UpdateInspectionItemRequestDto
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class BlockedMediaFeedReconciliationRobolectricTest {
    private lateinit var database: DriverDatabase
    private lateinit var json: Json

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            DriverDatabase::class.java,
        ).allowMainThreadQueries().build()
        json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `blocked upload still replaces a secondary drivers stale in progress task from fresh feed`() = runTest {
        val now = System.currentTimeMillis()
        database.taskDao().upsertAll(listOf(inProgressTask(now)))
        database.evidenceDao().upsert(reservedEvidence(now))
        val api = FreshEmptyFeedApi()
        val uploader = BlockedUploader()
        val coordinator = DriverSyncCoordinator(
            gateway = DriverGatewayClient(api, json),
            database = database,
            localStore = DriverLocalStore(
                database,
                PendingPayloadCipher(RuntimeEnvironment.getApplication()),
                json,
            ),
            projections = DriverProjectionWriter(database, json),
            mediaUploadPipeline = uploader,
            json = json,
        )

        val outcome = coordinator.sync(USER_ID)

        assertThat(outcome).isEqualTo(DriverSyncOutcome.Deferred("Фотография ещё обрабатывается"))
        assertThat(uploader.calls).isEqualTo(1)
        assertThat(api.feedCalls.get()).isEqualTo(1)
        assertThat(database.taskDao().task(USER_ID, ENTRY_ID)).isNull()
        assertThat(database.evidenceDao().evidence(USER_ID, EVIDENCE_ID)?.state).isEqualTo("RESERVED")
        // Feed data must not activate the new lease while media is blocked.
        assertThat(database.sessionDao().session(USER_ID)?.leaseId).isNull()
        assertThat(database.sessionDao().session(USER_ID)?.cacheHidden).isTrue()
    }

    @Test
    fun `failed media session still refreshes a secondary drivers stale in progress task`() = runTest {
        val now = System.currentTimeMillis()
        database.taskDao().upsertAll(listOf(inProgressTask(now)))
        database.evidenceDao().upsert(reservedEvidence(now))
        val api = FreshEmptyFeedApi()
        val uploader = FailingMediaSessionUploader()
        val coordinator = DriverSyncCoordinator(
            gateway = DriverGatewayClient(api, json),
            database = database,
            localStore = DriverLocalStore(
                database,
                PendingPayloadCipher(RuntimeEnvironment.getApplication()),
                json,
            ),
            projections = DriverProjectionWriter(database, json),
            mediaUploadPipeline = uploader,
            json = json,
        )

        val outcome = coordinator.sync(USER_ID)

        assertThat(outcome).isEqualTo(
            DriverSyncOutcome.Retry(
                "Не удалось отправить фотографию. Проверьте сеть и повторите попытку.",
            ),
        )
        assertThat(uploader.calls).isEqualTo(1)
        assertThat(api.feedCalls.get()).isEqualTo(1)
        assertThat(database.taskDao().task(USER_ID, ENTRY_ID)).isNull()
        // Media work is durable and is retried; it does not block feed reconciliation.
        assertThat(database.evidenceDao().evidence(USER_ID, EVIDENCE_ID)?.state).isEqualTo("RESERVED")
        assertThat(database.sessionDao().session(USER_ID)?.leaseId).isNull()
    }

    @Test
    fun `cancelled media upload propagates without scheduling a sync retry`() = runTest {
        val now = System.currentTimeMillis()
        database.taskDao().upsertAll(listOf(inProgressTask(now)))
        database.evidenceDao().upsert(reservedEvidence(now))
        val api = FreshEmptyFeedApi()
        val coordinator = DriverSyncCoordinator(
            gateway = DriverGatewayClient(api, json),
            database = database,
            localStore = DriverLocalStore(
                database,
                PendingPayloadCipher(RuntimeEnvironment.getApplication()),
                json,
            ),
            projections = DriverProjectionWriter(database, json),
            mediaUploadPipeline = CancellingUploader(),
            json = json,
        )

        val cancellation = try {
            coordinator.sync(USER_ID)
            null
        } catch (error: CancellationException) {
            error
        }

        assertThat(cancellation).isNotNull()
        assertThat(api.feedCalls.get()).isEqualTo(0)
    }

    private fun inProgressTask(now: Long) = DriverTaskEntity(
        localId = "$USER_ID:$ENTRY_ID",
        userId = USER_ID,
        entryId = ENTRY_ID,
        taskId = "task-1",
        version = 10,
        categoryId = "movement",
        categoryName = "Перемещение",
        categorySortOrder = 0,
        title = "Срочное перемещение",
        unitNumber = "БЫТ-006",
        taskText = null,
        scheduledDate = "2026-07-26",
        deadlineAt = null,
        priority = 1,
        queuePosition = 0,
        status = "IN_PROGRESS",
        availabilityMode = "MANDATORY",
        plannedDurationMinutes = 60,
        activeStartedAt = "2026-07-26T16:00:00Z",
        activeWorkSeconds = 0,
        readyEvidenceCount = 0,
        resultPhotoMinCount = 1,
        lastServerRevision = 10,
        locallyPending = true,
        updatedAtEpochMillis = now,
    )

    private fun reservedEvidence(now: Long) = TaskEvidenceEntity(
        evidenceId = EVIDENCE_ID,
        userId = USER_ID,
        entryId = ENTRY_ID,
        routeIndex = 0,
        capturedAt = "2026-07-26T16:50:00Z",
        encryptedFilePath = "/not-used-in-this-test",
        fileName = "$EVIDENCE_ID.jpg",
        contentType = "image/jpeg",
        sizeBytes = 128,
        sha256 = "a".repeat(64),
        reservationOperationId = EVIDENCE_ID,
        uploadOperationId = EVIDENCE_ID,
        state = "RESERVED",
        mediaId = null,
        mediaGeneration = null,
        reviewReason = null,
        uploadPercent = 0,
        lastError = null,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
    )

    private class BlockedUploader : DriverEvidenceUploader {
        var calls = 0

        override suspend fun uploadReservedEvidence(
            userId: String,
            evidence: TaskEvidenceEntity,
        ): EvidenceUploadResult {
            calls += 1
            return EvidenceUploadResult.Processing
        }
    }

    private class FailingMediaSessionUploader : DriverEvidenceUploader {
        var calls = 0

        override suspend fun uploadReservedEvidence(
            userId: String,
            evidence: TaskEvidenceEntity,
        ): EvidenceUploadResult {
            calls += 1
            throw IOException("Media session unavailable")
        }
    }

    private class CancellingUploader : DriverEvidenceUploader {
        override suspend fun uploadReservedEvidence(
            userId: String,
            evidence: TaskEvidenceEntity,
        ): EvidenceUploadResult = throw CancellationException("driver cancelled")
    }

    private class FreshEmptyFeedApi : DriverGatewayApi {
        val feedCalls = AtomicInteger()

        override suspend fun todayDriverShift(): Response<TodayDriverShiftDto> = shiftResponse()

        override suspend fun markShiftBriefingSeen(
            shiftId: String,
            idempotencyKey: String,
            request: ShiftTransitionRequestDto,
        ): Response<TodayDriverShiftDto> = shiftResponse()

        override suspend fun confirmShiftMedicalCheck(
            shiftId: String,
            idempotencyKey: String,
            request: ConfirmMedicalCheckRequestDto,
        ): Response<TodayDriverShiftDto> = shiftResponse()

        override suspend fun updateShiftInspectionItem(
            shiftId: String,
            itemId: String,
            idempotencyKey: String,
            request: UpdateInspectionItemRequestDto,
        ): Response<TodayDriverShiftDto> = shiftResponse()

        override suspend fun completeShiftInspection(
            shiftId: String,
            idempotencyKey: String,
            request: ShiftTransitionRequestDto,
        ): Response<TodayDriverShiftDto> = shiftResponse()

        override suspend fun startShift(
            shiftId: String,
            idempotencyKey: String,
            request: ShiftTransitionRequestDto,
        ): Response<TodayDriverShiftDto> = shiftResponse()

        override suspend fun startShiftClosing(
            shiftId: String,
            idempotencyKey: String,
            request: ShiftTransitionRequestDto,
        ): Response<TodayDriverShiftDto> = shiftResponse()

        override suspend fun confirmShiftWarehouseReturn(
            shiftId: String,
            idempotencyKey: String,
            request: ReturnToWarehouseRequestDto,
        ): Response<TodayDriverShiftDto> = shiftResponse()

        override suspend fun submitShiftClosingReport(
            shiftId: String,
            idempotencyKey: String,
            request: SubmitClosingReportRequestDto,
        ): Response<TodayDriverShiftDto> = shiftResponse()

        override suspend fun reserveShiftPhoto(
            shiftId: String,
            idempotencyKey: String,
            request: ReserveShiftPhotoRequestDto,
        ): Response<TodayDriverShiftDto> = shiftResponse()

        override suspend fun closeShift(
            shiftId: String,
            idempotencyKey: String,
            request: ShiftTransitionRequestDto,
        ): Response<TodayDriverShiftDto> = shiftResponse()

        override suspend fun driverContext(): Response<DriverContextDto> = Response.success(
            DriverContextDto(
                driver = DriverIdentityDto(
                    id = USER_ID,
                    warehouseId = "warehouse-1",
                    login = "test228228",
                    displayName = "Тест 228-228",
                ),
                groups = emptyList(),
                qualifications = emptyList(),
                categories = emptyList(),
                kpiPalette = null,
                serverTime = "2026-07-26T16:53:14Z",
                revision = 11,
                offlineLease = DriverOfflineLeaseDto(
                    id = "lease-11",
                    issuedAt = "2026-07-26T16:53:14Z",
                    expiresAt = "2026-07-27T16:53:14Z",
                    syncRevision = 11,
                ),
            ),
        )

        override suspend fun driverFeed(
            cursor: String?,
            limit: Int,
            ifNoneMatch: String?,
        ): Response<DriverFeedDto> {
            feedCalls.incrementAndGet()
            return Response.success(
                DriverFeedDto(
                    revision = 11,
                    serverTime = "2026-07-26T16:53:14Z",
                    categories = emptyList(),
                    nextCursor = null,
                ),
            )
        }

        override suspend fun driverTaskDetail(entryId: String): Response<DriverTaskDetailDto> = unused()

        override suspend fun logisticsDriverTask(taskId: String): Response<DriverTaskTripDetailsResponseDto> = unused()

        override suspend fun claimFutureLogisticsTask(taskId: String): Response<DriverTaskTripDetailsResponseDto> = unused()

        override suspend fun applyAction(
            entryId: String,
            idempotencyKey: String,
            request: DriverActionRequestDto,
        ): Response<DriverActionResultDto> = unused()

        override suspend fun reserveEvidence(
            entryId: String,
            idempotencyKey: String,
            request: EvidenceReservationRequestDto,
        ): Response<TaskEvidenceDto> = unused()

        override suspend fun registerDevice(
            installationId: String,
            request: DriverDeviceRegistrationRequestDto,
        ): Response<DriverDeviceRegistrationDto> = unused()

        override suspend fun unregisterDevice(installationId: String): Response<Unit> = unused()

        override suspend fun createUploadSession(
            idempotencyKey: String,
            request: CreateUploadSessionRequestDto,
        ): Response<UploadSessionDto> = unused()

        override suspend fun uploadMediaContent(
            sameOriginContentPath: String,
            idempotencyKey: String,
            content: RequestBody,
        ): Response<UploadedObjectDto> = unused()

        override suspend fun finalizeUploadSession(
            uploadSessionId: String,
            idempotencyKey: String,
            request: FinalizeUploadRequestDto,
        ): Response<MediaAssetDto> = unused()

        override suspend fun mediaContent(sameOriginMediaPath: String): Response<ResponseBody> = unused()

        private fun shiftResponse(): Response<TodayDriverShiftDto> = Response.success(
            TodayDriverShiftDto(
                enabled = false,
                serverTime = "2026-07-26T16:53:14Z",
                nextRequiredAction = "SHOW_TASKS",
            ),
        )

        private fun <T> unused(): Response<T> = error("This gateway call is not expected in this regression")
    }

    private companion object {
        const val USER_ID = "driver-test-228-228"
        const val ENTRY_ID = "entry-urgent"
        const val EVIDENCE_ID = "evidence-reserved"
    }
}
