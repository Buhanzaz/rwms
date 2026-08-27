package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomically coordinates only logistics-local checkout checkpoints. Remote presentation booking
 * and furniture effects remain outside these short transactions and resume from the stored command
 * and booking identities.
 */
@Service
@RequiredArgsConstructor
class CustomerCheckoutStore {
  private final CustomerRentalSessionStore sessions;
  private final CustomerDeliverySlotStore slots;
  private final Clock clock;

  /** Claims the cart and protects its route capacity before the remote booking command starts. */
  @Transactional
  Preparation prepare(
      UUID subjectId,
      UUID inquiryId,
      long expectedCartVersion,
      UUID commandKey,
      String commandSha256,
      UUID slotId,
      long expectedSlotVersion) {
    CustomerRentalSessionStore.CheckoutPreparation cart =
        sessions.prepareCheckout(
            subjectId, inquiryId, expectedCartVersion, commandKey, commandSha256);
    CustomerRentalSession session = cart.session();
    if (!slotId.equals(session.getDeliverySlotId())) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_DELIVERY_SLOT_MISMATCH",
          "Корзина содержит другое время доставки");
    }
    if (cart.completedReplay() || (cart.pendingReplay() && session.getBookingId() != null)) {
      return new Preparation(
          session, null, cart.completedReplay(), cart.pendingReplay(), cart.commandKey());
    }
    CustomerDeliverySlot slot =
        slots.prepareCheckout(
            subjectId,
            inquiryId,
            slotId,
            expectedSlotVersion,
            cart.commandKey(),
            now());
    return new Preparation(
        session, slot, cart.completedReplay(), cart.pendingReplay(), cart.commandKey());
  }

  /** Stores the booking receipt and replaces the provisional slot identity in one transaction. */
  @Transactional
  CustomerRentalSession recordBooking(
      UUID subjectId,
      UUID inquiryId,
      UUID commandKey,
      String commandSha256,
      UUID slotId,
      UUID bookingId,
      UUID orderId,
      String presentationToken) {
    CustomerRentalSession session =
        sessions.recordPendingBooking(
            subjectId,
            inquiryId,
            commandKey,
            commandSha256,
            bookingId,
            orderId,
            presentationToken);
    slots.bindCheckoutBooking(
        subjectId, inquiryId, slotId, commandKey, bookingId, orderId);
    return session;
  }

  /** Releases the protected slot and makes a rejected cart correctable atomically. */
  @Transactional
  CustomerRentalSession rejectBooking(
      UUID subjectId, UUID inquiryId, UUID slotId, UUID bookingId) {
    slots.release(subjectId, inquiryId, slotId);
    return sessions.rejectBooking(subjectId, inquiryId, bookingId);
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock)
        .withOffsetSameInstant(ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MICROS);
  }

  /** Local cart and protected-slot checkpoints returned before remote orchestration resumes. */
  record Preparation(
      CustomerRentalSession session,
      CustomerDeliverySlot slot,
      boolean completedReplay,
      boolean pendingReplay,
      UUID commandKey) {}
}
