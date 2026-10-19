package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Owns equipment availability reads, physical stock commands and general allocation holds.
 *
 * <p>Catalog metadata is delegated to {@link AssetEquipmentCatalogService}; this service owns
 * only workflows that consume or relocate physical balances. Every operation keeps its caller's
 * transaction and uses the ledger's ordered balance locks rather than exposing table state.
 */
@Service
final class AssetEquipmentService {
  private final AssetEquipmentCatalogService catalog;
  private final AssetEquipmentLedgerService ledger;
  private final AssetEquipmentHoldService holds;
  private final AssetLeaseService leases;
  private final AssetRentalItemService rentals;
  private final EquipmentAllocationPolicy allocationPolicy;
  private final AssetEventStore events;
  private final AssetIdempotencyStore idempotency;
  private final WarehouseRegistryClient warehouses;
  private final JdbcTemplate jdbc;
  private final AssetJsonCodec json;

  AssetEquipmentService(
      AssetEquipmentCatalogService catalog,
      AssetEquipmentLedgerService ledger,
      AssetEquipmentHoldService holds,
      AssetLeaseService leases,
      AssetRentalItemService rentals,
      EquipmentAllocationPolicy allocationPolicy,
      AssetEventStore events,
      AssetIdempotencyStore idempotency,
      WarehouseRegistryClient warehouses,
      JdbcTemplate jdbc,
      AssetJsonCodec json) {
    this.catalog = catalog;
    this.ledger = ledger;
    this.holds = holds;
    this.leases = leases;
    this.rentals = rentals;
    this.allocationPolicy = allocationPolicy;
    this.events = events;
    this.idempotency = idempotency;
    this.warehouses = warehouses;
    this.jdbc = jdbc;
    this.json = json;
  }

  List<EquipmentResponse> list() {
    return catalog.list();
  }

  EquipmentResponse equipment(UUID id) {
    return catalog.equipment(id);
  }

  AssetService.CreateResult<EquipmentResponse> create(
      UUID subjectId, UUID key, CreateEquipmentRequest request) {
    return catalog.create(subjectId, key, request);
  }

  EquipmentResponse update(UUID id, UpdateEquipmentRequest request) {
    return catalog.update(id, request);
  }

  List<EquipmentWarehouseResponse> atWarehouse(UUID warehouseId) {
    List<EquipmentCatalogItem> items = catalog.catalog();
    Map<UUID, List<EquipmentAllocationPolicy.SourceAvailability>> sources =
        allocationPolicy.sourcesAtWarehouse(warehouseId);
    Map<UUID, Long> reservations = activeOrderReservedAtWarehouse(warehouseId);
    Map<UUID, Long> outstanding = activeOrderOutstandingAtWarehouse(warehouseId);
    Set<UUID> historicalEquipment = equipmentHistoryAtWarehouse(warehouseId);
    return items.stream()
        .filter(item -> item.isActive() || historicalEquipment.contains(item.getId()))
        .map(
            item ->
                new EquipmentWarehouseResponse(
                    catalog.response(item),
                    totals(
                        item.getId(),
                        warehouseId,
                        sources.getOrDefault(item.getId(), List.of()),
                        reservations.getOrDefault(item.getId(), 0L),
                        outstanding.getOrDefault(item.getId(), 0L))))
        .toList();
  }

  EquipmentTotalsResponse totals(UUID equipmentId, UUID warehouseId) {
    catalog.require(equipmentId);
    List<EquipmentAllocationPolicy.SourceAvailability> availability =
        allocationPolicy.sources(equipmentId, warehouseId);
    return totals(
        equipmentId,
        warehouseId,
        availability,
        activeOrderReserved(equipmentId, warehouseId),
        activeOrderOutstanding(equipmentId, warehouseId));
  }

