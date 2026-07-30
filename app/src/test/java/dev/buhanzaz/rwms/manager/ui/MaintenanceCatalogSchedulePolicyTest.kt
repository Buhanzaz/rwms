package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

class MaintenanceCatalogSchedulePolicyTest {
    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun `before nine belongs to previous daily slot`() {
        val now = ZonedDateTime.of(2026, 7, 27, 8, 59, 0, 0, zone)

        assertThat(maintenanceCatalogSyncSlot(now))
            .isEqualTo(LocalDate.of(2026, 7, 26))
        assertThat(
            shouldAttemptMaintenanceCatalogSync(
                lastAttemptSlot = LocalDate.of(2026, 7, 26),
                now = now,
            ),
        ).isFalse()
    }

    @Test
    fun `at nine starts today slot and requests missed update`() {
        val now = ZonedDateTime.of(2026, 7, 27, 9, 0, 0, 0, zone)

        assertThat(maintenanceCatalogSyncSlot(now))
            .isEqualTo(LocalDate.of(2026, 7, 27))
        assertThat(
            shouldAttemptMaintenanceCatalogSync(
                lastAttemptSlot = LocalDate.of(2026, 7, 26),
                now = now,
            ),
        ).isTrue()
        assertThat(
            shouldAttemptMaintenanceCatalogSync(
                lastAttemptSlot = LocalDate.of(2026, 7, 27),
                now = now,
            ),
        ).isFalse()
    }

    @Test
    fun `opening later than nine still requests current day update`() {
        val now = ZonedDateTime.of(2026, 7, 27, 14, 30, 0, 0, zone)

        assertThat(
            shouldAttemptMaintenanceCatalogSync(
                lastAttemptSlot = LocalDate.of(2026, 7, 26),
                now = now,
            ),
        ).isTrue()
    }

    @Test
    fun `scheduler waits for today at nine then for next day`() {
        val beforeNine = ZonedDateTime.of(2026, 7, 27, 8, 59, 0, 0, zone)
        val afterNine = ZonedDateTime.of(2026, 7, 27, 9, 1, 0, 0, zone)

        assertThat(millisUntilNextMaintenanceCatalogSync(beforeNine)).isEqualTo(60_000L)
        assertThat(millisUntilNextMaintenanceCatalogSync(afterNine))
            .isEqualTo(23 * 60 * 60 * 1_000L + 59 * 60 * 1_000L)
    }
}
