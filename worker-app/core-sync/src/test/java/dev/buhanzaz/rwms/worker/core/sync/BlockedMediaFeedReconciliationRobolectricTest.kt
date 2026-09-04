package dev.buhanzaz.rwms.worker.core.sync

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.PendingEvidenceReservation
import dev.buhanzaz.rwms.worker.core.database.PendingPayloadCipher
import dev.buhanzaz.rwms.worker.core.database.PendingWorkerAction
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerDatabase
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.database.WorkerOutboxEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.media.EvidenceUploadResult
import dev.buhanzaz.rwms.worker.core.media.WorkerEvidenceUploader
import dev.buhanzaz.rwms.worker.core.network.ApiProblemDto
import dev.buhanzaz.rwms.worker.core.network.CreateUploadSessionRequestDto
import dev.buhanzaz.rwms.worker.core.network.EvidenceReservationRequestDto
import dev.buhanzaz.rwms.worker.core.network.FinalizeUploadRequestDto
import dev.buhanzaz.rwms.worker.core.network.MediaAssetDto
import dev.buhanzaz.rwms.worker.core.network.MediaAssetPageDto
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
import dev.buhanzaz.rwms.worker.core.network.WorkerProfileAvatarScopeDto
import dev.buhanzaz.rwms.worker.core.network.WorkerTaskDetailDto
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
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

        assertThat(outcome).isEqualTo(
            WorkerSyncOutcome.Retry(
                "Не удалось загрузить фотографию. Проверьте сеть и повторите попытку.",
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
    fun `processing evidence for older entry does not block independent completion`() = runTest {
        val now = System.currentTimeMillis()
        val completedEntryId = "entry-independent"
        database.taskDao().upsertAll(
            listOf(
                inProgressTask(now),
                inProgressTask(now).copy(
                    localId = "$USER_ID:$completedEntryId",
                    entryId = completedEntryId,
                    taskId = "task-independent",
                    readyEvidenceCount = 1,
                    resultPhotoMinCount = 1,
                ),
            ),
        )
        database.evidenceDao().upsert(reservedEvidence(now))
        val cipher = testPendingPayloadCipher()
        database.outboxDao().insert(completionOutbox(completedEntryId, now + 1, cipher))
        val api = CompletionActionApi(completedTaskDetail(completedEntryId))
        val coordinator = coordinator(api, BlockedUploader(), cipher)

        val outcome = coordinator.sync(USER_ID)

        assertThat(outcome).isEqualTo(WorkerSyncOutcome.Deferred("Фотография ещё обрабатывается"))
        assertThat(api.actionEntryIds).containsExactly(completedEntryId)
        assertThat(database.outboxDao().pending(USER_ID)).isEmpty()
    }

    @Test
    fun `retrying reservation for older entry does not block independent completion`() = runTest {
        val now = System.currentTimeMillis()
        val completedEntryId = "entry-after-reservation"
        database.taskDao().upsertAll(
            listOf(
                inProgressTask(now),
                inProgressTask(now).copy(
                    localId = "$USER_ID:$completedEntryId",
                    entryId = completedEntryId,
                    taskId = "task-after-reservation",
                    readyEvidenceCount = 1,
                    resultPhotoMinCount = 1,
                ),
            ),
        )
        database.evidenceDao().upsert(reservedEvidence(now).copy(state = "CAPTURED"))
        val cipher = testPendingPayloadCipher()
        database.outboxDao().insert(reservationOutbox(now, cipher))
        database.outboxDao().insert(completionOutbox(completedEntryId, now + 1, cipher))
        val api = RetryingReservationCompletionApi(completedTaskDetail(completedEntryId))
        val coordinator = coordinator(api, BlockedUploader(), cipher)

        val outcome = coordinator.sync(USER_ID)

        assertThat(outcome).isEqualTo(
            WorkerSyncOutcome.Retry(
                "RWMS временно недоступен. Проверьте соединение и повторите попытку.",
            ),
        )
        assertThat(api.actionEntryIds).containsExactly(completedEntryId)
        assertThat(database.outboxDao().pending(USER_ID).map { it.entryId }).containsExactly(ENTRY_ID)
    }

    @Test
    fun `deferred completion for older entry does not block ready independent completion`() = runTest {
        val now = System.currentTimeMillis()
        val completedEntryId = "entry-after-deferred-completion"
        database.taskDao().upsertAll(
            listOf(
                inProgressTask(now).copy(resultPhotoMinCount = 1, readyEvidenceCount = 0),
                inProgressTask(now).copy(
                    localId = "$USER_ID:$completedEntryId",
                    entryId = completedEntryId,
                    taskId = "task-after-deferred-completion",
                    readyEvidenceCount = 1,
                    resultPhotoMinCount = 1,
                ),
            ),
        )
        val cipher = testPendingPayloadCipher()
        database.outboxDao().insert(completionOutbox(ENTRY_ID, now, cipher))
        database.outboxDao().insert(completionOutbox(completedEntryId, now + 1, cipher))
        val api = CompletionActionApi(completedTaskDetail(completedEntryId))
        val coordinator = coordinator(api, BlockedUploader(), cipher)

        val outcome = coordinator.sync(USER_ID)

        assertThat(outcome).isEqualTo(
            WorkerSyncOutcome.Deferred("Завершение $ENTRY_ID ждёт обработки фотографии"),
        )
        assertThat(api.actionEntryIds).containsExactly(completedEntryId)
    }

    @Test
    fun `feed ready evidence count allows completion without a duplicate local row`() = runTest {
        val now = System.currentTimeMillis()
        val completedEntryId = "entry-server-evidence"
        database.taskDao().upsertAll(
            listOf(
                inProgressTask(now).copy(
                    localId = "$USER_ID:$completedEntryId",
                    entryId = completedEntryId,
                    taskId = "task-server-evidence",
                    readyEvidenceCount = 1,
                    resultPhotoMinCount = 1,
                ),
            ),
        )
        val cipher = testPendingPayloadCipher()
        database.outboxDao().insert(completionOutbox(completedEntryId, now, cipher))
        val api = CompletionActionApi(completedTaskDetail(completedEntryId))
        val coordinator = coordinator(api, BlockedUploader(), cipher)

        val outcome = coordinator.sync(USER_ID)

        assertThat(outcome).isEqualTo(WorkerSyncOutcome.Complete)
        assertThat(api.actionEntryIds).containsExactly(completedEntryId)
    }

    @Test
    fun `action conflict keeps authoritative snapshot visible until explicit acknowledgement`() = runTest {
        val now = System.currentTimeMillis()
        database.taskDao().upsertAll(listOf(inProgressTask(now)))
        val cipher = testPendingPayloadCipher()
        database.outboxDao().insert(actionOutbox(ENTRY_ID, "PAUSE", now, cipher))
        val currentEntry = completedTaskDetail(ENTRY_ID).copy(status = "PAUSED", completionAllowed = true)
        val api = ConflictingActionApi(json.encodeToString(conflictProblem(currentEntry)))
        val store = WorkerLocalStore(database, cipher, json)
        val coordinator = WorkerSyncCoordinator(
            gateway = WorkerGatewayClient(api, json),
            database = database,
            localStore = store,
            projections = WorkerProjectionWriter(database, json),
            mediaUploadPipeline = BlockedUploader(),
            json = json,
        )

        val outcome = coordinator.sync(USER_ID)

        assertThat(outcome).isEqualTo(
            WorkerSyncOutcome.Conflict(
                "Данные изменились. Обновите список заданий и повторите действие.",
            ),
        )
        assertThat(database.conflictDao().observeOpen(USER_ID).first()).hasSize(1)
        assertThat(database.outboxDao().pending(USER_ID)).isEmpty()
        assertThat(store.acknowledgeOpenConflicts(USER_ID)).isEqualTo(1)
        assertThat(database.conflictDao().observeOpen(USER_ID).first()).isEmpty()
    }

    @Test
    fun `legacy expired lease photo is retained and conflict stays visible when task is already done`() = runTest {
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

        assertThat(outcome).isEqualTo(
            WorkerSyncOutcome.Conflict(
                "Данные изменились. Обновите список заданий и повторите действие.",
            ),
        )
        assertThat(api.reservationCalls.get()).isEqualTo(1)
        assertThat(api.detailCalls.get()).isEqualTo(1)
        assertThat(uploader.calls).isEqualTo(0)
        assertThat(database.outboxDao().pending(USER_ID)).isEmpty()
        assertThat(database.evidenceDao().pending(USER_ID)).isEmpty()
        assertThat(database.conflictDao().observeOpen(USER_ID).first()).hasSize(1)
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

    private fun coordinator(
        api: WorkerGatewayApi,
        uploader: WorkerEvidenceUploader,
        cipher: PendingPayloadCipher,
    ) = WorkerSyncCoordinator(
        gateway = WorkerGatewayClient(api, json),
        database = database,
        localStore = WorkerLocalStore(database, cipher, json),
        projections = WorkerProjectionWriter(database, json),
        mediaUploadPipeline = uploader,
        json = json,
    )

    private fun completionOutbox(
        entryId: String,
        createdAt: Long,
        cipher: PendingPayloadCipher,
    ): WorkerOutboxEntity = actionOutbox(entryId, "COMPLETE", createdAt, cipher)

    private fun reservationOutbox(
        createdAt: Long,
        cipher: PendingPayloadCipher,
    ): WorkerOutboxEntity {
        val payload = PendingEvidenceReservation(
            operationId = EVIDENCE_ID,
            evidenceId = EVIDENCE_ID,
            routeIndex = 0,
            capturedAt = "2026-07-26T16:50:00Z",
            offlineLeaseId = "lease-11",
            contentType = "image/webp",
            sizeBytes = 128,
            sha256 = "a".repeat(64),
        )
        return WorkerOutboxEntity(
            operationId = EVIDENCE_ID,
            userId = USER_ID,
            entryId = ENTRY_ID,
            kind = WorkerLocalStore.OUTBOX_EVIDENCE_RESERVATION,
            encryptedPayload = cipher.encrypt(json.encodeToString(payload)),
            expectedVersion = null,
            state = WorkerLocalStore.OUTBOX_PENDING,
            retryCount = 0,
            createdAtEpochMillis = createdAt,
            updatedAtEpochMillis = createdAt,
            lastError = null,
        )
    }

    private fun actionOutbox(
        entryId: String,
        action: String,
        createdAt: Long,
        cipher: PendingPayloadCipher,
    ): WorkerOutboxEntity {
        val operationId = "operation-$action-$entryId"
        val payload = PendingWorkerAction(
            operationId = operationId,
            action = action,
            expectedVersion = 10,
            workerGroupId = null,
            occurredAt = "2026-07-26T16:50:00Z",
            offlineLeaseId = "lease-11",
        )
        return WorkerOutboxEntity(
            operationId = operationId,
            userId = USER_ID,
            entryId = entryId,
            kind = WorkerLocalStore.OUTBOX_ACTION,
            encryptedPayload = cipher.encrypt(json.encodeToString(payload)),
            expectedVersion = 10,
            state = WorkerLocalStore.OUTBOX_PENDING,
            retryCount = 0,
            createdAtEpochMillis = createdAt,
            updatedAtEpochMillis = createdAt,
            lastError = null,
        )
    }

    private fun completedTaskDetail(entryId: String) = WorkerTaskDetailDto(
        entryId = entryId,
        version = 11,
        taskId = "task-$entryId",
        routeIndex = 0,
        routeStepIndex = 0,
        routeStepCount = 1,
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
    )

    private fun conflictProblem(currentEntry: WorkerTaskDetailDto) = ApiProblemDto(
        type = "about:blank",
        title = "Conflict",
        status = 409,
        detail = "Задание уже изменено",
        code = "ENTRY_VERSION_CONFLICT",
        currentVersion = currentEntry.version,
        currentEntry = currentEntry,
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

        override suspend fun prepareWorkerProfileAvatarScope(): Response<WorkerProfileAvatarScopeDto> = unused()

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

        override suspend fun mediaAssets(
            ownerType: String,
            ownerId: String,
            warehouseId: String,
            context: String,
            limit: Int,
        ): Response<MediaAssetPageDto> = unused()

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

    /** Accepts completion commands and records exactly which independent entries reached the server. */
    private open class CompletionActionApi(
        private val completedEntry: WorkerTaskDetailDto,
    ) : FreshEmptyFeedApi() {
        val actionEntryIds = mutableListOf<String>()

        override suspend fun applyAction(
            entryId: String,
            idempotencyKey: String,
            request: WorkerActionRequestDto,
        ): Response<WorkerActionResultDto> {
            actionEntryIds += entryId
            return Response.success(
                WorkerActionResultDto(
                    outcome = "APPLIED",
                    currentVersion = completedEntry.version,
                    entry = completedEntry,
                ),
            )
        }
    }

    /** Makes one entry's reservation retryable while accepting another entry's command. */
    private class RetryingReservationCompletionApi(
        completedEntry: WorkerTaskDetailDto,
    ) : CompletionActionApi(completedEntry) {
        override suspend fun reserveEvidence(
            entryId: String,
            idempotencyKey: String,
            request: EvidenceReservationRequestDto,
        ): Response<TaskEvidenceDto> = Response.error(
            503,
            """{"type":"about:blank","title":"Unavailable","status":503,"detail":"Фото временно недоступно","code":"MEDIA_UNAVAILABLE"}"""
                .toResponseBody("application/problem+json".toMediaType()),
        )
    }

    /** Returns one canonical 409 Problem Details payload containing the authoritative entry. */
    private class ConflictingActionApi(
        private val problemJson: String,
    ) : FreshEmptyFeedApi() {
        override suspend fun applyAction(
            entryId: String,
            idempotencyKey: String,
            request: WorkerActionRequestDto,
        ): Response<WorkerActionResultDto> = Response.error(
            409,
            problemJson.toResponseBody("application/problem+json".toMediaType()),
        )
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
                    routeStepIndex = 0,
                    routeStepCount = 1,
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
