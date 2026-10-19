package dev.buhanzaz.rwms.worker.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkerLogisticsContractTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `worker category decodes active shared task as optional join work`() {
        val category = json.decodeFromString<WorkerCategoryDto>(
            """
            {
              "queueId":"queue-joint-loading",
              "name":"Совместная погрузка",
              "type":"MOVEMENT",
              "queuePurpose":"LOGISTICS_DRIVER",
              "groupIds":["group-slingers"],
              "sortOrder":10,
              "audienceModes":["OPTIONAL_JOIN"],
              "resultPhotoMinCount":1
            }
            """.trimIndent(),
        )

        assertThat(category.queuePurpose).isEqualTo("LOGISTICS_DRIVER")
        assertThat(category.groupIds).containsExactly("group-slingers")
        assertThat(category.audienceModes).containsExactly("OPTIONAL_JOIN")
        assertThat(category.resultPhotoMinCount).isEqualTo(1)
    }

    @Test
    fun `worker feed decodes only an active task available for secondary join`() {
        val fixture =
            """
            {
              "entryId":"entry",
              "version":4,
              "taskId":"task",
              "routeIndex":41,
              "routeStepIndex":1,
              "routeStepCount":3,
              "entryType":"REAL",
              "pinned":true,
              "title":"Погрузить бытовку",
              "unitNumber":"БТ-1",
              "taskText":null,
              "scheduledDate":"2026-08-10",
              "deadlineAt":null,
              "priority":3,
              "queuePosition":0,
              "status":"IN_PROGRESS",
              "availabilityMode":"OPTIONAL_JOIN",
              "plannedDurationMinutes":null,
              "activeStartedAt":"2026-08-10T08:00:00Z",
              "activeWorkSeconds":120,
              "assignments":[{
                "id":"assignment-primary",
                "workerId":"11111111-1111-1111-1111-111111111111",
                "workerName":"Основной исполнитель",
                "workerGroupId":null,
                "workerGroupName":null,
                "status":"ACTIVE",
                "assignedAt":"2026-08-10T08:00:00Z",
                "startedAt":"2026-08-10T08:00:00Z",
                "pausedAt":null,
                "finishedAt":null
              }],
              "readyEvidenceCount":0,
              "resultPhotoMinCount":1,
              "driverAudience":null,
              "timerSnapshot":null
            }
            """.trimIndent()
        val entry = json.decodeFromString<WorkerFeedEntryDto>(fixture)

        assertThat(entry.status).isEqualTo("IN_PROGRESS")
        assertThat(entry.availabilityMode).isEqualTo("OPTIONAL_JOIN")
        assertThat(entry.assignments).hasSize(1)
        assertThat(entry.routeIndex).isEqualTo(41)
        assertThat(entry.routeStepIndex).isEqualTo(1)
        assertThat(entry.routeStepCount).isEqualTo(3)
        assertThat(entry.entryType).isEqualTo("REAL")
        assertThat(entry.pinned).isTrue()
        assertThat(entry.resultPhotoMinCount).isEqualTo(1)
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<WorkerFeedEntryDto>(
                fixture.replace("  \"routeStepIndex\":1,\n", ""),
            )
        }
    }

    @Test
    fun `complete action carries the selected ready evidence`() {
        val request = WorkerActionRequestDto(
            operationId = "operation",
            action = "COMPLETE",
            expectedVersion = 8,
            workerGroupId = "group-slingers",
            evidenceId = "evidence-ready",
            occurredAt = "2026-07-30T10:00:00Z",
            offlineLeaseId = "lease",
        )

        val payload = json.encodeToString(request)

        assertThat(payload).contains("\"action\":\"COMPLETE\"")
        assertThat(payload).contains("\"evidenceId\":\"evidence-ready\"")
        assertThat(payload).contains("\"workerGroupId\":\"group-slingers\"")
    }
}
