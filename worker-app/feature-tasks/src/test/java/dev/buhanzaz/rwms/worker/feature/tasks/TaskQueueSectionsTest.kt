package dev.buhanzaz.rwms.worker.feature.tasks

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.WorkerAssignmentEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerCategoryEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerTaskEntity
import org.junit.Test

class TaskQueueSectionsTest {
    @Test
    fun `home title and cabin task headings use single-task product language`() {
        assertThat(CURRENT_TASK_TITLE).isEqualTo("Моё задание")
        assertThat(taskCardTitle("БТ-42")).isEqualTo("БТ-42")
        assertThat(taskCardTitle(null)).isEqualTo("Задание")
        assertThat(taskCardTitle("550e8400-e29b-41d4-a716-446655440000"))
            .isEqualTo("Задание")
    }

    @Test
    fun `technical maintenance queue label is matched without case or spacing sensitivity`() {
        assertThat(taskQueueLabel("Maintenance repair")).isEqualTo("Работы")
        assertThat(taskQueueLabel("  MAINTENANCE   REPAIR ")).isEqualTo("Работы")
        assertThat(taskQueueLabel("Maintenance rapair")).isEqualTo("Работы")
        assertThat(taskQueueLabel("Погрузка")).isEqualTo("Погрузка")
    }

    @Test
    fun `active group assignment wins over every waiting task`() {
        val active = task("active", "general", queuePosition = 12).copy(status = "IN_PROGRESS")
        val waiting = task("waiting", "repair", queuePosition = 0)

        val selected = selectCurrentWorkerTask(
            userId = USER_ID,
            currentGroupId = GROUP_ID,
            categories = listOf(
                category("repair", sortOrder = 1),
                category("general", sortOrder = 50, groupIds = listOf(GROUP_ID)),
            ),
            tasks = listOf(waiting, active),
            assignments = listOf(assignment(active.entryId, USER_ID, GROUP_ID, "ACTIVE")),
        )

        assertThat(selected?.entryId).isEqualTo("active")
    }

    @Test
    fun `only first waiting task in authoritative queue order is shown`() {
        val selected = selectCurrentWorkerTask(
            userId = USER_ID,
            currentGroupId = GROUP_ID,
            categories = listOf(
                category("repair", sortOrder = 10),
                category("electric", sortOrder = 20),
            ),
            tasks = listOf(
                task("electric-first", "electric", queuePosition = 0),
                task("repair-second", "repair", queuePosition = 2),
                task("repair-first", "repair", queuePosition = 1),
            ),
            assignments = emptyList(),
        )

        assertThat(selected?.entryId).isEqualTo("repair-first")
    }

    @Test
    fun `joined slinger task temporarily replaces paused ordinary task`() {
        val ordinary = task("ordinary", "general", queuePosition = 0).copy(status = "PAUSED")
        val slinger = task("slinger", "logistics", queuePosition = 0).copy(
            status = "IN_PROGRESS",
            availabilityMode = "OPTIONAL_JOIN",
        )

        val selected = selectCurrentWorkerTask(
            userId = USER_ID,
            currentGroupId = GROUP_ID,
            categories = listOf(
                category("general", 10, groupIds = listOf(GROUP_ID)),
                category("logistics", 20, queuePurpose = LOGISTICS_QUEUE_PURPOSE),
            ),
            tasks = listOf(ordinary, slinger),
            assignments = listOf(
                assignment(ordinary.entryId, null, GROUP_ID, "PAUSED"),
                assignment(slinger.entryId, "driver", null, "ACTIVE"),
                assignment(slinger.entryId, USER_ID, GROUP_ID, "ACTIVE"),
            ),
        )

        assertThat(selected?.entryId).isEqualTo("slinger")
    }

