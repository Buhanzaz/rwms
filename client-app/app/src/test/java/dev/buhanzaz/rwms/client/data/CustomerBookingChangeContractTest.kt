package dev.buhanzaz.rwms.client.data

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** Uses the actual transport Json policy to protect exact whole-ruble amounts and quote fences. */
class CustomerBookingChangeContractTest {
    private val json = CustomerNetworkModule.json()

    @Test
    fun `cancellation sends required explicit nullable target fields despite shared null omission`() {
        val request = CreateBookingChangeQuoteRequest(7, BookingChangeOperation.CANCEL, null, null)
        val fields = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        assertThat(fields.keys).containsExactly("expectedVersion", "operation", "slotId", "slotVersion")
        assertThat(fields["slotId"]).isEqualTo(JsonNull)
        assertThat(fields["slotVersion"]).isEqualTo(JsonNull)
    }

    @Test
    fun `reschedule quote includes only exact booking and selected offer fences`() {
        val request = CreateBookingChangeQuoteRequest(7, BookingChangeOperation.RESCHEDULE, "slot-new", 19)
        val fields = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        assertThat(fields["slotId"]).isEqualTo(JsonPrimitive("slot-new"))
        assertThat(fields["slotVersion"]).isEqualTo(JsonPrimitive(19))
        assertThat(json.decodeFromString<CreateBookingChangeQuoteRequest>(json.encodeToString(request))).isEqualTo(request)
    }

    @Test
    fun `quoted test consent is explicit on existing cancellation command`() {
        val fields = json.parseToJsonElement(
            json.encodeToString(CancelCustomerBookingRequest(7, "quote-a", 3, true)),
        ).jsonObject
        assertThat(fields["changeQuoteId"]).isEqualTo(JsonPrimitive("quote-a"))
        assertThat(fields["changeQuoteVersion"]).isEqualTo(JsonPrimitive(3))
        assertThat(fields["testPaymentRequested"]).isEqualTo(JsonPrimitive(true))
    }

    @Test
    fun `long maximum amount remains exact and is never narrowed to int`() {
        val quote = decode("\"9223372036854775807\"").validated()
        assertThat(quote.amountAsLongOrNull()).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `non whole overflowing negative and missing amounts cannot become actionable quotes`() {
        listOf("\"9223372036854775808\"", "\"-1\"", "\"1.5\"", "\" 1\"", "\"01\"", "null").forEach { amount ->
            assertThat(runCatching { decode(amount).validated() }.isFailure).isTrue()
        }
    }

    @Test
    fun `numeric money token is rejected instead of accepting an incompatible transport`() {
        assertThat(runCatching { decode("12500").validated() }.isFailure).isTrue()
    }

    @Test
    fun `unconfigured owner policy preserves an unavailable amount`() {
        val quote = decode("null", "POLICY_UNCONFIGURED").validated()
        assertThat(quote.amountAsLongOrNull()).isNull()
        assertThat(quote.settlement).isEqualTo(BookingChangeSettlement.POLICY_UNCONFIGURED)
    }

    @Test
    fun `test payment marker without exact applied owner outcome is rejected`() {
        assertThat(runCatching { decode("\"12500\"", "TEST_PAID").validated() }.isFailure).isTrue()
    }

    private fun decode(amount: String, settlement: String = "PAYMENT_REQUIRED") =
        json.decodeFromString<CustomerBookingChangeQuote>(
            """{"quoteId":"quote-a","version":0,"bookingId":"booking-a","bookingVersion":7,
                "operation":"CANCEL","oldSlotId":"slot-old","slotId":null,"slotVersion":null,
                "amountRubles":$amount,"settlement":"$settlement","applicationState":"OFFERED",
                "testPaymentAvailable":true,"supportPhone":null,"expiresAt":"2026-09-01T15:00:00Z",
                "noticeDays":2,"deliveryDate":"2026-09-02","warehouseTimeZone":"Europe/Moscow",
                "targetDeliveryDate":null,"targetWindowStart":null,"targetWindowEnd":null} """,
        )
}
