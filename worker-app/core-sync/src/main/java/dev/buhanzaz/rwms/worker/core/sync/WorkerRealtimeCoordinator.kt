package dev.buhanzaz.rwms.worker.core.sync

import dev.buhanzaz.rwms.worker.core.database.WorkerDatabase
import dev.buhanzaz.rwms.worker.core.database.WorkerInvalidationEntity
import dev.buhanzaz.rwms.worker.core.network.WorkerSseClient
import dev.buhanzaz.rwms.worker.core.network.foregroundPollIntervalMillis
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * SSE and foreground polling are invalidation triggers only. They never make
 * the UI believe a Wi-Fi connection is online; a sync must contact the
 * authenticated gateway before it can update Room.
 */
@Singleton
class WorkerRealtimeCoordinator @Inject constructor(
    private val database: WorkerDatabase,
    private val sse: WorkerSseClient,
    private val scheduler: WorkerSyncScheduler,
    private val alert: WorkerRealtimeInvalidationAlert,
) {
    /**
     * Starts paired SSE invalidation and foreground polling jobs for one user. Both merely request
     * the unique authenticated sync; neither applies a task state directly.
     */
    fun start(scope: CoroutineScope, userId: String): RealtimeHandles = RealtimeHandles(
        events = scope.launch {
            sse.events()
                .retryWhen { _, _ ->
                    // A reconnect is a fresh signal subscription; refresh authoritative REST state.
                    requestAuthoritativeRefreshOnReconnect(userId, scheduler::request)
                    delay(2_000)
                    true
                }
                .collect { event ->
                    val inserted = database.invalidationDao().insert(
                        WorkerInvalidationEntity(
                            eventId = event.eventId,
                            userId = userId,
                            revision = event.revision,
                            type = event.type,
                            entryId = event.entryId,
                            occurredAt = event.occurredAt,
                        ),
                    )
                    dispatchInsertedRealtimeInvalidation(
                        insertedRowId = inserted,
                        invalidation = WorkerRealtimeInvalidation(
                            eventId = event.eventId,
                            revision = event.revision,
                            type = event.type,
                            entryId = event.entryId,
                        ),
                        scheduleSync = { scheduler.request(userId) },
                        alert = alert,
                    )
                }
        },
        polling = scope.launch {
            while (true) {
                delay(foregroundPollIntervalMillis())
                scheduler.request(userId)
            }
        },
    )
}

/** Requests the authoritative REST sync required whenever the SSE stream reconnects. */
internal fun requestAuthoritativeRefreshOnReconnect(
    userId: String,
    scheduleSync: (String) -> Unit,
) {
    scheduleSync(userId)
}

/**
 * Coordinates worker sync/realtime invalidation as a trigger for an authenticated server refresh.
 */
data class RealtimeHandles(
    val events: Job,
    val polling: Job,
) {
    fun cancel() {
        events.cancel()
        polling.cancel()
    }
}
