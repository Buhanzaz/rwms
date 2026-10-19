package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.*;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CabinCatalogValueResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemPage;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderEquipmentReservationView;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitReservationView;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.ReplaceOrderEquipmentReservationsRequest;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns asset-side presentation holds and their fenced release or conversion into reservation work.
 * It prevents a presentation retry from bypassing the asset availability and lease boundaries.
 */
@Service
@RequiredArgsConstructor
public class PresentationHoldService {
  private static final int MAX_SEARCH_POOL = 2_000;
  private static final int MAX_CHAT_HOLD_MINUTES = 1_440;
  private static final Set<String> ACTOR_ROLES =
      Set.of(
          "SYSTEM_ADMIN",
          "WMS_ADMIN",
          "WAREHOUSE_MANAGER",
          "RENTAL_MANAGER",
          "CUSTOMER",
          "VIEWER");
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
  private final RentalAvailabilityInvalidationPublisher availabilityInvalidations;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  @Transactional
  public CabinFacetResponse facets(UUID warehouseId) {
    return facets(warehouseId, null, false);
  }

  @Transactional
  public CabinFacetResponse facets(UUID warehouseId, UUID holdScopeId) {
    return facets(warehouseId, holdScopeId, false);
  }

  /**
   * Returns availability-backed facets, filtering only hidden characteristics for the customer
   * surface. Internal logistics and manager reads retain the complete composition.
   */
  @Transactional
  public CabinFacetResponse facets(
      UUID warehouseId, UUID holdScopeId, boolean customerVisibleCharacteristicsOnly) {
    UUID requiredWarehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    OffsetDateTime timestamp = now();
    holds.expireDue(timestamp);
    List<RentalItem> available = availableItems(requiredWarehouseId, holdScopeId, timestamp);
    Map<UUID, CabinCompositionService.CabinComposition> compositions =
        customerVisibleCharacteristicsOnly
            ? cabinComposition.customerCompositionsFor(available)
            : cabinComposition.compositionsFor(available);
    CabinCompositionService.CatalogOrder catalogOrder = cabinComposition.catalogOrder();
    List<CabinCatalogValueResponse> typeValues =
        available.stream()
            .map(item -> compositions.get(item.getId()).rentalType())
            .toList();
    List<String> cabinTypes = catalogOrder.orderedFacetNames(typeValues);
    Map<String, List<CabinCatalogValueResponse>> dimensionsByType = new LinkedHashMap<>();
    for (String cabinType : cabinTypes) {
      dimensionsByType.put(cabinType, new ArrayList<>());
    }
    for (RentalItem item : available) {
      CabinCompositionService.CabinComposition composition = compositions.get(item.getId());
      String cabinType = trimmedName(composition.rentalType());
      CabinCatalogValueResponse dimension = composition.dimensions();
      if (cabinType != null && trimmedName(dimension) != null) {
        dimensionsByType.computeIfAbsent(cabinType, ignored -> new ArrayList<>()).add(dimension);
      }
    }
    List<String> finishes =
        catalogOrder.orderedFacetNames(
            available.stream()
                .map(item -> compositions.get(item.getId()).finishing())
                .toList());
    List<String> dimensions =
        catalogOrder.orderedFacetNames(
            available.stream()
                .map(item -> compositions.get(item.getId()).dimensions())
                .toList());
    List<String> categories =
        catalogOrder.orderedFacetNames(
            available.stream()
                .map(
                    item ->
                        new CabinCatalogValueResponse(item.getCategoryId(), item.getCategory()))
                .toList());
    List<String> characteristics =
        catalogOrder.orderedFacetNames(
            compositions.values().stream()
                .flatMap(composition -> composition.characteristics().stream())
                .toList());
    return new CabinFacetResponse(
        requiredWarehouseId,
        cabinTypes,
        finishes,
        dimensions,
        categories,
        characteristics,
        cabinTypes.stream()
            .map(
                cabinType ->
                    new CabinTypeDimensions(
                        cabinType,
                        catalogOrder.orderedFacetNames(
                            dimensionsByType.getOrDefault(cabinType, List.of()))))
            .filter(entry -> !entry.dimensions().isEmpty())
            .toList());
  }

