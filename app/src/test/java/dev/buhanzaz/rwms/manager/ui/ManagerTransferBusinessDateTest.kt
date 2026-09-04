package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.WarehouseDto
import java.time.Instant
import org.junit.Test

/** Regression coverage for transfer dates at a source-warehouse calendar boundary. */
class ManagerTransferBusinessDateTest {
    @Test
    fun sourceWarehouseTimezoneOwnsTheTransferBusinessDate() {
        val warehouse = WarehouseDto(
            id = "11111111-1111-4111-8111-111111111111",
            version = 1,
            name = "Новосибирск",
            city = "Новосибирск",
            timeZone = "Asia/Novosibirsk",
            active = true,
        )

        assertThat(
            warehouseBusinessDate(warehouse, Instant.parse("2026-08-31T20:30:00Z")).toString(),
        ).isEqualTo("2026-09-01")
    }
}
