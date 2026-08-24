package dev.buhanzaz.rwms.driver.feature.taskdetail

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.database.DriverAssignmentEntity
import dev.buhanzaz.rwms.driver.core.network.DriverTripActualEquipmentDto
import dev.buhanzaz.rwms.driver.core.network.DriverTripCabinDto
import dev.buhanzaz.rwms.driver.core.network.DriverTripDesiredDeliveryWindowDto
import dev.buhanzaz.rwms.driver.core.network.DriverTripDesiredEquipmentDto
import dev.buhanzaz.rwms.driver.core.network.DriverTripDetailsDto
import dev.buhanzaz.rwms.driver.core.network.DriverTaskTimerSnapshotDto
import dev.buhanzaz.rwms.driver.core.network.DriverMediaReferenceDto
import dev.buhanzaz.rwms.driver.core.network.DriverWorkDto
import dev.buhanzaz.rwms.driver.core.ui.cabinNumberForDisplay
import java.time.Instant
import java.time.LocalDate
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
        val presentation = photoCapturePresentation(
            hasLoadedDetail = false,
            currentDriverId = "driver-current",
            effectiveTaskStatus = "IN_PROGRESS",
            assignments = listOf(assignment("driver-current", "Текущий", "ACTIVE")),
            hasPendingTake = false,
        )

        assertThat(presentation.enabled).isFalse()
        assertThat(presentation.message).contains("Загружаем карточку")
    }

    @Test
    fun `waiting screenshot state tells driver to take task before adding photo`() {
        val presentation = photoCapturePresentation(
            hasLoadedDetail = true,
            currentDriverId = "driver-current",
            effectiveTaskStatus = "WAITING",
            assignments = emptyList(),
            hasPendingTake = false,
        )

        assertThat(presentation.enabled).isFalse()
        assertThat(presentation.message).contains("Сначала нажмите «Взять»")
    }

    @Test
    fun `optimistic pending take enables photo while assignment projection catches up`() {
        val presentation = photoCapturePresentation(
            hasLoadedDetail = true,
            currentDriverId = "driver-current",
            effectiveTaskStatus = "IN_PROGRESS",
            assignments = emptyList(),
            hasPendingTake = true,
        )

        assertThat(presentation.enabled).isTrue()
        assertThat(presentation.message).isNull()
    }

    @Test
    fun `active or paused current participant can add photo to in progress task`() {
        listOf("ACTIVE", "PAUSED").forEach { assignmentStatus ->
            val presentation = photoCapturePresentation(
                hasLoadedDetail = true,
                currentDriverId = "driver-current",
                effectiveTaskStatus = "IN_PROGRESS",
                assignments = listOf(assignment("driver-current", "Текущий", assignmentStatus)),
                hasPendingTake = false,
            )

            assertThat(presentation.enabled).isTrue()
            assertThat(presentation.message).isNull()
        }
    }

    @Test
    fun `paused task tells participant to resume before adding photo`() {
        val presentation = photoCapturePresentation(
            hasLoadedDetail = true,
            currentDriverId = "driver-current",
            effectiveTaskStatus = "PAUSED",
            assignments = listOf(assignment("driver-current", "Текущий", "PAUSED")),
            hasPendingTake = false,
        )

        assertThat(presentation.enabled).isFalse()
        assertThat(presentation.message).contains("Нажмите «Продолжить»")
    }

    @Test
    fun `nonparticipant cannot add photo to another drivers active task`() {
        val presentation = photoCapturePresentation(
            hasLoadedDetail = true,
            currentDriverId = "driver-current",
            effectiveTaskStatus = "IN_PROGRESS",
            assignments = listOf(assignment("driver-other", "Другой", "ACTIVE")),
            hasPendingTake = false,
        )

        assertThat(presentation.enabled).isFalse()
        assertThat(presentation.message).contains("только водитель, который взял")
    }

    @Test
    fun `ready and locally stored non ready photo counts are presented separately`() {
        val presentation = evidenceCountPresentation(
            readyEvidenceCount = 1,
            locallyStoredEvidenceStates = listOf("READY", "CAPTURED", "UPLOADING"),
        )

        assertThat(presentation.readyLabel).isEqualTo("Готово на сервере: 1")
        assertThat(presentation.locallyStoredNotReadyLabel)
            .isEqualTo("Сохранено локально, ещё не готово: 2")
    }

    @Test
    fun `logistics audience wording distinguishes shared warehouse task`() {
        assertThat(logisticsTaskAudienceLabel(ASSIGNED_DRIVER_AUDIENCE_MODE))
            .isEqualTo("Логистическое задание · индивидуальное назначение")
        assertThat(logisticsTaskAudienceLabel(WAREHOUSE_DRIVERS_AUDIENCE_MODE))
            .isEqualTo("Логистическое задание · общее для водителей склада")
        assertThat(logisticsTaskAudienceLabel(null)).isEqualTo("Логистическое задание")
    }

    @Test
    fun `future shared logistics supports preview and claim but today does not`() {
        val today = LocalDate.of(2026, 8, 23)

        assertThat(
            canReadRichLogisticsDetails(
                sourceType = "LOGISTICS_DRIVER_TASK",
                driverAudienceMode = WAREHOUSE_DRIVERS_AUDIENCE_MODE,
                scheduledDate = "2026-08-24",
                today = today,
            ),
        ).isTrue()
        assertThat(
            canClaimFutureLogisticsTask(
                sourceType = "LOGISTICS_DRIVER_TASK",
                driverAudienceMode = WAREHOUSE_DRIVERS_AUDIENCE_MODE,
                scheduledDate = "2026-08-24",
                today = today,
            ),
        ).isTrue()
        assertThat(
            canReadRichLogisticsDetails(
                sourceType = "LOGISTICS_DRIVER_TASK",
                driverAudienceMode = WAREHOUSE_DRIVERS_AUDIENCE_MODE,
                scheduledDate = "2026-08-23",
                today = today,
            ),
        ).isFalse()
        assertThat(
            canClaimFutureLogisticsTask(
                sourceType = "LOGISTICS_DRIVER_TASK",
                driverAudienceMode = WAREHOUSE_DRIVERS_AUDIENCE_MODE,
                scheduledDate = "2026-08-23",
                today = today,
            ),
        ).isFalse()
    }

    @Test
    fun `assigned logistics keeps rich details independent of its scheduled date`() {
        assertThat(
            canReadRichLogisticsDetails(
                sourceType = "LOGISTICS_DRIVER_TASK",
                driverAudienceMode = ASSIGNED_DRIVER_AUDIENCE_MODE,
                scheduledDate = "2026-08-22",
                today = LocalDate.of(2026, 8, 23),
            ),
        ).isTrue()
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
    fun `terminal reservation failure explains that the local photo is retained`() {
        val presentation = evidencePresentation(
            state = "REVIEW_REQUIRED",
            uploadPercent = 0,
            mediaId = null,
            hasValidReservationPayload = false,
        )

        assertThat(presentation.status).isEqualTo("Фото сохранено локально")
        assertThat(presentation.message).contains("повторит отправку")
        assertThat(presentation.message).contains("иначе потребуется новое фото")
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
            DriverWorkDto(
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
        val fields = DriverWorkDto::class.java.declaredFields.map { it.name }
        assertThat(fields).contains("id")
        assertThat(fields.contains("code")).isFalse()
        assertThat(fields.contains("price")).isFalse()
    }

    @Test
    fun `work photos are separated from general task photos by source media ids`() {
        val workPhoto = media("media-work")
        val generalPhoto = media("media-general")
        val work = DriverWorkDto(
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
            val snapshot = DriverTaskTimerSnapshotDto(
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
        val snapshot = DriverTaskTimerSnapshotDto(
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
    fun `take is unavailable without a manager selected group or while driver is disabled`() {
        val withoutGroup = taskActionPresentation(
            currentDriverId = "driver-current",
            taskStatus = "WAITING",
            availabilityMode = "AVAILABLE",
            assignments = emptyList(),
            locallyPending = false,
            hasCurrentGroup = false,
        )
        val disabled = taskActionPresentation(
            currentDriverId = "driver-current",
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

    private fun media(id: String) = DriverMediaReferenceDto(
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
            selectedGroupForAction(DriverTaskAction.TAKE, "group-current", "GENERAL"),
        ).isEqualTo("group-current")

        val failure = assertThrows(IllegalArgumentException::class.java) {
            selectedGroupForAction(DriverTaskAction.TAKE, null, "GENERAL")
        }
        assertThat(failure).hasMessageThat().contains("Руководитель")
    }

    @Test
    fun `driver can take a logistics task without a current group`() {
        val presentation = taskActionPresentation(
            currentDriverId = "driver",
            taskStatus = "WAITING",
            availabilityMode = "AVAILABLE",
            queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE,
            assignments = emptyList(),
            locallyPending = false,
            hasCurrentGroup = false,
        )

        assertThat(presentation.actions).containsExactly(DriverTaskAction.TAKE)
        assertThat(
            selectedGroupForAction(
                DriverTaskAction.TAKE,
                currentGroupId = null,
                queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE,
            ),
        ).isNull()
    }

    @Test
    fun `driver does not join an optional secondary task`() {
        val presentation = taskActionPresentation(
            currentDriverId = "slinger",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "OPTIONAL_JOIN",
            queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE,
            assignments = listOf(assignment("driver", "Водитель", "ACTIVE")),
            locallyPending = false,
            hasCurrentGroup = false,
        )

        assertThat(presentation.actions).isEmpty()
        assertThat(presentation.message).contains("другой")
    }

    @Test
    fun `driver does not join a required secondary task`() {
        val presentation = taskActionPresentation(
            currentDriverId = "slinger",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "REQUIRED_JOIN",
            queuePurpose = LOGISTICS_DRIVER_QUEUE_PURPOSE,
            assignments = listOf(assignment("driver", "Водитель", "ACTIVE")),
            locallyPending = false,
            hasCurrentGroup = false,
        )

        assertThat(presentation.actions).isEmpty()
        assertThat(presentation.message).contains("другой")
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
            currentDriverId = "driver-current",
            taskStatus = "WAITING",
            availabilityMode = "AVAILABLE",
            assignments = emptyList(),
            locallyPending = false,
        )
        val assignedToOther = taskActionPresentation(
            currentDriverId = "driver-current",
            taskStatus = "WAITING",
            availabilityMode = "AVAILABLE",
            assignments = listOf(assignment("driver-other", "Android Demo", "ACTIVE")),
            locallyPending = false,
        )

        assertThat(unassigned.actions).containsExactly(DriverTaskAction.TAKE)
        assertThat(unassigned.actionsEnabled).isTrue()
        assertThat(assignedToOther.actions).isEmpty()
        assertThat(assignedToOther.message).isEqualTo("Задание выполняет другой рабочий")
    }

    @Test
    fun `secondary driver waits for the primary driver before taking the task`() {
        val presentation = taskActionPresentation(
            currentDriverId = "driver-secondary",
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
    fun `active assignment allows only its driver to pause or complete`() {
        val mine = taskActionPresentation(
            currentDriverId = "driver-current",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "AVAILABLE",
            assignments = listOf(assignment("driver-current", "Текущий", "ACTIVE")),
            locallyPending = false,
        )
        val other = taskActionPresentation(
            currentDriverId = "driver-current",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "AVAILABLE",
            assignments = listOf(assignment("driver-other", "Android Demo", "ACTIVE")),
            locallyPending = false,
        )
        val mineButPaused = taskActionPresentation(
            currentDriverId = "driver-current",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "AVAILABLE",
            assignments = listOf(assignment("driver-current", "Текущий", "PAUSED")),
            locallyPending = false,
        )

        assertThat(mine.actions).containsExactly(
            DriverTaskAction.PAUSE,
            DriverTaskAction.COMPLETE,
        ).inOrder()
        assertThat(other.actions).isEmpty()
        assertThat(other.message).isEqualTo("Задание выполняет другой рабочий")
        assertThat(other.performers).containsExactly("Android Demo")
        assertThat(mineButPaused.actions).isEmpty()
    }

    @Test
    fun `mandatory secondary assignment remains unavailable to the driver client`() {
        val presentation = taskActionPresentation(
            currentDriverId = "driver-current",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "MANDATORY",
            assignments = listOf(assignment("driver-other", "Водитель", "ACTIVE")),
            locallyPending = false,
        )

        assertThat(presentation.actions).isEmpty()
        assertThat(presentation.actionsEnabled).isFalse()
        assertThat(presentation.takeLabel).isEqualTo("Взять")
        assertThat(presentation.message).isEqualTo("Задание выполняет другой рабочий")
        assertThat(presentation.performers).containsExactly("Водитель")
    }

    @Test
    fun `mandatory in-progress task without another live assignment cannot be taken`() {
        val presentation = taskActionPresentation(
            currentDriverId = "driver-current",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "MANDATORY",
            assignments = emptyList(),
            locallyPending = false,
        )

        assertThat(presentation.actions).isEmpty()
        assertThat(presentation.actionsEnabled).isFalse()
    }

    @Test
    fun `paused assignment allows only its driver to resume`() {
        val mine = taskActionPresentation(
            currentDriverId = "driver-current",
            taskStatus = "PAUSED",
            availabilityMode = "AVAILABLE",
            assignments = listOf(assignment("driver-current", "Текущий", "PAUSED")),
            locallyPending = false,
        )
        val other = taskActionPresentation(
            currentDriverId = "driver-current",
            taskStatus = "PAUSED",
            availabilityMode = "AVAILABLE",
            assignments = listOf(assignment("driver-other", "Android Demo", "PAUSED")),
            locallyPending = false,
        )

        assertThat(mine.actions).containsExactly(DriverTaskAction.RESUME)
        assertThat(other.actions).isEmpty()
        assertThat(other.message).isEqualTo("Задание выполняет другой рабочий")
    }

    @Test
    fun `locally pending task keeps its action buttons disabled`() {
        val presentation = taskActionPresentation(
            currentDriverId = "driver-current",
            taskStatus = "IN_PROGRESS",
            availabilityMode = "AVAILABLE",
            assignments = listOf(assignment("driver-current", "Текущий", "ACTIVE")),
            locallyPending = true,
        )

        assertThat(presentation.actions).containsExactly(
            DriverTaskAction.PAUSE,
            DriverTaskAction.COMPLETE,
        ).inOrder()
        assertThat(presentation.actionsEnabled).isFalse()
        assertThat(presentation.message).isEqualTo("Действие ожидает синхронизации")
    }

    private fun assignment(
        driverId: String,
        driverName: String,
        status: String,
    ) = DriverAssignmentEntity(
        localId = "user:entry:$driverId",
        userId = "driver-current",
        entryId = "entry",
        assignmentId = "assignment-$driverId",
        driverId = driverId,
        driverName = driverName,
        driverGroupId = "group",
        driverGroupName = "Разнорабочие",
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
