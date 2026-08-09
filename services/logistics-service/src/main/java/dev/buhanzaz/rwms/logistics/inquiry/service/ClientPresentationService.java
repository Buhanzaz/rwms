package dev.buhanzaz.rwms.logistics.inquiry.service;

import static dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.*;

import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentation;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentationItem;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentationState;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiryState;
import dev.buhanzaz.rwms.logistics.inquiry.repository.ClientPresentationItemRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.ClientPresentationRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.PresentationBookingRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalInquiryRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns client presentation snapshots derived from a rental inquiry and its available rental items.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClientPresentationService {
  private static final Set<String> OBSOLETE_CABIN_IMPORT_FIELDS =
      Set.of(
          "locationnodeid",
          "hasphotos",
          "photocount",
          "mainphotourl",
          "previewphotourls");

  private final ClientPresentationRepository presentations;
  private final ClientPresentationItemRepository items;
  private final PresentationBookingRepository bookings;
  private final RentalInquiryRepository inquiries;
  private final RentalSettingsService settings;
  private final RentalInquiryService inquiryService;
  private final ClientPresentationTokenService tokens;
  private final LogisticsDependencyGateway dependencies;
  private final OrderAuthorizer access;
  private final ObjectMapper json;

  @Transactional
  public ClientPresentationResponse publish(
      OrderActor actor,
      UUID inquiryId,
      UUID idempotencyKey,
      PublishClientPresentationRequest request) {
    RentalInquiry inquiry =
        inquiries
            .findForUpdate(inquiryId)
            .orElseThrow(() -> notFound("Диалог аренды не найден"));
    RentalInquiryService.requireOwner(actor, inquiry);
    if (inquiry.getState() != RentalInquiryState.ACTIVE) {
      throw conflict("INQUIRY_ARCHIVED", "Диалог уже завершён");
    }
    access.requireWarehouseEdit(actor, request.warehouseId());
    inquiry.selectWarehouse(request.warehouseId(), now());
    List<GroupSelection> selections = selections(request);
    List<UUID> cabinIds =
        selections.stream().flatMap(group -> group.rentalItemIds().stream()).toList();
    String requestHash = hash(request);
    OffsetDateTime timestamp = now();
    int holdMinutes = settings.presentationHoldMinutes(actor);
    OffsetDateTime expiresAt = timestamp.plusMinutes(holdMinutes);
    OffsetDateTime viewUntil = expiresAt.plusHours(24);

    ClientPresentation presentation =
        presentations.findByInquiryIdForUpdate(inquiryId).orElse(null);
    if (presentation != null && presentation.hasPublishKey(idempotencyKey)) {
      if (!presentation.matchesPublish(idempotencyKey, requestHash)) {
        throw conflict(
            "IDEMPOTENCY_KEY_REUSED",
            "Idempotency-Key уже использован для другого представления");
      }
      return response(presentation);
    }
    if (presentation != null
        && bookings.existsByPresentationIdAndPresentationRevision(
            presentation.getId(), presentation.getRevision())) {
      throw conflict(
          "PRESENTATION_BOOKING_STARTED",
          "Клиент уже отправил выбор из текущего представления");
    }
    if (presentation == null) {
      presentation =
          presentations.saveAndFlush(
              ClientPresentation.create(
                  inquiryId,
                  request.warehouseId(),
                  expiresAt,
                  viewUntil,
                  idempotencyKey,
                  requestHash,
                  timestamp));
    } else {
      presentation.replace(
          request.warehouseId(),
          expiresAt,
          viewUntil,
          idempotencyKey,
          requestHash,
          timestamp);
      presentation = presentations.saveAndFlush(presentation);
    }

    try {
      List<LogisticsDependencyGateway.AvailableCabin> cabinSnapshots =
          dependencies.readCabinSnapshots(request.warehouseId(), cabinIds);
      Map<UUID, LogisticsDependencyGateway.AvailableCabin> cabins =
          cabinSnapshots.stream()
              .collect(
                  Collectors.toMap(
                      LogisticsDependencyGateway.AvailableCabin::id,
                      Function.identity(),
                      (left, right) -> left,
                      LinkedHashMap::new));
      if (!cabins.keySet().equals(new LinkedHashSet<>(cabinIds))) {
        throw conflict(
            "PRESENTATION_CABIN_MISMATCH",
            "Не удалось подтвердить выбранные бытовки");
      }
      Map<UUID, List<LogisticsDependencyGateway.CabinMediaPhoto>> media =
          dependencies
              .readCabinMediaSnapshots(request.warehouseId(), cabinIds)
              .stream()
              .collect(
                  Collectors.toMap(
                      LogisticsDependencyGateway.CabinMediaSnapshot::cabinId,
                      LogisticsDependencyGateway.CabinMediaSnapshot::photos,
                      (left, right) -> left,
                      LinkedHashMap::new));
      UUID holdScopeId = inquiry.getId();
      UUID holdKey =
          deterministic(
              "presentation-holds:"
                  + holdScopeId
                  + ":"
                  + presentation.getRevision()
                  + ":"
                  + idempotencyKey);
      LogisticsDependencyGateway.PresentationHolds held =
          dependencies.replacePresentationHolds(
              holdKey,
              holdScopeId,
              request.warehouseId(),
              cabinIds,
              expiresAt,
              actor.subjectId(),
              actor.role(),
              request.manualBookingDraftId());
      if (!holdScopeId.equals(held.presentationId())
          || held.holds().size() != cabinIds.size()
          || !Set.copyOf(
                  held.holds().stream()
                      .map(LogisticsDependencyGateway.PresentationHold::rentalItemId)
                      .toList())
              .equals(Set.copyOf(cabinIds))) {
        throw conflict(
            "PRESENTATION_HOLD_MISMATCH",
            "Не удалось временно зарезервировать все бытовки");
      }
      dependencies.releasePresentationHolds(
          deterministic(
              "legacy-presentation-holds-release:"
                  + presentation.getId()
                  + ":"
                  + presentation.getRevision()
                  + ":"
                  + idempotencyKey),
          presentation.getId(),
          actor.subjectId(),
          actor.role());

      int sortOrder = 0;
      List<ClientPresentationItem> savedItems = new ArrayList<>(cabinIds.size());
      for (GroupSelection selection : selections) {
        for (UUID cabinId : selection.rentalItemIds()) {
          savedItems.add(
              ClientPresentationItem.create(
                  presentation.getId(),
                  presentation.getRevision(),
                  cabinId,
                  selection.key(),
                  selection.label(),
                  sortOrder++,
                  write(cabins.get(cabinId)),
                  write(media.getOrDefault(cabinId, List.of())),
                  timestamp));
        }
      }
      items.saveAllAndFlush(savedItems);
      inquiries.saveAndFlush(inquiry);
      return response(presentation);
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception);
    }
  }

  public ClientPresentationResponse get(OrderActor actor, UUID inquiryId) {
    RentalInquiry inquiry = inquiryService.requiredOwned(actor, inquiryId);
    ClientPresentation presentation =
        presentations
            .findByInquiryId(inquiry.getId())
            .orElseThrow(() -> notFound("Представление для клиента ещё не создано"));
    return response(presentation);
  }

  @Transactional
  public void revoke(
      OrderActor actor, UUID inquiryId, UUID idempotencyKey) {
    RentalInquiry inquiry =
        inquiries
            .findForUpdate(inquiryId)
            .orElseThrow(() -> notFound("Диалог аренды не найден"));
    RentalInquiryService.requireOwner(actor, inquiry);
    ClientPresentation presentation =
        presentations
            .findByInquiryIdForUpdate(inquiryId)
            .orElseThrow(() -> notFound("Представление для клиента не найдено"));
    if (presentation.getState() == ClientPresentationState.REVOKED) return;
    if (bookings.existsByPresentationIdAndPresentationRevision(
        presentation.getId(), presentation.getRevision())) {
      throw conflict(
          "PRESENTATION_BOOKING_STARTED",
          "Клиент уже отправил выбор из текущего представления");
    }
    try {
      dependencies.releasePresentationHolds(
          deterministic("inquiry-holds-revoke:" + inquiryId + ":" + idempotencyKey),
          inquiryId,
          actor.subjectId(),
          actor.role());
      dependencies.releasePresentationHolds(
          deterministic(
              "legacy-presentation-holds-revoke:"
                  + presentation.getId()
                  + ":"
                  + idempotencyKey),
          presentation.getId(),
          actor.subjectId(),
          actor.role());
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception);
    }
    presentation.revoke(now());
    presentations.saveAndFlush(presentation);
  }

  public PublicClientPresentationResponse publicPresentation(String token) {
    ClientPresentation presentation = resolve(token);
    ClientPresentationResponse internal = response(presentation, token);
    return new PublicClientPresentationResponse(
        internal.id(),
        internal.revision(),
        internal.state(),
        internal.expiresAt(),
        internal.viewUntil(),
        !internal.canConfirm(),
        internal.groups(),
        internal.bookedOrderId());
  }

  public LogisticsDependencyGateway.MediaContent media(
      String token,
      UUID cabinId,
      UUID mediaId,
      long generation,
      String variant) {
    ClientPresentation presentation = resolve(token);
    if (!Set.of("SMALL", "LARGE").contains(variant)) {
      throw notFound("Фотография не найдена");
    }
    String actualToken = tokens.issue(presentation.getId(), presentation.getRevision());
    boolean found =
        response(presentation, actualToken).groups().stream()
            .flatMap(group -> group.cabins().stream())
            .filter(cabin -> cabin.id().equals(cabinId))
            .flatMap(cabin -> cabin.photos().stream())
            .anyMatch(
                photo ->
                    photo.mediaId().equals(mediaId)
                        && photo.generation() == generation
                        && photo.availableVariants().contains(variant));
    if (!found) throw notFound("Фотография не найдена");
    try {
      return dependencies.readCabinPresentationMedia(
          presentation.getWarehouseId(),
          cabinId,
          mediaId,
          generation,
          variant);
    } catch (LogisticsDependencyException exception) {
      if (exception.kind()
          == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        throw notFound("Фотография не найдена");
      }
      throw dependencyProblem(exception);
    }
  }

  public ClientPresentation resolve(String token) {
    ClientPresentation presentation = resolveToken(token);
    if (!presentation.isViewable(now())) {
      throw gone();
    }
    return presentation;
  }

  public ClientPresentation resolveForBookingStatus(String token) {
    ClientPresentation presentation = resolveToken(token);
    if (!presentation.getViewUntil().isAfter(now())) {
      throw gone();
    }
    return presentation;
  }

  private ClientPresentation resolveToken(String token) {
    ClientPresentationTokenService.TokenIdentity identity;
    try {
      identity = tokens.verify(token);
    } catch (ClientPresentationTokenService.InvalidPresentationTokenException exception) {
      throw notFound("Представление для клиента не найдено");
    }
    ClientPresentation presentation =
        presentations
            .findById(identity.presentationId())
            .orElseThrow(() -> notFound("Представление для клиента не найдено"));
    if (presentation.getRevision() != identity.revision()) {
      throw gone();
    }
    return presentation;
  }

  public List<UUID> currentCabinIds(ClientPresentation presentation) {
    return items
        .findAllByPresentationIdAndPresentationRevisionOrderBySortOrderAscIdAsc(
            presentation.getId(), presentation.getRevision())
        .stream()
        .map(ClientPresentationItem::getRentalItemId)
        .toList();
  }

  private ClientPresentationResponse response(ClientPresentation presentation) {
    return response(
        presentation, tokens.issue(presentation.getId(), presentation.getRevision()));
  }

  private ClientPresentationResponse response(
      ClientPresentation presentation, String token) {
    List<ClientPresentationItem> current =
        items.findAllByPresentationIdAndPresentationRevisionOrderBySortOrderAscIdAsc(
            presentation.getId(), presentation.getRevision());
    Map<String, MutableGroup> groups = new LinkedHashMap<>();
    for (ClientPresentationItem item : current) {
      LogisticsDependencyGateway.AvailableCabin cabin =
          read(item.getCabinSnapshotJson(), LogisticsDependencyGateway.AvailableCabin.class);
      List<LogisticsDependencyGateway.CabinMediaPhoto> photos =
          read(
              item.getMediaSnapshotJson(),
              new TypeReference<List<LogisticsDependencyGateway.CabinMediaPhoto>>() {});
      List<PresentationPhoto> publicPhotos =
          photos.stream()
              .sorted(
                  Comparator.comparingInt(
                          LogisticsDependencyGateway.CabinMediaPhoto::sortOrder)
                      .thenComparing(value -> value.mediaId().toString()))
              .map(photo -> photo(presentation, token, cabin.id(), photo))
              .toList();
      PresentationCabin value =
          new PresentationCabin(
              cabin.id(),
              cabin.number(),
              cabin.rentalType(),
              cabin.dimensions(),
              cabin.finishing(),
              cabin.category(),
              cabin.characteristics(),
              cabin.linoleum(),
              publicPassport(cabin.passport()),
              cabin.tags(),
              publicPhotos);
      groups
          .computeIfAbsent(
              item.getGroupKey(),
              ignored -> new MutableGroup(item.getGroupKey(), item.getGroupLabel()))
          .cabins()
          .add(value);
    }
    OffsetDateTime timestamp = now();
    return new ClientPresentationResponse(
        presentation.getId(),
        presentation.getVersion(),
        presentation.getRevision(),
        presentation.getInquiryId(),
        presentation.getWarehouseId(),
        presentation.getState().name(),
        presentation.getExpiresAt(),
        presentation.getViewUntil(),
        presentation.canConfirm(timestamp),
        "/offer/" + token,
        presentation.getBookedOrderId(),
        groups.values().stream()
            .map(group -> new PresentationGroup(group.key(), group.label(), List.copyOf(group.cabins())))
            .toList());
  }

  private static PresentationPhoto photo(
      ClientPresentation presentation,
      String token,
      UUID cabinId,
      LogisticsDependencyGateway.CabinMediaPhoto photo) {
    String base =
        "/api/logistics/public/v1/client-presentations/"
            + token
            + "/media/"
            + cabinId
            + "/"
            + photo.mediaId()
            + "/"
            + photo.generation()
            + "/";
    String large =
        photo.availableVariants().contains("LARGE") ? "LARGE" : "SMALL";
    String small =
        photo.availableVariants().contains("SMALL") ? "SMALL" : large;
    return new PresentationPhoto(
        photo.mediaId(),
        photo.generation(),
        photo.sortOrder(),
        photo.availableVariants(),
        base + small,
        base + large);
  }

  static Map<String, Object> publicPassport(Map<String, Object> source) {
    if (source == null || source.isEmpty()) return Map.of();
    Map<String, Object> sanitized = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          if (isPublicPassportKey(key, value, true)) {
            Object publicValue = publicPassportValue(value);
            if (publicValue != null) sanitized.put(key, publicValue);
          }
        });
    return Map.copyOf(sanitized);
  }

  private static Object publicPassportValue(Object value) {
    if (value instanceof Map<?, ?> nested) {
      Map<String, Object> typed = new LinkedHashMap<>();
      nested.forEach(
          (key, child) -> {
            if (key instanceof String text
                && isPublicPassportKey(text, child, false)) {
              Object publicValue = publicPassportValue(child);
              if (publicValue != null) typed.put(text, publicValue);
            }
          });
      return Map.copyOf(typed);
    }
    if (value instanceof List<?> values) {
      return values.stream().map(ClientPresentationService::publicPassportValue).toList();
    }
    return value;
  }

  private static boolean isPublicPassportKey(String key, Object value, boolean topLevel) {
    return key != null
        && !(key.equalsIgnoreCase("source")
            && (topLevel || Objects.equals(value, "old-panel-rental-items-v1")))
        && !key.matches("(?i)^legacy.*")
        && !OBSOLETE_CABIN_IMPORT_FIELDS.contains(key.toLowerCase(java.util.Locale.ROOT))
        && !key.matches("(?i).*?(author|audit|action|created|updated|version|internal).*");
  }

  private static List<GroupSelection> selections(
      PublishClientPresentationRequest request) {
    LinkedHashSet<String> keys = new LinkedHashSet<>();
    LinkedHashSet<UUID> cabinIds = new LinkedHashSet<>();
    List<GroupSelection> groups = new ArrayList<>();
    for (PresentationGroupInput group : request.groups()) {
      String key = group.key().trim();
      if (!keys.add(key)) {
        throw new IllegalArgumentException("Presentation group keys must be unique");
      }
      List<UUID> ids = new ArrayList<>();
      for (UUID id : group.rentalItemIds()) {
        if (id == null || !cabinIds.add(id)) {
          throw new IllegalArgumentException(
              "A cabin can occur only once in a presentation");
        }
        ids.add(id);
      }
      groups.add(new GroupSelection(key, group.label().trim(), List.copyOf(ids)));
    }
    if (cabinIds.size() > 100) {
      throw new IllegalArgumentException("Presentation may contain at most 100 cabins");
    }
    return List.copyOf(groups);
  }

  private String hash(Object value) {
    try {
      return LogisticsEventStore.sha256(json.writeValueAsBytes(value));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Presentation request cannot be fingerprinted", exception);
    }
  }

  private static UUID deterministic(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Presentation snapshot cannot be serialized", exception);
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored presentation snapshot is corrupt", exception);
    }
  }

  private <T> T read(String value, TypeReference<T> type) {
    try {
      return json.readValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored presentation media snapshot is corrupt", exception);
    }
  }

  private static OrderProblemException dependencyProblem(
      LogisticsDependencyException exception) {
    if (exception.kind()
        == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      return conflict(
          exception.dependencyCode() == null
              ? "PRESENTATION_REJECTED"
              : exception.dependencyCode(),
          "Не удалось создать представление для клиента");
    }
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "PRESENTATION_DEPENDENCY_UNAVAILABLE",
        "Сервис представлений временно недоступен");
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }

  private static OrderProblemException notFound(String message) {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "CLIENT_PRESENTATION_NOT_FOUND", message);
  }

  private static OrderProblemException gone() {
    return new OrderProblemException(
        HttpStatus.GONE,
        "CLIENT_PRESENTATION_GONE",
        "Срок действия представления истёк");
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private record GroupSelection(String key, String label, List<UUID> rentalItemIds) {}

  private record MutableGroup(
      String key, String label, List<PresentationCabin> cabins) {
    private MutableGroup(String key, String label) {
      this(key, label, new ArrayList<>());
    }
  }
}
