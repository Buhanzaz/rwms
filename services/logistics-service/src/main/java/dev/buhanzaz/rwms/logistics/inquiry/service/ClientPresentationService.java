package dev.buhanzaz.rwms.logistics.inquiry.service;

import static dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.*;

import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentation;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentationItem;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentationMode;
import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentationState;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingState;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiryState;
import dev.buhanzaz.rwms.logistics.inquiry.repository.ClientPresentationItemRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.ClientPresentationRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.PresentationBookingRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalInquiryRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.DesiredDeliveryWindowResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderService;
import dev.buhanzaz.rwms.logistics.pricing.api.CabinRentalPricesResponse;
import dev.buhanzaz.rwms.logistics.pricing.service.RentalPricingService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
 * Owns versioned client presentations derived from an existing rental inquiry. Publishing stores
 * only cabin/media facts captured after asset atomically installs the holds; furniture availability
 * remains a live asset/order projection on every authenticated or public read.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClientPresentationService {
  private static final Set<String> OBSOLETE_CABIN_IMPORT_FIELDS =
      Set.of("locationnodeid", "hasphotos", "photocount", "mainphotourl", "previewphotourls");

  private final ClientPresentationRepository presentations;
  private final ClientPresentationItemRepository items;
  private final PresentationBookingRepository bookings;
  private final RentalInquiryRepository inquiries;
  private final RentalSettingsService settings;
  private final RentalInquiryService inquiryService;
  private final ClientPresentationTokenService tokens;
  private final LogisticsDependencyGateway dependencies;
  private final OrderAuthorizer access;
  private final RentalOrderService rentalOrders;
  private final ClientDeliveryDatePolicy deliveryDatePolicy;
  private final ObjectMapper json;
  private final RentalPricingService pricing;

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
    PresentationIntent intent = intent(actor, inquiry, request, cabinIds);
    String requestHash = hash(request);
    OffsetDateTime timestamp = now();
    int holdMinutes = settings.presentationHoldMinutes(actor);
    OffsetDateTime expiresAt = timestamp.plusMinutes(holdMinutes);
    OffsetDateTime viewUntil = expiresAt.plusHours(24);

    ClientPresentation presentation =
        presentations
            .findByInquiryIdForUpdate(inquiryId)
            .orElse(null);
    if (presentation != null && presentation.hasPublishKey(idempotencyKey)) {
      if (!presentation.matchesPublish(idempotencyKey, requestHash)) {
        throw conflict(
            "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key уже использован для другого представления");
      }
      return response(presentation);
    }
    boolean currentBookingBlocksRepublish =
        presentation != null
            && bookings
                .findByPresentationIdAndPresentationRevision(
                    presentation.getId(), presentation.getRevision())
                .filter(booking -> booking.getState() != PresentationBookingState.REJECTED)
                .isPresent();
    if (currentBookingBlocksRepublish) {
      throw conflict(
          "PRESENTATION_BOOKING_STARTED", "Клиент уже отправил выбор из текущего представления");
    }
    lockLinkedNormalOrderWarehouse(actor, inquiry, idempotencyKey, request, intent);
    if (presentation == null) {
      presentation =
          presentations.saveAndFlush(
              ClientPresentation.create(
                  inquiryId,
                  request.warehouseId(),
                  intent.mode(),
                  write(intent.replacementUnitIds()),
                  expiresAt,
                  viewUntil,
                  idempotencyKey,
                  requestHash,
                  timestamp));
    } else {
      presentation.replace(
          request.warehouseId(),
          intent.mode(),
          write(intent.replacementUnitIds()),
          expiresAt,
          viewUntil,
          idempotencyKey,
          requestHash,
          timestamp);
      presentation = presentations.saveAndFlush(presentation);
    }

    try {
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
            "PRESENTATION_HOLD_MISMATCH", "Не удалось временно зарезервировать все бытовки");
      }
      List<LogisticsDependencyGateway.AvailableCabin> heldCabins = held.cabins();
      if (heldCabins.size() != cabinIds.size()
          || !heldCabins.stream()
              .map(LogisticsDependencyGateway.AvailableCabin::id)
              .toList()
              .equals(cabinIds)
          || heldCabins.stream()
              .anyMatch(cabin -> !request.warehouseId().equals(cabin.warehouseId()))) {
        throw conflict("PRESENTATION_CABIN_MISMATCH", "Не удалось подтвердить выбранные бытовки");
      }
      Map<UUID, LogisticsDependencyGateway.AvailableCabin> cabins =
          heldCabins.stream()
              .collect(
                  Collectors.toMap(
                      LogisticsDependencyGateway.AvailableCabin::id,
                      Function.identity(),
                      (left, right) -> left,
                      LinkedHashMap::new));
      Map<UUID, List<LogisticsDependencyGateway.CabinMediaPhoto>> media =
          dependencies.readCabinMediaSnapshots(request.warehouseId(), cabinIds).stream()
              .collect(
                  Collectors.toMap(
                      LogisticsDependencyGateway.CabinMediaSnapshot::cabinId,
                      LogisticsDependencyGateway.CabinMediaSnapshot::photos,
                      (left, right) -> left,
                      LinkedHashMap::new));
      CabinRentalPricesResponse priceQuote = pricing.prices(request.warehouseId(), cabinIds);
      Map<UUID, CabinRentalPricesResponse.Price> priceById =
          priceQuote.cabins().stream()
              .collect(
                  Collectors.toMap(
                      CabinRentalPricesResponse.Price::rentalItemId, Function.identity()));
      if (heldCabins.stream()
          .anyMatch(cabin -> priceById.get(cabin.id()).rentalItemVersion() != cabin.version())) {
        throw conflict(
            "CABIN_VERSION_CONFLICT", "Бытовка изменилась при фиксации цены; повторите публикацию");
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
                  priceQuote.pricingVersion(),
                  priceById.get(cabinId).monthlyPriceRubles(),
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
  public void revoke(OrderActor actor, UUID inquiryId, UUID idempotencyKey) {
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
          "PRESENTATION_BOOKING_STARTED", "Клиент уже отправил выбор из текущего представления");
    }
    try {
      dependencies.releasePresentationHolds(
          deterministic("inquiry-holds-revoke:" + inquiryId + ":" + idempotencyKey),
          inquiryId,
          actor.subjectId(),
          actor.role());
      dependencies.releasePresentationHolds(
          deterministic(
              "legacy-presentation-holds-revoke:" + presentation.getId() + ":" + idempotencyKey),
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
    List<LocalDate> requestableDeliveryDates =
        internal.mode() == ClientPresentationMode.NORMAL
            ? deliveryDatePolicy.requestableDates(presentation.getWarehouseId(), now())
            : List.of();
    return new PublicClientPresentationResponse(
        internal.id(),
        internal.revision(),
        internal.state(),
        internal.expiresAt(),
        internal.viewUntil(),
        !internal.canConfirm(),
        internal.mode(),
        internal.requiredSelectionCount(),
        internal.requiresDesiredDeliveryWindows(),
        requestableDeliveryDates,
        internal.desiredDeliveryWindows(),
        internal.equipmentAvailability(),
        internal.groups(),
        internal.bookedOrderId());
  }

  public LogisticsDependencyGateway.MediaContent media(
      String token, UUID cabinId, UUID mediaId, long generation, String variant) {
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
          presentation.getWarehouseId(), cabinId, mediaId, generation, variant);
    } catch (LogisticsDependencyException exception) {
      if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
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
    return response(presentation, tokens.issue(presentation.getId(), presentation.getRevision()));
  }

  private ClientPresentationResponse response(ClientPresentation presentation, String token) {
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
                  Comparator.comparingInt(LogisticsDependencyGateway.CabinMediaPhoto::sortOrder)
                      .thenComparing(value -> value.mediaId().toString()))
              .map(photo -> photo(presentation, token, cabin.id(), photo))
              .toList();
      PresentationCabin value =
          new PresentationCabin(
              cabin.id(),
              cabin.number(),
              item.getPricingVersion(),
              item.getMonthlyPriceRubles(),
              cabin.rentalType(),
              cabin.dimensions(),
              cabin.finishing(),
              cabin.category(),
              cabin.characteristics(),
              cabin.linoleum(),
              publicPassport(cabin.passport()),
              cabin.tags(),
              (cabin.contents() == null
                      ? List.<LogisticsDependencyGateway.OrderEquipmentContent>of()
                      : cabin.contents())
                  .stream()
                      .map(
                          content ->
                              new dev.buhanzaz.rwms.logistics.order.api.OrderApiModels
                                  .OrderEquipmentContentResponse(
                                  content.equipmentId(),
                                  content.equipmentName(),
                                  content.quantity(),
                                  content.locationKind()))
                      .toList(),
              publicPhotos);
      groups
          .computeIfAbsent(
              item.getGroupKey(),
              ignored -> new MutableGroup(item.getGroupKey(), item.getGroupLabel()))
          .cabins()
          .add(value);
    }
    OffsetDateTime timestamp = now();
    List<UUID> replacementUnitIds = replacementUnitIds(presentation);
    RentalInquiry presentationInquiry =
        inquiries
            .findById(presentation.getInquiryId())
            .orElseThrow(() -> notFound("Диалог аренды не найден"));
    boolean requiresDesiredDeliveryWindows = presentation.getMode() == ClientPresentationMode.NORMAL;
    var desiredDeliveryWindows =
        presentation.getMode() == ClientPresentationMode.REPLACEMENT
                && presentationInquiry.getRentalOrderId() != null
            ? rentalOrders.presentationDesiredDeliveryWindows(
                presentationInquiry.getRentalOrderId(),
                presentationInquiry.getClient().getId(),
                presentation.getWarehouseId())
            : List.<DesiredDeliveryWindowResponse>of();
    Map<UUID, Long> linkedOrderSurplus =
        presentation.getMode() == ClientPresentationMode.NORMAL
                && presentationInquiry.getRentalOrderId() != null
            ? rentalOrders.presentationEquipmentSurplus(
                presentationInquiry.getRentalOrderId(),
                presentationInquiry.getClient().getId(),
                presentation.getWarehouseId())
            : Map.of();
    List<PresentationEquipmentAvailability> equipmentAvailability;
    try {
      equipmentAvailability =
          dependencies.readLogisticsEquipmentAvailability(presentation.getWarehouseId()).stream()
              .filter(LogisticsDependencyGateway.EquipmentWarehouseAvailability::active)
              .sorted(
                  Comparator.comparing(
                          LogisticsDependencyGateway.EquipmentWarehouseAvailability::equipmentName,
                          String.CASE_INSENSITIVE_ORDER)
                      .thenComparing(
                          LogisticsDependencyGateway.EquipmentWarehouseAvailability::equipmentId))
              .map(
                  value ->
                      new PresentationEquipmentAvailability(
                          value.equipmentId(),
                          value.equipmentName(),
                          addAvailability(
                              value.availableQuantity(),
                              linkedOrderSurplus.getOrDefault(value.equipmentId(), 0L)),
                          value.maximumPerCabin()))
              .toList();
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception);
    }
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
        presentation.getMode(),
        replacementUnitIds,
        presentation.getMode() == ClientPresentationMode.REPLACEMENT
            ? replacementUnitIds.size()
            : null,
        requiresDesiredDeliveryWindows,
        desiredDeliveryWindows,
        equipmentAvailability,
        groups.values().stream()
            .map(
                group ->
                    new PresentationGroup(group.key(), group.label(), List.copyOf(group.cabins())))
            .toList());
  }

  private static long addAvailability(long shared, long orderSurplus) {
    if (shared < 0 || orderSurplus < 0) {
      throw conflict(
          "EQUIPMENT_AVAILABILITY_INVALID", "Сервис имущества вернул некорректный остаток мебели");
    }
    try {
      return Math.addExact(shared, orderSurplus);
    } catch (ArithmeticException exception) {
      throw conflict(
          "EQUIPMENT_AVAILABILITY_INVALID", "Сервис имущества вернул некорректный остаток мебели");
    }
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
    String large = photo.availableVariants().contains("LARGE") ? "LARGE" : "SMALL";
    String small = photo.availableVariants().contains("SMALL") ? "SMALL" : large;
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
            if (key instanceof String text && isPublicPassportKey(text, child, false)) {
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

  private static List<GroupSelection> selections(PublishClientPresentationRequest request) {
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
          throw new IllegalArgumentException("A cabin can occur only once in a presentation");
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

  private PresentationIntent intent(
      OrderActor actor,
      RentalInquiry inquiry,
      PublishClientPresentationRequest request,
      List<UUID> alternatives) {
    ClientPresentationMode mode =
        request.mode() == null ? ClientPresentationMode.NORMAL : request.mode();
    List<UUID> replacementUnitIds = uniqueIds(request.replacementUnitIds());
    if (mode == ClientPresentationMode.NORMAL) {
      if (!replacementUnitIds.isEmpty()) {
        throw new IllegalArgumentException(
            "Normal presentation cannot contain replacement targets");
      }
      OrderDetailResponse linkedOrder = null;
      if (inquiry.getRentalOrderId() != null) {
        linkedOrder = rentalOrders.get(actor, inquiry.getRentalOrderId());
        if (!linkedOrder.permissions().canEdit()
            || !linkedOrder.client().id().equals(inquiry.getClient().getId())) {
          throw conflict(
              "ORDER_NOT_EDITABLE", "Заказ больше нельзя дополнять через обычное бронирование");
        }
        if (linkedOrder.warehouseId() != null
            && !linkedOrder.warehouseId().equals(request.warehouseId())) {
          throw conflict("ORDER_WAREHOUSE_LOCKED", "Представление использует другой склад заказа");
        }
      }
      return new PresentationIntent(mode, List.of(), linkedOrder);
    }
    if (!actor.globalAdministrator() && !actor.localAdministrator()) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Warehouse manager role is required for cabin replacement");
    }
    if (inquiry.getRentalOrderId() == null || replacementUnitIds.isEmpty()) {
      throw conflict(
          "REPLACEMENT_ORDER_REQUIRED", "Замена создаётся только для бытовок существующего заказа");
    }
    OrderDetailResponse order = rentalOrders.get(actor, inquiry.getRentalOrderId());
    if ((order.status() != RentalOrderStatus.DRAFT && order.status() != RentalOrderStatus.SAVED)
        || !order.client().id().equals(inquiry.getClient().getId())
        || order.warehouseId() == null
        || !order.warehouseId().equals(request.warehouseId())) {
      throw conflict(
          "ORDER_NOT_REPLACEABLE", "Заказ больше нельзя изменять через представление замены");
    }
    Set<UUID> currentUnitIds =
        order.units().stream()
            .map(unit -> unit.unit().id())
            .collect(Collectors.toUnmodifiableSet());
    if (!currentUnitIds.containsAll(replacementUnitIds)) {
      throw conflict("REPLACEMENT_TARGET_INVALID", "В заказе нет одной из заменяемых бытовок");
    }
    rentalOrders.requirePresentationReplacementTargetsPreStart(
        inquiry.getRentalOrderId(), replacementUnitIds);
    if (alternatives.size() < replacementUnitIds.size()
        || alternatives.stream().anyMatch(currentUnitIds::contains)) {
      throw conflict(
          "REPLACEMENT_ALTERNATIVES_INVALID",
          "Для замены требуется достаточно других доступных бытовок");
    }
    return new PresentationIntent(mode, replacementUnitIds, null);
  }

  /**
   * Fixes the warehouse of a first normal presentation's unassigned draft before any asset-owned
   * hold is transferred. The intent carries the exact already-authorized order response so this
   * command retains its optimistic version fence without a second unfenced read.
   */
  private void lockLinkedNormalOrderWarehouse(
      OrderActor actor,
      RentalInquiry inquiry,
      UUID presentationIdempotencyKey,
      PublishClientPresentationRequest request,
      PresentationIntent intent) {
    OrderDetailResponse linkedOrder = intent.linkedOrder();
    if (intent.mode() != ClientPresentationMode.NORMAL
        || linkedOrder == null
        || linkedOrder.warehouseId() != null) {
      return;
    }
    rentalOrders.selectWarehouseForPresentation(
        actor,
        linkedOrder.id(),
        deterministic(
            "presentation-order-warehouse:"
                + inquiry.getId()
                + ":"
                + presentationIdempotencyKey),
        linkedOrder.version(),
        request.warehouseId());
  }

  public List<UUID> replacementUnitIds(ClientPresentation presentation) {
    try {
      List<UUID> values =
          json.readValue(
              presentation.getReplacementUnitIdsJson(), new TypeReference<List<UUID>>() {});
      return uniqueIds(values);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored replacement targets are corrupt", exception);
    }
  }

  private static List<UUID> uniqueIds(List<UUID> values) {
    if (values == null || values.isEmpty()) return List.of();
    LinkedHashSet<UUID> unique = new LinkedHashSet<>();
    for (UUID value : values) {
      if (value == null || !unique.add(value)) {
        throw new IllegalArgumentException("Identifiers must be non-null and unique");
      }
    }
    return List.copyOf(unique);
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

  private static OrderProblemException dependencyProblem(LogisticsDependencyException exception) {
    if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      return conflict(
          exception.dependencyCode() == null ? "PRESENTATION_REJECTED" : exception.dependencyCode(),
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
        HttpStatus.GONE, "CLIENT_PRESENTATION_GONE", "Срок действия представления истёк");
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  /** Validated ordered cabin selection for one presentation group. */
  private record GroupSelection(String key, String label, List<UUID> rentalItemIds) {}

  /**
   * Validated purpose and immutable old-unit targets for one presentation revision, with the exact
   * linked normal-order response retained for its warehouse version fence.
   */
  private record PresentationIntent(
      ClientPresentationMode mode,
      List<UUID> replacementUnitIds,
      OrderDetailResponse linkedOrder) {}

  /** Local assembly bucket that preserves group order while held cabin snapshots are validated. */
  private record MutableGroup(String key, String label, List<PresentationCabin> cabins) {
    private MutableGroup(String key, String label) {
      this(key, label, new ArrayList<>());
    }
  }
}