    @Test
    fun `completed slinger task disappears and paused ordinary task returns`() {
        val ordinary = task("ordinary", "general", queuePosition = 0).copy(status = "PAUSED")
        val completedSlinger = task("slinger", "logistics", queuePosition = 0).copy(
            status = "DONE",
            availabilityMode = "OPTIONAL_JOIN",
        )

        val selected = selectCurrentWorkerTask(
            userId = USER_ID,
            currentGroupId = GROUP_ID,
            categories = listOf(
                category("general", 10, groupIds = listOf(GROUP_ID)),
                category("logistics", 20, queuePurpose = LOGISTICS_QUEUE_PURPOSE),
            ),
            tasks = listOf(ordinary, completedSlinger),
            assignments = listOf(assignment(ordinary.entryId, null, GROUP_ID, "PAUSED")),
        )

        assertThat(selected?.entryId).isEqualTo("ordinary")
    }

    @Test
    fun `locally pending take stays foreground until assignments refresh`() {
        val pending = task("pending", "general", queuePosition = 2).copy(
            status = "IN_PROGRESS",
            locallyPending = true,
        )

        val selected = selectCurrentWorkerTask(
            userId = USER_ID,
            currentGroupId = GROUP_ID,
            categories = listOf(category("general", 10, groupIds = listOf(GROUP_ID))),
            tasks = listOf(task("waiting", "general", 1), pending),
            assignments = emptyList(),
        )

        assertThat(selected?.entryId).isEqualTo("pending")
    }

    @Test
    fun `locally pending completion stays foreground while its evidence is delivered`() {
        val completedLocally = task("completed-locally", "general", queuePosition = 2).copy(
            status = "DONE",
            locallyPending = true,
        )

        val selected = selectCurrentWorkerTask(
            userId = USER_ID,
            currentGroupId = GROUP_ID,
            categories = listOf(category("general", 10, groupIds = listOf(GROUP_ID))),
            tasks = listOf(task("waiting", "general", 1), completedLocally),
            assignments = emptyList(),
        )

        assertThat(selected?.entryId).isEqualTo("completed-locally")
    }

    @Test
    fun `active unjoined slinger task becomes a modal candidate`() {
        val slinger = task("slinger", "logistics", queuePosition = 0).copy(
            status = "IN_PROGRESS",
            availabilityMode = "REQUIRED_JOIN",
        )

        val selected = selectIncomingSlingerTask(
            userId = USER_ID,
            currentGroupId = GROUP_ID,
            operationalAvailability = "AVAILABLE",
            categories = listOf(
                category("logistics", 10, queuePurpose = LOGISTICS_QUEUE_PURPOSE),
            ),
            tasks = listOf(slinger),
            assignments = listOf(assignment(slinger.entryId, "driver", null, "ACTIVE")),
        )

        assertThat(selected?.entryId).isEqualTo("slinger")
    }

    @Test
    fun `joined pending or waiting logistics task never opens interruption modal`() {
        val active = task("active", "logistics", queuePosition = 0).copy(
            status = "IN_PROGRESS",
            availabilityMode = "OPTIONAL_JOIN",
        )
        val waiting = task("waiting", "logistics", queuePosition = 1).copy(
            status = "WAITING",
            availabilityMode = "OPTIONAL_JOIN",
        )
        val pending = task("pending", "logistics", queuePosition = 2).copy(
            status = "IN_PROGRESS",
            availabilityMode = "REQUIRED_JOIN",
            locallyPending = true,
        )
        val category = category("logistics", 10, queuePurpose = LOGISTICS_QUEUE_PURPOSE)

        assertThat(
            selectIncomingSlingerTask(
                USER_ID,
                GROUP_ID,
                "AVAILABLE",
                listOf(category),
                listOf(active, waiting, pending),
                listOf(
                    assignment(active.entryId, "driver", null, "ACTIVE"),
                    assignment(active.entryId, USER_ID, GROUP_ID, "ACTIVE"),
                ),
            ),
        ).isNull()
    }

    @Test
    fun `modal fails closed without current group or operational availability`() {
        val slinger = task("slinger", "logistics", 0).copy(
            status = "IN_PROGRESS",
            availabilityMode = "REQUIRED_JOIN",
        )
        val category = category("logistics", 10, queuePurpose = LOGISTICS_QUEUE_PURPOSE)
        val assignments = listOf(assignment(slinger.entryId, "driver", null, "ACTIVE"))

        assertThat(
            selectIncomingSlingerTask(
                USER_ID,
                null,
                "AVAILABLE",
                listOf(category),
                listOf(slinger),
                assignments,
            ),
        ).isNull()
        assertThat(
            selectIncomingSlingerTask(
                USER_ID,
                GROUP_ID,
                "DISABLED",
                listOf(category),
                listOf(slinger),
                assignments,
            ),
        ).isNull()
    }

