package dev.buhanzaz.rwms.logistics.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
    session.completeSelection(commandKey, HASH, "[]");

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
            LocalTime.of(9, 0),
            LocalTime.of(12, 0),
            "Москва",
            BigDecimal.valueOf(55.75),
            BigDecimal.valueOf(37.61),
            1,
            1_800,
            1,
            1,
            now.plusMinutes(10));
    UUID bookingId = UUID.randomUUID();
    slot.hold(now.plusMinutes(20), 1);

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
    slot.hold(now.plusMinutes(20), 1);

    slot.protectCheckout(reservationKey, null);
    slot.bindCheckoutBooking(reservationKey, bookingId, orderId);

    assertThat(slot.getBookingId()).isEqualTo(bookingId);
    assertThat(slot.getOrderId()).isEqualTo(orderId);
    slot.release();
    assertThat(slot.getState()).isEqualTo(CustomerDeliverySlotState.RELEASED);
    assertThat(slot.getBookingId()).isNull();
    assertThat(slot.getOrderId()).isNull();
  }

  private static CustomerDeliverySlot deliverySlot(OffsetDateTime now) {
    return CustomerDeliverySlot.offer(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        LocalDate.now(ZoneOffset.UTC).plusDays(2),
        LocalTime.of(9, 0),
        LocalTime.of(12, 0),
        "Москва",
        BigDecimal.valueOf(55.75),
        BigDecimal.valueOf(37.61),
        1,
        1_800,
        1,
        1,
        now.plusMinutes(10));
  }

  private static CustomerRentalSession sessionWithSlot() {
    CustomerRentalSession session =
        CustomerRentalSession.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    session.selectDeliverySlot(0, UUID.randomUUID());
    return session;
  }
}
