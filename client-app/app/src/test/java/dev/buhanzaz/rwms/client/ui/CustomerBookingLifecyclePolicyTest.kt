package dev.buhanzaz.rwms.client.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.CustomerBooking
import java.util.Locale
import org.junit.Test

/** Locks CustomerApp booking lifecycle labels and editability to server-owned states. */
class CustomerBookingLifecyclePolicyTest {
    @Test
    fun `only a completed identified booking can be changed`() {
        assertThat(CustomerBookingLifecyclePolicy.canChange(booking("COMPLETED"))).isTrue()
        assertThat(CustomerBookingLifecyclePolicy.canChange(booking("CANCELLATION_PENDING"))).isFalse()
        assertThat(CustomerBookingLifecyclePolicy.canChange(booking("CANCELLED"))).isFalse()
        assertThat(CustomerBookingLifecyclePolicy.canChange(booking("COMPLETED").copy(bookingId = null))).isFalse()
        assertThat(CustomerBookingLifecyclePolicy.canChange(booking("COMPLETED").copy(version = 0))).isFalse()
    }

    @Test
    fun `terminal and pending states are Russian and keep the original inquiry immutable`() {
        assertThat(CustomerBookingLifecyclePolicy.statusLabel("COMPLETED")).isEqualTo("Оформлен")
        assertThat(CustomerBookingLifecyclePolicy.statusLabel("CANCELLATION_PENDING"))
            .isEqualTo("Отмена выполняется")
        assertThat(CustomerBookingLifecyclePolicy.statusLabel("CANCELLED")).isEqualTo("Отменён")
        assertThat(CustomerBookingPolicy.locksCart(booking("CANCELLATION_PENDING"))).isTrue()
        assertThat(CustomerBookingPolicy.locksCart(booking("CANCELLED"))).isTrue()
    }

    @Test
    fun `missing cancellation policy is hidden instead of fabricated as zero`() {
        assertThat(CustomerBookingLifecyclePolicy.cancellationFeeLabel(null)).isNull()
        assertThat(
            CustomerBookingLifecyclePolicy.cancellationFeeLabel(
                amount = 12_500,
                locale = Locale.US,
            ),
        ).isEqualTo("12,500 ₽")
    }

    @Test
    fun `recovery code becomes customer copy rather than an internal enum`() {
        val message = CustomerBookingLifecyclePolicy.recoveryMessage(
            "CUSTOMER_BOOKING_RECONCILIATION_REQUIRED",
        )

        assertThat(message).contains("требует проверки")
        assertThat(message).doesNotContain("CUSTOMER_BOOKING")
    }

    @Test
    fun `command response replaces the matching booking until authoritative refresh`() {
        val completed = booking("COMPLETED")
        val pending = completed.copy(version = 5, status = "CANCELLATION_PENDING")

        val visible = CustomerBookingPolicy.replace(pending, listOf(completed))

        assertThat(visible).containsExactly(pending)
        assertThat(CustomerBookingPolicy.visible(pending, visible)).containsExactly(pending)
    }

    private fun booking(status: String): CustomerBooking = CustomerBooking(
        bookingId = "booking-a",
        version = 4,
        status = status,
        inquiryId = "inquiry-a",
        warehouseId = "warehouse-a",
    )
}
