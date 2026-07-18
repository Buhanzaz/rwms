package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.OperationLease;
import dev.buhanzaz.rwms.asset.domain.OperationLeaseState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.mapper.AssetLogisticsResponseMapper;
import dev.buhanzaz.rwms.asset.mapper.EquipmentCatalogItemMapper;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.OperationLeaseRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Asset-owned command boundary. Cross-warehouse/cabin equipment changes never
 * leave this PostgreSQL transaction; task-board and future workflow services
 * receive committed facts only.
 */
@Service
public class AssetService {
  private final RentalItemRepository rentalItems;
  private final EquipmentCatalogItemRepository equipment;
  private final OperationLeaseRepository operationLeases;
  private final JdbcTemplate jdbc;
  private final AssetEventStore events;
  private final AssetIdempotencyStore idempotency;
  private final WarehouseRegistryClient warehouses;
  private final AssetLogisticsResponseMapper logisticsMapper;
  private final EquipmentCatalogItemMapper equipmentMapper;
  private final ObjectMapper mapper;
  private final Duration holdTtl;
  private final Duration leaseTtl;

  public AssetService(
      RentalItemRepository rentalItems,
      EquipmentCatalogItemRepository equipment,
      OperationLeaseRepository operationLeases,
      JdbcTemplate jdbc,
      AssetEventStore events,
      AssetIdempotencyStore idempotency,
      WarehouseRegistryClient warehouses,
      AssetLogisticsResponseMapper logisticsMapper,
      EquipmentCatalogItemMapper equipmentMapper,
      ObjectMapper mapper,
      @Value("${rwms.asset.equipment-hold.ttl:15m}") Duration holdTtl,
      @Value("${rwms.asset.operation-lease.ttl:15m}") Duration leaseTtl) {
    this.rentalItems = rentalItems;
    this.equipment = equipment;
    this.operationLeases = operationLeases;
    this.jdbc = jdbc;
    this.events = events;
    this.idempotency = idempotency;
    this.warehouses = warehouses;
    this.logisticsMapper = logisticsMapper;
    this.equipmentMapper = equipmentMapper;
    this.mapper = mapper;
    this.holdTtl = requireTtl(holdTtl, "equipment hold");
    this.leaseTtl = requireTtl(leaseTtl, "operation lease");
  }

  @Transactional(readOnly = true)
  public RentalItemPage listRentalItems(
      UUID warehouseId,
      int page,
      int size,
      String search,
      java.util.Set<RentalItemStatus> excludedStatuses) {
    if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("Invalid page request");
    List<RentalItem> values = rentalItems.findAllByWarehouseIdOrderByNumber(warehouseId);
    String needle = search == null ? "" : search.trim().toUpperCase(java.util.Locale.ROOT);
    java.util.Set<RentalItemStatus> exclusions =
        excludedStatuses == null ? java.util.Set.of() : java.util.Set.copyOf(excludedStatuses);
    List<RentalItemResponse> all = values.stream()
        .filter(item -> !exclusions.contains(item.getStatus()))
        .filter(item -> needle.isEmpty() || item.getNumber().contains(needle))
        .map(this::rentalResponse).toList();
    int from = Math.min(Math.multiplyExact(page, size), all.size());
    int to = Math.min(from + size, all.size());
    long pages = all.isEmpty() ? 0 : (all.size() + (long) size - 1) / size;
    return new RentalItemPage(all.subList(from, to), page, size, all.size(), pages);
  }

  @Transactional(readOnly = true)
  public RentalItemResponse rentalItem(UUID id) { return rentalResponse(requireRentalItem(id)); }

  /**
   * Deliberately narrow read boundary for logistics. The public rental response
   * contains passport and local operator data which must never cross this
   * service-to-service contract.
   */
  @Transactional(readOnly = true)
  public LogisticsRentalItemSnapshot logisticsSnapshot(UUID id) {
    return logisticsMapper.toLogisticsSnapshot(rentalResponse(requireRentalItem(id)));
  }

