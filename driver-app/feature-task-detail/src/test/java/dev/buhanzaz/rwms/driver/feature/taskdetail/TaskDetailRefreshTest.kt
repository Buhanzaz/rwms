package dev.buhanzaz.rwms.driver.feature.taskdetail

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.network.DriverTripDetailsDto
import dev.buhanzaz.rwms.driver.core.network.TaskSourceReferenceDto
import dev.buhanzaz.rwms.driver.core.network.DriverTaskDetailDto
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Verifies the fenced task-board-to-logistics read orchestration used by the detail ViewModel. */
class TaskDetailRefreshTest {
    @Test
    fun `logistics source persists task board detail then reads exact source task`() = runTest {
        val detail = detailWithSource("LOGISTICS_DRIVER_TASK", "driver-task-1")
        val trip = tripDetails()
        var persisted: DriverTaskDetailDto? = null
        var requestedSourceId: String? = null

        val outcome = loadTaskDetail(
            fetchDetail = { detail },
            persistDetail = { persisted = it },
            fetchLogisticsTrip = { sourceId ->
                requestedSourceId = sourceId
                trip
            },
            isCurrent = { true },
        )

        assertThat(persisted).isEqualTo(detail)
        assertThat(requestedSourceId).isEqualTo("driver-task-1")
        assertThat(outcome.accepted).isTrue()
        assertThat(outcome.logisticsRequested).isTrue()
        assertThat(outcome.tripDetails).isEqualTo(trip)
        assertThat(outcome.ordinaryError).isNull()
        assertThat(outcome.tripError).isNull()
    }

    @Test
    fun `non logistics source never calls logistics`() = runTest {
        var logisticsCalls = 0

        val outcome = loadTaskDetail(
            fetchDetail = { detailWithSource("MAINTENANCE_REPAIR", "repair-1") },
            persistDetail = {},
            fetchLogisticsTrip = {
                logisticsCalls += 1
                tripDetails()
            },
            isCurrent = { true },
        )

        assertThat(logisticsCalls).isEqualTo(0)
        assertThat(outcome.accepted).isTrue()
        assertThat(outcome.logisticsRequested).isFalse()
        assertThat(outcome.tripDetails).isNull()
    }

    @Test
    fun `logistics failure preserves persisted ordinary detail and reports only trip error`() = runTest {
        val detail = detailWithSource("LOGISTICS_DRIVER_TASK", "driver-task-1")
        var persisted: DriverTaskDetailDto? = null

        val outcome = loadTaskDetail(
            fetchDetail = { detail },
            persistDetail = { persisted = it },
            fetchLogisticsTrip = { error("logistics unavailable") },
            isCurrent = { true },
        )

        assertThat(persisted).isEqualTo(detail)
        assertThat(outcome.accepted).isTrue()
        assertThat(outcome.ordinaryError).isNull()
        assertThat(outcome.tripDetails).isNull()
        assertThat(outcome.tripError).contains("данные ходки")
    }

    @Test
    fun `logistics response is rejected after refresh key becomes stale`() = runTest {
        var current = true

        val outcome = loadTaskDetail(
            fetchDetail = { detailWithSource("LOGISTICS_DRIVER_TASK", "driver-task-1") },
            persistDetail = {},
            fetchLogisticsTrip = {
                current = false
                tripDetails()
            },
            isCurrent = { current },
        )

        assertThat(outcome.accepted).isFalse()
        assertThat(outcome.tripDetails).isNull()
        assertThat(outcome.tripError).isNull()
    }

    private fun detailWithSource(type: String, sourceId: String): DriverTaskDetailDto =
        DriverTaskDetailDto(
            entryId = "entry-1",
            version = 1,
            taskId = "task-1",
            source = TaskSourceReferenceDto(type = type, sourceId = sourceId),
            routeIndex = 0,
            title = "Задание",
            description = null,
            taskObject = null,
            taskText = null,
            scheduledDate = "2026-08-11",
            deadlineAt = null,
            priority = 3,
            queuePosition = 0,
            status = "WAITING",
            availabilityMode = "MANDATORY",
            plannedDurationMinutes = null,
            activeStartedAt = null,
            activeWorkSeconds = 0,
            audienceSelectors = emptyList(),
            assignments = emptyList(),
            materials = emptyList(),
            works = emptyList(),
            comments = emptyList(),
            sourceMedia = emptyList(),
            evidence = emptyList(),
            relatedSteps = emptyList(),
            resultPhotoMinCount = 1,
            completionAllowed = false,
        )

    private fun tripDetails(): DriverTripDetailsDto = DriverTripDetailsDto(
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
        cabins = emptyList(),
    )
}
