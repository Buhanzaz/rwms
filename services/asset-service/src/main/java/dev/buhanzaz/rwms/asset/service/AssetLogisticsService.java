package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.OperationLease;
import dev.buhanzaz.rwms.asset.domain.OperationLeaseState;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.mapper.AssetLogisticsResponseMapper;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Owns typed logistics leases, rental effects, shipment holds and task-bound equipment moves.
 *
 * <p>Each effect validates a logistics owner and a live fencing token before mutating asset state.
 * The class is a command orchestrator: physical row storage remains in ledger/hold collaborators,
 * and all remote warehouse checks happen before the corresponding asset transaction changes.
 */
@Service
final class AssetLogisticsService {
  private final AssetRentalItemService rentals;
  private final AssetRentalProjectionService projections;
  private final AssetLeaseService leases;
  private final AssetEquipmentCatalogService catalog;
  private final AssetEquipmentLedgerService ledger;
  private final AssetEquipmentHoldService holds;
  private final AssetIdempotencyStore idempotency;
  private final AssetEventStore events;
  private final WarehouseRegistryClient warehouses;
  private final AssetLogisticsResponseMapper mapper;
  private final OrderUnitReservationRepository orderUnitReservations;
  private final RentalItemRepository rentalItems;
  private final JdbcTemplate jdbc;
  private final AssetJsonCodec json;
  private final AssetEquipmentMovementService movements;
  private final TransferUnitReservationService transferUnitReservations;

  AssetLogisticsService(
      AssetRentalItemService rentals,
      AssetRentalProjectionService projections,
      AssetLeaseService leases,
      AssetEquipmentCatalogService catalog,
      AssetEquipmentLedgerService ledger,
      AssetEquipmentHoldService holds,
      AssetIdempotencyStore idempotency,
      AssetEventStore events,
      WarehouseRegistryClient warehouses,
      AssetLogisticsResponseMapper mapper,
      OrderUnitReservationRepository orderUnitReservations,
      RentalItemRepository rentalItems,
      JdbcTemplate jdbc,
      AssetJsonCodec json,
      AssetEquipmentMovementService movements,
      TransferUnitReservationService transferUnitReservations) {
    this.rentals = rentals;
    this.projections = projections;
    this.leases = leases;
    this.catalog = catalog;
    this.ledger = ledger;
    this.holds = holds;
    this.idempotency = idempotency;
    this.events = events;
    this.warehouses = warehouses;
    this.mapper = mapper;
    this.orderUnitReservations = orderUnitReservations;
    this.rentalItems = rentalItems;
    this.jdbc = jdbc;
    this.json = json;
    this.movements = movements;
    this.transferUnitReservations = transferUnitReservations;
  }

  LogisticsRentalItemSnapshot snapshot(UUID id) {
    return mapper.toLogisticsSnapshot(projections.response(rentals.require(id)));
  }

  /**
   * Reads the exact cabin version and only the display metadata that logistics may freeze into a
   * public photo presentation.
   */
  LogisticsCabinPhotoPresentationSnapshot photoPresentationSnapshot(UUID id) {
    RentalItem item = rentals.require(id);
    return mapper.toPhotoPresentationSnapshot(item, projections.composition(item));
  }

