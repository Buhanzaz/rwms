package dev.buhanzaz.rwms.driver.core.sync

import android.os.SystemClock
import dev.buhanzaz.rwms.driver.core.database.DriverLocalStore
import dev.buhanzaz.rwms.driver.core.database.DriverSessionEntity
import dev.buhanzaz.rwms.driver.core.database.DriverShiftSnapshotEntity
import dev.buhanzaz.rwms.driver.core.database.ServerTimeAnchor
import dev.buhanzaz.rwms.driver.core.network.TodayDriverShiftDto
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Converts elapsed realtime into the current calendar date of one authenticated warehouse.
 *
 * The server-issued anchor avoids the device wall clock, while [timeZone] comes from the cached
 * `shift/today` projection for the same warehouse. The snapshot stops producing a date when its
 * offline lease is no longer a trustworthy time window or elapsed realtime was reset by reboot.
 */
data class DriverWarehouseClockSnapshot(
    val serverTimeAnchor: ServerTimeAnchor,
    val timeZone: ZoneId,
) {
    /** Returns the warehouse-local date at [elapsedRealtimeMillis], or null for an invalid anchor. */
    fun localDateAt(elapsedRealtimeMillis: Long): LocalDate? =
        serverInstantAt(elapsedRealtimeMillis)?.atZone(timeZone)?.toLocalDate()

    /** Applies the bounded server anchor only while its monotonic lease remains trustworthy. */
    internal fun serverInstantAt(elapsedRealtimeMillis: Long): Instant? {
        if (!serverTimeAnchor.isLeaseActive(elapsedRealtimeMillis)) return null
        return runCatching {
            Instant.ofEpochMilli(serverTimeAnchor.estimatedServerNow(elapsedRealtimeMillis))
        }.getOrNull()
    }
}

/**
 * Exposes the assigned warehouse's current calendar date from durable server and timezone facts.
 *
 * A missing, malformed or cross-warehouse shift snapshot emits null instead of falling back to the
 * Android timezone. The flow wakes at warehouse midnight and at offline-lease expiry, and restarts
 * whenever synchronization replaces either source projection.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class DriverWarehouseClock @Inject constructor(
    private val localStore: DriverLocalStore,
    private val json: Json,
) {
    /** Emits immediately, at warehouse midnight, and when the current server-time lease expires. */
    fun observeDate(userId: String): Flow<LocalDate?> = clockSnapshots(userId)
        .flatMapLatest { clock ->
            clock?.dateFlow() ?: flowOf(null)
        }
        .distinctUntilChanged()

    /** Resolves one action-time date without consulting the device wall clock or default timezone. */
    suspend fun currentDate(userId: String): LocalDate? =
        clockSnapshots(userId).first()?.localDateAt(SystemClock.elapsedRealtime())

    private fun clockSnapshots(userId: String): Flow<DriverWarehouseClockSnapshot?> = combine(
        localStore.observeSession(userId),
        localStore.observeShiftSnapshot(userId),
    ) { session, shiftSnapshot ->
        driverWarehouseClockSnapshot(session, shiftSnapshot, json)
    }.distinctUntilChanged()
}

/** Joins same-user, same-warehouse persisted projections without inventing missing clock facts. */
internal fun driverWarehouseClockSnapshot(
    session: DriverSessionEntity?,
    shiftSnapshot: DriverShiftSnapshotEntity?,
    json: Json,
): DriverWarehouseClockSnapshot? {
    session ?: return null
    shiftSnapshot ?: return null
    if (shiftSnapshot.userId != session.userId) return null
    val warehouseId = session.warehouseId ?: return null
    val serverTime = session.serverEpochMillis ?: return null
    val elapsedRealtime = session.elapsedRealtimeAtSyncMillis ?: return null
    val leaseExpiresAt = session.leaseExpiresAtEpochMillis ?: return null
    val today = runCatching {
        json.decodeFromString<TodayDriverShiftDto>(shiftSnapshot.serializedTodayShift)
    }.getOrNull() ?: return null
    val warehouse = today.warehouse
    val shift = today.shift
    if (warehouse != null && warehouse.id != warehouseId) return null
    if (shift != null && shift.warehouseId != warehouseId) return null
    val zoneId = runCatching {
        ZoneId.of(warehouse?.timeZone ?: shift?.timeZone ?: return null)
    }.getOrNull() ?: return null
    return DriverWarehouseClockSnapshot(
        serverTimeAnchor = ServerTimeAnchor(
            serverEpochMillis = serverTime,
            elapsedRealtimeAtSyncMillis = elapsedRealtime,
            leaseExpiresAtEpochMillis = leaseExpiresAt,
        ),
        timeZone = zoneId,
    )
}

/** Sleeps only until the next date or trust-boundary transition of this exact snapshot. */
private fun DriverWarehouseClockSnapshot.dateFlow(): Flow<LocalDate?> = flow {
    while (true) {
        val elapsedRealtime = SystemClock.elapsedRealtime()
        val serverNow = serverInstantAt(elapsedRealtime)
        if (serverNow == null) {
            emit(null)
            return@flow
        }
        val warehouseNow = serverNow.atZone(timeZone)
        emit(warehouseNow.toLocalDate())
        val nextMidnight = warehouseNow.toLocalDate().plusDays(1).atStartOfDay(timeZone).toInstant()
        val untilMidnight = Duration.between(serverNow, nextMidnight).toMillis()
        val untilLeaseExpiry = serverTimeAnchor.leaseExpiresAtEpochMillis - serverNow.toEpochMilli() + 1L
        delay(minOf(untilMidnight, untilLeaseExpiry).coerceAtLeast(1L))
    }
}
