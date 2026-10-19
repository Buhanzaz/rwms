package dev.buhanzaz.rwms.driver.core.sync

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.database.DriverSessionEntity
import dev.buhanzaz.rwms.driver.core.database.DriverShiftSnapshotEntity
import dev.buhanzaz.rwms.driver.core.database.ServerTimeAnchor
import dev.buhanzaz.rwms.driver.core.network.DriverShiftWarehouseDto
import dev.buhanzaz.rwms.driver.core.network.TodayDriverShiftDto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

/** Verifies that DriverApp derives calendar dates only from server and warehouse facts. */
class DriverWarehouseClockTest {
    @Test
    fun `warehouse date ignores device timezone and crosses warehouse midnight`() {
        val clock = DriverWarehouseClockSnapshot(
            serverTimeAnchor = ServerTimeAnchor(
                serverEpochMillis = Instant.parse("2026-08-31T18:59:59Z").toEpochMilli(),
                elapsedRealtimeAtSyncMillis = 5_000L,
                leaseExpiresAtEpochMillis = Instant.parse("2026-09-02T00:00:00Z").toEpochMilli(),
            ),
            timeZone = ZoneId.of("Asia/Yekaterinburg"),
        )

        assertThat(clock.localDateAt(5_999L)).isEqualTo(LocalDate.of(2026, 8, 31))
        assertThat(clock.localDateAt(6_000L)).isEqualTo(LocalDate.of(2026, 9, 1))
        assertThat(
            Instant.parse("2026-08-31T19:00:00Z")
                .atZone(ZoneId.of("America/Los_Angeles"))
                .toLocalDate(),
        ).isEqualTo(LocalDate.of(2026, 8, 31))
    }

    @Test
    fun `resolver uses the assigned warehouse timezone from today shift`() {
        val session = session(warehouseId = "warehouse-a")
        val today = TodayDriverShiftDto(
            enabled = true,
            serverTime = "2026-08-31T18:59:59Z",
            nextRequiredAction = "SHOW_TASKS",
            warehouse = DriverShiftWarehouseDto(
                id = "warehouse-a",
                name = "Екатеринбург",
                city = "Екатеринбург",
                timeZone = "Asia/Yekaterinburg",
            ),
        )

        val clock = driverWarehouseClockSnapshot(session, shiftSnapshot(today), Json)

        assertThat(clock?.timeZone).isEqualTo(ZoneId.of("Asia/Yekaterinburg"))
        assertThat(clock?.localDateAt(5_000L)).isEqualTo(LocalDate.of(2026, 8, 31))
    }

    @Test
    fun `resolver fails closed for another warehouse or invalid timezone`() {
        val session = session(warehouseId = "warehouse-a")
        val anotherWarehouse = todayShift(warehouseId = "warehouse-b", timeZone = "Asia/Yekaterinburg")
        val invalidZone = todayShift(warehouseId = "warehouse-a", timeZone = "Not/A_Zone")

        assertThat(driverWarehouseClockSnapshot(session, shiftSnapshot(anotherWarehouse), Json)).isNull()
        assertThat(driverWarehouseClockSnapshot(session, shiftSnapshot(invalidZone), Json)).isNull()
    }

    private fun session(warehouseId: String) = DriverSessionEntity(
        userId = "driver-a",
        displayName = "Водитель",
        login = "driver",
        warehouseId = warehouseId,
        leaseId = "lease-a",
        leaseExpiresAtEpochMillis = Instant.parse("2026-09-02T00:00:00Z").toEpochMilli(),
        serverEpochMillis = Instant.parse("2026-08-31T18:59:59Z").toEpochMilli(),
        elapsedRealtimeAtSyncMillis = 5_000L,
        revision = 1L,
        feedEtag = null,
        cacheHidden = false,
        updatedAtEpochMillis = 1L,
    )

    private fun todayShift(warehouseId: String, timeZone: String) = TodayDriverShiftDto(
        enabled = true,
        serverTime = "2026-08-31T18:59:59Z",
        nextRequiredAction = "SHOW_TASKS",
        warehouse = DriverShiftWarehouseDto(
            id = warehouseId,
            name = "Склад",
            city = "Город",
            timeZone = timeZone,
        ),
    )

    private fun shiftSnapshot(today: TodayDriverShiftDto) = DriverShiftSnapshotEntity(
        userId = "driver-a",
        shiftId = null,
        workDate = null,
        enabled = today.enabled,
        nextRequiredAction = today.nextRequiredAction,
        serializedTodayShift = Json.encodeToString(today),
        serverTime = today.serverTime,
        updatedAtEpochMillis = 1L,
    )
}
