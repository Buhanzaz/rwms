package dev.buhanzaz.rwms.logistics.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerBookingLifecycleDomainTest {
  @Test
  void completedBookingCanBeFencedAndCancelledWithoutLosingItsIdentity() {
    BookingFixture fixture = bookedSession();

    fixture.session().beginCancellation(0, fixture.now());
    fixture.session().completeCancellation(fixture.now().plusSeconds(1));

    assertThat(fixture.session().getState()).isEqualTo(CustomerSessionState.CANCELLED);
    assertThat(fixture.session().getBookingId()).isEqualTo(fixture.bookingId());
    assertThat(fixture.session().getOrderId()).isEqualTo(fixture.orderId());
    assertThat(fixture.session().getDeliverySlotId()).isEqualTo(fixture.slotId());
  }

  @Test
  void bookedSessionRescheduleChangesOnlyDeliverySlotIdentity() {
    BookingFixture fixture = bookedSession();
    UUID replacementSlot = UUID.randomUUID();

    fixture.session().reschedule(0, replacementSlot, fixture.now());

    assertThat(fixture.session().getState()).isEqualTo(CustomerSessionState.BOOKED);
    assertThat(fixture.session().getDeliverySlotId()).isEqualTo(replacementSlot);
    assertThat(fixture.session().getBookingId()).isEqualTo(fixture.bookingId());
    assertThat(fixture.session().getOrderId()).isEqualTo(fixture.orderId());
  }

  @Test
  void cancellationCannotStartFromAnActiveCart() {
    CustomerRentalSession session =
        CustomerRentalSession.create(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    assertThatThrownBy(() -> session.beginCancellation(0, OffsetDateTime.now(ZoneOffset.UTC)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void releasedConfirmedSlotRetainsBookingAuditAndStopsConsumingCapacity() {
    UUID bookingId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    CustomerDeliverySlot slot = offeredSlot();
    slot.hold(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1), 2, true, true);
    slot.protectCheckout(bookingId, orderId);
    slot.confirm(bookingId, orderId);

    slot.releaseConfirmed(bookingId, orderId);

    assertThat(slot.getState()).isEqualTo(CustomerDeliverySlotState.RELEASED);
    assertThat(slot.getBookingId()).isEqualTo(bookingId);
    assertThat(slot.getOrderId()).isEqualTo(orderId);
    assertThat(slot.consumesCapacity(OffsetDateTime.now(ZoneOffset.UTC))).isFalse();
  }

  @Test
  void freshOfferCanBeConfirmedAsAtomicRescheduleTarget() {
    UUID bookingId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    CustomerDeliverySlot slot = offeredSlot();

    slot.confirmReschedule(bookingId, orderId, 1);

    assertThat(slot.getState()).isEqualTo(CustomerDeliverySlotState.CONFIRMED);
    assertThat(slot.getBookingId()).isEqualTo(bookingId);
    assertThat(slot.getOrderId()).isEqualTo(orderId);
    assertThat(slot.getCapacityRemaining()).isOne();
  }

  @Test
  void mutationLeaseSupportsRetryThenTerminalCompletion() {
    OffsetDateTime now = OffsetDateTime.parse("2026-08-31T09:00:00Z");
    CustomerBookingMutation mutation =
        CustomerBookingMutation.cancel(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64),
            4,
            8,
            UUID.randomUUID(),
            now);
    UUID firstLease = UUID.randomUUID();
    assertThat(mutation.claim(firstLease, now, now.plusMinutes(5))).isTrue();
    mutation.fail(firstLease, "TEMPORARY", now.plusSeconds(1), now.plusSeconds(3), false);
    UUID secondLease = UUID.randomUUID();
    assertThat(mutation.claim(secondLease, now.plusSeconds(3), now.plusMinutes(6))).isTrue();

    mutation.complete(secondLease, now.plusSeconds(4));

    assertThat(mutation.getState()).isEqualTo(CustomerBookingMutationState.COMPLETED);
    assertThat(mutation.getCompletedAt()).isEqualTo(now.plusSeconds(4));
    assertThat(mutation.getNextAttemptAt()).isNull();
  }

  private static BookingFixture bookedSession() {
    OffsetDateTime now = OffsetDateTime.parse("2026-08-31T08:00:00Z");
    UUID subjectId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    UUID bookingId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID commandKey = UUID.randomUUID();
    UUID leaseToken = UUID.randomUUID();
    CustomerRentalSession session =
        CustomerRentalSession.create(
            inquiryId, subjectId, warehouseId);
    session.selectDeliverySlot(0, slotId);
    session.beginCheckout(0, commandKey, "b".repeat(64));
    session.recordPendingBooking(commandKey, "b".repeat(64), bookingId, orderId, "token", now);
    assertThat(session.claimCheckoutRecovery(leaseToken, now, now.plusMinutes(5))).isTrue();
    session.completeBooking(bookingId, orderId, leaseToken, now);
    return new BookingFixture(session, slotId, bookingId, orderId, now);
  }

  private static CustomerDeliverySlot offeredSlot() {
    CustomerDeliverySlot slot =
        CustomerDeliverySlot.offer(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            LocalDate.of(2026, 9, 2),
            CustomerDeliverySlotKind.FIXED_WINDOW,
            LocalTime.of(9, 0),
            LocalTime.of(12, 0),
            "Великий Новгород, тестовый адрес",
            new BigDecimal("58.521475"),
            new BigDecimal("31.275475"),
            1,
            14_400,
            4,
            2,
            1,
            25_000L,
            240,
            true,
            true,
            4.2,
            2.5,
            20.0,
            30.0,
            10.0,
            4,
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(2));
    set(slot, "id", UUID.randomUUID());
    return slot;
  }

  private static void set(Object target, String fieldName, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record BookingFixture(
      CustomerRentalSession session,
      UUID slotId,
      UUID bookingId,
      UUID orderId,
      OffsetDateTime now) {}
}
