package dev.buhanzaz.rwms.worker.feature.tasks

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.WorkerCategoryEntity
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

    private fun category(queueId: String, name: String, sortOrder: Int) = WorkerCategoryEntity(
        localId = "$USER_ID:$queueId",
        userId = USER_ID,
        queueId = queueId,
        name = name,
        type = "REPAIR",
        sortOrder = sortOrder,
        audienceModesKey = "AVAILABLE",
        resultPhotoMinCount = 1,
        lastServerRevision = 12,
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
