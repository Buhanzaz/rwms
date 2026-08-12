package dev.buhanzaz.rwms.driver.core.sync

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.database.DriverConflictEntity
import dev.buhanzaz.rwms.driver.core.database.DriverDatabase
import dev.buhanzaz.rwms.driver.core.database.DriverOutboxEntity
import dev.buhanzaz.rwms.driver.core.database.DriverTaskEntity
import dev.buhanzaz.rwms.driver.core.network.DriverAssignmentDto
import dev.buhanzaz.rwms.driver.core.network.DriverCategoryDto
import dev.buhanzaz.rwms.driver.core.network.DriverContextDto
import dev.buhanzaz.rwms.driver.core.network.DriverTaskAudienceDto
import dev.buhanzaz.rwms.driver.core.network.DriverFeedCategoryDto
import dev.buhanzaz.rwms.driver.core.network.DriverFeedEntryDto
import dev.buhanzaz.rwms.driver.core.network.DriverIdentityDto
import dev.buhanzaz.rwms.driver.core.network.DriverOfflineLeaseDto
import dev.buhanzaz.rwms.driver.core.network.DriverTaskDetailDto
import dev.buhanzaz.rwms.driver.core.network.DriverTaskTimerSnapshotDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FullSyncConflictResolutionRobolectricTest {
    private lateinit var database: DriverDatabase
    private lateinit var writer: DriverProjectionWriter

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            DriverDatabase::class.java,
        ).allowMainThreadQueries().build()
        writer = DriverProjectionWriter(database, Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun changedFullFeedResolvesOnlyCurrentUsersOpenConflicts() = runTest {
        database.conflictDao().upsert(conflict(USER_ID, "operation-current"))
        database.conflictDao().upsert(conflict("driver-other", "operation-other"))

        writer.commitContextAndFeed(
            context = context(),
            revision = 11,
            serverTime = "2026-07-26T10:00:00Z",
            categories = emptyList(),
            etag = "\"feed-11\"",
        )

        assertThat(database.conflictDao().observeOpen(USER_ID).first()).isEmpty()
        assertThat(database.conflictDao().observeOpen("driver-other").first()).hasSize(1)
    }

    @Test
    fun validatedNotModifiedFeedRepairsLegacyOptimisticTaskAndResolvesConflicts() = runTest {
        val now = System.currentTimeMillis()
        database.taskDao().upsertAll(listOf(optimisticTask(now)))
        writer.applyDetail(USER_ID, authoritativeDetail())
        database.conflictDao().upsert(conflict(USER_ID, "operation-terminal"))

        writer.applyContext(context())

        assertThat(database.conflictDao().observeOpen(USER_ID).first()).isEmpty()
        val repaired = database.taskDao().task(USER_ID, ENTRY_ID)
        assertThat(repaired?.status).isEqualTo("PAUSED")
        assertThat(repaired?.version).isEqualTo(8)
        assertThat(repaired?.locallyPending).isFalse()
        assertThat(
            database.assignmentDao().observeForEntry(USER_ID, ENTRY_ID).first()
                .single()
                .driverName,
        ).isEqualTo("Android Demo")
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
                DriverFeedCategoryDto(furniture, entries = emptyList()),
                DriverFeedCategoryDto(repairs, entries = emptyList()),
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
            categories = listOf(DriverFeedCategoryDto(electric, entries = emptyList())),
            etag = "\"feed-12\"",
        )

        assertThat(database.categoryDao().observeCategories(USER_ID).first().map { it.queueId })
            .containsExactly("electric")
    }

    @Test
    fun fullFeedPersistsServerOwnedDriverAudienceClassification() = runTest {
        val drivers = category("drivers", "Водители", sortOrder = 5).copy(
            type = "MOVEMENT",
            queuePurpose = "LOGISTICS_DRIVER",
        )
        writer.commitContextAndFeed(
            context = context(categories = listOf(drivers)),
            revision = 12,
            serverTime = "2026-08-10T10:00:00Z",
            categories = listOf(
                DriverFeedCategoryDto(
                    category = drivers,
                    entries = listOf(driverFeedEntry()),
                ),
            ),
            etag = "\"feed-12\"",
        )

        assertThat(database.taskDao().task(USER_ID, ENTRY_ID)?.driverAudienceMode)
            .isEqualTo("ASSIGNED_DRIVER")
    }

    private fun context(
        categories: List<DriverCategoryDto> = emptyList(),
        revision: Long = 11,
    ) = DriverContextDto(
        driver = DriverIdentityDto(
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
        offlineLease = DriverOfflineLeaseDto(
            id = "lease",
            issuedAt = "2026-07-26T10:00:00Z",
            expiresAt = "2026-07-27T10:00:00Z",
            syncRevision = revision,
        ),
    )

    private fun category(queueId: String, name: String, sortOrder: Int) = DriverCategoryDto(
        queueId = queueId,
        name = name,
        type = "REPAIR",
        queuePurpose = "GENERAL",
        groupIds = emptyList(),
        sortOrder = sortOrder,
        audienceModes = listOf("AVAILABLE"),
        resultPhotoMinCount = 1,
    )

    private fun driverFeedEntry() = DriverFeedEntryDto(
        entryId = ENTRY_ID,
        version = 4,
        taskId = "task",
        routeIndex = 0,
        title = "Отгрузить бытовку",
        unitNumber = "БТ-1",
        taskText = null,
        scheduledDate = "2026-08-10",
        deadlineAt = null,
        priority = 3,
        queuePosition = 0,
        status = "WAITING",
        availabilityMode = "AVAILABLE",
        plannedDurationMinutes = null,
        activeStartedAt = null,
        activeWorkSeconds = 0,
        assignments = emptyList(),
        readyEvidenceCount = 0,
        resultPhotoMinCount = 1,
        driverAudience = DriverTaskAudienceDto(
            mode = "ASSIGNED_DRIVER",
            driverId = "11111111-1111-1111-1111-111111111111",
            driverName = "Водитель",
        ),
        timerSnapshot = null,
    )

    private fun conflict(userId: String, operationId: String) = DriverConflictEntity(
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

    private fun optimisticTask(now: Long) = DriverTaskEntity(
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

    private fun actionOutbox(now: Long) = DriverOutboxEntity(
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

    private fun authoritativeDetail() = DriverTaskDetailDto(
        entryId = ENTRY_ID,
        version = 8,
        taskId = "task",
        routeIndex = 0,
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
            DriverAssignmentDto(
                id = "assignment",
                driverId = "driver-other",
                driverName = "Android Demo",
                driverGroupId = "group",
                driverGroupName = "Разнорабочие",
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
        timerSnapshot = DriverTaskTimerSnapshotDto(
            countedActiveSeconds = 1_800,
            remainingSeconds = 1_800,
            remainingPercent = 50.0,
            timerState = "PAUSED",
            nextTransitionAt = null,
            serverTime = "2026-07-26T09:30:00Z",
        ),
    )

    private companion object {
        const val USER_ID = "driver-current"
        const val ENTRY_ID = "entry-current"
    }
}
