package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.OperationLease;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
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
  private final EquipmentAllocationPolicy allocationPolicy;
  private final OrderUnitReservationRepository orderUnitReservations;
  private final RentalItemRepository rentalItems;
  private final JdbcTemplate jdbc;
  private final AssetJsonCodec json;

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
      EquipmentAllocationPolicy allocationPolicy,
      OrderUnitReservationRepository orderUnitReservations,
      RentalItemRepository rentalItems,
      JdbcTemplate jdbc,
      AssetJsonCodec json) {
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
    this.allocationPolicy = allocationPolicy;
    this.orderUnitReservations = orderUnitReservations;
    this.rentalItems = rentalItems;
    this.jdbc = jdbc;
    this.json = json;
  }

  LogisticsRentalItemSnapshot snapshot(UUID id) {
    return mapper.toLogisticsSnapshot(projections.response(rentals.require(id)));
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
        request.expectedVersion(),
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

  /** Acquires a task-deadline reservation on stock or one canonical cabin balance. */
  AssetService.CreateResult<LogisticsEquipmentMovementReservationResponse> acquireMovementReservation(
      UUID subjectId, UUID key, AcquireLogisticsEquipmentMovementReservationRequest request) {
    if (request == null || request.purpose() == null) {
      throw new IllegalArgumentException("Equipment movement purpose is required");
    }
    String hash = json.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "logistics.equipment-movement-reservation.acquire", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), LogisticsEquipmentMovementReservationResponse.class), true);
    }
    if (!isMovableReservationSource(request.sourceLocationKind())) {
      throw new IllegalArgumentException(
          "Equipment movement reservation source must be stock or a cabin balance");
    }
    warehouses.requireOutgoing(request.sourceWarehouseId());
    EquipmentCatalogItem catalogItem = catalog.require(request.equipmentId());
    ledger.validateLocation(
        request.sourceWarehouseId(), request.sourceRentalItemId(), request.sourceLocationKind());
    if (request.sourceRentalItemId() != null) {
      leases.lockRentalItem(request.sourceRentalItemId());
      if (request.purpose() == LogisticsEquipmentMovementPurpose.ALLOCATABLE_REBALANCE) {
        leases.assertNoActive(request.sourceRentalItemId());
      }
    }
    ledger.lock(
        request.equipmentId(),
        request.sourceWarehouseId(),
        request.sourceRentalItemId(),
        request.sourceLocationKind());
    AssetBalanceRow source =
        ledger.require(
            request.equipmentId(),
            request.sourceWarehouseId(),
            request.sourceRentalItemId(),
            request.sourceLocationKind());
    holds.expireFor(source);
    AssetLeaseService.assertVersion(source.version(), request.expectedSourceBalanceVersion());
    Set<UUID> nestedHoldIds;
    LogisticsEquipmentMovementReservationOwnerType ownerType;
    if (request.purpose() == LogisticsEquipmentMovementPurpose.MAINTENANCE_DISPOSITION) {
      nestedHoldIds = Set.of(requireMaintenanceParentHold(request, source));
      ownerType = LogisticsEquipmentMovementReservationOwnerType.MAINTENANCE_DISPOSITION_MOVEMENT;
    } else {
      requireAllocatableSource(source);
      nestedHoldIds = Set.of();
      ownerType = LogisticsEquipmentMovementReservationOwnerType.LOGISTICS_EQUIPMENT_MOVEMENT;
    }
    OffsetDateTime acquiredAt = now();
    if (!request.reservedUntil().isAfter(acquiredAt)) {
      throw new IllegalArgumentException("reservedUntil must be in the future");
    }
    if (Math.subtractExact(source.quantity(), holds.activeHeldExcluding(source, nestedHoldIds))
        < request.quantity()) {
      throw new AssetConflictException("Equipment movement reservation exceeds the unreserved source balance");
    }
    assertNoActiveMovementReservation(request.movementId(), request.lineId());
    UUID reservationId = UUID.randomUUID();
    try {
      jdbc.update(
          """
          insert into equipment_allocation_hold(
            id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,
            quantity,state,idempotency_key,expires_at,created_at,updated_at)
          values (?,0,?,?,?,?,?,?,'ACTIVE',?,?,?,?)
          """,
          reservationId,
          request.equipmentId(),
          request.sourceWarehouseId(),
          source.id(),
          ownerType.name(),
          logisticsOwnerId(request.movementId(), request.lineId()),
          request.quantity(),
          key,
          request.reservedUntil(),
          acquiredAt,
          acquiredAt);
    } catch (DataIntegrityViolationException exception) {
      throw new AssetConflictException("Equipment movement line already has an active reservation");
    }
    EquipmentHoldResponse reservation = holds.forUpdate(reservationId);
    events.initialize(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        reservationId,
        0,
        AssetEventType.EQUIPMENT_HOLD_ACQUIRED,
        holds.fact(reservation),
        holds.snapshot(reservation));
    LogisticsEquipmentMovementReservationResponse response =
        movementReservationResponse(reservation, catalogItem, source);
    idempotency.store(
        subjectId, "logistics.equipment-movement-reservation.acquire", key, hash, 201, response);
    return new AssetService.CreateResult<>(response, false);
  }

  AssetService.CreateResult<LogisticsEquipmentMovementReservationResponse> releaseMovementReservation(
      UUID subjectId,
      UUID key,
      UUID reservationId,
      LogisticsEquipmentMovementReservationCommandRequest request) {
    EquipmentHoldResponse current = holds.forUpdate(reservationId);
    assertMovementReservationOwner(current, request.movementId(), request.lineId());
    String hash = json.hash(new LogisticsEquipmentMovementReservationCommand(reservationId, request));
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "logistics.equipment-movement-reservation.release", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), LogisticsEquipmentMovementReservationResponse.class), true);
    }
    holds.expireFor(current);
    current = holds.forUpdate(reservationId);
    assertMovementReservationOwner(current, request.movementId(), request.lineId());
    AssetLeaseService.assertVersion(current.version(), request.expectedReservationVersion());
    EquipmentHoldResponse updated = current;
    if ("ACTIVE".equals(current.state())) {
      int changed =
          jdbc.update(
              """
              update equipment_allocation_hold
              set version=version+1,state='RELEASED',released_at=clock_timestamp(),updated_at=clock_timestamp()
              where id=? and version=? and state='ACTIVE'
              """,
              reservationId,
              request.expectedReservationVersion());
      if (changed != 1) {
        throw new AssetConflictException("Equipment movement reservation changed concurrently during release");
      }
      updated = holds.forUpdate(reservationId);
      events.append(
          AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
          reservationId,
          request.expectedReservationVersion(),
          AssetEventType.EQUIPMENT_HOLD_RELEASED,
          holds.fact(updated),
          holds.snapshot(updated));
    }
    LogisticsEquipmentMovementReservationResponse response = movementReservationResponse(updated);
    idempotency.store(
        subjectId, "logistics.equipment-movement-reservation.release", key, hash, 200, response);
    return new AssetService.CreateResult<>(response, false);
  }

  /** Converts completed worker reservations into balanced ledger entries and executed holds. */
  AssetService.CreateResult<LogisticsEquipmentMovementExecutionResponse> executeMovementReservations(
      UUID subjectId, UUID key, ExecuteLogisticsEquipmentMovementReservationsRequest request) {
    String hash = json.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "logistics.equipment-movement-reservation.execute", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), LogisticsEquipmentMovementExecutionResponse.class), true);
    }
    validateExecutionRequest(request);
    List<MovementReservationCandidate> candidates = new ArrayList<>();
    for (ExecuteLogisticsEquipmentMovementReservationLine line : request.lines()) {
      EquipmentHoldResponse reservation = holds.read(line.reservationId());
      assertMovementReservationOwner(reservation, request.movementId(), line.lineId());
      if (reservation.sourceBalanceId() == null) {
        throw new AssetConflictException("Equipment movement reservation has no concrete source balance");
      }
      AssetBalanceRow source = ledger.read(reservation.sourceBalanceId());
      if (!source.equipmentId().equals(reservation.equipmentId())
          || !source.warehouseId().equals(reservation.warehouseId())) {
        throw new AssetConflictException("Equipment movement reservation source no longer matches its balance");
      }
      if (isMaintenanceDispositionReservation(reservation)) {
        warehouses.requireOutgoing(source.warehouseId());
      } else {
        warehouses.requireIncoming(line.targetWarehouseId());
      }
      ledger.validateLocation(
          line.targetWarehouseId(), line.targetRentalItemId(), line.targetLocationKind());
      candidates.add(new MovementReservationCandidate(reservation, source, line));
    }
    candidates.stream()
        .flatMap(
            candidate ->
                java.util.stream.Stream.of(
                    candidate.source().rentalItemId(), candidate.line().targetRentalItemId()))
        .filter(java.util.Objects::nonNull)
        .distinct()
        .sorted(Comparator.comparing(UUID::toString))
        .forEach(leases::lockRentalItem);
    Set<UUID> leaseBlockedCabins = new TreeSet<>(Comparator.comparing(UUID::toString));
    for (MovementReservationCandidate candidate : candidates) {
      if (!isMaintenanceDispositionReservation(candidate.reservation())
          && candidate.source().rentalItemId() != null) {
        leaseBlockedCabins.add(candidate.source().rentalItemId());
      }
      if (candidate.line().targetRentalItemId() != null) {
        leaseBlockedCabins.add(candidate.line().targetRentalItemId());
      }
    }
    leaseBlockedCabins.forEach(leases::assertNoActive);
    for (MovementReservationCandidate candidate : candidates) {
      ledger.validateLocation(
          candidate.line().targetWarehouseId(),
          candidate.line().targetRentalItemId(),
          candidate.line().targetLocationKind());
    }
    ledger.lockAll(
        candidates.stream()
            .flatMap(
                candidate ->
                    java.util.stream.Stream.of(
                        AssetEquipmentLedgerService.balanceLockKey(
                            candidate.source().equipmentId(),
                            candidate.source().warehouseId(),
                            candidate.source().rentalItemId(),
                            candidate.source().kind()),
                        AssetEquipmentLedgerService.balanceLockKey(
                            candidate.reservation().equipmentId(),
                            candidate.line().targetWarehouseId(),
                            candidate.line().targetRentalItemId(),
                            candidate.line().targetLocationKind())))
            .toList());
    Map<UUID, AssetBalanceRow> sources = new LinkedHashMap<>();
    for (MovementReservationCandidate candidate : candidates) {
      AssetBalanceRow source = ledger.requireForUpdate(candidate.source().id());
      if (!source.equipmentId().equals(candidate.reservation().equipmentId())
          || !source.warehouseId().equals(candidate.reservation().warehouseId())
          || !java.util.Objects.equals(source.rentalItemId(), candidate.source().rentalItemId())
          || source.kind() != candidate.source().kind()) {
        throw new AssetConflictException("Equipment movement reservation source changed concurrently");
      }
      sources.put(source.id(), source);
    }
    sources.values().forEach(holds::expireFor);
    List<MovementReservationExecutionPlan> plans = new ArrayList<>();
    for (MovementReservationCandidate candidate : candidates) {
      EquipmentHoldResponse reservation = holds.forUpdate(candidate.line().reservationId());
      assertMovementReservationOwner(reservation, request.movementId(), candidate.line().lineId());
      AssetLeaseService.assertVersion(reservation.version(), candidate.line().expectedReservationVersion());
      if (!"ACTIVE".equals(reservation.state()) || !reservation.expiresAt().isAfter(now())) {
        throw new AssetConflictException("Equipment movement reservation is not active");
      }
      AssetBalanceRow source = ledger.requireForUpdate(reservation.sourceBalanceId());
      AssetBalanceRow target =
          ledger
              .findForUpdate(
                  reservation.equipmentId(),
                  candidate.line().targetWarehouseId(),
                  candidate.line().targetRentalItemId(),
                  candidate.line().targetLocationKind())
              .orElseGet(
                  () ->
                      ledger.createEmpty(
                          reservation.equipmentId(),
                          candidate.line().targetWarehouseId(),
                          candidate.line().targetRentalItemId(),
                          candidate.line().targetLocationKind()));
      if (source.id().equals(target.id())) {
        throw new AssetConflictException("Equipment movement reservation source and target must differ");
      }
      UUID nestedParentHoldId = null;
      if (isMaintenanceDispositionReservation(reservation)) {
        if (source.rentalItemId() == null
            || source.kind() != BalanceLocationKind.CABIN_NON_RENTED
            || candidate.line().targetRentalItemId() != null
            || candidate.line().targetLocationKind() != BalanceLocationKind.STOCK
            || !source.warehouseId().equals(candidate.line().targetWarehouseId())) {
          throw new AssetConflictException(
              "Maintenance disposition movement must return prepared cabin contents to stock in the same warehouse");
        }
        nestedParentHoldId =
            requireMaintenanceParentHold(
                request.movementId(),
                source.warehouseId(),
                source.rentalItemId(),
                source.equipmentId(),
                source.id(),
                reservation.quantity());
      } else {
        requireAllocatableSource(source);
      }
      plans.add(
          new MovementReservationExecutionPlan(
              reservation, source, target, candidate.line(), nestedParentHoldId));
    }
    assertReservedExecutionAvailability(plans);
    events.lockStreams(
        plans.stream()
            .flatMap(
                plan ->
                    java.util.stream.Stream.of(
                        new AssetEventStore.StreamRef(
                            AssetAggregateType.EQUIPMENT_BALANCE, plan.source().id()),
                        new AssetEventStore.StreamRef(
                            AssetAggregateType.EQUIPMENT_BALANCE, plan.target().id())))
            .toList());
    List<LogisticsEquipmentMovementExecutionLine> responseLines = new ArrayList<>();
    for (MovementReservationExecutionPlan plan : plans) {
      AssetBalanceRow source = ledger.requireForUpdate(plan.source().id());
      AssetBalanceRow target = ledger.requireForUpdate(plan.target().id());
      ledger.decrement(source, plan.reservation().quantity(), source.version());
      ledger.increment(target, plan.reservation().quantity(), target.version());
      AssetBalanceRow sourceAfter = ledger.requireForUpdate(source.id());
      AssetBalanceRow targetAfter = ledger.requireForUpdate(target.id());
      events.append(
          AssetAggregateType.EQUIPMENT_BALANCE,
          source.id(),
          source.version(),
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          ledger.fact(sourceAfter),
          ledger.snapshot(sourceAfter));
      events.append(
          AssetAggregateType.EQUIPMENT_BALANCE,
          target.id(),
          target.version(),
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          ledger.fact(targetAfter),
          ledger.snapshot(targetAfter));
      UUID movementId = UUID.randomUUID();
      String kind = AssetEquipmentLedgerService.movementKind(source.kind(), target.kind());
      jdbc.update(
          """
          insert into equipment_movement(
            id,version,equipment_id,source_balance_id,target_balance_id,quantity,movement_kind,
            occurred_at,actor_subject_id,origin_reservation_id)
          values (?,0,?,?,?,?,?,clock_timestamp(),?,?)
          """,
          movementId,
          plan.reservation().equipmentId(),
          source.id(),
          target.id(),
          plan.reservation().quantity(),
          kind,
          subjectId,
          plan.reservation().id());
      jdbc.update(
          """
          insert into equipment_movement_ledger(
            movement_id,line_no,balance_id,quantity_delta,recorded_at)
          values (?,1,?,-?,clock_timestamp()), (?,2,?,?,clock_timestamp())
          """,
          movementId,
          source.id(),
          plan.reservation().quantity(),
          movementId,
          target.id(),
          plan.reservation().quantity());
      MovementResponse movement = ledger.movementResponse(movementId);
      events.initialize(
          AssetAggregateType.EQUIPMENT_MOVEMENT,
          movementId,
          0,
          AssetEquipmentLedgerService.movementEventType(kind),
          ledger.movementFact(movement),
          Map.of("movementId", movementId.toString(), "version", 0));
      int changed =
          jdbc.update(
              """
              update equipment_allocation_hold
              set version=version+1,state='EXECUTED',executed_at=clock_timestamp(),updated_at=clock_timestamp()
              where id=? and version=? and state='ACTIVE' and expires_at>clock_timestamp()
              """,
              plan.reservation().id(),
              plan.reservation().version());
      if (changed != 1) {
        throw new AssetConflictException("Equipment movement reservation changed concurrently during execution");
      }
      EquipmentHoldResponse executed = holds.forUpdate(plan.reservation().id());
      events.append(
          AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
          executed.id(),
          plan.reservation().version(),
          AssetEventType.EQUIPMENT_HOLD_EXECUTED,
          holds.fact(executed),
          holds.snapshot(executed));
      responseLines.add(
          new LogisticsEquipmentMovementExecutionLine(
              executed.id(), executed.version(), plan.line().lineId(), movement));
    }
    LogisticsEquipmentMovementExecutionResponse response =
        new LogisticsEquipmentMovementExecutionResponse(request.movementId(), List.copyOf(responseLines));
    idempotency.store(
        subjectId, "logistics.equipment-movement-reservation.execute", key, hash, 201, response);
    return new AssetService.CreateResult<>(response, false);
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

  private void assertNoActiveMovementReservation(UUID movementId, UUID lineId) {
    Boolean active =
        jdbc.queryForObject(
            """
            select exists(
              select 1 from equipment_allocation_hold
              where owner_type in (?,?) and owner_id=? and state='ACTIVE'
            )
            """,
            Boolean.class,
            LogisticsEquipmentMovementReservationOwnerType.LOGISTICS_EQUIPMENT_MOVEMENT.name(),
            LogisticsEquipmentMovementReservationOwnerType.MAINTENANCE_DISPOSITION_MOVEMENT.name(),
            logisticsOwnerId(movementId, lineId));
    if (Boolean.TRUE.equals(active)) {
      throw new AssetConflictException("Equipment movement line already has an active reservation");
    }
  }

  private void assertReservedExecutionAvailability(List<MovementReservationExecutionPlan> plans) {
    Map<UUID, List<MovementReservationExecutionPlan>> bySource = new LinkedHashMap<>();
    for (MovementReservationExecutionPlan plan : plans) {
      bySource.computeIfAbsent(plan.source().id(), ignored -> new ArrayList<>()).add(plan);
    }
    for (List<MovementReservationExecutionPlan> values : bySource.values()) {
      AssetBalanceRow source = values.getFirst().source();
      long consumed = values.stream().mapToLong(value -> value.reservation().quantity()).sum();
      Set<UUID> ownReservationIds =
          values.stream()
              .map(value -> value.reservation().id())
              .collect(java.util.stream.Collectors.toSet());
      values.stream()
          .map(MovementReservationExecutionPlan::nestedParentHoldId)
          .filter(java.util.Objects::nonNull)
          .forEach(ownReservationIds::add);
      if (Math.subtractExact(source.quantity(), consumed)
          < holds.activeHeldExcluding(source, ownReservationIds)) {
        throw new AssetConflictException(
            "Equipment movement execution would consume quantities reserved by another hold");
      }
    }
  }

  private void requireAllocatableSource(AssetBalanceRow source) {
    boolean allocatable =
        allocationPolicy.sources(source.equipmentId(), source.warehouseId()).stream()
            .anyMatch(candidate -> candidate.balanceId().equals(source.id()) && candidate.allocatable());
    if (!allocatable) {
      throw new AssetConflictException(
          "Equipment source is not allocatable in its current cabin workflow state");
    }
  }

  private UUID requireMaintenanceParentHold(
      AcquireLogisticsEquipmentMovementReservationRequest request, AssetBalanceRow source) {
    if (request.sourceRentalItemId() == null
        || request.sourceLocationKind() != BalanceLocationKind.CABIN_NON_RENTED
        || !request.sourceRentalItemId().equals(source.rentalItemId())) {
      throw new AssetConflictException(
          "Maintenance disposition movement must start in its prepared non-rented cabin");
    }
    return requireMaintenanceParentHold(
        request.movementId(),
        request.sourceWarehouseId(),
        request.sourceRentalItemId(),
        request.equipmentId(),
        source.id(),
        request.quantity());
  }

  private UUID requireMaintenanceParentHold(
      UUID decisionId,
      UUID warehouseId,
      UUID rentalItemId,
      UUID equipmentId,
      UUID sourceBalanceId,
      long moveQuantity) {
    return jdbc
        .query(
            """
            select content.hold_id
            from property_disposition_fence fence
            join property_disposition_fence_content content
              on content.decision_id=fence.decision_id
            join equipment_allocation_hold parent_hold
              on parent_hold.id=content.hold_id
            where fence.decision_id=?
              and fence.state='PREPARED'
              and fence.asset_kind='CABIN'
              and fence.contents_mode='MOVE_SELECTED_TO_STOCK'
              and fence.warehouse_id=?
              and fence.asset_id=?
              and content.equipment_id=?
              and content.source_balance_id=?
              and content.move_quantity=?
              and parent_hold.owner_type='MAINTENANCE_PROPERTY_DISPOSITION'
              and parent_hold.owner_id=?
              and parent_hold.state='COMMITTED'
            for update of fence,content,parent_hold
            """,
            (result, row) -> result.getObject("hold_id", UUID.class),
            decisionId,
            warehouseId,
            rentalItemId,
            equipmentId,
            sourceBalanceId,
            moveQuantity,
            decisionId.toString())
        .stream()
        .findFirst()
        .orElseThrow(
            () ->
                new AssetConflictException(
                    "Maintenance disposition movement does not match its prepared content fence"));
  }

  private LogisticsEquipmentMovementReservationResponse movementReservationResponse(
      EquipmentHoldResponse reservation) {
    EquipmentCatalogItem catalogItem = catalog.require(reservation.equipmentId());
    if (reservation.sourceBalanceId() == null) {
      throw new AssetConflictException("Equipment movement reservation has no concrete source balance");
    }
    return movementReservationResponse(reservation, catalogItem, ledger.read(reservation.sourceBalanceId()));
  }

  private static LogisticsEquipmentMovementReservationResponse movementReservationResponse(
      EquipmentHoldResponse reservation, EquipmentCatalogItem catalogItem, AssetBalanceRow source) {
    return new LogisticsEquipmentMovementReservationResponse(
        reservation.id(),
        reservation.version(),
        LogisticsEquipmentMovementReservationOwnerType.valueOf(reservation.ownerType()),
        ownerDocumentId(reservation.ownerId()),
        ownerLineId(reservation.ownerId()),
        reservation.equipmentId(),
        catalogItem.getName(),
        source.id(),
        source.warehouseId(),
        source.rentalItemId(),
        source.kind(),
        reservation.quantity(),
        reservation.state(),
        reservation.expiresAt(),
        reservation.executedAt());
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

  private static void assertMovementReservationOwner(
      EquipmentHoldResponse reservation, UUID movementId, UUID lineId) {
    boolean supportedOwner =
        reservation != null
            && (LogisticsEquipmentMovementReservationOwnerType.LOGISTICS_EQUIPMENT_MOVEMENT
                    .name()
                    .equals(reservation.ownerType())
                || LogisticsEquipmentMovementReservationOwnerType.MAINTENANCE_DISPOSITION_MOVEMENT
                    .name()
                    .equals(reservation.ownerType()));
    if (reservation == null
        || !supportedOwner
        || !logisticsOwnerId(movementId, lineId).equals(reservation.ownerId())) {
      throw new AssetConflictException(
          "Equipment movement reservation belongs to another logistics movement line");
    }
  }

  private static boolean isMaintenanceDispositionReservation(EquipmentHoldResponse reservation) {
    return reservation != null
        && LogisticsEquipmentMovementReservationOwnerType.MAINTENANCE_DISPOSITION_MOVEMENT
            .name()
            .equals(reservation.ownerType());
  }

  private static boolean isMovableReservationSource(BalanceLocationKind kind) {
    return kind == BalanceLocationKind.STOCK
        || kind == BalanceLocationKind.CABIN_NON_RENTED
        || kind == BalanceLocationKind.CABIN_RENTED;
  }

  private static void validateExecutionRequest(
      ExecuteLogisticsEquipmentMovementReservationsRequest request) {
    if (request == null || request.movementId() == null || request.lines() == null || request.lines().isEmpty()) {
      throw new IllegalArgumentException("Equipment movement execution requires a movement and lines");
    }
    Set<UUID> reservationIds = new HashSet<>();
    Set<UUID> lineIds = new HashSet<>();
    for (ExecuteLogisticsEquipmentMovementReservationLine line : request.lines()) {
      if (line == null
          || line.reservationId() == null
          || line.expectedReservationVersion() == null
          || line.expectedReservationVersion() < 0
          || line.lineId() == null
          || line.targetWarehouseId() == null
          || line.targetLocationKind() == null) {
        throw new IllegalArgumentException("Equipment movement execution line is incomplete");
      }
      if (!reservationIds.add(line.reservationId()) || !lineIds.add(line.lineId())) {
        throw new IllegalArgumentException(
            "Equipment movement execution cannot repeat a reservation or line");
      }
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

  private static UUID ownerDocumentId(String ownerId) {
    return ownerIdPart(ownerId, 0);
  }

  private static UUID ownerLineId(String ownerId) {
    return ownerIdPart(ownerId, 1);
  }

  private static UUID ownerIdPart(String ownerId, int index) {
    String[] parts = ownerId == null ? new String[0] : ownerId.split(":", -1);
    if (parts.length != 2) {
      throw new AssetConflictException("Equipment movement reservation owner is malformed");
    }
    try {
      return UUID.fromString(parts[index]);
    } catch (IllegalArgumentException exception) {
      throw new AssetConflictException("Equipment movement reservation owner is malformed");
    }
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /**
   * Validated reservation, source balance, and requested line collected before execution planning
   * acquires target balances.
   */
  private record MovementReservationCandidate(
      EquipmentHoldResponse reservation,
      AssetBalanceRow source,
      ExecuteLogisticsEquipmentMovementReservationLine line) {}

  /**
   * Complete locked-balance execution plan, including any parent hold whose quantity must be
   * excluded from competing availability.
   */
  private record MovementReservationExecutionPlan(
      EquipmentHoldResponse reservation,
      AssetBalanceRow source,
      AssetBalanceRow target,
      ExecuteLogisticsEquipmentMovementReservationLine line,
      UUID nestedParentHoldId) {}

  /** Resource-bound lease request material used for stable idempotency hashing. */
  private record LogisticsLeaseCommand(UUID resourceId, LogisticsLeaseCommandRequest request) {}

  /** Resource-bound fenced effect material used for stable idempotency hashing. */
  private record LogisticsEffectCommand(UUID resourceId, LogisticsFencedEffectRequest request) {}

  /** Resource-bound equipment-hold material used for stable idempotency hashing. */
  private record LogisticsHoldCommand(UUID resourceId, LogisticsEquipmentHoldCommandRequest request) {}

  /**
   * Resource-bound movement-reservation material used to reject conflicting reuse of an
   * idempotency key.
   */
  private record LogisticsEquipmentMovementReservationCommand(
      UUID resourceId, LogisticsEquipmentMovementReservationCommandRequest request) {}
}
