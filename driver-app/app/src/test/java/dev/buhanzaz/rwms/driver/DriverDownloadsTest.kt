package dev.buhanzaz.rwms.driver

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.database.TaskEvidenceEntity
import dev.buhanzaz.rwms.driver.core.database.DriverLocalStore
import dev.buhanzaz.rwms.driver.core.database.DriverOutboxEntity
import org.junit.Test

class DriverDownloadsTest {
    @Test
    fun `main menu contains exactly warehouse work and downloads`() {
        val items = driverMenuItems(pendingDownloadCount = 2)

        assertThat(items.map { it.title }).containsExactly("Работа на складе", "Загрузки").inOrder()
        assertThat(items[1].description).contains("2")
    }

    @Test
    fun `download projection hides successful evidence and encrypted storage fields`() {
        val pending = evidence("pending", state = "UPLOADING", percent = 63, lastError = null)
        val ready = evidence("ready", state = "READY", percent = 100, lastError = null)
        val duplicateReservation = outbox(
            operationId = pending.reservationOperationId,
            kind = DriverLocalStore.OUTBOX_EVIDENCE_RESERVATION,
            state = DriverLocalStore.OUTBOX_PENDING,
            error = null,
        )

        val items = driverDownloadItems(listOf(duplicateReservation), listOf(pending, ready))

        assertThat(items).hasSize(1)
        assertThat(items.single().percent).isEqualTo(63)
        assertThat(items.single().toString()).doesNotContain("encrypted-payload")
        assertThat(items.single().toString()).doesNotContain("encrypted-file")
        assertThat(DriverDownloadItem::class.java.declaredFields.map { it.name })
            .containsNoneOf("encryptedPayload", "encryptedFilePath")
    }

    @Test
    fun `retrying outbox and failed evidence remain visible with safe error`() {
        val items = driverDownloadItems(
            outbox = listOf(
                outbox(
                    operationId = "action-1",
                    kind = DriverLocalStore.OUTBOX_ACTION,
                    state = DriverLocalStore.OUTBOX_RETRY,
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

    private fun outbox(
        operationId: String,
        kind: String,
        state: String,
        error: String?,
    ) = DriverOutboxEntity(
        operationId = operationId,
        userId = "driver",
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
        userId = "driver",
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
