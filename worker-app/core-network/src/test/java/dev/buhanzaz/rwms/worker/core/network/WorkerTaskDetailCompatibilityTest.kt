package dev.buhanzaz.rwms.worker.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test

class WorkerTaskDetailCompatibilityTest {
    @Test
    fun `cached detail decodes optional legacy content when package coordinates are present`() {
        val detail = Json { explicitNulls = false }.decodeFromString<WorkerTaskDetailDto>(
            """
            {
              "entryId":"entry-1",
              "version":8,
              "taskId":"task-1",
              "routeIndex":0,
              "routeStepIndex":0,
              "routeStepCount":1,
              "title":"Замена профлиста",
              "description":null,
              "object":null,
              "taskText":"Срочная работа",
              "scheduledDate":"2026-07-26",
              "deadlineAt":null,
              "priority":1,
              "queuePosition":0,
              "status":"IN_PROGRESS",
              "availabilityMode":"MANDATORY",
              "plannedDurationMinutes":90,
              "activeStartedAt":"2026-07-26T10:00:00Z",
              "activeWorkSeconds":0,
              "audienceSelectors":[],
              "assignments":[],
              "materials":[],
              "comments":[],
              "sourceMedia":[],
              "evidence":[{
                "evidenceId":"evidence-1",
                "version":1,
                "entryId":"entry-1",
                "routeIndex":0,
                "workerId":"worker-1",
                "workerGroupId":null,
                "capturedAt":"2026-07-26T10:00:00Z",
                "recordedAt":"2026-07-26T10:00:01Z",
                "state":"READY",
                "mediaId":"media-1",
                "mediaGeneration":1,
                "reviewReason":null
              }],
              "relatedSteps":[],
              "resultPhotoMinCount":1,
              "completionAllowed":true
            }
            """.trimIndent(),
        )

        assertThat(detail.works).isEmpty()
        assertThat(detail.source).isNull()
        assertThat(detail.evidence.single().contentType).isNull()
        assertThat(detail.evidence.single().readPath).isNull()
        assertThat(detail.evidence.single().thumbnailPath).isNull()
        assertThat(detail.timerSnapshot).isNull()
    }

    @Test
    fun `legacy work decodes without work level source media ids`() {
        val work = Json.decodeFromString<WorkerWorkDto>(
            """{"id":"work-1","name":"Замена листа","quantity":1.0,"unit":"шт","durationMinutes":30,"comment":null}""",
        )

        assertThat(work.sourceMediaIds).isEmpty()
    }

    @Test
    fun `server timer snapshot decodes without deriving time on the device`() {
        val detail = Json { explicitNulls = false }.decodeFromString<WorkerTaskDetailDto>(
            """
            {
              "entryId":"entry-1",
              "version":9,
              "taskId":"task-1",
              "routeIndex":0,
              "routeStepIndex":0,
              "routeStepCount":1,
              "title":"Замена профлиста",
              "description":null,
              "object":null,
              "taskText":"Срочная работа",
              "scheduledDate":"2026-07-26",
              "deadlineAt":null,
              "priority":1,
              "queuePosition":0,
              "status":"IN_PROGRESS",
              "availabilityMode":"MANDATORY",
              "plannedDurationMinutes":90,
              "activeStartedAt":"2026-07-26T10:00:00Z",
              "activeWorkSeconds":0,
              "timerSnapshot":{
                "countedActiveSeconds":900,
                "remainingSeconds":4500,
                "remainingPercent":83.3333,
                "timerState":"BREAK",
                "nextTransitionAt":"2026-07-26T10:15:00Z",
                "serverTime":"2026-07-26T10:05:00Z"
              },
              "audienceSelectors":[],
              "assignments":[],
              "materials":[],
              "comments":[],
              "sourceMedia":[],
              "evidence":[],
              "relatedSteps":[],
              "resultPhotoMinCount":1,
              "completionAllowed":false
            }
            """.trimIndent(),
        )

        assertThat(detail.timerSnapshot).isEqualTo(
            WorkerTaskTimerSnapshotDto(
                countedActiveSeconds = 900,
                remainingSeconds = 4_500,
                remainingPercent = 83.3333,
                timerState = "BREAK",
                nextTransitionAt = "2026-07-26T10:15:00Z",
                serverTime = "2026-07-26T10:05:00Z",
            ),
        )
    }
}
