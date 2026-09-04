package dev.buhanzaz.rwms.logistics.inquiry.service;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.ConfirmClientPresentationRequest;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.PresentationCabinSelectionInput;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.PresentationEquipmentSelectionInput;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentation;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentationMode;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBooking;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.inquiry.eventing.RentalInquiryBookedOutboxStore;
import dev.buhanzaz.rwms.logistics.inquiry.repository.ClientPresentationRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.PresentationBookingRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalInquiryRepository;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.AdditionalContactInput;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.DesiredDeliveryWindowInput;
import dev.buhanzaz.rwms.logistics.order.domain.AdditionalContact;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Coordinates local booking persistence, idempotency and short recovery transactions. Claims are
 * committed before remote work starts, and every later local mutation re-locks the exact lease
 * capability so an expired worker cannot overwrite its successor.
 */
@Service
@RequiredArgsConstructor
public class PresentationBookingStore {
  private static final int RECOVERY_BATCH_SIZE = 50;
  private static final Duration RECOVERY_LEASE_DURATION = Duration.ofMinutes(5);

  private final PresentationBookingRepository bookings;
  private final ClientPresentationRepository presentations;
  private final RentalInquiryRepository inquiries;
  private final ClientPresentationService presentationService;
  private final ClientDeliveryDatePolicy deliveryDatePolicy;
  private final RentalInquiryBookedOutboxStore outbox;
  private final ObjectMapper json;

