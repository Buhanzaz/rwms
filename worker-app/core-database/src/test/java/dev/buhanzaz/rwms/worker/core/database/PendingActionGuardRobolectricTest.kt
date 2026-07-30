package dev.buhanzaz.rwms.worker.core.database

import android.os.SystemClock
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PendingActionGuardRobolectricTest {
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
                database.sessionDao().upsert(
                    WorkerSessionEntity(
                        userId = USER_ID,
                        displayName = "Рабочий",
                        login = "worker",
                        warehouseId = "warehouse",
                        leaseId = "lease",
                        leaseExpiresAtEpochMillis = now + 86_400_000,
                        serverEpochMillis = now,
                        elapsedRealtimeAtSyncMillis = elapsed,
                        revision = 1,
                        feedEtag = "\"feed-1\"",
                        cacheHidden = false,
                        updatedAtEpochMillis = now,
                    ),
                )
                database.taskDao().upsertAll(listOf(task(now)))
                database.outboxDao().insert(outbox(existingState, now))
                val store = WorkerLocalStore(
                    database,
                    PendingPayloadCipher(RuntimeEnvironment.getApplication()),
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
