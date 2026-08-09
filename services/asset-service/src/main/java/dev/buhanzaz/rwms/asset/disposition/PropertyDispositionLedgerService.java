package dev.buhanzaz.rwms.asset.disposition;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.MovementResponse;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetNotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Owns property-disposition equipment balance, hold, and movement persistence under the caller's
 * transaction.
 *
 * <p>It is the single source for the existing physical ledger row mapping, advisory lock order,
 * optimistic version fences, and append-only balance/hold/movement events. It does not decide
 * whether a maintenance decision is eligible to use those primitives.
 */
@Service
final class PropertyDispositionLedgerService {
  private static final String DISPOSITION_HOLD_OWNER = "MAINTENANCE_PROPERTY_DISPOSITION";
  private static final String DISPOSITION_MOVEMENT_HOLD_OWNER =
      "MAINTENANCE_DISPOSITION_MOVEMENT";

  private final JdbcTemplate jdbc;
  private final AssetEventStore events;

  PropertyDispositionLedgerService(JdbcTemplate jdbc, AssetEventStore events) {
    this.jdbc = jdbc;
    this.events = events;
  }

  void lockCabinAndLease(UUID rentalItemId) {
    advisoryLocks(List.of(rentalItemLockKey(rentalItemId), leaseLockKey(rentalItemId)));
  }

  void lockStockBalance(UUID equipmentId, UUID warehouseId) {
    advisoryLock(balanceLockKey(equipmentId, warehouseId, null, BalanceLocationKind.STOCK));
  }

  void lockCabinContents(List<ContentBalanceRow> contents) {
    advisoryLocks(
        contents.stream()
            .map(
                value ->
                    balanceLockKey(
                        value.equipmentId(),
                        value.warehouseId(),
                        value.rentalItemId(),
                        value.locationKind()))
            .toList());
  }

  void lockCabinSourcesAndTerminals(
      List<PropertyDispositionFenceContent> plan,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind cabinKind,
      BalanceLocationKind terminalKind) {
    advisoryLocks(
        plan.stream()
            .flatMap(
                line ->
                    List.of(
                            balanceLockKey(
                                line.getEquipmentId(), warehouseId, rentalItemId, cabinKind),
                            balanceLockKey(
                                line.getEquipmentId(), warehouseId, null, terminalKind))
                        .stream())
            .toList());
  }

  void lockStockAndTerminal(
      UUID equipmentId, UUID warehouseId, BalanceLocationKind terminalKind) {
    advisoryLocks(
        List.of(
            balanceLockKey(equipmentId, warehouseId, null, BalanceLocationKind.STOCK),
            balanceLockKey(equipmentId, warehouseId, null, terminalKind)));
  }

  List<ContentBalanceRow> cabinContents(UUID rentalItemId, UUID warehouseId) {
    return jdbc.query(
        """
        select balance.id,balance.version,balance.equipment_id,balance.warehouse_id,balance.rental_item_id,
               balance.location_kind,balance.quantity,catalog.name
        from equipment_balance balance
        join equipment_catalog_item catalog on catalog.id=balance.equipment_id
        where balance.rental_item_id=? and balance.warehouse_id=?
          and balance.location_kind in ('CABIN_NON_RENTED','CABIN_RENTED')
          and balance.quantity>0
        order by balance.equipment_id,balance.id
        """,
        (resultSet, row) -> contentBalanceRow(resultSet),
        rentalItemId,
        warehouseId);
  }

  List<ContentBalanceRow> cabinContentsForUpdate(UUID rentalItemId, UUID warehouseId) {
    return jdbc.query(
        """
        select balance.id,balance.version,balance.equipment_id,balance.warehouse_id,balance.rental_item_id,
               balance.location_kind,balance.quantity,catalog.name
        from equipment_balance balance
        join equipment_catalog_item catalog on catalog.id=balance.equipment_id
        where balance.rental_item_id=? and balance.warehouse_id=?
          and balance.location_kind in ('CABIN_NON_RENTED','CABIN_RENTED')
          and balance.quantity>0
        order by balance.equipment_id,balance.id
        for update of balance
        """,
        (resultSet, row) -> contentBalanceRow(resultSet),
        rentalItemId,
        warehouseId);
  }

