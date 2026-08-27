package dev.buhanzaz.rwms.client.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Locks camera/geocoder policies to the latest confirmed customer delivery point. */
class DeliveryMapPolicyTest {
    @Test
    fun `saint petersburg depot is initial target when no point exists`() {
        assertThat(deliveryMapInitialTarget(null, null, 59.763806, 30.471798))
            .isEqualTo(59.763806 to 30.471798)
    }

    @Test
    fun `customer point replaces depot without changing coordinate order`() {
        assertThat(deliveryMapInitialTarget(59.93, 30.32, 59.763806, 30.471798))
            .isEqualTo(59.93 to 30.32)
    }

    @Test
    fun `yandex labels form one readable address without duplicate fragments`() {
        assertThat(deliveryDisplayAddress("Санкт-Петербург", "Невский проспект, 1", null))
            .isEqualTo("Санкт-Петербург, Невский проспект, 1")
        assertThat(deliveryDisplayAddress("Невский проспект, 1", "Невский проспект, 1", null))
            .isEqualTo("Невский проспект, 1")
    }

    @Test
    fun `typed address remains fallback when yandex object has no labels`() {
        assertThat(deliveryDisplayAddress(null, " ", " Санкт-Петербург, Невский проспект, 1 "))
            .isEqualTo("Санкт-Петербург, Невский проспект, 1")
    }

    @Test
    fun `only latest asynchronous geocode response is accepted`() {
        val gate = LatestDeliveryGeocodeGate()
        val first = gate.begin()
        val second = gate.begin()

        assertThat(gate.isCurrent(first)).isFalse()
        assertThat(gate.isCurrent(second)).isTrue()

        gate.invalidate()
        assertThat(gate.isCurrent(second)).isFalse()
    }
}