  /**
   * Computes own-order edit capacity without subtracting furniture already fulfilling that order.
   * Physical content in active order cabins satisfies the aggregate reserve once, while only other
   * orders' unfulfilled quantities fence the allocatable pool.
   */
  AssetService.OrderEquipmentCapacity orderCapacity(
      UUID equipmentId, UUID warehouseId, UUID orderId) {
    List<EquipmentAllocationPolicy.SourceAvailability> availability =
        allocationPolicy.sources(equipmentId, warehouseId);
    long allocatable =
        availability.stream()
            .mapToLong(EquipmentAllocationPolicy.SourceAvailability::availableQuantity)
            .sum();
    Long orderPhysical =
        jdbc.queryForObject(
            """
            select coalesce(sum(balance.quantity),0)
            from order_unit_reservation unit_reservation
            join equipment_balance balance
              on balance.rental_item_id=unit_reservation.rental_item_id
             and balance.equipment_id=?
             and balance.warehouse_id=?
             and balance.location_kind in ('CABIN_NON_RENTED','CABIN_RENTED')
            where unit_reservation.order_id=?
              and unit_reservation.warehouse_id=?
              and unit_reservation.state='ACTIVE'
            """,
            Long.class,
            equipmentId,
            warehouseId,
            orderId,
            warehouseId);
    Long outstandingOther =
        jdbc.queryForObject(
            """
            select coalesce(sum(greatest(
              reservation.quantity - coalesce((
                select sum(balance.quantity)
                from order_unit_reservation unit_reservation
                join equipment_balance balance
                  on balance.rental_item_id=unit_reservation.rental_item_id
                 and balance.equipment_id=reservation.equipment_id
                 and balance.warehouse_id=reservation.warehouse_id
                 and balance.location_kind in ('CABIN_NON_RENTED','CABIN_RENTED')
                where unit_reservation.order_id=reservation.order_id
                  and unit_reservation.warehouse_id=reservation.warehouse_id
                  and unit_reservation.state='ACTIVE'
              ),0), 0)),0)
            from order_equipment_reservation reservation
            where reservation.equipment_id=?
              and reservation.warehouse_id=?
              and reservation.order_id<>?
              and reservation.state='ACTIVE'
            """,
            Long.class,
            equipmentId,
            warehouseId,
            orderId);
    return new AssetService.OrderEquipmentCapacity(
        allocatable,
        orderPhysical == null ? 0 : orderPhysical,
        outstandingOther == null ? 0 : outstandingOther);
  }

  List<EquipmentDispositionResponse> dispositions(UUID warehouseId) {
    return jdbc.query(
        """
        select m.id,m.version,m.equipment_id,m.source_balance_id,m.target_balance_id,m.quantity,m.movement_kind,m.occurred_at,
          c.name
        from equipment_movement m
        join equipment_catalog_item c on c.id=m.equipment_id
        join equipment_balance target on target.id=m.target_balance_id
        where target.warehouse_id=? and m.movement_kind in ('WRITE_OFF','LOSS')
        order by m.occurred_at desc,m.id desc
        """,
        (rs, row) ->
            new EquipmentDispositionResponse(
                new MovementResponse(
                    rs.getObject("id", UUID.class),
                    rs.getLong("version"),
                    rs.getObject("equipment_id", UUID.class),
                    rs.getObject("source_balance_id", UUID.class),
                    rs.getObject("target_balance_id", UUID.class),
                    rs.getLong("quantity"),
                    rs.getString("movement_kind"),
                    rs.getObject("occurred_at", OffsetDateTime.class)),
                rs.getString("name")),
        warehouseId);
  }

