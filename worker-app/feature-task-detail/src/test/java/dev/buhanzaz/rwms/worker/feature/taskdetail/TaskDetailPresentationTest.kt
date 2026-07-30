package dev.buhanzaz.rwms.worker.feature.taskdetail

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.WorkerAssignmentEntity
import dev.buhanzaz.rwms.worker.core.network.WorkerTaskTimerSnapshotDto
import dev.buhanzaz.rwms.worker.core.network.WorkerWorkDto
import dev.buhanzaz.rwms.worker.core.ui.cabinNumberForDisplay
import java.time.Instant
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskDetailPresentationTest {
    @Test
    fun `photo button is visibly unavailable until its route index is loaded`() {
        val presentation = photoCapturePresentation(hasLoadedDetail = false)

        assertThat(presentation.enabled).isFalse()
        assertThat(presentation.message).contains("Загружаем карточку")
    }

    @Test
    fun `cabin number wins over an internal object UUID`() {
        assertThat(
            cabinNumberForDisplay(
                "БТ-1042",
                "51000000-0000-4000-8000-000000000003",
            ),
        ).isEqualTo("БТ-1042")
    }

    @Test
    fun `UUID-only object is hidden`() {
        assertThat(
            cabinNumberForDisplay(
                "51000000-0000-4000-8000-000000000003",
                "Объект 51000000-0000-4000-8000-000000000003",
            ),
        ).isNull()
    }

    @Test
    fun `legacy failure without original reservation asks for a new photo`() {
        val presentation = evidencePresentation(
            state = "REVIEW_REQUIRED",
            uploadPercent = 0,
            mediaId = null,
            hasValidReservationPayload = false,
        )

        assertThat(presentation.status).isEqualTo("Нужно новое фото")
        assertThat(presentation.message).contains("снимите фото заново")
        assertThat(presentation.message).doesNotContain("404")
        assertThat(presentation.message).doesNotContain("{")
        assertThat(presentation.canRetryReservation).isFalse()
    }

    @Test
    fun `saved valid reservation remains retryable`() {
        val presentation = evidencePresentation(
            state = "REVIEW_REQUIRED",
            uploadPercent = 0,
            mediaId = null,
            hasValidReservationPayload = true,
        )

        assertThat(presentation.status).isEqualTo("Ожидает повторной отправки")
        assertThat(presentation.canRetryReservation).isTrue()
    }

    @Test
    fun `server review after upload cannot recreate reservation`() {
        val presentation = evidencePresentation(
            state = "REVIEW_REQUIRED",
            uploadPercent = 100,
            mediaId = "31000000-0000-4000-8000-000000000001",
            hasValidReservationPayload = true,
        )

        assertThat(presentation.status).isEqualTo("Требуется проверка")
        assertThat(presentation.canRetryReservation).isFalse()
    }

    @Test
    fun `photo upload exposes its durable percentage`() {
        val presentation = evidencePresentation(
            state = "UPLOADING",
            uploadPercent = 63,
            mediaId = "31000000-0000-4000-8000-000000000001",
            hasValidReservationPayload = false,
        )

        assertThat(presentation.status).isEqualTo("Загрузка · 63%")
    }

    @Test
    fun `work presentation exposes quantity unit and planned time without a price`() {
        val presentation = workPresentation(
            WorkerWorkDto(
                id = "51000000-0000-4000-8000-000000000004",
                name = "Замена профлиста",
                quantity = 2.0,
                unit = "шт",
                durationMinutes = 90,
                comment = "Сначала демонтировать повреждённый лист",
            ),
        )

        assertThat(presentation.quantity).isEqualTo("2 шт")
        assertThat(presentation.plannedDuration).isEqualTo("1 ч 30 мин")
        assertThat(presentation.comment).contains("демонтировать")
        val fields = WorkerWorkDto::class.java.declaredFields.map { it.name }
        assertThat(fields).contains("id")
        assertThat(fields.contains("code")).isFalse()
        assertThat(fields.contains("price")).isFalse()
    }

    @Test
    fun `active timer includes the live interval only while task is in progress`() {
        val startedAt = "2026-07-26T10:00:00Z"
        val now = Instant.parse("2026-07-26T10:02:30Z")

        assertThat(
            activeElapsedSeconds(
                activeStartedAt = startedAt,
                activeWorkSeconds = 45,
                status = "IN_PROGRESS",
                now = now,
            ),
        ).isEqualTo(195)
        assertThat(
            activeElapsedSeconds(
                activeStartedAt = startedAt,
                activeWorkSeconds = 45,
                status = "PAUSED",
                now = now,
            ),
        ).isEqualTo(45)
        assertThat(
            taskTimingPresentation(
                plannedDurationMinutes = 90,
                activeStartedAt = startedAt,
                activeWorkSeconds = 45,
                status = "IN_PROGRESS",
                now = now,
            ),
        ).isEqualTo(
            TaskTimingPresentation(
                planned = "1 ч 30 мин",
                activeElapsed = "0:03:15",
                activeLabel = "В работе",
            ),
        )
    }

    @Test
    fun `break and off shift render the exact frozen server timer snapshot`() {
        listOf("BREAK", "OFF_SHIFT").forEach { timerState ->
            val snapshot = WorkerTaskTimerSnapshotDto(
                countedActiveSeconds = 1_200,
                remainingSeconds = 2_400,
                remainingPercent = 66.6667,
                timerState = timerState,
                nextTransitionAt = "2026-07-26T11:00:00Z",
                serverTime = "2026-07-26T10:30:00Z",
            )

            assertThat(taskTimingPresentation(snapshot)).isEqualTo(
                TaskTimingPresentation(
                    activeElapsed = "0:20:00",
                    activeLabel = if (timerState == "BREAK") {
                        "Перерыв · таймер остановлен"
                    } else {
                        "Вне смены · таймер остановлен"
                    },
                    remaining = "0:40:00",
                    remainingPercent = "66,7%",
                    nextTransitionAt = "2026-07-26T11:00:00Z",
                ),
            )
        }
    }

    @Test
    fun `working timer advances only until the next schedule transition`() {
        val snapshot = WorkerTaskTimerSnapshotDto(
            countedActiveSeconds = 1_200,
            remainingSeconds = 2_400,
            remainingPercent = 66.6667,
            timerState = "WORKING",
            nextTransitionAt = "2026-07-26T10:30:10Z",
            serverTime = "2026-07-26T10:30:00Z",
        )

        assertThat(snapshot.projectedAfter(30)).isEqualTo(
            snapshot.copy(
                countedActiveSeconds = 1_210,
                remainingSeconds = 2_390,
                remainingPercent = 2_390.0 * 100.0 / 3_600.0,
            ),
        )
        assertThat(snapshot.copy(timerState = "BREAK").projectedAfter(30))
            .isEqualTo(snapshot.copy(timerState = "BREAK"))
    }

    @Test
    fun `take is unavailable without a manager selected group or while worker is disabled`() {
        val withoutGroup = taskActionPresentation(
            currentWorkerId = "worker-current",
            taskStatus = "WAITING",
            availabilityMode = "AVAILABLE",
            assignments = emptyList(),
            locallyPending = false,
            hasCurrentGroup = false,
        )
        val disabled = taskActionPresentation(
            currentWorkerId = "worker-current",
            taskStatus = "WAITING",
            availabilityMode = "AVAILABLE",
            assignments = emptyList(),
            locallyPending = false,
            operationalAvailability = "DISABLED",
        )

        assertThat(withoutGroup.actions).isEmpty()
        assertThat(withoutGroup.message).isEqualTo("Руководитель ещё не выбрал текущую группу")
        assertThat(disabled.actions).isEmpty()
        assertThat(disabled.message).isEqualTo("Группа временно недоступна")
    }

    @Test
    fun `take payload can only use the manager selected current group`() {
        assertThat(
            managerSelectedGroupForAction(WorkerTaskAction.TAKE, "group-current"),
        ).isEqualTo("group-current")

        val failure = assertThrows(IllegalArgumentException::class.java) {
            managerSelectedGroupForAction(WorkerTaskAction.TAKE, null)
        }
        assertThat(failure).hasMessageThat().contains("Руководитель")
    }

    @Test
    fun `waiting task without a live assignment can be taken`() {
        val unassigned = taskActionPresentation(
            currentWorkerId = "worker-current",
            taskStatus = "WAITING",
            availabilityMode = "AVAILABLE",
            assignments = emptyList(),
            locallyPending = false,
        )
        val assignedToOther = taskActionPresentation(
            currentWorkerId = "worker-current",
            taskStatus = "WAITING",
            availabilityMode = "AVAILABLE",
            assignments = listOf(assignment("worker-other", "Android Demo", "ACTIVE")),
            locallyPending = false,
        )

        assertThat(unassigned.actions).containsExactly(WorkerTaskAction.TAKE)
        assertThat(unassigned.actionsEnabled).isTrue()
        assertThat(assignedToOther.actions).isEmpty()
        assertThat(assignedToOther.message).isEqualTo("Задание выполняет другой рабочий")
    }

    @Test
    fun `secondary worker waits for the primary worker before taking the task`() {
        val presentation = taskActionPresentation(
            currentWorkerId = "worker-secondary",
            taskStatus = "WAITING",
            availabilityMode = "SECONDARY_PENDING",
            assignments = emptyList(),
            locallyPending = false,
        )

        assertThat(presentation.actions).isEmpty()
        assertThat(presentation.actionsEnabled).isFalse()
        assertThat(presentation.message).isEqualTo("Ожидает основного исполнителя")
        assertThat(presentation.takeLabel).isEqualTo("Взять")
    }

    @Test
    fun `active assignment allows only its worker to pause or complete`() {
        val mine = taskActionPresentation(
            currentWorkerId = "worker-current",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "AVAILABLE",
            assignments = listOf(assignment("worker-current", "Текущий", "ACTIVE")),
            locallyPending = false,
        )
        val other = taskActionPresentation(
            currentWorkerId = "worker-current",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "AVAILABLE",
            assignments = listOf(assignment("worker-other", "Android Demo", "ACTIVE")),
            locallyPending = false,
        )
        val mineButPaused = taskActionPresentation(
            currentWorkerId = "worker-current",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "AVAILABLE",
            assignments = listOf(assignment("worker-current", "Текущий", "PAUSED")),
            locallyPending = false,
        )

        assertThat(mine.actions).containsExactly(
            WorkerTaskAction.PAUSE,
            WorkerTaskAction.COMPLETE,
        ).inOrder()
        assertThat(other.actions).isEmpty()
        assertThat(other.message).isEqualTo("Задание выполняет другой рабочий")
        assertThat(other.performers).containsExactly("Android Demo")
        assertThat(mineButPaused.actions).isEmpty()
    }

    @Test
    fun `mandatory in-progress task lets an unassigned secondary worker join urgently`() {
        val presentation = taskActionPresentation(
            currentWorkerId = "worker-current",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "MANDATORY",
            assignments = listOf(assignment("worker-other", "Водитель", "ACTIVE")),
            locallyPending = false,
        )

        assertThat(presentation.actions).containsExactly(WorkerTaskAction.TAKE)
        assertThat(presentation.actionsEnabled).isTrue()
        assertThat(presentation.takeLabel).isEqualTo("Взять срочное")
        assertThat(presentation.message).isEqualTo("Срочное задание: присоединитесь к выполнению")
        assertThat(presentation.performers).containsExactly("Водитель")
    }

    @Test
    fun `mandatory in-progress task without another live assignment cannot be taken`() {
        val presentation = taskActionPresentation(
            currentWorkerId = "worker-current",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "MANDATORY",
            assignments = emptyList(),
            locallyPending = false,
        )

        assertThat(presentation.actions).isEmpty()
        assertThat(presentation.actionsEnabled).isFalse()
    }

    @Test
    fun `paused assignment allows only its worker to resume`() {
        val mine = taskActionPresentation(
            currentWorkerId = "worker-current",
            taskStatus = "PAUSED",
            availabilityMode = "AVAILABLE",
            assignments = listOf(assignment("worker-current", "Текущий", "PAUSED")),
            locallyPending = false,
        )
        val other = taskActionPresentation(
            currentWorkerId = "worker-current",
            taskStatus = "PAUSED",
            availabilityMode = "AVAILABLE",
            assignments = listOf(assignment("worker-other", "Android Demo", "PAUSED")),
            locallyPending = false,
        )

        assertThat(mine.actions).containsExactly(WorkerTaskAction.RESUME)
        assertThat(other.actions).isEmpty()
        assertThat(other.message).isEqualTo("Задание выполняет другой рабочий")
    }

    @Test
    fun `locally pending task keeps its action buttons disabled`() {
        val presentation = taskActionPresentation(
            currentWorkerId = "worker-current",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "AVAILABLE",
            assignments = listOf(assignment("worker-current", "Текущий", "ACTIVE")),
            locallyPending = true,
        )

        assertThat(presentation.actions).containsExactly(
            WorkerTaskAction.PAUSE,
            WorkerTaskAction.COMPLETE,
        ).inOrder()
        assertThat(presentation.actionsEnabled).isFalse()
        assertThat(presentation.message).isEqualTo("Действие ожидает синхронизации")
    }

    private fun assignment(
        workerId: String,
        workerName: String,
        status: String,
    ) = WorkerAssignmentEntity(
        localId = "user:entry:$workerId",
        userId = "worker-current",
        entryId = "entry",
        assignmentId = "assignment-$workerId",
        workerId = workerId,
        workerName = workerName,
        workerGroupId = "group",
        workerGroupName = "Разнорабочие",
        status = status,
        assignedAt = "2026-07-26T10:00:00Z",
        startedAt = null,
        pausedAt = null,
        finishedAt = null,
    )
}
