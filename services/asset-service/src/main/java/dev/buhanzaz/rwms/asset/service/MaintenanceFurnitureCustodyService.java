package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Asset-owned pending-return ledger for furniture selected during either kind
 * of maintenance work. A claim is immutable source evidence; append-only
 * events close quantities through a stock return or an approved terminal
 * disposition. It is intentionally not an equipment_balance location kind:
 * availability consumers keep seeing only physical balances.
 */
@Service
public class MaintenanceFurnitureCustodyService {
  private static final String EVENT_SELECTED = "SELECTED";
  private static final String EVENT_RETURNED = "RETURNED_TO_STOCK";
  private static final String EVENT_PREPARED = "DISPOSITION_PREPARED";
  private static final String EVENT_APPLIED = "DISPOSITION_APPLIED";

  private final JdbcTemplate jdbc;
  private final AssetEventStore events;
  private final ObjectMapper mapper;
  private final WarehouseRegistryClient warehouses;

  public MaintenanceFurnitureCustodyService(
      JdbcTemplate jdbc,
      AssetEventStore events,
      ObjectMapper mapper,
      WarehouseRegistryClient warehouses) {
    this.jdbc = jdbc;
    this.events = events;
    this.mapper = mapper;
    this.warehouses = warehouses;
  }

  /**
   * Runs inside the fenced-status transaction. The caller has already proven
   * the active maintenance lease and validated the requested line shape.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<MaintenanceFurnitureCustodyClaim> selectFromCabin(
      UUID actorSubjectId,
      UUID commandIdempotencyKey,
      String commandSha256,
      RentalItem rentalItem,
      MaintenanceLeaseOwnerType ownerType,
      UUID ownerId,
      List<MaintenanceFurniturePendingReturn> requested) {
    requireSelectionIdentity(
        actorSubjectId,
        commandIdempotencyKey,
        commandSha256,
        rentalItem,
        ownerType,
        ownerId,
        requested);
    if (requested.isEmpty()) return List.of();

    advisoryLock(
        "asset-maintenance-furniture-custody-command:"
            + actorSubjectId
            + ':'
            + commandIdempotencyKey
            + ':'
            + rentalItem.getId());
    List<ClaimRow> replay = claimsForCommandForUpdate(
        actorSubjectId, commandIdempotencyKey, rentalItem.getId());
    if (!replay.isEmpty()) {
      return verifySelectionReplay(
          replay, commandSha256, rentalItem, ownerType, ownerId, requested);
    }

    BalanceLocationKind sourceKind = rentalItem.getStatus().name().equals("RENTED")
        ? BalanceLocationKind.CABIN_RENTED
        : BalanceLocationKind.CABIN_NON_RENTED;
    List<MaintenanceFurniturePendingReturn> lines = requested.stream()
        .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
        .toList();
    advisoryLocks(
        lines.stream()
            .map(
                line ->
                    balanceLockKey(
                        line.equipmentId(),
                        rentalItem.getWarehouseId(),
                        rentalItem.getId(),
                        sourceKind))
            .toList());

    List<Selection> selections = new ArrayList<>();
    for (MaintenanceFurniturePendingReturn line : lines) {
      requireActiveFurniture(line.equipmentId());
      BalanceRow source = findBalanceForUpdate(
              line.equipmentId(),
              rentalItem.getWarehouseId(),
              rentalItem.getId(),
              sourceKind)
          .orElseThrow(
              () ->
                  new AssetConflictException(
                      "Rental item does not contain requested furniture equipment"));
      assertVersion(
          source.version(),
          line.expectedSourceBalanceVersion(),
          "Furniture source balance changed concurrently");
      if (source.quantity() < line.quantity()) {
        throw new AssetConflictException("Rental item has insufficient furniture equipment");
      }
      if (Math.subtractExact(source.quantity(), line.quantity()) < activeHeld(source)) {
        throw new AssetConflictException(
            "Furniture pending return would consume quantities reserved by an active hold");
      }
      selections.add(new Selection(line, source));
    }
    Map<AssetEventStore.StreamRef, Long> streamVersions = events.lockStreams(
        selections.stream()
            .map(
                selection ->
                    new AssetEventStore.StreamRef(
                        AssetAggregateType.EQUIPMENT_BALANCE, selection.source().id()))
            .toList());
    for (Selection selection : selections) {
      long streamVersion = streamVersions.get(
          new AssetEventStore.StreamRef(
              AssetAggregateType.EQUIPMENT_BALANCE, selection.source().id()));
      if (streamVersion != selection.source().version()) {
        throw new AssetConflictException("Furniture source balance event stream changed concurrently");
      }
    }

    List<MaintenanceFurnitureCustodyClaim> result = new ArrayList<>();
    for (Selection selection : selections) {
      UUID claimId = UUID.randomUUID();
      OffsetDateTime selectedAt = databaseNow();
      ClaimRow claim = new ClaimRow(
          claimId,
          actorSubjectId,
          commandIdempotencyKey,
          commandSha256,
          ownerType,
          ownerId,
          rentalItem.getId(),
          rentalItem.getWarehouseId(),
          selection.line().equipmentId(),
          selection.source().id(),
          selection.source().version(),
          selection.line().quantity(),
          selectedAt);
      insertClaim(claim);
      insertEvent(
          UUID.randomUUID(),
          claimId,
          0,
          EVENT_SELECTED,
          selection.line().quantity(),
          null,
          null,
          null,
          null,
          null,
          null,
          commandSha256,
          actorSubjectId,
          selectedAt,
          Map.of(
              "claimId", claimId.toString(),
              "ownerType", ownerType.name(),
              "ownerId", ownerId.toString(),
              "rentalItemId", rentalItem.getId().toString(),
              "warehouseId", rentalItem.getWarehouseId().toString(),
              "equipmentId", selection.line().equipmentId().toString(),
              "sourceBalanceId", selection.source().id().toString(),
              "sourceBalanceVersion", selection.source().version(),
              "quantity", selection.line().quantity()));
      decrement(selection.source(), selection.line().quantity(), selection.source().version());
      BalanceRow sourceAfter = requireBalanceById(selection.source().id());
      events.append(
          AssetAggregateType.EQUIPMENT_BALANCE,
          selection.source().id(),
          selection.source().version(),
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          balanceFact(sourceAfter),
          balanceFact(sourceAfter));
      result.add(claimResponse(claim, totals(claimId)));
    }
    return List.copyOf(result);
  }

  @Transactional(readOnly = true)
  public List<MaintenanceFurnitureCustodyClaim> unresolvedClaims(
      MaintenanceLeaseOwnerType ownerType, UUID ownerId) {
    if (ownerType == null || ownerId == null) {
      throw new IllegalArgumentException("Maintenance custody owner identity is required");
    }
    List<ClaimRow> rows = jdbc.query(
        """
        select id,actor_subject_id,command_idempotency_key,request_sha256,owner_type,owner_id,
               rental_item_id,warehouse_id,equipment_id,source_balance_id,source_balance_version,
               quantity,selected_at
        from maintenance_furniture_custody_claim
        where owner_type=? and owner_id=?
        order by selected_at,id
        """,
        (rs, row) -> claimRow(rs),
        ownerType.name(),
        ownerId);
    return rows.stream()
        .map(claim -> claimResponse(claim, totals(claim.id())))
        .filter(claim -> claim.unresolvedQuantity() > 0)
        .toList();
  }

  @Transactional
  public CommandResult<MaintenanceFurnitureCustodyReturnReceipt> returnToStock(
      UUID actorSubjectId,
      UUID transportIdempotencyKey,
      UUID claimId,
      ReturnMaintenanceFurnitureCustodyToStockRequest request) {
    if (actorSubjectId == null || transportIdempotencyKey == null || claimId == null || request == null) {
      throw new IllegalArgumentException("Maintenance custody return identity is required");
    }
    if (request.expectedCustodyVersion() == null
        || request.expectedCustodyVersion() < 0
        || request.expectedStockBalanceVersion() == null
        || request.expectedStockBalanceVersion() < 0
        || request.quantity() < 1
        || request.returnReferenceId() == null) {
      throw new IllegalArgumentException("Maintenance custody return request is invalid");
    }
    String requestSha256 = hash(new ReturnCommand(claimId, request));
    advisoryLock("asset-maintenance-furniture-custody:" + claimId);
    ClaimRow claim = requireClaimForUpdate(claimId);
    Optional<EventRow> replay = returnReplayForUpdate(
        claimId, transportIdempotencyKey, request.returnReferenceId());
    if (replay.isPresent()) {
      EventRow event = replay.get();
      if (!requestSha256.equals(event.requestSha256())) {
        throw new AssetConflictException("Maintenance custody return idempotency identity is already bound");
      }
      return new CommandResult<>(returnReceipt(event), true);
    }

    // Returning furniture creates a physical STOCK balance, so a draining
    // warehouse must reject it just like any other incoming operation. A
    // replay deliberately returns above without contacting warehouse-service.
    warehouses.requireIncoming(claim.warehouseId());

    Totals current = totals(claimId);
    assertVersion(
        current.version(),
        request.expectedCustodyVersion(),
        "Maintenance custody claim changed concurrently");
    if (request.quantity() > availableForDispositionQuantity(claim, current)) {
      throw new AssetConflictException("Maintenance custody return exceeds an unreserved quantity");
    }
    advisoryLock(balanceLockKey(claim.equipmentId(), claim.warehouseId(), null, BalanceLocationKind.STOCK));
    BalanceRow stock = findBalanceForUpdate(
        claim.equipmentId(), claim.warehouseId(), null, BalanceLocationKind.STOCK).orElse(null);
    if (stock == null) {
      if (request.expectedStockBalanceVersion() != 0) {
        throw new AssetConflictException("Stock balance is absent; expectedStockBalanceVersion must be zero");
      }
      stock = createEmptyBalance(
          claim.equipmentId(), claim.warehouseId(), null, BalanceLocationKind.STOCK);
    } else {
      assertVersion(
          stock.version(),
          request.expectedStockBalanceVersion(),
          "Stock balance changed concurrently");
    }
    long stockStreamVersion = events.lockCurrentVersion(AssetAggregateType.EQUIPMENT_BALANCE, stock.id());
    if (stockStreamVersion != stock.version()) {
      throw new AssetConflictException("Stock balance event stream changed concurrently");
    }
    increment(stock, request.quantity(), stock.version());
    BalanceRow stockAfter = requireBalanceById(stock.id());
    events.append(
        AssetAggregateType.EQUIPMENT_BALANCE,
        stock.id(),
        stock.version(),
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        balanceFact(stockAfter),
        balanceFact(stockAfter));
    OffsetDateTime returnedAt = databaseNow();
    EventRow event = new EventRow(
        UUID.randomUUID(),
        claimId,
        Math.addExact(current.version(), 1),
        EVENT_RETURNED,
        request.quantity(),
        stockAfter.id(),
        stockAfter.version(),
        stockAfter.quantity(),
        null,
        request.returnReferenceId(),
        transportIdempotencyKey,
        requestSha256,
        actorSubjectId,
        returnedAt);
    insertEvent(
        event.eventId(),
        event.claimId(),
        event.version(),
        event.eventType(),
        event.quantity(),
        event.targetBalanceId(),
        event.targetBalanceVersion(),
        event.targetBalanceQuantity(),
        null,
        event.returnReferenceId(),
        event.idempotencyKey(),
        event.requestSha256(),
        actorSubjectId,
        returnedAt,
        Map.of(
            "claimId", claimId.toString(),
            "returnReferenceId", request.returnReferenceId().toString(),
            "stockBalanceId", stockAfter.id().toString(),
            "stockBalanceVersion", stockAfter.version(),
            "stockQuantity", stockAfter.quantity(),
            "quantity", request.quantity()));
    return new CommandResult<>(returnReceipt(event), false);
  }

  /** Reserves an exact pending-return quantity for one already approved maintenance decision. */
  @Transactional(propagation = Propagation.MANDATORY)
  public CustodyPreparation prepareDisposition(
      UUID actorSubjectId,
      UUID decisionId,
      UUID claimId,
      long expectedCustodyVersion,
      UUID warehouseId,
      UUID equipmentId,
      long quantity,
      String requestSha256) {
    if (actorSubjectId == null
        || decisionId == null
        || claimId == null
        || expectedCustodyVersion < 0
        || warehouseId == null
        || equipmentId == null
        || quantity < 1
        || requestSha256 == null
        || !requestSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Maintenance custody disposition preparation is invalid");
    }
    advisoryLock("asset-maintenance-furniture-custody:" + claimId);
    ClaimRow claim = requireClaimForUpdate(claimId);
    assertClaimIdentity(claim, warehouseId, equipmentId);
    Optional<EventRow> existing = decisionEventForUpdate(claimId, decisionId, EVENT_PREPARED);
    if (existing.isPresent()) {
      EventRow value = existing.get();
      if (value.quantity() != quantity || !requestSha256.equals(value.requestSha256())) {
        throw new AssetConflictException("Maintenance custody claim is already bound to another decision plan");
      }
      return new CustodyPreparation(claimId, value.version(), quantity);
    }
    Totals current = totals(claimId);
    assertVersion(
        current.version(),
        expectedCustodyVersion,
        "Maintenance custody claim changed concurrently");
    if (quantity > availableForDispositionQuantity(claim, current)) {
      throw new AssetConflictException("Maintenance custody disposition exceeds the unreserved quantity");
    }
    long nextVersion = Math.addExact(current.version(), 1);
    insertEvent(
        UUID.randomUUID(),
        claimId,
        nextVersion,
        EVENT_PREPARED,
        quantity,
        null,
        null,
        null,
        decisionId,
        null,
        null,
        requestSha256,
        actorSubjectId,
        databaseNow(),
        Map.of(
            "claimId", claimId.toString(),
            "decisionId", decisionId.toString(),
            "equipmentId", equipmentId.toString(),
            "warehouseId", warehouseId.toString(),
            "quantity", quantity));
    return new CustodyPreparation(claimId, nextVersion, quantity);
  }

