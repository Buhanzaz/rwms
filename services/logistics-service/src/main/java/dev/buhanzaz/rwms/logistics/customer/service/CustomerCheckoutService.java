package dev.buhanzaz.rwms.logistics.customer.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerBookingResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinEquipmentSelection;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinRentalTerm;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCheckoutRequest;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CabinFurnitureRequirement;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.ConfirmClientPresentationRequest;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.PresentationCabinSelectionInput;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.PresentationEquipmentSelectionInput;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.PresentationGroupInput;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.PresentationBookingResponse;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.PublishClientPresentationRequest;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentationMode;
import dev.buhanzaz.rwms.logistics.inquiry.service.ClientPresentationService;
import dev.buhanzaz.rwms.logistics.inquiry.service.PresentationBookingService;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.DesiredDeliveryWindowInput;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.service.CabinFurnitureTaskService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Customer checkout saga facade. It reuses the existing presentation hold conversion and booking
 * receipt, then confirms the route slot only after the resulting order is durably saved.
 */
@Service
@RequiredArgsConstructor
public class CustomerCheckoutService {
  private final CustomerRentalService rentals;
  private final CustomerRentalSessionStore sessions;
  private final CustomerCheckoutStore checkoutStore;
  private final CustomerEquipmentCodec equipmentCodec;
  private final CustomerRentalTermCodec rentalTermCodec;
  private final CustomerDeliverySlotService slots;
  private final CustomerBookingService customerBookings;
  private final CustomerAuthorizer access;
  private final ClientPresentationService presentations;
  private final PresentationBookingService bookings;
  private final CabinFurnitureTaskService furnitureTasks;

  /** Submits or idempotently resumes one customer checkout. */
  public CustomerBookingResponse checkout(
      CustomerIdentity identity,
      UUID inquiryId,
      UUID idempotencyKey,
      CustomerCheckoutRequest request) {
    CustomerRentalSession current = rentals.requiredSession(identity, inquiryId);
    List<UUID> cabinIds = List.of();
    List<CustomerCabinEquipmentSelection> furniture = List.of();
    List<CustomerCabinRentalTerm> rentalTerms =
        rentalTermCodec.decode(current.getRentalTermsJson());
    if (current.getState() != CustomerSessionState.BOOKED && current.getBookingId() == null) {
      cabinIds = rentals.selectedCabinIds(identity, inquiryId);
      if (cabinIds.isEmpty()) {
        throw conflict("CUSTOMER_CABINS_REQUIRED", "В корзине нет бытовок");
      }
      furniture = equipmentCodec.decode(current.getEquipmentSelectionJson());
      Set<UUID> selected = Set.copyOf(cabinIds);
      rentalTerms =
          rentalTermCodec.completeWithDefaults(current.getRentalTermsJson(), selected);
      if (furniture.stream().anyMatch(item -> !selected.contains(item.cabinUnitId()))) {
        throw conflict(
            "CUSTOMER_EQUIPMENT_SELECTION_INVALID",
            "Мебель выбрана для бытовки, которой больше нет в корзине");
      }
      Set<UUID> termCabins =
          rentalTerms.stream()
              .map(CustomerCabinRentalTerm::cabinUnitId)
              .collect(java.util.stream.Collectors.toSet());
      if (termCabins.size() != rentalTerms.size() || !termCabins.equals(selected)) {
        throw conflict(
            "CUSTOMER_RENTAL_TERMS_REQUIRED",
            "Укажите срок аренды для каждой бытовки в корзине");
      }
    }
    String requestHash = checkoutHash(inquiryId, request, rentalTerms);
    var preparation =
        checkoutStore.prepare(
            identity.subjectId(),
            inquiryId,
            request.expectedVersion(),
            idempotencyKey,
            requestHash,
            request.slotId(),
            request.slotVersion());
    CustomerRentalSession session = preparation.session();
    UUID commandKey = preparation.commandKey();
    if (preparation.completedReplay()) {
      return customerBookings.response(identity, session, "COMPLETED", null);
    }
    if (preparation.pendingReplay() && session.getBookingId() != null) {
      return reconcile(identity, session);
    }
    CustomerDeliverySlot slot = preparation.slot();
    UUID presentationKey = deterministic("customer-presentation:" + commandKey);
    var presentation =
        presentations.publish(
            access.orderActor(identity, session.getWarehouseId()),
            inquiryId,
            presentationKey,
            new PublishClientPresentationRequest(
                session.getWarehouseId(),
                List.of(new PresentationGroupInput("customer-cart", "Заказ клиента", cabinIds)),
                null,
                ClientPresentationMode.NORMAL,
                List.of()));
    String token = token(presentation.publicPath());
    PresentationBookingResponse booking =
        bookings.confirm(
            token,
            commandKey,
            new ConfirmClientPresentationRequest(
                selections(cabinIds, furniture, rentalTerms),
                List.of(
                    new DesiredDeliveryWindowInput(
                        slot.getDeliveryDate(), slot.getDeliveryDate())),
                null,
                slot.getDeliveryAddress(),
                slot.getLatitude(),
                slot.getLongitude(),
                List.of()));
    session =
        checkoutStore.recordBooking(
            identity.subjectId(),
            inquiryId,
            commandKey,
            requestHash,
            request.slotId(),
            booking.bookingId(),
            booking.orderId(),
            token);
    return reconcile(identity, session);
  }

