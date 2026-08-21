package dev.buhanzaz.rwms.worker.feature.tasks

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.WorkerCategoryEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerAssignmentEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerGroupEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import org.junit.Test

class TaskQueueSectionsTest {
    @Test
    fun `board title and cabin task headings use product language`() {
        assertThat(TASK_BOARD_TITLE).isEqualTo("Доска задач")
        assertThat(taskCardTitle("БТ-42")).isEqualTo("БТ-42")
        assertThat(taskCardTitle(null)).isEqualTo("Задание")
        assertThat(taskCardTitle("550e8400-e29b-41d4-a716-446655440000")).isEqualTo("Задание")
    }

    @Test
    fun `technical maintenance queue label is matched without case or spacing sensitivity`() {
        assertThat(taskBoardQueueLabel("Maintenance repair")).isEqualTo("Работы")
        assertThat(taskBoardQueueLabel("  MAINTENANCE   REPAIR ")).isEqualTo("Работы")
        assertThat(taskBoardQueueLabel("Maintenance rapair")).isEqualTo("Работы")
        assertThat(taskBoardQueueLabel("Погрузка")).isEqualTo("Погрузка")
    }

    @Test
    fun `refresh animation requires a recent active synchronization stage`() {
        val now = 1_000_000L

        assertThat(shouldAnimateTaskBoardRefresh("CONTEXT", now - 1_000, now)).isTrue()
        assertThat(shouldAnimateTaskBoardRefresh("UPLOAD", now - 60_000, now)).isTrue()
        assertThat(shouldAnimateTaskBoardRefresh("CONTEXT", now - 60_001, now)).isFalse()
        assertThat(shouldAnimateTaskBoardRefresh("IDLE", now, now)).isFalse()
        assertThat(shouldAnimateTaskBoardRefresh("WAITING_FOR_EVIDENCE", now, now)).isFalse()
        assertThat(shouldAnimateTaskBoardRefresh("CONTEXT", now + 1, now)).isFalse()
    }

    @Test
    fun `board notice hides progress chatter but keeps offline and blocked truth`() {
        assertThat(taskBoardSyncNotice(true, "CONTEXT", "Проверяем доступ")).isNull()
        assertThat(taskBoardSyncNotice(false, "CONTEXT", "Проверяем доступ"))
            .isEqualTo("Нет связи с RWMS")
        assertThat(taskBoardSyncNotice(true, "WAITING_FOR_EVIDENCE", "Фото ожидает отправки"))
            .isEqualTo("Фото ожидает отправки")
    }

    @Test
    fun rendersEveryAuthorizedQueueInServerOrderIncludingEmptyQueues() {
        val sections = buildTaskQueueSections(
            categories = listOf(
                category("furniture", "Перемещение мебели", sortOrder = 20),
                category("repair", "Ремонты", sortOrder = 10),
                category("electric", "Электрики", sortOrder = 30),
            ),
            tasks = listOf(task("furniture-task", "furniture", "Перемещение мебели", 20, 0)),
        )

        assertThat(sections.map { it.queueId })
            .containsExactly("repair", "furniture", "electric")
            .inOrder()
        assertThat(sections.first().tasks).isEmpty()
        assertThat(sections.first().queuePurpose).isEqualTo("GENERAL")
        assertThat(sections[1].tasks.map { it.entryId }).containsExactly("furniture-task")
        assertThat(sections.last().tasks).isEmpty()
    }

    @Test
    fun ordersTasksByExactQueuePositionInsideEachSection() {
        val sections = buildTaskQueueSections(
            categories = listOf(category("repair", "Ремонты", sortOrder = 10)),
            tasks = listOf(
                task("last", "repair", "Ремонты", 10, queuePosition = 2),
                task("first", "repair", "Ремонты", 10, queuePosition = 0),
                task("middle", "repair", "Ремонты", 10, queuePosition = 1),
            ),
        )

        assertThat(sections.single().tasks.map { it.entryId })
            .containsExactly("first", "middle", "last")
            .inOrder()
    }

