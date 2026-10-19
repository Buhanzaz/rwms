package dev.buhanzaz.rwms.worker.core.sync

/**
 * Coordinates worker sync/realtime invalidation as a trigger for an authenticated server refresh.
 */
data class WorkerRealtimeInvalidation(
    val eventId: String,
    val revision: Long,
    val type: String,
    val entryId: String?,
)

/** Receives each newly persisted realtime invalidation after its de-duplication boundary. */
fun interface WorkerRealtimeInvalidationAlert {
    fun onInserted(invalidation: WorkerRealtimeInvalidation)
}

internal fun dispatchInsertedRealtimeInvalidation(
    insertedRowId: Long,
    invalidation: WorkerRealtimeInvalidation,
    scheduleSync: () -> Unit,
    alert: WorkerRealtimeInvalidationAlert,
) {
    if (insertedRowId == -1L) return
    scheduleSync()
    alert.onInserted(invalidation)
}
