package dev.buhanzaz.rwms.driver.feature.taskdetail

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.network.DriverTripDetailsDto
import org.junit.Test

/** Verifies that the driver hand-off always prefers confirmed coordinates to a free-form address. */
class YandexMapsRouteTest {
    @Test
    fun `coordinates take priority over address in the Yandex Maps route`() {
        val route = requireNotNull(yandexMapsRouteUrl(trip(latitude = 59.9343, longitude = 30.3351)))

        assertThat(route).isEqualTo("https://yandex.ru/maps/?rtext=~59.9343%2C30.3351&rtt=auto")
    }

    @Test
    fun `address remains a destination when no complete coordinate pair is known`() {
        val route = requireNotNull(yandexMapsRouteUrl(trip(latitude = null, longitude = null)))

        assertThat(route).isEqualTo(
            "https://yandex.ru/maps/?rtext=~%D0%A1%D0%B0%D0%BD%D0%BA%D1%82-%D0%9F%D0%B5%D1%82%D0%B5%D1%80%D0%B1%D1%83%D1%80%D0%B3%2C+%D0%9D%D0%B5%D0%B2%D1%81%D0%BA%D0%B8%D0%B9+%D0%BF%D1%80%D0%BE%D1%81%D0%BF%D0%B5%D0%BA%D1%82%2C+1&rtt=auto",
        )
    }

    @Test
    fun `missing destination does not expose an unusable button route`() {
        assertThat(yandexMapsRouteUrl(trip(address = " ", latitude = null, longitude = null))).isNull()
    }

    private fun trip(
        address: String? = "Санкт-Петербург, Невский проспект, 1",
        latitude: Double? = null,
        longitude: Double? = null,
    ) = DriverTripDetailsDto(
        taskNumber = "TRIP-1",
        tripNumber = 1,
        operationType = "SHIPMENT",
        customerDeliveryPurpose = "RENTAL_DELIVERY",
        clientName = "Клиент",
        address = address,
        latitude = latitude,
        longitude = longitude,
        primaryContactName = null,
        primaryContactPhone = null,
        additionalContacts = emptyList(),
        comment = null,
        desiredDeliveryWindows = emptyList(),
        scheduledDate = "2026-08-23",
        cabins = emptyList(),
    )
}
