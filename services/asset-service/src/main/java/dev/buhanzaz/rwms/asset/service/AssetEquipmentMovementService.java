package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderEquipmentRequirement;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderFurnitureMovementPlanLine;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitEquipmentRequirements;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitReplacementMovementBundle;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitReplacementMovementLine;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.OrderEquipmentReservation;
import dev.buhanzaz.rwms.asset.domain.OrderEquipmentReservationState;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.repository.OrderEquipmentReservationRepository;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * Owns task-bound equipment movement reservations, replacement preallocation, and fenced
 * execution against the asset ledger.
 */
@Service
final class AssetEquipmentMovementService {
  private final AssetLeaseService leases;
  private final AssetEquipmentCatalogService catalog;
  private final AssetEquipmentLedgerService ledger;
  private final AssetEquipmentHoldService holds;
  private final AssetIdempotencyStore idempotency;
  private final AssetEventStore events;
  private final WarehouseRegistryClient warehouses;
  private final EquipmentAllocationPolicy allocationPolicy;
  private final OrderEquipmentReservationRepository orderEquipmentReservations;
  private final OrderUnitReservationRepository orderUnitReservations;
  private final JdbcTemplate jdbc;
  private final AssetJsonCodec json;

  AssetEquipmentMovementService(
      AssetLeaseService leases,
      AssetEquipmentCatalogService catalog,
      AssetEquipmentLedgerService ledger,
      AssetEquipmentHoldService holds,
      AssetIdempotencyStore idempotency,
      AssetEventStore events,
      WarehouseRegistryClient warehouses,
      EquipmentAllocationPolicy allocationPolicy,
      OrderEquipmentReservationRepository orderEquipmentReservations,
      OrderUnitReservationRepository orderUnitReservations,
      JdbcTemplate jdbc,
      AssetJsonCodec json) {
    this.leases = leases;
    this.catalog = catalog;
    this.ledger = ledger;
    this.holds = holds;
    this.idempotency = idempotency;
    this.events = events;
    this.warehouses = warehouses;
    this.allocationPolicy = allocationPolicy;
    this.orderEquipmentReservations = orderEquipmentReservations;
    this.orderUnitReservations = orderUnitReservations;
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Acquires a task-deadline reservation on stock or one canonical cabin balance. Same-order moves
   * hold the order composition and equipment namespaces before capping source surplus and target
   * deficit. Replacement provenance is replay-only: the exact row must have been pre-created by the
   * atomic cabin swap and its released-source/active-target composition is revalidated.
   */
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
    if (!request.isOrderContextComplete()) {
      throw new IllegalArgumentException(
          "Order movement context must be fully present or fully absent");
    }
    boolean orderContext = request.orderId() != null;
    if (orderContext) {
      if (request.purpose() != LogisticsEquipmentMovementPurpose.ALLOCATABLE_REBALANCE
          || request.sourceRentalItemId() == null
          || request.sourceLocationKind() != BalanceLocationKind.CABIN_NON_RENTED
          || request.sourceRentalItemId().equals(request.targetRentalItemId())) {
        throw new IllegalArgumentException(
            "Order movement context requires distinct warehouse-side source and target cabins");
      }
      orderUnitReservations.acquireTransactionLock("order-composition:" + request.orderId());
      orderEquipmentReservations.acquireTransactionLock(
          "order-equipment:" + request.sourceWarehouseId() + ":" + request.equipmentId());
    }
    warehouses.requireOutgoing(request.sourceWarehouseId());
    EquipmentCatalogItem catalogItem = catalog.require(request.equipmentId());
    ledger.validateLocation(
        request.sourceWarehouseId(), request.sourceRentalItemId(), request.sourceLocationKind());
    List<UUID> cabinLockIds =
        java.util.stream.Stream.of(
                request.sourceRentalItemId(),
                orderContext ? request.targetRentalItemId() : null)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    cabinLockIds.forEach(leases::lockRentalItem);
    if (request.purpose() == LogisticsEquipmentMovementPurpose.ALLOCATABLE_REBALANCE) {
      cabinLockIds.forEach(leases::assertNoActive);
    }
    cabinLockIds.forEach(leases::assertNoActivePresentationHold);
    List<String> balanceLocks =
        new ArrayList<>(
            List.of(
                AssetEquipmentLedgerService.balanceLockKey(
                    request.equipmentId(),
                    request.sourceWarehouseId(),
                    request.sourceRentalItemId(),
                    request.sourceLocationKind())));
    if (orderContext) {
      balanceLocks.add(
          AssetEquipmentLedgerService.balanceLockKey(
              request.equipmentId(),
              request.sourceWarehouseId(),
              request.targetRentalItemId(),
              BalanceLocationKind.CABIN_NON_RENTED));
      balanceLocks.add(
          AssetEquipmentLedgerService.balanceLockKey(
              request.equipmentId(),
              request.sourceWarehouseId(),
              request.targetRentalItemId(),
              BalanceLocationKind.CABIN_RENTED));
    }
    ledger.lockAll(balanceLocks);
    AssetBalanceRow source =
        ledger.require(
            request.equipmentId(),
            request.sourceWarehouseId(),
            request.sourceRentalItemId(),
            request.sourceLocationKind());
    holds.expireFor(source);
    AssetLeaseService.assertVersion(source.version(), request.expectedSourceBalanceVersion());
    OffsetDateTime acquiredAt = now();
    if (!request.reservedUntil().isAfter(acquiredAt)) {
      throw new IllegalArgumentException("reservedUntil must be in the future");
    }
    MovementReservationRow prepared =
        activeMovementReservation(request.movementId(), request.lineId());
    OrderMovementComposition orderComposition =
        orderContext
            ? requireOrderMovementComposition(
                request.orderId(),
                request.sourceWarehouseId(),
                request.sourceRentalItemId(),
                request.targetRentalItemId(),
                request.units(),
                request.replacementSourceReservationId())
            : null;
    if (prepared != null) {
      assertPreparedMovementReservation(prepared, request, source);
      LogisticsEquipmentMovementReservationResponse response =
          movementReservationResponse(prepared.hold(), catalogItem, source);
      idempotency.store(
          subjectId,
          "logistics.equipment-movement-reservation.acquire",
          key,
          hash,
          200,
          response);
      return new AssetService.CreateResult<>(response, true);
    }
    if (request.replacementSourceReservationId() != null) {
      throw new AssetConflictException(
          "Replacement furniture movement must replay an atomic replacement reservation");
    }
    Set<UUID> nestedHoldIds;
    LogisticsEquipmentMovementReservationOwnerType ownerType;
    if (request.purpose() == LogisticsEquipmentMovementPurpose.MAINTENANCE_DISPOSITION) {
      nestedHoldIds = Set.of(requireMaintenanceParentHold(request, source));
      ownerType = LogisticsEquipmentMovementReservationOwnerType.MAINTENANCE_DISPOSITION_MOVEMENT;
    } else {
      if (orderContext) {
        assertOrderMovementCapacity(request, source, orderComposition);
      } else {
        requireAllocatableSource(source);
      }
      nestedHoldIds = Set.of();
      ownerType = LogisticsEquipmentMovementReservationOwnerType.LOGISTICS_EQUIPMENT_MOVEMENT;
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
            quantity,state,idempotency_key,expires_at,order_id,target_rental_item_id,order_units,
            replacement_source_reservation_id,created_at,updated_at)
          values (?,0,?,?,?,?,?,?,'ACTIVE',?,?,?,?,?::jsonb,?,?,?)
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
          request.orderId(),
          request.targetRentalItemId(),
          orderContext ? json.jsonArray(request.units()) : null,
          null,
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

  /**
   * Pre-creates every ordinary logistics movement reservation for one all-or-nothing cabin swap.
   * All source balances are locked in a stable global order before any hold is inserted, so a
   * later pair cannot fail after an earlier pair escaped the transaction boundary. The normal
   * acquire endpoint replays these same rows when logistics persists and starts its existing task.
   */
  List<List<LogisticsEquipmentMovementReservationResponse>> reserveReplacementMovements(
      UUID idempotencyKey, List<AssetService.ReplacementMovementRequest> requests) {
    if (requests == null || requests.isEmpty()) {
      return List.of();
    }
    OffsetDateTime acquiredAt = now();
    List<IndexedReplacementMovementCandidate> candidates = new ArrayList<>();
    for (int requestIndex = 0; requestIndex < requests.size(); requestIndex++) {
      AssetService.ReplacementMovementRequest request = requests.get(requestIndex);
      OrderUnitReplacementMovementBundle movement = request.movement();
      if (movement == null || movement.movementId() == null || movement.reservedUntil() == null) {
        throw new IllegalArgumentException("Replacement furniture movement is required");
      }
      if (!movement.reservedUntil().isAfter(acquiredAt)) {
        throw new IllegalArgumentException("reservedUntil must be in the future");
      }
      if (request.orderId() == null
          || request.releasedSourceReservationId() == null
          || request.targetRentalItemId() == null
          || request.units() == null) {
        throw new IllegalArgumentException("Replacement movement provenance is incomplete");
      }
      for (ReplacementMovementCandidate candidate :
          exactReplacementMovementCandidates(movement, request.exactPlan())) {
        if (!request.targetRentalItemId().equals(candidate.request().targetRentalItemId())) {
          throw new AssetConflictException(
              "Replacement movement target no longer matches the replacement cabin");
        }
        candidates.add(
            new IndexedReplacementMovementCandidate(
                requestIndex, request, movement, candidate));
      }
    }
    ledger.lockAll(
        candidates.stream()
            .map(
                value ->
                    AssetEquipmentLedgerService.balanceLockKey(
                        value.candidate().source().equipmentId(),
                        value.candidate().source().warehouseId(),
                        value.candidate().source().rentalItemId(),
                        value.candidate().source().kind()))
            .toList());

    Map<UUID, AssetBalanceRow> lockedSources = new LinkedHashMap<>();
    candidates.stream()
        .map(value -> value.candidate().source().id())
        .distinct()
        .sorted()
        .forEach(
            sourceId -> {
              AssetBalanceRow source = ledger.requireForUpdate(sourceId);
              holds.expireFor(source);
              lockedSources.put(sourceId, source);
            });
    List<IndexedReplacementMovementCandidate> locked =
        candidates.stream()
            .map(
                value -> {
                  AssetBalanceRow source =
                      lockedSources.get(value.candidate().source().id());
                  AssetLeaseService.assertVersion(
                      source.version(),
                      value.candidate().request().expectedSourceBalanceVersion());
                  return new IndexedReplacementMovementCandidate(
                      value.requestIndex(),
                      value.replacement(),
                      value.movement(),
                      new ReplacementMovementCandidate(
                          value.candidate().request(), value.candidate().plan(), source));
                })
            .toList();

    Map<UUID, Long> requestedBySource = new LinkedHashMap<>();
    for (IndexedReplacementMovementCandidate value : locked) {
      ReplacementMovementCandidate candidate = value.candidate();
      requestedBySource.merge(
          candidate.source().id(), candidate.request().quantity(), Math::addExact);
      if (activeMovementReservation(
              value.movement().movementId(), candidate.request().lineId())
          != null) {
        throw new AssetConflictException(
            "Equipment movement line already has an active reservation");
      }
    }
    for (Map.Entry<UUID, Long> requested : requestedBySource.entrySet()) {
      AssetBalanceRow source = lockedSources.get(requested.getKey());
      long available = Math.subtractExact(source.quantity(), holds.activeHeld(source));
      if (requested.getValue() > available) {
        throw new AssetConflictException(
            "Replacement furniture movement exceeds the unreserved old-cabin balance");
      }
    }

    List<List<LogisticsEquipmentMovementReservationResponse>> reservations =
        new ArrayList<>();
    for (int index = 0; index < requests.size(); index++) {
      reservations.add(new ArrayList<>());
    }
    for (IndexedReplacementMovementCandidate value : locked) {
      ReplacementMovementCandidate candidate = value.candidate();
      OrderUnitReplacementMovementLine line = candidate.request();
      UUID reservationId = UUID.randomUUID();
      try {
        jdbc.update(
            """
            insert into equipment_allocation_hold(
              id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,
              quantity,state,idempotency_key,expires_at,order_id,target_rental_item_id,order_units,
              replacement_source_reservation_id,created_at,updated_at)
            values (?,0,?,?,?,?,?,?,'ACTIVE',?,?,?,?,?::jsonb,?,?,?)
            """,
            reservationId,
            line.equipmentId(),
            candidate.source().warehouseId(),
            line.sourceBalanceId(),
            LogisticsEquipmentMovementReservationOwnerType.LOGISTICS_EQUIPMENT_MOVEMENT.name(),
            logisticsOwnerId(value.movement().movementId(), line.lineId()),
            line.quantity(),
            idempotencyKey,
            value.movement().reservedUntil(),
            value.replacement().orderId(),
            value.replacement().targetRentalItemId(),
            json.jsonArray(value.replacement().units()),
            value.replacement().releasedSourceReservationId(),
            acquiredAt,
            acquiredAt);
      } catch (DataIntegrityViolationException exception) {
        throw new AssetConflictException(
            "Equipment movement line already has an active reservation");
      }
      EquipmentHoldResponse hold = holds.forUpdate(reservationId);
      events.initialize(
          AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
          reservationId,
          0,
          AssetEventType.EQUIPMENT_HOLD_ACQUIRED,
          holds.fact(hold),
          holds.snapshot(hold));
      reservations
          .get(value.requestIndex())
          .add(
              movementReservationResponse(
                  hold, catalog.require(line.equipmentId()), candidate.source()));
    }
    return reservations.stream().map(List::copyOf).toList();
  }

  private List<ReplacementMovementCandidate> exactReplacementMovementCandidates(
      OrderUnitReplacementMovementBundle movement,
      List<OrderFurnitureMovementPlanLine> exactPlan) {
    if (movement.lines() == null
        || exactPlan == null
        || movement.lines().size() != exactPlan.size()) {
      throw new AssetConflictException(
          "Replacement furniture movement no longer matches the old cabin contents");
    }
    Set<UUID> lineIds = new HashSet<>();
    Set<Integer> matchedPlanIndexes = new HashSet<>();
    List<ReplacementMovementCandidate> result = new ArrayList<>();
    for (OrderUnitReplacementMovementLine requested : movement.lines()) {
      if (requested == null || !lineIds.add(requested.lineId())) {
        throw new IllegalArgumentException("Replacement movement line identity is invalid");
      }
      int matched = -1;
      for (int index = 0; index < exactPlan.size(); index++) {
        if (matchedPlanIndexes.contains(index)) {
          continue;
        }
        OrderFurnitureMovementPlanLine planned = exactPlan.get(index);
        if (requested.equipmentId().equals(planned.equipmentId())
            && requested.sourceBalanceId().equals(planned.sourceBalanceId())
            && requested.expectedSourceBalanceVersion()
                == planned.expectedSourceBalanceVersion()
            && requested.targetRentalItemId().equals(planned.targetRentalItemId())
            && requested.quantity() == planned.quantity()) {
          matched = index;
          break;
        }
      }
      if (matched < 0) {
        throw new AssetConflictException(
            "Replacement furniture movement no longer matches the old cabin contents");
      }
      matchedPlanIndexes.add(matched);
      OrderFurnitureMovementPlanLine planned = exactPlan.get(matched);
      AssetBalanceRow source = ledger.read(requested.sourceBalanceId());
      if (!source.equipmentId().equals(planned.equipmentId())
          || !source.warehouseId().equals(planned.sourceWarehouseId())
          || !java.util.Objects.equals(source.rentalItemId(), planned.sourceRentalItemId())
          || source.kind() != planned.sourceLocationKind()) {
        throw new AssetConflictException(
            "Replacement furniture source balance changed before reservation");
      }
      result.add(new ReplacementMovementCandidate(requested, planned, source));
    }
    return result;
  }

  private MovementReservationRow activeMovementReservation(UUID movementId, UUID lineId) {
    return jdbc
        .query(
            """
            select id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,
              quantity,state,expires_at,committed_at,executed_at,order_id,target_rental_item_id,
              order_units::text order_units,replacement_source_reservation_id
            from equipment_allocation_hold
            where owner_type in (?,?) and owner_id=? and state='ACTIVE'
            for update
            """,
            (result, row) -> movementReservationRow(result),
            LogisticsEquipmentMovementReservationOwnerType.LOGISTICS_EQUIPMENT_MOVEMENT.name(),
            LogisticsEquipmentMovementReservationOwnerType.MAINTENANCE_DISPOSITION_MOVEMENT.name(),
            logisticsOwnerId(movementId, lineId))
        .stream()
        .findFirst()
        .orElse(null);
  }

  private void assertPreparedMovementReservation(
      MovementReservationRow prepared,
      AcquireLogisticsEquipmentMovementReservationRequest request,
      AssetBalanceRow source) {
    EquipmentHoldResponse hold = prepared.hold();
    List<String> mismatches = new ArrayList<>();
    if (request.purpose() != LogisticsEquipmentMovementPurpose.ALLOCATABLE_REBALANCE) {
      mismatches.add("purpose");
    }
    if (!LogisticsEquipmentMovementReservationOwnerType.LOGISTICS_EQUIPMENT_MOVEMENT
        .name()
        .equals(hold.ownerType())) {
      mismatches.add("ownerType");
    }
    if (!hold.equipmentId().equals(request.equipmentId())) mismatches.add("equipmentId");
    if (!hold.warehouseId().equals(request.sourceWarehouseId())) mismatches.add("warehouseId");
    if (!java.util.Objects.equals(hold.sourceBalanceId(), source.id())) {
      mismatches.add("sourceBalanceId");
    }
    if (!java.util.Objects.equals(prepared.orderId(), request.orderId())) {
      mismatches.add("orderId");
    }
    if (!java.util.Objects.equals(
        prepared.targetRentalItemId(), request.targetRentalItemId())) {
      mismatches.add("targetRentalItemId");
    }
    if (!java.util.Objects.equals(
        prepared.replacementSourceReservationId(),
        request.replacementSourceReservationId())) {
      mismatches.add("replacementSourceReservationId");
    }
    if (!java.util.Objects.equals(storedOrderUnits(prepared), request.units())) {
      mismatches.add("units");
    }
    if (hold.quantity() != request.quantity()) mismatches.add("quantity");
    if (java.time.Duration
            .between(hold.expiresAt().toInstant(), request.reservedUntil().toInstant())
            .abs()
            .compareTo(java.time.Duration.ofNanos(1_000))
        > 0) {
      mismatches.add("reservedUntil");
    }
    if (!mismatches.isEmpty()) {
      throw new AssetConflictException(
          "Equipment movement line already has a different active reservation: "
              + String.join(",", mismatches));
    }
  }

  private List<OrderUnitEquipmentRequirements> storedOrderUnits(
      MovementReservationRow reservation) {
    if (reservation.orderUnitsJson() == null) {
      return null;
    }
    return json.read(
        reservation.orderUnitsJson(),
        new TypeReference<List<OrderUnitEquipmentRequirements>>() {});
  }

  private MovementReservationRow movementReservation(UUID reservationId) {
    return jdbc
        .query(
            """
            select id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,
              quantity,state,expires_at,committed_at,executed_at,order_id,target_rental_item_id,
              order_units::text order_units,replacement_source_reservation_id
            from equipment_allocation_hold
            where id=?
            """,
            (result, row) -> movementReservationRow(result),
            reservationId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment hold was not found"));
  }

  private static MovementReservationRow movementReservationRow(java.sql.ResultSet result)
      throws java.sql.SQLException {
    return new MovementReservationRow(
        new EquipmentHoldResponse(
            result.getObject("id", UUID.class),
            result.getLong("version"),
            result.getObject("equipment_id", UUID.class),
            result.getObject("warehouse_id", UUID.class),
            result.getString("owner_type"),
            result.getString("owner_id"),
            result.getObject("source_balance_id", UUID.class),
            result.getLong("quantity"),
            result.getString("state"),
            result.getObject("expires_at", OffsetDateTime.class),
            result.getObject("committed_at", OffsetDateTime.class),
            result.getObject("executed_at", OffsetDateTime.class)),
        result.getObject("order_id", UUID.class),
        result.getObject("target_rental_item_id", UUID.class),
        result.getString("order_units"),
        result.getObject("replacement_source_reservation_id", UUID.class));
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

  /**
   * Converts completed worker reservations into balanced ledger entries and executed holds. It
   * locks every order composition, cabin identity and balance in stable order, revalidates durable
   * normal or replacement context, and rejects presentation-held sources or targets before any
   * quantity changes; idempotent replay returns the original receipt.
   */
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
      MovementReservationRow stored = movementReservation(line.reservationId());
      EquipmentHoldResponse reservation = stored.hold();
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
      candidates.add(
          new MovementReservationCandidate(
              reservation,
              source,
              line,
              stored.orderId(),
              stored.targetRentalItemId(),
              storedOrderUnits(stored),
              stored.replacementSourceReservationId()));
    }
    candidates.stream()
        .map(MovementReservationCandidate::orderId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .sorted()
        .forEach(
            orderId ->
                orderUnitReservations.acquireTransactionLock(
                    "order-composition:" + orderId));
    candidates.stream()
        .filter(candidate -> candidate.orderId() != null)
        .map(
            candidate ->
                "order-equipment:"
                    + candidate.reservation().warehouseId()
                    + ":"
                    + candidate.reservation().equipmentId())
        .distinct()
        .sorted()
        .forEach(orderEquipmentReservations::acquireTransactionLock);
    candidates.forEach(this::assertMovementExecutionOrderContext);
    Set<UUID> movementCabins =
        candidates.stream()
        .flatMap(
            candidate ->
                java.util.stream.Stream.of(
                    candidate.source().rentalItemId(), candidate.line().targetRentalItemId()))
        .filter(java.util.Objects::nonNull)
        .collect(
            java.util.stream.Collectors.toCollection(
                () -> new TreeSet<>(Comparator.comparing(UUID::toString))));
    movementCabins.forEach(leases::lockRentalItem);
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
    movementCabins.forEach(leases::assertNoActivePresentationHold);
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
      } else if (candidate.orderId() == null) {
        requireAllocatableSource(source);
      }
      plans.add(
          new MovementReservationExecutionPlan(
              reservation,
              source,
              target,
              candidate.line(),
              nestedParentHoldId,
              candidate.orderId()));
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

  /**
   * Revalidates the durable full-order furniture context under the shared order lock. Standard
   * movement requires two active units; replacement replay instead proves that its source
   * reservation was released by the same order while the target reservation remains active.
   */
  private OrderMovementComposition requireOrderMovementComposition(
      UUID orderId,
      UUID warehouseId,
      UUID sourceRentalItemId,
      UUID targetRentalItemId,
      List<OrderUnitEquipmentRequirements> units,
      UUID replacementSourceReservationId) {
    Map<UUID, Map<UUID, Long>> requiredByUnit = movementRequirementsByUnit(units);
    List<OrderUnitReservation> activeUnits =
        orderUnitReservations.findAllActiveForUpdate(
            orderId, OrderUnitReservationState.ACTIVE);
    Map<UUID, OrderUnitReservation> activeByRentalItem = new LinkedHashMap<>();
    for (OrderUnitReservation reservation : activeUnits) {
      if (activeByRentalItem.putIfAbsent(reservation.getRentalItemId(), reservation) != null) {
        throw new IllegalStateException("Duplicate active order unit reservation");
      }
      if (!warehouseId.equals(reservation.getWarehouseId())) {
        throw new AssetConflictException(
            "Order movement units must belong to the source warehouse");
      }
    }
    if (!activeByRentalItem.keySet().equals(requiredByUnit.keySet())) {
      throw new AssetConflictException(
          "Order movement units no longer match the active order composition");
    }
    OrderUnitReservation target = activeByRentalItem.get(targetRentalItemId);
    if (target == null) {
      throw new AssetConflictException(
          "Equipment movement target is no longer an active unit of the selected order");
    }
    if (replacementSourceReservationId == null) {
      if (!activeByRentalItem.containsKey(sourceRentalItemId)) {
        throw new AssetConflictException(
            "Equipment movement source is no longer an active unit of the selected order");
      }
    } else {
      OrderUnitReservation released =
          orderUnitReservations
              .findByIdForUpdate(replacementSourceReservationId)
              .orElseThrow(
                  () ->
                      new AssetConflictException(
                          "Replacement movement source reservation was not found"));
      if (released.getState() != OrderUnitReservationState.RELEASED
          || !orderId.equals(released.getOrderId())
          || !sourceRentalItemId.equals(released.getRentalItemId())
          || !warehouseId.equals(released.getWarehouseId())
          || activeByRentalItem.containsKey(sourceRentalItemId)
          || !java.util.Objects.equals(released.getClientId(), target.getClientId())
          || !java.util.Objects.equals(
              released.getTenantSnapshot(), target.getTenantSnapshot())
          || !java.util.Objects.equals(
              released.getDraftReservationExpiresAt(), target.getDraftReservationExpiresAt())) {
        throw new AssetConflictException(
            "Replacement movement source no longer matches the atomic cabin replacement");
      }
    }

    Map<UUID, Long> expectedTotals = aggregateMovementRequirements(requiredByUnit.values());
    Map<UUID, Long> actualTotals = new LinkedHashMap<>();
    for (OrderEquipmentReservation reservation :
        orderEquipmentReservations.findAllActiveForUpdate(
            orderId, OrderEquipmentReservationState.ACTIVE)) {
      if (!warehouseId.equals(reservation.getWarehouseId())) {
        throw new AssetConflictException(
            "Order equipment reservation belongs to a different warehouse");
      }
      if (actualTotals.putIfAbsent(reservation.getEquipmentId(), reservation.getQuantity())
          != null) {
        throw new IllegalStateException("Duplicate active order equipment reservation");
      }
    }
    if (!actualTotals.equals(expectedTotals)) {
      throw new AssetConflictException(
          "Order movement requirements no longer match the active equipment reservation");
    }

    Map<UUID, EquipmentCatalogItem> equipment = new LinkedHashMap<>();
    for (UUID equipmentId : expectedTotals.keySet().stream().sorted().toList()) {
      equipment.put(equipmentId, catalog.require(equipmentId));
    }
    for (Map<UUID, Long> requirements : requiredByUnit.values()) {
      for (Map.Entry<UUID, Long> requirement : requirements.entrySet()) {
        Integer maximum = equipment.get(requirement.getKey()).getMaximumPerCabin();
        if (maximum != null && requirement.getValue() > maximum) {
          throw new AssetConflictException(
              "Equipment quantity exceeds maximumPerCabin for an order unit");
        }
      }
    }
    return new OrderMovementComposition(requiredByUnit);
  }

  /**
   * Caps an ordinary same-order hold by source surplus and target deficit after all live source and
   * inbound holds, preventing concurrent plans from reserving the same physical quantity twice.
   */
  private void assertOrderMovementCapacity(
      AcquireLogisticsEquipmentMovementReservationRequest request,
      AssetBalanceRow source,
      OrderMovementComposition composition) {
    Map<UUID, Long> sourceRequirements =
        composition.requirementsByUnit().get(request.sourceRentalItemId());
    Map<UUID, Long> targetRequirements =
        composition.requirementsByUnit().get(request.targetRentalItemId());
    long desiredSource = sourceRequirements.getOrDefault(request.equipmentId(), 0L);
    long desiredTarget = targetRequirements.getOrDefault(request.equipmentId(), 0L);
    long activeSourceHolds = holds.activeHeld(source);
    long sourceSurplus =
        Math.max(
            0,
            Math.subtractExact(
                Math.subtractExact(source.quantity(), desiredSource), activeSourceHolds));
    Long targetActualValue =
        jdbc.queryForObject(
            """
            select coalesce(sum(quantity),0)
            from equipment_balance
            where equipment_id=? and warehouse_id=? and rental_item_id=?
              and location_kind in ('CABIN_NON_RENTED','CABIN_RENTED')
            """,
            Long.class,
            request.equipmentId(),
            request.sourceWarehouseId(),
            request.targetRentalItemId());
    long targetActual = targetActualValue == null ? 0 : targetActualValue;
    Long activeInboundValue =
        jdbc.queryForObject(
            """
            select coalesce(sum(quantity),0)
            from equipment_allocation_hold
            where order_id=? and target_rental_item_id=? and equipment_id=?
              and state='ACTIVE' and expires_at>clock_timestamp()
            """,
            Long.class,
            request.orderId(),
            request.targetRentalItemId(),
            request.equipmentId());
    long activeInbound = activeInboundValue == null ? 0 : activeInboundValue;
    long targetDeficit =
        Math.max(
            0,
            Math.subtractExact(
                Math.subtractExact(desiredTarget, targetActual), activeInbound));
    if (request.quantity() > sourceSurplus) {
      throw new AssetConflictException(
          "Equipment movement exceeds the source order-unit surplus");
    }
    if (request.quantity() > targetDeficit) {
      throw new AssetConflictException(
          "Equipment movement exceeds the target order-unit deficit");
    }
  }

  /**
   * Replays the immutable stored context at execution. Legacy rows stay context-free; normal rows
   * require active source/target units, while replacement rows prove the released source reservation
   * and active replacement target without weakening allocatable-source rules.
   */
  private void assertMovementExecutionOrderContext(MovementReservationCandidate candidate) {
    if (candidate.orderId() == null) {
      if (candidate.targetRentalItemId() != null
          || candidate.units() != null
          || candidate.replacementSourceReservationId() != null) {
        throw new AssetConflictException("Equipment movement order context is corrupted");
      }
      return;
    }
    if (candidate.targetRentalItemId() == null
        || !candidate.targetRentalItemId().equals(candidate.line().targetRentalItemId())
        || !candidate.reservation().warehouseId().equals(candidate.line().targetWarehouseId())
        || candidate.source().kind() != BalanceLocationKind.CABIN_NON_RENTED
        || candidate.line().targetLocationKind() != BalanceLocationKind.CABIN_NON_RENTED
        || candidate.source().rentalItemId() == null
        || candidate.units() == null) {
      throw new AssetConflictException(
          "Equipment movement target no longer matches its reserved order context");
    }
    requireOrderMovementComposition(
        candidate.orderId(),
        candidate.reservation().warehouseId(),
        candidate.source().rentalItemId(),
        candidate.targetRentalItemId(),
        candidate.units(),
        candidate.replacementSourceReservationId());
  }

  private static Map<UUID, Map<UUID, Long>> movementRequirementsByUnit(
      List<OrderUnitEquipmentRequirements> units) {
    if (units == null || units.isEmpty()) {
      throw new IllegalArgumentException("Order movement units are required");
    }
    Map<UUID, Map<UUID, Long>> result = new LinkedHashMap<>();
    for (OrderUnitEquipmentRequirements unit : units) {
      if (unit == null || unit.rentalItemId() == null || unit.requirements() == null) {
        throw new IllegalArgumentException("Order movement unit requirement is incomplete");
      }
      Map<UUID, Long> requirements = new LinkedHashMap<>();
      for (OrderEquipmentRequirement requirement : unit.requirements()) {
        if (requirement == null
            || requirement.equipmentId() == null
            || requirement.quantity() == null
            || requirement.quantity() < 1) {
          throw new IllegalArgumentException("Order movement equipment requirement is invalid");
        }
        if (requirements.putIfAbsent(requirement.equipmentId(), requirement.quantity()) != null) {
          throw new IllegalArgumentException(
              "Order movement equipment may be specified only once per unit");
        }
      }
      if (result.putIfAbsent(unit.rentalItemId(), Map.copyOf(requirements)) != null) {
        throw new IllegalArgumentException("Order movement unit may be specified only once");
      }
    }
    return Map.copyOf(result);
  }

  private static Map<UUID, Long> aggregateMovementRequirements(
      java.util.Collection<Map<UUID, Long>> unitRequirements) {
    Map<UUID, Long> totals = new LinkedHashMap<>();
    for (Map<UUID, Long> requirements : unitRequirements) {
      requirements.forEach(
          (equipmentId, quantity) -> totals.merge(equipmentId, quantity, Math::addExact));
    }
    return totals;
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

  /**
   * Validated reservation, source balance, and requested line collected before execution planning
   * acquires target balances.
   */
  private record MovementReservationCandidate(
      EquipmentHoldResponse reservation,
      AssetBalanceRow source,
      ExecuteLogisticsEquipmentMovementReservationLine line,
      UUID orderId,
      UUID targetRentalItemId,
      List<OrderUnitEquipmentRequirements> units,
      UUID replacementSourceReservationId) {}

  /**
   * Complete locked-balance execution plan, including any parent hold whose quantity must be
   * excluded from competing availability.
   */
  private record MovementReservationExecutionPlan(
      EquipmentHoldResponse reservation,
      AssetBalanceRow source,
      AssetBalanceRow target,
      ExecuteLogisticsEquipmentMovementReservationLine line,
      UUID nestedParentHoldId,
      UUID orderId) {}

  /** Existing movement hold plus its optional immutable same-order movement context. */
  private record MovementReservationRow(
      EquipmentHoldResponse hold,
      UUID orderId,
      UUID targetRentalItemId,
      String orderUnitsJson,
      UUID replacementSourceReservationId) {}

  /** Authoritative per-unit equipment composition validated under the order lock. */
  private record OrderMovementComposition(
      Map<UUID, Map<UUID, Long>> requirementsByUnit) {}

  /**
   * Resource-bound movement-reservation material used to reject conflicting reuse of an
   * idempotency key.
   */
  private record LogisticsEquipmentMovementReservationCommand(
      UUID resourceId, LogisticsEquipmentMovementReservationCommandRequest request) {}

  /** Exact source row paired with the caller line and freshly recomputed replacement plan. */
  private record ReplacementMovementCandidate(
      OrderUnitReplacementMovementLine request,
      OrderFurnitureMovementPlanLine plan,
      AssetBalanceRow source) {}

  /** One caller-order slot paired with its exact source candidate after global locking. */
  private record IndexedReplacementMovementCandidate(
      int requestIndex,
      AssetService.ReplacementMovementRequest replacement,
      OrderUnitReplacementMovementBundle movement,
      ReplacementMovementCandidate candidate) {}

  private static String logisticsOwnerId(UUID documentId, UUID lineId) {
    if (documentId == null || lineId == null) {
      throw new IllegalArgumentException("logistics documentId and lineId are required");
    }
    return documentId + ":" + lineId;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
