package dev.buhanzaz.rwms.driver.core.sync

import dev.buhanzaz.rwms.driver.core.database.DriverDatabase
import dev.buhanzaz.rwms.driver.core.database.DriverInvalidationEntity
import dev.buhanzaz.rwms.driver.core.network.DriverSseClient
import dev.buhanzaz.rwms.driver.core.network.foregroundPollIntervalMillis
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
class DriverRealtimeCoordinator @Inject constructor(
    private val database: DriverDatabase,
    private val sse: DriverSseClient,
    private val scheduler: DriverSyncScheduler,
    private val alert: DriverRealtimeInvalidationAlert,
) {
    /**
     * Starts paired SSE invalidation and foreground polling jobs for one user. Both merely request
     * the unique authenticated sync; neither applies a task state directly.
     */
    fun start(scope: CoroutineScope, userId: String): RealtimeHandles = RealtimeHandles(
        events = scope.launch {
            val lastEventId = database.invalidationDao().latestEventId(userId)
            sse.events(lastEventId)
                .retryWhen { _, _ ->
                    delay(2_000)
                    true
                }
                .collect { event ->
                    val inserted = database.invalidationDao().insert(
                        DriverInvalidationEntity(
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
                        invalidation = DriverRealtimeInvalidation(
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

/**
 * Coordinates driver sync/realtime invalidation as a trigger for an authenticated server refresh.
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
