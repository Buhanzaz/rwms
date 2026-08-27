package dev.buhanzaz.rwms.client.data

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/** Locks CustomerApp decoding to the actual logistics CustomerController response shapes. */
class CustomerBackendContractDecodingTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `warehouse response preserves the selected delivery depot coordinates`() {
        val warehouse = json.decodeFromString<CustomerWarehouse>(
            """{"id":"c89b65f1-2891-4176-bd88-1d231e869a25","name":"СПБ",
              "city":"Санкт-Петербург","address":null,"timezone":"Europe/Moscow",
              "depotLatitude":59.763806,"depotLongitude":30.471798}""",
        )

        assertThat(warehouse.depotLatitude).isEqualTo(59.763806)
        assertThat(warehouse.depotLongitude).isEqualTo(30.471798)
    }

    @Test
    fun `inquiry identity and optimistic version decode for process recovery`() {
        val inquiry = json.decodeFromString<InquirySession>(
            """{"inquiryId":"00000000-0000-0000-0000-000000000001",
              "warehouseId":"00000000-0000-0000-0000-000000000002",
              "selectionVersion":7,"state":"SELECTION_PENDING"}""",
        )

        assertThat(inquiry.selectionVersion).isEqualTo(7)
        assertThat(inquiry.state).isEqualTo("SELECTION_PENDING")
    }

    @Test
    fun `cabin page uses content and preserves photo generation`() {
        val page = json.decodeFromString<CabinPage>(
            """{
              "content":[{"unitId":"00000000-0000-0000-0000-000000000001","version":4,
                "accountingNo":"BK-1","type":"БК","finish":"ПВХ","dimensions":"6x2.4",
                "category":"standard","linoleum":true,"characteristics":["Пластиковое окно"],
                "facts":{"color":"white"},"photos":[{"photoId":"00000000-0000-0000-0000-000000000002",
                "generation":7,"thumbnailUrl":"/api/logistics/customer/v1/small","url":"/api/logistics/customer/v1/large"}]}],
              "page":0,"size":20,"totalElements":1,"totalPages":1
            }""",
        )

        assertThat(page.content).hasSize(1)
        assertThat(page.content.single().photos.single().generation).isEqualTo(7)
    }

    @Test
    fun `held slot and cart versions decode independently`() {
        val held = json.decodeFromString<HeldDeliverySlot>(
            """{"cartVersion":9,"slot":{"slotId":"00000000-0000-0000-0000-000000000010",
              "version":3,"date":"2026-09-01","start":"09:00:00","end":"12:00:00",
              "travelZoneHours":2,"capacityRemaining":1,"expiresAt":"2026-08-26T18:00:00Z","state":"HELD"}}""",
        )

        assertThat(held.cartVersion).isEqualTo(9)
        assertThat(held.slot.version).isEqualTo(3)
        assertThat(held.slot.travelZoneHours).isEqualTo(2)
    }

    @Test
    fun `nullable canonical response fields decode without fabricating values`() {
        val selection = json.decodeFromString<CabinSelectionResponse>(
            """{"version":5,"expiresAt":null,"cabins":[{"unitId":"cabin-a","version":1,
              "accountingNo":"BK-2","type":null}]}""",
        )
        val booking = json.decodeFromString<CustomerBooking>(
            """{"bookingId":null,"orderId":null,"status":"PENDING","inquiryId":"inquiry-a","slotId":null}""",
        )

        assertThat(selection.expiresAt).isNull()
        assertThat(selection.cabins.single().type).isNull()
        assertThat(booking.bookingId).isNull()
        assertThat(booking.slotId).isNull()
    }

    @Test
    fun `filter badge includes dimensions and category`() {
        val filters = CabinFilters(
            cabinType = "БК",
            dimensions = "6x2.4",
            category = "standard",
            characteristics = setOf("Пластиковое окно"),
        )

        assertThat(filters.activeCount).isEqualTo(4)
    }
}