  /**
   * Reads facts for all warehouse cabins, regardless of current hold or operational availability.
   * Text lookup covers number, type, finish, size, category and characteristics; a linoleum query
   * additionally resolves the boolean passport characteristic. No expiry or hold is changed.
   */
  @Transactional(readOnly = true)
  public CabinCatalogPage catalog(UUID warehouseId, String query, int page, int size) {
    UUID requiredWarehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    if (page < 0 || size < 1 || size > 100 || (query != null && query.length() > 255)) {
      throw new IllegalArgumentException("Invalid cabin catalog page request");
    }
    String normalized = normalize(query);
    if (normalized.contains("линолеум")) {
      boolean requestedValue = !normalized.contains("без");
      List<RentalItem> matches =
          rentalItems.findAllByWarehouseIdOrderByNumber(requiredWarehouseId).stream()
              .filter(item -> Boolean.valueOf(requestedValue).equals(item.getLinoleum()))
              .toList();
      long offset = (long) page * size;
      int from = (int) Math.min(offset, matches.size());
      int to = Math.min(from + size, matches.size());
      long pages = matches.isEmpty() ? 0 : (matches.size() + (long) size - 1) / size;
      return new CabinCatalogPage(
          requiredWarehouseId,
          matches.subList(from, to).stream().map(this::snapshot).toList(),
          page,
          size,
          matches.size(),
          pages);
    }
    Page<RentalItem> result =
        rentalItems.findPublicPage(
            requiredWarehouseId,
            normalized.toUpperCase(Locale.ROOT),
            PageRequest.of(page, size, Sort.by("number").ascending().and(Sort.by("id"))));
    return new CabinCatalogPage(
        requiredWarehouseId,
        result.getContent().stream().map(this::snapshot).toList(),
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  /**
   * Returns one stable page of cabins that are currently rentable by a customer inquiry. The
   * inquiry's own live presentation holds remain visible when its scope is supplied; an absent
   * scope excludes every live presentation hold. Order reservations and operation leases are
   * always excluded. Structured filters are exact after
   * whitespace/case normalization; every requested characteristic must be present.
   */
  @Transactional
  public CabinCatalogPage customerCatalog(
      UUID warehouseId,
      UUID holdScopeId,
      String query,
      String cabinType,
      String finish,
      String dimensions,
      String category,
      Boolean linoleum,
      List<String> characteristics,
      int page,
      int size) {
    UUID requiredWarehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    validateCustomerCatalogRequest(
        query, cabinType, finish, dimensions, category, characteristics, page, size);
    List<String> requiredCharacteristics = normalizedCharacteristics(characteristics);
    OffsetDateTime timestamp = now();
    holds.expireDue(timestamp);
    List<RentalItem> available = availableItems(requiredWarehouseId, holdScopeId, timestamp, false);
    Map<UUID, CabinCompositionService.CabinComposition> compositions =
        cabinComposition.customerCompositionsFor(available);
    List<RentalItem> matches =
        available.stream()
            .filter(
                item ->
                    matchesCustomerQuery(
                        item, compositions.get(item.getId()), query))
            .filter(
                item ->
                    matches(
                        name(compositions.get(item.getId()).rentalType()), cabinType))
            .filter(
                item ->
                    matches(
                        name(compositions.get(item.getId()).finishing()), finish))
            .filter(
                item ->
                    matches(
                        name(compositions.get(item.getId()).dimensions()), dimensions))
            .filter(item -> matches(item.getCategory(), category))
            .filter(item -> linoleum == null || linoleum.equals(item.getLinoleum()))
            .filter(
                item ->
                    containsAllCharacteristics(
                        compositions.get(item.getId()).characteristics(),
                        requiredCharacteristics))
            .toList();
    long offset = (long) page * size;
    int from = (int) Math.min(offset, matches.size());
    int to = Math.min(from + size, matches.size());
    long totalPages =
        matches.isEmpty() ? 0 : (matches.size() + (long) size - 1) / size;
    return new CabinCatalogPage(
        requiredWarehouseId,
        matches.subList(from, to).stream()
            .map(item -> snapshot(item, compositions.get(item.getId())))
            .toList(),
        page,
        size,
        matches.size(),
        totalPages);
  }

  /**
   * Applies one subject-scoped search exactly once: its request hash, status-200 response and holds
   * commit in the same transaction. Selected cabin identities are locked against movement, then
   * their contents are resnapshotted so the returned facts match the newly active hold fence.
   */
  @Transactional
  public AssetService.CreateResult<CabinSearchResponse> search(
      UUID subjectId, UUID idempotencyKey, CabinSearchRequest request) {
    Objects.requireNonNull(subjectId, "subjectId");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    String fingerprint = hash(request);
    var replay =
        idempotency.replay(
            subjectId,
            "logistics.cabin-search",
            idempotencyKey,
            fingerprint);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          read(replay.get(), CabinSearchResponse.class), true);
    }
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
    if (current.stream()
        .anyMatch(hold -> !request.actorSubjectId().equals(hold.getCreatedBySubjectId()))) {
      throw new AssetNotFoundException("Presentation holds were not found");
    }
    Map<UUID, PresentationUnitHold> currentByItem =
        current.stream()
            .collect(
                Collectors.toMap(
                    PresentationUnitHold::getRentalItemId, Function.identity()));
    boolean append = "APPEND".equals(request.normalizedResultMode());
    List<RentalItem> available =
        availableItems(request.warehouseId(), request.holdScopeId(), timestamp).stream()
            .filter(item -> !append || !currentByItem.containsKey(item.getId()))
            .toList();
    Map<UUID, CabinCompositionService.CabinComposition> compositions =
        cabinComposition.compositionsFor(available);
    Set<UUID> allocated = new HashSet<>();
    Set<UUID> changedAvailability = new LinkedHashSet<>();
    Map<UUID, RentalItem> selectedItems = new LinkedHashMap<>();
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
    if (append) {
      for (PresentationUnitHold hold : current) {
        if (hold.getExpiresAt().isBefore(request.expiresAt())
            && hold.renew(request.expiresAt(), timestamp)) {
          changedAvailability.add(hold.getRentalItemId());
        }
      }
    } else {
      for (PresentationUnitHold hold : current) {
        if (!allocated.contains(hold.getRentalItemId()) && hold.release(timestamp)) {
          changedAvailability.add(hold.getRentalItemId());
        }
      }
    }
    if (!selectedIds.isEmpty()) {
      List<RentalItem> lockedItems = rentalItems.findAllByIdInForUpdate(selectedIds);
      if (lockedItems.size() != selectedIds.size()) {
        throw new AssetNotFoundException("Rental item was not found");
      }
      Map<UUID, RentalItem> items =
          lockedItems.stream()
              .collect(Collectors.toMap(RentalItem::getId, Function.identity()));
      selectedItems.putAll(items);
      for (UUID rentalItemId : selectedIds) {
        requireRentable(items.get(rentalItemId), request.warehouseId());
        try {
          assets.lockOrderRentalItemForOrder(rentalItemId);
        } catch (AssetConflictException exception) {
          throw conflict(
              "UNIT_NOT_AVAILABLE", "Бытовка выполняет другую складскую операцию");
        }
        assertNoActiveMovementFence(rentalItemId);
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
          changedAvailability.add(rentalItemId);
        } else if (own.getExpiresAt().isBefore(request.expiresAt())) {
          if (own.renew(request.expiresAt(), timestamp)) {
            changedAvailability.add(rentalItemId);
          }
        }
      }
    }
    try {
      holds.saveAllAndFlush(current);
    } catch (DataIntegrityViolationException exception) {
      throw conflict(
          "UNIT_PRESENTATION_HELD", "Бытовка уже показана другому клиенту");
    }
    availabilityInvalidations.publishAfterCommit(
        request.warehouseId(), changedAvailability);
    List<CabinSearchGroupResult> stableResults =
        results.stream()
            .map(
                result ->
                    new CabinSearchGroupResult(
                        result.group(),
                        result.cabins().stream()
                            .map(cabin -> snapshot(selectedItems.get(cabin.id())))
                            .toList()))
            .toList();
    CabinSearchResponse response =
        new CabinSearchResponse(
            request.warehouseId(), request.expiresAt(), stableResults);
    idempotency.store(
        subjectId,
        "logistics.cabin-search",
        idempotencyKey,
        fingerprint,
        200,
        response);
    return new AssetService.CreateResult<>(response, false);
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

