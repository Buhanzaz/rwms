package dev.buhanzaz.rwms.client.data

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

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
    fun `problem code survives transport decoding for lifecycle recovery`() {
        val body = """{"status":409,"code":"INQUIRY_ARCHIVED","detail":"Диалог уже завершён"}"""
            .toResponseBody("application/problem+json".toMediaType())
        val failure = HttpException(Response.error<Unit>(409, body)).toCustomerApiException(json)

        assertThat(failure.status).isEqualTo(409)
        assertThat(failure.code).isEqualTo("INQUIRY_ARCHIVED")
        assertThat(failure.message).isEqualTo("Этот заказ уже завершён. Начните новый заказ.")
    }

    @Test
    fun `technical backend detail is never exposed to the customer`() {
        val body = """{"status":400,"code":"UNKNOWN","detail":"RMS Logistics Service returned HTTP 400: {\"trace\":\"java.lang.IllegalStateException\"}"}"""
            .toResponseBody("application/problem+json".toMediaType())

        val failure = HttpException(Response.error<Unit>(400, body)).toCustomerApiException(json)

        assertThat(failure.message)
            .isEqualTo("Не удалось выполнить запрос. Проверьте данные и повторите действие.")
        assertThat(failure.message).doesNotContain("HTTP")
        assertThat(failure.message).doesNotContain("Exception")
    }

    @Test
    fun `unknown safe Russian domain detail stays actionable`() {
        val body = """{"status":422,"code":"NEW_CUSTOMER_RULE","detail":"Укажите контактный телефон и повторите действие."}"""
            .toResponseBody("application/problem+json".toMediaType())

        val failure = HttpException(Response.error<Unit>(422, body)).toCustomerApiException(json)

        assertThat(failure.message).isEqualTo("Укажите контактный телефон и повторите действие.")
        assertThat(failure.code).isEqualTo("NEW_CUSTOMER_RULE")
    }

    @Test
    fun `known route code has Russian recovery action even with English detail`() {
        val body = """{"status":503,"code":"CUSTOMER_ROUTING_UNAVAILABLE","detail":"Valhalla timeout"}"""
            .toResponseBody("application/problem+json".toMediaType())

        val failure = HttpException(Response.error<Unit>(503, body)).toCustomerApiException(json)

        assertThat(failure.message)
            .isEqualTo("Расчёт маршрута временно недоступен. Повторите попытку позже.")
    }

    @Test
    fun `booking lifecycle conflict codes have Russian customer actions`() {
        val body = """{"status":409,"code":"CUSTOMER_DELIVERY_SLOT_TAKEN","detail":"capacity race"}"""
            .toResponseBody("application/problem+json".toMediaType())

        val failure = HttpException(Response.error<Unit>(409, body)).toCustomerApiException(json)

        assertThat(failure.code).isEqualTo("CUSTOMER_DELIVERY_SLOT_TAKEN")
        assertThat(failure.message)
            .isEqualTo("Выбранное время уже занято. Рассчитайте доступные варианты заново.")
        assertThat(failure.message).doesNotContain("capacity")
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
              "version":3,"date":"2026-09-01","kind":"DURING_DAY","start":"09:00:00","end":"18:00:00",
              "travelZoneHours":2,"capacityRemaining":1,"roadRouteConfirmed":true,
              "deliveryPriceRubles":12500,"priceZoneId":"00000000-0000-0000-0000-000000000020",
              "priceIsochroneMinutes":null,"siteCabinCapacity":2,
              "privateSiteAccessConfirmed":true,"failedTripChargeAcknowledged":true,
              "routeProfile":{"combinationHeightMeters":4.0,"combinationWidthMeters":2.5,
              "combinationLengthMeters":14.0,"combinationWeightTons":20.0,"axleLoadTons":8.0,"axleCount":5},
              "expiresAt":"2026-08-26T18:00:00Z","state":"HELD"}}""",
        )

        assertThat(held.cartVersion).isEqualTo(9)
        assertThat(held.slot.version).isEqualTo(3)
        assertThat(held.slot.kind).isEqualTo(DeliverySlotKind.DURING_DAY)
        assertThat(held.slot.travelZoneHours).isEqualTo(2)
        assertThat(held.slot.deliveryPriceRubles).isEqualTo(12500)
        assertThat(held.slot.priceZoneId).isEqualTo("00000000-0000-0000-0000-000000000020")
        assertThat(held.slot.priceIsochroneMinutes).isNull()
        assertThat(held.slot.siteCabinCapacity).isEqualTo(2)
    }

    @Test
    fun `ordinary delivery price preserves its canonical isochrone source`() {
        val slot = json.decodeFromString<DeliverySlot>(
            """{"slotId":"00000000-0000-0000-0000-000000000010","version":3,
              "date":"2026-09-01","kind":"FIXED_WINDOW","start":"12:00:00","end":"15:00:00",
              "travelZoneHours":2,"capacityRemaining":1,"deliveryPriceRubles":15000,
              "priceZoneId":null,"priceIsochroneMinutes":120,"siteCabinCapacity":1,
              "roadRouteConfirmed":true,"privateSiteAccessConfirmed":false,
              "failedTripChargeAcknowledged":false,
              "routeProfile":{"combinationHeightMeters":4.0,"combinationWidthMeters":2.5,
              "combinationLengthMeters":9.0,"combinationWeightTons":12.0,"axleLoadTons":8.0,
              "axleCount":3},"expiresAt":"2026-08-26T18:00:00Z","state":"OFFERED"}""",
        )

        assertThat(slot.priceZoneId).isNull()
        assertThat(slot.priceIsochroneMinutes).isEqualTo(120)
    }

    @Test
    fun `nullable canonical response fields decode without fabricating values`() {
        val selection = json.decodeFromString<CabinSelectionResponse>(
            """{"version":5,"expiresAt":null,"cabins":[{"unitId":"cabin-a","version":1,
              "accountingNo":"BK-2","type":null}]}""",
        )
        val booking = json.decodeFromString<CustomerBooking>(
            """{"bookingId":null,"orderId":null,"status":"PENDING","errorCode":null,
              "inquiryId":"inquiry-a","slotId":null,"warehouseId":"warehouse-a",
              "deliveryAddress":null,"deliveryDate":null,"windowStart":null,"windowEnd":null,"cabins":[]}""",
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

    @Test
    fun `booking cabin decodes arrival acceptance problems and shipment media owner`() {
        val booking = json.decodeFromString<CustomerBooking>(
            """{"bookingId":"booking-a","version":14,"orderId":"order-a","status":"COMPLETED","errorCode":null,
              "inquiryId":"inquiry-a","slotId":"slot-a","warehouseId":"warehouse-a",
              "deliveryAddress":"Невский, 1","deliveryDate":"2026-09-01","windowStart":"09:00:00",
              "windowEnd":"12:00:00","cancellationFeeRubles":null,
              "cabins":[{"cabinUnitId":"cabin-a","accountingNo":"БК-1",
              "rentalMonths":3,"deliveryState":"ARRIVED","arrivalEligible":true,
              "mediaOwner":{"ownerType":"LOGISTICS_SHIPMENT","documentId":"document-a","lineId":"line-a",
              "warehouseId":"warehouse-a","context":"SHIPMENT"},"acceptance":null,"problems":[]}]}""",
        )

        assertThat(booking.version).isEqualTo(14)
        assertThat(booking.cancellationFeeRubles).isNull()
        assertThat(booking.cabins.single().arrivalEligible).isTrue()
        assertThat(booking.cabins.single().mediaOwner?.lineId).isEqualTo("line-a")
    }

    @Test
    fun `slot search forwards current false attestations and hold requires explicit true attestations`() {
        val search = json.encodeToString(
            DeliverySlotSearchRequest(
                inquiryId = "inquiry-a",
                address = "Невский, 1",
                latitude = 59.9,
                longitude = 30.3,
                siteCabinCapacity = 2,
                privateSiteAccessConfirmed = false,
                failedTripChargeAcknowledged = false,
            ),
        )
        val hold = json.encodeToString(
            HoldDeliverySlotRequest(
                inquiryId = "inquiry-a",
                expectedVersion = 9,
                siteCabinCapacity = 2,
                privateSiteAccessConfirmed = true,
                failedTripChargeAcknowledged = true,
            ),
        )

        assertThat(search).contains("\"privateSiteAccessConfirmed\":false")
        assertThat(search).contains("\"failedTripChargeAcknowledged\":false")
        assertThat(search).contains("\"siteCabinCapacity\":2")
        assertThat(hold).contains("\"privateSiteAccessConfirmed\":true")
        assertThat(hold).contains("\"failedTripChargeAcknowledged\":true")
        assertThat(hold).contains("\"siteCabinCapacity\":2")
    }

    @Test
    fun `booking reschedule requests contain only server booking and slot fences`() {
        val search = json.encodeToString(
            SearchCustomerBookingRescheduleRequest(expectedVersion = 14),
        )
        val confirmation = json.encodeToString(
            RescheduleCustomerBookingRequest(
                expectedVersion = 14,
                slotId = "slot-new",
                slotVersion = 6,
            ),
        )

        assertThat(search).isEqualTo("{\"expectedVersion\":14}")
        assertThat(confirmation)
            .isEqualTo("{\"expectedVersion\":14,\"slotId\":\"slot-new\",\"slotVersion\":6}")
        assertThat(confirmation).doesNotContain("cabin")
        assertThat(confirmation).doesNotContain("equipment")
    }
}
