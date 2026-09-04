package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the local transaction boundary that protects capacity before remote checkout. */
class CustomerCheckoutStoreTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-27T08:00:00Z"), ZoneOffset.UTC);

  @Test
  void interruptedSubmissionReusesItsCommandReservationAndSlot() {
    UUID subjectId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    UUID transportKey = UUID.randomUUID();
    UUID originalCommand = UUID.randomUUID();
    String hash = "a".repeat(64);
    CustomerRentalSession session = mock(CustomerRentalSession.class);
    CustomerDeliverySlot slot = mock(CustomerDeliverySlot.class);
    CustomerRentalSessionStore sessions = mock(CustomerRentalSessionStore.class);
    CustomerDeliverySlotStore slots = mock(CustomerDeliverySlotStore.class);
    CustomerCheckoutStore store = new CustomerCheckoutStore(sessions, slots, CLOCK);
    when(session.getDeliverySlotId()).thenReturn(slotId);
    when(
            sessions.prepareCheckout(subjectId, inquiryId, 7, transportKey, hash))
        .thenReturn(
            new CustomerRentalSessionStore.CheckoutPreparation(
                session, false, true, originalCommand));
    when(
            slots.prepareCheckout(
                subjectId,
                inquiryId,
                slotId,
                3,
                originalCommand,
                java.time.OffsetDateTime.parse("2026-08-27T08:00:00Z")))
        .thenReturn(slot);

    CustomerCheckoutStore.Preparation result =
        store.prepare(subjectId, inquiryId, 7, transportKey, hash, slotId, 3);

    assertThat(result.commandKey()).isEqualTo(originalCommand);
    assertThat(result.slot()).isSameAs(slot);
  }

  @Test
  void durableReceiptReplacesTheProvisionalSlotIdentityWithTheSameLocalTransaction() {
    UUID subjectId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    UUID commandKey = UUID.randomUUID();
    UUID bookingId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    String hash = "b".repeat(64);
    CustomerRentalSession session = mock(CustomerRentalSession.class);
    CustomerRentalSessionStore sessions = mock(CustomerRentalSessionStore.class);
    CustomerDeliverySlotStore slots = mock(CustomerDeliverySlotStore.class);
    CustomerCheckoutStore store = new CustomerCheckoutStore(sessions, slots, CLOCK);
    when(sessions.recordPendingBooking(
            subjectId,
            inquiryId,
            commandKey,
            hash,
            bookingId,
            orderId,
            "token"))
        .thenReturn(session);

    assertThat(
            store.recordBooking(
                subjectId,
                inquiryId,
                commandKey,
                hash,
                slotId,
                bookingId,
                orderId,
                "token"))
        .isSameAs(session);
    verify(slots)
        .bindCheckoutBooking(
            subjectId,
            inquiryId,
            slotId,
            commandKey,
            bookingId,
            orderId);
  }
}
