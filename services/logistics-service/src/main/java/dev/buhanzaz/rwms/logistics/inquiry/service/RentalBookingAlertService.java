package dev.buhanzaz.rwms.logistics.inquiry.service;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.RentalBookingAlertActionRequest;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.RentalBookingAlertCabin;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.RentalBookingAlertResponse;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentation;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentationItem;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBooking;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingManagerAction;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingState;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.inquiry.mapper.RentalBookingAlertResponseMapper;
import dev.buhanzaz.rwms.logistics.inquiry.repository.ClientPresentationItemRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.ClientPresentationRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.PresentationBookingRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalInquiryRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.AvailableCabin;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RentalBookingAlertService {
  private final PresentationBookingRepository bookings;
  private final ClientPresentationRepository presentations;
  private final ClientPresentationItemRepository items;
  private final RentalInquiryRepository inquiries;
  private final RentalBookingAlertResponseMapper mapper;
  private final ObjectMapper json;

  public List<RentalBookingAlertResponse> list(OrderActor actor) {
    UUID managerId = requiredManagerId(actor);
    return bookings
        .findUnprocessedByManagerIdAndState(managerId, PresentationBookingState.COMPLETED)
        .stream()
        .map(this::response)
        .toList();
  }

  @Transactional
  public void act(
      OrderActor actor,
      UUID bookingId,
      UUID idempotencyKey,
      RentalBookingAlertActionRequest request) {
    if (bookingId == null || idempotencyKey == null || request == null || request.action() == null) {
      throw new IllegalArgumentException("Booking action and Idempotency-Key are required");
    }
    PresentationBooking booking =
        bookings
            .findForUpdateByIdAndManagerIdAndState(
                bookingId, requiredManagerId(actor), PresentationBookingState.COMPLETED)
            .orElseThrow(() -> notFound("Подтверждение клиента не найдено"));
    PresentationBookingManagerAction action = request.action();
    if (booking.hasManagerAction(action, idempotencyKey)) return;
    if (booking.getManagerAction() != null) {
      if (booking.getManagerAction() != action) {
        throw conflict(
            "PRESENTATION_BOOKING_ALERT_ACTION_CONFLICT",
            "Для подтверждения уже выбрано другое действие");
      }
      throw conflict(
          "PRESENTATION_BOOKING_ALERT_IDEMPOTENCY_CONFLICT",
          "Idempotency-Key уже использован для этого подтверждения");
    }
    if (request.expectedVersion() == null
        || request.expectedVersion() < 0
        || booking.getVersion() != request.expectedVersion()) {
      throw conflict(
          "PRESENTATION_BOOKING_ALERT_VERSION_CONFLICT",
          "Подтверждение клиента было изменено параллельно; обновите список");
    }
    booking.recordManagerAction(action, idempotencyKey, now());
    bookings.saveAndFlush(booking);
  }

  private RentalBookingAlertResponse response(PresentationBooking booking) {
    ClientPresentation presentation =
        presentations
            .findById(booking.getPresentationId())
            .orElseThrow(() -> corrupt("Представление подтверждения отсутствует"));
    RentalInquiry inquiry =
        inquiries
            .findById(presentation.getInquiryId())
            .orElseThrow(() -> corrupt("Диалог подтверждения отсутствует"));
    List<UUID> selected = selected(booking.getSelectedItemIdsJson());
    Set<UUID> selectedIds = new LinkedHashSet<>(selected);
    List<RentalBookingAlertCabin> cabins =
        items
            .findAllByPresentationIdAndPresentationRevisionOrderBySortOrderAscIdAsc(
                booking.getPresentationId(), booking.getPresentationRevision())
            .stream()
            .filter(item -> selectedIds.contains(item.getRentalItemId()))
            .map(this::cabin)
            .toList();
    if (cabins.size() != selectedIds.size()) {
      throw corrupt("Снимок выбранных бытовок неполный");
    }
    return mapper.toResponse(booking, inquiry, cabins);
  }

  private RentalBookingAlertCabin cabin(ClientPresentationItem item) {
    AvailableCabin snapshot;
    try {
      snapshot = json.readValue(item.getCabinSnapshotJson(), AvailableCabin.class);
    } catch (JacksonException exception) {
      throw corrupt("Снимок бытовки повреждён");
    }
    if (!Objects.equals(item.getRentalItemId(), snapshot.id())) {
      throw corrupt("Снимок бытовки не соответствует выбору клиента");
    }
    return mapper.toCabin(snapshot);
  }

  private List<UUID> selected(String value) {
    try {
      List<UUID> selected = json.readValue(value, new TypeReference<List<UUID>>() {});
      if (selected == null || selected.isEmpty()) throw corrupt("Выбор клиента отсутствует");
      LinkedHashSet<UUID> unique = new LinkedHashSet<>(selected);
      if (unique.size() != selected.size() || unique.contains(null)) {
        throw corrupt("Выбор клиента повреждён");
      }
      return List.copyOf(selected);
    } catch (JacksonException exception) {
      throw corrupt("Выбор клиента повреждён");
    }
  }

  private static UUID requiredManagerId(OrderActor actor) {
    if (actor == null || actor.subjectId() == null) {
      throw new IllegalArgumentException("Manager identity is required");
    }
    return actor.subjectId();
  }

  private static OrderProblemException notFound(String message) {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "PRESENTATION_BOOKING_ALERT_NOT_FOUND", message);
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }

  private static IllegalStateException corrupt(String message) {
    return new IllegalStateException(message);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
