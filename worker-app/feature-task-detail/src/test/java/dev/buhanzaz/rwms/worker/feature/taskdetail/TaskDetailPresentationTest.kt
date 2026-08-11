package dev.buhanzaz.rwms.worker.feature.taskdetail

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.WorkerAssignmentEntity
import dev.buhanzaz.rwms.worker.core.network.DriverTripActualEquipmentDto
import dev.buhanzaz.rwms.worker.core.network.DriverTripCabinDto
import dev.buhanzaz.rwms.worker.core.network.DriverTripDesiredDeliveryWindowDto
import dev.buhanzaz.rwms.worker.core.network.DriverTripDesiredEquipmentDto
import dev.buhanzaz.rwms.worker.core.network.DriverTripDetailsDto
import dev.buhanzaz.rwms.worker.core.network.WorkerTaskTimerSnapshotDto
import dev.buhanzaz.rwms.worker.core.network.WorkerMediaReferenceDto
import dev.buhanzaz.rwms.worker.core.network.WorkerWorkDto
import dev.buhanzaz.rwms.worker.core.ui.cabinNumberForDisplay
import java.time.Instant
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskDetailPresentationTest {
    @Test
    fun `trip operation labels preserve unknown server values`() {
        assertThat(driverTripOperationLabel("SHIPMENT")).isEqualTo("Доставка / аренда")
        assertThat(driverTripOperationLabel("RETURN")).isEqualTo("Вывоз")
        assertThat(driverTripOperationLabel("TRANSFER")).isEqualTo("Перемещение")
        assertThat(driverTripOperationLabel("CUSTOM_OPERATION")).isEqualTo("CUSTOM_OPERATION")
    }

    @Test
    fun `desired and assigned logistics dates never include time`() {
        val singleDay = desiredDeliveryWindowLabel(
            DriverTripDesiredDeliveryWindowDto(
                startDate = "2026-08-11",
                endDate = "2026-08-11",
            ),
        )
        val dateRange = desiredDeliveryWindowLabel(
            DriverTripDesiredDeliveryWindowDto(
                startDate = "2026-08-13",
                endDate = "2026-08-15",
            ),
        )

        assertThat(singleDay).isEqualTo("11.08.2026")
        assertThat(dateRange).isEqualTo("13.08.2026–15.08.2026")
        assertThat(scheduledTripLabel("2026-08-12")).isEqualTo("12.08.2026")
    }

    @Test
    fun `grouped trip keeps one readiness presentation per cabin`() {
        val presentations = driverTripCabinPresentations(
            trip(
                cabins = listOf(
                    DriverTripCabinDto(
                        cabinId = "cabin-1",
                        unitNumber = "БТ-101",
                        desiredContents = listOf(
                            DriverTripDesiredEquipmentDto("bed", "Кровать", 4),
                        ),
                        actualContents = listOf(
                            DriverTripActualEquipmentDto("bed", "Кровать", 4, "CABIN"),
                        ),
                        movementTaskCreated = true,
                        movementTaskCompleted = true,
                        contentReady = true,
                    ),
                    DriverTripCabinDto(
                        cabinId = "cabin-2",
                        unitNumber = "БТ-102",
                        desiredContents = listOf(
                            DriverTripDesiredEquipmentDto("table", "Стол", 1),
                        ),
                        actualContents = emptyList(),
                        movementTaskCreated = true,
                        movementTaskCompleted = false,
                        contentReady = false,
                    ),
                ),
            ),
        )

        assertThat(presentations.map { it.unitNumber }).containsExactly("БТ-101", "БТ-102")
            .inOrder()
        assertThat(presentations.first().desiredContents).containsExactly("Кровать: 4")
        assertThat(presentations.first().actualContents).containsExactly("Кровать: 4 · CABIN")
        assertThat(presentations.first().contentReady).isTrue()
        assertThat(presentations.last().movementTaskCreated).isTrue()
        assertThat(presentations.last().movementTaskCompleted).isFalse()
        assertThat(presentations.last().contentReady).isFalse()
    }

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
    fun `work photos are separated from general task photos by source media ids`() {
        val workPhoto = media("media-work")
        val generalPhoto = media("media-general")
        val work = WorkerWorkDto(
            id = "work-1",
            name = "Замена профлиста",
            quantity = 1.0,
            unit = "шт",
            durationMinutes = 30,
            comment = null,
            sourceMediaIds = listOf(workPhoto.mediaId),
        )

        val presentation = taskSourceMediaPresentation(
            works = listOf(work),
            sourceMedia = listOf(generalPhoto, workPhoto),
        )

        assertThat(presentation.general.map { it.mediaId }).containsExactly("media-general")
        assertThat(presentation.byWorkId.getValue("work-1").map { it.mediaId })
            .containsExactly("media-work")
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
        assertThat(disabled.message).isEqualTo("Рабочий временно недоступен")
    }

    private fun media(id: String) = WorkerMediaReferenceDto(
        mediaId = id,
        generation = 1,
        kind = "SOURCE",
        contentType = "image/jpeg",
        readPath = "/api/media/v1/assets/$id/original",
        thumbnailPath = null,
        capturedAt = null,
        recordedAt = "2026-08-04T10:00:00Z",
    )

    @Test
    fun `take payload can only use the manager selected current group`() {
        assertThat(
            selectedGroupForAction(WorkerTaskAction.TAKE, "group-current", "GENERAL"),
        ).isEqualTo("group-current")

        val failure = assertThrows(IllegalArgumentException::class.java) {
            selectedGroupForAction(WorkerTaskAction.TAKE, null, "GENERAL")
        }
        assertThat(failure).hasMessageThat().contains("Руководитель")
    }

    @Test
    fun `driver can take a logistics task without a current group`() {
        val presentation = taskActionPresentation(
            currentWorkerId = "driver",
            taskStatus = "WAITING",
            availabilityMode = "AVAILABLE",
            queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE,
            assignments = emptyList(),
            locallyPending = false,
            hasCurrentGroup = false,
        )

        assertThat(presentation.actions).containsExactly(WorkerTaskAction.TAKE)
        assertThat(
            selectedGroupForAction(
                WorkerTaskAction.TAKE,
                currentGroupId = null,
                queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE,
            ),
        ).isNull()
    }

    @Test
    fun `qualified secondary worker joins the same optional logistics task`() {
        val presentation = taskActionPresentation(
            currentWorkerId = "slinger",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "OPTIONAL_JOIN",
            queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE,
            assignments = listOf(assignment("driver", "Водитель", "ACTIVE")),
            locallyPending = false,
            hasCurrentGroup = false,
        )

        assertThat(presentation.actions).containsExactly(WorkerTaskAction.JOIN)
        assertThat(presentation.message).contains("можно присоединиться")
        assertThat(
            selectedGroupForAction(
                WorkerTaskAction.JOIN,
                currentGroupId = null,
                queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE,
            ),
        ).isNull()
    }

    @Test
    fun `required secondary participation also uses join instead of a second take`() {
        val presentation = taskActionPresentation(
            currentWorkerId = "slinger",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "REQUIRED_JOIN",
            queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE,
            assignments = listOf(assignment("driver", "Водитель", "ACTIVE")),
            locallyPending = false,
            hasCurrentGroup = false,
        )

        assertThat(presentation.actions).containsExactly(WorkerTaskAction.JOIN)
        assertThat(presentation.message).contains("требуется присоединиться")
    }

    @Test
    fun `logistics completion auto selects only one ready evidence`() {
        assertThat(
            completionEvidenceId(
                queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE,
                readyEvidenceIds = setOf("photo-one"),
                selectedEvidenceId = null,
            ),
        ).isEqualTo("photo-one")
        assertThat(
            completionEvidenceId(
                queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE,
                readyEvidenceIds = setOf("photo-one", "photo-two"),
                selectedEvidenceId = null,
            ),
        ).isNull()
        assertThat(
            completionEvidenceId(
                queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE,
                readyEvidenceIds = setOf("photo-one", "photo-two"),
                selectedEvidenceId = "photo-two",
            ),
        ).isEqualTo("photo-two")
        assertThat(
            completionEvidenceId(
                queuePurpose = "GENERAL",
                readyEvidenceIds = setOf("photo-one"),
                selectedEvidenceId = "photo-one",
            ),
        ).isNull()
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

    private fun trip(cabins: List<DriverTripCabinDto>) = DriverTripDetailsDto(
        taskNumber = "123",
        tripNumber = 1,
        operationType = "SHIPMENT",
        clientName = "ООО Стройка",
        address = "Санкт-Петербург",
        latitude = 59.9,
        longitude = 30.3,
        primaryContactName = "Иван",
        primaryContactPhone = "+79990000001",
        additionalContacts = emptyList(),
        comment = null,
        desiredDeliveryWindows = emptyList(),
        scheduledDate = "2026-08-12",
        cabins = cabins,
    )
}