    @Test
    fun keepsVersionOneTaskSectionsVisibleUntilCategoryProjectionIsSynced() {
        val sections = buildTaskQueueSections(
            categories = emptyList(),
            tasks = listOf(task("legacy-task", "repair", "Ремонты", 10, 0)),
        )

        assertThat(sections.map { it.queueId }).containsExactly("repair")
        assertThat(sections.single().tasks.map { it.entryId }).containsExactly("legacy-task")
    }

    @Test
    fun hidesTaskThatIsNoLongerInTheAuthorizedQueueProjection() {
        val sections = buildTaskQueueSections(
            categories = listOf(category("furniture", "Перемещение мебели", 20)),
            tasks = listOf(task("revoked-task", "repair", "Ремонты", 10, 0)),
        )

        assertThat(sections.map { it.queueId }).containsExactly("furniture")
        assertThat(sections.single().tasks).isEmpty()
    }

    @Test
    fun `renders frozen break timer from the server snapshot`() {
        val timer = queueTaskTimerPresentation(
            task("entry", "repair", "Ремонты", 10, 0).copy(
                timerCountedActiveSeconds = 1_200,
                timerRemainingSeconds = 2_400,
                timerRemainingPercent = 66.6667,
                timerState = "BREAK",
                timerNextTransitionAt = "2026-07-26T11:00:00Z",
                timerServerTime = "2026-07-26T10:30:00Z",
            ),
        )

        assertThat(timer).isEqualTo(
            QueueTaskTimerPresentation(
                elapsed = "0:20:00",
                remaining = "0:40:00",
                percent = "66.7%",
                state = "Перерыв · таймер остановлен",
            ),
        )
    }

    @Test
    fun `group roles and qualification-only categories create independent columns`() {
        val groups = listOf(group("general", "Разнорабочие"))
        val groupTask = task("general-task", "general-queue", "Общие работы", 10, 0)
        val personalTask = task("slinger-task", "slinger-queue", "Стропальщики", 20, 0)
        val columns = buildWorkBoardColumns(
            groups = groups,
            categories = listOf(
                category("general-queue", "Общие работы", 10, groupIds = listOf("general")),
                category("slinger-queue", "Стропальщики", 20, groupIds = emptyList()),
            ),
            tasks = listOf(groupTask, personalTask),
            assignments = emptyList(),
        )

        assertThat(columns.map { it.name })
            .containsExactly("Разнорабочие", "Стропальщики")
            .inOrder()
        assertThat(columns.last().id).isEqualTo("qualification-slinger-queue")
        assertThat(columns.last().personal).isTrue()
        assertThat(columns.last().sections.single().tasks.map { it.entryId })
            .containsExactly("slinger-task")
        assertThat(columns.first().sections.flatMap { it.tasks }.map { it.entryId })
            .containsExactly("general-task")
    }

    @Test
    fun `active shared task is rendered in the slinger group without driver columns`() {
        val sharedCategory = category(
            queueId = "joint-loading",
            name = "Совместная погрузка",
            sortOrder = 5,
            groupIds = listOf("slingers"),
            queuePurpose = "LOGISTICS_DRIVER",
        )
        val activeTask = task("shipment", "joint-loading", "Совместная погрузка", 5, 0)
            .copy(status = "IN_PROGRESS", availabilityMode = "REQUIRED_JOIN")

        val columns = buildWorkBoardColumns(
            groups = listOf(group("slingers", "Стропальщики")),
            categories = listOf(sharedCategory),
            tasks = listOf(activeTask),
            assignments = listOf(assignment(activeTask.entryId, null)),
        )

        assertThat(columns.map { it.name }).containsExactly("Стропальщики")
        assertThat(columns.single().sections.single().tasks.map { it.entryId })
            .containsExactly("shipment")
        assertThat(columns.none { it.id.startsWith("driver-") }).isTrue()
    }

