package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.*;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CabinCatalogValueResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitReservationView;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHold;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHoldState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.repository.OperationLeaseRepository;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.PresentationUnitHoldRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class PresentationHoldService {
  private static final int MAX_SEARCH_POOL = 2_000;
  private static final int MAX_CHAT_HOLD_MINUTES = 1_440;
  private static final Set<RentalItemStatus> RENTABLE_STATUSES =
      Set.of(RentalItemStatus.FREE);

  private final PresentationUnitHoldRepository holds;
  private final RentalItemRepository rentalItems;
  private final OrderUnitReservationRepository orderReservations;
  private final OperationLeaseRepository operationLeases;
  private final AssetService assets;
  private final CabinCompositionService cabinComposition;
  private final OrderAssetService orders;
  private final AssetIdempotencyStore idempotency;
  private final ObjectMapper json;

  @Transactional
  public CabinFacetResponse facets(UUID warehouseId) {
    return facets(warehouseId, null);
  }

  @Transactional
  public CabinFacetResponse facets(UUID warehouseId, UUID holdScopeId) {
    UUID requiredWarehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    OffsetDateTime timestamp = now();
    holds.expireDue(timestamp);
    List<RentalItem> available = availableItems(requiredWarehouseId, holdScopeId, timestamp);
    Map<UUID, CabinCompositionService.CabinComposition> compositions =
        cabinComposition.compositionsFor(available);
    return new CabinFacetResponse(
        requiredWarehouseId,
        distinct(available, item -> name(compositions.get(item.getId()).rentalType())),
        distinct(available, item -> name(compositions.get(item.getId()).finishing())),
        distinct(available, item -> name(compositions.get(item.getId()).dimensions())),
        distinct(available, RentalItem::getCategory));
  }

  @Transactional
  public CabinSearchResponse search(CabinSearchRequest request) {
    OffsetDateTime timestamp = now();
    if (!request.expiresAt().isAfter(timestamp)
        || request.expiresAt().isAfter(timestamp.plusMinutes(MAX_CHAT_HOLD_MINUTES))) {
      throw new IllegalArgumentException(
          "Chat search expiresAt must be within the next 24 hours");
    }
    holds.acquireTransactionLock("presentation-holds:" + request.holdScopeId());
    holds.acquireTransactionLock("presentation-search:" + request.warehouseId());
    holds.expireDue(timestamp);
    List<PresentationUnitHold> current =
        new ArrayList<>(
            holds.findAllActiveForUpdate(
                request.holdScopeId(), PresentationUnitHoldState.ACTIVE));
    Map<UUID, PresentationUnitHold> currentByItem =
        current.stream()
            .collect(
                Collectors.toMap(
                    PresentationUnitHold::getRentalItemId, Function.identity()));
    List<RentalItem> available =
        availableItems(request.warehouseId(), request.holdScopeId(), timestamp);
    Map<UUID, CabinCompositionService.CabinComposition> compositions =
        cabinComposition.compositionsFor(available);
    Set<UUID> allocated = new HashSet<>();
    List<CabinSearchGroupResult> results = new ArrayList<>();
    for (CabinSearchGroup group : request.groups()) {
      List<AvailableCabin> cabins =
          available.stream()
              .filter(item -> !allocated.contains(item.getId()))
              .filter(item -> matches(name(compositions.get(item.getId()).rentalType()), group.cabinType()))
              .filter(item -> matches(name(compositions.get(item.getId()).finishing()), group.finish()))
              .filter(item -> matches(name(compositions.get(item.getId()).dimensions()), group.dimensions()))
              .filter(item -> matches(item.getCategory(), group.category()))
              .filter(
                  item ->
                      matchesCharacteristics(
                          compositions.get(item.getId()).characteristics(),
                          group.characteristics()))
              .filter(
                  item ->
                      group.linoleum() == null || group.linoleum().equals(item.getLinoleum()))
              .limit(group.quantity())
              .peek(item -> allocated.add(item.getId()))
              .map(this::snapshot)
              .toList();
      results.add(new CabinSearchGroupResult(group, cabins));
    }
    List<UUID> selectedIds = allocated.stream().sorted().toList();
    if (!selectedIds.isEmpty()) {
      List<RentalItem> lockedItems = rentalItems.findAllByIdInForUpdate(selectedIds);
      if (lockedItems.size() != selectedIds.size()) {
        throw new AssetNotFoundException("Rental item was not found");
      }
      Map<UUID, RentalItem> items =
          lockedItems.stream()
              .collect(Collectors.toMap(RentalItem::getId, Function.identity()));
      for (UUID rentalItemId : selectedIds) {
        requireRentable(items.get(rentalItemId), request.warehouseId());
        try {
          assets.lockOrderRentalItemForOrder(rentalItemId);
        } catch (AssetConflictException exception) {
          throw conflict(
              "UNIT_NOT_AVAILABLE", "Бытовка выполняет другую складскую операцию");
        }
        if (orderReservations
            .findByRentalItemIdAndState(rentalItemId, OrderUnitReservationState.ACTIVE)
            .isPresent()) {
          throw conflict("UNIT_ORDER_RESERVED", "Бытовка уже занята заказом");
        }
        PresentationUnitHold existing =
            holds
                .findActiveByRentalItemForUpdate(
                    rentalItemId, PresentationUnitHoldState.ACTIVE)
                .orElse(null);
        if (existing != null
            && !existing.getPresentationId().equals(request.holdScopeId())) {
          throw conflict(
              "UNIT_PRESENTATION_HELD", "Бытовка уже показана другому клиенту");
        }
        PresentationUnitHold own = currentByItem.get(rentalItemId);
        if (own == null) {
          own =
              PresentationUnitHold.create(
                  request.holdScopeId(),
                  rentalItemId,
                  request.warehouseId(),
                  request.expiresAt(),
                  request.actorSubjectId(),
                  request.actorRole(),
                  timestamp);
          current.add(own);
          currentByItem.put(rentalItemId, own);
        } else if (own.getExpiresAt().isBefore(request.expiresAt())) {
          own.renew(request.expiresAt(), timestamp);
        }
      }
      try {
        holds.saveAllAndFlush(current);
      } catch (DataIntegrityViolationException exception) {
        throw conflict(
            "UNIT_PRESENTATION_HELD", "Бытовка уже показана другому клиенту");
      }
    }
    return new CabinSearchResponse(
        request.warehouseId(), request.expiresAt(), List.copyOf(results));
  }

  @Transactional
  public CabinAvailabilityResponse availability(CabinAvailabilityRequest request) {
    OffsetDateTime now = now();
    holds.expireDue(now);
    List<UUID> ids = uniqueIds(request.rentalItemIds(), 100);
    Map<UUID, RentalItem> items =
        rentalItems.findAllById(ids).stream()
            .collect(Collectors.toMap(RentalItem::getId, Function.identity()));
    Set<UUID> orderReserved =
        orderReservations
            .findAllByRentalItemIdInAndState(ids, OrderUnitReservationState.ACTIVE)
            .stream()
            .map(OrderUnitReservation::getRentalItemId)
            .collect(Collectors.toSet());
    Set<UUID> presentationHeld =
        holds.findAllLiveByRentalItemIdIn(ids, PresentationUnitHoldState.ACTIVE, now).stream()
            .map(PresentationUnitHold::getRentalItemId)
            .collect(Collectors.toSet());
    Set<UUID> leased = Set.copyOf(operationLeases.findAllLiveRentalItemIds(ids, now));
    List<CabinAvailability> response =
        ids.stream()
            .map(
                id -> {
                  RentalItem item = items.get(id);
                  String reason =
                      item == null
                          ? "NOT_FOUND"
                          : !request.warehouseId().equals(item.getWarehouseId())
                              ? "WAREHOUSE_MISMATCH"
                              : !RENTABLE_STATUSES.contains(item.getStatus())
                                  ? "STATUS"
                                  : orderReserved.contains(id)
                                      ? "ORDER_RESERVED"
                                      : presentationHeld.contains(id)
                                          ? "PRESENTATION_HELD"
                                          : leased.contains(id)
                                              ? "OPERATION_LEASED"
                                              : "AVAILABLE";
                  return new CabinAvailability(id, "AVAILABLE".equals(reason), reason);
                })
            .toList();
    return new CabinAvailabilityResponse(request.warehouseId(), response);
  }

  @Transactional(readOnly = true)
  public CabinSnapshotsResponse snapshots(CabinAvailabilityRequest request) {
    List<UUID> ids = uniqueIds(request.rentalItemIds(), 100);
    Map<UUID, RentalItem> items =
        rentalItems.findAllById(ids).stream()
            .collect(Collectors.toMap(RentalItem::getId, Function.identity()));
    List<AvailableCabin> response =
        ids.stream()
            .map(items::get)
            .filter(Objects::nonNull)
            .filter(item -> request.warehouseId().equals(item.getWarehouseId()))
            .map(this::snapshot)
            .toList();
    if (response.size() != ids.size()) {
      throw new AssetNotFoundException("Rental item was not found in the selected warehouse");
    }
    return new CabinSnapshotsResponse(request.warehouseId(), response);
  }

  @Transactional
  public ReplacePresentationHoldsResponse holds(UUID presentationId) {
    OffsetDateTime timestamp = now();
    holds.expireDue(timestamp);
    List<PresentationUnitHold> active =
        holds.findAllByPresentationIdAndStateOrderByCreatedAtAscIdAsc(
            presentationId, PresentationUnitHoldState.ACTIVE);
    OffsetDateTime expiry =
        active.stream()
            .map(PresentationUnitHold::getExpiresAt)
            .max(Comparator.naturalOrder())
            .orElse(null);
    return new ReplacePresentationHoldsResponse(
        presentationId, expiry, active.stream().map(PresentationHoldService::view).toList());
  }

  @Transactional
  public AssetService.CreateResult<ReplacePresentationHoldsResponse> replace(
      UUID idempotencyKey, UUID presentationId, ReplacePresentationHoldsRequest request) {
    ReplaceCommand command = new ReplaceCommand(presentationId, request);
    String fingerprint = hash(command);
    var replay =
        idempotency.replay(
            request.actorSubjectId(), "presentation-holds.replace", idempotencyKey, fingerprint);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          read(replay.get(), ReplacePresentationHoldsResponse.class), true);
    }
    OffsetDateTime timestamp = now();
    if (!request.expiresAt().isAfter(timestamp)
        || request.expiresAt().isAfter(timestamp.plusHours(24))) {
      throw new IllegalArgumentException("expiresAt must be within the next 24 hours");
    }
    List<UUID> requestedIds = uniqueIds(request.rentalItemIds(), 100);
    holds.acquireTransactionLock("presentation-holds:" + presentationId);
    holds.expireDue(timestamp);
    List<PresentationUnitHold> current =
        holds.findAllActiveForUpdate(presentationId, PresentationUnitHoldState.ACTIVE);
    Set<UUID> lockIds = new LinkedHashSet<>(requestedIds);
    current.stream().map(PresentationUnitHold::getRentalItemId).forEach(lockIds::add);
    List<RentalItem> lockedItems =
        lockIds.isEmpty()
            ? List.of()
            : rentalItems.findAllByIdInForUpdate(lockIds.stream().sorted().toList());
    if (lockedItems.size() != lockIds.size()) {
      throw new AssetNotFoundException("Rental item was not found");
    }
    Map<UUID, RentalItem> items =
        lockedItems.stream()
            .collect(Collectors.toMap(RentalItem::getId, Function.identity()));
    Map<UUID, PresentationUnitHold> currentByItem =
        current.stream()
            .collect(Collectors.toMap(PresentationUnitHold::getRentalItemId, Function.identity()));
    Set<UUID> requested = Set.copyOf(requestedIds);

    for (PresentationUnitHold hold : current) {
      if (!requested.contains(hold.getRentalItemId())) hold.release(timestamp);
    }
    for (UUID rentalItemId : requestedIds) {
      RentalItem item = items.get(rentalItemId);
      requireRentable(item, request.warehouseId());
      try {
        assets.lockOrderRentalItemForOrder(rentalItemId);
      } catch (AssetConflictException exception) {
        throw conflict("UNIT_NOT_AVAILABLE", "Бытовка выполняет другую складскую операцию");
      }
      if (orderReservations
          .findByRentalItemIdAndState(rentalItemId, OrderUnitReservationState.ACTIVE)
          .isPresent()) {
        throw conflict("UNIT_ORDER_RESERVED", "Бытовка уже занята заказом");
      }
      PresentationUnitHold existing =
          holds
              .findActiveByRentalItemForUpdate(rentalItemId, PresentationUnitHoldState.ACTIVE)
              .orElse(null);
      if (existing != null && !existing.getPresentationId().equals(presentationId)) {
        if (existing.expire(timestamp)) {
          holds.save(existing);
        } else {
          throw conflict(
              "UNIT_PRESENTATION_HELD", "Бытовка уже показана другому клиенту");
        }
      }
      PresentationUnitHold own = currentByItem.get(rentalItemId);
      if (own == null) {
        current.add(
            PresentationUnitHold.create(
                presentationId,
                rentalItemId,
                request.warehouseId(),
                request.expiresAt(),
                request.actorSubjectId(),
                request.actorRole(),
                timestamp));
      } else {
        own.renew(request.expiresAt(), timestamp);
      }
    }
    try {
      holds.saveAllAndFlush(current);
    } catch (DataIntegrityViolationException exception) {
      throw conflict("UNIT_PRESENTATION_HELD", "Бытовка уже показана другому клиенту");
    }
    List<PresentationUnitHold> active =
        holds.findAllByPresentationIdAndStateOrderByCreatedAtAscIdAsc(
            presentationId, PresentationUnitHoldState.ACTIVE);
    ReplacePresentationHoldsResponse response =
        new ReplacePresentationHoldsResponse(
            presentationId,
            request.expiresAt(),
            active.stream().map(PresentationHoldService::view).toList());
    idempotency.store(
        request.actorSubjectId(),
        "presentation-holds.replace",
        idempotencyKey,
        fingerprint,
        200,
        response);
    return new AssetService.CreateResult<>(response, false);
  }

  @Transactional
  public AssetService.CreateResult<ReplacePresentationHoldsResponse> release(
      UUID idempotencyKey, UUID presentationId, ActorInput request) {
    ReleaseCommand command = new ReleaseCommand(presentationId, request);
    String fingerprint = hash(command);
    var replay =
        idempotency.replay(
            request.actorSubjectId(), "presentation-holds.release", idempotencyKey, fingerprint);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          read(replay.get(), ReplacePresentationHoldsResponse.class), true);
    }
    OffsetDateTime timestamp = now();
    holds.acquireTransactionLock("presentation-holds:" + presentationId);
    List<PresentationUnitHold> active =
        holds.findAllActiveForUpdate(presentationId, PresentationUnitHoldState.ACTIVE);
    active.forEach(value -> value.release(timestamp));
    holds.saveAllAndFlush(active);
    ReplacePresentationHoldsResponse response =
        new ReplacePresentationHoldsResponse(presentationId, null, List.of());
    idempotency.store(
        request.actorSubjectId(),
        "presentation-holds.release",
        idempotencyKey,
        fingerprint,
        200,
        response);
    return new AssetService.CreateResult<>(response, false);
  }

  @Transactional
  public AssetService.CreateResult<ConvertPresentationHoldsResponse> convert(
      UUID idempotencyKey, UUID presentationId, ConvertPresentationHoldsRequest request) {
    ConvertCommand command = new ConvertCommand(presentationId, request);
    String fingerprint = hash(command);
    var replay =
        idempotency.replay(
            request.actorSubjectId(), "presentation-holds.convert", idempotencyKey, fingerprint);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          read(replay.get(), ConvertPresentationHoldsResponse.class), true);
    }
    OffsetDateTime timestamp = now();
    List<UUID> selectedIds = uniqueIds(request.selectedRentalItemIds(), 100);
    Set<UUID> selected = Set.copyOf(selectedIds);
    holds.acquireTransactionLock("presentation-holds:" + presentationId);
    List<PresentationUnitHold> current =
        holds.findAllActiveForUpdate(presentationId, PresentationUnitHoldState.ACTIVE);
    if (current.isEmpty()) {
      throw conflict("PRESENTATION_HOLDS_EXPIRED", "Временный резерв уже истёк");
    }
    Map<UUID, PresentationUnitHold> byItem =
        current.stream()
            .collect(Collectors.toMap(PresentationUnitHold::getRentalItemId, Function.identity()));
    if (!byItem.keySet().containsAll(selected)) {
      throw conflict(
          "PRESENTATION_SELECTION_INVALID",
          "Выбраны бытовки, которых нет в представлении");
    }
    List<UUID> lockIds = current.stream()
        .map(PresentationUnitHold::getRentalItemId)
        .distinct()
        .sorted()
        .toList();
    List<RentalItem> locked = rentalItems.findAllByIdInForUpdate(lockIds);
    if (locked.size() != lockIds.size()) {
      throw new AssetNotFoundException("Rental item was not found");
    }
    Map<UUID, RentalItem> items =
        locked.stream().collect(Collectors.toMap(RentalItem::getId, Function.identity()));
    for (UUID id : selectedIds) {
      PresentationUnitHold hold = byItem.get(id);
      if (!hold.isLiveAt(timestamp)) {
        throw conflict("PRESENTATION_HOLDS_EXPIRED", "Временный резерв уже истёк");
      }
      requireRentable(items.get(id), request.warehouseId());
      try {
        assets.lockOrderRentalItemForOrder(id);
      } catch (AssetConflictException exception) {
        throw conflict("UNIT_NOT_AVAILABLE", "Бытовка выполняет другую складскую операцию");
      }
      if (orderReservations
          .findByRentalItemIdAndState(id, OrderUnitReservationState.ACTIVE)
          .isPresent()) {
        throw conflict("UNIT_ORDER_RESERVED", "Бытовка уже занята заказом");
      }
    }
    List<OrderUnitReservation> created = new ArrayList<>();
    for (PresentationUnitHold hold : current) {
      if (selected.contains(hold.getRentalItemId())) {
        hold.convert(request.orderId(), timestamp);
        created.add(
            OrderUnitReservation.create(
                request.orderId(),
                hold.getRentalItemId(),
                request.warehouseId(),
                request.clientId(),
                request.tenantSnapshot(),
                null,
                request.actorSubjectId(),
                request.actorRole()));
      } else {
        hold.release(timestamp);
      }
    }
    try {
      holds.saveAllAndFlush(current);
      orderReservations.saveAllAndFlush(created);
      selectedIds.forEach(assets::bookOrderRentalItem);
    } catch (DataIntegrityViolationException exception) {
      throw conflict("UNIT_ORDER_RESERVED", "Бытовка уже занята заказом");
    }
    Map<UUID, OrderUnitReservationView> views =
        orders.units(request.orderId()).stream()
            .collect(
                Collectors.toMap(
                    value -> value.unit().id(), Function.identity(), (left, right) -> left));
    ConvertPresentationHoldsResponse response =
        new ConvertPresentationHoldsResponse(
            presentationId,
            request.orderId(),
            selectedIds.stream().map(views::get).filter(Objects::nonNull).toList(),
            current.stream()
                .map(PresentationUnitHold::getRentalItemId)
                .filter(id -> !selected.contains(id))
                .toList());
    idempotency.store(
        request.actorSubjectId(),
        "presentation-holds.convert",
        idempotencyKey,
        fingerprint,
        200,
        response);
    return new AssetService.CreateResult<>(response, false);
  }

  private List<RentalItem> availableItems(UUID warehouseId) {
    OffsetDateTime timestamp = now();
    holds.expireDue(timestamp);
    return availableItems(warehouseId, null, timestamp);
  }

  private List<RentalItem> availableItems(
      UUID warehouseId, UUID ownHoldScopeId, OffsetDateTime timestamp) {
    List<RentalItem> pool =
        rentalItems.findAllByWarehouseIdAndStatusInOrderByIdentityMatchKeyAscIdAsc(
            warehouseId, RENTABLE_STATUSES);
    if (pool.size() > MAX_SEARCH_POOL) {
      pool = pool.subList(0, MAX_SEARCH_POOL);
    }
    List<UUID> ids = pool.stream().map(RentalItem::getId).toList();
    if (ids.isEmpty()) return List.of();
    Set<UUID> orderReserved =
        orderReservations
            .findAllByRentalItemIdInAndState(ids, OrderUnitReservationState.ACTIVE)
            .stream()
            .map(OrderUnitReservation::getRentalItemId)
            .collect(Collectors.toSet());
    Set<UUID> presentationHeld =
        holds.findAllLiveByRentalItemIdIn(ids, PresentationUnitHoldState.ACTIVE, timestamp).stream()
            .filter(
                hold ->
                    ownHoldScopeId == null
                        || !ownHoldScopeId.equals(hold.getPresentationId()))
            .map(PresentationUnitHold::getRentalItemId)
            .collect(Collectors.toSet());
    Set<UUID> leased = Set.copyOf(operationLeases.findAllLiveRentalItemIds(ids, timestamp));
    return pool.stream()
        .filter(item -> !orderReserved.contains(item.getId()))
        .filter(item -> !presentationHeld.contains(item.getId()))
        .filter(item -> !leased.contains(item.getId()))
        .toList();
  }

  private AvailableCabin snapshot(RentalItem item) {
    RentalItemResponse source = assets.rentalItem(item.getId());
    return new AvailableCabin(
        source.id(),
        source.version(),
        source.warehouseId(),
        source.number(),
        source.status(),
        source.rentalType(),
        source.dimensions(),
        source.finishing(),
        source.category(),
        source.characteristics().stream()
            .map(CabinCatalogValueResponse::name)
            .collect(Collectors.joining(", ")),
        source.linoleum(),
        source.passport(),
        source.tags(),
        source.updatedAt());
  }

  private static boolean matches(String actual, String requested) {
    String expected = normalize(requested);
    return expected.isEmpty() || normalize(actual).equals(expected);
  }

  private static boolean matchesCharacteristics(
      List<CabinCatalogValueResponse> actual, String requested) {
    String expected = normalizeCharacteristics(requested);
    return expected.isEmpty()
        || actual.stream()
            .map(CabinCatalogValueResponse::name)
            .map(PresentationHoldService::normalizeCharacteristics)
            .anyMatch(value -> value.contains(expected));
  }

  private static String name(CabinCatalogValueResponse value) {
    return value == null ? null : value.name();
  }

  private static String normalizeCharacteristics(String value) {
    return value == null
        ? ""
        : value.replaceAll("[\\p{Z}\\s]+", " ").trim().toLowerCase(Locale.ROOT);
  }

  private static String normalize(String value) {
    return value == null
        ? ""
        : value.trim().replaceAll("[\\p{Z}\\s]+", " ").toLowerCase(Locale.ROOT);
  }

  private static List<String> distinct(
      List<RentalItem> values, Function<RentalItem, String> extractor) {
    return values.stream()
        .map(extractor)
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .distinct()
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .toList();
  }

  private static List<UUID> uniqueIds(List<UUID> values, int maximum) {
    if (values == null || values.isEmpty() || values.size() > maximum) {
      throw new IllegalArgumentException("rentalItemIds size is invalid");
    }
    LinkedHashSet<UUID> result = new LinkedHashSet<>();
    for (UUID value : values) {
      if (value == null || !result.add(value)) {
        throw new IllegalArgumentException("rentalItemIds must be unique and non-null");
      }
    }
    return List.copyOf(result);
  }

  private static void requireRentable(RentalItem item, UUID warehouseId) {
    if (!warehouseId.equals(item.getWarehouseId())) {
      throw conflict("UNIT_WAREHOUSE_MISMATCH", "Бытовка находится на другом складе");
    }
    if (!RENTABLE_STATUSES.contains(item.getStatus())) {
      throw conflict("UNIT_NOT_AVAILABLE", "Бытовка больше не доступна для аренды");
    }
  }

  private static PresentationHoldView view(PresentationUnitHold hold) {
    return new PresentationHoldView(
        hold.getId(),
        hold.getVersion(),
        hold.getPresentationId(),
        hold.getRentalItemId(),
        hold.getWarehouseId(),
        hold.getState().name(),
        hold.getExpiresAt(),
        hold.getOrderId(),
        hold.getCreatedAt(),
        hold.getEndedAt());
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private static OrderUnitReservationConflictException conflict(String code, String message) {
    return new OrderUnitReservationConflictException(code, message);
  }

  private String hash(Object value) {
    try {
      return AssetChecksum.sha256(json.writeValueAsBytes(value));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Presentation hold command cannot be fingerprinted", exception);
    }
  }

  private <T> T read(JsonNode node, Class<T> type) {
    try {
      return json.readerFor(type).readValue(node);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored presentation hold response is corrupt", exception);
    }
  }

  private record ReplaceCommand(UUID presentationId, ReplacePresentationHoldsRequest request) {}

  private record ReleaseCommand(UUID presentationId, ActorInput request) {}

  private record ConvertCommand(UUID presentationId, ConvertPresentationHoldsRequest request) {}
}