  /**
   * Receives append-only initial furniture evidence for one newly-created reviewed HTML import
   * row. Retrying the same source row validates its immutable evidence rather than rewriting a
   * cabin balance.
   */
  void recordHtmlImportEquipmentReceipts(
      UUID importId,
      String sourceRowId,
      UUID actorSubjectId,
      UUID rentalItemId,
      Map<UUID, Long> quantities) {
    if (importId == null
        || sourceRowId == null
        || sourceRowId.isBlank()
        || sourceRowId.length() > 64
        || actorSubjectId == null
        || rentalItemId == null
        || quantities == null
        || quantities.isEmpty()
        || quantities.size() > 100) {
      throw new IllegalArgumentException("HTML import equipment receipt is invalid");
    }
    List<Map.Entry<UUID, Long>> entries =
        quantities.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    for (Map.Entry<UUID, Long> entry : entries) {
      if (entry.getKey() == null
          || entry.getValue() == null
          || entry.getValue() < 1
          || entry.getValue() > 1_000_000) {
        throw new IllegalArgumentException("HTML import equipment quantity is invalid");
      }
    }
    advisoryLock("html-import-equipment-receipt:" + importId + ':' + sourceRowId);
    RentalItem rentalItem = rentals.require(rentalItemId);
    if (!AssetRentalItemService.isHtmlImportManualStatus(rentalItem.getStatus())) {
      throw new AssetConflictException(
          "HTML import can only receive furniture into a manual-status cabin");
    }
    Boolean createdByImport =
        jdbc.queryForObject(
            """
            select exists(
              select 1
              from rental_item_html_import_row
              where import_id=?
                and source_row_id=?
                and target_rental_item_id=?
                and action='CREATE')
            """,
            Boolean.class,
            importId,
            sourceRowId,
            rentalItemId);
    if (!Boolean.TRUE.equals(createdByImport)) {
      throw new AssetConflictException(
          "HTML import equipment receipt requires a newly-created source-row cabin");
    }
    List<HtmlImportEquipmentReceiptRow> existing =
        findHtmlImportEquipmentReceipts(importId, sourceRowId);
    if (!existing.isEmpty()) {
      verifyHtmlImportEquipmentReceiptReplay(
          existing, entries, actorSubjectId, rentalItemId, rentalItem.getWarehouseId());
      return;
    }
    leases.assertNoActive(rentalItemId);
    leases.assertNoActiveOrderReservation(
        rentalItemId, "Reserved order unit cannot receive HTML import furniture");
    Boolean livePresentationHold =
        jdbc.queryForObject(
            """
            select exists(
              select 1 from presentation_unit_hold
              where rental_item_id=?
                and state='ACTIVE'
                and expires_at>clock_timestamp())
            """,
            Boolean.class,
            rentalItemId);
    if (Boolean.TRUE.equals(livePresentationHold)) {
      throw new AssetConflictException("Presented rental-item cannot receive HTML import furniture");
    }
    Boolean hasExistingContents =
        jdbc.queryForObject(
            """
            select exists(
              select 1
              from equipment_balance
              where rental_item_id=?
                and location_kind in ('CABIN_NON_RENTED','CABIN_RENTED')
                and quantity>0)
            """,
            Boolean.class,
            rentalItemId);
    if (Boolean.TRUE.equals(hasExistingContents)) {
      throw new AssetConflictException("HTML import never rewrites equipment contents of a live cabin");
    }
    for (Map.Entry<UUID, Long> entry : entries) {
      EquipmentCatalogItem catalogItem = catalog.require(entry.getKey());
      if (!catalogItem.isActive() || catalogItem.getCategory() != EquipmentCategory.FURNITURE) {
        throw new AssetConflictException("HTML import equipment target must be active furniture");
      }
      ledger.lock(
          entry.getKey(),
          rentalItem.getWarehouseId(),
          rentalItemId,
          BalanceLocationKind.CABIN_NON_RENTED);
    }
    for (Map.Entry<UUID, Long> entry : entries) {
      AssetBalanceRow target =
          ledger
              .findForUpdate(
                  entry.getKey(),
                  rentalItem.getWarehouseId(),
                  rentalItemId,
                  BalanceLocationKind.CABIN_NON_RENTED)
              .orElseGet(
                  () ->
                      ledger.createEmpty(
                          entry.getKey(),
                          rentalItem.getWarehouseId(),
                          rentalItemId,
                          BalanceLocationKind.CABIN_NON_RENTED));
      if (target.quantity() != 0) {
        throw new AssetConflictException("HTML import equipment receipt target balance is not empty");
      }
      long streamVersion =
          events.lockCurrentVersion(AssetAggregateType.EQUIPMENT_BALANCE, target.id());
      if (streamVersion != target.version()) {
        throw new AssetConflictException("HTML import equipment balance changed concurrently");
      }
      ledger.increment(target, entry.getValue(), streamVersion);
      AssetBalanceRow after = ledger.requireForUpdate(target.id());
      events.append(
          AssetAggregateType.EQUIPMENT_BALANCE,
          after.id(),
          streamVersion,
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          ledger.fact(after),
          ledger.snapshot(after));
      jdbc.update(
          """
          insert into rental_item_html_import_equipment_receipt(
            id,import_id,source_row_id,actor_subject_id,reason,rental_item_id,
            equipment_id,target_balance_id,quantity,recorded_at)
          values (?,?,?,?, 'HTML_IMPORT_INITIAL_CONTENTS', ?,?,?,?,clock_timestamp())
          """,
          UUID.randomUUID(),
          importId,
          sourceRowId,
          actorSubjectId,
          rentalItemId,
          entry.getKey(),
          after.id(),
          entry.getValue());
    }
  }