    @Test
    fun `shadow revoked and terminal entries are never selected`() {
        val category = category("general", 10)
        val selected = selectCurrentWorkerTask(
            userId = USER_ID,
            currentGroupId = GROUP_ID,
            categories = listOf(category),
            tasks = listOf(
                task("shadow", "general", 0).copy(entryType = "SHADOW"),
                task("done", "general", 1).copy(status = "DONE"),
                task("revoked", "revoked", 0),
            ),
            assignments = emptyList(),
        )

        assertThat(selected).isNull()
    }

    @Test
    fun `renders elapsed work and KPI from server timer snapshot`() {
        val timer = queueTaskTimerPresentation(
            task("entry", "repair", 0).copy(
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
                percent = "66.7%",
            ),
        )
    }

    @Test
    fun `refresh animation requires a recent active synchronization stage`() {
        val now = 1_000_000L

        assertThat(shouldAnimateTaskRefresh("CONTEXT", now - 1_000, now)).isTrue()
        assertThat(shouldAnimateTaskRefresh("UPLOAD", now - 60_000, now)).isTrue()
        assertThat(shouldAnimateTaskRefresh("CONTEXT", now - 60_001, now)).isFalse()
        assertThat(shouldAnimateTaskRefresh("IDLE", now, now)).isFalse()
    }

    @Test
    fun `sync notice hides progress chatter but keeps offline and blocked truth`() {
        assertThat(taskSyncNotice(true, "CONTEXT", "Проверяем доступ")).isNull()
        assertThat(taskSyncNotice(false, "CONTEXT", "Проверяем доступ"))
            .isEqualTo("Нет связи с RWMS")
        assertThat(taskSyncNotice(true, "WAITING_FOR_EVIDENCE", "Фото ожидает отправки"))
            .isEqualTo("Фото ожидает отправки")
    }

    private fun category(
        queueId: String,
        sortOrder: Int,
        groupIds: List<String> = emptyList(),
        queuePurpose: String = "GENERAL",
    ) = WorkerCategoryEntity(
        localId = "$USER_ID:$queueId",
        userId = USER_ID,
        queueId = queueId,
        name = queueId,
        type = "REPAIR",
        queuePurpose = queuePurpose,
        groupIdsKey = groupIds.joinToString("\u001F"),
        sortOrder = sortOrder,
        audienceModesKey = "AVAILABLE",
        resultPhotoMinCount = 1,
        lastServerRevision = 12,
    )

    private fun assignment(
        entryId: String,
        workerId: String?,
        groupId: String?,
        status: String,
    ) = WorkerAssignmentEntity(
        localId = "$USER_ID:$entryId:${workerId ?: groupId}",
        userId = USER_ID,
        entryId = entryId,
        assignmentId = "assignment-$entryId-${workerId ?: groupId}",
        workerId = workerId,
        workerName = workerId,
        workerGroupId = groupId,
        workerGroupName = groupId,
        status = status,
        assignedAt = "2026-08-04T10:00:00Z",
        startedAt = null,
        pausedAt = null,
        finishedAt = null,
    )

    private fun task(
        entryId: String,
        queueId: String,
        queuePosition: Int,
    ) = WorkerTaskEntity(
        localId = "$USER_ID:$entryId",
        userId = USER_ID,
        entryId = entryId,
        taskId = "task-$entryId",
        version = 1,
        categoryId = queueId,
        categoryName = queueId,
        categorySortOrder = 10,
        title = entryId,
        unitNumber = "БТ-1",
        taskText = null,
        scheduledDate = "2026-07-26",
        deadlineAt = null,
        priority = 3,
        queuePosition = queuePosition,
        status = "WAITING",
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
        const val GROUP_ID = "general-group"
    }
}
