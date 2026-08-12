package dev.buhanzaz.rwms.driver.feature.tasks

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.database.DriverCategoryEntity
import dev.buhanzaz.rwms.driver.core.database.DriverAssignmentEntity
import dev.buhanzaz.rwms.driver.core.database.DriverGroupEntity
import dev.buhanzaz.rwms.driver.core.database.DriverTaskEntity
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
    fun `driver audience creates separate personal logistics and shared movement tables`() {
        val driverCategory = category(
            queueId = "drivers",
            name = "Водители",
            sortOrder = 5,
            queuePurpose = "LOGISTICS_DRIVER",
        )
        val assigned = task("shipment", "drivers", "Водители", 5, 0)
            .copy(driverAudienceMode = "ASSIGNED_DRIVER")
        val shared = task("movement", "drivers", "Водители", 5, 1)
            .copy(driverAudienceMode = "WAREHOUSE_DRIVERS")
        val legacyUnclassified = task("legacy", "drivers", "Водители", 5, 2)

        val columns = buildWorkBoardColumns(
            groups = emptyList(),
            categories = listOf(driverCategory),
            tasks = listOf(shared, legacyUnclassified, assigned),
            assignments = emptyList(),
        )

        assertThat(columns.map { it.name })
            .containsExactly("Логистика", "Перемещения")
            .inOrder()
        assertThat(columns[0].description).isEqualTo("Только назначенные вам задания")
        assertThat(columns[0].sections.single().tasks.map { it.entryId })
            .containsExactly("shipment")
        assertThat(columns[1].description).isEqualTo("Общие задания водителей склада")
        assertThat(columns[1].sections.single().tasks.map { it.entryId })
            .containsExactly("movement")
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
    ) = DriverCategoryEntity(
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

    private fun group(id: String, name: String) = DriverGroupEntity(
        localId = "$USER_ID:$id",
        userId = USER_ID,
        groupId = id,
        name = name,
        driverClassId = "class-$id",
        driverClassName = name,
    )

    private fun assignment(entryId: String, groupId: String) = DriverAssignmentEntity(
        localId = "$USER_ID:$entryId:$groupId",
        userId = USER_ID,
        entryId = entryId,
        assignmentId = "assignment-$groupId",
        driverId = null,
        driverName = null,
        driverGroupId = groupId,
        driverGroupName = groupId,
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
    ) = DriverTaskEntity(
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
        const val USER_ID = "driver"
    }
}
