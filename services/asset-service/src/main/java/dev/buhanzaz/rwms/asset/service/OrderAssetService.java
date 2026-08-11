package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.*;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentBalanceResponse;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.OrderEquipmentReservation;
import dev.buhanzaz.rwms.asset.domain.OrderEquipmentReservationState;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHold;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHoldState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.mapper.OrderAssetResponseMapper;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.OrderEquipmentReservationRepository;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.PresentationUnitHoldRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the asset-side unit and equipment reservation effects requested by a logistics order.
 * It retains asset reservations and fencing locally rather than owning logistics order state.
 */
@Service
@RequiredArgsConstructor
public class OrderAssetService {
  private static final UUID EXPIRATION_SUBJECT_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000000");
  private static final String EXPIRATION_ROLE = "SYSTEM_ADMIN";
  private static final Set<RentalItemStatus> RESERVABLE_STATUSES =
      Set.of(RentalItemStatus.FREE);
  private static final Set<RentalItemStatus> ORDER_EDITABLE_STATUSES =
      Set.of(RentalItemStatus.FREE, RentalItemStatus.BOOKED);

  private final RentalItemRepository rentalItems;
  private final OrderUnitReservationRepository reservations;
  private final PresentationUnitHoldRepository presentationHolds;
  private final OrderEquipmentReservationRepository equipmentReservations;
  private final EquipmentCatalogItemRepository equipmentCatalog;
  private final AssetService assets;
  private final AssetIdempotencyStore idempotency;
  private final OrderAssetResponseMapper responses;
  private final RentalAvailabilityInvalidationPublisher availabilityInvalidations;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  @Transactional
  public OrderUnitCandidatePage candidates(
      UUID orderId, UUID warehouseId, int page, int size, String search) {
    expireDueDraftReservations(now());
    requirePage(page, size);
    String normalizedSearch = search == null ? "" : search.trim().toUpperCase(Locale.ROOT);
    var result =
        rentalItems.findOrderCandidates(
            orderId,
            warehouseId,
            RESERVABLE_STATUSES,
            OrderUnitReservationState.ACTIVE,
            PresentationUnitHoldState.ACTIVE,
            now(),
            normalizedSearch,
            PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "number", "id")));
    Map<UUID, OrderUnitReservation> current =
        reservations
            .findAllByOrderIdAndStateOrderByCreatedAtAscIdAsc(
                orderId, OrderUnitReservationState.ACTIVE)
            .stream()
            .collect(Collectors.toMap(OrderUnitReservation::getRentalItemId, Function.identity()));
    List<OrderUnitCandidate> values =
        result.getContent().stream()
            .map(
                item -> {
                  OrderUnitReservation reservation = current.get(item.getId());
                  return new OrderUnitCandidate(
                      reservation == null ? null : reservation.getId(),
                      reservation != null,
                      toOrderRentalItem(item));
                })
            .toList();
    return new OrderUnitCandidatePage(
        values,
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  @Transactional
  public List<OrderUnitReservationView> units(UUID orderId) {
    expireDueDraftReservations(now());
    return reservations
        .findAllByOrderIdAndStateOrderByCreatedAtAscIdAsc(
            orderId, OrderUnitReservationState.ACTIVE)
        .stream()
        .map(reservation -> view(reservation, false))
        .toList();
  }

  /**
   * Idempotently reserves one cabin while sharing the order-composition advisory lock with every
   * furniture and replacement command, preventing a furniture receipt for a stale unit set.
   */
  @Transactional
  public AssetService.CreateResult<OrderUnitReservationView> reserve(
      UUID idempotencyKey, UUID orderId, ReserveOrderUnitRequest request) {
    ReserveCommand command = new ReserveCommand(orderId, request);
    String fingerprint = hash(command);
    var replay =
        idempotency.replay(
            request.actorSubjectId(), "order-unit.reserve", idempotencyKey, fingerprint);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          withReplay(read(replay.get(), OrderUnitReservationView.class)), true);
    }

    OffsetDateTime timestamp = now();
    validateDraftReservationExpiry(request.draftReservationExpiresAt(), timestamp);
    expireDueDraftReservations(timestamp);
    acquireOrderCompositionLock(orderId);
    try {
      assets.lockOrderRentalItemForOrder(request.rentalItemId());
    } catch (AssetConflictException exception) {
      throw conflict("UNIT_NOT_AVAILABLE", "Бытовка выполняет другую складскую операцию");
    }
    RentalItem item =
        rentalItems
            .findByIdForUpdate(request.rentalItemId())
            .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    presentationHolds.expireDue(timestamp);
    PresentationUnitHold activePresentationHold =
        presentationHolds
            .findActiveByRentalItemForUpdate(
                item.getId(), PresentationUnitHoldState.ACTIVE)
            .orElse(null);
    if (activePresentationHold != null && activePresentationHold.isLiveAt(timestamp)) {
      throw conflict(
          "UNIT_PRESENTATION_HELD", "Бытовка временно зарезервирована для клиента");
    }
    OrderUnitReservation existing =
        reservations
            .findByRentalItemIdAndState(item.getId(), OrderUnitReservationState.ACTIVE)
            .orElse(null);
    if (existing != null) {
      if (existing.getOrderId().equals(orderId)
          && item.getWarehouseId().equals(request.warehouseId())) {
        boolean changed =
            existing.updateClientProjection(request.clientId(), request.tenantSnapshot());
        changed |= existing.synchronizeDraftReservationExpiry(request.draftReservationExpiresAt());
        if (changed) {
          reservations.saveAndFlush(existing);
        }
        if (item.getStatus() == RentalItemStatus.FREE
            || item.getStatus() == RentalItemStatus.BOOKED) {
          assets.bookOrderRentalItem(item.getId());
        }
        OrderUnitReservationView response = view(existing, true);
        idempotency.store(
            request.actorSubjectId(),
            "order-unit.reserve",
            idempotencyKey,
            fingerprint,
            200,
            response);
        return new AssetService.CreateResult<>(response, true);
      }
      if (existing.getOrderId().equals(orderId)) {
        throw conflict("UNIT_WAREHOUSE_MISMATCH", "Бытовка находится на другом складе");
      }
      throw conflict(
          "UNIT_ALREADY_RESERVED", "Бытовка уже занята другим активным заказом");
    }
    if (!item.getWarehouseId().equals(request.warehouseId())) {
      throw conflict("UNIT_WAREHOUSE_MISMATCH", "Бытовка находится на другом складе");
    }
    if (!RESERVABLE_STATUSES.contains(item.getStatus())) {
      throw conflict("UNIT_NOT_AVAILABLE", "Бытовка больше не доступна для заказа");
    }

    try {
      OrderUnitReservation saved =
          reservations.saveAndFlush(
              OrderUnitReservation.create(
                  orderId,
                  item.getId(),
                  item.getWarehouseId(),
                  request.clientId(),
                  request.tenantSnapshot(),
                  request.draftReservationExpiresAt(),
                  request.actorSubjectId(),
                  request.actorRole()));
      assets.bookOrderRentalItem(item.getId());
      OrderUnitReservationView response = view(saved, false);
      idempotency.store(
          request.actorSubjectId(),
          "order-unit.reserve",
          idempotencyKey,
          fingerprint,
          201,
          response);
      return new AssetService.CreateResult<>(response, false);
    } catch (DataIntegrityViolationException exception) {
      throw new OrderUnitReservationConflictException(
          "UNIT_ALREADY_RESERVED", "Бытовка уже занята другим активным заказом");
    }
  }

  /** Releases one cabin under the same order-composition and rental-item lock order. */
  @Transactional
  public AssetService.CreateResult<OrderUnitReservationView> release(
      UUID idempotencyKey, UUID orderId, UUID rentalItemId, OrderActorRequest actor) {
    ReleaseCommand command = new ReleaseCommand(orderId, rentalItemId, actor);
    String fingerprint = hash(command);
    var replay =
        idempotency.replay(
            actor.actorSubjectId(), "order-unit.release", idempotencyKey, fingerprint);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          withReplay(read(replay.get(), OrderUnitReservationView.class)), true);
    }
    acquireOrderCompositionLock(orderId);
    try {
      assets.lockOrderRentalItemForOrder(rentalItemId);
    } catch (AssetConflictException exception) {
      throw conflict("UNIT_NOT_EDITABLE", "Бытовка выполняет другую складскую операцию");
    }
    OrderUnitReservation reservation =
        reservations
            .findActiveForUpdate(orderId, rentalItemId, OrderUnitReservationState.ACTIVE)
            .orElseThrow(() -> new AssetNotFoundException("Order unit reservation was not found"));
    reservation.release(actor.actorSubjectId(), actor.actorRole());
    OrderUnitReservation released = reservations.saveAndFlush(reservation);
    assets.releaseOrderBooking(rentalItemId);
    OrderUnitReservationView response = view(released, false);
    idempotency.store(
        actor.actorSubjectId(),
        "order-unit.release",
        idempotencyKey,
        fingerprint,
        200,
        response);
    return new AssetService.CreateResult<>(response, false);
  }

  /**
   * Atomically replaces every requested cabin pair, including exact furniture source holds, before
   * any old reservation is released. Pair order is preserved in the one idempotent batch receipt;
   * selected presentation holds convert and every unselected alternative releases in the same
   * transaction. Old cabins intentionally remain BOOKED until maintenance acquires them after the
   * order reservation and any live replacement furniture hold are gone.
   */
  @Transactional
  public AssetService.CreateResult<OrderUnitsReplacementReceipt> replaceUnits(
      UUID idempotencyKey, UUID orderId, ReplaceOrderUnitsRequest request) {
    ReplaceUnitsCommand command = new ReplaceUnitsCommand(orderId, request);
    String fingerprint = hash(command);
    var replay =
        idempotency.replay(
            request.actorSubjectId(), "order-unit.replace", idempotencyKey, fingerprint);
    if (replay.isPresent()) {
      OrderUnitsReplacementReceipt stored =
          read(replay.get(), OrderUnitsReplacementReceipt.class);
      return new AssetService.CreateResult<>(
          new OrderUnitsReplacementReceipt(stored.replacements(), true), true);
    }

    if (request.replacements() == null || request.replacements().isEmpty()) {
      throw new IllegalArgumentException("At least one cabin replacement is required");
    }
    if (request.units() == null || request.units().isEmpty()) {
      throw new IllegalArgumentException("Post-replacement order units are required");
    }
    List<OrderUnitReplacement> pairs = List.copyOf(request.replacements());
    Set<UUID> oldIds = new java.util.LinkedHashSet<>();
    Set<UUID> replacementIds = new java.util.LinkedHashSet<>();
    for (OrderUnitReplacement pair : pairs) {
      if (!oldIds.add(pair.rentalItemId())
          || !replacementIds.add(pair.replacementRentalItemId())) {
        throw new IllegalArgumentException("Replacement cabin identities must be unique");
      }
    }
    if (!java.util.Collections.disjoint(oldIds, replacementIds)) {
      throw new IllegalArgumentException("Old and replacement cabin sets must be disjoint");
    }

    OffsetDateTime timestamp = now();
    expireDueDraftReservations(timestamp);
    acquireOrderCompositionLock(orderId);
    List<PresentationUnitHold> presentationScopeHolds;
    if (request.presentationId() == null) {
      presentationHolds.expireDue(timestamp);
      presentationScopeHolds = List.of();
    } else {
      presentationHolds.acquireTransactionLock(
          "presentation-holds:" + request.presentationId());
      presentationHolds.expireDue(timestamp);
      presentationScopeHolds =
          presentationHolds.findAllActiveForUpdate(
              request.presentationId(), PresentationUnitHoldState.ACTIVE);
    }
    Set<UUID> itemIdsToLock = new java.util.LinkedHashSet<>(oldIds);
    itemIdsToLock.addAll(replacementIds);
    presentationScopeHolds.stream()
        .map(PresentationUnitHold::getRentalItemId)
        .forEach(itemIdsToLock::add);
    List<UUID> lockedItemIds =
        itemIdsToLock.stream().sorted().toList();
    try {
      lockedItemIds.forEach(assets::lockOrderRentalItemForOrder);
    } catch (AssetConflictException exception) {
      throw conflict("REPLACEMENT_UNIT_NOT_AVAILABLE", "Бытовка выполняет другую операцию");
    }
    Map<UUID, RentalItem> lockedItems =
        rentalItems.findAllByIdInForUpdate(lockedItemIds).stream()
            .collect(Collectors.toMap(RentalItem::getId, Function.identity()));
    if (lockedItems.size() != lockedItemIds.size()) {
      throw new AssetNotFoundException("Rental item was not found");
    }
    for (RentalItem item : lockedItems.values()) {
      if (!request.warehouseId().equals(item.getWarehouseId())) {
        throw conflict("UNIT_WAREHOUSE_MISMATCH", "Бытовки находятся на разных складах");
      }
    }
    for (UUID oldId : oldIds) {
      if (lockedItems.get(oldId).getStatus() != RentalItemStatus.BOOKED) {
        throw conflict(
            "REPLACEMENT_UNIT_NOT_EDITABLE",
            "Заменять можно только забронированную бытовку до начала отгрузки");
      }
    }
    for (UUID replacementId : replacementIds) {
      if (lockedItems.get(replacementId).getStatus() != RentalItemStatus.FREE) {
        throw conflict("REPLACEMENT_UNIT_NOT_AVAILABLE", "Заменяющая бытовка недоступна");
      }
      if (reservations
          .findByRentalItemIdAndState(replacementId, OrderUnitReservationState.ACTIVE)
          .isPresent()) {
        throw conflict(
            "REPLACEMENT_UNIT_ALREADY_RESERVED",
            "Заменяющая бытовка уже занята другим заказом");
      }
    }

    List<OrderUnitReservation> active =
        reservations.findAllActiveForUpdate(orderId, OrderUnitReservationState.ACTIVE);
    Map<UUID, OrderUnitReservation> activeByItem =
        active.stream()
            .collect(Collectors.toMap(OrderUnitReservation::getRentalItemId, Function.identity()));
    if (!activeByItem.keySet().containsAll(oldIds)) {
      throw new AssetNotFoundException("Order unit reservation was not found");
    }
    Map<UUID, Map<UUID, Long>> requiredByUnit = requirementsByUnit(request.units());
    Set<UUID> expectedUnitIds = new java.util.LinkedHashSet<>(activeByItem.keySet());
    expectedUnitIds.removeAll(oldIds);
    expectedUnitIds.addAll(replacementIds);
    if (!requiredByUnit.keySet().equals(expectedUnitIds)) {
      throw conflict(
          "ORDER_UNIT_RESERVATION_MISMATCH",
          "Состав бытовок заказа изменился, обновите замену и повторите действие");
    }
    Map<UUID, Long> orderRequirements = aggregateRequirements(requiredByUnit.values());
    assertOrderReservations(orderId, request.warehouseId(), orderRequirements);
    Map<UUID, EquipmentCatalogItem> catalog = catalogItems(orderRequirements.keySet());
    assertMaximumPerCabin(requiredByUnit, catalog);

    Map<UUID, PresentationUnitHold> presentationScopeByItem =
        presentationScopeHolds.stream()
            .collect(
                Collectors.toMap(
                    PresentationUnitHold::getRentalItemId,
                    Function.identity(),
                    (left, right) -> {
                      throw new IllegalStateException(
                          "Duplicate active presentation hold for a rental item");
                    },
                    LinkedHashMap::new));
    List<PreparedUnitReplacement> prepared = new ArrayList<>();
    Set<UUID> movementIds = new java.util.HashSet<>();
    Set<UUID> lineIds = new java.util.HashSet<>();
    Set<UUID> sourceBalanceIds = new java.util.HashSet<>();
    for (OrderUnitReplacement pair : pairs) {
      RentalItem oldUnit = lockedItems.get(pair.rentalItemId());
      RentalItem replacement = lockedItems.get(pair.replacementRentalItemId());
      PresentationUnitHold presentationHold =
          request.presentationId() == null
              ? presentationHolds
                  .findActiveByRentalItemForUpdate(
                      replacement.getId(), PresentationUnitHoldState.ACTIVE)
                  .orElse(null)
              : presentationScopeByItem.get(replacement.getId());
      if (request.presentationId() != null) {
        if (presentationHold == null
            || !presentationHold.isLiveAt(timestamp)
            || !request.presentationId().equals(presentationHold.getPresentationId())) {
          throw conflict(
              "REPLACEMENT_PRESENTATION_HOLD_REQUIRED",
              "Для каждой заменяющей бытовки нужен активный резерв этого представления");
        }
      } else if (presentationHold != null && presentationHold.isLiveAt(timestamp)) {
        throw conflict(
            "REPLACEMENT_UNIT_PRESENTATION_HELD",
            "Заменяющая бытовка временно зарезервирована для клиента");
      }
      Map<UUID, Long> desired = requiredByUnit.get(replacement.getId());
      List<OrderFurnitureMovementPlanLine> exactPlan =
          directReplacementMovementLines(oldUnit, replacement, desired, catalog);
      if (exactPlan.isEmpty() && pair.movement() != null) {
        throw conflict(
            "REPLACEMENT_MOVEMENT_NOT_REQUIRED",
            "Для текущего наполнения заменяемой бытовки перемещение не требуется");
      }
      if (!exactPlan.isEmpty() && pair.movement() == null) {
        throw conflict(
            "REPLACEMENT_MOVEMENT_REQUIRED",
            "Мебель из заменяемой бытовки должна быть защищена заданием перемещения");
      }
      if (pair.movement() != null) {
        if (!movementIds.add(pair.movement().movementId())) {
          throw new IllegalArgumentException("Replacement movement identities must be unique");
        }
        for (OrderUnitReplacementMovementLine line : pair.movement().lines()) {
          if (!lineIds.add(line.lineId()) || !sourceBalanceIds.add(line.sourceBalanceId())) {
            throw new IllegalArgumentException(
                "Replacement movement source and line identities must be unique across pairs");
          }
        }
      }
      prepared.add(
          new PreparedUnitReplacement(
              pair,
              oldUnit,
              replacement,
              activeByItem.get(oldUnit.getId()),
              presentationHold,
              exactPlan,
              rentalItemContents(replacement).equals(desired) && exactPlan.isEmpty()));
    }

    List<List<dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsEquipmentMovementReservationResponse>>
        heldByPair = new ArrayList<>();
    List<Integer> movementPairIndexes = new ArrayList<>();
    List<AssetService.ReplacementMovementRequest> movementRequests = new ArrayList<>();
    for (int index = 0; index < prepared.size(); index++) {
      heldByPair.add(List.of());
      PreparedUnitReplacement value = prepared.get(index);
      if (!value.exactPlan().isEmpty()) {
        movementPairIndexes.add(index);
        movementRequests.add(
            new AssetService.ReplacementMovementRequest(
                orderId,
                value.oldReservation().getId(),
                value.replacement().getId(),
                request.units(),
                value.pair().movement(), value.exactPlan()));
      }
    }
    List<List<dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsEquipmentMovementReservationResponse>>
        movementReservations =
            assets.reserveReplacementMovements(idempotencyKey, movementRequests);
    for (int index = 0; index < movementPairIndexes.size(); index++) {
      heldByPair.set(movementPairIndexes.get(index), movementReservations.get(index));
    }

    List<PresentationUnitHold> consumedHolds = new ArrayList<>();
    List<OrderUnitReservation> changedReservations = new ArrayList<>();
    List<OrderUnitReservation> replacements = new ArrayList<>();
    for (PreparedUnitReplacement value : prepared) {
      if (value.presentationHold() != null) {
        value.presentationHold().convert(orderId, timestamp);
        consumedHolds.add(value.presentationHold());
      }
      OrderUnitReservation replacementReservation =
          OrderUnitReservation.replace(
              value.oldReservation(),
              value.replacement().getId(),
              request.actorSubjectId(),
              request.actorRole());
      value.oldReservation().release(request.actorSubjectId(), request.actorRole());
      changedReservations.add(value.oldReservation());
      changedReservations.add(replacementReservation);
      replacements.add(replacementReservation);
    }
    if (request.presentationId() != null) {
      Set<UUID> selectedReplacementIds = Set.copyOf(replacementIds);
      presentationScopeHolds.stream()
          .filter(hold -> !selectedReplacementIds.contains(hold.getRentalItemId()))
          .forEach(hold -> hold.release(timestamp));
      presentationHolds.saveAllAndFlush(presentationScopeHolds);
    } else if (!consumedHolds.isEmpty()) {
      presentationHolds.saveAllAndFlush(consumedHolds);
    }
    reservations.saveAllAndFlush(changedReservations);
    replacements.forEach(value -> assets.bookOrderRentalItem(value.getRentalItemId()));

    List<OrderUnitReplacementReceipt> pairReceipts = new ArrayList<>();
    for (int index = 0; index < prepared.size(); index++) {
      PreparedUnitReplacement value = prepared.get(index);
      pairReceipts.add(
          new OrderUnitReplacementReceipt(
              view(value.oldReservation(), false),
              view(replacements.get(index), false),
              heldByPair.get(index),
              value.contentReady()));
    }
    OrderUnitsReplacementReceipt response =
        new OrderUnitsReplacementReceipt(List.copyOf(pairReceipts), false);
    idempotency.store(
        request.actorSubjectId(),
        "order-unit.replace",
        idempotencyKey,
        fingerprint,
        200,
        response);
    if (!presentationScopeHolds.isEmpty()) {
      availabilityInvalidations.publishAfterCommit(
          request.warehouseId(),
          presentationScopeHolds.stream()
              .map(PresentationUnitHold::getRentalItemId)
              .toList());
    }
    return new AssetService.CreateResult<>(response, false);
  }

  /** Releases the complete order cabin set atomically under the shared composition lock. */
  @Transactional
  public AssetService.CreateResult<List<OrderUnitReservationView>> releaseAll(
      UUID idempotencyKey, UUID orderId, OrderActorRequest actor) {
    ReleaseAllCommand command = new ReleaseAllCommand(orderId, actor);
    String fingerprint = hash(command);
    var replay =
        idempotency.replay(
            actor.actorSubjectId(), "order-unit.release-all", idempotencyKey, fingerprint);
    if (replay.isPresent()) {
      List<OrderUnitReservationView> response =
          read(replay.get(), ReservationListReceipt.class).reservations().stream()
              .map(OrderAssetService::withReplay)
              .toList();
      return new AssetService.CreateResult<>(response, true);
    }
    acquireOrderCompositionLock(orderId);
    List<OrderUnitReservation> active =
        reservations.findAllByOrderIdAndStateOrderByCreatedAtAscIdAsc(
            orderId, OrderUnitReservationState.ACTIVE);
    active.stream()
        .map(OrderUnitReservation::getRentalItemId)
        .distinct()
        .sorted()
        .forEach(assets::lockOrderRentalItemForOrder);
    active =
        reservations.findAllActiveForUpdate(orderId, OrderUnitReservationState.ACTIVE);
    active.forEach(value -> value.release(actor.actorSubjectId(), actor.actorRole()));
    List<OrderUnitReservation> released = reservations.saveAllAndFlush(active);
    released.forEach(value -> assets.releaseOrderBooking(value.getRentalItemId()));
    List<OrderUnitReservationView> response = released.stream()
        .map(value -> view(value, false))
        .toList();
    idempotency.store(
        actor.actorSubjectId(),
        "order-unit.release-all",
        idempotencyKey,
        fingerprint,
        200,
        new ReservationListReceipt(response));
    return new AssetService.CreateResult<>(response, false);
  }

  @Transactional
  public boolean hasActiveUnits(UUID orderId) {
    expireDueDraftReservations(now());
    return reservations.existsByOrderIdAndState(orderId, OrderUnitReservationState.ACTIVE);
  }

  @Scheduled(fixedDelayString = "${rwms.asset.order-reservation.expiration-sweep-delay:PT1M}")
  @Transactional
  public void releaseExpiredDraftReservations() {
    expireDueDraftReservations(now());
  }

  /**
   * Atomically replaces the order-wide allocation from authoritative per-unit requirements. The
   * shared composition lock, sorted equipment locks, per-cabin maxima and outstanding-only global
   * capacity prevent both stale unit attribution and cross-order overbooking. Physical source
   * selection remains in the existing worker movement workflow.
   */
  @Transactional
  public AssetService.CreateResult<List<OrderEquipmentReservationView>> replaceEquipmentReservations(
      UUID idempotencyKey, UUID orderId, ReplaceOrderEquipmentReservationsRequest request) {
    EquipmentReservationCommand command = new EquipmentReservationCommand(orderId, request);
    String fingerprint = hash(command);
    var replay =
        idempotency.replay(
            request.actorSubjectId(), "order-equipment.replace", idempotencyKey, fingerprint);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          read(replay.get(), EquipmentReservationListReceipt.class).reservations(), true);
    }

    List<OrderEquipmentReservationView> response =
        replaceEquipmentReservationsInCurrentTransaction(orderId, request);
    idempotency.store(
        request.actorSubjectId(),
        "order-equipment.replace",
        idempotencyKey,
        fingerprint,
        200,
        new EquipmentReservationListReceipt(response));
    return new AssetService.CreateResult<>(response, false);
  }

  /**
   * Joins an already-open presentation conversion or replacement transaction to the same
   * order-composition and sorted equipment locks before replacing aggregate reservations.
   */
  List<OrderEquipmentReservationView> replaceEquipmentReservationsInCurrentTransaction(
      UUID orderId, ReplaceOrderEquipmentReservationsRequest request) {
    acquireOrderCompositionLock(orderId);
    Map<UUID, Map<UUID, Long>> requiredByUnit = requirementsByUnit(request.units());
    assertRequestedUnitsBelongToOrder(orderId, request.warehouseId(), requiredByUnit.keySet());
    Map<UUID, Long> required = aggregateRequirements(requiredByUnit.values());
    Set<UUID> lockEquipmentIds = new java.util.LinkedHashSet<>(required.keySet());
    equipmentReservations
        .findAllByOrderIdAndStateOrderByEquipmentId(
            orderId, OrderEquipmentReservationState.ACTIVE)
        .stream()
        .map(OrderEquipmentReservation::getEquipmentId)
        .forEach(lockEquipmentIds::add);
    List<UUID> equipmentIds = lockEquipmentIds.stream().sorted().toList();
    for (UUID equipmentId : equipmentIds) {
      equipmentReservations.acquireTransactionLock(
          "order-equipment:" + request.warehouseId() + ":" + equipmentId);
    }

    List<OrderEquipmentReservation> current =
        equipmentReservations.findAllActiveForUpdate(orderId, OrderEquipmentReservationState.ACTIVE);
    Map<UUID, OrderEquipmentReservation> currentByEquipment =
        current.stream()
            .collect(
                Collectors.toMap(
                    OrderEquipmentReservation::getEquipmentId,
                    Function.identity(),
                    (left, right) -> {
                      throw new IllegalStateException("Duplicate active order equipment reservation");
                    },
                    LinkedHashMap::new));

    Map<UUID, EquipmentCatalogItem> catalog = catalogItems(required.keySet());
    for (EquipmentCatalogItem item : catalog.values()) {
      if (!item.isActive()) {
        throw conflict("EQUIPMENT_NOT_AVAILABLE", "Позиция дополнительного оборудования отключена");
      }
    }
    assertMaximumPerCabin(requiredByUnit, catalog);

    Map<UUID, Long> availableAfter = new LinkedHashMap<>();
    for (UUID equipmentId : required.keySet().stream().sorted().toList()) {
      AssetService.OrderEquipmentCapacity capacity =
          assets.orderEquipmentCapacity(equipmentId, request.warehouseId(), orderId);
      long requested = required.get(equipmentId);
      long satisfiedByOrderCabins = Math.min(requested, capacity.orderPhysicalQuantity());
      long requestedOutstanding = Math.subtractExact(requested, satisfiedByOrderCabins);
      long freeForOrder =
          Math.max(
              0,
              Math.subtractExact(
                  capacity.allocatableQuantity(), capacity.otherOrderOutstandingQuantity()));
      if (requestedOutstanding > freeForOrder) {
        throw conflict(
            "INSUFFICIENT_EQUIPMENT",
            "Недостаточно доступного дополнительного оборудования для заказа");
      }
      availableAfter.put(
          equipmentId, Math.subtractExact(freeForOrder, requestedOutstanding));
    }

    List<OrderEquipmentReservation> changed = new ArrayList<>();
    for (OrderEquipmentReservation reservation : current) {
      Long nextQuantity = required.remove(reservation.getEquipmentId());
      if (nextQuantity == null) {
        if (reservation.release()) {
          changed.add(reservation);
        }
      } else if (reservation.getWarehouseId().equals(request.warehouseId())) {
        if (reservation.changeQuantity(nextQuantity)) {
          changed.add(reservation);
        }
      } else {
        throw conflict(
            "ORDER_WAREHOUSE_MISMATCH",
            "Склад заказа нельзя изменить, пока в нём есть резерв мебели");
      }
    }
    for (Map.Entry<UUID, Long> entry : required.entrySet()) {
      OrderEquipmentReservation created =
          OrderEquipmentReservation.create(orderId, entry.getKey(), request.warehouseId(), entry.getValue());
      changed.add(created);
      currentByEquipment.put(entry.getKey(), created);
    }
    if (!changed.isEmpty()) {
      equipmentReservations.saveAllAndFlush(changed);
    }

    return
        currentByEquipment.values().stream()
            .filter(value -> value.getState() == OrderEquipmentReservationState.ACTIVE)
            .sorted(
                Comparator.<OrderEquipmentReservation, String>comparing(
                    value -> catalog.get(value.getEquipmentId()).getName())
                    .thenComparing(OrderEquipmentReservation::getEquipmentId))
            .map(
                value ->
                    responses.toOrderEquipmentReservation(
                        value,
                        catalog.get(value.getEquipmentId()),
                        availableAfter.getOrDefault(value.getEquipmentId(), 0L)))
            .toList();
  }

  /**
   * Calculates only the physical delta for one cabin. Additions first use physical surplus from
   * another active cabin of the same order, then legacy allocatable sources; surplus goes back to
   * stock.
   */
  @Transactional
  public OrderFurnitureMovementPlan furnitureMovementPlan(
      UUID orderId, OrderFurnitureMovementPlanRequest request) {
    acquireOrderCompositionLock(orderId);
    Map<UUID, Long> desired = requirementsByEquipment(request.requirements());
    Map<UUID, Map<UUID, Long>> requiredByUnit = requirementsByUnit(request.units());
    Map<UUID, Long> orderRequired = aggregateRequirements(requiredByUnit.values());
    for (UUID equipmentId : orderRequired.keySet().stream().sorted().toList()) {
      equipmentReservations.acquireTransactionLock(
          "order-equipment:" + request.warehouseId() + ":" + equipmentId);
    }
    assertOrderReservations(orderId, request.warehouseId(), orderRequired);

    if (request.replacementForRentalItemId() != null) {
      return replacementFurnitureMovementPlan(orderId, request, desired, requiredByUnit);
    }
    assertRequestedUnitsBelongToOrder(orderId, request.warehouseId(), requiredByUnit.keySet());
    if (!desired.equals(requiredByUnit.get(request.rentalItemId()))) {
      throw conflict(
          "ORDER_UNIT_REQUIREMENTS_MISMATCH",
          "Наполнение бытовки изменилось, обновите план перемещения");
    }

    try {
      assets.lockOrderRentalItemForOrder(request.rentalItemId());
    } catch (AssetConflictException exception) {
      throw conflict("UNIT_NOT_EDITABLE", "Бытовка временно недоступна для комплектации");
    }
    RentalItem unit =
        rentalItems
            .findByIdForUpdate(request.rentalItemId())
            .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    if (!request.warehouseId().equals(unit.getWarehouseId())) {
      throw conflict("UNIT_WAREHOUSE_MISMATCH", "Бытовка находится на другом складе");
    }
    if (!ORDER_EDITABLE_STATUSES.contains(unit.getStatus())) {
      throw conflict("UNIT_NOT_EDITABLE", "Наполнение этой бытовки нельзя изменить");
    }
    reservations
        .findActiveForUpdate(orderId, unit.getId(), OrderUnitReservationState.ACTIVE)
        .orElseThrow(() -> new AssetNotFoundException("Order unit reservation was not found"));

    Map<UUID, Long> actual =
        toOrderRentalItem(unit).contents().stream()
            .collect(
                Collectors.toMap(
                    OrderEquipmentContent::equipmentId,
                    OrderEquipmentContent::quantity,
                    Math::addExact,
                    LinkedHashMap::new));
    Set<UUID> equipmentIds = new java.util.LinkedHashSet<>();
    equipmentIds.addAll(desired.keySet());
    equipmentIds.addAll(actual.keySet());
    Map<UUID, EquipmentCatalogItem> catalog = catalogItems(equipmentIds);
    List<OrderFurnitureMovementPlanLine> lines = new ArrayList<>();
    for (UUID equipmentId : equipmentIds.stream().sorted().toList()) {
      long desiredQuantity = desired.getOrDefault(equipmentId, 0L);
      long actualQuantity = actual.getOrDefault(equipmentId, 0L);
      if (desiredQuantity == actualQuantity) {
        continue;
      }
      EquipmentCatalogItem equipment = catalog.get(equipmentId);
      var totals = assets.equipmentTotals(equipmentId, request.warehouseId());
      if (desiredQuantity > actualQuantity) {
        long activeInbound = activeOrderInbound(orderId, unit.getId(), equipmentId);
        long outstanding =
            Math.max(
                0,
                Math.subtractExact(
                    Math.subtractExact(desiredQuantity, actualQuantity), activeInbound));
        for (OrderSurplusSource source :
            sameOrderSurplusSources(
                totals.balances(), unit.getId(), equipmentId, requiredByUnit)) {
          if (outstanding == 0) {
            break;
          }
          long quantity = Math.min(outstanding, source.availableQuantity());
          if (quantity < 1) {
            continue;
          }
          lines.add(
              movementLine(
                  equipment,
                  source.balance(),
                  request.warehouseId(),
                  unit.getId(),
                  BalanceLocationKind.CABIN_NON_RENTED,
                  quantity));
          outstanding = Math.subtractExact(outstanding, quantity);
        }
        for (EquipmentBalanceResponse source : eligibleSources(totals.balances(), unit.getId())) {
          if (outstanding == 0) {
            break;
          }
          long quantity = Math.min(outstanding, source.availableStock());
          if (quantity < 1) {
            continue;
          }
          lines.add(
              movementLine(
                  equipment,
                  source,
                  request.warehouseId(),
                  unit.getId(),
                  BalanceLocationKind.CABIN_NON_RENTED,
                  quantity));
          outstanding = Math.subtractExact(outstanding, quantity);
        }
        if (outstanding > 0) {
          throw conflict(
              "INSUFFICIENT_EQUIPMENT_SOURCE",
              "Не удалось подобрать фактический источник дополнительного оборудования");
        }
      } else {
        EquipmentBalanceResponse source =
            totals.balances().stream()
                .filter(
                    balance ->
                        unit.getId().equals(balance.rentalItemId())
                            && balance.locationKind() == BalanceLocationKind.CABIN_NON_RENTED)
                .findFirst()
                .orElseThrow(
                    () ->
                        conflict(
                            "EQUIPMENT_QUANTITY_CONFLICT",
                            "Фактическое наполнение бытовки изменилось"));
        lines.add(
            movementLine(
                equipment,
                source,
                request.warehouseId(),
                null,
                BalanceLocationKind.STOCK,
                Math.subtractExact(actualQuantity, desiredQuantity)));
      }
    }
    return new OrderFurnitureMovementPlan(orderId, unit.getId(), unit.getNumber(), List.copyOf(lines));
  }

  private OrderFurnitureMovementPlan replacementFurnitureMovementPlan(
      UUID orderId,
      OrderFurnitureMovementPlanRequest request,
      Map<UUID, Long> desired,
      Map<UUID, Map<UUID, Long>> requiredByUnit) {
    UUID oldRentalItemId = request.replacementForRentalItemId();
    if (oldRentalItemId.equals(request.rentalItemId())) {
      throw new IllegalArgumentException("Replacement cabin must differ from the reserved cabin");
    }
    List<UUID> itemIds = List.of(oldRentalItemId, request.rentalItemId()).stream().sorted().toList();
    itemIds.forEach(assets::lockOrderRentalItemForOrder);
    Map<UUID, RentalItem> items =
        rentalItems.findAllByIdInForUpdate(itemIds).stream()
            .collect(Collectors.toMap(RentalItem::getId, Function.identity()));
    if (items.size() != 2) {
      throw new AssetNotFoundException("Rental item was not found");
    }
    RentalItem oldUnit = items.get(oldRentalItemId);
    RentalItem replacement = items.get(request.rentalItemId());
    if (!request.warehouseId().equals(oldUnit.getWarehouseId())
        || !request.warehouseId().equals(replacement.getWarehouseId())) {
      throw conflict("UNIT_WAREHOUSE_MISMATCH", "Бытовки находятся на разных складах");
    }
    reservations
        .findActiveForUpdate(orderId, oldRentalItemId, OrderUnitReservationState.ACTIVE)
        .orElseThrow(() -> new AssetNotFoundException("Order unit reservation was not found"));
    if (reservations
            .findByRentalItemIdAndState(replacement.getId(), OrderUnitReservationState.ACTIVE)
            .isPresent()
        || replacement.getStatus() != RentalItemStatus.FREE) {
      throw conflict("REPLACEMENT_UNIT_NOT_AVAILABLE", "Заменяющая бытовка недоступна");
    }
    Map<UUID, Long> replacementRequirements = requiredByUnit.get(replacement.getId());
    if (replacementRequirements == null || !replacementRequirements.equals(desired)) {
      throw conflict(
          "ORDER_RESERVATION_MISMATCH",
          "Наполнение заменяющей бытовки не совпадает с составом заказа");
    }

    Map<UUID, EquipmentCatalogItem> catalog = catalogItems(desired.keySet());
    List<OrderFurnitureMovementPlanLine> lines =
        directReplacementMovementLines(oldUnit, replacement, desired, catalog);
    return new OrderFurnitureMovementPlan(
        orderId, replacement.getId(), replacement.getNumber(), List.copyOf(lines));
  }

  /**
   * Recomputes the exact old-to-new physical delta while both unit identities and the order
   * composition are locked; it never executes balances or releases the order-wide reservation.
   */
  private List<OrderFurnitureMovementPlanLine> directReplacementMovementLines(
      RentalItem oldUnit,
      RentalItem replacement,
      Map<UUID, Long> desired,
      Map<UUID, EquipmentCatalogItem> requestedCatalog) {
    Map<UUID, Long> actual = rentalItemContents(oldUnit);
    Map<UUID, Long> replacementActual = rentalItemContents(replacement);
    Set<UUID> transferredEquipment =
        actual.keySet().stream()
            .filter(
                equipmentId ->
                    desired.getOrDefault(equipmentId, 0L)
                        > replacementActual.getOrDefault(equipmentId, 0L))
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    Map<UUID, EquipmentCatalogItem> catalog = new LinkedHashMap<>(requestedCatalog);
    catalog.putAll(catalogItems(transferredEquipment));
    List<OrderFurnitureMovementPlanLine> lines = new ArrayList<>();
    for (UUID equipmentId : transferredEquipment.stream().sorted().toList()) {
      long outstanding =
          Math.subtractExact(
              desired.get(equipmentId), replacementActual.getOrDefault(equipmentId, 0L));
      long quantity = Math.min(actual.get(equipmentId), outstanding);
      EquipmentBalanceResponse source =
          assets.equipmentTotals(equipmentId, oldUnit.getWarehouseId()).balances().stream()
              .filter(balance -> oldUnit.getId().equals(balance.rentalItemId()))
              .filter(balance -> balance.locationKind() == BalanceLocationKind.CABIN_NON_RENTED)
              .findFirst()
              .orElseThrow(
                  () -> conflict("EQUIPMENT_QUANTITY_CONFLICT", "Наполнение бытовки изменилось"));
      lines.add(
          movementLine(
              catalog.get(equipmentId),
              source,
              replacement.getWarehouseId(),
              replacement.getId(),
              BalanceLocationKind.CABIN_NON_RENTED,
              quantity));
    }
    return List.copyOf(lines);
  }

  private Map<UUID, Long> rentalItemContents(RentalItem rentalItem) {
    return toOrderRentalItem(rentalItem).contents().stream()
        .filter(content -> content.quantity() > 0)
        .collect(
            Collectors.toMap(
                OrderEquipmentContent::equipmentId,
                OrderEquipmentContent::quantity,
                Math::addExact,
                LinkedHashMap::new));
  }

  private void assertRequestedUnitsBelongToOrder(
      UUID orderId, UUID warehouseId, Set<UUID> requestedUnitIds) {
    if (requestedUnitIds.isEmpty()) {
      return;
    }
    Map<UUID, OrderUnitReservation> active =
        reservations
            .findAllByOrderIdAndStateOrderByCreatedAtAscIdAsc(
                orderId, OrderUnitReservationState.ACTIVE)
            .stream()
            .collect(Collectors.toMap(OrderUnitReservation::getRentalItemId, Function.identity()));
    if (!active.keySet().equals(requestedUnitIds)) {
      throw conflict(
          "ORDER_UNIT_RESERVATION_MISMATCH",
          "Состав бытовок заказа изменился, обновите мебель и повторите действие");
    }
    for (UUID rentalItemId : requestedUnitIds) {
      OrderUnitReservation reservation = active.get(rentalItemId);
      if (reservation == null) {
        throw conflict(
            "ORDER_UNIT_RESERVATION_MISMATCH",
            "Мебель можно назначить только забронированной бытовке заказа");
      }
      if (!warehouseId.equals(reservation.getWarehouseId())) {
        throw conflict("ORDER_WAREHOUSE_MISMATCH", "Бытовка относится к другому складу");
      }
    }
  }

  private static void assertMaximumPerCabin(
      Map<UUID, Map<UUID, Long>> requiredByUnit,
      Map<UUID, EquipmentCatalogItem> catalog) {
    for (Map<UUID, Long> requirements : requiredByUnit.values()) {
      for (Map.Entry<UUID, Long> requirement : requirements.entrySet()) {
        Integer maximum = catalog.get(requirement.getKey()).getMaximumPerCabin();
        if (maximum != null && requirement.getValue() > maximum) {
          throw conflict(
              "EQUIPMENT_MAXIMUM_PER_CABIN_EXCEEDED",
              "Превышено максимальное количество позиции в одной бытовке");
        }
      }
    }
  }

  private void assertOrderReservations(
      UUID orderId, UUID warehouseId, Map<UUID, Long> expectedRequirements) {
    List<OrderEquipmentReservation> active =
        equipmentReservations.findAllActiveForUpdate(orderId, OrderEquipmentReservationState.ACTIVE);
    Map<UUID, Long> actualRequirements = new LinkedHashMap<>();
    for (OrderEquipmentReservation reservation : active) {
      if (!warehouseId.equals(reservation.getWarehouseId())) {
        throw conflict("ORDER_RESERVATION_MISMATCH", "Резерв мебели относится к другому складу");
      }
      actualRequirements.put(reservation.getEquipmentId(), reservation.getQuantity());
    }
    if (!actualRequirements.equals(expectedRequirements)) {
      throw conflict(
          "ORDER_RESERVATION_MISMATCH",
          "Состав мебели заказа изменился, обновите отгрузку и повторите действие");
    }
  }

  /** Counts live existing movement holds already satisfying one order-unit target deficit. */
  private long activeOrderInbound(UUID orderId, UUID targetRentalItemId, UUID equipmentId) {
    Long result =
        jdbc.queryForObject(
            """
            select coalesce(sum(quantity),0)
            from equipment_allocation_hold
            where order_id=? and target_rental_item_id=? and equipment_id=?
              and state='ACTIVE' and expires_at>clock_timestamp()
            """,
            Long.class,
            orderId,
            targetRentalItemId,
            equipmentId);
    return result == null ? 0 : result;
  }

  /**
   * Selects only warehouse-side active order cabins and caps each source at physical surplus after
   * desired content and live source holds; rented cabins may fulfill totals but never supply a plan.
   */
  private static List<OrderSurplusSource> sameOrderSurplusSources(
      List<EquipmentBalanceResponse> balances,
      UUID targetRentalItemId,
      UUID equipmentId,
      Map<UUID, Map<UUID, Long>> requiredByUnit) {
    return balances.stream()
        .filter(balance -> equipmentId.equals(balance.equipmentId()))
        .filter(balance -> balance.rentalItemId() != null)
        .filter(balance -> !targetRentalItemId.equals(balance.rentalItemId()))
        .filter(balance -> requiredByUnit.containsKey(balance.rentalItemId()))
        .filter(
            balance ->
                balance.locationKind() == BalanceLocationKind.CABIN_NON_RENTED)
        .map(
            balance -> {
              long desired =
                  requiredByUnit
                      .get(balance.rentalItemId())
                      .getOrDefault(equipmentId, 0L);
              long available =
                  Math.max(
                      0,
                      Math.subtractExact(
                          Math.subtractExact(balance.quantity(), desired),
                          balance.activeHeldQuantity()));
              return new OrderSurplusSource(balance, available);
            })
        .filter(source -> source.availableQuantity() > 0)
        .sorted(
            Comparator.comparing(
                    (OrderSurplusSource source) ->
                        source.balance().rentalItemId().toString())
                .thenComparing(source -> source.balance().locationKind().name())
                .thenComparing(source -> source.balance().id().toString()))
        .toList();
  }

  private List<EquipmentBalanceResponse> eligibleSources(
      List<EquipmentBalanceResponse> balances, UUID targetRentalItemId) {
    return balances.stream()
        .filter(EquipmentBalanceResponse::allocatable)
        .filter(balance -> balance.availableStock() > 0)
        .filter(
            balance ->
                balance.locationKind() == BalanceLocationKind.STOCK
                    || (balance.locationKind() == BalanceLocationKind.CABIN_NON_RENTED
                        && balance.rentalItemId() != null
                        && !balance.rentalItemId().equals(targetRentalItemId)))
        .sorted(
            Comparator.comparingInt(
                    (EquipmentBalanceResponse balance) ->
                        balance.locationKind() == BalanceLocationKind.STOCK ? 0 : 1)
                .thenComparing(
                    balance ->
                        balance.rentalItemId() == null
                            ? ""
                            : balance.rentalItemId().toString()))
        .toList();
  }

  private static OrderFurnitureMovementPlanLine movementLine(
      EquipmentCatalogItem equipment,
      EquipmentBalanceResponse source,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      BalanceLocationKind targetLocationKind,
      long quantity) {
    return new OrderFurnitureMovementPlanLine(
        equipment.getId(),
        equipment.getName(),
        source.id(),
        source.warehouseId(),
        source.rentalItemId(),
        source.locationKind(),
        source.version(),
        targetWarehouseId,
        targetRentalItemId,
        targetLocationKind,
        quantity);
  }

  private Map<UUID, EquipmentCatalogItem> catalogItems(Collection<UUID> equipmentIds) {
    if (equipmentIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, EquipmentCatalogItem> result =
        equipmentCatalog.findAllById(equipmentIds).stream()
            .collect(
                Collectors.toMap(
                    EquipmentCatalogItem::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    if (result.size() != equipmentIds.size()) {
      throw new AssetNotFoundException("Equipment catalog item was not found");
    }
    return result;
  }

  private static Map<UUID, Long> requirementsByEquipment(
      List<OrderEquipmentRequirement> requirements) {
    if (requirements == null) {
      throw new IllegalArgumentException("Equipment requirements are required");
    }
    Map<UUID, Long> result = new LinkedHashMap<>();
    for (OrderEquipmentRequirement requirement : requirements) {
      if (requirement == null || requirement.equipmentId() == null || requirement.quantity() == null) {
        throw new IllegalArgumentException("Equipment requirement is invalid");
      }
      if (requirement.quantity() < 1) {
        throw new IllegalArgumentException("Equipment quantity must be positive");
      }
      if (result.putIfAbsent(requirement.equipmentId(), requirement.quantity()) != null) {
        throw new IllegalArgumentException("Equipment requirement may be specified only once");
      }
    }
    return result;
  }

  private static Map<UUID, Map<UUID, Long>> requirementsByUnit(
      List<OrderUnitEquipmentRequirements> units) {
    if (units == null) {
      throw new IllegalArgumentException("Order unit requirements are required");
    }
    Map<UUID, Map<UUID, Long>> result = new LinkedHashMap<>();
    for (OrderUnitEquipmentRequirements unit : units) {
      if (unit == null || unit.rentalItemId() == null) {
        throw new IllegalArgumentException("Order unit requirement is invalid");
      }
      if (result.putIfAbsent(
              unit.rentalItemId(), requirementsByEquipment(unit.requirements()))
          != null) {
        throw new IllegalArgumentException("Order unit may be specified only once");
      }
    }
    return result;
  }

  private static Map<UUID, Long> aggregateRequirements(
      Collection<Map<UUID, Long>> unitRequirements) {
    Map<UUID, Long> result = new LinkedHashMap<>();
    for (Map<UUID, Long> requirements : unitRequirements) {
      requirements.forEach(
          (equipmentId, quantity) -> result.merge(equipmentId, quantity, Math::addExact));
    }
    return result;
  }

  private OrderUnitReservationView view(OrderUnitReservation reservation, boolean replayed) {
    RentalItem item =
        rentalItems
            .findById(reservation.getRentalItemId())
            .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    return responses.toReservation(reservation, replayed, toOrderRentalItem(item));
  }

  private OrderRentalItem toOrderRentalItem(RentalItem item) {
    return responses.toOrderRentalItem(assets.rentalItem(item.getId()));
  }

  private static void requirePage(int page, int size) {
    if (page < 0 || size < 1 || size > 200) {
      throw new IllegalArgumentException("Invalid order-unit page request");
    }
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private void expireDueDraftReservations(OffsetDateTime timestamp) {
    for (UUID reservationId :
        reservations.findExpiredDraftReservationIds(OrderUnitReservationState.ACTIVE, timestamp)) {
      OrderUnitReservation snapshot = reservations.findById(reservationId).orElse(null);
      if (snapshot == null) continue;
      acquireOrderCompositionLock(snapshot.getOrderId());
      RentalItem item =
          rentalItems
              .findByIdForUpdate(snapshot.getRentalItemId())
              .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
      OrderUnitReservation reservation =
          reservations.findByIdForUpdate(reservationId).orElse(null);
      if (reservation == null || !reservation.isDraftReservationExpiredAt(timestamp)) continue;
      reservation.release(EXPIRATION_SUBJECT_ID, EXPIRATION_ROLE);
      reservations.saveAndFlush(reservation);
      assets.releaseOrderBooking(item.getId());
    }
  }

  private static void validateDraftReservationExpiry(
      OffsetDateTime expiresAt, OffsetDateTime timestamp) {
    if (expiresAt == null) return;
    if (!expiresAt.isAfter(timestamp) || expiresAt.isAfter(timestamp.plusDays(10))) {
      throw new IllegalArgumentException(
          "draftReservationExpiresAt must be within the next 10 days");
    }
  }

  private static OrderUnitReservationConflictException conflict(String code, String message) {
    return new OrderUnitReservationConflictException(code, message);
  }

  private void acquireOrderCompositionLock(UUID orderId) {
    reservations.acquireTransactionLock("order-composition:" + orderId);
  }

  private String hash(Object value) {
    try {
      return AssetChecksum.sha256(json.writeValueAsBytes(value));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Order asset command cannot be fingerprinted", exception);
    }
  }

  private <T> T read(JsonNode node, Class<T> type) {
    try {
      return json.readerFor(type).readValue(node);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored order asset response is corrupt", exception);
    }
  }

  private static OrderUnitReservationView withReplay(OrderUnitReservationView value) {
    return new OrderUnitReservationView(
        value.reservationId(),
        value.reservationVersion(),
        value.orderId(),
        value.rentalItemId(),
        value.warehouseId(),
        value.state(),
        value.addedBySubjectId(),
        value.addedByRole(),
        value.createdAt(),
        value.releasedAt(),
        true,
        value.unit());
  }

  private record ReserveCommand(UUID orderId, ReserveOrderUnitRequest request) {}

  private record ReleaseCommand(UUID orderId, UUID rentalItemId, OrderActorRequest request) {}

  private record ReleaseAllCommand(UUID orderId, OrderActorRequest request) {}

  /** Resource-bound batch replacement material used for stable idempotency hashing. */
  private record ReplaceUnitsCommand(UUID orderId, ReplaceOrderUnitsRequest request) {}

  /** Locked and validated material for one ordered pair in an atomic replacement batch. */
  private record PreparedUnitReplacement(
      OrderUnitReplacement pair,
      RentalItem oldUnit,
      RentalItem replacement,
      OrderUnitReservation oldReservation,
      PresentationUnitHold presentationHold,
      List<OrderFurnitureMovementPlanLine> exactPlan,
      boolean contentReady) {}

  private record EquipmentReservationCommand(
      UUID orderId, ReplaceOrderEquipmentReservationsRequest request) {}

  private record ReservationListReceipt(List<OrderUnitReservationView> reservations) {}

  private record EquipmentReservationListReceipt(
      List<OrderEquipmentReservationView> reservations) {}

  /** One active same-order cabin balance and its unheld physical surplus over desired content. */
  private record OrderSurplusSource(
      EquipmentBalanceResponse balance, long availableQuantity) {}
}