  AssetService.CreateResult<LogisticsOperationLeaseResponse> acquireLease(
      UUID subjectId, UUID key, AcquireLogisticsOperationLeaseRequest request) {
    String hash = json.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "logistics.operation-lease.acquire", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), LogisticsOperationLeaseResponse.class), true);
    }
    leases.lockRentalItemAndLease(request.rentalItemId());
    RentalItem item = rentals.require(request.rentalItemId());
    AssetLeaseService.assertVersion(item.getVersion(), request.expectedRentalItemVersion());
    AssetLeaseService.assertRentalItemAllowsLeaseEffects(item);
    assertOrderReservation(subjectId, request, item);
    leases.expire(request.rentalItemId());
    if (!leases.activeForUpdate(request.rentalItemId()).isEmpty()) {
      throw new AssetConflictException(
          "Rental item already has an active operation lease; reacquisition is forbidden");
    }
    OperationLeaseResponse response =
        leases.acquire(
            request.rentalItemId(),
            logisticsOwnerType(request.ownerType()),
            logisticsOwnerId(request.documentId(), request.lineId()),
            key);
    LogisticsOperationLeaseResponse safe = mapper.toLogisticsLease(response);
    idempotency.store(subjectId, "logistics.operation-lease.acquire", key, hash, 201, safe);
    return new AssetService.CreateResult<>(safe, false);
  }

  AssetService.CreateResult<LogisticsOperationLeaseResponse> renewLease(
      UUID subjectId, UUID key, UUID id, LogisticsLeaseCommandRequest request) {
    OperationLease current = leases.requireForUpdate(id);
    assertLeaseOwner(current, request.ownerType(), request.documentId(), request.lineId());
    String hash = json.hash(new LogisticsLeaseCommand(id, request));
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "logistics.operation-lease.renew", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), LogisticsOperationLeaseResponse.class), true);
    }
    LogisticsOperationLeaseResponse safe =
        mapper.toLogisticsLease(leases.renew(id, request.expectedVersion(), request.fencingToken()));
    idempotency.store(subjectId, "logistics.operation-lease.renew", key, hash, 200, safe);
    return new AssetService.CreateResult<>(safe, false);
  }

  /**
   * Releases the exact logistics-owned lease or returns its already terminal state when the owner
   * and fencing token still match. This makes a lost response and a natural expiry recoverable
   * without weakening owner fencing.
   */
  AssetService.CreateResult<LogisticsOperationLeaseResponse> releaseLease(
      UUID subjectId, UUID key, UUID id, LogisticsLeaseCommandRequest request) {
    OperationLease current = leases.requireForUpdate(id);
    assertLeaseOwner(current, request.ownerType(), request.documentId(), request.lineId());
    String hash = json.hash(new LogisticsLeaseCommand(id, request));
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "logistics.operation-lease.release", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), LogisticsOperationLeaseResponse.class), true);
    }
    leases.expire(current.getRentalItemId());
    current = leases.requireForUpdate(id);
    assertLeaseOwner(current, request.ownerType(), request.documentId(), request.lineId());
    if (request.expectedVersion() == null || request.expectedVersion() < 0) {
      throw new IllegalArgumentException("expectedVersion is required");
    }
    if (current.getState() == OperationLeaseState.RELEASED
        || current.getState() == OperationLeaseState.EXPIRED) {
      if (idempotency.isBoundToAnotherSubject(
          subjectId, "logistics.operation-lease.release", key)) {
        throw new AssetConflictException("Asset data changed concurrently");
      }
      if (current.getFencingToken() != request.fencingToken()) {
        throw new AssetConflictException("Operation lease is stale or fenced");
      }
      LogisticsOperationLeaseResponse terminal =
          mapper.toLogisticsLease(leases.response(current));
      idempotency.store(
          subjectId, "logistics.operation-lease.release", key, hash, 200, terminal);
      return new AssetService.CreateResult<>(terminal, false);
    }
    LogisticsOperationLeaseResponse safe =
        mapper.toLogisticsLease(leases.release(id, request.expectedVersion(), request.fencingToken()));
    idempotency.store(subjectId, "logistics.operation-lease.release", key, hash, 200, safe);
    return new AssetService.CreateResult<>(safe, false);
  }

  /**
   * Applies one fenced logistics status effect. A transfer arrival relocates attached cabin
   * balances through the asset ledger in the same transaction.
   */
  AssetService.CreateResult<LogisticsRentalItemSnapshot> applyEffect(
      UUID subjectId, UUID key, UUID id, LogisticsFencedEffectRequest request) {
    leases.lockRentalItem(id);
    OperationLease lease = leases.validate(id, request.leaseId(), request.fencingToken());
    assertLeaseOwner(lease, request.ownerType(), request.documentId(), request.lineId());
    String hash = json.hash(new LogisticsEffectCommand(id, request));
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "logistics.rental-item.effect", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), LogisticsRentalItemSnapshot.class), true);
    }
    RentalItem item = rentals.require(id);
    AssetLeaseService.assertVersion(item.getVersion(), request.expectedVersion());
    AssetLeaseService.assertRentalItemAllowsLeaseEffects(item);
    long effectExpectedVersion = request.expectedVersion();
    if (request.action() == LogisticsRentalItemAction.TRANSFER_DEPART
        && item.getStatus() == RentalItemStatus.RESERVED) {
      item =
          transferUnitReservations.consumeForDeparture(
              subjectId, key, item, request.documentId(), request.lineId());
      effectExpectedVersion = item.getVersion();
    }
    RentalItemStatus target =
        LogisticsAssetTransitionPolicy.target(
            item.getStatus(),
            request.action(),
            request.ownerType(),
            request.destinationWarehouseId(),
            request.transferAssetStatus(),
            item.getTransferOriginStatus());
    RentalItemStatus previous = item.getStatus();
    UUID sourceWarehouseId = item.getWarehouseId();
    if (request.action() == LogisticsRentalItemAction.TRANSFER_ARRIVE) {
      if (sourceWarehouseId.equals(request.destinationWarehouseId())) {
        throw new AssetConflictException("Transfer arrival must move the rental item to another warehouse");
      }
      warehouses.requireIncoming(request.destinationWarehouseId());
      rentals.assertNumberAvailableInWarehouse(item, request.destinationWarehouseId());
    } else if (request.action() == LogisticsRentalItemAction.TRANSFER_DEPART
        || request.action() == LogisticsRentalItemAction.SHIPMENT_CONFIRM) {
      warehouses.requireOutgoing(sourceWarehouseId);
    } else if (request.action() == LogisticsRentalItemAction.RETURN_INTAKE) {
      warehouses.requireIncoming(sourceWarehouseId);
    }
    if (request.action() == LogisticsRentalItemAction.TRANSFER_DEPART) {
      item.departTransferUnderLease(RentalItemStatus.valueOf(request.transferAssetStatus().name()));
    } else if (request.action() == LogisticsRentalItemAction.TRANSFER_ARRIVE) {
      item.arriveTransferUnderLease(target);
    } else {
      item.changeStatusUnderLease(target);
    }
    if (request.action() == LogisticsRentalItemAction.TRANSFER_ARRIVE) {
      item.changeWarehouse(request.destinationWarehouseId());
    }
    RentalItem saved = rentalItems.saveAndFlush(item);
    if (request.action() == LogisticsRentalItemAction.TRANSFER_ARRIVE) {
      ledger.relocateCabinContentsUnderLease(
          subjectId, saved.getId(), sourceWarehouseId, request.destinationWarehouseId());
    } else {
      ledger.reclassifyCabinBalances(saved, previous);
    }
    if (request.ownerType() == LogisticsLeaseOwnerType.LOGISTICS_RETURN
        && (request.action() == LogisticsRentalItemAction.RETURN_SETTLE_FREE
            || request.action() == LogisticsRentalItemAction.RETURN_SETTLE_SHORTAGE)) {
      releaseReturnOrderReservation(saved.getId(), subjectId);
    }
    events.append(
        AssetAggregateType.RENTAL_ITEM,
        saved.getId(),
        effectExpectedVersion,
        AssetEventType.RENTAL_ITEM_LOGISTICS_EFFECT_APPLIED,
        projections.fact(saved),
        projections.snapshot(saved));
    LogisticsRentalItemSnapshot safe = mapper.toLogisticsSnapshot(projections.response(saved));
    idempotency.store(subjectId, "logistics.rental-item.effect", key, hash, 200, safe);
    return new AssetService.CreateResult<>(safe, false);
  }

  /**
   * Receives additional return furniture as stock with immutable receipt evidence; it never
   * fabricates a transfer from a non-existent source balance.
   */
  AssetService.CreateResult<LogisticsReturnEquipmentReceiptResponse> receiveReturnEquipment(
      UUID subjectId, UUID key, LogisticsReturnEquipmentReceiptRequest request) {
    validateReturnReceipt(request);
    String hash = json.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "logistics.return-equipment-receipt.receive", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), LogisticsReturnEquipmentReceiptResponse.class), true);
    }
    warehouses.requireIncoming(request.warehouseId());
    List<LogisticsReturnEquipmentReceiptLine> lines = validReturnLines(request);
    ledger.lockReturnReceiptBalances(request.warehouseId(), lines);
    for (LogisticsReturnEquipmentReceiptLine line : lines) {
      EquipmentCatalogItem catalogItem = catalog.require(line.equipmentId());
      if (!catalogItem.isActive() || catalogItem.getCategory() != EquipmentCategory.FURNITURE) {
        throw new AssetConflictException("Returned additional equipment must reference active furniture");
      }
    }
    List<LogisticsReturnEquipmentReceiptLineResponse> received =
        ledger.receiveReturnEquipment(
            subjectId,
            request.returnId(),
            request.returnLineId(),
            request.warehouseId(),
            lines);
    LogisticsReturnEquipmentReceiptResponse response =
        new LogisticsReturnEquipmentReceiptResponse(
            request.returnId(), request.returnLineId(), request.warehouseId(), received);
    idempotency.store(
        subjectId, "logistics.return-equipment-receipt.receive", key, hash, 201, response);
    return new AssetService.CreateResult<>(response, false);
  }

  AssetService.CreateResult<LogisticsEquipmentHoldResponse> acquireHold(
      UUID subjectId, UUID key, AcquireLogisticsEquipmentHoldRequest request) {
    String hash = json.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "logistics.equipment-hold.acquire", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), LogisticsEquipmentHoldResponse.class), true);
    }
    warehouses.requireOutgoing(request.warehouseId());
    catalog.require(request.equipmentId());
    ledger.lock(request.equipmentId(), request.warehouseId(), null, BalanceLocationKind.STOCK);
    AssetBalanceRow stock =
        ledger.require(request.equipmentId(), request.warehouseId(), null, BalanceLocationKind.STOCK);
    holds.expireFor(stock);
    AssetLeaseService.assertVersion(stock.version(), request.expectedStockVersion());
    if (Math.subtractExact(stock.quantity(), holds.activeHeld(stock)) < request.quantity()) {
      throw new AssetConflictException("Active equipment holds reduce available stock");
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into equipment_allocation_hold(id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,quantity,state,idempotency_key,expires_at,created_at,updated_at)
        values (?,0,?,?,?,?,?,?,'ACTIVE',?,?,clock_timestamp(),clock_timestamp())
        """,
        id,
        request.equipmentId(),
        request.warehouseId(),
        stock.id(),
        LogisticsLeaseOwnerType.LOGISTICS_SHIPMENT.name(),
        logisticsOwnerId(request.shipmentId(), request.shipmentLineId()),
        request.quantity(),
        key,
        holds.expiry());
    EquipmentHoldResponse response = holds.forUpdate(id);
    events.initialize(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        id,
        0,
        AssetEventType.EQUIPMENT_HOLD_ACQUIRED,
        holds.fact(response),
        holds.snapshot(response));
    LogisticsEquipmentHoldResponse safe = mapper.toLogisticsHold(response);
    idempotency.store(subjectId, "logistics.equipment-hold.acquire", key, hash, 201, safe);
    return new AssetService.CreateResult<>(safe, false);
  }

  AssetService.CreateResult<LogisticsEquipmentHoldResponse> renewHold(
      UUID subjectId, UUID key, UUID id, LogisticsEquipmentHoldCommandRequest request) {
    EquipmentHoldResponse current = holds.forUpdate(id);
    assertShipmentHoldOwner(current, request.shipmentId(), request.shipmentLineId());
    String hash = json.hash(new LogisticsHoldCommand(id, request));
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "logistics.equipment-hold.renew", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), LogisticsEquipmentHoldResponse.class), true);
    }
    AssetLeaseService.assertVersion(current.version(), request.expectedVersion());
    if (!"ACTIVE".equals(current.state()) || current.expiresAt().isBefore(now())) {
      throw new AssetConflictException("Equipment hold is not active");
    }
    int changed =
        jdbc.update(
            "update equipment_allocation_hold set version=version+1,expires_at=?,updated_at=clock_timestamp() where id=? and version=?",
            holds.expiry(),
            id,
            request.expectedVersion());
    if (changed != 1) {
      throw new AssetConflictException("Equipment hold changed concurrently during renewal");
    }
    EquipmentHoldResponse updated = holds.forUpdate(id);
    events.append(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        id,
        request.expectedVersion(),
        AssetEventType.EQUIPMENT_HOLD_RENEWED,
        holds.fact(updated),
        holds.snapshot(updated));
    LogisticsEquipmentHoldResponse safe = mapper.toLogisticsHold(updated);
    idempotency.store(subjectId, "logistics.equipment-hold.renew", key, hash, 200, safe);
    return new AssetService.CreateResult<>(safe, false);
  }

  AssetService.CreateResult<LogisticsEquipmentHoldResponse> commitHold(
      UUID subjectId, UUID key, UUID id, LogisticsEquipmentHoldCommandRequest request) {
    EquipmentHoldResponse current = holds.forUpdate(id);
    assertShipmentHoldOwner(current, request.shipmentId(), request.shipmentLineId());
    String hash = json.hash(new LogisticsHoldCommand(id, request));
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "logistics.equipment-hold.commit", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), LogisticsEquipmentHoldResponse.class), true);
    }
    holds.expireFor(current);
    current = holds.forUpdate(id);
    assertShipmentHoldOwner(current, request.shipmentId(), request.shipmentLineId());
    AssetLeaseService.assertVersion(current.version(), request.expectedVersion());
    if (!"ACTIVE".equals(current.state())) {
      throw new AssetConflictException("Equipment hold is not active");
    }
    int changed =
        jdbc.update(
            """
            update equipment_allocation_hold
            set version=version+1,state='COMMITTED',committed_at=clock_timestamp(),updated_at=clock_timestamp()
            where id=? and version=? and state='ACTIVE'
            """,
            id,
            request.expectedVersion());
    if (changed != 1) {
      throw new AssetConflictException("Equipment hold changed concurrently during commit");
    }
    EquipmentHoldResponse committed = holds.forUpdate(id);
    events.append(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        id,
        request.expectedVersion(),
        AssetEventType.EQUIPMENT_HOLD_COMMITTED,
        holds.fact(committed),
        holds.snapshot(committed));
    LogisticsEquipmentHoldResponse safe = mapper.toLogisticsHold(committed);
    idempotency.store(subjectId, "logistics.equipment-hold.commit", key, hash, 200, safe);
    return new AssetService.CreateResult<>(safe, false);
  }

  AssetService.CreateResult<LogisticsEquipmentHoldResponse> releaseHold(
      UUID subjectId, UUID key, UUID id, LogisticsEquipmentHoldCommandRequest request) {
    EquipmentHoldResponse current = holds.forUpdate(id);
    assertShipmentHoldOwner(current, request.shipmentId(), request.shipmentLineId());
    String hash = json.hash(new LogisticsHoldCommand(id, request));
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "logistics.equipment-hold.release", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), LogisticsEquipmentHoldResponse.class), true);
    }
    holds.expireFor(current);
    current = holds.forUpdate(id);
    assertShipmentHoldOwner(current, request.shipmentId(), request.shipmentLineId());
    AssetLeaseService.assertVersion(current.version(), request.expectedVersion());
    EquipmentHoldResponse updated = current;
    if ("ACTIVE".equals(current.state()) || "COMMITTED".equals(current.state())) {
      int changed =
          jdbc.update(
              """
              update equipment_allocation_hold
              set version=version+1,state='RELEASED',released_at=clock_timestamp(),updated_at=clock_timestamp()
              where id=? and version=? and state in ('ACTIVE','COMMITTED')
              """,
              id,
              request.expectedVersion());
      if (changed != 1) {
        throw new AssetConflictException("Equipment hold changed concurrently during release");
      }
      updated = holds.forUpdate(id);
      events.append(
          AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
          id,
          request.expectedVersion(),
          AssetEventType.EQUIPMENT_HOLD_RELEASED,
          holds.fact(updated),
          holds.snapshot(updated));
    }
    LogisticsEquipmentHoldResponse safe = mapper.toLogisticsHold(updated);
    idempotency.store(subjectId, "logistics.equipment-hold.release", key, hash, 200, safe);
    return new AssetService.CreateResult<>(safe, false);
  }

  /**
   * Acquires a task-deadline reservation on stock or one canonical cabin balance. Same-order moves
   * hold the order composition and equipment namespaces before capping source surplus and target
   * deficit. Replacement provenance is replay-only: the exact row must have been pre-created by the
   * atomic cabin swap and its released-source/active-target composition is revalidated.
   */
  AssetService.CreateResult<LogisticsEquipmentMovementReservationResponse> acquireMovementReservation(
      UUID subjectId, UUID key, AcquireLogisticsEquipmentMovementReservationRequest request) {
    return movements.acquireMovementReservation(subjectId, key, request);
  }

  /**
   * Pre-creates every ordinary logistics movement reservation for one all-or-nothing cabin swap.
   * All source balances are locked in a stable global order before any hold is inserted, so a
   * later pair cannot fail after an earlier pair escaped the transaction boundary. The normal
   * acquire endpoint replays these same rows when logistics persists and starts its existing task.
   */
  List<List<LogisticsEquipmentMovementReservationResponse>> reserveReplacementMovements(
      UUID idempotencyKey, List<AssetService.ReplacementMovementRequest> requests) {
    return movements.reserveReplacementMovements(idempotencyKey, requests);
  }







  AssetService.CreateResult<LogisticsEquipmentMovementReservationResponse> releaseMovementReservation(
      UUID subjectId,
      UUID key,
      UUID reservationId,
      LogisticsEquipmentMovementReservationCommandRequest request) {
    return movements.releaseMovementReservation(subjectId, key, reservationId, request);
  }

  /**
   * Converts completed worker reservations into balanced ledger entries and executed holds. It
   * locks every order composition, cabin identity and balance in stable order, revalidates durable
   * normal or replacement context, and rejects presentation-held sources or targets before any
   * quantity changes; idempotent replay returns the original receipt.
   */
  AssetService.CreateResult<LogisticsEquipmentMovementExecutionResponse> executeMovementReservations(
      UUID subjectId, UUID key, ExecuteLogisticsEquipmentMovementReservationsRequest request) {
    return movements.executeMovementReservations(subjectId, key, request);
  }

  private void assertOrderReservation(
      UUID subjectId, AcquireLogisticsOperationLeaseRequest request, RentalItem item) {
    OrderUnitReservation current =
        orderUnitReservations
            .findByRentalItemIdAndState(item.getId(), OrderUnitReservationState.ACTIVE)
            .orElse(null);
    if (request.ownerType() == LogisticsLeaseOwnerType.LOGISTICS_SHIPMENT) {
      if (request.rentalOrderId() == null) {
        if (current != null) {
          throw new AssetConflictException("Reserved order unit cannot acquire an operation lease");
        }
        return;
      }
      if (current == null) {
        if (item.getStatus() != RentalItemStatus.FREE) {
          throw new AssetConflictException("Only a free rental item can be added to a rental shipment");
        }
        orderUnitReservations.saveAndFlush(
            OrderUnitReservation.create(
                request.rentalOrderId(), item.getId(), item.getWarehouseId(), subjectId, "SYSTEM_ADMIN"));
        return;
      }
      if (!request.rentalOrderId().equals(current.getOrderId())) {
        throw new AssetConflictException("Rental item is reserved by a different rental order");
      }
      return;
    }
    if (request.ownerType() == LogisticsLeaseOwnerType.LOGISTICS_RETURN
        && request.rentalOrderId() != null) {
      if (current == null || !request.rentalOrderId().equals(current.getOrderId())) {
        throw new AssetConflictException("Rental item is not actively reserved by the selected rental order");
      }
      return;
    }
    if (current != null) {
      throw new AssetConflictException("Reserved order unit cannot acquire an operation lease");
    }
  }

  private void releaseReturnOrderReservation(UUID rentalItemId, UUID subjectId) {
    orderUnitReservations
        .findByRentalItemIdAndState(rentalItemId, OrderUnitReservationState.ACTIVE)
        .ifPresent(
            reservation -> {
              reservation.release(subjectId, "SYSTEM_ADMIN");
              orderUnitReservations.saveAndFlush(reservation);
            });
  }
  private static void validateReturnReceipt(LogisticsReturnEquipmentReceiptRequest request) {
    if (request == null
        || request.returnId() == null
        || request.returnLineId() == null
        || request.warehouseId() == null
        || request.lines() == null) {
      throw new IllegalArgumentException("Return equipment receipt is required");
    }
  }

  private static List<LogisticsReturnEquipmentReceiptLine> validReturnLines(
      LogisticsReturnEquipmentReceiptRequest request) {
    if (request.lines().isEmpty() || request.lines().size() > 100) {
      throw new IllegalArgumentException("Return equipment receipt line count is invalid");
    }
    Set<UUID> equipmentIds = new HashSet<>();
    List<LogisticsReturnEquipmentReceiptLine> result = new ArrayList<>();
    for (LogisticsReturnEquipmentReceiptLine line : request.lines()) {
      if (line == null
          || line.equipmentId() == null
          || line.quantity() == null
          || line.quantity() < 1
          || !equipmentIds.add(line.equipmentId())) {
        throw new IllegalArgumentException("Return equipment receipt line is invalid");
      }
      result.add(line);
    }
    result.sort(Comparator.comparing(line -> line.equipmentId().toString()));
    return List.copyOf(result);
  }

  private static void assertLeaseOwner(
      OperationLease lease,
      LogisticsLeaseOwnerType ownerType,
      UUID documentId,
      UUID lineId) {
    if (lease == null
        || !lease.isOwnedBy(logisticsOwnerType(ownerType), logisticsOwnerId(documentId, lineId))) {
      throw new AssetConflictException("Operation lease belongs to another logistics document line");
    }
  }

  private static void assertShipmentHoldOwner(
      EquipmentHoldResponse hold, UUID shipmentId, UUID shipmentLineId) {
    if (hold == null
        || !LogisticsLeaseOwnerType.LOGISTICS_SHIPMENT.name().equals(hold.ownerType())
        || !logisticsOwnerId(shipmentId, shipmentLineId).equals(hold.ownerId())) {
      throw new AssetConflictException("Equipment hold belongs to another logistics shipment line");
    }
  }
  private static String logisticsOwnerType(LogisticsLeaseOwnerType ownerType) {
    if (ownerType == null) {
      throw new IllegalArgumentException("logistics ownerType is required");
    }
    return ownerType.name();
  }

  private static String logisticsOwnerId(UUID documentId, UUID lineId) {
    if (documentId == null || lineId == null) {
      throw new IllegalArgumentException("logistics documentId and lineId are required");
    }
    return documentId + ":" + lineId;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** Resource-bound lease request material used for stable idempotency hashing. */
  private record LogisticsLeaseCommand(UUID resourceId, LogisticsLeaseCommandRequest request) {}

  /** Resource-bound fenced effect material used for stable idempotency hashing. */
  private record LogisticsEffectCommand(UUID resourceId, LogisticsFencedEffectRequest request) {}

  /** Resource-bound equipment-hold material used for stable idempotency hashing. */
  private record LogisticsHoldCommand(UUID resourceId, LogisticsEquipmentHoldCommandRequest request) {}

}
