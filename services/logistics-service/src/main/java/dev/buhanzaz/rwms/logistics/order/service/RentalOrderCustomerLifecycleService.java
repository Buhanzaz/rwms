package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.order.domain.DesiredDeliveryWindow;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEventType;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the narrow order-side boundary used by CustomerApp after checkout. It permits only an exact
 * customer-owned SAVED booking whose shipment and furniture work remains editable under the
 * established order guard.
 */
@Service
@RequiredArgsConstructor
public class RentalOrderCustomerLifecycleService {
  private final RentalOrderCommandStore orders;
  private final RentalOrderEditabilityService editability;
  private final OrderAuditService audit;

  /** Locks and validates an order before customer cancellation checkpoints are persisted. */
  @Transactional(propagation = Propagation.MANDATORY)
  public CustomerOrderFence requireCancellation(OrderActor actor, UUID orderId) {
    RentalOrder order = requiredCustomerSavedOrder(actor, orderId);
    return fence(order);
  }

  /**
   * Replaces only the date-only customer delivery preference inside the caller's atomic slot-swap
   * transaction. Cabin, furniture, client and address state are deliberately untouched.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CustomerOrderFence reschedule(OrderActor actor, UUID orderId, LocalDate deliveryDate) {
    return reschedule(actor, orderId, deliveryDate, null);
  }

  /** Replaces a booked delivery date only when the dispatcher still holds the exported order fence. */
  @Transactional(propagation = Propagation.MANDATORY)
  public CustomerOrderFence reschedule(
      OrderActor actor, UUID orderId, LocalDate deliveryDate, Long expectedOrderVersion) {
    RentalOrder order = requiredCustomerSavedOrder(actor, orderId);
    if (expectedOrderVersion != null && order.getVersion() != expectedOrderVersion) {
      throw RentalOrderProblems.conflict(
          "ORDER_VERSION_CONFLICT", "Заказ изменился после расчёта вариантов переноса");
    }
    List<String> previous =
        order.getDesiredDeliveryWindows().stream()
            .map(window -> window.getStartDate().toString())
            .toList();
    boolean changed =
        order.replaceClientDesiredDeliveryWindows(
            List.of(DesiredDeliveryWindow.create(deliveryDate, deliveryDate)));
    if (!changed) order.markCustomerBookingRescheduled();
    orders.persist(order);
    audit.append(
        order.getId(),
        OrderAuditEventType.ORDER_CHANGED,
        actor,
        "CUSTOMER_BOOKING",
        order.getId().toString(),
        Map.of("desiredDeliveryDates", previous),
        Map.of("desiredDeliveryDates", List.of(deliveryDate.toString())));
    return fence(order);
  }

  /**
   * Changes only the customer delivery date after the owning saga has proved and held an exact
   * published pre-start assignment. Ordinary editability remains strict and cannot call this
   * boundary.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CustomerOrderFence reschedulePublishedPreStart(
      OrderActor actor, UUID orderId, LocalDate deliveryDate, long expectedOrderVersion) {
    RentalOrder order = orders.lockedOrder(actor, orderId);
    editability.requirePublishedRescheduleActor(actor, order);
    if (order.getStatus() != RentalOrderStatus.SAVED) {
      throw RentalOrderProblems.conflict(
          "CUSTOMER_BOOKING_NOT_EDITABLE",
          "Бронирование нельзя изменить после начала выполнения отгрузки");
    }
    if (order.getVersion() != expectedOrderVersion) {
      throw RentalOrderProblems.conflict(
          "ORDER_VERSION_CONFLICT", "Заказ изменился после расчёта вариантов переноса");
    }
    List<String> previous =
        order.getDesiredDeliveryWindows().stream()
            .map(window -> window.getStartDate().toString())
            .toList();
    boolean changed =
        order.replaceClientDesiredDeliveryWindows(
            List.of(DesiredDeliveryWindow.create(deliveryDate, deliveryDate)));
    if (!changed) order.markCustomerBookingRescheduled();
    orders.persist(order);
    audit.append(
        order.getId(),
        OrderAuditEventType.ORDER_CHANGED,
        actor,
        "PUBLISHED_PLANNING_RESCHEDULE",
        order.getId().toString(),
        Map.of("desiredDeliveryDates", previous),
        Map.of("desiredDeliveryDates", List.of(deliveryDate.toString())));
    return fence(order);
  }

  private RentalOrder requiredCustomerSavedOrder(OrderActor actor, UUID orderId) {
    if (actor == null || !"CUSTOMER".equals(actor.role())) {
      throw RentalOrderProblems.notFound();
    }
    RentalOrder order = orders.lockedOrder(actor, orderId);
    editability.requireEditable(actor, order);
    if (order.getStatus() != RentalOrderStatus.SAVED) {
      throw RentalOrderProblems.conflict(
          "CUSTOMER_BOOKING_NOT_EDITABLE",
          "Бронирование нельзя изменить после начала отгрузки или выполнения работ");
    }
    return order;
  }

  private static CustomerOrderFence fence(RentalOrder order) {
    return new CustomerOrderFence(
        order.getId(),
        order.getVersion(),
        order.getWarehouseId(),
        order.getDeliveryAddress(),
        order.getLatitude(),
        order.getLongitude());
  }

  /** Order facts frozen under the SAVED-booking editability lock. */
  public record CustomerOrderFence(
      UUID orderId,
      long version,
      UUID warehouseId,
      String deliveryAddress,
      java.math.BigDecimal latitude,
      java.math.BigDecimal longitude) {}
}
