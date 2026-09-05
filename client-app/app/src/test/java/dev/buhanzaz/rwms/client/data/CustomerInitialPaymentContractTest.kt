package dev.buhanzaz.rwms.client.data

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

/** Initial-bill wire and arithmetic checks; old orders never become paid by missing data. */
class CustomerInitialPaymentContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `bill preserves furniture per unit per month and delivery once`() {
        val payment = initialPaymentFixture()
        val encoded = json.encodeToString(payment)
        assertThat(json.decodeFromString<CustomerOrderPayment>(encoded).validated(payment.orderId)).isEqualTo(payment)
        assertThat(payment.receipt?.totalRubles).isEqualTo("14100")
    }

    @Test
    fun `amount beyond long remains exact and cannot be decoded from a numeric JSON token`() {
        val base = initialPaymentFixture()
        val line = base.receipt!!.lines.first().copy(
            unitPriceRubles = Long.MAX_VALUE.toString(), rentalMonths = 120,
            amountRubles = (Long.MAX_VALUE.toBigInteger() * 120.toBigInteger()).toString(),
        )
        val receipt = base.receipt.copy(deliveryIncluded = false, lines = listOf(line), totalRubles = line.amountRubles)
        assertThat(receipt.validated(base.orderId)).isEqualTo(receipt)
        val encoded = json.encodeToString(base.copy(receipt = receipt))
        assertThat(json.decodeFromString<CustomerOrderPayment>(encoded).receipt?.totalRubles).isEqualTo(line.amountRubles)
        assertThat(runCatching {
            json.decodeFromString<CustomerOrderPayment>(encoded.replace("\"totalRubles\":\"${line.amountRubles}\"",
                "\"totalRubles\":${line.amountRubles}"))
        }.isFailure).isTrue()
    }

    @Test
    fun `wrong order arithmetic source or extended timer never offers payment`() {
        val payment = initialPaymentFixture()
        val invalid = listOf(
            payment.copy(orderId = "foreign"),
            payment.copy(expiresAt = "2026-09-05T12:06:00Z"),
            payment.copy(state = "CONFIRMED", source = null),
            payment.copy(receipt = payment.receipt!!.copy(totalRubles = "0")),
            payment.copy(receipt = payment.receipt.copy(deliveryIncluded = false)),
            payment.copy(receipt = null),
            payment.copy(orderStatus = "CANCELLED"),
        )
        invalid.forEach { assertThat(runCatching { it.validated(payment.orderId) }.isFailure).isTrue() }
    }

    @Test
    fun `historical saved order with null bill is valid but never payable`() {
        val historical = initialPaymentFixture().copy(state = null, receipt = null, startedAt = null,
            expiresAt = null, canConfirm = false)
        assertThat(historical.validated(historical.orderId).state).isNull()
        assertThat(historical.canConfirm).isFalse()
    }

    @Test
    fun `noncanonical fractional negative and overlong integers fail`() {
        listOf("01", "-1", "1.5", "1e3", "", "1".repeat(81)).forEach {
            assertThat(runCatching { exactReceiptInteger(it) }.isFailure).isTrue()
        }
    }

    @Test
    fun `payment and notification endpoints stay on the public customer gateway boundary`() {
        val methods = CustomerApi::class.java.methods.associateBy { it.name }
        assertThat(methods.getValue("payment").getAnnotation(retrofit2.http.GET::class.java)?.value)
            .isEqualTo("api/logistics/customer/v1/bookings/{bookingId}/payment")
        assertThat(methods.getValue("confirmTestPayment").getAnnotation(retrofit2.http.POST::class.java)?.value)
            .isEqualTo("api/logistics/customer/v1/bookings/{bookingId}/payment/confirm-test")
        assertThat(methods.getValue("notifications").getAnnotation(retrofit2.http.GET::class.java)?.value)
            .isEqualTo("api/logistics/customer/v1/notifications")
        assertThat(methods.getValue("readNotification").getAnnotation(retrofit2.http.POST::class.java)?.value)
            .isEqualTo("api/logistics/customer/v1/notifications/{notificationId}/read")
    }
}

/** Frozen bill shared by contract/repository/UI tests, not available in production. */
internal fun initialPaymentFixture(): CustomerOrderPayment {
    val orderId = "a1335c2b-6e0b-4a9c-b6bc-bf08a636e021"
    val cabinId = "c1335c2b-6e0b-4a9c-b6bc-bf08a636e021"
    val lines = listOf(
        CustomerPaymentReceiptLine("CABIN", cabinId, null, "Бытовка № БК-1", "1", 2, "1000", "2000", 7),
        CustomerPaymentReceiptLine("FURNITURE", cabinId, "e1335c2b-6e0b-4a9c-b6bc-bf08a636e021", "Стул · БК-1", "3", 2, "350", "2100", 4),
        CustomerPaymentReceiptLine("DELIVERY", null, null, "Доставка", "1", null, "10000", "10000", null),
    )
    return CustomerOrderPayment(orderId, 3, "SAVED", "PENDING", "2026-09-05T12:00:00Z",
        "2026-09-05T12:05:00Z", null, null, "2026-09-05T12:00:00Z", true,
        CustomerPaymentReceipt(1, orderId, "000042", "2026-09-05T12:00:00Z", "RUB", true, lines, "14100"))
}