  @Transactional
  public RentalItemPage availableRentalItems(
      UUID warehouseId, int page, int size, String search) {
    UUID requiredWarehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    if (page < 0 || size < 1 || size > 200) {
      throw new IllegalArgumentException("Invalid page request");
    }
    String needle = normalize(search);
    OffsetDateTime timestamp = now();
    holds.expireDue(timestamp);
    List<RentalItem> available =
        availableItems(requiredWarehouseId, null, timestamp, false).stream()
            .filter(item -> needle.isEmpty() || normalize(item.getNumber()).contains(needle))
            .toList();
    long offset = (long) page * size;
    int from = (int) Math.min(offset, available.size());
    int to = Math.min(from + size, available.size());
    List<RentalItemResponse> content =
        available.subList(from, to).stream().map(item -> assets.rentalItem(item.getId())).toList();
    long pages =
        available.isEmpty() ? 0 : (available.size() + (long) size - 1) / size;
    return new RentalItemPage(content, page, size, available.size(), pages);
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

  /** Returns live held cabins and their contents in the durable hold order without mutating expiry. */
  @Transactional
  public ReplacePresentationHoldsResponse holds(UUID presentationId) {
    return holds(presentationId, null, null);
  }

  /** Applies optional actor ownership checks while returning the same live snapshot contract. */
  @Transactional
  public ReplacePresentationHoldsResponse holds(
      UUID presentationId, UUID actorSubjectId, String actorRole) {
    if ((actorSubjectId == null) != (actorRole == null)
        || (actorRole != null && !ACTOR_ROLES.contains(actorRole))) {
      throw new IllegalArgumentException("Actor identity is invalid");
    }
    OffsetDateTime timestamp = now();
    holds.expireDue(timestamp);
    List<PresentationUnitHold> active =
        holds.findAllByPresentationIdAndStateOrderByCreatedAtAscIdAsc(
            presentationId, PresentationUnitHoldState.ACTIVE);
    if (actorSubjectId != null
        && active.stream()
            .anyMatch(hold -> !actorSubjectId.equals(hold.getCreatedBySubjectId()))) {
      throw new AssetNotFoundException("Presentation holds were not found");
    }
    OffsetDateTime expiry =
        active.stream()
            .map(PresentationUnitHold::getExpiresAt)
            .max(Comparator.naturalOrder())
            .orElse(null);
    boolean customerVisibleCharacteristicsOnly = "CUSTOMER".equals(actorRole);
    return new ReplacePresentationHoldsResponse(
        presentationId,
        expiry,
        active.stream().map(PresentationHoldService::view).toList(),
        active.stream()
            .map(PresentationUnitHold::getRentalItemId)
            .map(rentalItems::findById)
            .map(
                item ->
                    snapshot(
                        item.orElseThrow(
                            () -> new AssetNotFoundException("Rental item was not found")),
                        customerVisibleCharacteristicsOnly))
            .toList());
  }

  /**
   * Idempotently replaces a presentation hold set. Scope and rental-item locks serialize competing
   * hold/movement commands, and cabin snapshots are captured after all requested holds succeed in
   * request order; any failure rolls back both holds and response persistence.
   */
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
    UUID sourceHoldScopeId = request.sourceHoldScopeId();
    if (presentationId.equals(sourceHoldScopeId)) {
      throw new IllegalArgumentException("sourceHoldScopeId must differ from presentationId");
    }
    List<UUID> holdScopes = new ArrayList<>();
    holdScopes.add(presentationId);
    if (sourceHoldScopeId != null) holdScopes.add(sourceHoldScopeId);
    holdScopes.stream()
        .sorted()
        .forEach(scope -> holds.acquireTransactionLock("presentation-holds:" + scope));
    holds.expireDue(timestamp);
    List<PresentationUnitHold> current =
        holds.findAllActiveForUpdate(presentationId, PresentationUnitHoldState.ACTIVE);
    List<PresentationUnitHold> source =
        sourceHoldScopeId == null
            ? List.of()
            : holds.findAllActiveForUpdate(
                sourceHoldScopeId, PresentationUnitHoldState.ACTIVE);
    if (current.stream()
            .anyMatch(
                hold ->
                    !request.actorSubjectId().equals(hold.getCreatedBySubjectId()))
        || source.stream()
            .anyMatch(
                hold ->
                    !request.actorSubjectId().equals(hold.getCreatedBySubjectId()))) {
      throw new AssetNotFoundException("Presentation holds were not found");
    }
    Set<UUID> lockIds = new LinkedHashSet<>(requestedIds);
    current.stream().map(PresentationUnitHold::getRentalItemId).forEach(lockIds::add);
    source.stream().map(PresentationUnitHold::getRentalItemId).forEach(lockIds::add);
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
    Map<UUID, PresentationUnitHold> sourceByItem =
        source.stream()
            .collect(Collectors.toMap(PresentationUnitHold::getRentalItemId, Function.identity()));
    Set<UUID> requested = Set.copyOf(requestedIds);
    Set<UUID> changedAvailability = new LinkedHashSet<>();

    for (PresentationUnitHold hold : current) {
      if (!requested.contains(hold.getRentalItemId()) && hold.release(timestamp)) {
        changedAvailability.add(hold.getRentalItemId());
      }
    }
    for (PresentationUnitHold hold : source) {
      if (!requested.contains(hold.getRentalItemId()) && hold.release(timestamp)) {
        changedAvailability.add(hold.getRentalItemId());
      }
    }
    for (UUID rentalItemId : requestedIds) {
      RentalItem item = items.get(rentalItemId);
      requireRentable(item, request.warehouseId());
      try {
        assets.lockOrderRentalItemForOrder(rentalItemId);
      } catch (AssetConflictException exception) {
        throw conflict("UNIT_NOT_AVAILABLE", "Бытовка выполняет другую складскую операцию");
      }
      assertNoActiveMovementFence(rentalItemId);
      if (orderReservations
          .findByRentalItemIdAndState(rentalItemId, OrderUnitReservationState.ACTIVE)
          .isPresent()) {
        throw conflict("UNIT_ORDER_RESERVED", "Бытовка уже занята заказом");
      }
      PresentationUnitHold existing =
          holds
              .findActiveByRentalItemForUpdate(rentalItemId, PresentationUnitHoldState.ACTIVE)
              .orElse(null);
      PresentationUnitHold own = currentByItem.get(rentalItemId);
      PresentationUnitHold sourceHold = sourceByItem.get(rentalItemId);
      if (sourceHoldScopeId != null && own == null && sourceHold == null) {
        throw conflict(
            "MANUAL_BOOKING_HOLD_MISSING",
            "Временный резерв выбранной бытовки уже истёк");
      }
      if (existing != null
          && existing != own
          && existing != sourceHold
          && !existing.getPresentationId().equals(presentationId)) {
        if (existing.expire(timestamp)) {
          holds.save(existing);
          changedAvailability.add(rentalItemId);
        } else {
          throw conflict(
              "UNIT_PRESENTATION_HELD", "Бытовка уже показана другому клиенту");
        }
      }
      if (own != null) {
        if (own.renew(request.expiresAt(), timestamp)) {
          changedAvailability.add(rentalItemId);
        }
      } else if (sourceHold != null) {
        sourceHold.transferTo(presentationId, request.expiresAt(), timestamp);
        current.add(sourceHold);
      } else {
        current.add(
            PresentationUnitHold.create(
                presentationId,
                rentalItemId,
                request.warehouseId(),
                request.expiresAt(),
                request.actorSubjectId(),
                request.actorRole(),
                timestamp));
        changedAvailability.add(rentalItemId);
      }
    }
    try {
      LinkedHashSet<PresentationUnitHold> changed = new LinkedHashSet<>(current);
      changed.addAll(source);
      holds.saveAllAndFlush(changed);
    } catch (DataIntegrityViolationException exception) {
      throw conflict("UNIT_PRESENTATION_HELD", "Бытовка уже показана другому клиенту");
    }
    List<PresentationUnitHold> active =
        holds.findAllByPresentationIdAndStateOrderByCreatedAtAscIdAsc(
            presentationId, PresentationUnitHoldState.ACTIVE);
    Set<UUID> activeIds =
        active.stream()
            .map(PresentationUnitHold::getRentalItemId)
            .collect(Collectors.toSet());
    if (!activeIds.equals(requested)) {
      throw new IllegalStateException("Stored presentation hold set does not match the request");
    }
    ReplacePresentationHoldsResponse response =
        new ReplacePresentationHoldsResponse(
            presentationId,
            request.expiresAt(),
            active.stream().map(PresentationHoldService::view).toList(),
            requestedIds.stream()
                .map(items::get)
                .map(item -> snapshot(item, "CUSTOMER".equals(request.actorRole())))
                .toList());
    idempotency.store(
        request.actorSubjectId(),
        "presentation-holds.replace",
        idempotencyKey,
        fingerprint,
        200,
        response);
    availabilityInvalidations.publishAfterCommit(
        request.warehouseId(), changedAvailability);
    return new AssetService.CreateResult<>(response, false);
  }

