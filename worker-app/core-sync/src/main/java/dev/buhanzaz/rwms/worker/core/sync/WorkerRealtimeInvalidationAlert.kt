package dev.buhanzaz.rwms.worker.core.sync

data class WorkerRealtimeInvalidation(
    val eventId: String,
    val revision: Long,
    val type: String,
    val entryId: String?,
)

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