  @Transactional
  public CreateResult<LogisticsOperationLeaseResponse> acquireLogisticsLease(
      UUID subjectId, UUID key, AcquireLogisticsOperationLeaseRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "logistics.operation-lease.acquire", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), LogisticsOperationLeaseResponse.class), true);
    }

    lockRentalItemAndLease(request.rentalItemId());
    RentalItem item = requireRentalItem(request.rentalItemId());
    assertVersion(item.getVersion(), request.expectedRentalItemVersion());
    expireLeases(request.rentalItemId());
    if (!activeLeasesForUpdate(request.rentalItemId()).isEmpty()) {
      throw new AssetConflictException(
          "Rental item already has an active operation lease; reacquisition is forbidden");
    }

    long next = Math.addExact(operationLeases.maximumFencingToken(request.rentalItemId()), 1);
    OffsetDateTime acquiredAt = now();
    OperationLease persisted = operationLeases.saveAndFlush(OperationLease.acquire(
        request.rentalItemId(),
        logisticsOwnerType(request.ownerType()),
        logisticsOwnerId(request.documentId(), request.lineId()),
        next,
        key,
        acquiredAt,
        acquiredAt.plus(leaseTtl)));
    OperationLeaseResponse response = leaseResponse(persisted);
    events.initialize(
        AssetAggregateType.OPERATION_LEASE,
        persisted.getId(),
        persisted.getVersion(),
        AssetEventType.OPERATION_LEASE_ACQUIRED,
        leaseFact(response),
        leaseSnapshot(response));
    LogisticsOperationLeaseResponse safe = logisticsMapper.toLogisticsLease(response);
    idempotency.store(subjectId, "logistics.operation-lease.acquire", key, hash, 201, safe);
    return new CreateResult<>(safe, false);
  }

  @Transactional
  public CreateResult<LogisticsOperationLeaseResponse> renewLogisticsLease(
      UUID subjectId, UUID key, UUID id, LogisticsLeaseCommandRequest request) {
    OperationLease current = requireLeaseForUpdate(id);
    assertLogisticsLeaseOwner(
        current, request.ownerType(), request.documentId(), request.lineId());
    String hash = hash(new LogisticsLeaseCommand(id, request));
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "logistics.operation-lease.renew", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), LogisticsOperationLeaseResponse.class), true);
    }
    OperationLeaseResponse updated = renewLeaseState(
        id, request.expectedVersion(), request.fencingToken());
    LogisticsOperationLeaseResponse safe = logisticsMapper.toLogisticsLease(updated);
    idempotency.store(subjectId, "logistics.operation-lease.renew", key, hash, 200, safe);
    return new CreateResult<>(safe, false);
  }

  @Transactional
  public CreateResult<LogisticsOperationLeaseResponse> releaseLogisticsLease(
      UUID subjectId, UUID key, UUID id, LogisticsLeaseCommandRequest request) {
    OperationLease current = requireLeaseForUpdate(id);
    assertLogisticsLeaseOwner(
        current, request.ownerType(), request.documentId(), request.lineId());
    String hash = hash(new LogisticsLeaseCommand(id, request));
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "logistics.operation-lease.release", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), LogisticsOperationLeaseResponse.class), true);
    }
    OperationLeaseResponse updated = releaseLeaseState(
        id, request.expectedVersion(), request.fencingToken());
    LogisticsOperationLeaseResponse safe = logisticsMapper.toLogisticsLease(updated);
    idempotency.store(subjectId, "logistics.operation-lease.release", key, hash, 200, safe);
    return new CreateResult<>(safe, false);
  }

  /**
   * Validates the active typed lease and applies one canonical state effect in
   * the same transaction. Transfer arrival also moves attached cabin balances
   * through asset's immutable movement ledger.
   */
  @Transactional
  public CreateResult<LogisticsRentalItemSnapshot> applyLogisticsEffect(
      UUID subjectId, UUID key, UUID id, LogisticsFencedEffectRequest request) {
    advisoryLock(rentalItemLockKey(id));
    OperationLease lease = validateLease(id, request.leaseId(), request.fencingToken());
    assertLogisticsLeaseOwner(
        lease, request.ownerType(), request.documentId(), request.lineId());
    String hash = hash(new LogisticsEffectCommand(id, request));
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "logistics.rental-item.effect", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), LogisticsRentalItemSnapshot.class), true);
    }

    RentalItem item = requireRentalItem(id);
    assertVersion(item.getVersion(), request.expectedVersion());
    RentalItemStatus target = LogisticsAssetTransitionPolicy.target(
        item.getStatus(),
        request.action(),
        request.ownerType(),
        request.destinationWarehouseId());
    RentalItemStatus previous = item.getStatus();
    UUID sourceWarehouseId = item.getWarehouseId();
    if (request.action() == LogisticsRentalItemAction.TRANSFER_ARRIVE) {
      if (sourceWarehouseId.equals(request.destinationWarehouseId())) {
        throw new AssetConflictException(
            "Transfer arrival must move the rental item to another warehouse");
      }
      warehouses.requireActive(request.destinationWarehouseId());
    }

    item.changeStatusUnderLease(target);
    if (request.action() == LogisticsRentalItemAction.TRANSFER_ARRIVE) {
      item.changeWarehouse(request.destinationWarehouseId());
    }
    RentalItem saved = rentalItems.saveAndFlush(item);
    if (request.action() == LogisticsRentalItemAction.TRANSFER_ARRIVE) {
      relocateCabinContentsUnderLease(
          subjectId, saved.getId(), sourceWarehouseId, request.destinationWarehouseId());
    } else {
      reclassifyCabinBalances(saved, previous);
    }
    events.append(
        AssetAggregateType.RENTAL_ITEM,
        saved.getId(),
        request.expectedVersion(),
        AssetEventType.RENTAL_ITEM_LOGISTICS_EFFECT_APPLIED,
        rentalFact(saved),
        rentalSnapshot(saved));
    LogisticsRentalItemSnapshot safe =
        logisticsMapper.toLogisticsSnapshot(rentalResponse(saved));
    idempotency.store(subjectId, "logistics.rental-item.effect", key, hash, 200, safe);
    return new CreateResult<>(safe, false);
  }

  @Transactional
  public CreateResult<LogisticsEquipmentHoldResponse> acquireLogisticsHold(
      UUID subjectId, UUID key, AcquireLogisticsEquipmentHoldRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "logistics.equipment-hold.acquire", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), LogisticsEquipmentHoldResponse.class), true);
    }
    warehouses.requireActive(request.warehouseId());
    requireEquipment(request.equipmentId());
    advisoryLock(balanceLockKey(
        request.equipmentId(),
        request.warehouseId(),
        null,
        BalanceLocationKind.STOCK));
    expireHolds(request.equipmentId(), request.warehouseId());
    BalanceRow stock = requireBalance(
        request.equipmentId(), request.warehouseId(), null, BalanceLocationKind.STOCK);
    assertVersion(stock.version(), request.expectedStockVersion());
    long available = Math.subtractExact(
        stock.quantity(), activeHeld(request.equipmentId(), request.warehouseId()));
    if (available < request.quantity()) {
      throw new AssetConflictException("Active equipment holds reduce available stock");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime expiry = now().plus(holdTtl);
    jdbc.update(
        """
        insert into equipment_allocation_hold(id,version,equipment_id,warehouse_id,owner_type,owner_id,quantity,state,idempotency_key,expires_at,created_at,updated_at)
        values (?,0,?,?,?,?,?,'ACTIVE',?,?,clock_timestamp(),clock_timestamp())
        """,
        id,
        request.equipmentId(),
        request.warehouseId(),
        LogisticsLeaseOwnerType.LOGISTICS_SHIPMENT.name(),
        logisticsOwnerId(request.shipmentId(), request.shipmentLineId()),
        request.quantity(),
        key,
        expiry);
    EquipmentHoldResponse response = holdResponse(id);
    events.initialize(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        id,
        0,
        AssetEventType.EQUIPMENT_HOLD_ACQUIRED,
        holdFact(response),
        holdSnapshot(response));
    LogisticsEquipmentHoldResponse safe = logisticsMapper.toLogisticsHold(response);
    idempotency.store(subjectId, "logistics.equipment-hold.acquire", key, hash, 201, safe);
    return new CreateResult<>(safe, false);
  }

  @Transactional
  public CreateResult<LogisticsEquipmentHoldResponse> renewLogisticsHold(
      UUID subjectId, UUID key, UUID id, LogisticsEquipmentHoldCommandRequest request) {
    EquipmentHoldResponse current = holdResponse(id);
    assertLogisticsShipmentHoldOwner(current, request.shipmentId(), request.shipmentLineId());
    String hash = hash(new LogisticsHoldCommand(id, request));
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "logistics.equipment-hold.renew", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), LogisticsEquipmentHoldResponse.class), true);
    }
    assertVersion(current.version(), request.expectedVersion());
    if (!"ACTIVE".equals(current.state()) || current.expiresAt().isBefore(now())) {
      throw new AssetConflictException("Equipment hold is not active");
    }
    int changed = jdbc.update(
        "update equipment_allocation_hold set version=version+1,expires_at=?,updated_at=clock_timestamp() where id=? and version=?",
        now().plus(holdTtl),
        id,
        request.expectedVersion());
    if (changed != 1) {
      throw new AssetConflictException("Equipment hold changed concurrently during renewal");
    }
    EquipmentHoldResponse updated = holdResponse(id);
    events.append(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        id,
        request.expectedVersion(),
        AssetEventType.EQUIPMENT_HOLD_RENEWED,
        holdFact(updated),
        holdSnapshot(updated));
    LogisticsEquipmentHoldResponse safe = logisticsMapper.toLogisticsHold(updated);
    idempotency.store(subjectId, "logistics.equipment-hold.renew", key, hash, 200, safe);
    return new CreateResult<>(safe, false);
  }

  @Transactional
  public CreateResult<LogisticsEquipmentHoldResponse> commitLogisticsHold(
      UUID subjectId, UUID key, UUID id, LogisticsEquipmentHoldCommandRequest request) {
    EquipmentHoldResponse current = holdResponse(id);
    assertLogisticsShipmentHoldOwner(current, request.shipmentId(), request.shipmentLineId());
    String hash = hash(new LogisticsHoldCommand(id, request));
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "logistics.equipment-hold.commit", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), LogisticsEquipmentHoldResponse.class), true);
    }
    expireHolds(current.equipmentId(), current.warehouseId());
    current = holdResponse(id);
    assertLogisticsShipmentHoldOwner(current, request.shipmentId(), request.shipmentLineId());
    assertVersion(current.version(), request.expectedVersion());
    if (!"ACTIVE".equals(current.state())) {
      throw new AssetConflictException("Equipment hold is not active");
    }
    int changed = jdbc.update(
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
    EquipmentHoldResponse committed = holdResponse(id);
    events.append(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        id,
        request.expectedVersion(),
        AssetEventType.EQUIPMENT_HOLD_COMMITTED,
        holdFact(committed),
        holdSnapshot(committed));
    LogisticsEquipmentHoldResponse safe = logisticsMapper.toLogisticsHold(committed);
    idempotency.store(subjectId, "logistics.equipment-hold.commit", key, hash, 200, safe);
    return new CreateResult<>(safe, false);
  }

  @Transactional
  public CreateResult<LogisticsEquipmentHoldResponse> releaseLogisticsHold(
      UUID subjectId, UUID key, UUID id, LogisticsEquipmentHoldCommandRequest request) {
    EquipmentHoldResponse current = holdResponse(id);
    assertLogisticsShipmentHoldOwner(current, request.shipmentId(), request.shipmentLineId());
    String hash = hash(new LogisticsHoldCommand(id, request));
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "logistics.equipment-hold.release", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), LogisticsEquipmentHoldResponse.class), true);
    }
    expireHolds(current.equipmentId(), current.warehouseId());
    current = holdResponse(id);
    assertLogisticsShipmentHoldOwner(current, request.shipmentId(), request.shipmentLineId());
    assertVersion(current.version(), request.expectedVersion());
    EquipmentHoldResponse updated = current;
    if ("ACTIVE".equals(current.state()) || "COMMITTED".equals(current.state())) {
      int changed = jdbc.update(
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
      updated = holdResponse(id);
      events.append(
          AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
          id,
          request.expectedVersion(),
          AssetEventType.EQUIPMENT_HOLD_RELEASED,
          holdFact(updated),
          holdSnapshot(updated));
    }
    LogisticsEquipmentHoldResponse safe = logisticsMapper.toLogisticsHold(updated);
    idempotency.store(subjectId, "logistics.equipment-hold.release", key, hash, 200, safe);
    return new CreateResult<>(safe, false);
  }

  @Transactional
  public CreateResult<RentalItemResponse> createRentalItem(UUID subjectId, UUID key, CreateRentalItemRequest request) {
    warehouses.requireActive(request.warehouseId());
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "rental-item.create", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), RentalItemResponse.class), true);
    RentalItem candidate = RentalItem.create(request.warehouseId(), request.number(), request.rentalType(), request.dimensions(),
        request.finishing(), request.category(), request.characteristics(), request.linoleum(), jsonObject(request.passport()), jsonArray(request.tags()));
    if (rentalItems.existsByIdentityMatchKey(candidate.getIdentityMatchKey())) {
      throw new AssetConflictException("Rental item number identity is globally unique and cannot be reused");
    }
    RentalItem persisted = rentalItems.saveAndFlush(candidate);
    events.initialize(AssetAggregateType.RENTAL_ITEM, persisted.getId(), persisted.getVersion(), AssetEventType.RENTAL_ITEM_CREATED,
        rentalFact(persisted), rentalSnapshot(persisted));
    RentalItemResponse response = rentalResponse(persisted);
    idempotency.store(subjectId, "rental-item.create", key, hash, 201, response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public RentalItemResponse updatePassport(UUID id, UpdatePassportRequest request) {
    assertNoActiveLease(id);
    RentalItem item = requireRentalItem(id);
    assertVersion(item.getVersion(), request.expectedVersion());
    if (!item.changePassport(request.rentalType(), request.dimensions(), request.finishing(), request.category(), request.characteristics(),
        request.linoleum(), jsonObject(request.passport()), jsonArray(request.tags()))) return rentalResponse(item);
    RentalItem saved = rentalItems.saveAndFlush(item);
    events.append(AssetAggregateType.RENTAL_ITEM, saved.getId(), request.expectedVersion(), AssetEventType.RENTAL_ITEM_PASSPORT_CHANGED,
        rentalFact(saved), rentalSnapshot(saved));
    return rentalResponse(saved);
  }

  @Transactional
  public RentalItemResponse updateStatus(UUID id, UpdateStatusRequest request) {
    return changeStatus(id, request.expectedVersion(), request.status(), false);
  }

  @Transactional
  public RentalItemResponse fencedStatus(UUID id, FencedStatusRequest request) {
    advisoryLock(rentalItemLockKey(id));
    validateLease(id, request.leaseId(), request.fencingToken());
    return changeStatusLocked(id, request.expectedVersion(), request.status(), true);
  }

  @Transactional
  public RentalItemResponse updateWarehouse(UUID id, UpdateWarehouseRequest request) {
    assertNoActiveLease(id);
    RentalItem item = requireRentalItem(id);
    assertVersion(item.getVersion(), request.expectedVersion());
    warehouses.requireActive(request.warehouseId());
    if (!contents(id).isEmpty()) {
      throw new AssetConflictException("Rental item with equipment contents must be moved through equipment transfers first");
    }
    if (!item.changeWarehouse(request.warehouseId())) return rentalResponse(item);
    RentalItem saved = rentalItems.saveAndFlush(item);
    events.append(AssetAggregateType.RENTAL_ITEM, saved.getId(), request.expectedVersion(), AssetEventType.RENTAL_ITEM_WAREHOUSE_CHANGED,
        rentalFact(saved), rentalSnapshot(saved));
    return rentalResponse(saved);
  }

  @Transactional
  public RentalItemResponse updateGeneralComment(UUID id, UpdateGeneralCommentRequest request) {
    assertNoActiveLease(id);
    RentalItem item = requireRentalItem(id);
    assertVersion(item.getVersion(), request.expectedVersion());
    if (!item.changeGeneralComment(request.comment())) return rentalResponse(item);
    RentalItem saved = rentalItems.saveAndFlush(item);
    events.append(AssetAggregateType.RENTAL_ITEM, saved.getId(), request.expectedVersion(), AssetEventType.RENTAL_ITEM_GENERAL_COMMENT_CHANGED,
        Map.of("rentalItemId", saved.getId().toString(), "commentRevision", saved.getVersion()), rentalSnapshot(saved));
    return rentalResponse(saved);
  }

  @Transactional
  public CreateResult<ManualNoteResponse> addManualNote(UUID subjectId, UUID key, UUID id, AddManualNoteRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "rental-item.manual-note", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), ManualNoteResponse.class), true);
    assertNoActiveLease(id);
    RentalItem item = requireRentalItem(id);
    assertVersion(item.getVersion(), request.expectedVersion());
    UUID noteId = UUID.randomUUID();
    item.touchActivity();
    RentalItem saved = rentalItems.saveAndFlush(item);
    jdbc.update("insert into rental_item_note(id,rental_item_id,actor_subject_id,note_text,created_at) values (?, ?, ?, ?, clock_timestamp())",
        noteId, id, subjectId, request.text().trim());
    events.append(AssetAggregateType.RENTAL_ITEM, id, request.expectedVersion(), AssetEventType.RENTAL_ITEM_MANUAL_NOTE_ADDED,
        Map.of("rentalItemId", id.toString(), "noteId", noteId.toString()), rentalSnapshot(saved));
    ManualNoteResponse response = manualNote(noteId);
    idempotency.store(subjectId, "rental-item.manual-note", key, hash, 201, response);
    return new CreateResult<>(response, false);
  }

  @Transactional(readOnly = true)
  public List<ManualNoteResponse> manualNotes(UUID rentalItemId) {
    requireRentalItem(rentalItemId);
    return jdbc.query("select id,rental_item_id,note_text,created_at from rental_item_note where rental_item_id=? order by created_at,id",
        (rs, row) -> manualNote(rs), rentalItemId);
  }

  @Transactional(readOnly = true)
  public List<EquipmentResponse> listEquipment() {
    return equipment.findAllByOrderByCode().stream().map(this::equipmentResponse).toList();
  }

  @Transactional(readOnly = true)
  public List<EquipmentWarehouseResponse> equipmentAtWarehouse(UUID warehouseId) {
    return equipment.findAllByOrderByCode().stream()
        .map(item -> new EquipmentWarehouseResponse(equipmentResponse(item), equipmentTotals(item.getId(), warehouseId)))
        .toList();
  }

  @Transactional(readOnly = true)
  public EquipmentResponse equipment(UUID id) { return equipmentResponse(requireEquipment(id)); }

  @Transactional
  public CreateResult<EquipmentResponse> createEquipment(UUID subjectId, UUID key, CreateEquipmentRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "equipment-catalog.create", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), EquipmentResponse.class), true);
    EquipmentCatalogItem candidate = EquipmentCatalogItem.create(request.code(), request.name(), request.category(), request.comment());
    if (equipment.existsByCode(candidate.getCode())) throw new AssetConflictException("Equipment catalog code is globally unique and cannot be reused");
    EquipmentCatalogItem saved = equipment.saveAndFlush(candidate);
    events.initialize(AssetAggregateType.EQUIPMENT_CATALOG, saved.getId(), saved.getVersion(), AssetEventType.EQUIPMENT_CATALOG_CREATED,
        equipmentFact(saved), equipmentSnapshot(saved));
    EquipmentResponse response = equipmentResponse(saved);
    idempotency.store(subjectId, "equipment-catalog.create", key, hash, 201, response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public EquipmentResponse updateEquipment(UUID id, UpdateEquipmentRequest request) {
    EquipmentCatalogItem item = requireEquipment(id);
    assertVersion(item.getVersion(), request.expectedVersion());
    String code = EquipmentCatalogItem.canonicalCode(request.code());
    if (equipment.existsByCodeAndIdNot(code, id)) throw new AssetConflictException("Equipment catalog code is globally unique and cannot be reused");
    if (!item.change(request.code(), request.name(), request.category(), request.active(), request.comment())) return equipmentResponse(item);
    EquipmentCatalogItem saved = equipment.saveAndFlush(item);
    events.append(AssetAggregateType.EQUIPMENT_CATALOG, id, request.expectedVersion(), AssetEventType.EQUIPMENT_CATALOG_CHANGED,
        equipmentFact(saved), equipmentSnapshot(saved));
    return equipmentResponse(saved);
  }

  @Transactional(readOnly = true)
  public EquipmentTotalsResponse equipmentTotals(UUID equipmentId, UUID warehouseId) {
    requireEquipment(equipmentId);
    List<BalanceRow> rows = balances(equipmentId, warehouseId);
    long stock = sum(rows, BalanceLocationKind.STOCK);
    long nonRented = sum(rows, BalanceLocationKind.CABIN_NON_RENTED);
    long rented = sum(rows, BalanceLocationKind.CABIN_RENTED);
    long writtenOff = sum(rows, BalanceLocationKind.WRITTEN_OFF);
    long lost = sum(rows, BalanceLocationKind.LOST);
    long held = activeHeld(equipmentId, warehouseId);
    List<EquipmentBalanceResponse> values = rows.stream().map(row -> balanceResponse(row, held)).toList();
    return new EquipmentTotalsResponse(equipmentId, warehouseId, Math.addExact(Math.addExact(Math.addExact(stock, nonRented), Math.addExact(rented, writtenOff)), lost),
        stock, nonRented, rented, writtenOff, lost, held, Math.max(0, stock - held), values);
  }

  @Transactional(readOnly = true)
  public List<EquipmentDispositionResponse> dispositions(UUID warehouseId) {
    return jdbc.query("""
        select m.id,m.version,m.equipment_id,m.source_balance_id,m.target_balance_id,m.quantity,m.movement_kind,m.occurred_at,
          c.code,c.name
        from equipment_movement m
        join equipment_catalog_item c on c.id=m.equipment_id
        join equipment_balance target on target.id=m.target_balance_id
        where target.warehouse_id=? and m.movement_kind in ('WRITE_OFF','LOSS')
        order by m.occurred_at desc,m.id desc
        """, (rs, row) -> new EquipmentDispositionResponse(
            new MovementResponse(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getObject("equipment_id", UUID.class),
                rs.getObject("source_balance_id", UUID.class), rs.getObject("target_balance_id", UUID.class), rs.getLong("quantity"),
                rs.getString("movement_kind"), rs.getObject("occurred_at", OffsetDateTime.class)),
            rs.getString("code"), rs.getString("name")), warehouseId);
  }

  @Transactional
  public CreateResult<MovementResponse> transfer(UUID subjectId, UUID key, TransferEquipmentRequest request) {
    if (request.targetLocationKind() == BalanceLocationKind.WRITTEN_OFF
        || request.targetLocationKind() == BalanceLocationKind.LOST) {
      throw new IllegalArgumentException("Use the disposition command for written-off or lost equipment");
    }
    return move(subjectId, key, "equipment.transfer", request, AssetEventType.EQUIPMENT_TRANSFERRED);
  }

  private CreateResult<MovementResponse> move(
      UUID subjectId,
      UUID key,
      String idempotencyScope,
      TransferEquipmentRequest request,
      AssetEventType eventType) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, idempotencyScope, key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), MovementResponse.class), true);
    warehouses.requireActive(request.sourceWarehouseId());
    warehouses.requireActive(request.targetWarehouseId());
    requireEquipment(request.equipmentId());
    advisoryLocks(java.util.stream.Stream.of(request.sourceRentalItemId(), request.targetRentalItemId())
        .filter(java.util.Objects::nonNull).map(AssetService::rentalItemLockKey).toList());
    java.util.stream.Stream.of(request.sourceRentalItemId(), request.targetRentalItemId())
        .filter(java.util.Objects::nonNull)
        .distinct()
        .sorted(Comparator.comparing(UUID::toString))
        .forEach(this::assertNoActiveLease);
    validateBalanceLocation(request.sourceWarehouseId(), request.sourceRentalItemId(), request.sourceLocationKind());
    validateBalanceLocation(request.targetWarehouseId(), request.targetRentalItemId(), request.targetLocationKind());
    if (sameLocation(request)) throw new AssetConflictException("Equipment transfer source and target must differ");
    advisoryLocks(List.of(
        balanceLockKey(request.equipmentId(), request.sourceWarehouseId(), request.sourceRentalItemId(), request.sourceLocationKind()),
        balanceLockKey(request.equipmentId(), request.targetWarehouseId(), request.targetRentalItemId(), request.targetLocationKind())));
    BalanceRow source = requireBalance(request.equipmentId(), request.sourceWarehouseId(), request.sourceRentalItemId(), request.sourceLocationKind());
    assertVersion(source.version(), request.sourceExpectedVersion());
    BalanceRow target = findBalance(request.equipmentId(), request.targetWarehouseId(), request.targetRentalItemId(), request.targetLocationKind())
        .orElseGet(() -> createEmptyBalance(request.equipmentId(), request.targetWarehouseId(), request.targetRentalItemId(), request.targetLocationKind()));
    assertVersion(target.version(), request.targetExpectedVersion());
    if (source.quantity() < request.quantity()) throw new AssetConflictException("Equipment balance cannot become negative");
    if (source.kind() == BalanceLocationKind.STOCK
        && Math.subtractExact(source.quantity(), request.quantity()) < activeHeld(source.equipmentId(), source.warehouseId())) {
      throw new AssetConflictException("Equipment transfer would consume stock reserved by active holds");
    }
    events.lockStreams(List.of(new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, source.id()), new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, target.id())));
    decrement(source, request.quantity(), request.sourceExpectedVersion());
    increment(target, request.quantity(), target.version());
    BalanceRow sourceAfter = requireBalanceById(source.id());
    BalanceRow targetAfter = requireBalanceById(target.id());
    events.append(AssetAggregateType.EQUIPMENT_BALANCE, source.id(), request.sourceExpectedVersion(), AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        balanceFact(sourceAfter), balanceSnapshot(sourceAfter));
    events.append(AssetAggregateType.EQUIPMENT_BALANCE, target.id(), target.version(), AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        balanceFact(targetAfter), balanceSnapshot(targetAfter));
    UUID movementId = UUID.randomUUID();
    String kind = movementKind(request.sourceLocationKind(), request.targetLocationKind());
    jdbc.update("insert into equipment_movement(id,version,equipment_id,source_balance_id,target_balance_id,quantity,movement_kind,occurred_at,actor_subject_id) values (?,0,?,?,?,?,?,clock_timestamp(),?)",
        movementId, request.equipmentId(), source.id(), target.id(), request.quantity(), kind, subjectId);
    jdbc.update("insert into equipment_movement_ledger(movement_id,line_no,balance_id,quantity_delta,recorded_at) values (?,1,?,-?,clock_timestamp()), (?,2,?,?,clock_timestamp())",
        movementId, source.id(), request.quantity(), movementId, target.id(), request.quantity());
    MovementResponse response = movementResponse(movementId);
    events.initialize(AssetAggregateType.EQUIPMENT_MOVEMENT, movementId, 0, eventType,
        movementFact(response), Map.of("movementId", movementId.toString(), "version", 0));
    idempotency.store(subjectId, idempotencyScope, key, hash, 201, response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public CreateResult<MovementResponse> dispose(UUID subjectId, UUID key, DispositionEquipmentRequest request) {
    BalanceLocationKind target = request.disposition() == Disposition.WRITE_OFF ? BalanceLocationKind.WRITTEN_OFF : BalanceLocationKind.LOST;
    TransferEquipmentRequest move = new TransferEquipmentRequest(request.equipmentId(), request.warehouseId(), request.sourceRentalItemId(),
        request.sourceLocationKind(), request.sourceExpectedVersion(), request.warehouseId(), null, target, 0L, request.quantity());
    AssetEventType eventType = request.disposition() == Disposition.WRITE_OFF
        ? AssetEventType.EQUIPMENT_WRITTEN_OFF : AssetEventType.EQUIPMENT_LOST;
    return move(subjectId, key, "equipment.disposition", move, eventType);
  }

  @Transactional
  public CreateResult<EquipmentHoldResponse> acquireHold(UUID subjectId, UUID key, AcquireEquipmentHoldRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "equipment-hold.acquire", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), EquipmentHoldResponse.class), true);
    warehouses.requireActive(request.warehouseId());
    requireEquipment(request.equipmentId());
    advisoryLock(balanceLockKey(request.equipmentId(), request.warehouseId(), null, BalanceLocationKind.STOCK));
    expireHolds(request.equipmentId(), request.warehouseId());
    BalanceRow stock = requireBalance(request.equipmentId(), request.warehouseId(), null, BalanceLocationKind.STOCK);
    assertVersion(stock.version(), request.expectedStockVersion());
    long available = Math.subtractExact(stock.quantity(), activeHeld(request.equipmentId(), request.warehouseId()));
    if (available < request.quantity()) throw new AssetConflictException("Active equipment holds reduce available stock");
    UUID id = UUID.randomUUID();
    OffsetDateTime expiry = now().plus(holdTtl);
    jdbc.update("""
        insert into equipment_allocation_hold(id,version,equipment_id,warehouse_id,owner_type,owner_id,quantity,state,idempotency_key,expires_at,created_at,updated_at)
        values (?,0,?,?,?,?,?,'ACTIVE',?,?,clock_timestamp(),clock_timestamp())
        """, id, request.equipmentId(), request.warehouseId(), canonicalOwnerType(request.ownerType()), canonicalOwnerId(request.ownerId()),
        request.quantity(), key, expiry);
    EquipmentHoldResponse response = holdResponse(id);
    events.initialize(AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD, id, 0, AssetEventType.EQUIPMENT_HOLD_ACQUIRED, holdFact(response), holdSnapshot(response));
    idempotency.store(subjectId, "equipment-hold.acquire", key, hash, 201, response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public CreateResult<EquipmentHoldResponse> renewHold(
      UUID subjectId, UUID key, UUID id, RenewEquipmentHoldRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "equipment-hold.renew", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), EquipmentHoldResponse.class), true);
    EquipmentHoldResponse current = holdResponse(id);
    assertVersion(current.version(), request.expectedVersion());
    if (!"ACTIVE".equals(current.state()) || current.expiresAt().isBefore(now())) throw new AssetConflictException("Equipment hold is not active");
    jdbc.update("update equipment_allocation_hold set version=version+1,expires_at=?,updated_at=clock_timestamp() where id=? and version=?", now().plus(holdTtl), id, request.expectedVersion());
    EquipmentHoldResponse updated = holdResponse(id);
    events.append(AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD, id, request.expectedVersion(), AssetEventType.EQUIPMENT_HOLD_RENEWED, holdFact(updated), holdSnapshot(updated));
    idempotency.store(subjectId, "equipment-hold.renew", key, hash, 200, updated);
    return new CreateResult<>(updated, false);
  }

  /**
   * A committed hold remains part of the stock availability invariant until a
   * later owning workflow releases it. Commit is deliberately a state change,
   * not a hidden logistics movement: asset-service must not infer a shipment,
   * transfer, or inventory destination from an opaque owner reference.
   */
  @Transactional
  public CreateResult<EquipmentHoldResponse> commitHold(
      UUID subjectId, UUID key, UUID id, CommitEquipmentHoldRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "equipment-hold.commit", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), EquipmentHoldResponse.class), true);
    EquipmentHoldResponse current = holdResponse(id);
    expireHolds(current.equipmentId(), current.warehouseId());
    current = holdResponse(id);
    assertVersion(current.version(), request.expectedVersion());
    if (!"ACTIVE".equals(current.state())) throw new AssetConflictException("Equipment hold is not active");
    int changed = jdbc.update("""
        update equipment_allocation_hold
        set version=version+1,state='COMMITTED',committed_at=clock_timestamp(),updated_at=clock_timestamp()
        where id=? and version=? and state='ACTIVE'
        """, id, request.expectedVersion());
    if (changed != 1) throw new AssetConflictException("Equipment hold changed concurrently during commit");
    EquipmentHoldResponse committed = holdResponse(id);
    events.append(AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD, id, request.expectedVersion(),
        AssetEventType.EQUIPMENT_HOLD_COMMITTED, holdFact(committed), holdSnapshot(committed));
    idempotency.store(subjectId, "equipment-hold.commit", key, hash, 200, committed);
    return new CreateResult<>(committed, false);
  }

  @Transactional
  public CreateResult<EquipmentHoldResponse> releaseHold(
      UUID subjectId, UUID key, UUID id, ReleaseEquipmentHoldRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "equipment-hold.release", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), EquipmentHoldResponse.class), true);
    EquipmentHoldResponse current = holdResponse(id);
    expireHolds(current.equipmentId(), current.warehouseId());
    current = holdResponse(id);
    assertVersion(current.version(), request.expectedVersion());
    EquipmentHoldResponse updated = current;
    if ("ACTIVE".equals(current.state()) || "COMMITTED".equals(current.state())) {
      int changed = jdbc.update("""
          update equipment_allocation_hold
          set version=version+1,state='RELEASED',released_at=clock_timestamp(),updated_at=clock_timestamp()
          where id=? and version=? and state in ('ACTIVE','COMMITTED')
          """, id, request.expectedVersion());
      if (changed != 1) throw new AssetConflictException("Equipment hold changed concurrently during release");
      updated = holdResponse(id);
      events.append(AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD, id, request.expectedVersion(), AssetEventType.EQUIPMENT_HOLD_RELEASED, holdFact(updated), holdSnapshot(updated));
    }
    idempotency.store(subjectId, "equipment-hold.release", key, hash, 200, updated);
    return new CreateResult<>(updated, false);
  }

  @Transactional
  public CreateResult<OperationLeaseResponse> acquireLease(UUID subjectId, UUID key, AcquireOperationLeaseRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "operation-lease.acquire", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), OperationLeaseResponse.class), true);

    lockRentalItemAndLease(request.rentalItemId());
    RentalItem item = requireRentalItem(request.rentalItemId());
    assertVersion(item.getVersion(), request.expectedRentalItemVersion());
    expireLeases(request.rentalItemId());
    String ownerType = canonicalOwnerType(request.ownerType());
    String ownerId = canonicalOwnerId(request.ownerId());
    List<OperationLease> active = activeLeasesForUpdate(request.rentalItemId());
    if (!active.isEmpty()) {
      OperationLease current = active.getFirst();
      if (!current.isOwnedBy(ownerType, ownerId)) {
        throw new AssetConflictException("Rental item already has an active operation lease");
      }
      long expectedVersion = current.getVersion();
      OffsetDateTime renewedAt = now();
      current.renew(renewedAt, renewedAt.plus(leaseTtl));
      OperationLeaseResponse renewed = leaseResponse(operationLeases.saveAndFlush(current));
      events.append(AssetAggregateType.OPERATION_LEASE, current.getId(), expectedVersion,
          AssetEventType.OPERATION_LEASE_RENEWED, leaseFact(renewed), leaseSnapshot(renewed));
      idempotency.store(subjectId, "operation-lease.acquire", key, hash, 200, renewed);
      return new CreateResult<>(renewed, false);
    }

    long next = Math.addExact(operationLeases.maximumFencingToken(request.rentalItemId()), 1);
    OffsetDateTime acquiredAt = now();
    OperationLease persisted = operationLeases.saveAndFlush(OperationLease.acquire(
        request.rentalItemId(), ownerType, ownerId, next, key, acquiredAt, acquiredAt.plus(leaseTtl)));
    OperationLeaseResponse response = leaseResponse(persisted);
    events.initialize(AssetAggregateType.OPERATION_LEASE, persisted.getId(), persisted.getVersion(),
        AssetEventType.OPERATION_LEASE_ACQUIRED, leaseFact(response), leaseSnapshot(response));
    idempotency.store(subjectId, "operation-lease.acquire", key, hash, 201, response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public CreateResult<OperationLeaseResponse> renewLease(
      UUID subjectId, UUID key, UUID id, RenewOperationLeaseRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "operation-lease.renew", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), OperationLeaseResponse.class), true);
    OperationLeaseResponse updated = renewLeaseState(id, request.expectedVersion(), request.fencingToken());
    idempotency.store(subjectId, "operation-lease.renew", key, hash, 200, updated);
    return new CreateResult<>(updated, false);
  }

  @Transactional
  public CreateResult<OperationLeaseResponse> releaseLease(
      UUID subjectId, UUID key, UUID id, ReleaseOperationLeaseRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "operation-lease.release", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), OperationLeaseResponse.class), true);
    OperationLeaseResponse updated = releaseLeaseState(id, request.expectedVersion(), request.fencingToken());
    idempotency.store(subjectId, "operation-lease.release", key, hash, 200, updated);
    return new CreateResult<>(updated, false);
  }

  @Transactional
  public CreateResult<OperationLeaseResponse> acquireMaintenanceLease(
      UUID subjectId, UUID key, AcquireMaintenanceOperationLeaseRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "maintenance.operation-lease.acquire", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), OperationLeaseResponse.class), true);
    }

    // Every rental-item mutation takes these locks in this order. Re-read the
    // aggregate only after both locks so expectedRentalItemVersion fences a
    // concurrent manual status mutation rather than a stale pre-lock read.
    lockRentalItemAndLease(request.rentalItemId());
    RentalItem item = requireRentalItem(request.rentalItemId());
    assertVersion(item.getVersion(), request.expectedRentalItemVersion());
    expireLeases(request.rentalItemId());
    List<OperationLease> active = activeLeasesForUpdate(request.rentalItemId());
    if (!active.isEmpty()) {
      throw new AssetConflictException(
          "Rental item already has an active operation lease; reacquisition is forbidden");
    }

    long next = Math.addExact(operationLeases.maximumFencingToken(request.rentalItemId()), 1);
    OffsetDateTime acquiredAt = now();
    OperationLease persisted = operationLeases.saveAndFlush(OperationLease.acquire(
        request.rentalItemId(),
        request.ownerType().name(),
        request.ownerId().toString(),
        next,
        key,
        acquiredAt,
        acquiredAt.plus(leaseTtl)));
    OperationLeaseResponse response = leaseResponse(persisted);
    events.initialize(
        AssetAggregateType.OPERATION_LEASE,
        persisted.getId(),
        persisted.getVersion(),
        AssetEventType.OPERATION_LEASE_ACQUIRED,
        leaseFact(response),
        leaseSnapshot(response));
    idempotency.store(
        subjectId, "maintenance.operation-lease.acquire", key, hash, 201, response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public CreateResult<OperationLeaseResponse> renewMaintenanceLease(
      UUID subjectId, UUID key, UUID id, RenewMaintenanceOperationLeaseRequest request) {
    OperationLease current = requireLeaseForUpdate(id);
    assertMaintenanceOwner(current, request.ownerType(), request.ownerId());
    String hash = hash(new MaintenanceLeaseCommand<>(id, request));
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "maintenance.operation-lease.renew", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), OperationLeaseResponse.class), true);
    }
    OperationLeaseResponse updated = renewLeaseState(
        id, request.expectedVersion(), request.fencingToken());
    idempotency.store(
        subjectId, "maintenance.operation-lease.renew", key, hash, 200, updated);
    return new CreateResult<>(updated, false);
  }

  @Transactional
  public CreateResult<OperationLeaseResponse> releaseMaintenanceLease(
      UUID subjectId, UUID key, UUID id, ReleaseMaintenanceOperationLeaseRequest request) {
    OperationLease current = requireLeaseForUpdate(id);
    assertMaintenanceOwner(current, request.ownerType(), request.ownerId());
    String hash = hash(new MaintenanceLeaseCommand<>(id, request));
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "maintenance.operation-lease.release", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), OperationLeaseResponse.class), true);
    }
    OperationLeaseResponse updated = releaseLeaseState(
        id, request.expectedVersion(), request.fencingToken());
    idempotency.store(
        subjectId, "maintenance.operation-lease.release", key, hash, 200, updated);
    return new CreateResult<>(updated, false);
  }

  @Transactional
  public CreateResult<RentalItemResponse> maintenanceFencedStatus(
      UUID subjectId, UUID key, UUID id, MaintenanceFencedStatusRequest request) {
    advisoryLock(rentalItemLockKey(id));
    OperationLease lease = validateLease(id, request.leaseId(), request.fencingToken());
    assertMaintenanceOwner(lease, request.ownerType(), request.ownerId());
    String hash = hash(new MaintenanceLeaseCommand<>(id, request));
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "maintenance.rental-item.fenced-status", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), RentalItemResponse.class), true);
    }
    RentalItem current = requireRentalItem(id);
    RentalItemStatus target = MaintenanceAssetTransitionPolicy.target(
        current.getStatus(),
        request.action(),
        request.ownerType(),
        request.ownerId(),
        request.linkedReturnEstimateId());
    RentalItemResponse updated = changeStatusLocked(id, request.expectedVersion(), target, true);
    idempotency.store(
        subjectId, "maintenance.rental-item.fenced-status", key, hash, 200, updated);
    return new CreateResult<>(updated, false);
  }

  private OperationLeaseResponse renewLeaseState(UUID id, Long expectedVersion, long fencingToken) {
    OperationLease current = requireLeaseForUpdate(id);
    assertVersion(current.getVersion(), expectedVersion);
    OffsetDateTime renewedAt = now();
    assertFencing(current, fencingToken, renewedAt);
    current.renew(renewedAt, renewedAt.plus(leaseTtl));
    OperationLeaseResponse updated = leaseResponse(operationLeases.saveAndFlush(current));
    events.append(AssetAggregateType.OPERATION_LEASE, id, expectedVersion,
        AssetEventType.OPERATION_LEASE_RENEWED, leaseFact(updated), leaseSnapshot(updated));
    return updated;
  }

  private OperationLeaseResponse releaseLeaseState(UUID id, Long expectedVersion, long fencingToken) {
    OperationLease current = requireLeaseForUpdate(id);
    assertVersion(current.getVersion(), expectedVersion);
    OffsetDateTime releasedAt = now();
    assertFencing(current, fencingToken, releasedAt);
    current.release(releasedAt);
    OperationLeaseResponse updated = leaseResponse(operationLeases.saveAndFlush(current));
    events.append(AssetAggregateType.OPERATION_LEASE, id, expectedVersion,
        AssetEventType.OPERATION_LEASE_RELEASED, leaseFact(updated), leaseSnapshot(updated));
    return updated;
  }

  @Transactional(readOnly = true)
  public List<ClassifierResponse> classifiers(String type) {
    return jdbc.query("select id,version,classifier_type,parent_id,code,name,active,sort_order from asset_classifier where (? is null or classifier_type=?) order by classifier_type,sort_order nulls last,code",
        (rs, row) -> new ClassifierResponse(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("classifier_type"),
            rs.getObject("parent_id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getBoolean("active"), rs.getObject("sort_order", Integer.class)), type, type);
  }

  @Transactional
  public CreateResult<ClassifierResponse> createClassifier(UUID subjectId, UUID key, CreateClassifierRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "classifier.create", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), ClassifierResponse.class), true);
    UUID id = UUID.randomUUID();
    jdbc.update("insert into asset_classifier(id,version,classifier_type,parent_id,code,name,active,sort_order,created_at,updated_at) values (?,0,?,?,?,?,?,?,clock_timestamp(),clock_timestamp())",
        id, classifierType(request.type()), request.parentId(), canonicalCode(request.code()), required(request.name(), 255), request.active(), request.sortOrder());
    ClassifierResponse response = classifier(id);
    events.initialize(AssetAggregateType.CLASSIFIER, id, 0, AssetEventType.CLASSIFIER_CREATED,
        classifierFact(response), classifierSnapshot(response));
    idempotency.store(subjectId, "classifier.create", key, hash, 201, response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public ClassifierResponse updateClassifier(UUID id, ClassifierRequest request) {
    ClassifierResponse current = classifier(id);
    assertVersion(current.version(), request.expectedVersion());
    int changed = jdbc.update("""
        update asset_classifier set classifier_type=?,parent_id=?,code=?,name=?,active=?,sort_order=?,version=version+1,updated_at=clock_timestamp()
        where id=? and version=?
        """, classifierType(request.type()), request.parentId(), canonicalCode(request.code()), required(request.name(), 255), request.active(), request.sortOrder(), id, request.expectedVersion());
    if (changed != 1) throw new AssetConflictException("Classifier changed concurrently");
    ClassifierResponse updated = classifier(id);
    events.append(AssetAggregateType.CLASSIFIER, id, request.expectedVersion(), AssetEventType.CLASSIFIER_CHANGED,
        classifierFact(updated), classifierSnapshot(updated));
    return updated;
  }

  private RentalItemResponse changeStatus(UUID id, Long expectedVersion, RentalItemStatus status, boolean fenced) {
    advisoryLock(rentalItemLockKey(id));
    if (!fenced) assertNoActiveLease(id);
    return changeStatusLocked(id, expectedVersion, status, fenced);
  }

  private RentalItemResponse changeStatusLocked(
      UUID id, Long expectedVersion, RentalItemStatus status, boolean fenced) {
    RentalItem item = requireRentalItem(id);
    assertVersion(item.getVersion(), expectedVersion);
    RentalItemStatus previous = item.getStatus();
    boolean changed = fenced ? item.changeStatusUnderLease(status) : item.changeStatus(status);
    if (!changed) return rentalResponse(item);
    RentalItem saved = rentalItems.saveAndFlush(item);
    events.append(AssetAggregateType.RENTAL_ITEM, saved.getId(), expectedVersion, AssetEventType.RENTAL_ITEM_STATUS_CHANGED,
        rentalFact(saved), rentalSnapshot(saved));
    reclassifyCabinBalances(saved, previous);
    return rentalResponse(saved);
  }

  private RentalItem requireRentalItem(UUID id) { return rentalItems.findById(id).orElseThrow(() -> new AssetNotFoundException("Rental item was not found")); }
  private EquipmentCatalogItem requireEquipment(UUID id) { return equipment.findById(id).orElseThrow(() -> new AssetNotFoundException("Equipment catalog item was not found")); }
  private void assertVersion(long actual, Long expected) {
    if (expected == null || expected < 0) throw new IllegalArgumentException("expectedVersion is required");
    if (actual != expected) throw new AssetConflictException("Asset data changed concurrently");
  }
  private static Duration requireTtl(Duration ttl, String name) {
    if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(1)) > 0) throw new IllegalArgumentException(name + " ttl must be between 1 ms and 1 h");
    return ttl;
  }
  private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }

  private RentalItemResponse rentalResponse(RentalItem item) {
    return new RentalItemResponse(item.getId(), item.getVersion(), item.getWarehouseId(), item.getNumber(), item.getStatus(), item.getRentalType(),
        item.getDimensions(), item.getFinishing(), item.getCategory(), item.getCharacteristics(), item.getLinoleum(), item.getGeneralComment(),
        map(item.getPassportJson()), strings(item.getTagsJson()), contents(item.getId()), item.getCreatedAt(), item.getUpdatedAt());
  }
  private EquipmentResponse equipmentResponse(EquipmentCatalogItem item) {
    return equipmentMapper.toResponse(item);
  }
  private ManualNoteResponse manualNote(UUID id) {
    return jdbc.query("select id,rental_item_id,note_text,created_at from rental_item_note where id=?", (rs, row) -> manualNote(rs), id)
        .stream().findFirst().orElseThrow(() -> new AssetNotFoundException("Rental-item note was not found"));
  }
  private static ManualNoteResponse manualNote(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ManualNoteResponse(rs.getObject("id", UUID.class), rs.getObject("rental_item_id", UUID.class),
        rs.getString("note_text"), rs.getObject("created_at", OffsetDateTime.class));
  }
  private List<EquipmentContentResponse> contents(UUID rentalItemId) {
    return jdbc.query("""
        select b.equipment_id,c.code,b.quantity,b.location_kind from equipment_balance b
        join equipment_catalog_item c on c.id=b.equipment_id
        where b.rental_item_id=? and b.location_kind in ('CABIN_NON_RENTED','CABIN_RENTED') and b.quantity>0 order by c.code
        """, (rs, row) -> new EquipmentContentResponse(rs.getObject("equipment_id", UUID.class), rs.getString("code"), rs.getLong("quantity"),
        BalanceLocationKind.valueOf(rs.getString("location_kind"))), rentalItemId);
  }

  private List<BalanceRow> balances(UUID equipmentId, UUID warehouseId) {
    return jdbc.query("select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity from equipment_balance where equipment_id=? and warehouse_id=? order by location_kind,rental_item_id",
        (rs, row) -> balanceRow(rs), equipmentId, warehouseId);
  }
  private Optional<BalanceRow> findBalance(UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    return jdbc.query("select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity from equipment_balance where equipment_id=? and warehouse_id=? and rental_item_id is not distinct from ? and location_kind=? for update",
        (rs, row) -> balanceRow(rs), equipmentId, warehouseId, rentalItemId, kind.name()).stream().findFirst();
  }
  private BalanceRow requireBalance(UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    return findBalance(equipmentId, warehouseId, rentalItemId, kind).orElseThrow(() -> new AssetNotFoundException("Equipment balance was not found"));
  }
  private BalanceRow requireBalanceById(UUID id) {
    return jdbc.query("select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity from equipment_balance where id=? for update",
        (rs, row) -> balanceRow(rs), id).stream().findFirst().orElseThrow(() -> new AssetNotFoundException("Equipment balance was not found"));
  }
  private BalanceRow createEmptyBalance(UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into equipment_balance(id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,created_at,updated_at) values (?,0,?,?,?,?,0,clock_timestamp(),clock_timestamp())",
        id, equipmentId, warehouseId, rentalItemId, kind.name());
    BalanceRow row = requireBalanceById(id);
    events.initialize(AssetAggregateType.EQUIPMENT_BALANCE, id, 0, AssetEventType.EQUIPMENT_BALANCE_CHANGED, balanceFact(row), balanceSnapshot(row));
    return row;
  }
  private static BalanceRow balanceRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new BalanceRow(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getObject("equipment_id", UUID.class),
        rs.getObject("warehouse_id", UUID.class), rs.getObject("rental_item_id", UUID.class), BalanceLocationKind.valueOf(rs.getString("location_kind")), rs.getLong("quantity"));
  }
  private void decrement(BalanceRow row, long amount, long expectedVersion) {
    int changed = jdbc.update("update equipment_balance set quantity=quantity-?,version=version+1,updated_at=clock_timestamp() where id=? and version=? and quantity>=?", amount, row.id(), expectedVersion, amount);
    if (changed != 1) throw new AssetConflictException("Equipment balance changed concurrently or cannot become negative");
  }
  private void increment(BalanceRow row, long amount, long expectedVersion) {
    int changed = jdbc.update("update equipment_balance set quantity=quantity+?,version=version+1,updated_at=clock_timestamp() where id=? and version=?", amount, row.id(), expectedVersion);
    if (changed != 1) throw new AssetConflictException("Target equipment balance changed concurrently");
  }
  private static long sum(List<BalanceRow> values, BalanceLocationKind kind) { return values.stream().filter(row -> row.kind() == kind).mapToLong(BalanceRow::quantity).sum(); }
  private long activeHeld(UUID equipmentId, UUID warehouseId) {
    Long value = jdbc.queryForObject("""
        select coalesce(sum(quantity),0) from equipment_allocation_hold
        where equipment_id=? and warehouse_id=?
          and (state='COMMITTED' or (state='ACTIVE' and expires_at>clock_timestamp()))
        """, Long.class, equipmentId, warehouseId);
    return value == null ? 0 : value;
  }
  private EquipmentBalanceResponse balanceResponse(BalanceRow row, long allHolds) {
    return new EquipmentBalanceResponse(row.id(), row.version(), row.equipmentId(), row.warehouseId(), row.rentalItemId(), row.kind(), row.quantity(),
        row.kind() == BalanceLocationKind.STOCK ? allHolds : 0, row.kind() == BalanceLocationKind.STOCK ? Math.max(0, row.quantity() - allHolds) : row.quantity());
  }
  private void validateBalanceLocation(UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    boolean cabin = kind == BalanceLocationKind.CABIN_NON_RENTED || kind == BalanceLocationKind.CABIN_RENTED;
    if (cabin != (rentalItemId != null)) throw new IllegalArgumentException("Balance location does not match cabin reference");
    if (!cabin) return;
    RentalItem item = requireRentalItem(rentalItemId);
    if (!warehouseId.equals(item.getWarehouseId())) throw new AssetConflictException("Cabin must belong to the balance warehouse");
    BalanceLocationKind expected = item.getStatus() == RentalItemStatus.RENTED ? BalanceLocationKind.CABIN_RENTED : BalanceLocationKind.CABIN_NON_RENTED;
    if (kind != expected) throw new AssetConflictException("Cabin balance location does not match canonical cabin status");
  }
  private static boolean sameLocation(TransferEquipmentRequest request) {
    return request.sourceWarehouseId().equals(request.targetWarehouseId()) && java.util.Objects.equals(request.sourceRentalItemId(), request.targetRentalItemId())
        && request.sourceLocationKind() == request.targetLocationKind();
  }
  private static String movementKind(BalanceLocationKind source, BalanceLocationKind target) {
    if (target == BalanceLocationKind.WRITTEN_OFF) return "WRITE_OFF";
    if (target == BalanceLocationKind.LOST) return "LOSS";
    if (source == BalanceLocationKind.STOCK && (target == BalanceLocationKind.CABIN_NON_RENTED || target == BalanceLocationKind.CABIN_RENTED)) return "STOCK_TO_CABIN";
    if ((source == BalanceLocationKind.CABIN_NON_RENTED || source == BalanceLocationKind.CABIN_RENTED) && target == BalanceLocationKind.STOCK) return "CABIN_TO_STOCK";
    if ((source == BalanceLocationKind.CABIN_NON_RENTED || source == BalanceLocationKind.CABIN_RENTED) && (target == BalanceLocationKind.CABIN_NON_RENTED || target == BalanceLocationKind.CABIN_RENTED)) return "CABIN_TO_CABIN";
    return "WAREHOUSE_TO_WAREHOUSE";
  }
  private MovementResponse movementResponse(UUID id) {
    return jdbc.query("select id,version,equipment_id,source_balance_id,target_balance_id,quantity,movement_kind,occurred_at from equipment_movement where id=?",
        (rs, row) -> new MovementResponse(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getObject("equipment_id", UUID.class),
            rs.getObject("source_balance_id", UUID.class), rs.getObject("target_balance_id", UUID.class), rs.getLong("quantity"), rs.getString("movement_kind"), rs.getObject("occurred_at", OffsetDateTime.class)), id).stream().findFirst().orElseThrow();
  }

  private void reclassifyCabinBalances(RentalItem item, RentalItemStatus previous) {
    BalanceLocationKind before = previous == RentalItemStatus.RENTED ? BalanceLocationKind.CABIN_RENTED : BalanceLocationKind.CABIN_NON_RENTED;
    BalanceLocationKind after = item.getStatus() == RentalItemStatus.RENTED ? BalanceLocationKind.CABIN_RENTED : BalanceLocationKind.CABIN_NON_RENTED;
    if (before == after) return;
    List<BalanceRow> candidates = jdbc.query("select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity from equipment_balance where rental_item_id=? and location_kind=?",
        (rs, row) -> balanceRow(rs), item.getId(), before.name());
    advisoryLocks(candidates.stream()
        .flatMap(row -> java.util.stream.Stream.of(
            balanceLockKey(row.equipmentId(), row.warehouseId(), row.rentalItemId(), before),
            balanceLockKey(row.equipmentId(), row.warehouseId(), row.rentalItemId(), after)))
        .toList());
    events.lockStreams(candidates.stream()
        .map(row -> new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, row.id()))
        .toList());
    List<BalanceRow> values = candidates.stream()
        .sorted(Comparator.comparing(row -> row.id().toString()))
        .map(row -> requireBalanceById(row.id()))
        .filter(row -> row.kind() == before)
        .toList();
    for (BalanceRow row : values) {
      int updateCount = jdbc.update("update equipment_balance set location_kind=?,version=version+1,updated_at=clock_timestamp() where id=? and version=?", after.name(), row.id(), row.version());
      if (updateCount != 1) throw new AssetConflictException("Equipment balance changed concurrently during cabin status reclassification");
      BalanceRow updatedRow = requireBalanceById(row.id());
      events.append(AssetAggregateType.EQUIPMENT_BALANCE, row.id(), row.version(), AssetEventType.EQUIPMENT_BALANCE_CHANGED, balanceFact(updatedRow), balanceSnapshot(updatedRow));
    }
  }

  /**
   * Transfer arrival moves non-zero attached cabin balances in asset's own
   * ledger while the validated logistics lease fences all competing cabin
   * changes. Zero source buckets remain historic rows at the origin.
   */
  private void relocateCabinContentsUnderLease(
      UUID subjectId, UUID rentalItemId, UUID sourceWarehouseId, UUID destinationWarehouseId) {
    List<BalanceRow> candidates = jdbc.query(
        """
        select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity
        from equipment_balance
        where rental_item_id=? and warehouse_id=? and quantity>0
        order by equipment_id,id
        """,
        (rs, row) -> balanceRow(rs),
        rentalItemId,
        sourceWarehouseId);
    for (BalanceRow candidate : candidates) {
      if (candidate.kind() != BalanceLocationKind.CABIN_NON_RENTED
          && candidate.kind() != BalanceLocationKind.CABIN_RENTED) {
        throw new AssetConflictException(
            "Only canonical cabin balances may move with a transfer arrival");
      }
    }
    advisoryLocks(candidates.stream()
        .flatMap(row -> java.util.stream.Stream.of(
            balanceLockKey(
                row.equipmentId(),
                sourceWarehouseId,
                rentalItemId,
                row.kind()),
            balanceLockKey(
                row.equipmentId(),
                destinationWarehouseId,
                rentalItemId,
                row.kind())))
        .toList());

    for (BalanceRow candidate : candidates) {
      BalanceRow source = requireBalanceById(candidate.id());
      if (source.quantity() == 0) {
        continue;
      }
      if (!source.warehouseId().equals(sourceWarehouseId)
          || !rentalItemId.equals(source.rentalItemId())
          || source.kind() != candidate.kind()) {
        throw new AssetConflictException(
            "Cabin balance changed concurrently during transfer arrival");
      }
      BalanceRow target = findBalance(
          source.equipmentId(),
          destinationWarehouseId,
          rentalItemId,
          source.kind()).orElseGet(
              () -> createEmptyBalance(
                  source.equipmentId(),
                  destinationWarehouseId,
                  rentalItemId,
                  source.kind()));
      if (target.quantity() != 0) {
        throw new AssetConflictException(
            "Destination cabin already has a canonical equipment balance");
      }
      events.lockStreams(List.of(
          new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, source.id()),
          new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, target.id())));
      long quantity = source.quantity();
      decrement(source, quantity, source.version());
      increment(target, quantity, target.version());
      BalanceRow sourceAfter = requireBalanceById(source.id());
      BalanceRow targetAfter = requireBalanceById(target.id());
      events.append(
          AssetAggregateType.EQUIPMENT_BALANCE,
          source.id(),
          source.version(),
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          balanceFact(sourceAfter),
          balanceSnapshot(sourceAfter));
      events.append(
          AssetAggregateType.EQUIPMENT_BALANCE,
          target.id(),
          target.version(),
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          balanceFact(targetAfter),
          balanceSnapshot(targetAfter));
      UUID movementId = UUID.randomUUID();
      jdbc.update(
          """
          insert into equipment_movement(
            id,version,equipment_id,source_balance_id,target_balance_id,quantity,movement_kind,occurred_at,actor_subject_id)
          values (?,0,?,?,?,?,'CABIN_TO_CABIN',clock_timestamp(),?)
          """,
          movementId,
          source.equipmentId(),
          source.id(),
          target.id(),
          quantity,
          subjectId);
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
      MovementResponse movement = movementResponse(movementId);
      events.initialize(
          AssetAggregateType.EQUIPMENT_MOVEMENT,
          movementId,
          0,
          AssetEventType.EQUIPMENT_TRANSFERRED,
          movementFact(movement),
          Map.of("movementId", movementId.toString(), "version", 0));
    }
  }

  private void expireHolds(UUID equipmentId, UUID warehouseId) {
    List<EquipmentHoldResponse> expired = jdbc.query("""
        select id,version,equipment_id,warehouse_id,owner_type,owner_id,quantity,state,expires_at,committed_at
        from equipment_allocation_hold
        where equipment_id=? and warehouse_id=? and state='ACTIVE' and expires_at<=clock_timestamp()
        for update
        """, (rs, row) -> new EquipmentHoldResponse(rs.getObject("id", UUID.class), rs.getLong("version"),
        rs.getObject("equipment_id", UUID.class), rs.getObject("warehouse_id", UUID.class), rs.getString("owner_type"),
        rs.getString("owner_id"), rs.getLong("quantity"), rs.getString("state"), rs.getObject("expires_at", OffsetDateTime.class),
        rs.getObject("committed_at", OffsetDateTime.class)), equipmentId, warehouseId);
    for (EquipmentHoldResponse current : expired) {
      int changed = jdbc.update("update equipment_allocation_hold set state='EXPIRED',released_at=clock_timestamp(),version=version+1,updated_at=clock_timestamp() where id=? and version=? and state='ACTIVE'", current.id(), current.version());
      if (changed != 1) throw new AssetConflictException("Equipment hold changed concurrently during expiry");
      EquipmentHoldResponse updated = holdResponse(current.id());
      events.append(AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD, current.id(), current.version(), AssetEventType.EQUIPMENT_HOLD_EXPIRED,
          holdFact(updated), holdSnapshot(updated));
    }
  }
  private EquipmentHoldResponse holdResponse(UUID id) {
    return jdbc.query("select id,version,equipment_id,warehouse_id,owner_type,owner_id,quantity,state,expires_at,committed_at from equipment_allocation_hold where id=? for update",
        (rs, row) -> new EquipmentHoldResponse(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getObject("equipment_id", UUID.class),
            rs.getObject("warehouse_id", UUID.class), rs.getString("owner_type"), rs.getString("owner_id"), rs.getLong("quantity"), rs.getString("state"),
            rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("committed_at", OffsetDateTime.class)), id).stream().findFirst().orElseThrow(() -> new AssetNotFoundException("Equipment hold was not found"));
  }
  private void expireLeases(UUID itemId) {
    OffsetDateTime expiredAt = now();
    List<OperationLease> expired = operationLeases.findExpiredByRentalItemIdAndStateForUpdate(
        itemId, OperationLeaseState.ACTIVE, expiredAt);
    for (OperationLease current : expired) {
      long expectedVersion = current.getVersion();
      if (!current.expire(expiredAt)) continue;
      OperationLeaseResponse updated = leaseResponse(operationLeases.saveAndFlush(current));
      events.append(AssetAggregateType.OPERATION_LEASE, current.getId(), expectedVersion, AssetEventType.OPERATION_LEASE_EXPIRED,
          leaseFact(updated), leaseSnapshot(updated));
    }
  }
  private OperationLeaseResponse leaseResponse(OperationLease lease) {
    return new OperationLeaseResponse(
        lease.getId(),
        lease.getVersion(),
        lease.getRentalItemId(),
        lease.getOwnerType(),
        lease.getOwnerId(),
        lease.getFencingToken(),
        lease.getState().name(),
        lease.getExpiresAt());
  }
  private OperationLease validateLease(UUID rentalItemId, UUID leaseId, long fencingToken) {
    lockRentalItemAndLease(rentalItemId);
    expireLeases(rentalItemId);
    OperationLease lease = operationLeases.findByIdForUpdate(leaseId)
        .orElseThrow(() -> new AssetNotFoundException("Operation lease was not found"));
    if (!lease.getRentalItemId().equals(rentalItemId)) {
      throw new AssetConflictException("Operation lease belongs to another rental item");
    }
    assertFencing(lease, fencingToken, now());
    return lease;
  }
  private void assertMaintenanceOwner(
      OperationLease lease, MaintenanceLeaseOwnerType ownerType, UUID ownerId) {
    if (ownerType == null
        || ownerId == null
        || !lease.isOwnedBy(ownerType.name(), ownerId.toString())) {
      throw new AssetConflictException("Operation lease belongs to another maintenance owner");
    }
  }
  private void assertLogisticsLeaseOwner(
      OperationLease lease,
      LogisticsLeaseOwnerType ownerType,
      UUID documentId,
      UUID lineId) {
    if (lease == null
        || !lease.isOwnedBy(
            logisticsOwnerType(ownerType), logisticsOwnerId(documentId, lineId))) {
      throw new AssetConflictException(
          "Operation lease belongs to another logistics document line");
    }
  }
  private void assertLogisticsShipmentHoldOwner(
      EquipmentHoldResponse hold, UUID shipmentId, UUID shipmentLineId) {
    if (hold == null
        || !LogisticsLeaseOwnerType.LOGISTICS_SHIPMENT.name().equals(hold.ownerType())
        || !logisticsOwnerId(shipmentId, shipmentLineId).equals(hold.ownerId())) {
      throw new AssetConflictException(
          "Equipment hold belongs to another logistics shipment line");
    }
  }
  private void assertFencing(OperationLease lease, long token, OffsetDateTime instant) {
    if (!lease.isActiveAt(instant) || lease.getFencingToken() != token) {
      throw new AssetConflictException("Operation lease is stale or fenced");
    }
  }
  private void assertNoActiveLease(UUID rentalItemId) {
    lockRentalItemAndLease(rentalItemId);
    expireLeases(rentalItemId);
    if (!activeLeasesForUpdate(rentalItemId).isEmpty()) {
      throw new AssetConflictException("Rental item has an active operation lease; use a fenced internal command");
    }
  }
  private OperationLease requireLeaseForUpdate(UUID id) {
    UUID rentalItemId = operationLeases.findRentalItemIdById(id)
        .orElseThrow(() -> new AssetNotFoundException("Operation lease was not found"));
    lockRentalItemAndLease(rentalItemId);
    return operationLeases.findByIdForUpdate(id)
        .orElseThrow(() -> new AssetNotFoundException("Operation lease was not found"));
  }
  private List<OperationLease> activeLeasesForUpdate(UUID rentalItemId) {
    List<OperationLease> active = operationLeases.findByRentalItemIdAndStateForUpdate(
        rentalItemId, OperationLeaseState.ACTIVE);
    if (active.size() > 1) {
      throw new IllegalStateException("Active operation-lease uniqueness is corrupted");
    }
    return active;
  }
  private void lockRentalItemAndLease(UUID rentalItemId) {
    advisoryLock(rentalItemLockKey(rentalItemId));
    advisoryLock(leaseLockKey(rentalItemId));
  }
  private static String rentalItemLockKey(UUID rentalItemId) { return "asset-rental-item:" + rentalItemId; }
  private static String leaseLockKey(UUID rentalItemId) { return "lease:" + rentalItemId; }
  private static String balanceLockKey(UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    return "asset-balance:" + equipmentId + ':' + warehouseId + ':' + (rentalItemId == null ? "-" : rentalItemId) + ':' + kind.name();
  }
  private void advisoryLocks(Collection<String> values) {
    values.stream().filter(java.util.Objects::nonNull).distinct().sorted().forEach(this::advisoryLock);
  }
  private void advisoryLock(String value) { jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, value); }

  private ClassifierResponse classifier(UUID id) {
    return jdbc.query("select id,version,classifier_type,parent_id,code,name,active,sort_order from asset_classifier where id=?",
        (rs, row) -> new ClassifierResponse(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("classifier_type"),
            rs.getObject("parent_id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getBoolean("active"), rs.getObject("sort_order", Integer.class)), id).stream().findFirst().orElseThrow(() -> new AssetNotFoundException("Classifier was not found"));
  }
  private static String classifierType(String value) {
    String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    if (!List.of("CATEGORY", "SUBCATEGORY", "TYPE", "CONDITION").contains(normalized)) throw new IllegalArgumentException("Unsupported classifier type");
    return normalized;
  }
  private static String canonicalCode(String value) { return EquipmentCatalogItem.canonicalCode(value); }
  private static String required(String value, int max) {
    if (value == null || value.trim().isEmpty() || value.trim().length() > max) throw new IllegalArgumentException("Value is required and bounded");
    return value.trim();
  }
  private static String canonicalOwnerType(String value) {
    String result = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    if (!result.matches("^[A-Z][A-Z0-9_]{0,63}$")) throw new IllegalArgumentException("ownerType has invalid format");
    return result;
  }
  private static String canonicalOwnerId(String value) {
    String result = value == null ? "" : value.trim();
    if (result.isEmpty() || result.length() > 128) throw new IllegalArgumentException("ownerId has invalid format");
    return result;
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

  private Map<String, ?> rentalFact(RentalItem item) {
    return Map.of("rentalItemId", item.getId().toString(), "warehouseId", item.getWarehouseId().toString(), "status", item.getStatus().name(),
        "numberSha256", AssetChecksum.sha256(item.getNumber().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }
  /** Full service-local state for deterministic replay; never serialized into a Kafka envelope. */
  private Map<String, ?> rentalSnapshot(RentalItem item) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("rentalItemId", item.getId().toString());
    value.put("version", item.getVersion());
    value.put("warehouseId", item.getWarehouseId().toString());
    value.put("number", item.getNumber());
    value.put("status", item.getStatus().name());
    value.put("rentalType", item.getRentalType());
    value.put("dimensions", item.getDimensions());
    value.put("finishing", item.getFinishing());
    value.put("category", item.getCategory());
    value.put("characteristics", item.getCharacteristics());
    value.put("linoleum", item.getLinoleum());
    value.put("generalComment", item.getGeneralComment());
    value.put("passport", map(item.getPassportJson()));
    value.put("tags", strings(item.getTagsJson()));
    return value;
  }
  private Map<String, ?> equipmentFact(EquipmentCatalogItem item) {
    return Map.of("equipmentId", item.getId().toString(), "code", item.getCode(), "category", item.getCategory().name(), "active", item.isActive());
  }
  private Map<String, ?> equipmentSnapshot(EquipmentCatalogItem item) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("equipmentId", item.getId().toString());
    value.put("version", item.getVersion());
    value.put("code", item.getCode());
    value.put("name", item.getName());
    value.put("category", item.getCategory().name());
    value.put("active", item.isActive());
    value.put("comment", item.getComment());
    return value;
  }
  private Map<String, ?> balanceFact(BalanceRow row) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("balanceId", row.id().toString()); value.put("equipmentId", row.equipmentId().toString()); value.put("warehouseId", row.warehouseId().toString());
    value.put("rentalItemId", row.rentalItemId() == null ? null : row.rentalItemId().toString()); value.put("locationKind", row.kind().name()); value.put("quantity", row.quantity());
    return value;
  }
  private Map<String, ?> balanceSnapshot(BalanceRow row) { return balanceFact(row); }
  private Map<String, ?> movementFact(MovementResponse value) {
    return Map.of("movementId", value.id().toString(), "equipmentId", value.equipmentId().toString(), "sourceBalanceId", value.sourceBalanceId().toString(),
        "targetBalanceId", value.targetBalanceId().toString(), "quantity", value.quantity(), "movementKind", value.kind());
  }
  private Map<String, ?> holdFact(EquipmentHoldResponse value) {
    return Map.of("holdId", value.id().toString(), "equipmentId", value.equipmentId().toString(), "warehouseId", value.warehouseId().toString(), "quantity", value.quantity(), "state", value.state());
  }
  private Map<String, ?> holdSnapshot(EquipmentHoldResponse value) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("holdId", value.id().toString());
    snapshot.put("version", value.version());
    snapshot.put("equipmentId", value.equipmentId().toString());
    snapshot.put("warehouseId", value.warehouseId().toString());
    snapshot.put("ownerType", value.ownerType());
    snapshot.put("ownerId", value.ownerId());
    snapshot.put("quantity", value.quantity());
    snapshot.put("state", value.state());
    snapshot.put("expiresAt", value.expiresAt().toString());
    snapshot.put("committedAt", value.committedAt() == null ? null : value.committedAt().toString());
    return snapshot;
  }
  private Map<String, ?> leaseFact(OperationLeaseResponse value) {
    return Map.of("leaseId", value.id().toString(), "rentalItemId", value.rentalItemId().toString(), "fencingToken", value.fencingToken(), "state", value.state());
  }
  private Map<String, ?> leaseSnapshot(OperationLeaseResponse value) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("leaseId", value.id().toString());
    snapshot.put("version", value.version());
    snapshot.put("rentalItemId", value.rentalItemId().toString());
    snapshot.put("ownerType", value.ownerType());
    snapshot.put("ownerId", value.ownerId());
    snapshot.put("fencingToken", value.fencingToken());
    snapshot.put("state", value.state());
    snapshot.put("expiresAt", value.expiresAt().toString());
    return snapshot;
  }
  private Map<String, ?> classifierFact(ClassifierResponse value) {
    Map<String, Object> fact = new LinkedHashMap<>();
    fact.put("classifierId", value.id().toString());
    fact.put("type", value.type());
    fact.put("parentId", value.parentId() == null ? null : value.parentId().toString());
    fact.put("code", value.code());
    fact.put("label", value.name());
    fact.put("active", value.active());
    fact.put("sortOrder", value.sortOrder());
    return fact;
  }
  private Map<String, ?> classifierSnapshot(ClassifierResponse value) { return classifierFact(value); }

  private String hash(Object value) {
    try { return AssetChecksum.sha256(mapper.writeValueAsBytes(value)); }
    catch (JacksonException exception) { throw new IllegalArgumentException("Asset command cannot be fingerprinted", exception); }
  }
  private String jsonObject(Object value) {
    try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
    catch (JacksonException exception) { throw new IllegalArgumentException("Asset JSON is invalid", exception); }
  }
  private String jsonArray(Object value) {
    try { return mapper.writeValueAsString(value == null ? List.of() : value); }
    catch (JacksonException exception) { throw new IllegalArgumentException("Asset JSON is invalid", exception); }
  }
  private Map<String, Object> map(String value) {
    try { return mapper.readValue(value, new TypeReference<Map<String, Object>>() {}); }
    catch (JacksonException exception) { throw new IllegalStateException("Stored rental passport is corrupt", exception); }
  }
  private List<String> strings(String value) {
    try { return mapper.readValue(value, new TypeReference<List<String>>() {}); }
    catch (JacksonException exception) { throw new IllegalStateException("Stored rental tags are corrupt", exception); }
  }
  private <T> T read(JsonNode node, Class<T> type) {
    try { return mapper.readerFor(type).readValue(node); }
    catch (JacksonException exception) { throw new IllegalStateException("Stored idempotent response is corrupt", exception); }
  }

  private record BalanceRow(UUID id, long version, UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind, long quantity) {}
  private record MaintenanceLeaseCommand<T>(UUID resourceId, T request) {}
  private record LogisticsLeaseCommand(UUID resourceId, LogisticsLeaseCommandRequest request) {}
  private record LogisticsEffectCommand(UUID resourceId, LogisticsFencedEffectRequest request) {}
  private record LogisticsHoldCommand(UUID resourceId, LogisticsEquipmentHoldCommandRequest request) {}
  public record CreateResult<T>(T response, boolean replayed) {}
}