  /**
   * Applies a prepared decision after maintenance has recorded admin approval.
   * The terminal balance increases without inventing an equipment_movement
   * from a physical balance that was already consumed at selection time.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CustodyTerminalApplication applyDisposition(
      UUID actorSubjectId,
      UUID decisionId,
      UUID claimId,
      UUID warehouseId,
      UUID equipmentId,
      long quantity,
      BalanceLocationKind terminalKind,
      String requestSha256) {
    if (actorSubjectId == null
        || decisionId == null
        || claimId == null
        || warehouseId == null
        || equipmentId == null
        || quantity < 1
        || (terminalKind != BalanceLocationKind.LOST && terminalKind != BalanceLocationKind.WRITTEN_OFF)
        || requestSha256 == null
        || !requestSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Maintenance custody disposition application is invalid");
    }
    advisoryLock("asset-maintenance-furniture-custody:" + claimId);
    ClaimRow claim = requireClaimForUpdate(claimId);
    assertClaimIdentity(claim, warehouseId, equipmentId);
    EventRow prepared = decisionEventForUpdate(claimId, decisionId, EVENT_PREPARED)
        .orElseThrow(
            () -> new AssetConflictException("Maintenance custody claim was not prepared for this decision"));
    if (prepared.quantity() != quantity) {
      throw new AssetConflictException("Maintenance custody prepared quantity differs from the decision");
    }
    Optional<EventRow> replay = decisionEventForUpdate(claimId, decisionId, EVENT_APPLIED);
    if (replay.isPresent()) {
      EventRow applied = replay.get();
      if (!requestSha256.equals(applied.requestSha256())) {
        throw new AssetConflictException("Maintenance custody application is already bound to another request");
      }
      return terminalApplication(applied);
    }

    advisoryLock(balanceLockKey(equipmentId, warehouseId, null, terminalKind));
    BalanceRow terminal = findBalanceForUpdate(equipmentId, warehouseId, null, terminalKind)
        .orElseGet(() -> createEmptyBalance(equipmentId, warehouseId, null, terminalKind));
    long terminalStreamVersion = events.lockCurrentVersion(AssetAggregateType.EQUIPMENT_BALANCE, terminal.id());
    if (terminalStreamVersion != terminal.version()) {
      throw new AssetConflictException("Terminal equipment balance event stream changed concurrently");
    }
    increment(terminal, quantity, terminal.version());
    BalanceRow terminalAfter = requireBalanceById(terminal.id());
    events.append(
        AssetAggregateType.EQUIPMENT_BALANCE,
        terminal.id(),
        terminal.version(),
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        balanceFact(terminalAfter),
        balanceFact(terminalAfter));
    Totals current = totals(claimId);
    long nextVersion = Math.addExact(current.version(), 1);
    OffsetDateTime appliedAt = databaseNow();
    EventRow applied = new EventRow(
        UUID.randomUUID(),
        claimId,
        nextVersion,
        EVENT_APPLIED,
        quantity,
        terminalAfter.id(),
        terminalAfter.version(),
        terminalAfter.quantity(),
        decisionId,
        null,
        null,
        requestSha256,
        actorSubjectId,
        appliedAt);
    insertEvent(
        applied.eventId(),
        applied.claimId(),
        applied.version(),
        applied.eventType(),
        applied.quantity(),
        applied.targetBalanceId(),
        applied.targetBalanceVersion(),
        applied.targetBalanceQuantity(),
        decisionId,
        null,
        null,
        requestSha256,
        actorSubjectId,
        appliedAt,
        Map.of(
            "claimId", claimId.toString(),
            "decisionId", decisionId.toString(),
            "terminalBalanceId", terminalAfter.id().toString(),
            "terminalBalanceVersion", terminalAfter.version(),
            "terminalBalanceKind", terminalKind.name(),
            "quantity", quantity));
    return terminalApplication(applied);
  }

  private List<MaintenanceFurnitureCustodyClaim> verifySelectionReplay(
      List<ClaimRow> replay,
      String commandSha256,
      RentalItem rentalItem,
      MaintenanceLeaseOwnerType ownerType,
      UUID ownerId,
      List<MaintenanceFurniturePendingReturn> requested) {
    Map<UUID, ClaimRow> byEquipment = new HashMap<>();
    for (ClaimRow claim : replay) {
      if (!commandSha256.equals(claim.requestSha256())
          || claim.ownerType() != ownerType
          || !ownerId.equals(claim.ownerId())
          || !rentalItem.getWarehouseId().equals(claim.warehouseId())
          || byEquipment.put(claim.equipmentId(), claim) != null) {
        throw new AssetConflictException("Maintenance furniture custody evidence is inconsistent");
      }
    }
    if (byEquipment.size() != requested.size()) {
      throw new AssetConflictException("Maintenance furniture custody command is already bound to another request");
    }
    for (MaintenanceFurniturePendingReturn line : requested) {
      ClaimRow claim = byEquipment.get(line.equipmentId());
      if (claim == null
          || claim.sourceBalanceVersion() != line.expectedSourceBalanceVersion()
          || claim.quantity() != line.quantity()) {
        throw new AssetConflictException("Maintenance furniture custody command is already bound to another request");
      }
    }
    return replay.stream().map(claim -> claimResponse(claim, totals(claim.id()))).toList();
  }

  private void requireSelectionIdentity(
      UUID actorSubjectId,
      UUID commandIdempotencyKey,
      String commandSha256,
      RentalItem rentalItem,
      MaintenanceLeaseOwnerType ownerType,
      UUID ownerId,
      List<MaintenanceFurniturePendingReturn> requested) {
    if (actorSubjectId == null
        || commandIdempotencyKey == null
        || commandSha256 == null
        || !commandSha256.matches("[0-9a-f]{64}")
        || rentalItem == null
        || ownerType == null
        || ownerId == null
        || requested == null) {
      throw new IllegalArgumentException("Maintenance furniture custody selection is incomplete");
    }
  }

  private void requireActiveFurniture(UUID equipmentId) {
    EquipmentCatalogRow catalog = jdbc.query(
        "select category,active from equipment_catalog_item where id=? for update",
        (rs, row) -> new EquipmentCatalogRow(
            EquipmentCategory.valueOf(rs.getString("category")), rs.getBoolean("active")),
        equipmentId).stream().findFirst().orElse(null);
    if (catalog == null || !catalog.active() || catalog.category() != EquipmentCategory.FURNITURE) {
      throw new AssetConflictException("Furniture pending return must reference active FURNITURE equipment");
    }
  }

  private List<ClaimRow> claimsForCommandForUpdate(
      UUID actorSubjectId, UUID idempotencyKey, UUID rentalItemId) {
    return jdbc.query(
        """
        select id,actor_subject_id,command_idempotency_key,request_sha256,owner_type,owner_id,
               rental_item_id,warehouse_id,equipment_id,source_balance_id,source_balance_version,
               quantity,selected_at
        from maintenance_furniture_custody_claim
        where actor_subject_id=? and command_idempotency_key=? and rental_item_id=?
        order by equipment_id,id
        for update
        """,
        (rs, row) -> claimRow(rs),
        actorSubjectId,
        idempotencyKey,
        rentalItemId);
  }

  private ClaimRow requireClaimForUpdate(UUID claimId) {
    return jdbc.query(
        """
        select id,actor_subject_id,command_idempotency_key,request_sha256,owner_type,owner_id,
               rental_item_id,warehouse_id,equipment_id,source_balance_id,source_balance_version,
               quantity,selected_at
        from maintenance_furniture_custody_claim where id=? for update
        """,
        (rs, row) -> claimRow(rs),
        claimId).stream().findFirst().orElseThrow(
            () -> new AssetNotFoundException("Maintenance furniture custody claim was not found"));
  }

  private Optional<EventRow> returnReplayForUpdate(
      UUID claimId, UUID idempotencyKey, UUID returnReferenceId) {
    List<EventRow> matches = jdbc.query(
        """
        select event_id,claim_id,event_version,event_type,quantity,target_balance_id,
               target_balance_version,target_balance_quantity,decision_id,return_reference_id,
               idempotency_key,request_sha256,actor_subject_id,occurred_at
        from maintenance_furniture_custody_event
        where claim_id=? and event_type='RETURNED_TO_STOCK'
          and (idempotency_key=? or return_reference_id=?)
        order by event_version
        for update
        """,
        (rs, row) -> eventRow(rs),
        claimId,
        idempotencyKey,
        returnReferenceId);
    if (matches.size() > 1) {
      throw new AssetConflictException("Maintenance custody return replay identity is inconsistent");
    }
    return matches.stream().findFirst();
  }

  private Optional<EventRow> decisionEventForUpdate(
      UUID claimId, UUID decisionId, String eventType) {
    return jdbc.query(
        """
        select event_id,claim_id,event_version,event_type,quantity,target_balance_id,
               target_balance_version,target_balance_quantity,decision_id,return_reference_id,
               idempotency_key,request_sha256,actor_subject_id,occurred_at
        from maintenance_furniture_custody_event
        where claim_id=? and decision_id=? and event_type=?
        for update
        """,
        (rs, row) -> eventRow(rs),
        claimId,
        decisionId,
        eventType).stream().findFirst();
  }

  private Totals totals(UUID claimId) {
    return jdbc.query(
        """
        select coalesce(max(event_version), 0) as version,
               coalesce(sum(quantity) filter (where event_type='RETURNED_TO_STOCK'), 0) as returned_quantity,
               coalesce(sum(quantity) filter (where event_type='DISPOSITION_PREPARED'), 0) as prepared_quantity,
               coalesce(sum(quantity) filter (where event_type='DISPOSITION_APPLIED'), 0) as applied_quantity
        from maintenance_furniture_custody_event where claim_id=?
        """,
        (rs, row) -> new Totals(
            rs.getLong("version"),
            rs.getLong("returned_quantity"),
            rs.getLong("prepared_quantity"),
            rs.getLong("applied_quantity")),
        claimId).stream().findFirst().orElseThrow(
            () -> new IllegalStateException("Maintenance custody claim has no event evidence"));
  }

  private void insertClaim(ClaimRow claim) {
    jdbc.update(
        """
        insert into maintenance_furniture_custody_claim(
          id,actor_subject_id,command_idempotency_key,request_sha256,owner_type,owner_id,
          rental_item_id,warehouse_id,equipment_id,source_balance_id,source_balance_version,
          quantity,selected_at,created_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        claim.id(),
        claim.actorSubjectId(),
        claim.commandIdempotencyKey(),
        claim.requestSha256(),
        claim.ownerType().name(),
        claim.ownerId(),
        claim.rentalItemId(),
        claim.warehouseId(),
        claim.equipmentId(),
        claim.sourceBalanceId(),
        claim.sourceBalanceVersion(),
        claim.quantity(),
        claim.selectedAt(),
        claim.selectedAt());
  }

  private void insertEvent(
      UUID eventId,
      UUID claimId,
      long eventVersion,
      String eventType,
      long quantity,
      UUID targetBalanceId,
      Long targetBalanceVersion,
      Long targetBalanceQuantity,
      UUID decisionId,
      UUID returnReferenceId,
      UUID idempotencyKey,
      String requestSha256,
      UUID actorSubjectId,
      OffsetDateTime occurredAt,
      Map<String, ?> body) {
    String eventBody = canonicalJson(body);
    jdbc.update(
        """
        insert into maintenance_furniture_custody_event(
          event_id,claim_id,event_version,event_type,quantity,target_balance_id,
          target_balance_version,target_balance_quantity,decision_id,return_reference_id,
          idempotency_key,request_sha256,actor_subject_id,event_body,event_sha256,occurred_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)
        """,
        eventId,
        claimId,
        eventVersion,
        eventType,
        quantity,
        targetBalanceId,
        targetBalanceVersion,
        targetBalanceQuantity,
        decisionId,
        returnReferenceId,
        idempotencyKey,
        requestSha256,
        actorSubjectId,
        eventBody,
        AssetChecksum.sha256(eventBody.getBytes(StandardCharsets.UTF_8)),
        occurredAt);
  }

  private MaintenanceFurnitureCustodyClaim claimResponse(ClaimRow claim, Totals totals) {
    long unresolved = Math.subtractExact(
        Math.subtractExact(claim.quantity(), totals.returnedToStockQuantity()),
        totals.terminalDispositionQuantity());
    long available = availableForDispositionQuantity(claim, totals);
    if (unresolved < 0 || available < 0 || totals.terminalDispositionQuantity() > totals.preparedDispositionQuantity()) {
      throw new IllegalStateException("Maintenance furniture custody evidence is internally inconsistent");
    }
    return new MaintenanceFurnitureCustodyClaim(
        claim.id(),
        totals.version(),
        claim.ownerType(),
        claim.ownerId(),
        claim.rentalItemId(),
        claim.warehouseId(),
        claim.equipmentId(),
        claim.sourceBalanceId(),
        claim.sourceBalanceVersion(),
        claim.quantity(),
        totals.returnedToStockQuantity(),
        totals.preparedDispositionQuantity(),
        totals.terminalDispositionQuantity(),
        unresolved,
        available,
        claim.selectedAt());
  }

  private static long availableForDispositionQuantity(ClaimRow claim, Totals totals) {
    return Math.subtractExact(
        Math.subtractExact(claim.quantity(), totals.returnedToStockQuantity()),
        totals.preparedDispositionQuantity());
  }

  private static MaintenanceFurnitureCustodyReturnReceipt returnReceipt(EventRow event) {
    if (!EVENT_RETURNED.equals(event.eventType())
        || event.returnReferenceId() == null
        || event.targetBalanceId() == null
        || event.targetBalanceVersion() == null
        || event.targetBalanceQuantity() == null) {
      throw new IllegalStateException("Maintenance custody return evidence is malformed");
    }
    return new MaintenanceFurnitureCustodyReturnReceipt(
        event.claimId(),
        event.version(),
        event.returnReferenceId(),
        event.targetBalanceId(),
        event.targetBalanceVersion(),
        event.targetBalanceQuantity(),
        event.quantity(),
        event.occurredAt());
  }

  private static CustodyTerminalApplication terminalApplication(EventRow event) {
    if (!EVENT_APPLIED.equals(event.eventType())
        || event.targetBalanceId() == null
        || event.targetBalanceVersion() == null
        || event.targetBalanceQuantity() == null) {
      throw new IllegalStateException("Maintenance custody terminal evidence is malformed");
    }
    return new CustodyTerminalApplication(
        event.claimId(),
        event.version(),
        event.targetBalanceId(),
        event.targetBalanceVersion(),
        event.targetBalanceQuantity(),
        event.quantity(),
        event.occurredAt());
  }

  private void assertClaimIdentity(ClaimRow claim, UUID warehouseId, UUID equipmentId) {
    if (!warehouseId.equals(claim.warehouseId()) || !equipmentId.equals(claim.equipmentId())) {
      throw new AssetConflictException("Maintenance custody claim does not match the property asset");
    }
  }

  private Optional<BalanceRow> findBalanceForUpdate(
      UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    return jdbc.query(
        """
        select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity
        from equipment_balance
        where equipment_id=? and warehouse_id=? and rental_item_id is not distinct from ? and location_kind=?
        for update
        """,
        (rs, row) -> balanceRow(rs),
        equipmentId,
        warehouseId,
        rentalItemId,
        kind.name()).stream().findFirst();
  }

  private BalanceRow requireBalanceById(UUID balanceId) {
    return jdbc.query(
        """
        select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity
        from equipment_balance where id=? for update
        """,
        (rs, row) -> balanceRow(rs),
        balanceId).stream().findFirst().orElseThrow(
            () -> new AssetNotFoundException("Equipment balance was not found"));
  }

  private BalanceRow createEmptyBalance(
      UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    UUID balanceId = UUID.randomUUID();
    jdbc.update(
        """
        insert into equipment_balance(
          id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,created_at,updated_at)
        values (?,0,?,?,?,?,0,clock_timestamp(),clock_timestamp())
        """,
        balanceId,
        equipmentId,
        warehouseId,
        rentalItemId,
        kind.name());
    BalanceRow row = requireBalanceById(balanceId);
    events.initialize(
        AssetAggregateType.EQUIPMENT_BALANCE,
        balanceId,
        0,
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        balanceFact(row),
        balanceFact(row));
    return row;
  }

  private long activeHeld(BalanceRow source) {
    Long held = jdbc.queryForObject(
        """
        select coalesce(sum(quantity), 0)
        from equipment_allocation_hold
        where source_balance_id=?
          and (state='COMMITTED' or (state='ACTIVE' and expires_at>clock_timestamp()))
        """,
        Long.class,
        source.id());
    return held == null ? 0 : held;
  }

  private void decrement(BalanceRow row, long quantity, long expectedVersion) {
    int changed = jdbc.update(
        """
        update equipment_balance
        set quantity=quantity-?,version=version+1,updated_at=clock_timestamp()
        where id=? and version=? and quantity>=?
        """,
        quantity,
        row.id(),
        expectedVersion,
        quantity);
    if (changed != 1) {
      throw new AssetConflictException("Equipment balance changed concurrently or cannot become negative");
    }
  }

  private void increment(BalanceRow row, long quantity, long expectedVersion) {
    int changed = jdbc.update(
        """
        update equipment_balance
        set quantity=quantity+?,version=version+1,updated_at=clock_timestamp()
        where id=? and version=?
        """,
        quantity,
        row.id(),
        expectedVersion);
    if (changed != 1) {
      throw new AssetConflictException("Target equipment balance changed concurrently");
    }
  }

  private static void assertVersion(long actual, Long expected, String message) {
    if (expected == null || actual != expected) {
      throw new AssetConflictException(message);
    }
  }

  private Map<String, Object> balanceFact(BalanceRow row) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("balanceId", row.id().toString());
    result.put("equipmentId", row.equipmentId().toString());
    result.put("warehouseId", row.warehouseId().toString());
    result.put("rentalItemId", row.rentalItemId() == null ? null : row.rentalItemId().toString());
    result.put("locationKind", row.locationKind().name());
    result.put("quantity", row.quantity());
    return result;
  }

  private String hash(Object value) {
    try {
      return AssetChecksum.sha256(
          mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Maintenance custody request cannot be fingerprinted", exception);
    }
  }

  private String canonicalJson(Object value) {
    try {
      String raw = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);
      String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, raw);
      if (canonical == null) {
        throw new IllegalStateException("PostgreSQL did not canonicalize maintenance custody JSON");
      }
      return canonical;
    } catch (JacksonException exception) {
      throw new IllegalStateException("Maintenance custody JSON cannot be serialized", exception);
    }
  }

  private OffsetDateTime databaseNow() {
    OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    if (value == null) {
      throw new IllegalStateException("PostgreSQL did not return a timestamp");
    }
    return value;
  }

  private void advisoryLocks(Collection<String> keys) {
    keys.stream().filter(Objects::nonNull).distinct().sorted().forEach(this::advisoryLock);
  }

  private void advisoryLock(String key) {
    jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, key);
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

  private static ClaimRow claimRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ClaimRow(
        rs.getObject("id", UUID.class),
        rs.getObject("actor_subject_id", UUID.class),
        rs.getObject("command_idempotency_key", UUID.class),
        rs.getString("request_sha256"),
        MaintenanceLeaseOwnerType.valueOf(rs.getString("owner_type")),
        rs.getObject("owner_id", UUID.class),
        rs.getObject("rental_item_id", UUID.class),
        rs.getObject("warehouse_id", UUID.class),
        rs.getObject("equipment_id", UUID.class),
        rs.getObject("source_balance_id", UUID.class),
        rs.getLong("source_balance_version"),
        rs.getLong("quantity"),
        rs.getObject("selected_at", OffsetDateTime.class));
  }

  private static EventRow eventRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new EventRow(
        rs.getObject("event_id", UUID.class),
        rs.getObject("claim_id", UUID.class),
        rs.getLong("event_version"),
        rs.getString("event_type"),
        rs.getLong("quantity"),
        rs.getObject("target_balance_id", UUID.class),
        rs.getObject("target_balance_version", Long.class),
        rs.getObject("target_balance_quantity", Long.class),
        rs.getObject("decision_id", UUID.class),
        rs.getObject("return_reference_id", UUID.class),
        rs.getObject("idempotency_key", UUID.class),
        rs.getString("request_sha256"),
        rs.getObject("actor_subject_id", UUID.class),
        rs.getObject("occurred_at", OffsetDateTime.class));
  }

  private static BalanceRow balanceRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new BalanceRow(
        rs.getObject("id", UUID.class),
        rs.getLong("version"),
        rs.getObject("equipment_id", UUID.class),
        rs.getObject("warehouse_id", UUID.class),
        rs.getObject("rental_item_id", UUID.class),
        BalanceLocationKind.valueOf(rs.getString("location_kind")),
        rs.getLong("quantity"));
  }

  public record CommandResult<T>(T response, boolean replayed) {}

  public record CustodyPreparation(UUID claimId, long custodyVersion, long quantity) {}

  public record CustodyTerminalApplication(
      UUID claimId,
      long custodyVersion,
      UUID terminalBalanceId,
      long terminalBalanceVersion,
      long terminalBalanceQuantity,
      long quantity,
      OffsetDateTime appliedAt) {}

  private record Selection(MaintenanceFurniturePendingReturn line, BalanceRow source) {}

  private record ClaimRow(
      UUID id,
      UUID actorSubjectId,
      UUID commandIdempotencyKey,
      String requestSha256,
      MaintenanceLeaseOwnerType ownerType,
      UUID ownerId,
      UUID rentalItemId,
      UUID warehouseId,
      UUID equipmentId,
      UUID sourceBalanceId,
      long sourceBalanceVersion,
      long quantity,
      OffsetDateTime selectedAt) {}

  private record EventRow(
      UUID eventId,
      UUID claimId,
      long version,
      String eventType,
      long quantity,
      UUID targetBalanceId,
      Long targetBalanceVersion,
      Long targetBalanceQuantity,
      UUID decisionId,
      UUID returnReferenceId,
      UUID idempotencyKey,
      String requestSha256,
      UUID actorSubjectId,
      OffsetDateTime occurredAt) {}

  private record Totals(
      long version,
      long returnedToStockQuantity,
      long preparedDispositionQuantity,
      long terminalDispositionQuantity) {}

  private record BalanceRow(
      UUID id,
      long version,
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind,
      long quantity) {}

  private record EquipmentCatalogRow(EquipmentCategory category, boolean active) {}

  private record ReturnCommand(
      UUID claimId, ReturnMaintenanceFurnitureCustodyToStockRequest request) {}
}
