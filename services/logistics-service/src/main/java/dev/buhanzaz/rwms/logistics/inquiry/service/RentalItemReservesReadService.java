package dev.buhanzaz.rwms.logistics.inquiry.service;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalItemReservesResponse;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalItemReservesResponse.Entry;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalItemReservesResponse.Kind;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalItemReservesResponse.Source;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.inquiry.repository.ClientPresentationRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalInquiryRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.RentalItemReserveSnapshot;
import dev.buhanzaz.rwms.logistics.integration.RentalItemReserveSnapshot.OrderReservation;
import dev.buhanzaz.rwms.logistics.integration.RentalItemReserveSnapshot.SelectionHold;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes one private asset snapshot with existing logistics visibility proofs. Reads never
 * acquire, renew, convert or release reservations and never hold a local transaction across HTTP.
 */
@Service
@RequiredArgsConstructor
public class RentalItemReservesReadService {
  private static final Set<String> ACTOR_ROLES =
      Set.of(
          "SYSTEM_ADMIN", "WMS_ADMIN", "WAREHOUSE_MANAGER", "RENTAL_MANAGER", "CUSTOMER", "VIEWER");

  private final LogisticsDependencyGateway dependencies;
  private final OrderAuthorizer access;
  private final RentalInquiryRepository inquiries;
  private final ClientPresentationRepository presentations;
  private final RentalOrderRepository orders;