  StockEquipmentRow stockEquipment(UUID equipmentId, UUID warehouseId) {
    return jdbc
        .query(
            """
            select balance.id as balance_id,balance.version as balance_version,balance.equipment_id,
                   balance.warehouse_id,balance.quantity,catalog.name,catalog.version as catalog_version
            from equipment_balance balance
            join equipment_catalog_item catalog on catalog.id=balance.equipment_id
            where balance.equipment_id=? and balance.warehouse_id=?
              and balance.rental_item_id is null and balance.location_kind='STOCK'
            """,
            (resultSet, row) -> stockEquipmentRow(resultSet),
            equipmentId,
            warehouseId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment stock balance was not found"));
  }

  StockEquipmentRow stockEquipmentForUpdate(UUID equipmentId, UUID warehouseId) {
    return jdbc
        .query(
            """
            select balance.id as balance_id,balance.version as balance_version,balance.equipment_id,
                   balance.warehouse_id,balance.quantity,catalog.name,catalog.version as catalog_version
            from equipment_balance balance
            join equipment_catalog_item catalog on catalog.id=balance.equipment_id
            where balance.equipment_id=? and balance.warehouse_id=?
              and balance.rental_item_id is null and balance.location_kind='STOCK'
            for update of balance
            """,
            (resultSet, row) -> stockEquipmentRow(resultSet),
            equipmentId,
            warehouseId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment stock balance was not found"));
  }

  BalanceRow balanceForUpdate(UUID id) {
    return jdbc
        .query(
            """
            select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity
            from equipment_balance where id=? for update
            """,
            (resultSet, row) -> balanceRow(resultSet),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment balance was not found"));
  }

  BalanceRow balanceRead(UUID id) {
    return jdbc
        .query(
            """
            select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity
            from equipment_balance where id=?
            """,
            (resultSet, row) -> balanceRow(resultSet),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment balance was not found"));
  }

  void assertNoForeignActiveHolds(Collection<UUID> sourceBalanceIds, UUID decisionId) {
    if (sourceBalanceIds.isEmpty()) {
      return;
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(sourceBalanceIds.size(), "?"));
    List<UUID> conflicting = jdbc.query(
        """
        select id
        from equipment_allocation_hold
        where source_balance_id in (%s)
          and (state='COMMITTED' or (state='ACTIVE' and expires_at>clock_timestamp()))
          and not (owner_type=? and owner_id=?)
        for update
        """.formatted(placeholders),
        (resultSet, row) -> resultSet.getObject("id", UUID.class),
        concat(sourceBalanceIds, DISPOSITION_HOLD_OWNER, decisionId.toString()));
    if (!conflicting.isEmpty()) {
      throw new AssetConflictException("Property disposition source has an active equipment hold");
    }
  }

  boolean hasActiveHold(Collection<UUID> sourceBalanceIds) {
    if (sourceBalanceIds.isEmpty()) {
      return false;
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(sourceBalanceIds.size(), "?"));
    Boolean active = jdbc.queryForObject(
        """
        select exists(
          select 1
          from equipment_allocation_hold
          where source_balance_id in (%s)
            and (state='COMMITTED' or (state='ACTIVE' and expires_at>clock_timestamp()))
        )
        """.formatted(placeholders),
        Boolean.class,
        sourceBalanceIds.toArray());
    return Boolean.TRUE.equals(active);
  }

  UUID createCommittedDispositionHold(
      UUID decisionId, UUID equipmentId, UUID warehouseId, UUID sourceBalanceId, long quantity) {
    UUID holdId = UUID.randomUUID();
    UUID idempotencyKey = UUID.nameUUIDFromBytes(
        ("property-disposition-hold:" + decisionId + ':' + equipmentId + ':' + sourceBalanceId)
            .getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        """
        insert into equipment_allocation_hold(
          id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,quantity,state,
          idempotency_key,expires_at,committed_at,created_at,updated_at)
        values (?,0,?,?,?,?,?,?,'COMMITTED',?,clock_timestamp() + interval '100 years',clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        holdId,
        equipmentId,
        warehouseId,
        sourceBalanceId,
        DISPOSITION_HOLD_OWNER,
        decisionId.toString(),
        quantity,
        idempotencyKey);
    HoldRow created = holdForUpdate(holdId);
    events.initialize(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        holdId,
        0,
        AssetEventType.EQUIPMENT_HOLD_COMMITTED,
        holdFact(created),
        holdSnapshot(created));
    return holdId;
  }

  void assertDispositionHoldCommitted(UUID holdId, UUID decisionId) {
    HoldRow hold = holdForUpdate(holdId);
    if (!DISPOSITION_HOLD_OWNER.equals(hold.ownerType())
        || !decisionId.toString().equals(hold.ownerId())
        || !"COMMITTED".equals(hold.state())) {
      throw new AssetConflictException("Property disposition fence no longer owns its source hold");
    }
  }

  void releaseDispositionHold(UUID holdId, UUID decisionId) {
    HoldRow current = holdForUpdate(holdId);
    if (!DISPOSITION_HOLD_OWNER.equals(current.ownerType())
        || !decisionId.toString().equals(current.ownerId())
        || !"COMMITTED".equals(current.state())) {
      throw new AssetConflictException("Property disposition hold is no longer committed");
    }
    int changed = jdbc.update(
        """
        update equipment_allocation_hold
        set version=version+1,state='RELEASED',released_at=clock_timestamp(),updated_at=clock_timestamp()
        where id=? and version=? and state='COMMITTED'
        """,
        holdId,
        current.version());
    if (changed != 1) {
      throw new AssetConflictException("Property disposition hold changed concurrently");
    }
    HoldRow updated = holdForUpdate(holdId);
    events.append(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        holdId,
        current.version(),
        AssetEventType.EQUIPMENT_HOLD_RELEASED,
        holdFact(updated),
        holdSnapshot(updated));
  }

  List<ExecutedMovementProof> executedMovementProofs(
      UUID decisionId, UUID equipmentId, UUID sourceBalanceId) {
    List<ExecutedMovementProof> proofs = jdbc.query(
        """
        select hold.id as reservation_id,movement.id as movement_id,movement.quantity
        from equipment_allocation_hold hold
        join equipment_movement movement on movement.origin_reservation_id=hold.id
        join equipment_balance target on target.id=movement.target_balance_id
        where hold.owner_type=?
          and split_part(hold.owner_id, ':', 1)=?
          and hold.state='EXECUTED'
          and hold.equipment_id=?
          and hold.source_balance_id=?
          and movement.equipment_id=hold.equipment_id
          and movement.source_balance_id=hold.source_balance_id
          and movement.quantity=hold.quantity
          and target.warehouse_id=hold.warehouse_id
          and target.rental_item_id is null
          and target.location_kind='STOCK'
        order by hold.id
        for update of hold,movement,target
        """,
        (resultSet, row) ->
            new ExecutedMovementProof(
                resultSet.getObject("reservation_id", UUID.class),
                resultSet.getObject("movement_id", UUID.class),
                resultSet.getLong("quantity")),
        DISPOSITION_MOVEMENT_HOLD_OWNER,
        decisionId.toString(),
        equipmentId,
        sourceBalanceId);
    Set<UUID> distinctReservations = new HashSet<>();
    if (proofs.stream().anyMatch(proof -> !distinctReservations.add(proof.reservationId()))) {
      throw new AssetConflictException("Executed logistics disposition proof is duplicated");
    }
    return proofs;
  }

  /**
   * Moves a validated source quantity to its terminal balance and emits the existing immutable
   * balance and movement facts. The caller has already made the workflow decision and acquired
   * the matching advisory locks.
   */
  MovementResponse moveToTerminal(
      UUID actorSubjectId, BalanceRow source, long quantity, BalanceLocationKind terminalKind) {
    if (quantity < 1 || source.quantity() < quantity) {
      throw new AssetConflictException("Property disposition cannot consume the source balance");
    }
    BalanceRow target = terminalBalanceForUpdate(source.equipmentId(), source.warehouseId(), terminalKind);
    long sourceStreamVersion = events.lockCurrentVersion(AssetAggregateType.EQUIPMENT_BALANCE, source.id());
    if (sourceStreamVersion != source.version()) {
      throw new AssetConflictException("Source equipment balance event stream changed concurrently");
    }
    long targetStreamVersion = events.lockCurrentVersion(AssetAggregateType.EQUIPMENT_BALANCE, target.id());
    if (targetStreamVersion != target.version()) {
      throw new AssetConflictException("Terminal equipment balance event stream changed concurrently");
    }
    decrementBalance(source, quantity, sourceStreamVersion);
    incrementBalance(target, quantity, targetStreamVersion);
    BalanceRow sourceAfter = balanceForUpdate(source.id());
    BalanceRow targetAfter = balanceForUpdate(target.id());
    events.append(
        AssetAggregateType.EQUIPMENT_BALANCE,
        source.id(),
        sourceStreamVersion,
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        balanceFact(sourceAfter),
        balanceFact(sourceAfter));
    events.append(
        AssetAggregateType.EQUIPMENT_BALANCE,
        target.id(),
        targetStreamVersion,
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        balanceFact(targetAfter),
        balanceFact(targetAfter));

    UUID movementId = UUID.randomUUID();
    String movementKind = terminalKind == BalanceLocationKind.WRITTEN_OFF ? "WRITE_OFF" : "LOSS";
    jdbc.update(
        """
        insert into equipment_movement(
          id,version,equipment_id,source_balance_id,target_balance_id,quantity,movement_kind,
          occurred_at,actor_subject_id,origin_reservation_id)
        values (?,0,?,?,?,?,?,clock_timestamp(),?,null)
        """,
        movementId,
        source.equipmentId(),
        source.id(),
        target.id(),
        quantity,
        movementKind,
        actorSubjectId);
    jdbc.update(
        """
        insert into equipment_movement_ledger(movement_id,line_no,balance_id,quantity_delta,recorded_at)
        values (?,1,?,-?,clock_timestamp()), (?,2,?,?,clock_timestamp())
        """,
        movementId,
        source.id(),
        quantity,
        movementId,
        target.id(),
        quantity);
    MovementResponse response = movementResponse(movementId);
    events.initialize(
        AssetAggregateType.EQUIPMENT_MOVEMENT,
        movementId,
        0,
        terminalKind == BalanceLocationKind.WRITTEN_OFF
            ? AssetEventType.EQUIPMENT_WRITTEN_OFF
            : AssetEventType.EQUIPMENT_LOST,
        movementFact(response),
        movementFact(response));
    return response;
  }

  OffsetDateTime databaseNow() {
    OffsetDateTime timestamp = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    if (timestamp == null) {
      throw new IllegalStateException("PostgreSQL did not return a timestamp");
    }
    return timestamp;
  }

  private BalanceRow terminalBalanceForUpdate(
      UUID equipmentId, UUID warehouseId, BalanceLocationKind terminalKind) {
    Optional<BalanceRow> current = findBalanceForUpdate(equipmentId, warehouseId, null, terminalKind);
    if (current.isPresent()) {
      return current.get();
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into equipment_balance(
          id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,created_at,updated_at)
        values (?,0,?,?,?,?,0,clock_timestamp(),clock_timestamp())
        """,
        id,
        equipmentId,
        warehouseId,
        null,
        terminalKind.name());
    BalanceRow created = balanceForUpdate(id);
    events.initialize(
        AssetAggregateType.EQUIPMENT_BALANCE,
        id,
        0,
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        balanceFact(created),
        balanceFact(created));
    return created;
  }

  private Optional<BalanceRow> findBalanceForUpdate(
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind) {
    String rentalPredicate =
        rentalItemId == null ? "balance.rental_item_id is null" : "balance.rental_item_id=?";
    List<Object> arguments = new ArrayList<>(List.of(equipmentId, warehouseId, locationKind.name()));
    if (rentalItemId != null) {
      arguments.add(rentalItemId);
    }
    return jdbc
        .query(
            """
            select balance.id,balance.version,balance.equipment_id,balance.warehouse_id,
                   balance.rental_item_id,balance.location_kind,balance.quantity
            from equipment_balance balance
            where balance.equipment_id=? and balance.warehouse_id=? and balance.location_kind=?
              and %s
            for update
            """.formatted(rentalPredicate),
            (resultSet, row) -> balanceRow(resultSet),
            arguments.toArray())
        .stream()
        .findFirst();
  }

  private HoldRow holdForUpdate(UUID id) {
    return jdbc
        .query(
            """
            select id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,quantity,
                   state,expires_at,committed_at,executed_at
            from equipment_allocation_hold where id=? for update
            """,
            (resultSet, row) -> holdRow(resultSet),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment hold was not found"));
  }

  private MovementResponse movementResponse(UUID movementId) {
    return jdbc
        .query(
            """
            select id,version,equipment_id,source_balance_id,target_balance_id,quantity,movement_kind,occurred_at
            from equipment_movement where id=?
            """,
            (resultSet, row) ->
                new MovementResponse(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getLong("version"),
                    resultSet.getObject("equipment_id", UUID.class),
                    resultSet.getObject("source_balance_id", UUID.class),
                    resultSet.getObject("target_balance_id", UUID.class),
                    resultSet.getLong("quantity"),
                    resultSet.getString("movement_kind"),
                    resultSet.getObject("occurred_at", OffsetDateTime.class)),
            movementId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Property disposition movement is missing"));
  }

  private void decrementBalance(BalanceRow row, long amount, long expectedVersion) {
    int changed = jdbc.update(
        """
        update equipment_balance
        set quantity=quantity-?,version=version+1,updated_at=clock_timestamp()
        where id=? and version=? and quantity>=?
        """,
        amount,
        row.id(),
        expectedVersion,
        amount);
    if (changed != 1) {
      throw new AssetConflictException("Source equipment balance changed concurrently");
    }
  }

  private void incrementBalance(BalanceRow row, long amount, long expectedVersion) {
    int changed = jdbc.update(
        """
        update equipment_balance
        set quantity=quantity+?,version=version+1,updated_at=clock_timestamp()
        where id=? and version=?
        """,
        amount,
        row.id(),
        expectedVersion);
    if (changed != 1) {
      throw new AssetConflictException("Terminal equipment balance changed concurrently");
    }
  }

  private static Map<String, ?> balanceFact(BalanceRow row) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("balanceId", row.id().toString());
    value.put("equipmentId", row.equipmentId().toString());
    value.put("warehouseId", row.warehouseId().toString());
    value.put("rentalItemId", nullableUuid(row.rentalItemId()));
    value.put("locationKind", row.locationKind().name());
    value.put("quantity", row.quantity());
    return value;
  }

  private Map<String, ?> movementFact(MovementResponse value) {
    MovementContext context = movementContext(value.id());
    Map<String, Object> fact = new LinkedHashMap<>();
    fact.put("movementId", value.id().toString());
    fact.put("equipmentId", value.equipmentId().toString());
    fact.put("sourceBalanceId", value.sourceBalanceId().toString());
    fact.put("targetBalanceId", value.targetBalanceId().toString());
    fact.put("quantity", value.quantity());
    fact.put("movementKind", value.kind());
    if (context.exact()) {
      fact.put("equipmentCategory", context.equipmentCategory());
      fact.put("sourceWarehouseId", context.sourceWarehouseId().toString());
      fact.put("sourceRentalItemId", nullableUuid(context.sourceRentalItemId()));
      fact.put("sourceLocationKind", context.sourceLocationKind());
      fact.put("targetWarehouseId", context.targetWarehouseId().toString());
      fact.put("targetRentalItemId", nullableUuid(context.targetRentalItemId()));
      fact.put("targetLocationKind", context.targetLocationKind());
    }
    return fact;
  }

  private MovementContext movementContext(UUID movementId) {
    return jdbc
        .query(
            """
            select equipment_category_snapshot,
                   source_warehouse_id,source_rental_item_id,source_location_kind,
                   target_warehouse_id,target_rental_item_id,target_location_kind,capture_origin
            from equipment_movement_context
            where movement_id=?
            """,
            (resultSet, row) ->
                new MovementContext(
                    resultSet.getString("equipment_category_snapshot"),
                    resultSet.getObject("source_warehouse_id", UUID.class),
                    resultSet.getObject("source_rental_item_id", UUID.class),
                    resultSet.getString("source_location_kind"),
                    resultSet.getObject("target_warehouse_id", UUID.class),
                    resultSet.getObject("target_rental_item_id", UUID.class),
                    resultSet.getString("target_location_kind"),
                    "AT_MOVEMENT".equals(resultSet.getString("capture_origin"))),
            movementId)
        .stream()
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Equipment movement context is missing for " + movementId));
  }

  private static Map<String, ?> holdFact(HoldRow value) {
    return Map.of(
        "holdId", value.id().toString(),
        "equipmentId", value.equipmentId().toString(),
        "warehouseId", value.warehouseId().toString(),
        "quantity", value.quantity(),
        "state", value.state());
  }

  private static Map<String, ?> holdSnapshot(HoldRow value) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("holdId", value.id().toString());
    snapshot.put("version", value.version());
    snapshot.put("equipmentId", value.equipmentId().toString());
    snapshot.put("warehouseId", value.warehouseId().toString());
    snapshot.put("ownerType", value.ownerType());
    snapshot.put("ownerId", value.ownerId());
    snapshot.put("sourceBalanceId", nullableUuid(value.sourceBalanceId()));
    snapshot.put("quantity", value.quantity());
    snapshot.put("state", value.state());
    snapshot.put("expiresAt", value.expiresAt().toString());
    snapshot.put("committedAt", value.committedAt() == null ? null : value.committedAt().toString());
    snapshot.put("executedAt", value.executedAt() == null ? null : value.executedAt().toString());
    return snapshot;
  }

  private void advisoryLocks(Collection<String> values) {
    values.stream().filter(Objects::nonNull).distinct().sorted().forEach(this::advisoryLock);
  }

  private void advisoryLock(String value) {
    jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", resultSet -> {}, value);
  }

  private static String rentalItemLockKey(UUID rentalItemId) {
    return "asset-rental-item:" + rentalItemId;
  }

  private static String leaseLockKey(UUID rentalItemId) {
    return "lease:" + rentalItemId;
  }

  private static String balanceLockKey(
      UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    return "asset-balance:"
        + equipmentId
        + ':'
        + warehouseId
        + ':'
        + (rentalItemId == null ? "-" : rentalItemId)
        + ':'
        + kind.name();
  }

  private static Object[] concat(Collection<UUID> ids, Object... trailing) {
    List<Object> result = new ArrayList<>(ids.size() + trailing.length);
    result.addAll(ids);
    java.util.Collections.addAll(result, trailing);
    return result.toArray();
  }

  private static String nullableUuid(UUID value) {
    return value == null ? null : value.toString();
  }

  private static ContentBalanceRow contentBalanceRow(java.sql.ResultSet resultSet)
      throws java.sql.SQLException {
    return new ContentBalanceRow(
        resultSet.getObject("id", UUID.class),
        resultSet.getLong("version"),
        resultSet.getObject("equipment_id", UUID.class),
        resultSet.getString("name"),
        resultSet.getObject("warehouse_id", UUID.class),
        resultSet.getObject("rental_item_id", UUID.class),
        BalanceLocationKind.valueOf(resultSet.getString("location_kind")),
        resultSet.getLong("quantity"));
  }

  private static StockEquipmentRow stockEquipmentRow(java.sql.ResultSet resultSet)
      throws java.sql.SQLException {
    return new StockEquipmentRow(
        resultSet.getObject("balance_id", UUID.class),
        resultSet.getLong("balance_version"),
        resultSet.getObject("equipment_id", UUID.class),
        resultSet.getObject("warehouse_id", UUID.class),
        resultSet.getLong("quantity"),
        resultSet.getString("name"),
        resultSet.getLong("catalog_version"));
  }

  private static BalanceRow balanceRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
    return new BalanceRow(
        resultSet.getObject("id", UUID.class),
        resultSet.getLong("version"),
        resultSet.getObject("equipment_id", UUID.class),
        resultSet.getObject("warehouse_id", UUID.class),
        resultSet.getObject("rental_item_id", UUID.class),
        BalanceLocationKind.valueOf(resultSet.getString("location_kind")),
        resultSet.getLong("quantity"));
  }

  private static HoldRow holdRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
    return new HoldRow(
        resultSet.getObject("id", UUID.class),
        resultSet.getLong("version"),
        resultSet.getObject("equipment_id", UUID.class),
        resultSet.getObject("warehouse_id", UUID.class),
        resultSet.getObject("source_balance_id", UUID.class),
        resultSet.getString("owner_type"),
        resultSet.getString("owner_id"),
        resultSet.getLong("quantity"),
        resultSet.getString("state"),
        resultSet.getObject("expires_at", OffsetDateTime.class),
        resultSet.getObject("committed_at", OffsetDateTime.class),
        resultSet.getObject("executed_at", OffsetDateTime.class));
  }

  /**
   * Cabin-content balance enriched with the catalog name needed to freeze a disposition decision.
   */
  static record ContentBalanceRow(
      UUID id,
      long version,
      UUID equipmentId,
      String equipmentName,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind,
      long quantity) {}

  /**
   * Stock balance paired with its catalog version so preparation can fence both quantity and
   * equipment identity.
   */
  static record StockEquipmentRow(
      UUID balanceId,
      long balanceVersion,
      UUID equipmentId,
      UUID warehouseId,
      long quantity,
      String equipmentName,
      long catalogVersion) {}

  /**
   * Canonical mutable balance projection used for version checks and disposition ledger updates.
   */
  static record BalanceRow(
      UUID id,
      long version,
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind,
      long quantity) {}

  /**
   * Immutable reservation, movement, and quantity evidence proving that a prepared relocation was
   * executed exactly as fenced.
   */
  static record ExecutedMovementProof(UUID reservationId, UUID movementId, long quantity) {}

  /**
   * Durable allocation-hold projection used to validate ownership and terminal execution state
   * before a disposition consumes the held quantity.
   */
  private record HoldRow(
      UUID id,
      long version,
      UUID equipmentId,
      UUID warehouseId,
      UUID sourceBalanceId,
      String ownerType,
      String ownerId,
      long quantity,
      String state,
      OffsetDateTime expiresAt,
      OffsetDateTime committedAt,
      OffsetDateTime executedAt) {}

  /**
   * Normalized source and target coordinates persisted with movement evidence for replay conflict
   * detection.
   */
  private record MovementContext(
      String equipmentCategory,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      String targetLocationKind,
      boolean exact) {}
}
