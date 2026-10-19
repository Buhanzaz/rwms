package dev.buhanzaz.rwms.driver.core.sync

/**
 * Coordinates driver sync/realtime invalidation as a trigger for an authenticated server refresh.
 */
data class DriverRealtimeInvalidation(
    val eventId: String,
    val revision: Long,
    val type: String,
    val entryId: String?,
)

/** Receives each newly persisted realtime invalidation after its de-duplication boundary. */
fun interface DriverRealtimeInvalidationAlert {
    fun onInserted(invalidation: DriverRealtimeInvalidation)
}

internal fun dispatchInsertedRealtimeInvalidation(
    insertedRowId: Long,
    invalidation: DriverRealtimeInvalidation,
    scheduleSync: () -> Unit,
    alert: DriverRealtimeInvalidationAlert,
) {
    if (insertedRowId == -1L) return
    scheduleSync()
    alert.onInserted(invalidation)
}