  @Transactional(propagation = Propagation.NEVER)
  public RentalItemReservesResponse read(OrderActor actor, UUID rentalItemId, UUID warehouseId) {
    access.requireWarehouseRead(actor, warehouseId);
    RentalItemReserveSnapshot snapshot;
    try {
      snapshot = dependencies.readRentalItemReserves(rentalItemId, warehouseId);
    } catch (LogisticsDependencyException exception) {
      if ("ASSET_NOT_FOUND".equals(exception.dependencyCode())) throw notFound();
      throw new OrderProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "RENTAL_ITEM_RESERVES_UNAVAILABLE",
          "Не удалось загрузить резервы бытовки");
    }
    validate(snapshot, rentalItemId, warehouseId);
    var entries = new ArrayList<Entry>();
    for (SelectionHold hold : snapshot.holds()) entries.add(selection(actor, warehouseId, hold));
    if (snapshot.orderReservation() != null) entries.add(order(actor, snapshot.orderReservation()));
    return new RentalItemReservesResponse(
        rentalItemId, warehouseId, snapshot.serverTime(), java.util.List.copyOf(entries));
  }

  private Entry selection(OrderActor actor, UUID warehouseId, SelectionHold hold) {
    RentalInquiry inquiry = inquiries.findById(hold.holdScopeId()).orElse(null);
    if (inquiry == null) {
      inquiry =
          presentations
              .findById(hold.holdScopeId())
              .flatMap(value -> inquiries.findById(value.getInquiryId()))
              .orElse(null);
    }
    String clientName = null;
    String managerName = null;
    RentalOrder linked = null;
    if (inquiry != null && warehouseId.equals(inquiry.getWarehouseId())) {
      UUID linkedId =
          inquiry.getRentalOrderId() != null
              ? inquiry.getRentalOrderId()
              : inquiry.getBookedOrderId();
      linked = visibleOrder(actor, linkedId);
      boolean ownInquiry = actor.subjectId().equals(inquiry.getManagerId());
      if (ownInquiry) {
        clientName = inquiry.getClient().getDisplayName();
        managerName =
            "CUSTOMER".equals(inquiry.getManagerRole()) ? null : inquiry.getManagerDisplayName();
      } else if (linked != null) {
        clientName = linked.getClient().getDisplayName();
        managerName = managerName(linked);
      }
    } else if (inquiry == null && actor.subjectId().equals(hold.actorSubjectId())) {
      managerName = "CUSTOMER".equals(hold.actorRole()) ? null : actor.displayName();
    }
    return new Entry(
        hold.holdId(),
        Kind.SELECTION_HOLD,
        source(hold.actorRole()),
        hold.createdAt(),
        hold.expiresAt(),
        clientName,
        managerName,
        linked == null ? null : linked.getId(),
        linked == null ? null : linked.getOrderNumber(),
        linked == null ? null : linked.getStatus(),
        null,
        linked != null);
  }

  private Entry order(OrderActor actor, OrderReservation reservation) {
    RentalOrder order = orders.findWithClientById(reservation.orderId()).orElse(null);
    boolean visible = order != null && isVisible(actor, order);
    OffsetDateTime expiresAt = reservation.draftExpiresAt();
    if (order != null) {
      expiresAt =
          order.getPaymentState() == RentalOrderPaymentState.PENDING
                  || order.getPaymentState() == RentalOrderPaymentState.EXPIRING
              ? order.getPaymentExpiresAt()
              : order.getStatus() == RentalOrderStatus.DRAFT ? reservation.draftExpiresAt() : null;
    }
    return new Entry(
        reservation.reservationId(),
        Kind.ORDER_RESERVATION,
        source(order == null ? reservation.actorRole() : order.getCreatedByRole()),
        reservation.createdAt(),
        expiresAt,
        visible ? order.getClient().getDisplayName() : null,
        visible ? managerName(order) : null,
        visible ? order.getId() : null,
        visible ? order.getOrderNumber() : null,
        visible ? order.getStatus() : null,
        visible ? order.getPaymentState() : null,
        visible);
  }

  private RentalOrder visibleOrder(OrderActor actor, UUID orderId) {
    return orderId == null
        ? null
        : orders.findWithClientById(orderId).filter(value -> isVisible(actor, value)).orElse(null);
  }

  private boolean isVisible(OrderActor actor, RentalOrder order) {
    return access.isVisible(actor, order)
        && (order.getWarehouseId() == null
            || access.canReadWarehouse(actor, order.getWarehouseId()));
  }

  private static String managerName(RentalOrder order) {
    return "CUSTOMER".equals(order.getCreatedByRole()) ? null : order.getManagerDisplayName();
  }

  private static Source source(String actorRole) {
    return "CUSTOMER".equals(actorRole) ? Source.CUSTOMER : Source.MANAGER;
  }

  private static void validate(
      RentalItemReserveSnapshot snapshot, UUID rentalItemId, UUID warehouseId) {
    if (snapshot == null
        || !rentalItemId.equals(snapshot.rentalItemId())
        || !warehouseId.equals(snapshot.warehouseId())
        || snapshot.serverTime() == null
        || snapshot.holds() == null) throw invalid();
    var ids = new HashSet<UUID>();
    for (SelectionHold hold : snapshot.holds()) {
      if (hold == null
          || hold.holdId() == null
          || !ids.add(hold.holdId())
          || hold.holdScopeId() == null
          || hold.version() == null
          || hold.version() < 0
          || hold.actorSubjectId() == null
          || hold.actorRole() == null
          || !ACTOR_ROLES.contains(hold.actorRole())
          || hold.createdAt() == null
          || hold.createdAt().isAfter(snapshot.serverTime())
          || hold.expiresAt() == null
          || !hold.expiresAt().isAfter(snapshot.serverTime())) throw invalid();
    }
    var reservation = snapshot.orderReservation();
    if (reservation != null
        && (reservation.reservationId() == null
            || !ids.add(reservation.reservationId())
            || reservation.version() == null
            || reservation.version() < 0
            || reservation.orderId() == null
            || reservation.actorSubjectId() == null
            || reservation.actorRole() == null
            || !ACTOR_ROLES.contains(reservation.actorRole())
            || reservation.createdAt() == null
            || reservation.createdAt().isAfter(snapshot.serverTime()))) throw invalid();
  }

  private static OrderProblemException invalid() {
    return new OrderProblemException(
        HttpStatus.BAD_GATEWAY,
        "RENTAL_ITEM_RESERVES_INVALID_RESPONSE",
        "Сервис имущества вернул некорректные резервы бытовки");
  }

  private static OrderProblemException notFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "RENTAL_ITEM_NOT_FOUND", "Бытовка не найдена на складе");
  }
}
