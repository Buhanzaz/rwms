package dev.buhanzaz.rwms.worker.core.database

import android.os.SystemClock
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import javax.crypto.spec.SecretKeySpec
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PendingActionGuardRobolectricTest {
    @Test
    fun `restoration retains item version and lease and blocks duplicate commands`() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), WorkerDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            database.sessionDao().upsert(session(now))
            database.taskDao().upsertAll(listOf(task(now).copy(status = "IN_PROGRESS")))
            val store = WorkerLocalStore(database, testPendingPayloadCipher(), Json)
            store.enqueueRequirementRestore(USER_ID, ENTRY_ID, 5, "hanger")
            val operation = database.outboxDao().pending(USER_ID).single()
            val pending = Json.decodeFromString<PendingWorkerAction>(store.decryptOutboxPayload(operation))
            assertThat(pending.action).isEqualTo("RESTORE_ITEM")
            assertThat(pending.itemId).isEqualTo("hanger")
            assertThat(pending.expectedVersion).isEqualTo(5)
            assertThat(pending.offlineLeaseId).isEqualTo("lease")
            assertThat(pending.operationId).isEqualTo(operation.operationId)
            assertThat(database.taskDao().task(USER_ID, ENTRY_ID)?.status).isEqualTo("IN_PROGRESS")
            assertThat(runCatching { store.enqueueRequirementRestore(USER_ID, ENTRY_ID, 5, "hanger") }.isFailure).isTrue()
            assertThat(database.outboxDao().pending(USER_ID)).containsExactly(operation)
        } finally {
            database.close()
        }
    }

    @Test
    fun `stale restoration cannot enter the outbox`() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), WorkerDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            database.sessionDao().upsert(session(now))
            database.taskDao().upsertAll(listOf(task(now)))
            val store = WorkerLocalStore(database, testPendingPayloadCipher(), Json)
            assertThat(runCatching { store.enqueueRequirementRestore(USER_ID, ENTRY_ID, 4, "hanger") }.isFailure).isTrue()
            assertThat(database.outboxDao().pending(USER_ID)).isEmpty()
        } finally {
            database.close()
        }
    }

    @Test
    fun `missing requirement retains one fenced report identity and rejects a second stale tap`() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            WorkerDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            database.sessionDao().upsert(session(now))
            val store = WorkerLocalStore(database, testPendingPayloadCipher(), Json)

            store.enqueueMissingItem(
                userId = USER_ID,
                entryId = ENTRY_ID,
                routeIndex = 2,
                expectedVersion = 5,
                itemId = "material-1",
                itemKind = "MATERIAL",
                itemName = "Краска",
            )

            val operation = database.outboxDao().pending(USER_ID).single()
            val payload = Json.decodeFromString<PendingWorkerProblemReport>(store.decryptOutboxPayload(operation))
            val secondTapFailure = runCatching {
                store.enqueueMissingItem(
                    userId = USER_ID,
                    entryId = ENTRY_ID,
                    routeIndex = 2,
                    expectedVersion = 5,
                    itemId = "work-2",
                    itemKind = "WORK",
                    itemName = "Покраска",
                )
            }.exceptionOrNull()

            assertThat(operation.operationId).isEqualTo(payload.operationId)
            assertThat(operation.expectedVersion).isEqualTo(5)
            assertThat(payload.expectedVersion).isEqualTo(5)
            assertThat(payload.missingItemIds).containsExactly("material-1")
            assertThat(secondTapFailure).hasMessageThat().contains("ожидает синхронизации")
            assertThat(database.outboxDao().pending(USER_ID)).containsExactly(operation)
        } finally {
            database.close()
        }
    }

    @Test
    fun `device reboot rejects new evidence and completion without mutating durable work`() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            WorkerDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            database.sessionDao().upsert(session(now, elapsed = SystemClock.elapsedRealtime() + 100_000))
            database.taskDao().upsertAll(listOf(task(now).copy(status = "IN_PROGRESS")))
            val store = WorkerLocalStore(database, testPendingPayloadCipher(), Json)

            val photoFailure = runCatching {
                store.enqueueEvidenceReservation(
                    userId = USER_ID, entryId = ENTRY_ID,
                    evidenceId = "00000000-0000-0000-0000-000000000001",
                    encryptedFilePath = "encrypted/evidence", fileName = "result.webp",
                    routeIndex = 0, capturedAt = "2026-07-26T10:00:00Z", sizeBytes = 42,
                    sha256 = "sha256", variantManifestJson = "[]", reservationPayload = "reservation",
                    completeAfterEvidence = true,
                )
            }.exceptionOrNull()
            val completionFailure = runCatching {
                store.applyOptimisticAction(
                    OptimisticAction(
                        userId = USER_ID, entryId = ENTRY_ID, statusAfterAction = "DONE",
                        payload = PendingWorkerAction(
                            operationId = "completion", action = "COMPLETE", expectedVersion = 5, workerGroupId = null,
                            occurredAt = "2026-07-26T10:00:00Z", offlineLeaseId = "lease",
                        ),
                    ),
                )
            }.exceptionOrNull()

            assertThat(photoFailure).hasMessageThat().contains("перезапуска")
            assertThat(completionFailure).hasMessageThat().contains("перезапуска")
            assertThat(database.evidenceDao().observeAll(USER_ID).first()).isEmpty()
            assertThat(database.outboxDao().pending(USER_ID)).isEmpty()
            assertThat(database.taskDao().task(USER_ID, ENTRY_ID)?.status).isEqualTo("IN_PROGRESS")
        } finally {
            database.close()
        }
    }

    @Test
    fun `final photo transaction rolls back when another task command is already queued`() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            WorkerDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            database.sessionDao().upsert(session(now))
            database.taskDao().upsertAll(listOf(task(now).copy(status = "IN_PROGRESS")))
            database.outboxDao().insert(outbox("PENDING", now))
            val store = WorkerLocalStore(database, testPendingPayloadCipher(), Json)

            val failure = runCatching {
                store.enqueueEvidenceReservation(
                    userId = USER_ID, entryId = ENTRY_ID,
                    evidenceId = "00000000-0000-0000-0000-000000000001",
                    encryptedFilePath = "encrypted/evidence", fileName = "result.webp",
                    routeIndex = 0, capturedAt = "2026-07-26T10:00:00Z", sizeBytes = 42,
                    sha256 = "sha256", variantManifestJson = "[]", reservationPayload = "reservation",
                    completeAfterEvidence = true,
                )
            }.exceptionOrNull()

            assertThat(failure).hasMessageThat().contains("ожидает синхронизации")
            assertThat(database.evidenceDao().observeAll(USER_ID).first()).isEmpty()
            assertThat(database.outboxDao().pending(USER_ID).map { it.operationId })
                .containsExactly("existing-operation-PENDING")
            assertThat(database.taskDao().task(USER_ID, ENTRY_ID)?.status).isEqualTo("IN_PROGRESS")
            assertThat(database.taskDao().task(USER_ID, ENTRY_ID)?.locallyPending).isFalse()
        } finally {
            database.close()
        }
    }

    @Test
    fun `final result photo atomically creates its reservation and completion`() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            WorkerDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            database.sessionDao().upsert(session(now))
            database.categoryDao().upsertAll(listOf(category()))
            database.taskDao().upsertAll(listOf(task(now).copy(status = "IN_PROGRESS")))
            val store = WorkerLocalStore(
                database,
                testPendingPayloadCipher(),
                Json,
            )

            store.enqueueEvidenceReservation(
                userId = USER_ID,
                entryId = ENTRY_ID,
                evidenceId = "00000000-0000-0000-0000-000000000001",
                encryptedFilePath = "encrypted/evidence",
                fileName = "result.webp",
                routeIndex = 0,
                capturedAt = "2026-07-26T10:00:00Z",
                sizeBytes = 42,
                sha256 = "sha256",
                variantManifestJson = "[]",
                reservationPayload = "reservation",
                completeAfterEvidence = true,
            )

            val operations = database.outboxDao().pending(USER_ID)
            assertThat(operations.map { it.kind }).containsExactly(
                WorkerLocalStore.OUTBOX_EVIDENCE_RESERVATION,
                WorkerLocalStore.OUTBOX_ACTION,
            )
            val completion = Json.decodeFromString<PendingWorkerAction>(
                store.decryptOutboxPayload(operations.single { it.kind == WorkerLocalStore.OUTBOX_ACTION }),
            )
            assertThat(completion.action).isEqualTo("COMPLETE")
            assertThat(completion.expectedVersion).isEqualTo(5)
            assertThat(completion.evidenceId).isEqualTo("00000000-0000-0000-0000-000000000001")
            assertThat(database.evidenceDao().evidence(USER_ID, "00000000-0000-0000-0000-000000000001")?.state)
                .isEqualTo(WorkerLocalStore.EVIDENCE_CAPTURED)
            assertThat(database.taskDao().task(USER_ID, ENTRY_ID)?.status).isEqualTo("DONE")
            assertThat(database.taskDao().task(USER_ID, ENTRY_ID)?.locallyPending).isTrue()
        } finally {
            database.close()
        }
    }

    @Test
    fun `expired cache retains only assigned result recovery work`() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            WorkerDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            database.sessionDao().upsert(session(now, leaseExpiresAt = now - 1))
            database.categoryDao().upsertAll(listOf(category(), category("stale-category")))
            val recoveryTask = task(now).copy(status = "IN_PROGRESS")
            val staleTask = task(now).copy(
                localId = "$USER_ID:stale-entry",
                entryId = "stale-entry",
                categoryId = "stale-category",
            )
            database.taskDao().upsertAll(listOf(recoveryTask, staleTask))
            database.assignmentDao().upsertAll(
                listOf(
                    WorkerAssignmentEntity(
                        localId = "$USER_ID:$ENTRY_ID",
                        userId = USER_ID,
                        entryId = ENTRY_ID,
                        assignmentId = "assignment",
                        workerId = USER_ID,
                        workerName = "Worker",
                        workerGroupId = "group",
                        workerGroupName = "Group",
                        status = "ACTIVE",
                        assignedAt = "2026-07-26T10:00:00Z",
                        startedAt = null,
                        pausedAt = null,
                        finishedAt = null,
                    ),
                ),
            )
            val store = WorkerLocalStore(
                database,
                testPendingPayloadCipher(),
                Json,
            )

            store.hideExpiredCacheIfNeeded(USER_ID, elapsedRealtimeMillis = SystemClock.elapsedRealtime())

            assertThat(database.taskDao().task(USER_ID, ENTRY_ID)?.status).isEqualTo("IN_PROGRESS")
            assertThat(database.taskDao().task(USER_ID, "stale-entry")).isNull()
            assertThat(database.assignmentDao().observeForEntry(USER_ID, ENTRY_ID).first()).hasSize(1)
            assertThat(database.categoryDao().categories(USER_ID).map { it.queueId }).containsExactly("category")
            assertThat(database.sessionDao().session(USER_ID)?.cacheHidden).isFalse()
        } finally {
            database.close()
        }
    }

    @Test
    fun pendingOrRetryActionAtomicallyBlocksAnotherActionForTheSameEntry() = runTest {
        for (existingState in listOf("PENDING", "RETRY")) {
            val database = Room.inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                WorkerDatabase::class.java,
            ).allowMainThreadQueries().build()
            try {
                val now = System.currentTimeMillis()
                val elapsed = SystemClock.elapsedRealtime()
                database.sessionDao().upsert(session(now, elapsed))
                database.taskDao().upsertAll(listOf(task(now)))
                database.outboxDao().insert(outbox(existingState, now))
                val store = WorkerLocalStore(
                    database,
                    testPendingPayloadCipher(),
                    Json,
                )

                val failure = runCatching {
                    store.applyOptimisticAction(
                        OptimisticAction(
                            userId = USER_ID,
                            entryId = ENTRY_ID,
                            statusAfterAction = "IN_PROGRESS",
                            payload = PendingWorkerAction(
                                operationId = "new-operation-$existingState",
                                action = "TAKE",
                                expectedVersion = 5,
                                workerGroupId = null,
                                occurredAt = "2026-07-26T10:00:00Z",
                                offlineLeaseId = "lease",
                            ),
                        ),
                    )
                }.exceptionOrNull()

                assertThat(failure).isInstanceOf(IllegalStateException::class.java)
                assertThat(failure).hasMessageThat()
                    .contains("уже ожидает синхронизации")
                assertThat(database.outboxDao().pending(USER_ID)).hasSize(1)
                val unchanged = database.taskDao().task(USER_ID, ENTRY_ID)
                assertThat(unchanged?.version).isEqualTo(5)
                assertThat(unchanged?.status).isEqualTo("WAITING")
                assertThat(unchanged?.locallyPending).isFalse()
            } finally {
                database.close()
            }
        }
    }

    private fun task(now: Long) = WorkerTaskEntity(
        localId = "$USER_ID:$ENTRY_ID",
        userId = USER_ID,
        entryId = ENTRY_ID,
        taskId = "task",
        version = 5,
        categoryId = "category",
        categoryName = "Перемещение",
        categorySortOrder = 0,
        title = "Переместить бытовку",
        unitNumber = "БТ-42",
        taskText = null,
        scheduledDate = "2026-07-26",
        deadlineAt = null,
        priority = 0,
        queuePosition = 0,
        status = "WAITING",
        availabilityMode = "AVAILABLE",
        plannedDurationMinutes = null,
        activeStartedAt = null,
        activeWorkSeconds = 0,
        readyEvidenceCount = 0,
        resultPhotoMinCount = 0,
        lastServerRevision = 1,
        locallyPending = false,
        updatedAtEpochMillis = now,
    )

    private fun category(queueId: String = "category") = WorkerCategoryEntity(
        localId = "$USER_ID:$queueId",
        userId = USER_ID,
        queueId = queueId,
        name = queueId,
        type = "LOGISTICS",
        queuePurpose = "LOGISTICS_DRIVER",
        groupIdsKey = "group",
        sortOrder = 0,
        audienceModesKey = "AVAILABLE",
        resultPhotoMinCount = 1,
        lastServerRevision = 1,
    )

    private fun testPendingPayloadCipher() =
        PendingPayloadCipher(SecretKeySpec(ByteArray(32) { 0x2a }, "AES"))

    private fun session(
        now: Long,
        elapsed: Long = SystemClock.elapsedRealtime(),
        leaseExpiresAt: Long = now + 86_400_000,
    ) = WorkerSessionEntity(
        userId = USER_ID,
        displayName = "Рабочий",
        login = "worker",
        warehouseId = "warehouse",
        leaseId = "lease",
        leaseExpiresAtEpochMillis = leaseExpiresAt,
        serverEpochMillis = now,
        elapsedRealtimeAtSyncMillis = elapsed,
        revision = 1,
        feedEtag = "\"feed-1\"",
        cacheHidden = false,
        updatedAtEpochMillis = now,
        currentGroupId = "group",
        currentGroupName = "Group",
    )

    private fun outbox(state: String, now: Long) = WorkerOutboxEntity(
        operationId = "existing-operation-$state",
        userId = USER_ID,
        entryId = ENTRY_ID,
        kind = WorkerLocalStore.OUTBOX_ACTION,
        encryptedPayload = "not-used-by-the-guard",
        expectedVersion = 5,
        state = state,
        retryCount = if (state == "RETRY") 1 else 0,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
        lastError = null,
    )

    private companion object {
        const val USER_ID = "worker-current"
        const val ENTRY_ID = "entry-current"
    }
}
