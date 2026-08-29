package dev.buhanzaz.rwms.client.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.CustomerApiException
import dev.buhanzaz.rwms.client.data.CustomerBooking
import org.junit.Test

/** Covers command serialization and booking-status reconciliation for the active workflow. */
class CustomerWorkflowPolicyTest {
    @Test
    fun `mutation gate rejects a duplicate gesture until the active command finishes`() {
        val gate = CustomerMutationGate()

        assertThat(gate.tryEnter()).isTrue()
        assertThat(gate.tryEnter()).isFalse()
        assertThat(gate.isActive()).isTrue()

        gate.leave()
        assertThat(gate.tryEnter()).isTrue()
        gate.leave()
    }

    @Test
    fun `listed booking status wins over stale immediate checkout response`() {
        val submitted = booking(status = "PENDING")
        val completed = submitted.copy(status = "COMPLETED", orderId = "order-a")

        assertThat(CustomerBookingPolicy.reconcile(submitted, listOf(completed))).isEqualTo(completed)
        assertThat(CustomerBookingPolicy.visible(submitted, listOf(completed))).containsExactly(completed)
    }

    @Test
    fun `only pending and completed checkout fence later cart mutations`() {
        assertThat(CustomerBookingPolicy.locksCart(booking("PENDING"))).isTrue()
        assertThat(CustomerBookingPolicy.locksCart(booking("COMPLETED"))).isTrue()
        assertThat(CustomerBookingPolicy.locksCart(booking("REJECTED"))).isFalse()
        assertThat(CustomerBookingPolicy.locksCart(null)).isFalse()
    }

    @Test
    fun `booking locks only its source inquiry and opens a fresh cart when revisiting catalogue`() {
        val pending = booking("PENDING")

        assertThat(CustomerBookingPolicy.locksCart(pending, "inquiry-a")).isTrue()
        assertThat(CustomerBookingPolicy.locksCart(pending, "inquiry-b")).isFalse()
        assertThat(CustomerInquiryRecoveryPolicy.requiresFreshInquiry(pending, "inquiry-a")).isTrue()
        assertThat(CustomerInquiryRecoveryPolicy.requiresFreshInquiry(pending, "inquiry-b")).isFalse()
    }

    @Test
    fun `booked session and archived problem require a fresh inquiry`() {
        assertThat(CustomerInquiryRecoveryPolicy.requiresFreshInquiry("BOOKED")).isTrue()
        assertThat(CustomerInquiryRecoveryPolicy.requiresFreshInquiry("ACTIVE")).isFalse()
        assertThat(
            CustomerInquiryRecoveryPolicy.isArchivedInquiry(
                CustomerApiException(409, "Диалог уже завершён", "INQUIRY_ARCHIVED"),
            ),
        ).isTrue()
        assertThat(
            CustomerInquiryRecoveryPolicy.isArchivedInquiry(
                CustomerApiException(409, "Корзина занята", "CUSTOMER_CART_BUSY"),
            ),
        ).isFalse()
    }

    private fun booking(status: String): CustomerBooking = CustomerBooking(
        bookingId = "booking-a",
        orderId = null,
        status = status,
        inquiryId = "inquiry-a",
        warehouseId = "warehouse-a",
    )
}
