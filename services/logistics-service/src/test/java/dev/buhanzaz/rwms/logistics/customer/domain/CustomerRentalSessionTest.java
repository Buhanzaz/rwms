package dev.buhanzaz.rwms.logistics.customer.domain;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Covers cart-local fences that prevent checkout against stale route-capacity calculations. */
class CustomerRentalSessionTest {
  private static final String HASH = "a".repeat(64);

  @Test
  void cabinSelectionChangeClearsPreviouslyHeldDeliverySlot() {
    CustomerRentalSession session = sessionWithSlot();
    UUID commandKey = UUID.randomUUID();

    session.beginSelection(0, commandKey, HASH);
    session.completeSelection(commandKey, HASH, "[]", "[]");

    assertThat(session.getDeliverySlotId()).isNull();
  }

  @Test
  void equipmentChangeClearsPreviouslyHeldDeliverySlot() {
    CustomerRentalSession session = sessionWithSlot();

    session.replaceEquipment(0, "[]");

    assertThat(session.getDeliverySlotId()).isNull();
  }

  @Test
  void pendingCheckoutContinuesToConsumeCapacityUntilTerminalReconciliation() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    CustomerDeliverySlot slot =
        CustomerDeliverySlot.offer(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            LocalDate.now(ZoneOffset.UTC).plusDays(2),
            CustomerDeliverySlotKind.FIXED_WINDOW,
            LocalTime.of(9, 0),
            LocalTime.of(12, 0),
            "Москва",
            BigDecimal.valueOf(55.75),
            BigDecimal.valueOf(37.61),
            1,
            1_800,
            1,
            1,
            2,
            10_000L,
            60,
            true,
            true,
            4.0,
            2.55,
            12.0,
            18.0,
            10.0,
            3,
            now.plusMinutes(10));
    UUID bookingId = UUID.randomUUID();
    slot.hold(now.plusMinutes(20), 1, false, false);

    slot.protectCheckout(bookingId, null);