  @Transactional
  public PresentationBooking begin(
      String token, UUID idempotencyKey, ConfirmClientPresentationRequest request) {
    ClientPresentation resolved = presentationService.resolveForBookingStatus(token);
    ClientPresentation presentation =
        presentations
            .findForUpdate(resolved.getId())
            .orElseThrow(() -> notFound("Представление не найдено"));
    OffsetDateTime timestamp = now();
    if (presentation.getRevision() != resolved.getRevision()
        || !presentation.getViewUntil().isAfter(timestamp)) {
      throw new OrderProblemException(
          HttpStatus.GONE, "CLIENT_PRESENTATION_GONE", "Срок действия представления истёк");
    }
    List<PresentationCabinSelectionInput> selections = normalized(request.selections());
    List<DesiredDeliveryWindowInput> desiredDeliveryWindows =
        normalizedWindows(request.desiredDeliveryWindows());
    Long rentalMonths = request.rentalMonths();
    String deliveryAddress = request.deliveryAddress();
    BigDecimal latitude = request.latitude();
    BigDecimal longitude = request.longitude();
    List<AdditionalContactInput> additionalContacts =
        normalizedAdditionalContacts(request.additionalContacts());
    if (presentation.getMode() == ClientPresentationMode.NORMAL) {
      desiredDeliveryWindows = requiredNormalWindows(desiredDeliveryWindows);
      rentalMonths = requiredRentalTerms(selections, rentalMonths);
      deliveryAddress = requiredNormalDeliveryAddress(deliveryAddress);
      requireCoordinatePair(latitude, longitude);
      latitude = normalizedDecimal(latitude);
      longitude = normalizedDecimal(longitude);
    } else if (hasReplacementOnlyConfirmationFields(request)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "REPLACEMENT_CLIENT_CONFIRMATION_FIELDS_FORBIDDEN",
          "При замене сохраняются условия заказа без изменения даты, срока и адреса");
    } else {
      desiredDeliveryWindows = List.of();
      rentalMonths = null;
      deliveryAddress = null;
      latitude = null;
      longitude = null;
      additionalContacts = List.of();
    }
    List<UUID> selected =
        selections.stream().map(PresentationCabinSelectionInput::rentalItemId).toList();
    List<UUID> available = presentationService.currentCabinIds(presentation);
    if (!new LinkedHashSet<>(available).containsAll(selected)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_SELECTION_INVALID",
          "Выбраны бытовки, которых нет в представлении");
    }
    if (presentation.getMode() == ClientPresentationMode.REPLACEMENT
        && selected.size() != presentationService.replacementUnitIds(presentation).size()) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_SELECTION_COUNT_INVALID",
          "Количество выбранных бытовок должно соответствовать количеству заменяемых");
    }
    if (presentation.getMode() == ClientPresentationMode.REPLACEMENT
        && selections.stream().anyMatch(selection -> !selection.equipment().isEmpty())) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "REPLACEMENT_EQUIPMENT_EDIT_FORBIDDEN",
          "При замене мебель переносится без изменения количества");
    }
    String selectedJson = write(selections);
    String desiredWindowsJson = write(desiredDeliveryWindows);
    String additionalContactsJson =
        presentation.getMode() == ClientPresentationMode.NORMAL ? write(additionalContacts) : null;
    PresentationBooking existing =
        bookings
            .findByPresentationIdAndPresentationRevision(
                presentation.getId(), presentation.getRevision())
            .orElse(null);
    if (existing != null) {
      boolean legacyDeliveryReceipt = legacyDeliveryReceipt(existing);
      if (!existing.getIdempotencyKey().equals(idempotencyKey)
          || !selected(existing.getSelectedItemIdsJson()).equals(selections)
          || !desiredWindows(existing.getDesiredDeliveryWindowsJson())
              .equals(desiredDeliveryWindows)
          || !Objects.equals(existing.getRentalMonths(), rentalMonths)
          || (!legacyDeliveryReceipt
              && (!Objects.equals(existing.getDeliveryAddress(), deliveryAddress)
                  || !equalDecimal(existing.getLatitude(), latitude)
                  || !equalDecimal(existing.getLongitude(), longitude)
                  || !additionalContacts(existing.getAdditionalContactsJson())
                      .equals(additionalContacts)))) {
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
    if (presentation.getMode() == ClientPresentationMode.NORMAL
        && !deliveryDatePolicy.containsAll(
            presentation.getWarehouseId(),
            timestamp,
            desiredDeliveryWindows.stream()
                .map(DesiredDeliveryWindowInput::startDate)
                .toList())) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_DELIVERY_DATE_NOT_REQUESTABLE",
          "Выберите даты со второго по пятый день после оформления; доставка на завтра уже закрыта");
    }
    return bookings.saveAndFlush(
        PresentationBooking.create(
            presentation.getId(),
            presentation.getRevision(),
            idempotencyKey,
            selectedJson,
            desiredWindowsJson,
            rentalMonths,
            deliveryAddress,
            latitude,
            longitude,
            additionalContactsJson,
            timestamp));
  }

  @Transactional(readOnly = true)
  public BookingContext context(UUID bookingId) {
    PresentationBooking booking =
        bookings.findById(bookingId).orElseThrow(() -> notFound("Бронирование не найдено"));
    return context(booking);
  }

  /**
   * Loads the immutable booking snapshot only while the supplied recovery capability is still
   * current. The transaction ends before the caller performs any remote effect.
   */
  @Transactional(readOnly = true)
  public Optional<BookingContext> claimedContext(RecoveryClaim claim) {
    RecoveryClaim required = requireClaim(claim);
    return bookings
        .findCurrentRecoveryClaim(required.bookingId(), required.leaseToken())
        .map(this::context);
  }

  private BookingContext context(PresentationBooking booking) {
    ClientPresentation presentation =
        presentations
            .findById(booking.getPresentationId())
            .orElseThrow(() -> notFound("Представление не найдено"));
    RentalInquiry inquiry =
        inquiries
            .findById(presentation.getInquiryId())
            .orElseThrow(() -> notFound("Диалог аренды не найден"));
    return new BookingContext(
        booking,
        presentation,
        inquiry,
        selected(booking.getSelectedItemIdsJson()),
        desiredWindows(booking.getDesiredDeliveryWindowsJson()),
        booking.getRentalMonths(),
        booking.getDeliveryAddress(),
        booking.getLatitude(),
        booking.getLongitude(),
        additionalContacts(booking.getAdditionalContactsJson()),
        legacyDesiredDeliveryTimes(booking.getDesiredDeliveryWindowsJson()));
  }

  /**
   * Claims an exact due booking for synchronous confirmation. An active lease, backoff or
   * quarantine deliberately returns empty so a replay cannot issue a duplicate remote effect.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<RecoveryClaim> claim(UUID bookingId) {
    UUID requiredId = Objects.requireNonNull(bookingId, "bookingId");
    return bookings.lockExactDueForRecovery(requiredId).map(this::claimLocked);
  }

  /**
   * Claims one stable due page with PostgreSQL SKIP LOCKED and commits every lease before any
   * remote processing starts.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<RecoveryClaim> claimDue() {
    List<PresentationBooking> due =
        bookings.lockDueForRecovery(PageRequest.of(0, RECOVERY_BATCH_SIZE));
    if (due.isEmpty()) return List.of();
    OffsetDateTime timestamp = databaseNow(due.getFirst().getId());
    OffsetDateTime leaseUntil = timestamp.plus(RECOVERY_LEASE_DURATION);
    List<RecoveryClaim> claims =
        due.stream()
            .map(booking -> claimLocked(booking, timestamp, leaseUntil))
            .toList();
    bookings.flush();
    return claims;
  }

  private RecoveryClaim claimLocked(PresentationBooking booking) {
    OffsetDateTime timestamp = databaseNow(booking.getId());
    RecoveryClaim claim =
        claimLocked(booking, timestamp, timestamp.plus(RECOVERY_LEASE_DURATION));
    bookings.flush();
    return claim;
  }

  private static RecoveryClaim claimLocked(
      PresentationBooking booking, OffsetDateTime timestamp, OffsetDateTime leaseUntil) {
    UUID leaseToken = UUID.randomUUID();
    booking.claimRecovery(leaseToken, leaseUntil, timestamp);
    return new RecoveryClaim(booking.getId(), leaseToken);
  }

  /** Atomically binds the order and presentation only for the worker holding the current lease. */
  @Transactional
  public boolean assignOrder(RecoveryClaim claim, UUID orderId) {
    RecoveryClaim required = requireClaim(claim);
    PresentationBooking booking =
        bookings
            .lockCurrentRecoveryClaim(required.bookingId(), required.leaseToken())
            .orElse(null);
    if (booking == null) return false;
    ClientPresentation presentation =
        presentations
            .findForUpdate(booking.getPresentationId())
            .orElseThrow(() -> notFound("Представление не найдено"));
    OffsetDateTime timestamp = databaseNow(booking.getId());
    booking.assignOrder(orderId, required.leaseToken(), timestamp);
    if (presentation.getState()
        == dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentationState.ACTIVE) {
      presentation.markBookingPending(orderId, timestamp);
    }
    bookings.saveAndFlush(booking);
    presentations.saveAndFlush(presentation);
    return true;
  }

  /** Records bounded retry state only for the worker still holding the exact lease capability. */
  @Transactional
  public boolean attempted(RecoveryClaim claim, String errorCode) {
    RecoveryClaim required = requireClaim(claim);
    PresentationBooking booking =
        bookings
            .lockCurrentRecoveryClaim(required.bookingId(), required.leaseToken())
            .orElse(null);
    if (booking == null) return false;
    booking.recoveryFailed(required.leaseToken(), errorCode, databaseNow(booking.getId()));
    bookings.saveAndFlush(booking);
    return true;
  }

  /** Rejects the booking only while the supplied recovery lease is current. */
  @Transactional
  public boolean reject(RecoveryClaim claim, String errorCode) {
    RecoveryClaim required = requireClaim(claim);
    PresentationBooking booking =
        bookings
            .lockCurrentRecoveryClaim(required.bookingId(), required.leaseToken())
            .orElse(null);
    if (booking == null) return false;
    ClientPresentation presentation =
        presentations
            .findForUpdate(booking.getPresentationId())
            .orElseThrow(() -> notFound("Представление не найдено"));
    OffsetDateTime timestamp = databaseNow(booking.getId());
    booking.reject(required.leaseToken(), errorCode, timestamp);
    presentation.revoke(timestamp);
    bookings.saveAndFlush(booking);
    presentations.saveAndFlush(presentation);
    return true;
  }

  /** Completes the booking and its local aggregates only for the exact current lease owner. */
  @Transactional
  public boolean complete(RecoveryClaim claim) {
    RecoveryClaim required = requireClaim(claim);
    PresentationBooking booking =
        bookings
            .lockCurrentRecoveryClaim(required.bookingId(), required.leaseToken())
            .orElse(null);
    if (booking == null) return false;
    ClientPresentation presentation =
        presentations
            .findForUpdate(booking.getPresentationId())
            .orElseThrow(() -> notFound("Представление не найдено"));
    RentalInquiry inquiry =
        inquiries
            .findForUpdate(presentation.getInquiryId())
            .orElseThrow(() -> notFound("Диалог аренды не найден"));
    OffsetDateTime timestamp = databaseNow(booking.getId());
    booking.complete(required.leaseToken(), timestamp);
    presentation.markBooked(booking.getOrderId(), timestamp);
    inquiry.markBooked(booking.getOrderId(), timestamp);
    bookings.saveAndFlush(booking);
    presentations.saveAndFlush(presentation);
    inquiries.saveAndFlush(inquiry);
    if (inquiry.getConversationId() != null) {
      outbox.append(
          inquiry.getId(),
          inquiry.getVersion(),
          inquiry.getConversationId(),
          booking.getId(),
          booking.getOrderId(),
          inquiry.getManagerId(),
          timestamp);
    }
    return true;
  }

  private OffsetDateTime databaseNow(UUID bookingId) {
    return bookings
        .currentDatabaseTimestamp(bookingId)
        .orElseThrow(() -> new IllegalStateException("Claimed presentation booking is missing"));
  }

  private static RecoveryClaim requireClaim(RecoveryClaim claim) {
    return Objects.requireNonNull(claim, "claim");
  }

  private String write(List<?> values) {
    try {
      return json.writeValueAsString(values);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Booking receipt values cannot be serialized", exception);
    }
  }

  private List<PresentationCabinSelectionInput> selected(String value) {
    try {
      var tree = json.readTree(value);
      List<PresentationCabinSelectionInput> result = new ArrayList<>();
      for (var item : tree) {
        if (item.isString()) {
          result.add(
              new PresentationCabinSelectionInput(
                  UUID.fromString(item.stringValue()), List.of(), null));
        } else {
          result.add(json.treeToValue(item, PresentationCabinSelectionInput.class));
        }
      }
      return List.copyOf(result);
    } catch (JacksonException | IllegalArgumentException exception) {
      throw new IllegalStateException("Stored booking selection is corrupt", exception);
    }
  }

  private List<DesiredDeliveryWindowInput> desiredWindows(String value) {
    try {
      var tree = json.readTree(value);
      if (!tree.isArray()) {
        throw new IllegalArgumentException("Stored desired delivery windows are not an array");
      }
      List<DesiredDeliveryWindowInput> result = new ArrayList<>();
      for (var item : tree) {
        var startDate = item.get("startDate");
        var endDate = item.get("endDate");
        if (startDate == null || endDate == null || !startDate.isString() || !endDate.isString()) {
          throw new IllegalArgumentException("Stored desired delivery window is invalid");
        }
        result.add(
            new DesiredDeliveryWindowInput(
                LocalDate.parse(startDate.stringValue()), LocalDate.parse(endDate.stringValue())));
      }
      return normalizedWindows(result);
    } catch (JacksonException | IllegalArgumentException exception) {
      throw new IllegalStateException("Stored desired delivery windows are corrupt", exception);
    }
  }

  private List<AdditionalContactInput> additionalContacts(String value) {
    if (value == null || value.isBlank()) return List.of();
    try {
      return normalizedAdditionalContacts(
          json.readValue(value, new TypeReference<List<AdditionalContactInput>>() {}));
    } catch (JacksonException | IllegalArgumentException exception) {
      throw new IllegalStateException("Stored booking additional contacts are corrupt", exception);
    }
  }

  /**
   * Reads optional legacy time strings only to accept an existing pre-date-only local command
   * receipt. They never reach a public response or a new order preference.
   */
  private List<String> legacyDesiredDeliveryTimes(String value) {
    try {
      var tree = json.readTree(value);
      if (!tree.isArray() || tree.size() != 1) return List.of();
      var window = tree.get(0);
      var timeFrom = window.get("timeFrom");
      var timeTo = window.get("timeTo");
      if (timeFrom == null
          || timeTo == null
          || !timeFrom.isString()
          || !timeTo.isString()) {
        return List.of();
      }
      LocalTime from = LocalTime.parse(timeFrom.stringValue());
      LocalTime to = LocalTime.parse(timeTo.stringValue());
      return from.isBefore(to) ? List.of(from.toString(), to.toString()) : List.of();
    } catch (JacksonException | IllegalArgumentException exception) {
      throw new IllegalStateException("Stored desired delivery windows are corrupt", exception);
    }
  }

  private static List<PresentationCabinSelectionInput> normalized(
      List<PresentationCabinSelectionInput> values) {
    if (values == null || values.isEmpty() || values.size() > 100) {
      throw new IllegalArgumentException("Presentation selections size is invalid");
    }
    LinkedHashSet<UUID> unitIds = new LinkedHashSet<>();
    List<PresentationCabinSelectionInput> selections = new ArrayList<>(values.size());
    for (PresentationCabinSelectionInput value : values) {
      if (value == null
          || value.rentalItemId() == null
          || !unitIds.add(value.rentalItemId())
          || value.equipment() == null) {
        throw new IllegalArgumentException("Presentation cabin selections must be unique");
      }
      LinkedHashSet<UUID> equipmentIds = new LinkedHashSet<>();
      List<PresentationEquipmentSelectionInput> equipment =
          value.equipment().stream()
              .peek(
                  item -> {
                    if (item == null
                        || item.equipmentId() == null
                        || item.quantity() == null
                        || item.quantity() < 1
                        || !equipmentIds.add(item.equipmentId())) {
                      throw new IllegalArgumentException(
                          "Presentation equipment selections are invalid");
                    }
                  })
              .sorted(Comparator.comparing(PresentationEquipmentSelectionInput::equipmentId))
              .toList();
      if (value.rentalMonths() != null
          && (value.rentalMonths() < 1 || value.rentalMonths() > 120)) {
        throw new IllegalArgumentException("Presentation rental term is invalid");
      }
      selections.add(
          new PresentationCabinSelectionInput(
              value.rentalItemId(), equipment, value.rentalMonths()));
    }
    return List.copyOf(selections);
  }

  private static List<DesiredDeliveryWindowInput> normalizedWindows(
      List<DesiredDeliveryWindowInput> values) {
    if (values == null || values.isEmpty()) return List.of();
    for (DesiredDeliveryWindowInput value : values) {
      if (value == null
          || value.startDate() == null
          || value.endDate() == null
          || !value.hasOrderedDates()) {
        throw new IllegalArgumentException("Desired delivery window is invalid");
      }
    }
    return List.copyOf(values);
  }

  /**
   * Validates and chronologically orders the independently selected client receiving days kept in
   * a normal-presentation receipt.
   */
  private static List<DesiredDeliveryWindowInput> requiredNormalWindows(
      List<DesiredDeliveryWindowInput> values) {
    if (values.isEmpty()) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_DELIVERY_WINDOW_REQUIRED",
          "Выберите хотя бы одну желаемую дату получения бытовок");
    }
    if (values.size() > 4) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_DELIVERY_WINDOW_INVALID",
          "В представлении можно выбрать не более четырёх желаемых дат получения бытовок");
    }
    LinkedHashSet<LocalDate> days = new LinkedHashSet<>();
    for (DesiredDeliveryWindowInput window : values) {
      if (!window.startDate().equals(window.endDate())) {
        throw new OrderProblemException(
            HttpStatus.CONFLICT,
            "CLIENT_PRESENTATION_DELIVERY_WINDOW_INVALID",
            "В представлении можно выбрать только отдельные календарные дни");
      }
      if (!days.add(window.startDate())) {
        throw new OrderProblemException(
            HttpStatus.CONFLICT,
            "CLIENT_PRESENTATION_DELIVERY_WINDOW_INVALID",
            "Одна и та же желаемая дата получения бытовок указана несколько раз");
      }
    }
    return values.stream().sorted(Comparator.comparing(DesiredDeliveryWindowInput::startDate)).toList();
  }

  private static Long requiredRentalTerms(
      List<PresentationCabinSelectionInput> selections, Long uniformRentalMonths) {
    if (uniformRentalMonths != null
        && (uniformRentalMonths < 1 || uniformRentalMonths > 120)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_RENTAL_MONTHS_INVALID",
          "Срок аренды должен быть от 1 до 120 месяцев");
    }
    boolean everySelectionHasTerm =
        selections.stream().allMatch(selection -> selection.rentalMonths() != null);
    boolean noSelectionHasTerm =
        selections.stream().noneMatch(selection -> selection.rentalMonths() != null);
    if ((everySelectionHasTerm && uniformRentalMonths != null)
        || (!everySelectionHasTerm && !noSelectionHasTerm)
        || (uniformRentalMonths == null && !everySelectionHasTerm)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_RENTAL_MONTHS_REQUIRED",
          "Укажите срок аренды для каждой бытовки");
    }
    return uniformRentalMonths;
  }

  private static String requiredNormalDeliveryAddress(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > 1_000) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_DELIVERY_ADDRESS_REQUIRED",
          "Укажите адрес доставки бытовок");
    }
    return normalized;
  }

  private static void requireCoordinatePair(BigDecimal latitude, BigDecimal longitude) {
    if ((latitude == null) != (longitude == null)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_COORDINATES_INVALID",
          "Широта и долгота указываются вместе");
    }
    if (latitude != null
        && (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
            || latitude.compareTo(BigDecimal.valueOf(90)) > 0
            || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
            || longitude.compareTo(BigDecimal.valueOf(180)) > 0)) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CLIENT_PRESENTATION_COORDINATES_INVALID",
          "Координаты доставки некорректны");
    }
  }

  private static List<AdditionalContactInput> normalizedAdditionalContacts(
      List<AdditionalContactInput> values) {
    if (values == null || values.isEmpty()) return List.of();
    List<AdditionalContactInput> result = new ArrayList<>(values.size());
    for (AdditionalContactInput value : values) {
      if (value == null) {
        throw new IllegalArgumentException("Additional contacts are invalid");
      }
      AdditionalContact normalized = AdditionalContact.create(value.name(), value.phone());
      result.add(new AdditionalContactInput(normalized.getName(), normalized.getPhone()));
    }
    return List.copyOf(result);
  }

  private static boolean hasReplacementOnlyConfirmationFields(
      ConfirmClientPresentationRequest request) {
    return request.desiredDeliveryWindows() != null
        || request.rentalMonths() != null
        || request.deliveryAddress() != null
        || request.latitude() != null
        || request.longitude() != null
        || request.additionalContacts() != null;
  }

  private static boolean equalDecimal(BigDecimal left, BigDecimal right) {
    return left == null ? right == null : right != null && left.compareTo(right) == 0;
  }

  /**
   * Identifies a V48 or older receipt which has a retired client time payload but no V49 delivery
   * snapshot. Its selection, date and term stay fenced; only the unavailable snapshot is skipped
   * so reconciliation can preserve the order facts it never recorded.
   */
  private boolean legacyDeliveryReceipt(PresentationBooking booking) {
    return booking.getDeliveryAddress() == null
        && booking.getLatitude() == null
        && booking.getLongitude() == null
        && booking.getAdditionalContactsJson() == null
        && !legacyDesiredDeliveryTimes(booking.getDesiredDeliveryWindowsJson()).isEmpty();
  }

  private static BigDecimal normalizedDecimal(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros();
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
      List<PresentationCabinSelectionInput> selections,
      List<DesiredDeliveryWindowInput> desiredDeliveryWindows,
      Long rentalMonths,
      String deliveryAddress,
      BigDecimal latitude,
      BigDecimal longitude,
      List<AdditionalContactInput> additionalContacts,
      List<String> legacyDesiredDeliveryTimes) {
    public List<UUID> selectedRentalItemIds() {
      return selections.stream().map(PresentationCabinSelectionInput::rentalItemId).toList();
    }
  }

  /** Immutable, payload-free capability for one exact presentation-booking recovery lease. */
  public record RecoveryClaim(UUID bookingId, UUID leaseToken) {
    /** Rejects incomplete capabilities before they can reach a recovery workflow. */
    public RecoveryClaim {
      Objects.requireNonNull(bookingId, "bookingId");
      Objects.requireNonNull(leaseToken, "leaseToken");
    }
  }
}
