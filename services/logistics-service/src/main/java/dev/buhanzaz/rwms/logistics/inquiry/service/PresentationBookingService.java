package dev.buhanzaz.rwms.logistics.inquiry.service;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.ConfirmClientPresentationRequest;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.PresentationBookingResponse;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.PresentationCabinSelectionInput;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentationMode;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBooking;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingState;
import dev.buhanzaz.rwms.logistics.inquiry.service.PresentationBookingStore.BookingContext;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRequest;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.domain.AdditionalContact;
import dev.buhanzaz.rwms.logistics.order.domain.DesiredDeliveryWindow;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderService;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderUnitReplacementService;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Owns the presentation booking lifecycle: a normal selection converts held cabins and the complete
 * per-cabin furniture composition atomically, then durably applies its one-day client receiving
 * preference, delivery facts and initial term to the converted cabins. A replacement delegates
 * one ordered same-order batch without changing either. Pending effects are retried from the
 * existing booking receipt; only terminal rejection releases holds and allows a manager to publish
 * the next presentation revision.
 */
@Service
@RequiredArgsConstructor
public class PresentationBookingService {
  private final PresentationBookingStore store;
  private final ClientPresentationService presentations;
  private final RentalOrderService rentalOrders;
  private final LogisticsDependencyGateway dependencies;
  private final RentalOrderUnitReplacementService replacements;

  public PresentationBookingResponse confirm(
      String token, UUID idempotencyKey, ConfirmClientPresentationRequest request) {
    PresentationBooking booking = store.begin(token, idempotencyKey, request);
    process(booking.getId());
    return status(token, booking.getId());
  }

  public PresentationBookingResponse status(String token, UUID bookingId) {
    var presentation = presentations.resolveForBookingStatus(token);
    BookingContext context = store.context(bookingId);
    if (!context.presentation().getId().equals(presentation.getId())
        || context.booking().getPresentationRevision() != presentation.getRevision()) {
      throw new OrderProblemException(
          org.springframework.http.HttpStatus.NOT_FOUND,
          "PRESENTATION_BOOKING_NOT_FOUND",
          "Бронирование не найдено");
    }
    return response(context.booking(), token);
  }

  @Scheduled(
      fixedDelayString = "${rwms.logistics.rental-inquiry.booking-retry-delay:2s}",
      initialDelayString = "${rwms.logistics.rental-inquiry.booking-retry-initial-delay:2s}")
  public void retryPending() {
    for (UUID bookingId : store.pendingIds()) {
      process(bookingId);
    }
  }