    @Test
    fun `stale waiting logistics task is hidden from the worker board`() {
        val category = category(
            queueId = "joint-loading",
            name = "Совместная погрузка",
            sortOrder = 5,
            groupIds = listOf("slingers"),
            queuePurpose = "LOGISTICS_DRIVER",
        )

        val columns = buildWorkBoardColumns(
            groups = listOf(group("slingers", "Стропальщики")),
            categories = listOf(category),
            tasks = listOf(
                task("waiting", "joint-loading", category.name, 5, 0)
                    .copy(status = "WAITING"),
            ),
            assignments = emptyList(),
        )

        assertThat(columns.single().sections.single().tasks).isEmpty()
    }

    @Test
    fun `live assignment restricts a shared queue card to the assigned group`() {
        val shared = category("shared", "Общая очередь", 10, groupIds = listOf("a", "b"))
        val task = task("assigned", "shared", "Общая очередь", 10, 0)
        val columns = buildWorkBoardColumns(
            groups = listOf(group("a", "Группа А"), group("b", "Группа Б")),
            categories = listOf(shared),
            tasks = listOf(task),
            assignments = listOf(assignment(task.entryId, "b")),
        )

        assertThat(columns[0].sections.flatMap { it.tasks }).isEmpty()
        assertThat(columns[1].sections.flatMap { it.tasks }.map { it.entryId }).containsExactly("assigned")
    }

    @Test
    fun `shows authoritative elapsed work when a budget timer is unavailable`() {
        val task = task("elapsed", "repair", "Ремонты", 10, 0).copy(activeWorkSeconds = 3_661)

        assertThat(queueTaskTimerPresentation(task)).isNull()
        assertThat(queueTaskElapsedLabel(task)).isEqualTo("1:01:01")
    }

    private fun category(
        queueId: String,
        name: String,
        sortOrder: Int,
        groupIds: List<String> = emptyList(),
        queuePurpose: String = "GENERAL",
    ) = WorkerCategoryEntity(
        localId = "$USER_ID:$queueId",
        userId = USER_ID,
        queueId = queueId,
        name = name,
        type = "REPAIR",
        queuePurpose = queuePurpose,
        groupIdsKey = groupIds.joinToString("\u001F"),
        sortOrder = sortOrder,
        audienceModesKey = "AVAILABLE",
        resultPhotoMinCount = 1,
        lastServerRevision = 12,
    )

    private fun group(id: String, name: String) = WorkerGroupEntity(
        localId = "$USER_ID:$id",
        userId = USER_ID,
        groupId = id,
        name = name,
        workerClassId = "class-$id",
        workerClassName = name,
    )

    private fun assignment(entryId: String, groupId: String?) = WorkerAssignmentEntity(
        localId = "$USER_ID:$entryId:$groupId",
        userId = USER_ID,
        entryId = entryId,
        assignmentId = "assignment-${groupId ?: "primary"}",
        workerId = null,
        workerName = null,
        workerGroupId = groupId,
        workerGroupName = groupId,
        status = "ACTIVE",
        assignedAt = "2026-08-04T10:00:00Z",
        startedAt = null,
        pausedAt = null,
        finishedAt = null,
    )

    private fun task(
        entryId: String,
        queueId: String,
        categoryName: String,
        categorySortOrder: Int,
        queuePosition: Int,
    ) = WorkerTaskEntity(
        localId = "$USER_ID:$entryId",
        userId = USER_ID,
        entryId = entryId,
        taskId = "task-$entryId",
        version = 1,
        categoryId = queueId,
        categoryName = categoryName,
        categorySortOrder = categorySortOrder,
        title = entryId,
        unitNumber = "БТ-1",
        taskText = null,
        scheduledDate = "2026-07-26",
        deadlineAt = null,
        priority = 0,
        queuePosition = queuePosition,
        status = "AVAILABLE",
        availabilityMode = "AVAILABLE",
        plannedDurationMinutes = null,
        activeStartedAt = null,
        activeWorkSeconds = 0,
        readyEvidenceCount = 0,
        resultPhotoMinCount = 1,
        lastServerRevision = 12,
        locallyPending = false,
        updatedAtEpochMillis = 1,
    )

    private companion object {
        const val USER_ID = "worker"
    }
}
