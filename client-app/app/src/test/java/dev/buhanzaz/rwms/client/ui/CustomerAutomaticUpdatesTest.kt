package dev.buhanzaz.rwms.client.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.BookingChangeApplicationState
import dev.buhanzaz.rwms.client.data.BookingChangeOperation
import dev.buhanzaz.rwms.client.data.BookingChangeSettlement
import dev.buhanzaz.rwms.client.data.CustomerBooking
import dev.buhanzaz.rwms.client.data.CustomerBookingChangeQuote
import java.time.Instant
import org.junit.Test

/** Protects finite retry admission and exact owner observations without simulating customer consent. */
class CustomerAutomaticUpdatesTest {
    private val now = Instant.parse("2026-09-07T10:00:00Z")

    @Test
    fun `five failed reads stop requests with increasing delays until an external restart`() {
        val policy = CustomerAutomaticReadPolicy()
        var elapsed = 0L
        for (delay in listOf(5_000L, 10_000L, 20_000L, 40_000L)) {
            assertThat(policy.canAttempt(elapsed)).isTrue()
            policy.record(CustomerReadOutcome.FAILED, elapsed)
            assertThat(policy.canAttempt(elapsed + delay - 1)).isFalse()
            elapsed += delay
        }
        assertThat(policy.canAttempt(elapsed)).isTrue()
        policy.record(CustomerReadOutcome.FAILED, elapsed)
        assertThat(policy.paused).isTrue()
        assertThat(policy.canAttempt(Long.MAX_VALUE)).isFalse()
        policy.reset()
        assertThat(policy.paused).isFalse()
        assertThat(policy.canAttempt(elapsed)).isTrue()
    }

    @Test
    fun `successful reads reset failure history and skipped commands do not consume attempts`() {
        val policy = CustomerAutomaticReadPolicy()
        repeat(4) { policy.record(CustomerReadOutcome.FAILED, 0) }
        repeat(20) { policy.record(CustomerReadOutcome.SKIPPED, 100_000) }
        assertThat(policy.paused).isFalse()
        policy.record(CustomerReadOutcome.SUCCESS, 100_000)
        assertThat(policy.canAttempt(104_999)).isFalse()
        assertThat(policy.canAttempt(105_000)).isTrue()
        policy.record(CustomerReadOutcome.FAILED, 105_000)
        assertThat(policy.paused).isFalse()
        assertThat(policy.canAttempt(110_000)).isTrue()
    }

    @Test
    fun `exact applied payment does not reopen a dismissed pending dialog`() {
        val pending = quote().copy(applicationState = BookingChangeApplicationState.APPLYING)
        val current = workflow(pending).copy(bookingChangeNeedsRefresh = true, error = "Ответ потерян")
        val applied = pending.copy(
            version = 2,
            applicationState = BookingChangeApplicationState.APPLIED,
            settlement = BookingChangeSettlement.TEST_PAID,
            amountRubles = Long.MAX_VALUE.toString(),
        )
        val observed = current.withObservedBookingChange(applied, bookingsReloaded = false, now = now)
        assertThat(observed.bookingChangeQuote).isEqualTo(applied)
        assertThat(observed.bookingChangeQuote?.amountAsLongOrNull()).isEqualTo(Long.MAX_VALUE)
        assertThat(observed.bookingChangeDialogVisible).isFalse()
        assertThat(observed.bookingChangeNeedsRefresh).isFalse()
        assertThat(observed.error).isNull()
        assertThat(observed.bookingChangeReferences).isEqualTo(current.bookingChangeReferences)
    }

    @Test
    fun `exact waiver read cannot clear an existing slot or policy rejection`() {
        val waived = quote().copy(settlement = BookingChangeSettlement.WAIVED, amountRubles = "0")
        val current = workflow(waived).copy(bookingChangeUnavailableReason = "Обратитесь к менеджеру")
        val observed = current.withObservedBookingChange(waived.copy(version = 2), true, now)
        assertThat(observed.bookingChangeUnavailableReason).isEqualTo("Обратитесь к менеджеру")
        assertThat(observed.bookingChangeQuote?.quoteId).isEqualTo(waived.quoteId)
    }

    @Test
    fun `expired offered and waived terms remain exact and unavailable`() {
        for (settlement in listOf(BookingChangeSettlement.PAYMENT_REQUIRED, BookingChangeSettlement.WAIVED)) {
            val expired = quote().copy(
                settlement = settlement,
                amountRubles = if (settlement == BookingChangeSettlement.WAIVED) "0" else "12500",
                expiresAt = now.toString(),
            )
            val observed = workflow(expired).withObservedBookingChange(expired, true, now)
            assertThat(observed.bookingChangeQuote).isEqualTo(expired)
            assertThat(observed.bookingChangeUnavailableReason).isNotNull()
        }
    }

    @Test
    fun `an offered quote stays fenced until booking facts are fresh and matching`() {
        val offered = quote()
        val current = workflow(offered)
        val unread = current.withObservedBookingChange(offered, false, now)
        assertThat(unread.bookingChangeNeedsRefresh).isTrue()
        val fresh = unread.withObservedBookingChange(offered, true, now)
        assertThat(fresh.bookingChangeNeedsRefresh).isFalse()
        assertThat(fresh.bookingChangeUnavailableReason).isNull()
        val changed = current.copy(bookings = current.bookings.map { it.copy(version = 8) })
            .withObservedBookingChange(offered, true, now)
        assertThat(changed.bookingChangeUnavailableReason).contains("Заказ изменился")
    }

    private fun workflow(quote: CustomerBookingChangeQuote) = CustomerWorkflowState(
        bookingChangeQuote = quote,
        bookingChangeReferences = mapOf(quote.bookingId to quote.quoteId),
        bookings = listOf(CustomerBooking(
            bookingId = quote.bookingId, version = quote.bookingVersion, status = "COMPLETED",
            inquiryId = "inquiry", warehouseId = "warehouse", slotId = quote.oldSlotId,
        )),
    )

    private fun quote() = CustomerBookingChangeQuote(
        quoteId = "quote", version = 1, bookingId = "booking", bookingVersion = 7,
        operation = BookingChangeOperation.CANCEL, oldSlotId = "slot", slotId = null, slotVersion = null,
        amountRubles = "12500", settlement = BookingChangeSettlement.PAYMENT_REQUIRED,
        applicationState = BookingChangeApplicationState.OFFERED, testPaymentAvailable = true, supportPhone = null,
        expiresAt = "2026-09-07T10:15:00Z", noticeDays = 2, deliveryDate = "2026-09-08",
        warehouseTimeZone = "Europe/Moscow", targetDeliveryDate = null, targetWindowStart = null, targetWindowEnd = null,
    )
}
