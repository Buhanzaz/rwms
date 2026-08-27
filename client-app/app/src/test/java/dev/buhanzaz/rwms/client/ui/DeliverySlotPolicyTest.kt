package dev.buhanzaz.rwms.client.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.DeliverySlot
import org.junit.Test

/** Proves that CustomerApp never creates or selects a slot absent from the server response. */
class DeliverySlotPolicyTest {
    @Test
    fun `known server slot is selected`() {
        assertThat(DeliverySlotPolicy.select(setOf("09-12", "12-15"), "12-15")).isEqualTo("12-15")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown local slot is rejected`() {
        DeliverySlotPolicy.select(setOf("09-12"), "15-18")
    }

    @Test
    fun `server slots are grouped by ordered date and ordered time`() {
        val late = slot(id = "late", date = "2026-09-02", start = "15:00:00")
        val first = slot(id = "first", date = "2026-09-01", start = "12:00:00")
        val early = slot(id = "early", date = "2026-09-01", start = "09:00:00")

        val grouped = DeliverySlotPolicy.byDate(listOf(late, first, early))

        assertThat(grouped.map(DeliveryDateAvailability::date))
            .containsExactly("2026-09-01", "2026-09-02")
            .inOrder()
        assertThat(grouped.first().slots.map(DeliverySlot::slotId))
            .containsExactly("early", "first")
            .inOrder()
    }

    private fun slot(id: String, date: String, start: String): DeliverySlot = DeliverySlot(
        slotId = id,
        version = 0,
        date = date,
        start = start,
        end = "18:00:00",
        travelZoneHours = 1,
        capacityRemaining = 1,
        expiresAt = "2026-08-27T12:00:00+03:00",
        state = "OFFERED",
    )
}
