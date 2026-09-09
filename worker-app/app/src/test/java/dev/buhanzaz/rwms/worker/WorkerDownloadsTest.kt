package dev.buhanzaz.rwms.worker

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.worker.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.database.WorkerOutboxEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerProblemReportDraftSnapshot
import dev.buhanzaz.rwms.worker.core.database.WorkerProblemReportStore
import org.junit.Test

class WorkerDownloadsTest {
    @Test
    fun `download projection hides successful evidence and encrypted storage fields`() {
        val pending = evidence("pending", state = "UPLOADING", percent = 63, lastError = null)
        val ready = evidence("ready", state = "READY", percent = 100, lastError = null)
        val duplicateReservation = outbox(
            operationId = pending.reservationOperationId,
            kind = WorkerLocalStore.OUTBOX_EVIDENCE_RESERVATION,
            state = WorkerLocalStore.OUTBOX_PENDING,
            error = null,
        )

        val items = workerDownloadItems(listOf(duplicateReservation), listOf(pending, ready))

        assertThat(items).hasSize(1)
        assertThat(items.single().percent).isEqualTo(63)
        assertThat(items.single().toString()).doesNotContain("encrypted-payload")
        assertThat(items.single().toString()).doesNotContain("encrypted-file")
        assertThat(WorkerDownloadItem::class.java.declaredFields.map { it.name })
            .containsNoneOf("encryptedPayload", "encryptedFilePath")
    }

    @Test
    fun `retrying outbox and failed evidence remain visible with safe error`() {
        val items = workerDownloadItems(
            outbox = listOf(
                outbox(
                    operationId = "action-1",
                    kind = WorkerLocalStore.OUTBOX_ACTION,
                    state = WorkerLocalStore.OUTBOX_RETRY,
                    error = "Нет связи с RWMS",
                ),
            ),
            evidence = listOf(
                evidence("failed", state = "UPLOADING", percent = 40, lastError = "Тайм-аут"),
            ),
        )

        assertThat(items).hasSize(2)
        assertThat(items.map { it.canRetry }).containsExactly(true, true)
        assertThat(items.mapNotNull { it.error }).containsExactly("Нет связи с RWMS", "Тайм-аут")
    }

    @Test
    fun `locally retained evidence for a terminal task is not shown as an active upload`() {
        val superseded = evidence(
            "terminal-task",
            state = WorkerLocalStore.EVIDENCE_SUPERSEDED,
            percent = 0,
            lastError = null,
        ).copy(reviewReason = "Задание уже завершено; локальная фотография сохранена на устройстве")

        val items = workerDownloadItems(outbox = emptyList(), evidence = listOf(superseded))

        assertThat(items).isEmpty()
        assertThat(superseded.encryptedFilePath).isEqualTo("encrypted-file")
    }

    @Test
    fun `blocked report remains readable and retryable after its task closes`() {
        val report = problemReport(WorkerProblemReportStore.OUTBOX_CONFLICT)
        val items = workerDownloadItems(emptyList(), emptyList(), listOf(report))

        assertThat(items).hasSize(1)
        assertThat(items.single().comment).isEqualTo("Дверь повреждена")
        assertThat(items.single().reportRetryId).isEqualTo(report.reportId)
        assertThat(items.single().canRetry).isTrue()
        assertThat(items.single().status).isEqualTo("Требуется внимание")
    }

    @Test
    fun `pending report has one card and its draft photos are never uploads`() {
        val report = problemReport(WorkerProblemReportStore.OUTBOX_PENDING)
        val items = workerDownloadItems(
            outbox = listOf(outbox(report.reportId, WorkerProblemReportStore.OUTBOX_PROBLEM_REPORT, report.state, null)),
            evidence = listOf(evidence("draft", "DRAFT", 0, null).copy(problemReportId = "draft-report")),
            reports = listOf(report),
        )

        assertThat(items).hasSize(1)
        assertThat(items.single().id).isEqualTo("report:${report.reportId}")
        assertThat(items.single().status).isEqualTo("В очереди")
        assertThat(items.single().canRetry).isFalse()
    }

    @Test
    fun `accepted report hides its card while unfinished report photos retain upload progress`() {
        val report = problemReport(WorkerProblemReportStore.OUTBOX_REPORTED)
        val photo = evidence("problem-photo", "UPLOADING", 45, null).copy(problemReportId = report.reportId)

        val items = workerDownloadItems(emptyList(), listOf(photo), listOf(report))

        assertThat(items).hasSize(1)
        assertThat(items.single().title).isEqualTo("Фото проблемы")
        assertThat(items.single().percent).isEqualTo(45)
    }

    private fun problemReport(state: String) = WorkerProblemReportDraftSnapshot(
        reportId = "report-1",
        entryId = "closed-entry",
        routeIndex = 0,
        comment = "Дверь повреждена",
        occurredAt = "2026-09-09T00:00:00Z",
        state = state,
        lastError = null,
        attachments = emptyList(),
    )

    private fun outbox(
        operationId: String,
        kind: String,
        state: String,
        error: String?,
    ) = WorkerOutboxEntity(
        operationId = operationId,
        userId = "worker",
        entryId = "entry-1",
        kind = kind,
        encryptedPayload = "encrypted-payload",
        expectedVersion = 1,
        state = state,
        retryCount = 1,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
        lastError = error,
    )

    private fun evidence(
        id: String,
        state: String,
        percent: Int,
        lastError: String?,
    ) = TaskEvidenceEntity(
        evidenceId = id,
        userId = "worker",
        entryId = "entry-1",
        routeIndex = 0,
        capturedAt = "2026-08-04T10:00:00Z",
        encryptedFilePath = "encrypted-file",
        fileName = "$id.jpg",
        contentType = "image/jpeg",
        sizeBytes = 100,
        sha256 = "a".repeat(64),
        reservationOperationId = "reservation-$id",
        uploadOperationId = "upload-$id",
        state = state,
        mediaId = null,
        mediaGeneration = null,
        reviewReason = null,
        uploadPercent = percent,
        lastError = lastError,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )
}
