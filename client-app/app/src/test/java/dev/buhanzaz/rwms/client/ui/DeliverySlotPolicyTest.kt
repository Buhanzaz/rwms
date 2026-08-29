package dev.buhanzaz.rwms.client.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.DeliverySlot
import dev.buhanzaz.rwms.client.data.DeliverySlotKind
import dev.buhanzaz.rwms.client.data.CustomerRouteProfile
import dev.buhanzaz.rwms.client.data.HeldDeliverySlot
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

    @Test
    fun `date navigation waits for a newer successful search generation`() {
        assertThat(SlotSearchNavigationPolicy.canNavigate(4, current = 4, busy = false)).isFalse()
        assertThat(SlotSearchNavigationPolicy.canNavigate(4, current = 5, busy = true)).isFalse()
        assertThat(SlotSearchNavigationPolicy.canNavigate(4, current = 5, busy = false)).isTrue()
    }

    @Test
    fun `changed slot attestation preserves offers and selection but clears existing hold`() {
        val offered = slot(id = "offered", date = "2026-09-01", start = "09:00:00")
        val state = CustomerWorkflowState(
            slots = listOf(offered),
            slotSearchCompleted = true,
            selectedSlotId = offered.slotId,
            heldSlot = HeldDeliverySlot(cartVersion = 7, slot = offered),
            privateSiteAccessConfirmed = true,
            failedTripChargeAcknowledged = true,
        )

        assertThat(state.withDeliveryAttestations()).isSameInstanceAs(state)
        val changed = state.withDeliveryAttestations(privateSiteAccessConfirmed = false)
        assertThat(changed.slots).containsExactly(offered)
        assertThat(changed.slotSearchCompleted).isTrue()
        assertThat(changed.selectedSlotId).isEqualTo(offered.slotId)
        assertThat(changed.heldSlot).isNull()
    }

    @Test
    fun `changed site capacity clears route offers attestations and hold`() {
        val offered = slot(id = "offered", date = "2026-09-01", start = "09:00:00")
        val state = CustomerWorkflowState(
            selectedCabinIds = setOf("cabin-1", "cabin-2"),
            siteCabinCapacity = 1,
            slots = listOf(offered),
            slotSearchCompleted = true,
            selectedSlotId = offered.slotId,
            heldSlot = HeldDeliverySlot(cartVersion = 7, slot = offered),
            privateSiteAccessConfirmed = true,
            failedTripChargeAcknowledged = true,
        )

        val changed = state.withSiteCabinCapacity(2)

        assertThat(changed.siteCabinCapacity).isEqualTo(2)
        assertThat(changed.privateSiteAccessConfirmed).isFalse()
        assertThat(changed.failedTripChargeAcknowledged).isFalse()
        assertThat(changed.slots).isEmpty()
        assertThat(changed.slotSearchCompleted).isFalse()
        assertThat(changed.selectedSlotId).isNull()
        assertThat(changed.heldSlot).isNull()
    }

    @Test
    fun `single cabin always normalizes receiving capacity to one`() {
        assertThat(normalizedSiteCabinCapacity(selectedCabinCount = 1, requestedCapacity = 2))
            .isEqualTo(1)
        assertThat(normalizedSiteCabinCapacity(selectedCabinCount = 2, requestedCapacity = 2))
            .isEqualTo(2)
    }

    @Test
    fun `delivery price remains unknown when server offers disagree or omit tariff`() {
        val priced = slot(id = "priced", date = "2026-09-01", start = "09:00:00")
        assertThat(DeliverySlotPolicy.deliveryPriceRubles(listOf(priced))).isEqualTo(12500)
        assertThat(DeliverySlotPolicy.deliveryPriceRubles(listOf(priced, priced.copy(deliveryPriceRubles = null))))
            .isNull()
        assertThat(
            DeliverySlotPolicy.deliveryPriceRubles(listOf(priced, priced.copy(deliveryPriceRubles = 13000))),
        ).isNull()
    }

    @Test
    fun `tariff source distinguishes ordinary isochrone from special price zone`() {
        val special = slot(id = "special", date = "2026-09-01", start = "09:00:00")
        val ordinary = special.copy(
            slotId = "ordinary",
            priceZoneId = null,
            priceIsochroneMinutes = 120,
        )

        assertThat(DeliverySlotPolicy.deliveryTariffSource(listOf(special)))
            .isEqualTo("Особая зона доставки")
        assertThat(DeliverySlotPolicy.deliveryTariffSource(listOf(ordinary)))
            .isEqualTo("Изохрона 2 ч")
        assertThat(DeliverySlotPolicy.deliveryTariffSource(listOf(special, ordinary))).isNull()
    }

    private fun slot(id: String, date: String, start: String): DeliverySlot = DeliverySlot(
        slotId = id,
        version = 0,
        date = date,
        kind = DeliverySlotKind.FIXED_WINDOW,
        start = start,
        end = "18:00:00",
        travelZoneHours = 1,
        capacityRemaining = 1,
        deliveryPriceRubles = 12500,
        priceZoneId = "00000000-0000-0000-0000-000000000020",
        priceIsochroneMinutes = null,
        siteCabinCapacity = 2,
        roadRouteConfirmed = true,
        privateSiteAccessConfirmed = true,
        failedTripChargeAcknowledged = true,
        routeProfile = CustomerRouteProfile(4.0, 2.5, 14.0, 20.0, 8.0, 5),
        expiresAt = "2026-08-27T12:00:00+03:00",
        state = "OFFERED",
    )
}
