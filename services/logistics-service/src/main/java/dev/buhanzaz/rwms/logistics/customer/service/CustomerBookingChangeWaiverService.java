package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.CustomerBookingChangeQuoteResponse;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.PendingCustomerBookingChangeQuoteResponse;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.WaiveCustomerBookingChangeChargeRequest;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingChangeCharge;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerChangeApplicationState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerChangeSettlement;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerBookingChangeChargeRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEventType;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.order.service.OrderAuditService;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Staff-only, reasoned fee waiver; does not cancel an order or change its delivery date. */
@Service
@RequiredArgsConstructor
public class CustomerBookingChangeWaiverService {
  private static final Set<String> ROLES =
      Set.of("SYSTEM_ADMIN", "WMS_ADMIN", "WAREHOUSE_MANAGER", "RENTAL_MANAGER");
  private final CustomerBookingChangeChargeRepository charges;
  private final CustomerRentalSessionRepository sessions;
  private final RentalOrderRepository orders;
  private final OrderAuthorizer access;
  private final CustomerBookingChangeChargeStore store;
  private final OrderAuditService audit;
  private final LogisticsTransactionLock locks;
  private final Clock clock;

  /** Pending fees only, scoped to warehouse EDIT, without exposing another manager's order. */
  @Transactional(readOnly = true)
  public List<PendingCustomerBookingChangeQuoteResponse> pending(OrderActor actor) {
    requireStaff(actor);
    if (actor.editableWarehouses().isEmpty()) return List.of();
    return charges
        .findPendingWaivers(
            actor.editableWarehouses(),
            clock.instant().atOffset(ZoneOffset.UTC),
            PageRequest.of(0, 50))
        .stream()
        .map(
            charge ->
                new PendingCustomerBookingChangeQuoteResponse(
                    charge.getOrderId(), charge.getWarehouseId(), store.response(charge)))
        .toList();
  }

  /** Returns only current, unapplied, unexpired quotes; completed facts remain in order history. */
  @Transactional(readOnly = true)
  public List<CustomerBookingChangeQuoteResponse> list(OrderActor actor, UUID orderId) {
    requireAccess(actor, orderId);
    var session = sessions.findFirstByOrderIdOrderByCreatedAtAscIdAsc(orderId);
    if (session.isEmpty()) return List.of();
    return charges.findAllByOrderIdOrderByCreatedAtDesc(orderId).stream()
        .filter(charge -> current(charge, session.get()))
        .map(store::response)
        .toList();
  }

  /**
   * Same-key retries are exact, and the session-before-quote lock order matches customer commands.
   */
  @Transactional
  public CustomerBookingChangeQuoteResponse waive(
      OrderActor actor,
      UUID orderId,
      UUID quoteId,
      UUID key,
      WaiveCustomerBookingChangeChargeRequest request) {
    requireAccess(actor, orderId);
    locks.acquire("customer-change-waiver:" + actor.subjectId() + ":" + key);
    var replay = charges.findByWaivedByAndWaiverKey(actor.subjectId(), key);
    if (replay.isPresent()) {
      var charge = replay.get();
      if (!charge.getId().equals(quoteId)
          || !charge.getOrderId().equals(orderId)
          || !Objects.equals(charge.getWaiverExpectedVersion(), request.expectedVersion())
          || !Objects.equals(charge.getWaiverReason(), request.reason().trim())) {
        throw CustomerBookingChangeChargeStore.conflict(
            "IDEMPOTENCY_KEY_REUSED", "Ключ использован для другой причины или неустойки");
      }
      return store.response(charge);
    }
    var snapshot =
        charges.findById(quoteId).orElseThrow(CustomerBookingChangeChargeStore::notFound);
    if (!snapshot.getOrderId().equals(orderId)) throw CustomerBookingChangeChargeStore.notFound();
    var session =
        sessions
            .findByBookingIdForUpdate(snapshot.getBookingId())
            .orElseThrow(CustomerBookingChangeChargeStore::stale);
    var charge =
        charges.findForUpdate(quoteId).orElseThrow(CustomerBookingChangeChargeStore::notFound);
    if (charge.getVersion() != request.expectedVersion() || !current(charge, session))
      throw CustomerBookingChangeChargeStore.stale();
    if (charge.getSettlement() != CustomerChangeSettlement.PAYMENT_REQUIRED
        && charge.getSettlement() != CustomerChangeSettlement.POLICY_UNCONFIGURED)
      throw CustomerBookingChangeChargeStore.stale();
    var previous = charge.getSettlement();
    charge.waive(actor.subjectId(), key, request.expectedVersion(), request.reason());
    charges.saveAndFlush(charge);
    audit.append(
        orderId,
        OrderAuditEventType.ORDER_CHANGED,
        actor,
        "CUSTOMER_BOOKING_CHANGE_CHARGE",
        charge.getId().toString(),
        Map.of("settlement", previous.name()),
        Map.of("settlement", "WAIVED", "reason", charge.getWaiverReason(), "amountRubles", "0"));
    return store.response(charge);
  }

  private boolean current(CustomerBookingChangeCharge charge, CustomerRentalSession session) {
    return session.getState() == CustomerSessionState.BOOKED
        && charge.getBookingVersion() == session.getVersion()
        && charge.getOldSlotId().equals(session.getDeliverySlotId())
        && charge.getApplicationState() == CustomerChangeApplicationState.OFFERED
        && charge.getExpiresAt().isAfter(clock.instant().atOffset(ZoneOffset.UTC));
  }

  private void requireAccess(OrderActor actor, UUID orderId) {
    requireStaff(actor);
    var order = orders.findById(orderId).orElseThrow(CustomerBookingChangeChargeStore::notFound);
    access.requireWarehouseEdit(actor, order.getWarehouseId());
  }

  private void requireStaff(OrderActor actor) {
    if (!ROLES.contains(actor.role()) || !actor.rentalAccess() || !actor.writeScope()) {
      throw new AccessDeniedException("Staff rental administration is required");
    }
  }
}
