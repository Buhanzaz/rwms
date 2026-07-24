package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.*;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentBalanceResponse;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.OrderEquipmentReservation;
import dev.buhanzaz.rwms.asset.domain.OrderEquipmentReservationState;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.mapper.OrderAssetResponseMapper;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.OrderEquipmentReservationRepository;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class OrderAssetService {
  private static final Set<RentalItemStatus> RESERVABLE_STATUSES =
      Set.of(RentalItemStatus.FREE);
  private static final Set<RentalItemStatus> ORDER_EDITABLE_STATUSES =
      Set.of(RentalItemStatus.FREE, RentalItemStatus.BOOKED);

  private final RentalItemRepository rentalItems;
  private final OrderUnitReservationRepository reservations;
  private final OrderEquipmentReservationRepository equipmentReservations;
  private final EquipmentCatalogItemRepository equipmentCatalog;
  private final AssetService assets;
  private final AssetIdempotencyStore idempotency;
  private final OrderAssetResponseMapper responses;
  private final ObjectMapper json;

  @Transactional(readOnly = true)
  public OrderUnitCandidatePage candidates(
      UUID orderId, UUID warehouseId, int page, int size, String search) {
    requirePage(page, size);
    String normalizedSearch = search == null ? "" : search.trim().toUpperCase(Locale.ROOT);
    var result =
        rentalItems.findOrderCandidates(
            orderId,
            warehouseId,
            RESERVABLE_STATUSES,
            OrderUnitReservationState.ACTIVE,
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

  @Transactional(readOnly = true)
  public List<OrderUnitReservationView> units(UUID orderId) {
    return reservations
        .findAllByOrderIdAndStateOrderByCreatedAtAscIdAsc(
            orderId, OrderUnitReservationState.ACTIVE)
        .stream()
        .map(reservation -> view(reservation, false))
        .toList();
  }

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

    try {
      assets.lockOrderRentalItemForOrder(request.rentalItemId());
    } catch (AssetConflictException exception) {
      throw conflict("UNIT_NOT_AVAILABLE", "Бытовка выполняет другую складскую операцию");
    }
    RentalItem item =
        rentalItems
            .findByIdForUpdate(request.rentalItemId())
            .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    OrderUnitReservation existing =
        reservations
            .findByRentalItemIdAndState(item.getId(), OrderUnitReservationState.ACTIVE)
            .orElse(null);
    if (existing != null) {
      if (existing.getOrderId().equals(orderId)
          && item.getWarehouseId().equals(request.warehouseId())) {
        boolean projectionChanged = existing.updateClientProjection(
            request.clientId(), request.tenantSnapshot());
        if (projectionChanged) {
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

  @Transactional(readOnly = true)
  public boolean hasActiveUnits(UUID orderId) {
    return reservations.existsByOrderIdAndState(orderId, OrderUnitReservationState.ACTIVE);
  }

  /**
   * Replaces the order-wide catalogue allocation. It intentionally does not
   * select a source cabin: that decision belongs to the later worker task.
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

    Map<UUID, Long> required = requirementsByEquipment(request.requirements());
    List<UUID> equipmentIds = required.keySet().stream().sorted().toList();
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

    Map<UUID, Long> availableAfter = new LinkedHashMap<>();
    if (!equipmentIds.isEmpty()) {
      List<OrderEquipmentReservation> allAtWarehouse =
          equipmentReservations.findAllActiveByWarehouseAndEquipmentIds(
              request.warehouseId(), equipmentIds, OrderEquipmentReservationState.ACTIVE);
      Map<UUID, Long> reservedByOtherOrders = new LinkedHashMap<>();
      for (OrderEquipmentReservation reservation : allAtWarehouse) {
        if (!reservation.getOrderId().equals(orderId)) {
          reservedByOtherOrders.merge(
              reservation.getEquipmentId(), reservation.getQuantity(), Math::addExact);
        }
      }
      for (UUID equipmentId : equipmentIds) {
        var totals = assets.equipmentTotals(equipmentId, request.warehouseId());
        long physicalAvailable =
            Math.max(
                0,
                Math.subtractExact(
                    Math.addExact(totals.stockQuantity(), totals.nonRentedCabinQuantity()),
                    totals.activeHeldQuantity()));
        long availableForOrder =
            Math.max(
                0,
                Math.subtractExact(
                    physicalAvailable,
                    reservedByOtherOrders.getOrDefault(equipmentId, 0L)));
        long requested = required.get(equipmentId);
        if (requested > availableForOrder) {
          throw conflict(
              "INSUFFICIENT_EQUIPMENT",
              "Недостаточно доступного дополнительного оборудования для заказа");
        }
        availableAfter.put(equipmentId, Math.subtractExact(availableForOrder, requested));
      }
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

    List<OrderEquipmentReservationView> response =
        currentByEquipment.values().stream()
            .filter(value -> value.getState() == OrderEquipmentReservationState.ACTIVE)
            .sorted(
                Comparator.comparing(
                    value -> catalog.get(value.getEquipmentId()).getCode()))
            .map(
                value ->
                    responses.toOrderEquipmentReservation(
                        value,
                        catalog.get(value.getEquipmentId()),
                        availableAfter.getOrDefault(value.getEquipmentId(), 0L)))
            .toList();
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
   * Calculates only the physical delta for one cabin. Additions draw from stock
   * first, then from unreserved non-rented cabins; surplus goes back to stock.
   */
  @Transactional
  public OrderFurnitureMovementPlan furnitureMovementPlan(
      UUID orderId, OrderFurnitureMovementPlanRequest request) {
    Map<UUID, Long> desired = requirementsByEquipment(request.requirements());
    Map<UUID, Long> orderRequired = requirementsByEquipment(request.orderRequirements());
    assertOrderReservations(orderId, request.warehouseId(), orderRequired);

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
        long outstanding = Math.subtractExact(desiredQuantity, actualQuantity);
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

  private List<EquipmentBalanceResponse> eligibleSources(
      List<EquipmentBalanceResponse> balances, UUID targetRentalItemId) {
    Set<UUID> candidateCabins =
        balances.stream()
            .filter(balance -> balance.locationKind() == BalanceLocationKind.CABIN_NON_RENTED)
            .map(EquipmentBalanceResponse::rentalItemId)
            .filter(Objects::nonNull)
            .filter(id -> !id.equals(targetRentalItemId))
            .collect(Collectors.toSet());
    Set<UUID> reservedCabins =
        candidateCabins.isEmpty()
            ? Set.of()
            : reservations
                .findAllByRentalItemIdInAndState(
                    List.copyOf(candidateCabins), OrderUnitReservationState.ACTIVE)
                .stream()
                .map(OrderUnitReservation::getRentalItemId)
                .collect(Collectors.toSet());
    return balances.stream()
        .filter(balance -> balance.availableStock() > 0)
        .filter(
            balance ->
                balance.locationKind() == BalanceLocationKind.STOCK
                    || (balance.locationKind() == BalanceLocationKind.CABIN_NON_RENTED
                        && balance.rentalItemId() != null
                        && !balance.rentalItemId().equals(targetRentalItemId)
                        && !reservedCabins.contains(balance.rentalItemId())))
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
        equipment.getCode(),
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

  private static OrderUnitReservationConflictException conflict(String code, String message) {
    return new OrderUnitReservationConflictException(code, message);
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

  private record EquipmentReservationCommand(
      UUID orderId, ReplaceOrderEquipmentReservationsRequest request) {}

  private record ReservationListReceipt(List<OrderUnitReservationView> reservations) {}

  private record EquipmentReservationListReceipt(
      List<OrderEquipmentReservationView> reservations) {}
}
