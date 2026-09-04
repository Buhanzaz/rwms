package dev.buhanzaz.rwms.logistics.inquiry.service;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.ConfirmClientPresentationRequest;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.PresentationBookingResponse;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.PresentationCabinSelectionInput;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentationMode;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBooking;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingState;
import dev.buhanzaz.rwms.logistics.inquiry.service.PresentationBookingStore.BookingContext;
import dev.buhanzaz.rwms.logistics.inquiry.service.PresentationBookingStore.RecoveryClaim;
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
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Owns the presentation booking lifecycle: a normal selection converts held cabins and the complete
 * per-cabin furniture composition atomically, then durably applies up to five individual client
 * receiving days, delivery facts and the initial term to the converted cabins. A replacement delegates
 * one ordered same-order batch without changing either. Pending effects use one bounded,
 * skip-locked lease path for synchronous confirmation and scheduled recovery; only terminal
 * rejection releases holds and allows a manager to publish the next presentation revision.
 * CustomerApp confirmations additionally save the resulting order before completion so they enter
 * the real planning feed without a manager-side browser saga.
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
    store.claim(booking.getId()).ifPresent(this::process);
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
    for (RecoveryClaim claim : store.claimDue()) {
      process(claim);
    }
  }

  /**
   * Performs remote and cross-aggregate work only after the short claim transaction committed.
   * Every following local mutation revalidates the immutable lease capability.
   */
  void process(RecoveryClaim claim) {
    BookingContext context = store.claimedContext(claim).orElse(null);
    if (context == null || context.booking().getState() != PresentationBookingState.PENDING) return;
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
          if (!store.assignOrder(claim, orderId)) return;
          context = store.claimedContext(claim).orElse(null);
          if (context == null) return;
        } else {
          RentalOrderService.CreateResult created =
              rentalOrders.create(
                  actor,
                  context.booking().getId(),
                  new CreateOrderRequest(
                      context.inquiry().getClient().getId(),
                      null,
                      context.inquiry().getClient().getPhone(),
                      null));
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
          if (!store.assignOrder(claim, orderId)) return;
          context = store.claimedContext(claim).orElse(null);
          if (context == null) return;
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
          RentalOrderService.MutationResult applied =
              rentalOrders.applyPresentationSelection(
              actor,
              orderId,
              context.booking().getId(),
              converted,
              requirements,
              normalDesiredDeliveryWindows(context),
              normalRentalTerms(context),
              context.rentalMonths(),
              normalDeliveryAddress(context),
              context.latitude(),
              context.longitude(),
              normalAdditionalContacts(context),
              context.legacyDesiredDeliveryTimes());
          if ("CUSTOMER".equals(actor.role())
              && applied.response().status() == RentalOrderStatus.DRAFT) {
            rentalOrders.save(
                actor,
                orderId,
                applied.response().version(),
                deterministic("customer-order-save:" + context.booking().getId()),
                context.booking().getId());
          }
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
      store.complete(claim);
    } catch (LogisticsDependencyException exception) {
      if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        reject(claim, context, actor, exception.dependencyCode());
      } else {
        store.attempted(claim, "DEPENDENCY_TRANSIENT");
      }
    } catch (OrderProblemException exception) {
      reject(claim, context, actor, exception.code());
    } catch (RuntimeException exception) {
      store.attempted(claim, "UNKNOWN_OUTCOME");
    }
  }

  private void reject(
      RecoveryClaim claim, BookingContext context, OrderActor actor, String errorCode) {
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
        claim, errorCode == null ? "PRESENTATION_BOOKING_REJECTED" : errorCode);
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

  /**
   * Rebuilds the already validated, chronologically ordered client receiving days from the durable
   * normal-presentation receipt.
   */
  private static List<DesiredDeliveryWindow> normalDesiredDeliveryWindows(BookingContext context) {
    if (context.desiredDeliveryWindows().isEmpty()) {
      throw new OrderProblemException(
          org.springframework.http.HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_DELIVERY_WINDOW_REQUIRED",
          "Не найдена желаемая дата получения бытовок");
    }
    if (context.desiredDeliveryWindows().size() > 5) {
      throw new OrderProblemException(
          org.springframework.http.HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_DELIVERY_WINDOW_INVALID",
          "Сохранено больше пяти желаемых дат получения бытовок");
    }
    LinkedHashSet<LocalDate> selectedDays = new LinkedHashSet<>();
    for (var window : context.desiredDeliveryWindows()) {
      if (window.startDate() == null
          || window.endDate() == null
          || !window.startDate().equals(window.endDate())
          || !selectedDays.add(window.startDate())) {
        throw new OrderProblemException(
            org.springframework.http.HttpStatus.CONFLICT,
            "CLIENT_PRESENTATION_DELIVERY_WINDOW_INVALID",
            "Сохранённые желаемые даты получения бытовок некорректны");
      }
    }
    return selectedDays.stream()
        .sorted(Comparator.naturalOrder())
        .map(day -> DesiredDeliveryWindow.create(day, day))
        .toList();
  }

  /** Resolves every selected cabin's immutable initial duration from the durable receipt. */
  private static Map<UUID, Long> normalRentalTerms(BookingContext context) {
    Map<UUID, Long> result = new LinkedHashMap<>();
    for (PresentationCabinSelectionInput selection : context.selections()) {
      Long months =
          selection.rentalMonths() == null ? context.rentalMonths() : selection.rentalMonths();
      if (months == null || months < 1 || months > 120) {
        throw new OrderProblemException(
            org.springframework.http.HttpStatus.CONFLICT,
            "CLIENT_PRESENTATION_RENTAL_MONTHS_REQUIRED",
            "Не найден срок аренды выбранной бытовки");
      }
      result.put(selection.rentalItemId(), months);
    }
    if (result.size() != context.selections().size()) {
      throw new OrderProblemException(
          org.springframework.http.HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_RENTAL_MONTHS_INVALID",
          "Сроки аренды бытовок содержат повторяющиеся позиции");
    }
    return Map.copyOf(result);
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