  /** Lists all customer booking outcomes, reconciling known pending receipts first. */
  public List<CustomerBookingResponse> bookings(CustomerIdentity identity) {
    List<CustomerBookingResponse> result = new ArrayList<>();
    for (CustomerRentalSession session : sessions.list(identity.subjectId())) {
      if (session.getBookingId() == null) continue;
      if (session.getState()
          == dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState.CHECKOUT_PENDING) {
        result.add(reconcile(identity, session));
      } else {
        result.add(customerBookings.response(identity, session, "COMPLETED", null));
      }
    }
    return List.copyOf(result);
  }

  /** Periodically joins completed existing booking receipts to their held delivery workload. */
  @Scheduled(
      fixedDelayString = "${rwms.logistics.customer.booking-reconcile-delay:2s}",
      initialDelayString = "${rwms.logistics.customer.booking-reconcile-initial-delay:3s}")
  public void reconcilePending() {
    for (CustomerRentalSession session : sessions.pendingBookings()) {
      if (session.getBookingId() == null || session.getPresentationToken() == null) continue;
      try {
        reconcile(
            new CustomerIdentity(
                session.getCustomerSubjectId(), session.getCustomerSubjectId().toString()),
            session);
      } catch (RuntimeException ignored) {
        // The durable booking receipt and slot hold remain available for the next bounded retry.
      }
    }
  }

  private CustomerBookingResponse reconcile(
      CustomerIdentity identity, CustomerRentalSession session) {
    slots.bindCheckoutBooking(
        identity,
        session.getInquiryId(),
        session.getDeliverySlotId(),
        session.getCheckoutCommandKey(),
        session.getBookingId(),
        session.getOrderId());
    PresentationBookingResponse status =
        bookings.status(session.getPresentationToken(), session.getBookingId());
    if ("COMPLETED".equals(status.state())) {
      if (status.orderId() == null) {
        throw new OrderProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "CUSTOMER_BOOKING_INVALID",
            "Бронирование завершено без заказа");
      }
      CustomerDeliverySlot confirmedSlot =
          slots.confirm(
              identity,
              session.getInquiryId(),
              session.getDeliverySlotId(),
              status.bookingId(),
              status.orderId());
      createFurnitureTasks(identity, session, confirmedSlot.getDeliveryDate());
      CustomerRentalSession completed =
          sessions.completeBooking(
              identity.subjectId(),
              session.getInquiryId(),
              status.bookingId(),
              status.orderId());
      return customerBookings.response(identity, completed, status.state(), status.errorCode());
    }
    if ("REJECTED".equals(status.state())) {
      CustomerRentalSession rejected =
          checkoutStore.rejectBooking(
              identity.subjectId(),
              session.getInquiryId(),
              session.getDeliverySlotId(),
              status.bookingId());
      return customerBookings.response(identity, rejected, status.state(), status.errorCode());
    }
    return customerBookings.response(identity, session, status.state(), status.errorCode());
  }

  private static List<PresentationCabinSelectionInput> selections(
      List<UUID> cabinIds,
      List<CustomerCabinEquipmentSelection> furniture,
      List<CustomerCabinRentalTerm> rentalTerms) {
    Map<UUID, List<PresentationEquipmentSelectionInput>> byCabin = new LinkedHashMap<>();
    Map<UUID, Long> monthsByCabin =
        rentalTerms.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    CustomerCabinRentalTerm::cabinUnitId,
                    CustomerCabinRentalTerm::rentalMonths));
    cabinIds.forEach(cabinId -> byCabin.put(cabinId, new ArrayList<>()));
    furniture.forEach(
        item ->
            byCabin
                .get(item.cabinUnitId())
                .add(
                    new PresentationEquipmentSelectionInput(
                        item.inventoryItemId(), item.quantity())));
    return byCabin.entrySet().stream()
        .map(
            entry ->
                new PresentationCabinSelectionInput(
                    entry.getKey(),
                    List.copyOf(entry.getValue()),
                    monthsByCabin.get(entry.getKey())))
        .toList();
  }

  private void createFurnitureTasks(
      CustomerIdentity identity, CustomerRentalSession session, LocalDate deliveryDate) {
    List<CustomerCabinEquipmentSelection> selections =
        equipmentCodec.decode(session.getEquipmentSelectionJson());
    Map<UUID, List<CabinFurnitureRequirement>> byCabin = new LinkedHashMap<>();
    selections.forEach(
        selection ->
            byCabin
                .computeIfAbsent(selection.cabinUnitId(), ignored -> new ArrayList<>())
                .add(
                    new CabinFurnitureRequirement(
                        selection.inventoryItemId(), selection.quantity())));
    byCabin.forEach(
        (cabinId, requirements) ->
            furnitureTasks.create(
                identity.subjectId(),
                deterministic(
                    "customer-furniture-task:" + session.getBookingId() + ":" + cabinId),
                session.getWarehouseId(),
                cabinId,
                deliveryDate,
                List.copyOf(requirements)));
  }

  private static String token(String publicPath) {
    if (publicPath == null || publicPath.isBlank() || !publicPath.contains("/")) {
      throw new IllegalStateException("Customer presentation path is invalid");
    }
    return publicPath.substring(publicPath.lastIndexOf('/') + 1);
  }

  private static String checkoutHash(
      UUID inquiryId,
      CustomerCheckoutRequest request,
      List<CustomerCabinRentalTerm> rentalTerms) {
    String terms =
        rentalTerms.stream()
            .sorted(java.util.Comparator.comparing(CustomerCabinRentalTerm::cabinUnitId))
            .map(term -> term.cabinUnitId() + ":" + term.rentalMonths())
            .collect(java.util.stream.Collectors.joining("\n"));
    String value =
        inquiryId
            + "\n"
            + request.expectedVersion()
            + "\n"
            + request.slotId()
            + "\n"
            + request.slotVersion()
            + "\n"
            + terms;
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static UUID deterministic(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }
}
