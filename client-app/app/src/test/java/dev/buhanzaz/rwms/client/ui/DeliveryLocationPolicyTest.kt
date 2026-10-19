package dev.buhanzaz.rwms.client.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Verifies that slot search cannot reuse an unconfirmed or incomplete delivery location. */
class DeliveryLocationPolicyTest {
    @Test
    fun `confirmed address point and cabin enable server slot search`() {
        assertThat(
            DeliveryLocationPolicy.canSearchSlots(
                address = "Санкт-Петербург, Невский проспект, 1",
                latitude = 59.9355,
                longitude = 30.3274,
                confirmed = true,
                selectedCabinCount = 2,
            ),
        ).isTrue()
    }

    @Test
    fun `address edit disables search until point is reconfirmed`() {
        assertThat(
            DeliveryLocationPolicy.canSearchSlots(
                address = "Другой адрес",
                latitude = 59.9355,
                longitude = 30.3274,
                confirmed = false,
                selectedCabinCount = 2,
            ),
        ).isFalse()
    }

    @Test
    fun `empty cart or missing coordinate disables search`() {
        assertThat(
            DeliveryLocationPolicy.canSearchSlots("Адрес", 59.9355, 30.3274, true, 0),
        ).isFalse()
        assertThat(
            DeliveryLocationPolicy.canSearchSlots("Адрес", 59.9355, null, true, 1),
        ).isFalse()
    }
}
