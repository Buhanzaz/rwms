package dev.buhanzaz.rwms.worker.core.sync

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.PendingPayloadCipher
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerDatabase
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.media.EvidenceUploadResult
import dev.buhanzaz.rwms.worker.core.media.WorkerEvidenceUploader
import dev.buhanzaz.rwms.worker.core.network.CreateUploadSessionRequestDto
import dev.buhanzaz.rwms.worker.core.network.EvidenceReservationRequestDto
import dev.buhanzaz.rwms.worker.core.network.FinalizeUploadRequestDto
import dev.buhanzaz.rwms.worker.core.network.MediaAssetDto
import dev.buhanzaz.rwms.worker.core.network.TaskEvidenceDto
import dev.buhanzaz.rwms.worker.core.network.UploadSessionDto
import dev.buhanzaz.rwms.worker.core.network.UploadedObjectDto
import dev.buhanzaz.rwms.worker.core.network.WorkerActionRequestDto
import dev.buhanzaz.rwms.worker.core.network.WorkerActionResultDto
import dev.buhanzaz.rwms.worker.core.network.WorkerContextDto
import dev.buhanzaz.rwms.worker.core.network.WorkerDeviceRegistrationDto
import dev.buhanzaz.rwms.worker.core.network.WorkerDeviceRegistrationRequestDto
import dev.buhanzaz.rwms.worker.core.network.WorkerFeedDto
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayApi
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import dev.buhanzaz.rwms.worker.core.network.WorkerIdentityDto
import dev.buhanzaz.rwms.worker.core.network.WorkerOfflineLeaseDto
import dev.buhanzaz.rwms.worker.core.network.WorkerTaskDetailDto
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
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
    private lateinit var database: WorkerDatabase
    private lateinit var json: Json

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            WorkerDatabase::class.java,
        ).allowMainThreadQueries().build()
        json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `blocked upload still replaces a secondary workers stale in progress task from fresh feed`() = runTest {
        val now = System.currentTimeMillis()
        database.taskDao().upsertAll(listOf(inProgressTask(now)))
        database.evidenceDao().upsert(reservedEvidence(now))
        val api = FreshEmptyFeedApi()
        val uploader = BlockedUploader()
        val coordinator = WorkerSyncCoordinator(
            gateway = WorkerGatewayClient(api, json),
            database = database,
            localStore = WorkerLocalStore(
                database,
                PendingPayloadCipher(RuntimeEnvironment.getApplication()),
                json,
            ),
            projections = WorkerProjectionWriter(database, json),
            mediaUploadPipeline = uploader,
            json = json,
        )

        val outcome = coordinator.sync(USER_ID)

        assertThat(outcome).isEqualTo(WorkerSyncOutcome.Retry("Фотография ещё обрабатывается"))
        assertThat(uploader.calls).isEqualTo(1)
        assertThat(api.feedCalls.get()).isEqualTo(1)
        assertThat(database.taskDao().task(USER_ID, ENTRY_ID)).isNull()
        assertThat(database.evidenceDao().evidence(USER_ID, EVIDENCE_ID)?.state).isEqualTo("RESERVED")
        // Feed data must not activate the new lease while media is blocked.
        assertThat(database.sessionDao().session(USER_ID)?.leaseId).isNull()
        assertThat(database.sessionDao().session(USER_ID)?.cacheHidden).isTrue()
    }

    @Test
    fun `failed media session still refreshes a secondary workers stale in progress task`() = runTest {
        val now = System.currentTimeMillis()
        database.taskDao().upsertAll(listOf(inProgressTask(now)))
        database.evidenceDao().upsert(reservedEvidence(now))
        val api = FreshEmptyFeedApi()
        val uploader = FailingMediaSessionUploader()
        val coordinator = WorkerSyncCoordinator(
            gateway = WorkerGatewayClient(api, json),
            database = database,
            localStore = WorkerLocalStore(
                database,
                PendingPayloadCipher(RuntimeEnvironment.getApplication()),
                json,
            ),
            projections = WorkerProjectionWriter(database, json),
            mediaUploadPipeline = uploader,
            json = json,
        )

        val outcome = coordinator.sync(USER_ID)

        assertThat(outcome).isEqualTo(WorkerSyncOutcome.Retry("Media session unavailable"))
        assertThat(uploader.calls).isEqualTo(1)
        assertThat(api.feedCalls.get()).isEqualTo(1)
        assertThat(database.taskDao().task(USER_ID, ENTRY_ID)).isNull()
        // Media work is durable and is retried; it does not block feed reconciliation.
        assertThat(database.evidenceDao().evidence(USER_ID, EVIDENCE_ID)?.state).isEqualTo("RESERVED")
        assertThat(database.sessionDao().session(USER_ID)?.leaseId).isNull()
    }

    private fun inProgressTask(now: Long) = WorkerTaskEntity(
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

    private class BlockedUploader : WorkerEvidenceUploader {
        var calls = 0

        override suspend fun uploadReservedEvidence(
            userId: String,
            evidence: TaskEvidenceEntity,
        ): EvidenceUploadResult {
            calls += 1
            return EvidenceUploadResult.Processing
        }
    }

    private class FailingMediaSessionUploader : WorkerEvidenceUploader {
        var calls = 0

        override suspend fun uploadReservedEvidence(
            userId: String,
            evidence: TaskEvidenceEntity,
        ): EvidenceUploadResult {
            calls += 1
            throw IOException("Media session unavailable")
        }
    }

    private class FreshEmptyFeedApi : WorkerGatewayApi {
        val feedCalls = AtomicInteger()

        override suspend fun workerContext(): Response<WorkerContextDto> = Response.success(
            WorkerContextDto(
                worker = WorkerIdentityDto(
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
                offlineLease = WorkerOfflineLeaseDto(
                    id = "lease-11",
                    issuedAt = "2026-07-26T16:53:14Z",
                    expiresAt = "2026-07-27T16:53:14Z",
                    syncRevision = 11,
                ),
            ),
        )

        override suspend fun workerFeed(
            cursor: String?,
            limit: Int,
            ifNoneMatch: String?,
        ): Response<WorkerFeedDto> {
            feedCalls.incrementAndGet()
            return Response.success(
                WorkerFeedDto(
                    revision = 11,
                    serverTime = "2026-07-26T16:53:14Z",
                    categories = emptyList(),
                    nextCursor = null,
                ),
            )
        }

        override suspend fun workerTaskDetail(entryId: String): Response<WorkerTaskDetailDto> = unused()

        override suspend fun applyAction(
            entryId: String,
            idempotencyKey: String,
            request: WorkerActionRequestDto,
        ): Response<WorkerActionResultDto> = unused()

        override suspend fun reserveEvidence(
            entryId: String,
            idempotencyKey: String,
            request: EvidenceReservationRequestDto,
        ): Response<TaskEvidenceDto> = unused()

        override suspend fun registerDevice(
            installationId: String,
            request: WorkerDeviceRegistrationRequestDto,
        ): Response<WorkerDeviceRegistrationDto> = unused()

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

        private fun <T> unused(): Response<T> = error("This gateway call is not expected in this regression")
    }

    private companion object {
        const val USER_ID = "worker-test-228-228"
        const val ENTRY_ID = "entry-urgent"
        const val EVIDENCE_ID = "evidence-reserved"
    }
}