  AssetService.CreateResult<MovementResponse> transfer(
      UUID subjectId, UUID key, TransferEquipmentRequest request) {
    if (!request.sourceWarehouseId().equals(request.targetWarehouseId())) {
      throw new AssetConflictException(
          "Public equipment transfer is limited to locations inside one warehouse; use logistics for inter-warehouse movement");
    }
    if (request.targetLocationKind() == BalanceLocationKind.WRITTEN_OFF
        || request.targetLocationKind() == BalanceLocationKind.LOST) {
      throw new IllegalArgumentException("Use the disposition command for written-off or lost equipment");
    }
    return move(
        subjectId,
        key,
        "equipment.transfer",
        request,
        AssetEventType.EQUIPMENT_TRANSFERRED,
        false);
  }

  AssetService.CreateResult<MovementResponse> dispose(
      UUID subjectId, UUID key, DispositionEquipmentRequest request) {
    BalanceLocationKind target =
        request.disposition() == Disposition.WRITE_OFF
            ? BalanceLocationKind.WRITTEN_OFF
            : BalanceLocationKind.LOST;
    TransferEquipmentRequest move =
        new TransferEquipmentRequest(
            request.equipmentId(),
            request.warehouseId(),
            request.sourceRentalItemId(),
            request.sourceLocationKind(),
            request.sourceExpectedVersion(),
            request.warehouseId(),
            null,
            target,
            0L,
            request.quantity());
    AssetEventType eventType =
        request.disposition() == Disposition.WRITE_OFF
            ? AssetEventType.EQUIPMENT_WRITTEN_OFF
            : AssetEventType.EQUIPMENT_LOST;
    return move(subjectId, key, "equipment.disposition", move, eventType, true);
  }

