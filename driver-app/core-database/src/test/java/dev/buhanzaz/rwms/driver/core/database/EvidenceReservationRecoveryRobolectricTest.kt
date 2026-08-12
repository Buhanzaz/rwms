package dev.buhanzaz.rwms.driver.core.database

import android.os.SystemClock
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Verifies atomic, lease-bounded recovery of photo reservations after TAKE or RESUME. */
@RunWith(RobolectricTestRunner::class)
class EvidenceReservationRecoveryRobolectricTest {
    @Test
    fun takeAndResumeQueueActionBeforeRecoveredReservationAndPreserveEvidenceIdentity() = runTest {
        withStore { database, store ->
            seedSession(database)
            database.taskDao().upsertAll(
                listOf(
                    task(TAKE_ENTRY_ID, "WAITING"),
                    task(RESUME_ENTRY_ID, "PAUSED"),
                    task(UNRELATED_ENTRY_ID, "WAITING"),
                ),
            )
            val takeEvidence = evidence(
                evidenceId = TAKE_EVIDENCE_ID,
                entryId = TAKE_ENTRY_ID,
                capturedAt = CAPTURED_AT,
            )
            val resumeEvidence = evidence(
                evidenceId = RESUME_EVIDENCE_ID,
                entryId = RESUME_ENTRY_ID,
                capturedAt = CAPTURED_AT,
            )
            val unrelatedEvidence = evidence(
                evidenceId = UNRELATED_EVIDENCE_ID,
                entryId = UNRELATED_ENTRY_ID,
                capturedAt = CAPTURED_AT,
            )
            database.evidenceDao().upsert(takeEvidence)
            database.evidenceDao().upsert(resumeEvidence)
            database.evidenceDao().upsert(unrelatedEvidence)

            store.applyOptimisticAction(action(TAKE_ENTRY_ID, TAKE_ACTION_ID, "TAKE"))
            store.applyOptimisticAction(action(RESUME_ENTRY_ID, RESUME_ACTION_ID, "RESUME"))

            assertRecoveredPair(database, store, TAKE_ENTRY_ID, TAKE_ACTION_ID, takeEvidence)
            assertRecoveredPair(database, store, RESUME_ENTRY_ID, RESUME_ACTION_ID, resumeEvidence)
            assertThat(database.evidenceDao().evidence(USER_ID, UNRELATED_EVIDENCE_ID))
                .isEqualTo(unrelatedEvidence)
            assertThat(database.outboxDao().operationCount(UNRELATED_EVIDENCE_ID)).isEqualTo(0)
        }
    }

    @Test
    fun photosOutsideCurrentLeaseAreNotRequeued() = runTest {
        withStore { database, store ->
            seedSession(database)
            database.taskDao().upsertAll(listOf(task(TAKE_ENTRY_ID, "WAITING")))
            val beforeLease = evidence(
                evidenceId = TAKE_EVIDENCE_ID,
                entryId = TAKE_ENTRY_ID,
                capturedAt = Instant.ofEpochMilli(LEASE_ISSUED_AT - 1).toString(),
            )
            val afterLease = evidence(
                evidenceId = SECOND_EVIDENCE_ID,
                entryId = TAKE_ENTRY_ID,
                capturedAt = Instant.ofEpochMilli(LEASE_EXPIRES_AT + 1).toString(),
            )
            database.evidenceDao().upsert(beforeLease)
            database.evidenceDao().upsert(afterLease)

            store.applyOptimisticAction(action(TAKE_ENTRY_ID, TAKE_ACTION_ID, "TAKE"))

            assertThat(store.pendingOutbox(USER_ID).map { it.kind })
                .containsExactly(DriverLocalStore.OUTBOX_ACTION)
            assertThat(database.evidenceDao().evidence(USER_ID, TAKE_EVIDENCE_ID))
                .isEqualTo(beforeLease)
            assertThat(database.evidenceDao().evidence(USER_ID, SECOND_EVIDENCE_ID))
                .isEqualTo(afterLease)
        }
    }

