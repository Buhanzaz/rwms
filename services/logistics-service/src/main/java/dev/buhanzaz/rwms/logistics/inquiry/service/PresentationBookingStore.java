package dev.buhanzaz.rwms.logistics.inquiry.service;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.ConfirmClientPresentationRequest;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentation;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBooking;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingState;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.inquiry.eventing.RentalInquiryBookedOutboxStore;
import dev.buhanzaz.rwms.logistics.inquiry.repository.ClientPresentationRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.PresentationBookingRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalInquiryRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class PresentationBookingStore {
  private final PresentationBookingRepository bookings;
  private final ClientPresentationRepository presentations;
  private final RentalInquiryRepository inquiries;
  private final ClientPresentationService presentationService;
  private final RentalInquiryBookedOutboxStore outbox;
  private final ObjectMapper json;

  @Transactional
  public PresentationBooking begin(
      String token,
      UUID idempotencyKey,
      ConfirmClientPresentationRequest request) {
    ClientPresentation resolved =
        presentationService.resolveForBookingStatus(token);
    ClientPresentation presentation =
        presentations
            .findForUpdate(resolved.getId())
            .orElseThrow(() -> notFound("Представление не найдено"));
    OffsetDateTime timestamp = now();
    if (presentation.getRevision() != resolved.getRevision()
        || !presentation.getViewUntil().isAfter(timestamp)) {
      throw new OrderProblemException(
          HttpStatus.GONE,
          "CLIENT_PRESENTATION_GONE",
          "Срок действия представления истёк");
    }
    List<UUID> selected = unique(request.selectedRentalItemIds());
    List<UUID> available = presentationService.currentCabinIds(presentation);
    if (!new LinkedHashSet<>(available).containsAll(selected)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_SELECTION_INVALID",
          "Выбраны бытовки, которых нет в представлении");
    }
    String selectedJson = write(selected);
    PresentationBooking existing =
        bookings
            .findByPresentationIdAndPresentationRevision(
                presentation.getId(), presentation.getRevision())
            .orElse(null);
    if (existing != null) {
      if (!existing.getIdempotencyKey().equals(idempotencyKey)
          || !existing.getSelectedItemIdsJson().equals(selectedJson)) {
        throw new OrderProblemException(
            HttpStatus.CONFLICT,
            "CLIENT_PRESENTATION_ALREADY_SUBMITTED",
            "Выбор из этого представления уже отправлен");
      }
      return existing;
    }
    if (!presentation.canConfirm(timestamp)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_VIEW_ONLY",
          "Временный резерв истёк, представление доступно только для просмотра");
    }
    return bookings.saveAndFlush(
        PresentationBooking.create(
            presentation.getId(),
            presentation.getRevision(),
            idempotencyKey,
            selectedJson,
            timestamp));
  }

  @Transactional(readOnly = true)
  public BookingContext context(UUID bookingId) {
    PresentationBooking booking =
        bookings
            .findById(bookingId)
            .orElseThrow(() -> notFound("Бронирование не найдено"));
    ClientPresentation presentation =
        presentations
            .findById(booking.getPresentationId())
            .orElseThrow(() -> notFound("Представление не найдено"));
    RentalInquiry inquiry =
        inquiries
            .findById(presentation.getInquiryId())
            .orElseThrow(() -> notFound("Диалог аренды не найден"));
    return new BookingContext(
        booking, presentation, inquiry, selected(booking.getSelectedItemIdsJson()));
  }

  @Transactional
  public void assignOrder(UUID bookingId, UUID orderId) {
    PresentationBooking booking =
        bookings.findForUpdate(bookingId).orElseThrow(() -> notFound("Бронирование не найдено"));
    if (booking.getState() != PresentationBookingState.PENDING) return;
    ClientPresentation presentation =
        presentations
            .findForUpdate(booking.getPresentationId())
            .orElseThrow(() -> notFound("Представление не найдено"));
    booking.assignOrder(orderId, now());
    if (presentation.getState()
        == dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentationState.ACTIVE) {
      presentation.markBookingPending(orderId, now());
    }
    bookings.saveAndFlush(booking);
    presentations.saveAndFlush(presentation);
  }

  @Transactional
  public void attempted(UUID bookingId, String errorCode) {
    PresentationBooking booking =
        bookings.findForUpdate(bookingId).orElseThrow(() -> notFound("Бронирование не найдено"));
    booking.attempted(errorCode, now());
    bookings.saveAndFlush(booking);
  }

  @Transactional
  public void reject(UUID bookingId, String errorCode) {
    PresentationBooking booking =
        bookings.findForUpdate(bookingId).orElseThrow(() -> notFound("Бронирование не найдено"));
    if (booking.getState() != PresentationBookingState.PENDING) return;
    ClientPresentation presentation =
        presentations
            .findForUpdate(booking.getPresentationId())
            .orElseThrow(() -> notFound("Представление не найдено"));
    booking.reject(errorCode, now());
    presentation.revoke(now());
    bookings.saveAndFlush(booking);
    presentations.saveAndFlush(presentation);
  }

  @Transactional
  public void complete(UUID bookingId) {
    PresentationBooking booking =
        bookings.findForUpdate(bookingId).orElseThrow(() -> notFound("Бронирование не найдено"));
    if (booking.getState() == PresentationBookingState.COMPLETED) return;
    ClientPresentation presentation =
        presentations
            .findForUpdate(booking.getPresentationId())
            .orElseThrow(() -> notFound("Представление не найдено"));
    RentalInquiry inquiry =
        inquiries
            .findForUpdate(presentation.getInquiryId())
            .orElseThrow(() -> notFound("Диалог аренды не найден"));
    OffsetDateTime timestamp = now();
    booking.complete(timestamp);
    presentation.markBooked(booking.getOrderId(), timestamp);
    inquiry.markBooked(booking.getOrderId(), timestamp);
    bookings.saveAndFlush(booking);
    presentations.saveAndFlush(presentation);
    inquiries.saveAndFlush(inquiry);
    outbox.append(inquiry.getId(), inquiry.getConversationId(), booking.getOrderId());
  }

  @Transactional(readOnly = true)
  public List<UUID> pendingIds() {
    return bookings
        .findAllByStateOrderByCreatedAtAscIdAsc(
            PresentationBookingState.PENDING, PageRequest.of(0, 50))
        .stream()
        .map(PresentationBooking::getId)
        .toList();
  }

  private String write(List<UUID> values) {
    try {
      return json.writeValueAsString(values);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Selected cabins cannot be serialized", exception);
    }
  }

  private List<UUID> selected(String value) {
    try {
      return json.readValue(value, new TypeReference<List<UUID>>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored booking selection is corrupt", exception);
    }
  }

  private static List<UUID> unique(List<UUID> values) {
    if (values == null || values.isEmpty() || values.size() > 100) {
      throw new IllegalArgumentException("selectedRentalItemIds size is invalid");
    }
    LinkedHashSet<UUID> unique = new LinkedHashSet<>(values);
    if (unique.size() != values.size() || unique.contains(null)) {
      throw new IllegalArgumentException("selectedRentalItemIds must be unique");
    }
    return List.copyOf(unique);
  }

  private static OrderProblemException notFound(String message) {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "PRESENTATION_BOOKING_NOT_FOUND", message);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  public record BookingContext(
      PresentationBooking booking,
      ClientPresentation presentation,
      RentalInquiry inquiry,
      List<UUID> selectedRentalItemIds) {}
}