  void process(UUID bookingId) {
    BookingContext context = store.context(bookingId);
    if (context.booking().getState() != PresentationBookingState.PENDING) return;
    OrderActor actor = actor(context);
    UUID orderId = context.booking().getOrderId();
    try {
      UUID holdScopeId = resolveHoldScope(context);
      if (orderId == null) {
        if (context.inquiry().getRentalOrderId() != null) {
          orderId = context.inquiry().getRentalOrderId();
          OrderDetailResponse target = rentalOrders.get(actor, orderId);
          boolean normal = context.presentation().getMode() == ClientPresentationMode.NORMAL;
          boolean targetLifecycleValid =
              normal
                  ? target.permissions().canEdit()
                  : target.status() == RentalOrderStatus.DRAFT
                      || target.status() == RentalOrderStatus.SAVED;
          if (!targetLifecycleValid
              || !target.client().id().equals(context.inquiry().getClient().getId())) {
            throw new OrderProblemException(
                org.springframework.http.HttpStatus.CONFLICT,
                "INQUIRY_ORDER_INVALID",
                normal
                    ? "Заказ больше нельзя дополнять через это представление"
                    : "Заказ больше нельзя изменять через представление замены");
          }
          if (normal && target.warehouseId() == null) {
            target =
                rentalOrders
                    .selectWarehouseForPresentation(
                        actor,
                        orderId,
                        deterministic("presentation-order-warehouse:" + context.booking().getId()),
                        target.version(),
                        context.presentation().getWarehouseId())
                    .response();
          }
          if (target.warehouseId() == null
              || !context.presentation().getWarehouseId().equals(target.warehouseId())) {
            throw new OrderProblemException(
                org.springframework.http.HttpStatus.CONFLICT,
                "ORDER_WAREHOUSE_LOCKED",
                "Представление использует другой склад заказа");
          }
          store.assignOrder(bookingId, orderId);
          context = store.context(bookingId);
        } else {
          RentalOrderService.CreateResult created =
              rentalOrders.create(
                  actor,
                  context.booking().getId(),
                  new CreateOrderRequest(context.inquiry().getClient().getId(), null, null, null));
          OrderDetailResponse order = created.response();
          orderId = order.id();
          if (order.warehouseId() == null) {
            UUID warehouseKey =
                deterministic("presentation-order-warehouse:" + context.booking().getId());
            order =
                rentalOrders
                    .selectWarehouseForPresentation(
                        actor,
                        order.id(),
                        warehouseKey,
                        order.version(),
                        context.presentation().getWarehouseId())
                    .response();
          }
          store.assignOrder(bookingId, orderId);
          context = store.context(bookingId);
        }
      }
      if (context.presentation().getMode() == ClientPresentationMode.NORMAL) {
        Map<UUID, Map<UUID, Long>> requirements = selectedRequirements(context.selections());
        List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> composition =
            rentalOrders.presentationComposition(actor, orderId, requirements);
        LogisticsDependencyGateway.ConvertedPresentationHolds converted =
            dependencies.convertPresentationHolds(
                context.booking().getId(),
                holdScopeId,
                orderId,
                context.presentation().getWarehouseId(),
                context.selectedRentalItemIds(),
                context.inquiry().getClient().getId(),
                context.inquiry().getClient().getDisplayName(),
                context.inquiry().getManagerId(),
                context.inquiry().getManagerRole(),
                composition);
        requireCompleteConversion(context, holdScopeId, orderId, converted);
        try {
          rentalOrders.applyPresentationSelection(
              actor,
              orderId,
              context.booking().getId(),
              converted,
              requirements,
              normalDesiredDeliveryWindow(context),
              normalRentalMonths(context),
              normalDeliveryAddress(context),
              context.latitude(),
              context.longitude(),
              normalAdditionalContacts(context),
              context.legacyDesiredDeliveryTimes());
        } catch (RuntimeException exception) {
          throw new LogisticsDependencyException(
              LogisticsDependencyException.FailureKind.TRANSIENT,
              "Atomic presentation conversion is awaiting local reconciliation",
              exception);
        }
      } else {
        replacements.replaceFromPresentation(
            actor,
            orderId,
            holdScopeId,
            context.booking().getId(),
            presentations.replacementUnitIds(context.presentation()),
            context.selectedRentalItemIds());
      }
      store.complete(bookingId);
    } catch (LogisticsDependencyException exception) {
      if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        reject(context, actor, exception.dependencyCode());
      } else {
        store.attempted(bookingId, "DEPENDENCY_TRANSIENT");
      }
    } catch (OrderProblemException exception) {
      reject(context, actor, exception.code());
    } catch (RuntimeException exception) {
      store.attempted(bookingId, "UNKNOWN_OUTCOME");
    }
  }

  private void reject(BookingContext context, OrderActor actor, String errorCode) {
    UUID orderId = context.booking().getOrderId();
    if (orderId != null && context.inquiry().getRentalOrderId() == null) {
      try {
        OrderDetailResponse order = rentalOrders.get(actor, orderId);
        if (order.status() == RentalOrderStatus.DRAFT) {
          rentalOrders.cancel(
              actor,
              orderId,
              order.version(),
              deterministic("presentation-order-cancel:" + context.booking().getId()));
        }
      } catch (RuntimeException ignored) {
        // A later reconciliation can cancel a draft whose external conversion was rejected.
      }
    }
    releaseHoldScope(context, context.inquiry().getId(), "inquiry");
    releaseHoldScope(context, context.presentation().getId(), "legacy-presentation");
    store.reject(
        context.booking().getId(), errorCode == null ? "PRESENTATION_BOOKING_REJECTED" : errorCode);
  }

  private static OrderActor actor(BookingContext context) {
    UUID warehouseId = context.presentation().getWarehouseId();
    boolean global =
        Set.of("SYSTEM_ADMIN", "WMS_ADMIN").contains(context.inquiry().getManagerRole());
    boolean local = "WAREHOUSE_MANAGER".equals(context.inquiry().getManagerRole());
    return new OrderActor(
        context.inquiry().getManagerId(),
        context.inquiry().getManagerRole(),
        context.inquiry().getManagerDisplayName(),
        Set.of(warehouseId),
        Set.of(warehouseId),
        global,
        local,
        true,
        true);
  }

  private static PresentationBookingResponse response(PresentationBooking booking, String token) {
    return new PresentationBookingResponse(
        booking.getId(),
        booking.getState().name(),
        booking.getOrderId(),
        "/api/logistics/public/v1/client-presentations/" + token + "/bookings/" + booking.getId(),
        booking.getLastErrorCode());
  }

  private void requireCompleteConversion(
      BookingContext context,
      UUID holdScopeId,
      UUID orderId,
      LogisticsDependencyGateway.ConvertedPresentationHolds converted) {
    if (converted == null
        || !holdScopeId.equals(converted.presentationId())
        || !orderId.equals(converted.orderId())
        || converted.reservations() == null
        || converted.releasedRentalItemIds() == null) {
      throw invalidConversion();
    }
    Set<UUID> selected = Set.copyOf(context.selectedRentalItemIds());
    if (converted.reservations().stream().anyMatch(java.util.Objects::isNull)
        || converted.releasedRentalItemIds().stream().anyMatch(java.util.Objects::isNull)) {
      throw invalidConversion();
    }
    Set<UUID> convertedIds =
        converted.reservations().stream()
            .map(LogisticsDependencyGateway.OrderUnitReservation::unitId)
            .collect(java.util.stream.Collectors.toSet());
    if (convertedIds.size() != converted.reservations().size()
        || !convertedIds.equals(selected)
        || converted.reservations().stream()
            .anyMatch(
                reservation ->
                    !orderId.equals(reservation.orderId())
                        || !context
                            .presentation()
                            .getWarehouseId()
                            .equals(reservation.warehouseId())
                        || !"ACTIVE".equals(reservation.state()))) {
      throw invalidConversion();
    }
    Set<UUID> all = Set.copyOf(presentations.currentCabinIds(context.presentation()));
    Set<UUID> expectedReleased = new java.util.HashSet<>(all);
    expectedReleased.removeAll(selected);
    Set<UUID> released = new java.util.HashSet<>(converted.releasedRentalItemIds());
    if (released.size() != converted.releasedRentalItemIds().size()
        || !released.equals(expectedReleased)) {
      throw invalidConversion();
    }
  }

  private UUID resolveHoldScope(BookingContext context) {
    List<UUID> expected = presentations.currentCabinIds(context.presentation());
    UUID inquiryScope = context.inquiry().getId();
    if (containsExactly(dependencies.readPresentationHolds(inquiryScope), inquiryScope, expected)) {
      return inquiryScope;
    }
    UUID legacyScope = context.presentation().getId();
    if (containsExactly(dependencies.readPresentationHolds(legacyScope), legacyScope, expected)) {
      return legacyScope;
    }
    return inquiryScope;
  }

  private static boolean containsExactly(
      LogisticsDependencyGateway.PresentationHolds current, UUID scopeId, List<UUID> expected) {
    if (current == null
        || !scopeId.equals(current.presentationId())
        || current.holds() == null
        || current.holds().stream().anyMatch(java.util.Objects::isNull)) {
      return false;
    }
    Set<UUID> actual =
        current.holds().stream()
            .filter(hold -> "ACTIVE".equals(hold.state()))
            .map(LogisticsDependencyGateway.PresentationHold::rentalItemId)
            .collect(java.util.stream.Collectors.toSet());
    return actual.size() == current.holds().size() && actual.equals(Set.copyOf(expected));
  }

  private void releaseHoldScope(BookingContext context, UUID scopeId, String scopeName) {
    try {
      dependencies.releasePresentationHolds(
          deterministic(
              "presentation-holds-release:" + scopeName + ":" + context.booking().getId()),
          scopeId,
          context.inquiry().getManagerId(),
          context.inquiry().getManagerRole());
    } catch (RuntimeException ignored) {
      // Every hold also has a hard expiry if best-effort compensation is unavailable.
    }
  }

  private static OrderProblemException invalidConversion() {
    return new OrderProblemException(
        org.springframework.http.HttpStatus.CONFLICT,
        "PRESENTATION_CONVERSION_INVALID",
        "Сервис имущества не подтвердил полный перевод выбранных бытовок");
  }

  private static UUID deterministic(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static Map<UUID, Map<UUID, Long>> selectedRequirements(
      List<PresentationCabinSelectionInput> selections) {
    Map<UUID, Map<UUID, Long>> result = new LinkedHashMap<>();
    for (PresentationCabinSelectionInput selection : selections) {
      Map<UUID, Long> equipment = new LinkedHashMap<>();
      selection.equipment().forEach(value -> equipment.put(value.equipmentId(), value.quantity()));
      result.put(selection.rentalItemId(), Map.copyOf(equipment));
    }
    return Map.copyOf(result);
  }

  /** Rebuilds the already validated one-day client preference from the durable booking receipt. */
  private static DesiredDeliveryWindow normalDesiredDeliveryWindow(BookingContext context) {
    if (context.desiredDeliveryWindows().size() != 1) {
      throw new OrderProblemException(
          org.springframework.http.HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_DELIVERY_WINDOW_REQUIRED",
          "Не найдена желаемая дата получения бытовок");
    }
    var window = context.desiredDeliveryWindows().getFirst();
    if (window.startDate() == null
        || window.endDate() == null
        || !window.startDate().equals(window.endDate())) {
      throw new OrderProblemException(
          org.springframework.http.HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_DELIVERY_WINDOW_INVALID",
          "Сохранённая желаемая дата получения бытовок некорректна");
    }
    return DesiredDeliveryWindow.create(window.startDate(), window.endDate());
  }

  /** Reads the duration from the durable booking receipt before local order reconciliation. */
  private static long normalRentalMonths(BookingContext context) {
    Long rentalMonths = context.rentalMonths();
    if (rentalMonths == null || rentalMonths < 1) {
      throw new OrderProblemException(
          org.springframework.http.HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_RENTAL_MONTHS_REQUIRED",
          "Не найден срок аренды бытовок");
    }
    return rentalMonths;
  }

  /**
   * Returns the durable client address for a current confirmation. A pre-date-only pending receipt
   * has no such snapshot, so it intentionally preserves already order-owned details instead.
   */
  private static String normalDeliveryAddress(BookingContext context) {
    if (context.deliveryAddress() != null) return context.deliveryAddress();
    if (!context.legacyDesiredDeliveryTimes().isEmpty()) return null;
    throw new OrderProblemException(
        org.springframework.http.HttpStatus.CONFLICT,
        "CLIENT_PRESENTATION_DELIVERY_ADDRESS_REQUIRED",
        "Не найден адрес доставки бытовок");
  }

  private static List<AdditionalContact> normalAdditionalContacts(BookingContext context) {
    return context.additionalContacts().stream()
        .map(contact -> AdditionalContact.create(contact.name(), contact.phone()))
        .toList();
  }
}
