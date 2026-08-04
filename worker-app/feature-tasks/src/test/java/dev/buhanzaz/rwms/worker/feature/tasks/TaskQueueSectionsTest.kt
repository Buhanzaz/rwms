package dev.buhanzaz.rwms.worker.feature.tasks

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.WorkerCategoryEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerAssignmentEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerGroupEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import org.junit.Test

class TaskQueueSectionsTest {
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
                remaining = "0:40:00",
                percent = "66.7%",
                state = "Перерыв · таймер остановлен",
            ),
        )
    }

    @Test
    fun `two groups create exactly two columns and personal work is a section of the first`() {
        val groups = listOf(group("repair", "Ремонтники"), group("electric", "Электрики"))
        val groupTask = task("repair-task", "repair-queue", "Ремонты", 10, 0)
        val personalTask = task("driver-task", "driver-queue", "Водители", 20, 0)
        val columns = buildWorkBoardColumns(
            groups = groups,
            categories = listOf(
                category("repair-queue", "Ремонты", 10, groupIds = listOf("repair")),
                category("driver-queue", "Водители", 20, groupIds = emptyList()),
            ),
            tasks = listOf(groupTask, personalTask),
            assignments = emptyList(),
        )

        assertThat(columns.map { it.name }).containsExactly("Ремонтники", "Электрики").inOrder()
        assertThat(columns.first().sections.last().name).isEqualTo("Личные задания")
        assertThat(columns.first().sections.last().tasks.map { it.entryId }).containsExactly("driver-task")
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

    private fun category(
        queueId: String,
        name: String,
        sortOrder: Int,
        groupIds: List<String> = emptyList(),
    ) = WorkerCategoryEntity(
        localId = "$USER_ID:$queueId",
        userId = USER_ID,
        queueId = queueId,
        name = name,
        type = "REPAIR",
        queuePurpose = "GENERAL",
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

    private fun assignment(entryId: String, groupId: String) = WorkerAssignmentEntity(
        localId = "$USER_ID:$entryId:$groupId",
        userId = USER_ID,
        entryId = entryId,
        assignmentId = "assignment-$groupId",
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
