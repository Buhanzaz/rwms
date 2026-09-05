package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerRentalSessionStore;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingState;
import dev.buhanzaz.rwms.logistics.inquiry.repository.PresentationBookingRepository;
import dev.buhanzaz.rwms.logistics.inquiry.service.ClientPresentationService;
import dev.buhanzaz.rwms.logistics.order.api.ConfirmOrderPaymentRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderPaymentResponse;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEventType;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentSource;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderReceiptData;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand;
import dev.buhanzaz.rwms.logistics.order.mapper.RentalOrderResponseMapper;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderPaymentReceiptRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns explicit initial-bill confirmation, not provider charging. Every entry point proves either
 * manager access, customer booking ownership or an exact presentation capability before reading a
 * bill. Confirmation serializes against expiry/cancellation and records payment, audit and the
 * idempotency receipt in one local transaction; it never calls remote services.
 */
@Service
@RequiredArgsConstructor
public class RentalOrderPaymentService {
  private static final Set<String> MANAGER_ROLES =
      Set.of("SYSTEM_ADMIN", "WMS_ADMIN", "WAREHOUSE_MANAGER", "RENTAL_MANAGER");
  private static final OrderActor PUBLIC_AUDIT_ACTOR =
      new OrderActor(
          RentalOrderMutationCommand.AUTOMATIC_RELEASE_ACTOR_ID,
          "LOGISTICS_SERVICE",
          "Public presentation test payment",
          Set.of(),
          Set.of(),
          false,
          false,
          false,
          false);

  private final RentalOrderCommandStore orders;
  private final OrderAuthorizer access;
  private final RentalOrderPaymentReceiptStore receipts;
  private final RentalOrderPaymentReceiptRepository databaseClock;
  private final RentalOrderResponseMapper mapper;
  private final OrderAuditService audit;
  private final RentalOrderEditabilityService editability;
  private final CustomerRentalSessionStore customerSessions;
  private final CustomerAuthorizer customerAccess;
  private final ClientPresentationService presentations;
  private final PresentationBookingRepository bookings;

  @Transactional(readOnly = true)
  public OrderPaymentResponse get(OrderActor actor, UUID orderId) {
    RentalOrder order = orders.requiredOrder(orderId);
    requireRead(actor, order);
    return response(order, canManagerConfirm(actor, order));
  }

  @Transactional
  public OrderPaymentResponse confirmManager(
      OrderActor actor, UUID orderId, UUID key, ConfirmOrderPaymentRequest request) {
    return confirm(
        actor, orderId, null, key, request, RentalOrderPaymentSource.MANAGER_CONFIRMATION);
  }

  @Transactional(readOnly = true)
  public OrderPaymentResponse getCustomer(CustomerIdentity identity, UUID bookingId) {
    RentalOrder order = customerOrder(identity, bookingId);
    return response(order, true);
  }

  @Transactional
  public OrderPaymentResponse confirmCustomer(
      CustomerIdentity identity, UUID bookingId, UUID key, ConfirmOrderPaymentRequest request) {
    var session = customerSessions.requiredBooking(identity.subjectId(), bookingId);
    if (session.getOrderId() == null) throw bookingNotReady();
    return confirm(
        customerAccess.orderActor(identity, session.getWarehouseId()),
        session.getOrderId(),
        bookingId,
        key,
        request,
        RentalOrderPaymentSource.CUSTOMER_TEST);
  }

  @Transactional(readOnly = true)
  public OrderPaymentResponse getPresentation(String token, UUID bookingId) {
    return response(orders.requiredOrder(presentationOrderId(token, bookingId)), true);
  }

  @Transactional
  public OrderPaymentResponse confirmPresentation(
      String token, UUID bookingId, UUID key, ConfirmOrderPaymentRequest request) {
    UUID orderId = presentationOrderId(token, bookingId);
    return confirm(
        PUBLIC_AUDIT_ACTOR,
        orderId,
        bookingId,
        key,
        request,
        RentalOrderPaymentSource.PRESENTATION_TEST);
  }

