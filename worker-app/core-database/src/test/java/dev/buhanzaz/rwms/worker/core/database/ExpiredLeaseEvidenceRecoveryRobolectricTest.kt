package dev.buhanzaz.rwms.worker.core.database

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies that only a retained pre-fix offline-lease result-photo failure is safely rebuilt after
 * a fresh authenticated context, without changing its original evidence identity or capture time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExpiredLeaseEvidenceRecoveryRobolectricTest {
    private lateinit var database: WorkerDatabase
    private lateinit var store: WorkerLocalStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            WorkerDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = WorkerLocalStore(
            database,
            PendingPayloadCipher(SecretKeySpec(ByteArray(32) { 0x2a }, "AES")),
            Json,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `requeues only expired lease evidence with a fresh lease and original capture time`() = runTest {
        val original = evidence(
            evidenceId = "evidence-expired",
            state = "REVIEW_REQUIRED",
            reviewReason = WorkerLocalStore.EXPIRED_OFFLINE_LEASE_ERROR,
        )
        val unrelated = evidence(
            evidenceId = "evidence-unrelated",
            state = "REVIEW_REQUIRED",
            reviewReason = "Фотография относится к другому шагу задания",
        )
        database.evidenceDao().upsert(original)
        database.evidenceDao().upsert(unrelated)

        val restored = store.requeueExpiredLeaseEvidenceReservations(USER_ID, "fresh-lease")

        assertThat(restored).isEqualTo(1)
        val recovered = database.evidenceDao().evidence(USER_ID, original.evidenceId)
        assertThat(recovered?.state).isEqualTo(WorkerLocalStore.EVIDENCE_CAPTURED)
        assertThat(recovered?.capturedAt).isEqualTo(original.capturedAt)
        assertThat(recovered?.reviewReason).isNull()
        assertThat(recovered?.lastError).isNull()
        assertThat(database.evidenceDao().evidence(USER_ID, unrelated.evidenceId)?.state)
            .isEqualTo("REVIEW_REQUIRED")

        val operation = database.outboxDao().pending(USER_ID).single()
        val payload = Json.decodeFromString<PendingEvidenceReservation>(
            store.decryptOutboxPayload(operation),
        )
        assertThat(operation.operationId).isEqualTo(original.reservationOperationId)
        assertThat(payload.evidenceId).isEqualTo(original.evidenceId)
        assertThat(payload.capturedAt).isEqualTo(original.capturedAt)
        assertThat(payload.offlineLeaseId).isEqualTo("fresh-lease")
    }

    private fun evidence(
        evidenceId: String,
        state: String,
        reviewReason: String,
    ) = TaskEvidenceEntity(
        evidenceId = evidenceId,
        userId = USER_ID,
        entryId = "entry-1",
        routeIndex = 2,
        capturedAt = "2026-08-21T10:15:00Z",
        encryptedFilePath = "encrypted-$evidenceId",
        fileName = "$evidenceId.webp",
        contentType = "image/webp",
        sizeBytes = 1024,
        sha256 = "a".repeat(64),
        reservationOperationId = "reservation-$evidenceId",
        uploadOperationId = "upload-$evidenceId",
        state = state,
        mediaId = null,
        mediaGeneration = null,
        reviewReason = reviewReason,
        uploadPercent = 0,
        lastError = "Действие создано вне срока offline lease",
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private companion object {
        const val USER_ID = "worker-current"
    }
}
