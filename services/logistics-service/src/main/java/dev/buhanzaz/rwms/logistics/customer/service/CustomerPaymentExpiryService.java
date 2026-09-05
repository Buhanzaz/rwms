package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.capacity.service.CustomerDeliveryCapacityFence;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerNotification;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerProfile;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerNotificationRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerProfileRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.inquiry.service.ClientPresentationTokenService;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Couples customer capacity release and inbox delivery to the order expiry completion transaction. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CustomerPaymentExpiryService {
  private final CustomerRentalSessionRepository sessions;
  private final CustomerDeliverySlotRepository slots;
  private final CustomerDeliveryCapacityFence capacityFence;
  private final ClientPresentationTokenService tokens;
  private final CustomerNotificationRepository notifications;
  private final CustomerProfileRepository profiles;

  /** Takes capacity then session locks before the caller locks and transitions the order. */
  public ExpiryContext prepare(UUID orderId) {
    var scope = sessions.findPaymentExpiryScope(orderId).orElse(null);
    if (scope == null) return null;
    var slot = slots.findById(scope.getDeliverySlotId()).orElseThrow();
    capacityFence.acquireDayAndWarehouseCapacity(slot.getWarehouseId(), slot.getDeliveryDate());
    var session = sessions.findByInquiryIdForUpdate(scope.getInquiryId()).orElseThrow();
    if (!Objects.equals(session.getDeliverySlotId(), slot.getId())
        || !session.getCustomerSubjectId().equals(slot.getCustomerSubjectId())
        || !session.getInquiryId().equals(slot.getInquiryId())
        || !session.getWarehouseId().equals(slot.getWarehouseId())) {
      throw new IllegalStateException("Payment expiry delivery scope changed");
    }
    return new ExpiryContext(
        session, scope.getBookingId(), scope.getPresentationId(), scope.getPresentationRevision());
  }

  /** Runs after all asset effects succeeded; any local failure rolls back order completion too. */
  public void complete(ExpiryContext context, RentalOrder order, OffsetDateTime timestamp) {
    if (order.getPaymentState() != RentalOrderPaymentState.EXPIRED) {
      throw new IllegalStateException("Payment expiry has not released the order");
    }
    UUID subjectId;
    UUID bookingId = null;
    if (context != null) {
      var session = context.session();
      if (!order.getWarehouseId().equals(session.getWarehouseId())
          || ("CUSTOMER".equals(order.getCreatedByRole())
              && !order.getCreatedBySubjectId().equals(session.getCustomerSubjectId()))) {
        throw new IllegalStateException("Payment expiry customer scope does not match order");
      }
      var slot = slots.findByIdForUpdate(session.getDeliverySlotId()).orElseThrow();
      boolean provisionalSlot =
          slot.getState() == CustomerDeliverySlotState.CHECKOUT_PENDING
              && slot.getOrderId() == null
              && Objects.equals(slot.getBookingId(), session.getCheckoutCommandKey());
      if ((slot.getBookingId() != null
              && !slot.getBookingId().equals(context.bookingId())
              && !provisionalSlot)
          || (slot.getOrderId() != null && !slot.getOrderId().equals(order.getId()))) {
        throw new IllegalStateException("Payment expiry slot belongs to another booking");
      }
      if (slot.getState() == CustomerDeliverySlotState.CONFIRMED) {
        slot.releaseConfirmed(context.bookingId(), order.getId());
      } else if (slot.getState() != CustomerDeliverySlotState.RELEASED) {
        slot.release();
      }
      slots.saveAndFlush(slot);
      session.expirePaymentReservation(
          context.bookingId(),
          order.getId(),
          session.getPresentationToken() != null
              ? session.getPresentationToken()
              : tokens.issue(context.presentationId(), context.presentationRevision()),
          timestamp);
      sessions.saveAndFlush(session);
      subjectId = session.getCustomerSubjectId();
      bookingId = context.bookingId();
    } else {
      if ("CUSTOMER".equals(order.getCreatedByRole())) {
        throw new IllegalStateException("Customer payment expiry has no checkout lineage");
      }
      subjectId =
          profiles
              .findByClientId(order.getClient().getId())
              .map(CustomerProfile::getAuthSubjectId)
              .orElse(null);
    }
    if (subjectId != null
        && !notifications.existsByCustomerSubjectIdAndOrderIdAndKind(
            subjectId, order.getId(), "PAYMENT_EXPIRED")) {
      notifications.saveAndFlush(
          CustomerNotification.paymentExpired(
              subjectId, order.getId(), bookingId, order.getOrderNumber(), timestamp));
    }
  }

  /** Locked checkout and immutable booking proof, usable only within the surrounding transaction. */
  public record ExpiryContext(
      CustomerRentalSession session,
      UUID bookingId,
      UUID presentationId,
      long presentationRevision) {}
}