    @Test
    fun existingReservationIsNotDuplicatedOrRewritten() = runTest {
        withStore { database, store ->
            seedSession(database)
            database.taskDao().upsertAll(listOf(task(TAKE_ENTRY_ID, "WAITING")))
            val rejected = evidence(
                evidenceId = TAKE_EVIDENCE_ID,
                entryId = TAKE_ENTRY_ID,
                capturedAt = CAPTURED_AT,
            )
            database.evidenceDao().upsert(rejected)
            database.outboxDao().insert(
                DriverOutboxEntity(
                    operationId = rejected.reservationOperationId,
                    userId = USER_ID,
                    entryId = TAKE_ENTRY_ID,
                    kind = DriverLocalStore.OUTBOX_EVIDENCE_RESERVATION,
                    encryptedPayload = "already-encrypted",
                    expectedVersion = null,
                    state = DriverLocalStore.OUTBOX_PENDING,
                    retryCount = 0,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                    lastError = null,
                ),
            )

            store.applyOptimisticAction(action(TAKE_ENTRY_ID, TAKE_ACTION_ID, "TAKE"))

            assertThat(database.outboxDao().operationCount(rejected.reservationOperationId)).isEqualTo(1)
            assertThat(database.evidenceDao().evidence(USER_ID, TAKE_EVIDENCE_ID))
                .isEqualTo(rejected)
        }
    }

    @Test
    fun terminalFailureUnrelatedToStartingWorkIsNotRequeued() = runTest {
        withStore { database, store ->
            seedSession(database)
            database.taskDao().upsertAll(listOf(task(TAKE_ENTRY_ID, "WAITING")))
            val rejected = evidence(
                evidenceId = TAKE_EVIDENCE_ID,
                entryId = TAKE_ENTRY_ID,
                capturedAt = CAPTURED_AT,
            ).copy(reviewReason = "Фотография относится к другому шагу задания")
            database.evidenceDao().upsert(rejected)

            store.applyOptimisticAction(action(TAKE_ENTRY_ID, TAKE_ACTION_ID, "TAKE"))

            assertThat(store.pendingOutbox(USER_ID).map { it.kind })
                .containsExactly(DriverLocalStore.OUTBOX_ACTION)
            assertThat(database.evidenceDao().evidence(USER_ID, TAKE_EVIDENCE_ID))
                .isEqualTo(rejected)
        }
    }

    private suspend fun assertRecoveredPair(
        database: DriverDatabase,
        store: DriverLocalStore,
        entryId: String,
        actionId: String,
        original: TaskEvidenceEntity,
    ) {
        val pair = store.pendingOutbox(USER_ID).filter { it.entryId == entryId }
        assertThat(pair.map { it.kind }).containsExactly(
            DriverLocalStore.OUTBOX_ACTION,
            DriverLocalStore.OUTBOX_EVIDENCE_RESERVATION,
        ).inOrder()
        assertThat(pair[0].operationId).isEqualTo(actionId)
        assertThat(pair[0].createdAtEpochMillis).isLessThan(pair[1].createdAtEpochMillis)
        val reservation = Json.decodeFromString<PendingEvidenceReservation>(
            store.decryptOutboxPayload(pair[1]),
        )
        assertThat(reservation.operationId).isEqualTo(original.reservationOperationId)
        assertThat(reservation.evidenceId).isEqualTo(original.evidenceId)
        assertThat(reservation.capturedAt).isEqualTo(original.capturedAt)
        assertThat(reservation.offlineLeaseId).isEqualTo(LEASE_ID)
        assertThat(reservation.routeIndex).isEqualTo(original.routeIndex)
        assertThat(reservation.contentType).isEqualTo(original.contentType)
        assertThat(reservation.sizeBytes).isEqualTo(original.sizeBytes)
        assertThat(reservation.sha256).isEqualTo(original.sha256)
        val recovered = requireNotNull(database.evidenceDao().evidence(USER_ID, original.evidenceId))
        assertThat(recovered.state).isEqualTo(DriverLocalStore.EVIDENCE_CAPTURED)
        assertThat(recovered.mediaId).isNull()
        assertThat(recovered.mediaGeneration).isNull()
        assertThat(recovered.reviewReason).isNull()
        assertThat(recovered.uploadPercent).isEqualTo(0)
        assertThat(recovered.lastError).isNull()
    }

