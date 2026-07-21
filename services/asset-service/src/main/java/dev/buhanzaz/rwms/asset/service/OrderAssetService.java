package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferEquipmentRequest;
import static dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.mapper.OrderAssetResponseMapper;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

  private final RentalItemRepository rentalItems;
  private final OrderUnitReservationRepository reservations;
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
                  request.actorSubjectId(),
                  request.actorRole()));
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
    OrderUnitReservation reservation =
        reservations
            .findActiveForUpdate(orderId, rentalItemId, OrderUnitReservationState.ACTIVE)
            .orElseThrow(() -> new AssetNotFoundException("Order unit reservation was not found"));
    reservation.release(actor.actorSubjectId(), actor.actorRole());
    OrderUnitReservationView response = view(reservations.saveAndFlush(reservation), false);
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
        reservations.findAllActiveForUpdate(orderId, OrderUnitReservationState.ACTIVE);
    active.forEach(value -> value.release(actor.actorSubjectId(), actor.actorRole()));
    List<OrderUnitReservationView> response =
        reservations.saveAllAndFlush(active).stream()
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

  @Transactional
  public AssetService.CreateResult<OrderEquipmentAdjustment> adjustEquipment(
      UUID idempotencyKey,
      UUID orderId,
      UUID rentalItemId,
      UUID equipmentId,
      AdjustOrderEquipmentRequest request) {
    EquipmentCommand command =
        new EquipmentCommand(orderId, rentalItemId, equipmentId, request);
    String fingerprint = hash(command);
    var replay =
        idempotency.replay(
            request.actorSubjectId(), "order-unit.equipment", idempotencyKey, fingerprint);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          read(replay.get(), OrderEquipmentAdjustment.class), true);
    }

    try {
      assets.lockOrderRentalItemForOrder(rentalItemId);
    } catch (AssetConflictException exception) {
      throw conflict("UNIT_NOT_EDITABLE", "Наполнение бытовки временно недоступно");
    }
    RentalItem item =
        rentalItems
            .findByIdForUpdate(rentalItemId)
            .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    reservations
        .findActiveForUpdate(orderId, rentalItemId, OrderUnitReservationState.ACTIVE)
        .orElseThrow(() -> new AssetNotFoundException("Order unit reservation was not found"));
    if (!RESERVABLE_STATUSES.contains(item.getStatus())) {
      throw conflict("UNIT_NOT_EDITABLE", "Наполнение этой бытовки нельзя изменить в заказе");
    }

    var totals = assets.equipmentTotals(equipmentId, item.getWarehouseId());
    var cabin =
        totals.balances().stream()
            .filter(
                balance ->
                    item.getId().equals(balance.rentalItemId())
                        && balance.locationKind() == BalanceLocationKind.CABIN_NON_RENTED)
            .findFirst()
            .orElse(null);
    long previous = cabin == null ? 0 : cabin.quantity();
    if (previous != request.expectedCurrentQuantity()) {
      throw conflict(
          "EQUIPMENT_QUANTITY_CONFLICT", "Количество оборудования изменилось параллельно");
    }
    long delta = Math.subtractExact(request.requiredQuantity(), previous);
    if (delta == 0) {
      OrderEquipmentAdjustment response =
          adjustment(orderId, item, equipmentId, previous, 0, null);
      idempotency.store(
          request.actorSubjectId(),
          "order-unit.equipment",
          idempotencyKey,
          fingerprint,
          200,
          response);
      return new AssetService.CreateResult<>(response, false);
    }

    var stock =
        totals.balances().stream()
            .filter(
                balance ->
                    balance.rentalItemId() == null
                        && balance.locationKind() == BalanceLocationKind.STOCK)
            .findFirst()
            .orElse(null);
    if (delta > 0 && (stock == null || totals.availableStock() < delta)) {
      throw conflict("INSUFFICIENT_STOCK", "Оборудование отсутствует в складском остатке");
    }

    TransferEquipmentRequest transfer =
        delta > 0
            ? new TransferEquipmentRequest(
                equipmentId,
                item.getWarehouseId(),
                null,
                BalanceLocationKind.STOCK,
                stock.version(),
                item.getWarehouseId(),
                item.getId(),
                BalanceLocationKind.CABIN_NON_RENTED,
                cabin == null ? 0L : cabin.version(),
                delta)
            : new TransferEquipmentRequest(
                equipmentId,
                item.getWarehouseId(),
                item.getId(),
                BalanceLocationKind.CABIN_NON_RENTED,
                cabin.version(),
                item.getWarehouseId(),
                null,
                BalanceLocationKind.STOCK,
                stock == null ? 0L : stock.version(),
                Math.negateExact(delta));
    var movement = moveEquipment(request.actorSubjectId(), idempotencyKey, transfer, delta);
    OrderEquipmentAdjustment response =
        adjustment(
            orderId,
            item,
            equipmentId,
            previous,
            delta,
            new OrderEquipmentMovement(
                movement.id(),
                movement.version(),
                movement.equipmentId(),
                movement.sourceBalanceId(),
                movement.targetBalanceId(),
                movement.quantity(),
                movement.kind(),
                movement.occurredAt()));
    idempotency.store(
        request.actorSubjectId(),
        "order-unit.equipment",
        idempotencyKey,
        fingerprint,
        200,
        response);
    return new AssetService.CreateResult<>(response, false);
  }

  private dev.buhanzaz.rwms.asset.api.AssetApiModels.MovementResponse moveEquipment(
      UUID actorSubjectId,
      UUID idempotencyKey,
      TransferEquipmentRequest transfer,
      long delta) {
    try {
      return assets.transfer(actorSubjectId, idempotencyKey, transfer).response();
    } catch (AssetConflictException exception) {
      if (delta > 0) {
        var fresh = assets.equipmentTotals(transfer.equipmentId(), transfer.sourceWarehouseId());
        if (fresh.availableStock() < delta) {
          throw conflict("INSUFFICIENT_STOCK", "Недостаточно доступного складского остатка");
        }
      }
      throw conflict(
          "EQUIPMENT_QUANTITY_CONFLICT", "Количество оборудования изменилось параллельно");
    }
  }

  private OrderEquipmentAdjustment adjustment(
      UUID orderId,
      RentalItem item,
      UUID equipmentId,
      long previous,
      long delta,
      OrderEquipmentMovement movement) {
    var totals = assets.equipmentTotals(equipmentId, item.getWarehouseId());
    return new OrderEquipmentAdjustment(
        orderId,
        item.getId(),
        equipmentId,
        previous,
        Math.addExact(previous, delta),
        delta,
        totals.availableStock(),
        movement,
        toOrderRentalItem(item));
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

  private record EquipmentCommand(
      UUID orderId,
      UUID rentalItemId,
      UUID equipmentId,
      AdjustOrderEquipmentRequest request) {}

  private record ReservationListReceipt(List<OrderUnitReservationView> reservations) {}
}
