package dev.buhanzaz.rwms.worker.core.sync

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.PendingEvidenceReservation
import dev.buhanzaz.rwms.worker.core.database.PendingPayloadCipher
import dev.buhanzaz.rwms.worker.core.database.PendingWorkerAction
import dev.buhanzaz.rwms.worker.core.database.PendingWorkerProblemReport
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerDatabase
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.database.WorkerOutboxEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerProblemReportStore
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.media.EvidenceUploadResult
import dev.buhanzaz.rwms.worker.core.media.WorkerEvidenceUploader
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
import dev.buhanzaz.rwms.worker.core.network.WorkerCategoryDto
import dev.buhanzaz.rwms.worker.core.network.WorkerContextDto
import dev.buhanzaz.rwms.worker.core.network.WorkerDeviceRegistrationDto
import dev.buhanzaz.rwms.worker.core.network.WorkerDeviceRegistrationRequestDto
import dev.buhanzaz.rwms.worker.core.network.WorkerFeedCategoryDto
import dev.buhanzaz.rwms.worker.core.network.WorkerFeedEntryDto
import dev.buhanzaz.rwms.worker.core.network.WorkerFeedDto
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayApi
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import dev.buhanzaz.rwms.worker.core.network.WorkerIdentityDto
import dev.buhanzaz.rwms.worker.core.network.WorkerOfflineLeaseDto
import dev.buhanzaz.rwms.worker.core.network.WorkerProblemReportDto
import dev.buhanzaz.rwms.worker.core.network.WorkerProblemReportRequestDto
import dev.buhanzaz.rwms.worker.core.network.WorkerProfileAvatarScopeDto
import dev.buhanzaz.rwms.worker.core.network.WorkerTaskDetailDto
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
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
class WorkerProblemReportSyncRobolectricTest {
    private lateinit var database: WorkerDatabase
    private lateinit var json: Json
    private lateinit var cipher: PendingPayloadCipher

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            WorkerDatabase::class.java,
        ).allowMainThreadQueries().build()
        json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        cipher = testPendingPayloadCipher()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `posts every report photo once before uploads and completion`() = runTest {
        val now = System.currentTimeMillis()
        database.taskDao().upsertAll(listOf(task(now, resultPhotoMinCount = 0)))
        val report = reportOutbox(now, WorkerProblemReportStore.OUTBOX_PENDING, listOf(PHOTO_A, PHOTO_B))
        database.outboxDao().insert(report)
        listOf(PHOTO_A, PHOTO_B).forEach { database.evidenceDao().upsert(reportEvidence(it, now, "CAPTURED")) }
        database.outboxDao().insert(completionOutbox(now + 1))
        val api = ReportApi()
        val uploader = RecordingUploader(api.events)

        assertThat(coordinator(api, uploader).sync(USER)).isEqualTo(WorkerSyncOutcome.Complete)

        assertThat(api.reportRequests).hasSize(1)
        assertThat(api.reportRequests.single().attachments.map { it.evidenceId })
            .containsExactly(PHOTO_A, PHOTO_B)
        assertThat(api.events.indexOf("report")).isLessThan(api.events.indexOf("upload:$PHOTO_A"))
        assertThat(api.events.indexOf("report")).isLessThan(api.events.indexOf("upload:$PHOTO_B"))
        assertThat(api.events.indexOf("complete")).isGreaterThan(api.events.indexOf("upload:$PHOTO_A"))
        assertThat(api.events.indexOf("complete")).isGreaterThan(api.events.indexOf("upload:$PHOTO_B"))
    }

    @Test
    fun `failed report post retains its declaration and blocks linked completion`() = runTest {
        val now = System.currentTimeMillis()
        database.taskDao().upsertAll(listOf(task(now, resultPhotoMinCount = 0)))
        database.outboxDao().insert(reportOutbox(now, WorkerProblemReportStore.OUTBOX_PENDING, listOf(PHOTO_A)))
        database.evidenceDao().upsert(reportEvidence(PHOTO_A, now, "CAPTURED"))
        database.outboxDao().insert(completionOutbox(now + 1))
        val api = ReportApi().apply { postFailure = unavailableProblem() }
        val uploader = RecordingUploader(api.events)

        val outcome = coordinator(api, uploader).sync(USER)

        assertThat(outcome).isInstanceOf(WorkerSyncOutcome.Retry::class.java)
        assertThat(api.reportRequests).hasSize(1)
        assertThat(api.events).doesNotContain("complete")
        assertThat(uploader.calls.get()).isEqualTo(0)
        assertThat(database.outboxDao().problemReport(USER, REPORT_ID)?.state)
            .isEqualTo(WorkerProblemReportStore.OUTBOX_RETRY)
        assertThat(database.evidenceDao().forProblemReport(USER, REPORT_ID).single().state).isEqualTo("CAPTURED")
        assertThat(database.outboxDao().pending(USER).map { it.kind }).contains(WorkerLocalStore.OUTBOX_ACTION)
    }

    @Test
    fun `ready report photo never opens the result photo completion gate`() = runTest {
        val now = System.currentTimeMillis()
        database.taskDao().upsertAll(listOf(task(now, resultPhotoMinCount = 1)))
        database.outboxDao().insert(reportOutbox(now, WorkerProblemReportStore.OUTBOX_REPORTED, listOf(PHOTO_A)))
        database.evidenceDao().upsert(reportEvidence(PHOTO_A, now, "READY"))
        database.outboxDao().insert(completionOutbox(now + 1))
        val api = ReportApi()

        val outcome = coordinator(api, RecordingUploader(api.events)).sync(USER)

        assertThat(outcome).isEqualTo(
            WorkerSyncOutcome.Deferred("Завершение $ENTRY ждёт обработки фотографии"),
        )
        assertThat(api.events).doesNotContain("complete")
        assertThat(database.outboxDao().pending(USER).map { it.kind })
            .containsExactly(WorkerLocalStore.OUTBOX_ACTION)
    }

    @Test
    fun `closed task refreshes own report and transient refresh keeps it retryable`() = runTest {
        val now = System.currentTimeMillis()
        database.outboxDao().insert(reportOutbox(now, WorkerProblemReportStore.OUTBOX_REPORTED, listOf(PHOTO_A)))
        database.evidenceDao().upsert(reportEvidence(PHOTO_A, now, "PROCESSING"))
        val api = ReportApi().apply { includeActiveEntry = false }

        assertThat(coordinator(api, RecordingUploader(api.events)).sync(USER)).isEqualTo(WorkerSyncOutcome.Complete)

        assertThat(api.ownReportCalls.get()).isEqualTo(1)
        assertThat(api.detailCalls.get()).isEqualTo(0)
        assertThat(database.evidenceDao().evidence(USER, PHOTO_A)?.state).isEqualTo("READY")
        assertThat(database.outboxDao().problemReport(USER, REPORT_ID)?.state)
            .isEqualTo(WorkerProblemReportStore.OUTBOX_REPORTED)

        database.evidenceDao().upsert(reportEvidence(PHOTO_A, now + 1, "PROCESSING"))
        api.ownReportFailure = unavailableProblem()

        assertThat(coordinator(api, RecordingUploader(api.events)).sync(USER)).isInstanceOf(WorkerSyncOutcome.Retry::class.java)
        assertThat(database.outboxDao().problemReport(USER, REPORT_ID)?.state)
            .isEqualTo(WorkerProblemReportStore.OUTBOX_REPORTED)
    }

    private fun coordinator(api: WorkerGatewayApi, uploader: WorkerEvidenceUploader) = WorkerSyncCoordinator(
        gateway = WorkerGatewayClient(api, json),
        database = database,
        localStore = WorkerLocalStore(database, cipher, json),
        projections = WorkerProjectionWriter(database, json),
        mediaUploadPipeline = uploader,
        json = json,
    )

    private fun task(now: Long, resultPhotoMinCount: Int) = WorkerTaskEntity(
        localId = "$USER:$ENTRY",
        userId = USER,
        entryId = ENTRY,
        taskId = TASK,
        version = 4,
        categoryId = "category",
        categoryName = "Очередь",
        categorySortOrder = 0,
        title = "Задание",
        unitNumber = null,
        taskText = null,
        scheduledDate = "2026-09-09",
        deadlineAt = null,
        priority = 0,
        queuePosition = 0,
        status = "IN_PROGRESS",
        availabilityMode = "AVAILABLE",
        plannedDurationMinutes = null,
        activeStartedAt = null,
        activeWorkSeconds = 0,
        readyEvidenceCount = 0,
        resultPhotoMinCount = resultPhotoMinCount,
        lastServerRevision = 1,
        locallyPending = true,
        updatedAtEpochMillis = now,
    )

    private fun reportOutbox(now: Long, state: String, attachmentIds: List<String>): WorkerOutboxEntity {
        val payload = PendingWorkerProblemReport(
            operationId = REPORT_ID,
            routeIndex = 0,
            comment = "Неисправность",
            occurredAt = OCCURRED_AT,
            offlineLeaseId = LEASE,
            attachments = attachmentIds.map { evidenceId -> pendingEvidence(evidenceId) },
        )
        return WorkerOutboxEntity(
            operationId = REPORT_ID,
            userId = USER,
            entryId = ENTRY,
            kind = WorkerProblemReportStore.OUTBOX_PROBLEM_REPORT,
            encryptedPayload = cipher.encrypt(json.encodeToString(payload)),
            expectedVersion = null,
            state = state,
            retryCount = 0,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            lastError = null,
        )
    }

    private fun completionOutbox(now: Long): WorkerOutboxEntity {
        val payload = PendingWorkerAction(
            operationId = COMPLETE_OPERATION,
            action = "COMPLETE",
            expectedVersion = 4,
            workerGroupId = null,
            occurredAt = OCCURRED_AT,
            offlineLeaseId = LEASE,
        )
        return WorkerOutboxEntity(
            operationId = COMPLETE_OPERATION,
            userId = USER,
            entryId = ENTRY,
            kind = WorkerLocalStore.OUTBOX_ACTION,
            encryptedPayload = cipher.encrypt(json.encodeToString(payload)),
            expectedVersion = 4,
            state = WorkerLocalStore.OUTBOX_PENDING,
            retryCount = 0,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            lastError = null,
        )
    }

    private fun reportEvidence(evidenceId: String, now: Long, state: String) = TaskEvidenceEntity(
        evidenceId = evidenceId,
        userId = USER,
        entryId = ENTRY,
        routeIndex = 0,
        capturedAt = OCCURRED_AT,
        encryptedFilePath = "encrypted/$evidenceId",
        fileName = "$evidenceId.webp",
        contentType = "image/webp",
        sizeBytes = 128,
        sha256 = "a".repeat(64),
        reservationOperationId = evidenceId,
        uploadOperationId = "upload-$evidenceId",
        state = state,
        mediaId = null,
        mediaGeneration = null,
        reviewReason = null,
        uploadPercent = 0,
        lastError = null,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
        variantManifestJson = "[]",
        problemReportId = REPORT_ID,
    )

    private fun pendingEvidence(evidenceId: String) = PendingEvidenceReservation(
        operationId = evidenceId,
        evidenceId = evidenceId,
        routeIndex = 0,
        capturedAt = OCCURRED_AT,
        offlineLeaseId = LEASE,
        contentType = "image/webp",
        sizeBytes = 128,
        sha256 = "a".repeat(64),
    )

    private fun reportDto(attachments: List<TaskEvidenceDto>) = WorkerProblemReportDto(
        reportId = REPORT_ID,
        entryId = ENTRY,
        taskId = TASK,
        routeIndex = 0,
        entryTitle = "Задание",
        comment = "Неисправность",
        occurredAt = OCCURRED_AT,
        recordedAt = OCCURRED_AT,
        attachments = attachments,
    )

    private fun remoteEvidence(evidenceId: String, state: String) = TaskEvidenceDto(
        evidenceId = evidenceId,
        version = 0,
        entryId = ENTRY,
        routeIndex = 0,
        workerId = USER,
        workerGroupId = null,
        capturedAt = OCCURRED_AT,
        recordedAt = OCCURRED_AT,
        state = state,
        mediaId = if (state == "READY") "media-$evidenceId" else null,
        mediaGeneration = if (state == "READY") 1 else null,
        reviewReason = null,
    )

    private fun detail() = WorkerTaskDetailDto(
        entryId = ENTRY,
        version = 5,
        taskId = TASK,
        routeIndex = 0,
        routeStepIndex = 0,
        routeStepCount = 1,
        title = "Задание",
        description = null,
        taskObject = null,
        taskText = null,
        scheduledDate = "2026-09-09",
        deadlineAt = null,
        priority = 0,
        queuePosition = 0,
        status = "DONE",
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
        resultPhotoMinCount = 0,
        completionAllowed = false,
    )

    private fun unavailableProblem(): Response<WorkerProblemReportDto> = Response.error(
        503,
        """{"type":"about:blank","title":"Unavailable","status":503,"detail":"Temporary"}"""
            .toResponseBody("application/problem+json".toMediaType()),
    )

    private fun testPendingPayloadCipher(): PendingPayloadCipher {
        val constructor = PendingPayloadCipher::class.java.getDeclaredConstructor(SecretKey::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(SecretKeySpec(ByteArray(32) { 0x2a }, "AES"))
    }

    private class RecordingUploader(private val events: MutableList<String>) : WorkerEvidenceUploader {
        val calls = AtomicInteger()

        override suspend fun uploadReservedEvidence(userId: String, evidence: TaskEvidenceEntity): EvidenceUploadResult {
            calls.incrementAndGet()
            events += "upload:${evidence.evidenceId}"
            return EvidenceUploadResult.Ready("media-${evidence.evidenceId}", 1)
        }
    }

    private inner class ReportApi : WorkerGatewayApi {
        val events = mutableListOf<String>()
        val reportRequests = mutableListOf<WorkerProblemReportRequestDto>()
        val ownReportCalls = AtomicInteger()
        val detailCalls = AtomicInteger()
        var postFailure: Response<WorkerProblemReportDto>? = null
        var ownReportFailure: Response<WorkerProblemReportDto>? = null
        var includeActiveEntry = true

        override suspend fun workerContext(): Response<WorkerContextDto> = Response.success(
            WorkerContextDto(
                worker = WorkerIdentityDto(USER, "warehouse", "worker", "Worker"),
                groups = emptyList(),
                qualifications = emptyList(),
                categories = emptyList(),
                kpiPalette = null,
                serverTime = OCCURRED_AT,
                revision = 1,
                offlineLease = WorkerOfflineLeaseDto(LEASE, OCCURRED_AT, "2026-09-10T10:00:00Z", 1),
            ),
        )

        override suspend fun workerFeed(cursor: String?, limit: Int, ifNoneMatch: String?): Response<WorkerFeedDto> =
            Response.success(
                WorkerFeedDto(
                    revision = 4,
                    serverTime = OCCURRED_AT,
                    categories = if (includeActiveEntry) {
                        listOf(
                            WorkerFeedCategoryDto(
                                category = WorkerCategoryDto(
                                    queueId = "category",
                                    name = "Очередь",
                                    type = "REPAIR",
                                    queuePurpose = "GENERAL",
                                    groupIds = emptyList(),
                                    sortOrder = 0,
                                    audienceModes = listOf("AVAILABLE"),
                                    resultPhotoMinCount = 0,
                                ),
                                entries = listOf(
                                    WorkerFeedEntryDto(
                                        entryId = ENTRY,
                                        version = 4,
                                        taskId = TASK,
                                        routeIndex = 0,
                                        routeStepIndex = 0,
                                        routeStepCount = 1,
                                        entryType = "DIRECT",
                                        pinned = false,
                                        title = "Задание",
                                        unitNumber = null,
                                        taskText = null,
                                        scheduledDate = "2026-09-09",
                                        deadlineAt = null,
                                        priority = 0,
                                        queuePosition = 0,
                                        status = "IN_PROGRESS",
                                        availabilityMode = "AVAILABLE",
                                        plannedDurationMinutes = null,
                                        activeStartedAt = null,
                                        activeWorkSeconds = 0,
                                        assignments = emptyList(),
                                        readyEvidenceCount = 0,
                                        resultPhotoMinCount = 0,
                                    ),
                                ),
                            ),
                        )
                    } else {
                        emptyList()
                    },
                    nextCursor = null,
                ),
            )

        override suspend fun createProblemReport(
            entryId: String,
            idempotencyKey: String,
            request: WorkerProblemReportRequestDto,
        ): Response<WorkerProblemReportDto> {
            events += "report"
            reportRequests += request
            return postFailure ?: Response.success(reportDto(request.attachments.map { remoteEvidence(it.evidenceId, "RESERVED") }))
        }

        override suspend fun workerProblemReport(reportId: String): Response<WorkerProblemReportDto> {
            ownReportCalls.incrementAndGet()
            return ownReportFailure ?: Response.success(reportDto(listOf(remoteEvidence(PHOTO_A, "READY"))))
        }

        override suspend fun workerTaskDetail(entryId: String): Response<WorkerTaskDetailDto> {
            detailCalls.incrementAndGet()
            return Response.success(detail())
        }

        override suspend fun applyAction(
            entryId: String,
            idempotencyKey: String,
            request: WorkerActionRequestDto,
        ): Response<WorkerActionResultDto> {
            events += "complete"
            return Response.success(WorkerActionResultDto("APPLIED", 5, detail()))
        }

        override suspend fun prepareWorkerProfileAvatarScope(): Response<WorkerProfileAvatarScopeDto> = unused()
        override suspend fun reserveEvidence(entryId: String, idempotencyKey: String, request: EvidenceReservationRequestDto): Response<TaskEvidenceDto> = unused()
        override suspend fun registerDevice(installationId: String, request: WorkerDeviceRegistrationRequestDto): Response<WorkerDeviceRegistrationDto> = unused()
        override suspend fun unregisterDevice(installationId: String): Response<Unit> = unused()
        override suspend fun createUploadSession(idempotencyKey: String, request: CreateUploadSessionRequestDto): Response<UploadSessionDto> = unused()
        override suspend fun mediaAssets(ownerType: String, ownerId: String, warehouseId: String, context: String, limit: Int): Response<MediaAssetPageDto> = unused()
        override suspend fun uploadMediaContent(sameOriginContentPath: String, idempotencyKey: String, content: RequestBody): Response<UploadedObjectDto> = unused()
        override suspend fun finalizeUploadSession(uploadSessionId: String, idempotencyKey: String, request: FinalizeUploadRequestDto): Response<MediaAssetDto> = unused()
        override suspend fun mediaContent(sameOriginMediaPath: String): Response<ResponseBody> = unused()

        private fun <T> unused(): Response<T> = error("Unexpected gateway call")
    }

    private companion object {
        const val USER = "worker"
        const val ENTRY = "entry"
        const val TASK = "task"
        const val LEASE = "lease"
        const val REPORT_ID = "00000000-0000-0000-0000-000000000001"
        const val PHOTO_A = "00000000-0000-0000-0000-000000000002"
        const val PHOTO_B = "00000000-0000-0000-0000-000000000003"
        const val COMPLETE_OPERATION = "00000000-0000-0000-0000-000000000004"
        const val OCCURRED_AT = "2026-09-09T10:00:00Z"
    }
}