    assertThat(slot.getState()).isEqualTo(CustomerDeliverySlotState.CHECKOUT_PENDING);
    assertThat(slot.getBookingId()).isEqualTo(bookingId);
    assertThat(slot.consumesCapacity(now.plusDays(1))).isTrue();
  }

  @Test
  void checkoutReservationRebindsToTheBookingAndReleaseClearsDatabaseConstrainedIds() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    CustomerDeliverySlot slot = deliverySlot(now);
    UUID reservationKey = UUID.randomUUID();
    UUID bookingId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    slot.hold(now.plusMinutes(20), 1, false, false);

    slot.protectCheckout(reservationKey, null);
    slot.bindCheckoutBooking(reservationKey, bookingId, orderId);

    assertThat(slot.getBookingId()).isEqualTo(bookingId);
    assertThat(slot.getOrderId()).isEqualTo(orderId);
    slot.release();
    assertThat(slot.getState()).isEqualTo(CustomerDeliverySlotState.RELEASED);
    assertThat(slot.getBookingId()).isNull();
    assertThat(slot.getOrderId()).isNull();
  }

  @Test
  void checkoutRecoveryUsesLeaseBackoffAndTerminalQuarantine() {
    CustomerRentalSession session =
        CustomerRentalSession.create(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    UUID commandKey = UUID.randomUUID();
    UUID bookingId = UUID.randomUUID();
    session.beginCheckout(0, commandKey, HASH);
    session.recordPendingBooking(
        commandKey,
        HASH,
        bookingId,
        null,
        "presentation-token",
        OffsetDateTime.parse("2026-08-31T09:00:00Z"));

    for (int attempt = 1; attempt <= 8; attempt++) {
      OffsetDateTime due = session.getRecoveryNextAttemptAt();
      UUID leaseToken = UUID.randomUUID();
      assertThat(session.claimCheckoutRecovery(leaseToken, due, due.plusMinutes(5))).isTrue();
      OffsetDateTime failedAt = due.plusSeconds(1);
      session.failCheckoutRecovery(
          leaseToken,
          "DEPENDENCY_PENDING",
          failedAt,
          attempt == 8 ? null : failedAt.plusSeconds(2),
          attempt == 8);
    }

    assertThat(session.getRecoveryAttemptCount()).isEqualTo(8);
    assertThat(session.getRecoveryQuarantinedAt()).isNotNull();
    assertThat(session.getRecoveryNextAttemptAt()).isNull();
    assertThat(
            session.claimCheckoutRecovery(
                UUID.randomUUID(),
                session.getRecoveryQuarantinedAt().plusMinutes(10),
                session.getRecoveryQuarantinedAt().plusMinutes(15)))
        .isFalse();
  }

  @Test
  void normalPaymentWaitingDoesNotConsumeRetriesAndRetainsTheExactOrder() {
    CustomerRentalSession session =
        CustomerRentalSession.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    UUID command = UUID.randomUUID();
    UUID booking = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    OffsetDateTime start = OffsetDateTime.parse("2026-09-05T17:00:00Z");
    session.beginCheckout(0, command, HASH);
    session.recordPendingBooking(command, HASH, booking, null, "token", start);
    for (int attempt = 0; attempt < 12; attempt++) {
      OffsetDateTime due = session.getRecoveryNextAttemptAt();
      UUID lease = UUID.randomUUID();
      assertThat(session.claimCheckoutRecovery(lease, due, due.plusMinutes(1))).isTrue();
      session.awaitPayment(booking, order, lease, due, due.plusSeconds(2));
    }
    assertThat(session.getOrderId()).isEqualTo(order);
    assertThat(session.getState()).isEqualTo(CustomerSessionState.CHECKOUT_PENDING);
    assertThat(session.getRecoveryAttemptCount()).isZero();
    assertThat(session.getRecoveryQuarantinedAt()).isNull();
    assertThat(session.getRecoveryLeaseToken()).isNull();
    OffsetDateTime due = session.getRecoveryNextAttemptAt();
    UUID lease = UUID.randomUUID();
    assertThat(session.claimCheckoutRecovery(lease, due, due.plusMinutes(1))).isTrue();
    assertThatThrownBy(
            () -> session.awaitPayment(booking, UUID.randomUUID(), lease, due, due.plusSeconds(2)))
        .hasMessageContaining("does not match");
    assertThatThrownBy(
            () -> session.awaitPayment(booking, order, UUID.randomUUID(), due, due.plusSeconds(2)))
        .hasMessageContaining("lease");
    session.completeBooking(booking, order, lease, due);
    assertThat(session.getState()).isEqualTo(CustomerSessionState.BOOKED);
  }

  @Test
  void checkoutCompletionRequiresCurrentLeaseAndClearsRecoveryMetadata() {
    CustomerRentalSession session =
        CustomerRentalSession.create(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    UUID commandKey = UUID.randomUUID();
    UUID bookingId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    session.beginCheckout(0, commandKey, HASH);
    session.recordPendingBooking(
        commandKey,
        HASH,
        bookingId,
        orderId,
        "presentation-token",
        OffsetDateTime.parse("2026-08-31T09:00:00Z"));
    OffsetDateTime due = session.getRecoveryNextAttemptAt();
    UUID leaseToken = UUID.randomUUID();
    assertThat(session.claimCheckoutRecovery(leaseToken, due, due.plusMinutes(5))).isTrue();

    assertThatThrownBy(
            () ->
                session.completeBooking(
                    bookingId, orderId, UUID.randomUUID(), due.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lease");

    session.completeBooking(bookingId, orderId, leaseToken, due.plusSeconds(1));

    assertThat(session.getState()).isEqualTo(CustomerSessionState.BOOKED);
    assertThat(session.getRecoveryAttemptCount()).isZero();
    assertThat(session.getRecoveryNextAttemptAt()).isNull();
    assertThat(session.getRecoveryLeaseToken()).isNull();
    assertThat(session.getRecoveryLastErrorCode()).isNull();
  }

  @Test
  void expiryAttachesLostCheckoutReceiptAndCannotCancelAnotherBookingOrResumeRecovery() {
    var session = sessionWithSlot();
    UUID command = UUID.randomUUID();
    UUID booking = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    session.beginCheckout(0, command, HASH);
    session.expirePaymentReservation(booking, order, "real-signed-token", now);
    session.expirePaymentReservation(booking, order, "unused-token", now.plusSeconds(1));
    assertThat(session.getState()).isEqualTo(CustomerSessionState.CANCELLED);
    assertThat(session.getBookingId()).isEqualTo(booking);
    assertThat(session.getOrderId()).isEqualTo(order);
    assertThat(session.getPresentationToken()).isEqualTo("real-signed-token");
    assertThat(session.getPendingCommandKey()).isNull();
    assertThat(session.getRecoveryNextAttemptAt()).isNull();
    assertThat(session.claimCheckoutRecovery(UUID.randomUUID(), now, now.plusMinutes(1))).isFalse();
    assertThatThrownBy(
            () -> session.expirePaymentReservation(UUID.randomUUID(), order, "token", now))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> sessionWithSlot().expirePaymentReservation(booking, order, "token", now))
        .isInstanceOf(IllegalStateException.class);
  }

  private static CustomerDeliverySlot deliverySlot(OffsetDateTime now) {
    return CustomerDeliverySlot.offer(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        LocalDate.now(ZoneOffset.UTC).plusDays(2),
        CustomerDeliverySlotKind.FIXED_WINDOW,
        LocalTime.of(9, 0),
        LocalTime.of(12, 0),
        "Москва",
        BigDecimal.valueOf(55.75),
        BigDecimal.valueOf(37.61),
        1,
        1_800,
        1,
        1,
        2,
        10_000L,
        60,
        true,
        true,
        4.0,
        2.55,
        12.0,
        18.0,
        10.0,
        3,
        now.plusMinutes(10));
  }

  private static CustomerRentalSession sessionWithSlot() {
    CustomerRentalSession session =
        CustomerRentalSession.create(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    session.selectDeliverySlot(0, UUID.randomUUID());
    return session;
  }
}