  private OrderPaymentResponse confirm(
      OrderActor actor,
      UUID orderId,
      UUID bookingId,
      UUID key,
      ConfirmOrderPaymentRequest request,
      RentalOrderPaymentSource source) {
    String operation = "CONFIRM_PAYMENT_" + source.name();
    String checksum =
        OrderCommandChecksum.sha256(
            operation,
            List.of(
                orderId.toString(),
                String.valueOf(bookingId),
                String.valueOf(request.expectedVersion())));
    var replay = orders.replay(actor, operation, key, checksum);
    // Load only after the command lock: a concurrent replay must not reuse a pre-lock JPA snapshot.
    RentalOrder order =
        replay == null ? orders.lockedOrder(orderId) : orders.requiredOrder(orderId);
    if (source == RentalOrderPaymentSource.MANAGER_CONFIRMATION) requireManager(actor, order);
    if (source == RentalOrderPaymentSource.CUSTOMER_TEST) requireRead(actor, order);
    if (replay != null) return response(order, false);
    editability.requireNoPendingReplacement(orderId);
    RentalOrderProblems.requireVersion(order, request.expectedVersion());
    RentalOrderReceiptData receipt =
        receipts
            .find(orderId)
            .orElseThrow(
                () ->
                    RentalOrderProblems.conflict(
                        "ORDER_RECEIPT_UNAVAILABLE", "Чек заказа ещё не сформирован"));
    OffsetDateTime timestamp = now();
    if (!pendingBeforeDeadline(order, timestamp)) {
      throw RentalOrderProblems.conflict(
          "ORDER_PAYMENT_NOT_PENDING",
          "Подтверждение недоступно: проверьте состояние и срок оплаты");
    }
    if (source == RentalOrderPaymentSource.PRESENTATION_TEST) {
      order.confirmPresentationPayment(bookingId, timestamp);
    } else {
      order.confirmPayment(source, actor.subjectId(), timestamp);
    }
    orders.persist(order);
    audit.append(
        orderId,
        OrderAuditEventType.ORDER_CHANGED,
        actor,
        "ORDER_PAYMENT",
        orderId.toString(),
        Map.of("paymentState", "PENDING"),
        source == RentalOrderPaymentSource.PRESENTATION_TEST
            ? Map.of("paymentState", "CONFIRMED", "source", source.name(), "bookingId", bookingId)
            : Map.of("paymentState", "CONFIRMED", "source", source.name()));
    orders.remember(actor, operation, key, checksum, order);
    return mapper.toPaymentResponse(order, receipt, timestamp, false);
  }

  private RentalOrder customerOrder(CustomerIdentity identity, UUID bookingId) {
    var session = customerSessions.requiredBooking(identity.subjectId(), bookingId);
    if (session.getOrderId() == null) throw bookingNotReady();
    RentalOrder order = orders.requiredOrder(session.getOrderId());
    requireRead(customerAccess.orderActor(identity, session.getWarehouseId()), order);
    return order;
  }

  private UUID presentationOrderId(String token, UUID bookingId) {
    var presentation = presentations.resolve(token);
    var booking =
        bookings.findById(bookingId).orElseThrow(RentalOrderPaymentService::bookingNotFound);
    if (!booking.getPresentationId().equals(presentation.getId())
        || booking.getPresentationRevision() != presentation.getRevision()) throw bookingNotFound();
    if (booking.getState() != PresentationBookingState.COMPLETED || booking.getOrderId() == null) {
      throw bookingNotReady();
    }
    if (!booking.getOrderId().equals(presentation.getBookedOrderId())) throw bookingNotFound();
    return booking.getOrderId();
  }

  private void requireRead(OrderActor actor, RentalOrder order) {
    access.requireVisible(actor, order);
    if (order.getWarehouseId() != null) access.requireWarehouseRead(actor, order.getWarehouseId());
  }

  private void requireManager(OrderActor actor, RentalOrder order) {
    requireRead(actor, order);
    if (!MANAGER_ROLES.contains(actor.role()) || !actor.writeScope()) {
      throw new AccessDeniedException("Manager payment confirmation permission is required");
    }
    if (order.getWarehouseId() != null) access.requireWarehouseEdit(actor, order.getWarehouseId());
  }

  private boolean canManagerConfirm(OrderActor actor, RentalOrder order) {
    return MANAGER_ROLES.contains(actor.role())
        && actor.writeScope()
        && order.getWarehouseId() != null
        && access.canEditWarehouse(actor, order.getWarehouseId());
  }

  private OrderPaymentResponse response(RentalOrder order, boolean permitted) {
    RentalOrderReceiptData receipt = receipts.find(order.getId()).orElse(null);
    OffsetDateTime timestamp = now();
    return mapper.toPaymentResponse(
        order,
        receipt,
        timestamp,
        permitted && receipt != null && pendingBeforeDeadline(order, timestamp));
  }

  private static boolean pendingBeforeDeadline(RentalOrder order, OffsetDateTime timestamp) {
    return order.getStatus() == RentalOrderStatus.SAVED
        && order.getPaymentState() == RentalOrderPaymentState.PENDING
        && !timestamp.isBefore(order.getPaymentStartedAt())
        && timestamp.isBefore(order.getPaymentExpiresAt());
  }

  private OffsetDateTime now() {
    return databaseClock.currentDatabaseTimestamp().atOffset(ZoneOffset.UTC);
  }

  private static OrderProblemException bookingNotFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "PRESENTATION_BOOKING_NOT_FOUND", "Бронирование не найдено");
  }

  private static OrderProblemException bookingNotReady() {
    return RentalOrderProblems.conflict(
        "ORDER_PAYMENT_BOOKING_PENDING", "Оформление заказа ещё не завершено");
  }
}