    private suspend fun withStore(
        block: suspend (DriverDatabase, DriverLocalStore) -> Unit,
    ) {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            DriverDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            block(
                database,
                DriverLocalStore(
                    database,
                    TestPendingPayloadCipher(),
                    Json,
                ),
            )
        } finally {
            database.close()
        }
    }

    private suspend fun seedSession(database: DriverDatabase) {
        database.sessionDao().upsert(
            DriverSessionEntity(
                userId = USER_ID,
                displayName = "Водитель",
                login = "driver",
                warehouseId = "warehouse",
                leaseId = LEASE_ID,
                leaseExpiresAtEpochMillis = LEASE_EXPIRES_AT,
                serverEpochMillis = LEASE_ISSUED_AT + 3_600_000,
                elapsedRealtimeAtSyncMillis = SystemClock.elapsedRealtime(),
                revision = 1,
                feedEtag = "\"feed-1\"",
                cacheHidden = false,
                updatedAtEpochMillis = LEASE_ISSUED_AT,
            ),
        )
    }

    private fun action(entryId: String, operationId: String, name: String) = OptimisticAction(
        userId = USER_ID,
        entryId = entryId,
        statusAfterAction = "IN_PROGRESS",
        payload = PendingDriverAction(
            operationId = operationId,
            action = name,
            expectedVersion = 5,
            driverGroupId = null,
            occurredAt = Instant.ofEpochMilli(LEASE_ISSUED_AT + 3_600_000).toString(),
            offlineLeaseId = LEASE_ID,
        ),
    )

    private fun task(entryId: String, status: String) = DriverTaskEntity(
        localId = "$USER_ID:$entryId",
        userId = USER_ID,
        entryId = entryId,
        taskId = "task-$entryId",
        version = 5,
        categoryId = "category",
        categoryName = "Перемещение",
        categorySortOrder = 0,
        title = "Переместить бытовку",
        unitNumber = "БТ-42",
        taskText = null,
        scheduledDate = "2026-08-12",
        deadlineAt = null,
        priority = 0,
        queuePosition = 0,
        status = status,
        availabilityMode = "AVAILABLE",
        plannedDurationMinutes = null,
        activeStartedAt = null,
        activeWorkSeconds = 0,
        readyEvidenceCount = 0,
        resultPhotoMinCount = 1,
        lastServerRevision = 1,
        locallyPending = false,
        updatedAtEpochMillis = LEASE_ISSUED_AT,
    )

    private fun evidence(
        evidenceId: String,
        entryId: String,
        capturedAt: String,
    ) = TaskEvidenceEntity(
        evidenceId = evidenceId,
        userId = USER_ID,
        entryId = entryId,
        routeIndex = 2,
        capturedAt = capturedAt,
        encryptedFilePath = "/encrypted/$evidenceId.jpg.enc",
        fileName = "$evidenceId.jpg",
        contentType = "image/jpeg",
        sizeBytes = 4_096,
        sha256 = "a".repeat(64),
        reservationOperationId = evidenceId,
        uploadOperationId = evidenceId,
        state = "REVIEW_REQUIRED",
        mediaId = null,
        mediaGeneration = null,
        reviewReason = "Добавить новую фотографию можно только к заданию в работе",
        uploadPercent = 73,
        lastError = "terminal",
        createdAtEpochMillis = LEASE_ISSUED_AT + 1,
        updatedAtEpochMillis = LEASE_ISSUED_AT + 2,
    )

    private companion object {
        const val USER_ID = "driver-current"
        const val LEASE_ID = "11111111-1111-4111-8111-111111111111"
        const val TAKE_ENTRY_ID = "entry-take"
        const val RESUME_ENTRY_ID = "entry-resume"
        const val UNRELATED_ENTRY_ID = "entry-unrelated"
        const val TAKE_ACTION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val RESUME_ACTION_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val TAKE_EVIDENCE_ID = "11111111-2222-4333-8444-555555555555"
        const val RESUME_EVIDENCE_ID = "22222222-3333-4444-8555-666666666666"
        const val UNRELATED_EVIDENCE_ID = "33333333-4444-4555-8666-777777777777"
        const val SECOND_EVIDENCE_ID = "44444444-5555-4666-8777-888888888888"
        val LEASE_ISSUED_AT = Instant.parse("2026-08-12T10:00:00Z").toEpochMilli()
        val LEASE_EXPIRES_AT = LEASE_ISSUED_AT + 86_400_000
        val CAPTURED_AT = Instant.ofEpochMilli(LEASE_ISSUED_AT + 1_800_000).toString()
    }

    /** Deterministic reversible payload codec used where Robolectric has no AndroidKeyStore. */
    private class TestPendingPayloadCipher : PendingPayloadCodec {
        override fun encrypt(plainText: String): String = plainText.reversed()

        override fun decrypt(encoded: String): String = encoded.reversed()
    }
}
