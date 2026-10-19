package dev.buhanzaz.rwms.worker.core.sync

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.PendingPayloadCipher
import dev.buhanzaz.rwms.worker.core.database.WorkerConflictEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerDatabase
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.database.WorkerOutboxEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import dev.buhanzaz.rwms.worker.core.network.WorkerAssignmentDto
import dev.buhanzaz.rwms.worker.core.network.WorkerCategoryDto
import dev.buhanzaz.rwms.worker.core.network.WorkerContextDto
import dev.buhanzaz.rwms.worker.core.network.WorkerFeedCategoryDto
import dev.buhanzaz.rwms.worker.core.network.WorkerFeedEntryDto
import dev.buhanzaz.rwms.worker.core.network.WorkerIdentityDto
import dev.buhanzaz.rwms.worker.core.network.WorkerOfflineLeaseDto
import dev.buhanzaz.rwms.worker.core.network.WorkerTaskDetailDto
import dev.buhanzaz.rwms.worker.core.network.WorkerTaskTimerSnapshotDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FullSyncConflictResolutionRobolectricTest {
    private lateinit var database: WorkerDatabase
    private lateinit var writer: WorkerProjectionWriter

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            WorkerDatabase::class.java,
        ).allowMainThreadQueries().build()
        writer = WorkerProjectionWriter(database, Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun changedFullFeedPreservesEveryOpenConflictUntilExplicitAcknowledgement() = runTest {
        database.conflictDao().upsert(conflict(USER_ID, "operation-current"))
        database.conflictDao().upsert(conflict("worker-other", "operation-other"))

        writer.commitContextAndFeed(
            context = context(),
            revision = 11,
            serverTime = "2026-07-26T10:00:00Z",
            categories = emptyList(),
            etag = "\"feed-11\"",
        )

        assertThat(database.conflictDao().observeOpen(USER_ID).first()).hasSize(1)
        assertThat(database.conflictDao().observeOpen("worker-other").first()).hasSize(1)
    }

    @Test
    fun validatedNotModifiedFeedRepairsLegacyTaskButOnlyUserAcknowledgementResolvesConflict() = runTest {
        val now = System.currentTimeMillis()
        database.taskDao().upsertAll(listOf(optimisticTask(now)))
        writer.applyDetail(USER_ID, authoritativeDetail())
        database.conflictDao().upsert(conflict(USER_ID, "operation-terminal"))

        writer.applyContext(context())

        assertThat(database.conflictDao().observeOpen(USER_ID).first()).hasSize(1)
        val repaired = database.taskDao().task(USER_ID, ENTRY_ID)
        assertThat(repaired?.status).isEqualTo("PAUSED")
        assertThat(repaired?.version).isEqualTo(8)
        assertThat(repaired?.locallyPending).isFalse()
        assertThat(
            database.assignmentDao().observeForEntry(USER_ID, ENTRY_ID).first()
                .single()
                .workerName,
        ).isEqualTo("Android Demo")

        val localStore = WorkerLocalStore(
            database,
            testPendingPayloadCipher(),
            Json { ignoreUnknownKeys = true },
        )
        assertThat(localStore.acknowledgeOpenConflicts(USER_ID)).isEqualTo(1)
        assertThat(database.conflictDao().observeOpen(USER_ID).first()).isEmpty()
    }

    @Test
    fun commandSnapshotAndOutboxCompletionAreCommittedTogether() = runTest {
        val now = System.currentTimeMillis()
        database.taskDao().upsertAll(listOf(optimisticTask(now)))
        database.outboxDao().insert(actionOutbox(now))

        writer.commitActionResult(
            userId = USER_ID,
            operationId = "operation-action",
            detail = authoritativeDetail(),
        )

        assertThat(database.outboxDao().pending(USER_ID)).isEmpty()
        val authoritative = database.taskDao().task(USER_ID, ENTRY_ID)
        assertThat(authoritative?.status).isEqualTo("PAUSED")
        assertThat(authoritative?.version).isEqualTo(8)
        assertThat(authoritative?.locallyPending).isFalse()
        assertThat(authoritative?.timerState).isEqualTo("PAUSED")
        assertThat(authoritative?.timerRemainingSeconds).isEqualTo(1_800)
    }

    @Test
    fun absentTerminalActionResponseRemovesStaleInProgressProjectionButKeepsNoReplay() = runTest {
        val now = System.currentTimeMillis()
        database.taskDao().upsertAll(listOf(optimisticTask(now)))
        database.outboxDao().insert(actionOutbox(now))
        writer.applyDetail(USER_ID, authoritativeDetail())

        writer.commitAbsentActionResult(
            userId = USER_ID,
            operationId = "operation-action",
            entryId = ENTRY_ID,
        )

        assertThat(database.taskDao().task(USER_ID, ENTRY_ID)).isNull()
        assertThat(database.detailDao().detail(USER_ID, ENTRY_ID)).isNull()
        assertThat(database.assignmentDao().observeForEntry(USER_ID, ENTRY_ID).first()).isEmpty()
        assertThat(database.outboxDao().pending(USER_ID)).isEmpty()
    }

    @Test
    fun terminalDetailSnapshotCannotLeaveAnInProgressBoardCardBehind() = runTest {
        val now = System.currentTimeMillis()
        database.taskDao().upsertAll(listOf(optimisticTask(now)))

        writer.applyDetail(USER_ID, authoritativeDetail().copy(status = "DONE"))

        assertThat(database.taskDao().task(USER_ID, ENTRY_ID)).isNull()
        assertThat(database.detailDao().detail(USER_ID, ENTRY_ID)?.version).isEqualTo(8)
    }

    @Test
    fun fullFeedReplacesAuthorizedQueueProjectionAndKeepsEmptyQueues() = runTest {
        val repairs = category("repair", "Ремонты", sortOrder = 10)
        val furniture = category("furniture", "Перемещение мебели", sortOrder = 20)
        writer.commitContextAndFeed(
            context = context(categories = listOf(furniture, repairs)),
            revision = 11,
            serverTime = "2026-07-26T10:00:00Z",
            categories = listOf(
                WorkerFeedCategoryDto(furniture, entries = emptyList()),
                WorkerFeedCategoryDto(repairs, entries = emptyList()),
            ),
            etag = "\"feed-11\"",
        )

        assertThat(database.categoryDao().observeCategories(USER_ID).first().map { it.queueId })
            .containsExactly("repair", "furniture")
            .inOrder()

        val electric = category("electric", "Электрики", sortOrder = 5)
        writer.commitContextAndFeed(
            context = context(categories = listOf(electric), revision = 12),
            revision = 12,
            serverTime = "2026-07-26T10:01:00Z",
            categories = listOf(WorkerFeedCategoryDto(electric, entries = emptyList())),
            etag = "\"feed-12\"",
        )

        assertThat(database.categoryDao().observeCategories(USER_ID).first().map { it.queueId })
            .containsExactly("electric")
    }

    @Test
    fun feedAndDetailKeepRawRouteIdentitySeparateFromWorkerPackageOrdinal() = runTest {
        val repairs = category("repair", "Ремонты", sortOrder = 10)
        val entry = WorkerFeedEntryDto(
            entryId = ENTRY_ID,
            version = 11,
            taskId = "task",
            routeIndex = 41,
            routeStepIndex = 1,
            routeStepCount = 3,
            entryType = "SHADOW",
            pinned = true,
            title = "Средний ремонт",
            unitNumber = "БТ-42",
            taskText = null,
            scheduledDate = "2026-07-26",
            deadlineAt = null,
            priority = 2,
            queuePosition = 0,
            status = "WAITING",
            availabilityMode = "AVAILABLE",
            plannedDurationMinutes = 90,
            activeStartedAt = null,
            activeWorkSeconds = 0,
            assignments = emptyList(),
            readyEvidenceCount = 2,
            resultPhotoMinCount = 1,
        )

        writer.commitContextAndFeed(
            context = context(categories = listOf(repairs)),
            revision = 11,
            serverTime = "2026-07-26T10:00:00Z",
            categories = listOf(WorkerFeedCategoryDto(repairs, entries = listOf(entry))),
            etag = "\"feed-11\"",
        )
        writer.applyDetail(
            USER_ID,
            authoritativeDetail().copy(
                routeIndex = 73,
                routeStepIndex = 2,
                routeStepCount = 4,
            ),
        )

        val persisted = database.taskDao().task(USER_ID, ENTRY_ID)
        assertThat(persisted?.routeIndex).isEqualTo(73)
        assertThat(persisted?.routeStepIndex).isEqualTo(2)
        assertThat(persisted?.routeStepCount).isEqualTo(4)
        assertThat(persisted?.entryType).isEqualTo("SHADOW")
        assertThat(persisted?.pinned).isTrue()
    }

    private fun context(
        categories: List<WorkerCategoryDto> = emptyList(),
        revision: Long = 11,
    ) = WorkerContextDto(
        worker = WorkerIdentityDto(
            id = USER_ID,
            warehouseId = "warehouse",
            login = "test228",
            displayName = "Тест 228",
        ),
        groups = emptyList(),
        qualifications = emptyList(),
        categories = categories,
        kpiPalette = null,
        serverTime = "2026-07-26T10:00:00Z",
        revision = revision,
        offlineLease = WorkerOfflineLeaseDto(
            id = "lease",
            issuedAt = "2026-07-26T10:00:00Z",
            expiresAt = "2026-07-27T10:00:00Z",
            syncRevision = revision,
        ),
    )

    private fun category(queueId: String, name: String, sortOrder: Int) = WorkerCategoryDto(
        queueId = queueId,
        name = name,
        type = "REPAIR",
        queuePurpose = "GENERAL",
        groupIds = emptyList(),
        sortOrder = sortOrder,
        audienceModes = listOf("AVAILABLE"),
        resultPhotoMinCount = 1,
    )

    private fun conflict(userId: String, operationId: String) = WorkerConflictEntity(
        operationId = operationId,
        userId = userId,
        entryId = ENTRY_ID,
        code = "ENTRY_VERSION_CONFLICT",
        message = "Задание уже изменено",
        currentVersion = 8,
        encryptedCurrentEntry = null,
        createdAtEpochMillis = 1,
        resolvedAtEpochMillis = null,
    )

    private fun testPendingPayloadCipher(): PendingPayloadCipher {
        val constructor = PendingPayloadCipher::class.java.getDeclaredConstructor(SecretKey::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(SecretKeySpec(ByteArray(32) { 0x2a }, "AES"))
    }

    private fun optimisticTask(now: Long) = WorkerTaskEntity(
        localId = "$USER_ID:$ENTRY_ID",
        userId = USER_ID,
        entryId = ENTRY_ID,
        taskId = "task",
        version = 9,
        categoryId = "category",
        categoryName = "Перемещение",
        categorySortOrder = 0,
        title = "Переместить бытовку",
        unitNumber = "БТ-42",
        taskText = null,
        scheduledDate = "2026-07-26",
        deadlineAt = null,
        priority = 0,
        queuePosition = 0,
        status = "IN_PROGRESS",
        availabilityMode = "AVAILABLE",
        plannedDurationMinutes = null,
        activeStartedAt = null,
        activeWorkSeconds = 0,
        readyEvidenceCount = 0,
        resultPhotoMinCount = 0,
        lastServerRevision = 10,
        locallyPending = true,
        updatedAtEpochMillis = now,
    )

    private fun actionOutbox(now: Long) = WorkerOutboxEntity(
        operationId = "operation-action",
        userId = USER_ID,
        entryId = ENTRY_ID,
        kind = "ACTION",
        encryptedPayload = "encrypted",
        expectedVersion = 8,
        state = "PENDING",
        retryCount = 0,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
        lastError = null,
    )

    private fun authoritativeDetail() = WorkerTaskDetailDto(
        entryId = ENTRY_ID,
        version = 8,
        taskId = "task",
        routeIndex = 0,
        routeStepIndex = 0,
        routeStepCount = 1,
        title = "Переместить бытовку",
        description = null,
        taskObject = null,
        taskText = null,
        scheduledDate = "2026-07-26",
        deadlineAt = null,
        priority = 0,
        queuePosition = 0,
        status = "PAUSED",
        availabilityMode = "AVAILABLE",
        plannedDurationMinutes = null,
        activeStartedAt = null,
        activeWorkSeconds = 0,
        audienceSelectors = emptyList(),
        assignments = listOf(
            WorkerAssignmentDto(
                id = "assignment",
                workerId = "worker-other",
                workerName = "Android Demo",
                workerGroupId = "group",
                workerGroupName = "Разнорабочие",
                status = "PAUSED",
                assignedAt = "2026-07-26T09:00:00Z",
                startedAt = "2026-07-26T09:01:00Z",
                pausedAt = "2026-07-26T09:30:00Z",
                finishedAt = null,
            ),
        ),
        materials = emptyList(),
        comments = emptyList(),
        sourceMedia = emptyList(),
        evidence = emptyList(),
        relatedSteps = emptyList(),
        resultPhotoMinCount = 0,
        completionAllowed = false,
        timerSnapshot = WorkerTaskTimerSnapshotDto(
            countedActiveSeconds = 1_800,
            remainingSeconds = 1_800,
            remainingPercent = 50.0,
            timerState = "PAUSED",
            nextTransitionAt = null,
            serverTime = "2026-07-26T09:30:00Z",
        ),
    )

    private companion object {
        const val USER_ID = "worker-current"
        const val ENTRY_ID = "entry-current"
    }
}
