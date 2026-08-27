package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional persistence operations for delivery offers and permanent workload. */
@Service
@RequiredArgsConstructor
public class CustomerDeliverySlotStore {
  private final CustomerDeliverySlotRepository slots;

  /** Replaces stale unheld offers for one inquiry without touching a live hold or confirmation. */
  @Transactional
  public List<CustomerDeliverySlot> replaceOffers(
      UUID subjectId, UUID inquiryId, List<CustomerDeliverySlot> offers) {
    List<CustomerDeliverySlot> current =
        slots.findAllByInquiryIdAndStateInOrderByCreatedAtDesc(
            inquiryId, Set.of(CustomerDeliverySlotState.OFFERED));
    current.stream()
        .filter(slot -> subjectId.equals(slot.getCustomerSubjectId()))
        .forEach(CustomerDeliverySlot::release);
    slots.saveAll(current);
    return slots.saveAllAndFlush(offers);
  }

  /** Returns capacity-consuming workload for one warehouse day. */
  @Transactional(readOnly = true)
  public List<CustomerDeliverySlot> workload(
      UUID warehouseId, LocalDate date, OffsetDateTime now) {
    return slots.findCapacityWorkload(
        warehouseId,
        date,
        CustomerDeliverySlotState.CONFIRMED,
        CustomerDeliverySlotState.CHECKOUT_PENDING,
        CustomerDeliverySlotState.HELD,
        now);
  }

  /**
   * Protects a held slot with the stable checkout command before a remote booking starts, or
   * recognizes the same protected retry after a lost response.
   */
  @Transactional
  public CustomerDeliverySlot prepareCheckout(
      UUID subjectId,
      UUID inquiryId,
      UUID slotId,
      long expectedSlotVersion,
      UUID reservationKey,
      OffsetDateTime now) {
    CustomerDeliverySlot slot = locked(subjectId, inquiryId, slotId);
    if (slot.getState() == CustomerDeliverySlotState.CHECKOUT_PENDING
        && reservationKey.equals(slot.getBookingId())) {
      return slot;
    }
    if (slot.getVersion() != expectedSlotVersion
        || slot.getState() != CustomerDeliverySlotState.HELD
        || !slot.getExpiresAt().isAfter(now)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_DELIVERY_SLOT_EXPIRED",
          "Удержание времени доставки истекло");
    }
    slot.protectCheckout(reservationKey, null);
    return slots.saveAndFlush(slot);
  }

  /** Binds the durable presentation-booking receipt to its protected checkout reservation. */
  @Transactional
  public CustomerDeliverySlot bindCheckoutBooking(
      UUID subjectId,
      UUID inquiryId,
      UUID slotId,
      UUID reservationKey,
      UUID bookingId,
      UUID orderId) {
    CustomerDeliverySlot slot = locked(subjectId, inquiryId, slotId);
    slot.bindCheckoutBooking(reservationKey, bookingId, orderId);
    return slots.saveAndFlush(slot);
  }

  /** Locks one offered slot for the hold command. */
  @Transactional(readOnly = true)
  public CustomerDeliverySlot required(UUID subjectId, UUID inquiryId, UUID slotId) {
    CustomerDeliverySlot slot =
        slots.findById(slotId).orElseThrow(CustomerDeliverySlotStore::notFound);
    if (!subjectId.equals(slot.getCustomerSubjectId()) || !inquiryId.equals(slot.getInquiryId())) {
      throw notFound();
    }
    return slot;
  }

  /** Confirms a held slot as durable order workload. */
  @Transactional
  public CustomerDeliverySlot confirm(
      UUID subjectId, UUID inquiryId, UUID slotId, UUID bookingId, UUID orderId) {
    CustomerDeliverySlot slot = locked(subjectId, inquiryId, slotId);
    slot.confirm(bookingId, orderId);
    return slots.saveAndFlush(slot);
  }

  /** Releases a non-confirmed slot after booking rejection or compensation. */
  @Transactional
  public void release(UUID subjectId, UUID inquiryId, UUID slotId) {
    CustomerDeliverySlot slot = locked(subjectId, inquiryId, slotId);
    if (slot.getState() != CustomerDeliverySlotState.CONFIRMED
        && slot.getState() != CustomerDeliverySlotState.RELEASED) {
      slot.release();
      slots.saveAndFlush(slot);
    }
  }

  /** Returns the confirmed customer slot used by the private planning feed. */
  @Transactional(readOnly = true)
  public CustomerDeliverySlot confirmedForOrder(UUID orderId) {
    return slots.findByOrderIdAndState(orderId, CustomerDeliverySlotState.CONFIRMED).orElse(null);
  }

  /** Loads confirmed exact windows for a planning batch without an order-by-order query loop. */
  @Transactional(readOnly = true)
  public Map<UUID, CustomerDeliverySlot> confirmedForOrders(Collection<UUID> orderIds) {
    if (orderIds == null || orderIds.isEmpty()) return Map.of();
    Map<UUID, CustomerDeliverySlot> result = new LinkedHashMap<>();
    for (CustomerDeliverySlot slot :
        slots.findAllByOrderIdInAndState(orderIds, CustomerDeliverySlotState.CONFIRMED)) {
      CustomerDeliverySlot previous = result.put(slot.getOrderId(), slot);
      if (previous != null) {
        throw new IllegalStateException("An order has more than one confirmed customer slot");
      }
    }
    return Map.copyOf(result);
  }

  private CustomerDeliverySlot locked(UUID subjectId, UUID inquiryId, UUID slotId) {
    CustomerDeliverySlot slot =
        slots.findByIdForUpdate(slotId).orElseThrow(CustomerDeliverySlotStore::notFound);
    if (!subjectId.equals(slot.getCustomerSubjectId()) || !inquiryId.equals(slot.getInquiryId())) {
      throw notFound();
    }
    return slot;
  }

  private static OrderProblemException notFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "CUSTOMER_DELIVERY_SLOT_NOT_FOUND", "Слот доставки не найден");
  }
}