  AssetService.CreateResult<EquipmentHoldResponse> acquireHold(
      UUID subjectId, UUID key, AcquireEquipmentHoldRequest request) {
    String hash = json.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "equipment-hold.acquire", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), EquipmentHoldResponse.class), true);
    }
    warehouses.requireOutgoing(request.warehouseId());
    catalog.require(request.equipmentId());
    ledger.lock(request.equipmentId(), request.warehouseId(), null, BalanceLocationKind.STOCK);
    AssetBalanceRow stock =
        ledger.require(request.equipmentId(), request.warehouseId(), null, BalanceLocationKind.STOCK);
    holds.expireFor(stock);
    AssetLeaseService.assertVersion(stock.version(), request.expectedStockVersion());
    long available = Math.subtractExact(stock.quantity(), holds.activeHeld(stock));
    if (available < request.quantity()) {
      throw new AssetConflictException("Active equipment holds reduce available stock");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime expiry = holds.expiry();
    jdbc.update(
        """
        insert into equipment_allocation_hold(id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,quantity,state,idempotency_key,expires_at,created_at,updated_at)
        values (?,0,?,?,?,?,?,?,'ACTIVE',?,?,clock_timestamp(),clock_timestamp())
        """,
        id,
        request.equipmentId(),
        request.warehouseId(),
        stock.id(),
        canonicalOwnerType(request.ownerType()),
        canonicalOwnerId(request.ownerId()),
        request.quantity(),
        key,
        expiry);
    EquipmentHoldResponse response = holds.forUpdate(id);
    events.initialize(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        id,
        0,
        AssetEventType.EQUIPMENT_HOLD_ACQUIRED,
        holds.fact(response),
        holds.snapshot(response));
    idempotency.store(subjectId, "equipment-hold.acquire", key, hash, 201, response);
    return new AssetService.CreateResult<>(response, false);
  }

  AssetService.CreateResult<EquipmentHoldResponse> renewHold(
      UUID subjectId, UUID key, UUID id, RenewEquipmentHoldRequest request) {
    String hash = json.hash(new ResourceCommand<>(id, request));
    Optional<JsonNode> replay = idempotency.replay(subjectId, "equipment-hold.renew", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), EquipmentHoldResponse.class), true);
    }
    EquipmentHoldResponse current = holds.forUpdate(id);
    AssetLeaseService.assertVersion(current.version(), request.expectedVersion());
    if (!"ACTIVE".equals(current.state()) || current.expiresAt().isBefore(now())) {
      throw new AssetConflictException("Equipment hold is not active");
    }
    jdbc.update(
        "update equipment_allocation_hold set version=version+1,expires_at=?,updated_at=clock_timestamp() where id=? and version=?",
        holds.expiry(),
        id,
        request.expectedVersion());
    EquipmentHoldResponse updated = holds.forUpdate(id);
    events.append(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        id,
        request.expectedVersion(),
        AssetEventType.EQUIPMENT_HOLD_RENEWED,
        holds.fact(updated),
        holds.snapshot(updated));
    idempotency.store(subjectId, "equipment-hold.renew", key, hash, 200, updated);
    return new AssetService.CreateResult<>(updated, false);
  }

  /**
   * Commits an allocation hold without inferring shipment, transfer or inventory movement from an
   * opaque owner reference. The committed hold remains part of availability until its owner
   * releases it.
   */
  AssetService.CreateResult<EquipmentHoldResponse> commitHold(
      UUID subjectId, UUID key, UUID id, CommitEquipmentHoldRequest request) {
    String hash = json.hash(new ResourceCommand<>(id, request));
    Optional<JsonNode> replay = idempotency.replay(subjectId, "equipment-hold.commit", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), EquipmentHoldResponse.class), true);
    }
    EquipmentHoldResponse current = holds.forUpdate(id);
    holds.expireFor(current);
    current = holds.forUpdate(id);
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
    idempotency.store(subjectId, "equipment-hold.commit", key, hash, 200, committed);
    return new AssetService.CreateResult<>(committed, false);
  }

  AssetService.CreateResult<EquipmentHoldResponse> releaseHold(
      UUID subjectId, UUID key, UUID id, ReleaseEquipmentHoldRequest request) {
    String hash = json.hash(new ResourceCommand<>(id, request));
    Optional<JsonNode> replay = idempotency.replay(subjectId, "equipment-hold.release", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), EquipmentHoldResponse.class), true);
    }
    EquipmentHoldResponse current = holds.forUpdate(id);
    holds.expireFor(current);
    current = holds.forUpdate(id);
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
    idempotency.store(subjectId, "equipment-hold.release", key, hash, 200, updated);
    return new AssetService.CreateResult<>(updated, false);
  }

  void expireDueHolds() {
    holds.expireDue();
  }

  /**
   * Executes a direct ledger move after locking both cabin identities and rejecting active leases or
   * presentation snapshots on either side. These identity locks serialize the hold-vs-move race
   * before any balance is changed or an idempotency receipt is stored.
   */
  private AssetService.CreateResult<MovementResponse> move(
      UUID subjectId,
      UUID key,
      String idempotencyScope,
      TransferEquipmentRequest request,
      AssetEventType eventType,
      boolean resolveTargetVersionFromServer) {
    String hash = json.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, idempotencyScope, key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), MovementResponse.class), true);
    }
    if (AssetEquipmentLedgerService.isTerminalLocation(request.sourceLocationKind())) {
      throw new AssetConflictException("Written-off or lost equipment cannot be moved again");
    }
    warehouses.requireOutgoing(request.sourceWarehouseId());
    if (!AssetEquipmentLedgerService.isTerminalLocation(request.targetLocationKind())) {
      warehouses.requireIncoming(request.targetWarehouseId());
    }
    catalog.require(request.equipmentId());
    List<UUID> cabins =
        java.util.stream.Stream.of(request.sourceRentalItemId(), request.targetRentalItemId())
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
    cabins.forEach(leases::lockRentalItem);
    cabins.forEach(leases::assertNoActive);
    cabins.forEach(leases::assertNoActivePresentationHold);
    ledger.validateLocation(
        request.sourceWarehouseId(), request.sourceRentalItemId(), request.sourceLocationKind());
    ledger.validateLocation(
        request.targetWarehouseId(), request.targetRentalItemId(), request.targetLocationKind());
    if (sameLocation(request)) {
      throw new AssetConflictException("Equipment transfer source and target must differ");
    }
    ledger.lockAll(
        List.of(
            AssetEquipmentLedgerService.balanceLockKey(
                request.equipmentId(),
                request.sourceWarehouseId(),
                request.sourceRentalItemId(),
                request.sourceLocationKind()),
            AssetEquipmentLedgerService.balanceLockKey(
                request.equipmentId(),
                request.targetWarehouseId(),
                request.targetRentalItemId(),
                request.targetLocationKind())));
    AssetBalanceRow source =
        ledger.require(
            request.equipmentId(),
            request.sourceWarehouseId(),
            request.sourceRentalItemId(),
            request.sourceLocationKind());
    AssetLeaseService.assertVersion(source.version(), request.sourceExpectedVersion());
    AssetBalanceRow target =
        ledger
            .findForUpdate(
                request.equipmentId(),
                request.targetWarehouseId(),
                request.targetRentalItemId(),
                request.targetLocationKind())
            .orElseGet(
                () ->
                    ledger.createEmpty(
                        request.equipmentId(),
                        request.targetWarehouseId(),
                        request.targetRentalItemId(),
                        request.targetLocationKind()));
    long targetExpectedVersion =
        resolveTargetVersionFromServer ? target.version() : request.targetExpectedVersion();
    AssetLeaseService.assertVersion(target.version(), targetExpectedVersion);
    if (source.quantity() < request.quantity()) {
      throw new AssetConflictException("Equipment balance cannot become negative");
    }
    if (Math.subtractExact(source.quantity(), request.quantity()) < holds.activeHeld(source)) {
      throw new AssetConflictException(
          "Equipment transfer would consume quantities reserved by an active hold");
    }
    events.lockStreams(
        List.of(
            new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, source.id()),
            new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, target.id())));
    ledger.decrement(source, request.quantity(), request.sourceExpectedVersion());
    ledger.increment(target, request.quantity(), targetExpectedVersion);
    AssetBalanceRow sourceAfter = ledger.requireForUpdate(source.id());
    AssetBalanceRow targetAfter = ledger.requireForUpdate(target.id());
    events.append(
        AssetAggregateType.EQUIPMENT_BALANCE,
        source.id(),
        request.sourceExpectedVersion(),
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        ledger.fact(sourceAfter),
        ledger.snapshot(sourceAfter));
    events.append(
        AssetAggregateType.EQUIPMENT_BALANCE,
        target.id(),
        targetExpectedVersion,
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        ledger.fact(targetAfter),
        ledger.snapshot(targetAfter));
    UUID movementId = UUID.randomUUID();
    String kind =
        AssetEquipmentLedgerService.movementKind(
            request.sourceLocationKind(), request.targetLocationKind());
    jdbc.update(
        "insert into equipment_movement(id,version,equipment_id,source_balance_id,target_balance_id,quantity,movement_kind,occurred_at,actor_subject_id) values (?,0,?,?,?,?,?,clock_timestamp(),?)",
        movementId,
        request.equipmentId(),
        source.id(),
        target.id(),
        request.quantity(),
        kind,
        subjectId);
    jdbc.update(
        "insert into equipment_movement_ledger(movement_id,line_no,balance_id,quantity_delta,recorded_at) values (?,1,?,-?,clock_timestamp()), (?,2,?,?,clock_timestamp())",
        movementId,
        source.id(),
        request.quantity(),
        movementId,
        target.id(),
        request.quantity());
    MovementResponse response = ledger.movementResponse(movementId);
    events.initialize(
        AssetAggregateType.EQUIPMENT_MOVEMENT,
        movementId,
        0,
        eventType,
        ledger.movementFact(response),
        Map.of("movementId", movementId.toString(), "version", 0));
    idempotency.store(subjectId, idempotencyScope, key, hash, 201, response);
    return new AssetService.CreateResult<>(response, false);
  }

  /**
   * Reports global free quantity as allocatable physical stock minus only outstanding order
   * reservations, avoiding a second subtraction for contents already in active order cabins.
   */
  private EquipmentTotalsResponse totals(
      UUID equipmentId,
      UUID warehouseId,
      List<EquipmentAllocationPolicy.SourceAvailability> availability,
      long reserved,
      long outstanding) {
    List<AssetBalanceRow> rows =
        availability.stream()
            .map(
                source ->
                    new AssetBalanceRow(
                        source.balanceId(),
                        source.version(),
                        source.equipmentId(),
                        source.warehouseId(),
                        source.rentalItemId(),
                        source.locationKind(),
                        source.quantity()))
            .toList();
    long stock = AssetEquipmentLedgerService.sum(rows, BalanceLocationKind.STOCK);
    long nonRented = AssetEquipmentLedgerService.sum(rows, BalanceLocationKind.CABIN_NON_RENTED);
    long rented = AssetEquipmentLedgerService.sum(rows, BalanceLocationKind.CABIN_RENTED);
    long writtenOff = AssetEquipmentLedgerService.sum(rows, BalanceLocationKind.WRITTEN_OFF);
    long lost = AssetEquipmentLedgerService.sum(rows, BalanceLocationKind.LOST);
    long activeHeld =
        availability.stream()
            .filter(EquipmentAllocationPolicy.SourceAvailability::allocatable)
            .mapToLong(EquipmentAllocationPolicy.SourceAvailability::activeHeldQuantity)
            .sum();
    List<EquipmentBalanceResponse> values =
        availability.stream().map(AssetEquipmentLedgerService::response).toList();
    long physicalAvailable =
        availability.stream().mapToLong(EquipmentAllocationPolicy.SourceAvailability::availableQuantity).sum();
    long available = Math.max(0, Math.subtractExact(physicalAvailable, outstanding));
    long availableStock =
        availability.stream()
            .filter(source -> source.locationKind() == BalanceLocationKind.STOCK)
            .mapToLong(EquipmentAllocationPolicy.SourceAvailability::availableQuantity)
            .sum();
    return new EquipmentTotalsResponse(
        equipmentId,
        warehouseId,
        Math.addExact(
            Math.addExact(Math.addExact(stock, nonRented), Math.addExact(rented, writtenOff)), lost),
        stock,
        nonRented,
        rented,
        writtenOff,
        lost,
        activeHeld,
        reserved,
        available,
        availableStock,
        values);
  }

  private long activeOrderReserved(UUID equipmentId, UUID warehouseId) {
    Long result =
        jdbc.queryForObject(
            """
            select coalesce(sum(quantity), 0)
            from order_equipment_reservation
            where equipment_id=? and warehouse_id=? and state='ACTIVE'
            """,
            Long.class,
            equipmentId,
            warehouseId);
    return result == null ? 0 : result;
  }

  private long activeOrderOutstanding(UUID equipmentId, UUID warehouseId) {
    Long result =
        jdbc.queryForObject(
            """
            select coalesce(sum(greatest(
              reservation.quantity - coalesce((
                select sum(balance.quantity)
                from order_unit_reservation unit_reservation
                join equipment_balance balance
                  on balance.rental_item_id=unit_reservation.rental_item_id
                 and balance.equipment_id=reservation.equipment_id
                 and balance.warehouse_id=reservation.warehouse_id
                 and balance.location_kind in ('CABIN_NON_RENTED','CABIN_RENTED')
                where unit_reservation.order_id=reservation.order_id
                  and unit_reservation.warehouse_id=reservation.warehouse_id
                  and unit_reservation.state='ACTIVE'
              ),0), 0)),0)
            from order_equipment_reservation reservation
            where reservation.equipment_id=?
              and reservation.warehouse_id=?
              and reservation.state='ACTIVE'
            """,
            Long.class,
            equipmentId,
            warehouseId);
    return result == null ? 0 : result;
  }

  private Map<UUID, Long> activeOrderReservedAtWarehouse(UUID warehouseId) {
    Map<UUID, Long> values = new LinkedHashMap<>();
    jdbc.query(
            """
            select equipment_id,coalesce(sum(quantity),0) reserved_quantity
            from order_equipment_reservation
            where warehouse_id=? and state='ACTIVE'
            group by equipment_id
            order by equipment_id
            """,
            (result, row) ->
                Map.entry(
                    result.getObject("equipment_id", UUID.class),
                    result.getLong("reserved_quantity")),
            warehouseId)
        .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
    return Map.copyOf(values);
  }

  private Map<UUID, Long> activeOrderOutstandingAtWarehouse(UUID warehouseId) {
    Map<UUID, Long> values = new LinkedHashMap<>();
    jdbc.query(
            """
            select reservation.equipment_id,
              coalesce(sum(greatest(
                reservation.quantity - coalesce((
                  select sum(balance.quantity)
                  from order_unit_reservation unit_reservation
                  join equipment_balance balance
                    on balance.rental_item_id=unit_reservation.rental_item_id
                   and balance.equipment_id=reservation.equipment_id
                   and balance.warehouse_id=reservation.warehouse_id
                   and balance.location_kind in ('CABIN_NON_RENTED','CABIN_RENTED')
                  where unit_reservation.order_id=reservation.order_id
                    and unit_reservation.warehouse_id=reservation.warehouse_id
                    and unit_reservation.state='ACTIVE'
                ),0), 0)),0) outstanding_quantity
            from order_equipment_reservation reservation
            where reservation.warehouse_id=? and reservation.state='ACTIVE'
            group by reservation.equipment_id
            order by reservation.equipment_id
            """,
            (result, row) ->
                Map.entry(
                    result.getObject("equipment_id", UUID.class),
                    result.getLong("outstanding_quantity")),
            warehouseId)
        .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
    return Map.copyOf(values);
  }

  private Set<UUID> equipmentHistoryAtWarehouse(UUID warehouseId) {
    return Set.copyOf(
        jdbc.query(
            """
            select equipment_id
            from (
              select equipment_id
              from equipment_balance
              where warehouse_id=?
              union all
              select movement.equipment_id
              from equipment_movement movement
              join equipment_balance source on source.id=movement.source_balance_id
              join equipment_balance target on target.id=movement.target_balance_id
              where source.warehouse_id=? or target.warehouse_id=?
            ) history
            group by equipment_id
            order by equipment_id
            """,
            (result, row) -> result.getObject("equipment_id", UUID.class),
            warehouseId,
            warehouseId,
            warehouseId));
  }

  private List<HtmlImportEquipmentReceiptRow> findHtmlImportEquipmentReceipts(
      UUID importId, String sourceRowId) {
    return jdbc.query(
        """
        select id,actor_subject_id,reason,rental_item_id,equipment_id,target_balance_id,quantity
        from rental_item_html_import_equipment_receipt
        where import_id=? and source_row_id=?
        order by equipment_id
        """,
        (rs, row) ->
            new HtmlImportEquipmentReceiptRow(
                rs.getObject("id", UUID.class),
                rs.getObject("actor_subject_id", UUID.class),
                rs.getString("reason"),
                rs.getObject("rental_item_id", UUID.class),
                rs.getObject("equipment_id", UUID.class),
                rs.getObject("target_balance_id", UUID.class),
                rs.getLong("quantity")),
        importId,
        sourceRowId);
  }

  private void verifyHtmlImportEquipmentReceiptReplay(
      List<HtmlImportEquipmentReceiptRow> existing,
      List<Map.Entry<UUID, Long>> entries,
      UUID actorSubjectId,
      UUID rentalItemId,
      UUID warehouseId) {
    Map<UUID, HtmlImportEquipmentReceiptRow> byEquipment =
        existing.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    HtmlImportEquipmentReceiptRow::equipmentId,
                    java.util.function.Function.identity()));
    if (byEquipment.size() != entries.size()) {
      throw new AssetConflictException(
          "HTML import equipment receipt conflicts with immutable source evidence");
    }
    for (Map.Entry<UUID, Long> entry : entries) {
      HtmlImportEquipmentReceiptRow receipt = byEquipment.get(entry.getKey());
      if (receipt == null
          || !receipt.actorSubjectId().equals(actorSubjectId)
          || !"HTML_IMPORT_INITIAL_CONTENTS".equals(receipt.reason())
          || !receipt.rentalItemId().equals(rentalItemId)
          || receipt.quantity() != entry.getValue()) {
        throw new AssetConflictException(
            "HTML import equipment receipt conflicts with immutable source evidence");
      }
      AssetBalanceRow target = ledger.read(receipt.targetBalanceId());
      if (!target.equipmentId().equals(entry.getKey())
          || !target.warehouseId().equals(warehouseId)
          || !rentalItemId.equals(target.rentalItemId())
          || target.kind() != BalanceLocationKind.CABIN_NON_RENTED) {
        throw new AssetConflictException("HTML import equipment receipt target is malformed");
      }
    }
  }

  private static boolean sameLocation(TransferEquipmentRequest request) {
    return request.sourceWarehouseId().equals(request.targetWarehouseId())
        && java.util.Objects.equals(request.sourceRentalItemId(), request.targetRentalItemId())
        && request.sourceLocationKind() == request.targetLocationKind();
  }

  private static String canonicalOwnerType(String value) {
    String result = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    if (!result.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
      throw new IllegalArgumentException("ownerType has invalid format");
    }
    return result;
  }

  private static String canonicalOwnerId(String value) {
    String result = value == null ? "" : value.trim();
    if (result.isEmpty() || result.length() > 128) {
      throw new IllegalArgumentException("ownerId has invalid format");
    }
    return result;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private void advisoryLock(String value) {
    jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, value);
  }

  /** Canonical resource-and-payload envelope hashed for idempotent hold commands. */
  private record ResourceCommand<T>(UUID resourceId, T request) {}

  /**
   * Persisted initial-content receipt used to prove that an HTML import replay matches the
   * original cabin, equipment, target balance, actor, and quantity.
   */
  private record HtmlImportEquipmentReceiptRow(
      UUID id,
      UUID actorSubjectId,
      String reason,
      UUID rentalItemId,
      UUID equipmentId,
      UUID targetBalanceId,
      long quantity) {}
}
