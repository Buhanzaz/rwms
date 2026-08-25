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
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
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

        assertThat(outcome).isEqualTo(WorkerSyncOutcome.Deferred("Фотография ещё обрабатывается"))
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

    @Test
    fun `cancelled media upload propagates without scheduling a sync retry`() = runTest {
        val now = System.currentTimeMillis()
        database.taskDao().upsertAll(listOf(inProgressTask(now)))
        database.evidenceDao().upsert(reservedEvidence(now))
        val api = FreshEmptyFeedApi()
        val coordinator = WorkerSyncCoordinator(
            gateway = WorkerGatewayClient(api, json),
            database = database,
            localStore = WorkerLocalStore(
                database,
                PendingPayloadCipher(RuntimeEnvironment.getApplication()),
                json,
            ),
            projections = WorkerProjectionWriter(database, json),
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

    @Test
    fun `evidence uploads run concurrently within the global bound`() = runTest {
        val now = System.currentTimeMillis()
        listOf("evidence-1", "evidence-2", "evidence-3").forEach { evidenceId ->
            database.evidenceDao().upsert(
                reservedEvidence(now).copy(
                    evidenceId = evidenceId,
                    reservationOperationId = evidenceId,
                    uploadOperationId = evidenceId,
                ),
            )
        }
        val uploader = ConcurrencyTrackingUploader()
        val coordinator = WorkerSyncCoordinator(
            gateway = WorkerGatewayClient(FreshEmptyFeedApi(), json),
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

        assertThat(coordinator.sync(USER_ID)).isEqualTo(WorkerSyncOutcome.Complete)
        assertThat(uploader.calls.get()).isEqualTo(3)
        assertThat(uploader.maximumConcurrent.get()).isEqualTo(2)
    }

    @Test
    fun `legacy expired lease photo is retained and leaves uploads when task is already done`() = runTest {
        val now = System.currentTimeMillis()
        val legacy = reservedEvidence(now).copy(
            state = "REVIEW_REQUIRED",
            reviewReason = null,
            lastError = WorkerLocalStore.EXPIRED_OFFLINE_LEASE_ERROR,
            encryptedFilePath = "/retained/legacy-photo.enc",
        )
        database.evidenceDao().upsert(legacy)
        val api = TerminalTaskReservationApi()
        val uploader = BlockedUploader()
        val coordinator = WorkerSyncCoordinator(
            gateway = WorkerGatewayClient(api, json),
            database = database,
            localStore = WorkerLocalStore(
                database,
                testPendingPayloadCipher(),
                json,
            ),
            projections = WorkerProjectionWriter(database, json),
            mediaUploadPipeline = uploader,
            json = json,
        )

        val outcome = coordinator.sync(USER_ID)

        assertThat(outcome).isEqualTo(WorkerSyncOutcome.Complete)
        assertThat(api.reservationCalls.get()).isEqualTo(1)
        assertThat(api.detailCalls.get()).isEqualTo(1)
        assertThat(uploader.calls).isEqualTo(0)
        assertThat(database.outboxDao().pending(USER_ID)).isEmpty()
        assertThat(database.evidenceDao().pending(USER_ID)).isEmpty()
        val retained = database.evidenceDao().evidence(USER_ID, EVIDENCE_ID)
        assertThat(retained?.state).isEqualTo(WorkerLocalStore.EVIDENCE_SUPERSEDED)
        assertThat(retained?.encryptedFilePath).isEqualTo("/retained/legacy-photo.enc")
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
        fileName = "$EVIDENCE_ID.webp",
        contentType = "image/webp",
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

    private fun testPendingPayloadCipher(): PendingPayloadCipher {
        val constructor = PendingPayloadCipher::class.java.getDeclaredConstructor(SecretKey::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(SecretKeySpec(ByteArray(32) { 0x2a }, "AES"))
    }

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

    private class CancellingUploader : WorkerEvidenceUploader {
        override suspend fun uploadReservedEvidence(
            userId: String,
            evidence: TaskEvidenceEntity,
        ): EvidenceUploadResult = throw CancellationException("worker cancelled")
    }

    /** Records the real coordinator concurrency while returning deterministic READY results. */
    private class ConcurrencyTrackingUploader : WorkerEvidenceUploader {
        val calls = AtomicInteger()
        val maximumConcurrent = AtomicInteger()
        private val active = AtomicInteger()

        override suspend fun uploadReservedEvidence(
            userId: String,
            evidence: TaskEvidenceEntity,
        ): EvidenceUploadResult = withContext(Dispatchers.Default) {
            calls.incrementAndGet()
            val concurrent = active.incrementAndGet()
            maximumConcurrent.updateAndGet { current -> maxOf(current, concurrent) }
            try {
                delay(100)
                EvidenceUploadResult.Ready("media-${evidence.evidenceId}", 1)
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private open class FreshEmptyFeedApi : WorkerGatewayApi {
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

    /** Returns the production-shaped conflict followed by an authoritative completed task. */
    private class TerminalTaskReservationApi : FreshEmptyFeedApi() {
        val reservationCalls = AtomicInteger()
        val detailCalls = AtomicInteger()

        override suspend fun workerTaskDetail(entryId: String): Response<WorkerTaskDetailDto> {
            detailCalls.incrementAndGet()
            return Response.success(
                WorkerTaskDetailDto(
                    entryId = entryId,
                    version = 18,
                    taskId = "task-terminal",
                    routeIndex = 0,
                    title = "Завершённое задание",
                    description = null,
                    taskObject = null,
                    taskText = null,
                    scheduledDate = "2026-08-24",
                    deadlineAt = null,
                    priority = 0,
                    queuePosition = 0,
                    status = "DONE",
                    availabilityMode = "AVAILABLE",
                    plannedDurationMinutes = null,
                    activeStartedAt = null,
                    activeWorkSeconds = 600,
                    audienceSelectors = emptyList(),
                    assignments = emptyList(),
                    materials = emptyList(),
                    comments = emptyList(),
                    sourceMedia = emptyList(),
                    evidence = emptyList(),
                    relatedSteps = emptyList(),
                    resultPhotoMinCount = 1,
                    completionAllowed = false,
                ),
            )
        }

        override suspend fun reserveEvidence(
            entryId: String,
            idempotencyKey: String,
            request: EvidenceReservationRequestDto,
        ): Response<TaskEvidenceDto> {
            reservationCalls.incrementAndGet()
            val problem =
                """{"type":"about:blank","title":"Conflict","status":409,"detail":"Добавить новую фотографию можно только к заданию в работе","code":"ENTRY_NOT_IN_PROGRESS"}"""
            return Response.error(
                409,
                problem.toResponseBody("application/problem+json".toMediaType()),
            )
        }
    }

    private companion object {
        const val USER_ID = "worker-test-228-228"
        const val ENTRY_ID = "entry-urgent"
        const val EVIDENCE_ID = "evidence-reserved"
    }
}