  /** Idempotently releases a presentation scope and returns an intentionally empty cabin snapshot. */
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
    Set<UUID> changedAvailability = new LinkedHashSet<>();
    active.forEach(
        value -> {
          if (value.release(timestamp)) changedAvailability.add(value.getRentalItemId());
        });
    holds.saveAllAndFlush(active);
    ReplacePresentationHoldsResponse response =
        new ReplacePresentationHoldsResponse(presentationId, null, List.of(), List.of());
    idempotency.store(
        request.actorSubjectId(),
        "presentation-holds.release",
        idempotencyKey,
        fingerprint,
        200,
        response);
    Map<UUID, List<UUID>> changesByWarehouse =
        active.stream()
            .filter(value -> changedAvailability.contains(value.getRentalItemId()))
            .collect(
                Collectors.groupingBy(
                    PresentationUnitHold::getWarehouseId,
                    LinkedHashMap::new,
                    Collectors.mapping(
                        PresentationUnitHold::getRentalItemId, Collectors.toList())));
    changesByWarehouse.forEach(availabilityInvalidations::publishAfterCommit);
    return new AssetService.CreateResult<>(response, false);
  }

  /**
   * Converts selected holds and optional authoritative furniture requirements atomically. The
   * shared order-composition lock materializes units before availability/max validation; failures
   * leave every presentation hold active and idempotent replay returns the stored full receipt.
   */
  @Transactional
  public AssetService.CreateResult<ConvertPresentationHoldsResponse> convert(
      UUID idempotencyKey, UUID presentationId, ConvertPresentationHoldsRequest request) {
    ConvertCommand command = new ConvertCommand(presentationId, request);
    String fingerprint = hash(command);
    var replay =
        idempotency.replay(
            request.actorSubjectId(), "presentation-holds.convert", idempotencyKey, fingerprint);
    if (replay.isPresent()) {
      ConvertPresentationHoldsResponse stored =
          read(replay.get(), ConvertPresentationHoldsResponse.class);
      return new AssetService.CreateResult<>(
          stored.equipmentReservations() == null
              ? new ConvertPresentationHoldsResponse(
                  stored.presentationId(),
                  stored.orderId(),
                  stored.reservations(),
                  stored.releasedRentalItemIds(),
                  List.of())
              : stored,
          true);
    }
    orderReservations.acquireTransactionLock("order-composition:" + request.orderId());
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
    List<OrderEquipmentReservationView> equipmentReservations;
    try {
      holds.saveAllAndFlush(current);
      orderReservations.saveAllAndFlush(created);
      equipmentReservations =
          request.units() == null
              ? List.of()
              : orders.replaceEquipmentReservationsInCurrentTransaction(
                  request.orderId(),
                  new ReplaceOrderEquipmentReservationsRequest(
                      request.warehouseId(),
                      request.actorSubjectId(),
                      request.actorRole(),
                      request.units()));
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
                .toList(),
            equipmentReservations);
    idempotency.store(
        request.actorSubjectId(),
        "presentation-holds.convert",
        idempotencyKey,
        fingerprint,
        200,
        response);
    availabilityInvalidations.publishAfterCommit(
        request.warehouseId(),
        current.stream().map(PresentationUnitHold::getRentalItemId).toList());
    return new AssetService.CreateResult<>(response, false);
  }

  private List<RentalItem> availableItems(
      UUID warehouseId, UUID ownHoldScopeId, OffsetDateTime timestamp) {
    return availableItems(warehouseId, ownHoldScopeId, timestamp, true);
  }

  private List<RentalItem> availableItems(
      UUID warehouseId,
      UUID ownHoldScopeId,
      OffsetDateTime timestamp,
      boolean boundSearchPool) {
    List<RentalItem> pool =
        rentalItems.findAllByWarehouseIdAndStatusInOrderByIdentityMatchKeyAscIdAsc(
            warehouseId, RENTABLE_STATUSES);
    if (boundSearchPool && pool.size() > MAX_SEARCH_POOL) {
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

  /**
   * Prevents publication from freezing a cabin whose physical contents are already fenced by an
   * existing equipment movement. The caller owns the canonical rental-item lock, so a concurrent
   * acquire either commits before this check or observes the newly-created presentation hold.
   */
  private void assertNoActiveMovementFence(UUID rentalItemId) {
    Boolean fenced =
        jdbc.queryForObject(
            """
            select exists(
              select 1
              from equipment_allocation_hold movement_hold
              left join equipment_balance source
                on source.id=movement_hold.source_balance_id
              where movement_hold.owner_type in (
                  'LOGISTICS_EQUIPMENT_MOVEMENT',
                  'MAINTENANCE_DISPOSITION_MOVEMENT')
                and movement_hold.state='ACTIVE'
                and movement_hold.expires_at>clock_timestamp()
                and (
                  source.rental_item_id=?
                  or movement_hold.target_rental_item_id=?)
            )
            """,
            Boolean.class,
            rentalItemId,
            rentalItemId);
    if (Boolean.TRUE.equals(fenced)) {
      throw conflict(
          "UNIT_EQUIPMENT_MOVEMENT_FENCED",
          "Наполнение бытовки уже зарезервировано заданием перемещения");
    }
  }

  private AvailableCabin snapshot(RentalItem item) {
    return snapshot(
        item, cabinComposition.compositionsFor(List.of(item)).get(item.getId()));
  }

  private AvailableCabin snapshot(RentalItem item, boolean customerVisibleCharacteristicsOnly) {
    Map<UUID, CabinCompositionService.CabinComposition> compositions =
        customerVisibleCharacteristicsOnly
            ? cabinComposition.customerCompositionsFor(List.of(item))
            : cabinComposition.compositionsFor(List.of(item));
    return snapshot(item, compositions.get(item.getId()));
  }

  private AvailableCabin snapshot(
      RentalItem item, CabinCompositionService.CabinComposition composition) {
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
        composition.characteristics().stream()
            .map(CabinCatalogValueResponse::name)
            .collect(Collectors.joining(", ")),
        source.linoleum(),
        source.passport(),
        source.tags(),
        source.contents(),
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

  private static boolean containsAllCharacteristics(
      List<CabinCatalogValueResponse> actual, List<String> requested) {
    if (requested.isEmpty()) return true;
    Set<String> available =
        actual.stream()
            .map(CabinCatalogValueResponse::name)
            .map(PresentationHoldService::normalizeCharacteristics)
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toSet());
    return available.containsAll(requested);
  }

  private static boolean matchesCustomerQuery(
      RentalItem item,
      CabinCompositionService.CabinComposition composition,
      String query) {
    String needle = normalize(query);
    if (needle.isEmpty()) return true;
    if (needle.equals("линолеум") || needle.equals("с линолеумом")) {
      return Boolean.TRUE.equals(item.getLinoleum());
    }
    if (needle.equals("без линолеума")) {
      return Boolean.FALSE.equals(item.getLinoleum());
    }
    return java.util.stream.Stream.of(
            item.getNumber(),
            name(composition.rentalType()),
            name(composition.finishing()),
            name(composition.dimensions()),
            item.getCategory())
        .filter(Objects::nonNull)
        .map(PresentationHoldService::normalize)
        .anyMatch(value -> value.contains(needle))
        || composition.characteristics().stream()
            .map(CabinCatalogValueResponse::name)
            .map(PresentationHoldService::normalizeCharacteristics)
            .anyMatch(value -> value.contains(needle));
  }

  private static List<String> normalizedCharacteristics(List<String> values) {
    if (values == null) return List.of();
    return values.stream()
        .map(PresentationHoldService::normalizeCharacteristics)
        .distinct()
        .toList();
  }

  private static void validateCustomerCatalogRequest(
      String query,
      String cabinType,
      String finish,
      String dimensions,
      String category,
      List<String> characteristics,
      int page,
      int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Invalid customer cabin catalog page request");
    }
    for (String value : new String[] {query, cabinType, finish, dimensions, category}) {
      if (value != null && value.length() > 255) {
        throw new IllegalArgumentException("Customer cabin catalog filter is too long");
      }
    }
    if (characteristics == null) return;
    if (characteristics.size() > 20
        || new HashSet<>(characteristics).size() != characteristics.size()
        || characteristics.stream()
            .anyMatch(value -> value == null || value.isBlank() || value.length() > 255)) {
      throw new IllegalArgumentException("Customer cabin characteristics filter is invalid");
    }
  }

  private static String name(CabinCatalogValueResponse value) {
    return value == null ? null : value.name();
  }

  private static String trimmedName(CabinCatalogValueResponse value) {
    String name = name(value);
    if (name == null) return null;
    String trimmed = name.trim();
    return trimmed.isEmpty() ? null : trimmed;
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

  /** Fingerprinted full hold-replacement command. */
  private record ReplaceCommand(UUID presentationId, ReplacePresentationHoldsRequest request) {}

  /** Fingerprinted presentation hold-release command. */
  private record ReleaseCommand(UUID presentationId, ActorInput request) {}

  /** Fingerprinted hold-to-order conversion command. */
  private record ConvertCommand(UUID presentationId, ConvertPresentationHoldsRequest request) {}
}
