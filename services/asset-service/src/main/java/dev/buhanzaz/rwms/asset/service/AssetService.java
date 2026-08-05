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
import dev.buhanzaz.rwms.asset.mapper.EquipmentCatalogItemMapper;
import dev.buhanzaz.rwms.asset.mapper.OrderAssetResponseMapper;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.OperationLeaseRepository;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
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
  private final OrderUnitReservationRepository orderUnitReservations;
  private final JdbcTemplate jdbc;
  private final EquipmentAllocationPolicy equipmentAllocationPolicy;
  private final AssetEventStore events;
  private final AssetIdempotencyStore idempotency;
  private final WarehouseRegistryClient warehouses;
  private final AssetLogisticsResponseMapper logisticsMapper;
  private final EquipmentCatalogItemMapper equipmentMapper;
  private final OrderAssetResponseMapper orderAssetMapper;
  private final CabinCompositionService cabinComposition;
  private final MaintenanceFurnitureCustodyService maintenanceFurnitureCustody;
  private final ObjectMapper mapper;
  private final Duration holdTtl;
  private final Duration leaseTtl;

  public AssetService(
      RentalItemRepository rentalItems,
      EquipmentCatalogItemRepository equipment,
      OperationLeaseRepository operationLeases,
      OrderUnitReservationRepository orderUnitReservations,
      JdbcTemplate jdbc,
      EquipmentAllocationPolicy equipmentAllocationPolicy,
      AssetEventStore events,
      AssetIdempotencyStore idempotency,
      WarehouseRegistryClient warehouses,
      AssetLogisticsResponseMapper logisticsMapper,
      EquipmentCatalogItemMapper equipmentMapper,
      OrderAssetResponseMapper orderAssetMapper,
      CabinCompositionService cabinComposition,
      MaintenanceFurnitureCustodyService maintenanceFurnitureCustody,
      ObjectMapper mapper,
      @Value("${rwms.asset.equipment-hold.ttl:15m}") Duration holdTtl,
      @Value("${rwms.asset.operation-lease.ttl:15m}") Duration leaseTtl) {
    this.rentalItems = rentalItems;
    this.equipment = equipment;
    this.operationLeases = operationLeases;
    this.orderUnitReservations = orderUnitReservations;
    this.jdbc = jdbc;
    this.equipmentAllocationPolicy = equipmentAllocationPolicy;
    this.events = events;
    this.idempotency = idempotency;
    this.warehouses = warehouses;
    this.logisticsMapper = logisticsMapper;
    this.equipmentMapper = equipmentMapper;
    this.orderAssetMapper = orderAssetMapper;
    this.cabinComposition = cabinComposition;
    this.maintenanceFurnitureCustody = maintenanceFurnitureCustody;
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
    String needle = search == null ? "" : search.trim().toUpperCase(java.util.Locale.ROOT);
    java.util.Set<RentalItemStatus> exclusions =
        excludedStatuses == null ? java.util.Set.of() : java.util.Set.copyOf(excludedStatuses);
    PageRequest pageable =
        PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "number", "id"));
    var result =
        exclusions.isEmpty()
            ? rentalItems.findPublicPage(warehouseId, needle, pageable)
            : rentalItems.findPublicPageExcludingStatuses(
                warehouseId, exclusions, needle, pageable);
    List<RentalItem> values = result.getContent();
    Map<UUID, CabinCompositionService.CabinComposition> compositions =
        cabinComposition.compositionsFor(values);
    Map<UUID, List<EquipmentContentResponse>> contentByRentalItem =
        contentsFor(values.stream().map(RentalItem::getId).toList());
    Map<UUID, ActiveOrderReservationResponse> activeReservations =
        values.isEmpty()
            ? Map.of()
            : orderUnitReservations
                .findAllByRentalItemIdInAndState(
                    values.stream().map(RentalItem::getId).toList(),
                    OrderUnitReservationState.ACTIVE)
                .stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        OrderUnitReservation::getRentalItemId,
                        orderAssetMapper::toActiveOrderReservation));
    List<RentalItemResponse> content = values.stream()
        .map(
            item ->
                rentalResponse(
                    item,
                    compositions.get(item.getId()),
                    contentByRentalItem.getOrDefault(item.getId(), List.of()),
                    activeReservations.get(item.getId())))
        .toList();
    return new RentalItemPage(
        content,
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  @Transactional(readOnly = true)
  public RentalItemResponse rentalItem(UUID id) { return rentalResponse(requireRentalItem(id)); }

  @Transactional(readOnly = true)
  public List<CabinCatalogValueResponse> maintenanceCabinCharacteristics() {
    return cabinComposition.activeCharacteristics();
  }

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
    assertRentalItemAllowsLeaseEffects(item);
    assertLogisticsOrderReservation(subjectId, request, item);
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
    assertRentalItemAllowsLeaseEffects(item);
    RentalItemStatus target = LogisticsAssetTransitionPolicy.target(
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
        throw new AssetConflictException(
            "Transfer arrival must move the rental item to another warehouse");
      }
      warehouses.requireIncoming(request.destinationWarehouseId());
      assertRentalNumberAvailableInWarehouse(item, request.destinationWarehouseId());
    } else if (request.action() == LogisticsRentalItemAction.TRANSFER_DEPART
        || request.action() == LogisticsRentalItemAction.SHIPMENT_CONFIRM) {
      warehouses.requireOutgoing(sourceWarehouseId);
    } else if (request.action() == LogisticsRentalItemAction.RETURN_INTAKE) {
      warehouses.requireIncoming(sourceWarehouseId);
    }

    if (request.action() == LogisticsRentalItemAction.TRANSFER_DEPART) {
      item.departTransferUnderLease(
          RentalItemStatus.valueOf(request.transferAssetStatus().name()));
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
      relocateCabinContentsUnderLease(
          subjectId, saved.getId(), sourceWarehouseId, request.destinationWarehouseId());
    } else {
      reclassifyCabinBalances(saved, previous);
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
        rentalFact(saved),
        rentalSnapshot(saved));
    LogisticsRentalItemSnapshot safe =
        logisticsMapper.toLogisticsSnapshot(rentalResponse(saved));
    idempotency.store(subjectId, "logistics.rental-item.effect", key, hash, 200, safe);
    return new CreateResult<>(safe, false);
  }

  /**
   * Receives furniture that was physically found with a returned cabin but
   * was absent from its canonical contents.  This is an asset-owned stock
   * increase with append-only receipt evidence, not a fabricated transfer
   * from a non-existent source balance.
   */
  @Transactional
  public CreateResult<LogisticsReturnEquipmentReceiptResponse>
      receiveLogisticsReturnEquipment(
          UUID subjectId, UUID key, LogisticsReturnEquipmentReceiptRequest request) {
    validateReturnEquipmentReceiptRequest(request);
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "logistics.return-equipment-receipt.receive", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), LogisticsReturnEquipmentReceiptResponse.class), true);
    }

    warehouses.requireIncoming(request.warehouseId());
    List<LogisticsReturnEquipmentReceiptLine> lines = validatedReturnEquipmentReceiptLines(request);
    advisoryLocks(
        lines.stream()
            .map(
                line ->
                    balanceLockKey(
                        line.equipmentId(),
                        request.warehouseId(),
                        null,
                        BalanceLocationKind.STOCK))
            .toList());

    List<LogisticsReturnEquipmentReceiptLineResponse> received = new ArrayList<>();
    for (LogisticsReturnEquipmentReceiptLine line : lines) {
      EquipmentCatalogItem catalog = requireEquipment(line.equipmentId());
      if (!catalog.isActive() || catalog.getCategory() != EquipmentCategory.FURNITURE) {
        throw new AssetConflictException(
            "Returned additional equipment must reference active furniture");
      }

      Optional<ReturnEquipmentReceiptRow> existing =
          findReturnEquipmentReceipt(
              request.returnId(), request.returnLineId(), line.equipmentId());
      if (existing.isPresent()) {
        ReturnEquipmentReceiptRow receipt = existing.get();
        if (!receipt.warehouseId().equals(request.warehouseId())
            || receipt.quantity() != line.quantity()) {
          throw new AssetConflictException(
              "Return equipment receipt conflicts with existing immutable evidence");
        }
        BalanceRow target = balanceReadById(receipt.stockBalanceId());
        requireStockReceiptBalance(target, line.equipmentId(), request.warehouseId());
        received.add(returnEquipmentReceiptResponse(receipt, target));
        continue;
      }

      BalanceRow target =
          findBalance(
                  line.equipmentId(),
                  request.warehouseId(),
                  null,
                  BalanceLocationKind.STOCK)
              .orElseGet(
                  () ->
                      createEmptyBalance(
                          line.equipmentId(),
                          request.warehouseId(),
                          null,
                          BalanceLocationKind.STOCK));
      long streamVersion =
          events.lockCurrentVersion(AssetAggregateType.EQUIPMENT_BALANCE, target.id());
      if (streamVersion != target.version()) {
        throw new AssetConflictException("Equipment stock balance changed concurrently");
      }
      increment(target, line.quantity(), streamVersion);
      BalanceRow targetAfter = requireBalanceById(target.id());
      events.append(
          AssetAggregateType.EQUIPMENT_BALANCE,
          target.id(),
          streamVersion,
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          balanceFact(targetAfter),
          balanceSnapshot(targetAfter));

      UUID receiptId = UUID.randomUUID();
      jdbc.update(
          """
          insert into logistics_return_equipment_receipt(
            id,return_id,return_line_id,equipment_id,warehouse_id,stock_balance_id,quantity,received_at,actor_subject_id)
          values (?,?,?,?,?,?,?,clock_timestamp(),?)
          """,
          receiptId,
          request.returnId(),
          request.returnLineId(),
          line.equipmentId(),
          request.warehouseId(),
          targetAfter.id(),
          line.quantity(),
          subjectId);
      received.add(
          new LogisticsReturnEquipmentReceiptLineResponse(
              receiptId,
              line.equipmentId(),
              line.quantity(),
              targetAfter.id(),
              targetAfter.version(),
              targetAfter.quantity()));
    }

    LogisticsReturnEquipmentReceiptResponse response =
        new LogisticsReturnEquipmentReceiptResponse(
            request.returnId(),
            request.returnLineId(),
            request.warehouseId(),
            List.copyOf(received));
    idempotency.store(
        subjectId,
        "logistics.return-equipment-receipt.receive",
        key,
        hash,
        201,
        response);
    return new CreateResult<>(response, false);
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
    warehouses.requireOutgoing(request.warehouseId());
    requireEquipment(request.equipmentId());
    advisoryLock(balanceLockKey(
        request.equipmentId(),
        request.warehouseId(),
        null,
        BalanceLocationKind.STOCK));
    BalanceRow stock = requireBalance(
        request.equipmentId(), request.warehouseId(), null, BalanceLocationKind.STOCK);
    expireHolds(stock);
    assertVersion(stock.version(), request.expectedStockVersion());
    long available = Math.subtractExact(
        stock.quantity(), activeHeld(stock));
    if (available < request.quantity()) {
      throw new AssetConflictException("Active equipment holds reduce available stock");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime expiry = now().plus(holdTtl);
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
    expireHolds(current);
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
    expireHolds(current);
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

  /**
   * Acquires a task-deadline reservation on one concrete balance. Unlike the
   * older shipment hold API, this is deliberately able to reserve furniture
   * already inside a cabin as well as stock.
   */
  @Transactional
  public CreateResult<LogisticsEquipmentMovementReservationResponse>
      acquireLogisticsEquipmentMovementReservation(
          UUID subjectId,
          UUID key,
          AcquireLogisticsEquipmentMovementReservationRequest request) {
    if (request == null || request.purpose() == null) {
      throw new IllegalArgumentException("Equipment movement purpose is required");
    }
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "logistics.equipment-movement-reservation.acquire", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), LogisticsEquipmentMovementReservationResponse.class), true);
    }
    if (!isMovableReservationSource(request.sourceLocationKind())) {
      throw new IllegalArgumentException(
          "Equipment movement reservation source must be stock or a cabin balance");
    }
    warehouses.requireOutgoing(request.sourceWarehouseId());
    EquipmentCatalogItem catalog = requireEquipment(request.equipmentId());
    validateBalanceLocation(
        request.sourceWarehouseId(),
        request.sourceRentalItemId(),
        request.sourceLocationKind());
    if (request.sourceRentalItemId() != null) {
      advisoryLock(rentalItemLockKey(request.sourceRentalItemId()));
      if (request.purpose() == LogisticsEquipmentMovementPurpose.ALLOCATABLE_REBALANCE) {
        assertNoActiveLease(request.sourceRentalItemId());
      }
    }
    advisoryLock(balanceLockKey(
        request.equipmentId(),
        request.sourceWarehouseId(),
        request.sourceRentalItemId(),
        request.sourceLocationKind()));
    BalanceRow source = requireBalance(
        request.equipmentId(),
        request.sourceWarehouseId(),
        request.sourceRentalItemId(),
        request.sourceLocationKind());
    expireHolds(source);
    assertVersion(source.version(), request.expectedSourceBalanceVersion());
    Set<UUID> nestedHoldIds;
    LogisticsEquipmentMovementReservationOwnerType ownerType;
    if (request.purpose() == LogisticsEquipmentMovementPurpose.MAINTENANCE_DISPOSITION) {
      nestedHoldIds = Set.of(requireMaintenanceDispositionParentHold(request, source));
      ownerType =
          LogisticsEquipmentMovementReservationOwnerType.MAINTENANCE_DISPOSITION_MOVEMENT;
    } else {
      requireAllocatableSource(source);
      nestedHoldIds = Set.of();
      ownerType = LogisticsEquipmentMovementReservationOwnerType.LOGISTICS_EQUIPMENT_MOVEMENT;
    }
    OffsetDateTime acquiredAt = now();
    if (!request.reservedUntil().isAfter(acquiredAt)) {
      throw new IllegalArgumentException("reservedUntil must be in the future");
    }
    long available =
        Math.subtractExact(source.quantity(), activeHeldExcluding(source, nestedHoldIds));
    if (available < request.quantity()) {
      throw new AssetConflictException(
          "Equipment movement reservation exceeds the unreserved source balance");
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
      throw new AssetConflictException(
          "Equipment movement line already has an active reservation");
    }
    EquipmentHoldResponse reservation = holdResponse(reservationId);
    events.initialize(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        reservationId,
        0,
        AssetEventType.EQUIPMENT_HOLD_ACQUIRED,
        holdFact(reservation),
        holdSnapshot(reservation));
    LogisticsEquipmentMovementReservationResponse response =
        movementReservationResponse(reservation, catalog, source);
    idempotency.store(
        subjectId,
        "logistics.equipment-movement-reservation.acquire",
        key,
        hash,
        201,
        response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public CreateResult<LogisticsEquipmentMovementReservationResponse>
      releaseLogisticsEquipmentMovementReservation(
          UUID subjectId,
          UUID key,
          UUID reservationId,
          LogisticsEquipmentMovementReservationCommandRequest request) {
    EquipmentHoldResponse current = holdResponse(reservationId);
    assertMovementReservationOwner(current, request.movementId(), request.lineId());
    String hash = hash(new LogisticsEquipmentMovementReservationCommand(reservationId, request));
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "logistics.equipment-movement-reservation.release", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), LogisticsEquipmentMovementReservationResponse.class), true);
    }
    expireHolds(current);
    current = holdResponse(reservationId);
    assertMovementReservationOwner(current, request.movementId(), request.lineId());
    assertVersion(current.version(), request.expectedReservationVersion());
    EquipmentHoldResponse updated = current;
    if ("ACTIVE".equals(current.state())) {
      int changed = jdbc.update(
          """
          update equipment_allocation_hold
          set version=version+1,state='RELEASED',released_at=clock_timestamp(),updated_at=clock_timestamp()
          where id=? and version=? and state='ACTIVE'
          """,
          reservationId,
          request.expectedReservationVersion());
      if (changed != 1) {
        throw new AssetConflictException(
            "Equipment movement reservation changed concurrently during release");
      }
      updated = holdResponse(reservationId);
      events.append(
          AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
          reservationId,
          request.expectedReservationVersion(),
          AssetEventType.EQUIPMENT_HOLD_RELEASED,
          holdFact(updated),
          holdSnapshot(updated));
    }
    LogisticsEquipmentMovementReservationResponse response =
        movementReservationResponse(updated);
    idempotency.store(
        subjectId,
        "logistics.equipment-movement-reservation.release",
        key,
        hash,
        200,
        response);
    return new CreateResult<>(response, false);
  }

  /**
   * Converts a completed worker task into ledger entries and reservation state
   * changes atomically. No browser or logistics-side balance mutation is part
   * of this operation.
   */
  @Transactional
  public CreateResult<LogisticsEquipmentMovementExecutionResponse>
      executeLogisticsEquipmentMovementReservations(
          UUID subjectId,
          UUID key,
          ExecuteLogisticsEquipmentMovementReservationsRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "logistics.equipment-movement-reservation.execute", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), LogisticsEquipmentMovementExecutionResponse.class), true);
    }
    validateMovementExecutionRequest(request);

    List<MovementReservationCandidate> candidates = new ArrayList<>();
    for (ExecuteLogisticsEquipmentMovementReservationLine line : request.lines()) {
      EquipmentHoldResponse reservation = holdRead(line.reservationId());
      assertMovementReservationOwner(reservation, request.movementId(), line.lineId());
      if (reservation.sourceBalanceId() == null) {
        throw new AssetConflictException(
            "Equipment movement reservation has no concrete source balance");
      }
      BalanceRow source = balanceReadById(reservation.sourceBalanceId());
      if (!source.equipmentId().equals(reservation.equipmentId())
          || !source.warehouseId().equals(reservation.warehouseId())) {
        throw new AssetConflictException(
            "Equipment movement reservation source no longer matches its balance");
      }
      if (isMaintenanceDispositionReservation(reservation)) {
        warehouses.requireOutgoing(source.warehouseId());
      } else {
        warehouses.requireIncoming(line.targetWarehouseId());
      }
      validateBalanceLocation(
          line.targetWarehouseId(), line.targetRentalItemId(), line.targetLocationKind());
      candidates.add(new MovementReservationCandidate(reservation, source, line));
    }

    advisoryLocks(candidates.stream()
        .flatMap(candidate -> java.util.stream.Stream.of(
            candidate.source().rentalItemId(),
            candidate.line().targetRentalItemId()))
        .filter(java.util.Objects::nonNull)
        .map(AssetService::rentalItemLockKey)
        .toList());
    Set<UUID> leaseBlockedCabins = new java.util.TreeSet<>(Comparator.comparing(UUID::toString));
    for (MovementReservationCandidate candidate : candidates) {
      if (!isMaintenanceDispositionReservation(candidate.reservation())
          && candidate.source().rentalItemId() != null) {
        leaseBlockedCabins.add(candidate.source().rentalItemId());
      }
      if (candidate.line().targetRentalItemId() != null) {
        leaseBlockedCabins.add(candidate.line().targetRentalItemId());
      }
    }
    leaseBlockedCabins.forEach(this::assertNoActiveLease);
    for (MovementReservationCandidate candidate : candidates) {
      validateBalanceLocation(
          candidate.line().targetWarehouseId(),
          candidate.line().targetRentalItemId(),
          candidate.line().targetLocationKind());
    }
    advisoryLocks(candidates.stream()
        .flatMap(candidate -> java.util.stream.Stream.of(
            balanceLockKey(
                candidate.source().equipmentId(),
                candidate.source().warehouseId(),
                candidate.source().rentalItemId(),
                candidate.source().kind()),
            balanceLockKey(
                candidate.reservation().equipmentId(),
                candidate.line().targetWarehouseId(),
                candidate.line().targetRentalItemId(),
                candidate.line().targetLocationKind())))
        .toList());

    Map<UUID, BalanceRow> sources = new LinkedHashMap<>();
    for (MovementReservationCandidate candidate : candidates) {
      BalanceRow source = requireBalanceById(candidate.source().id());
      if (!source.equipmentId().equals(candidate.reservation().equipmentId())
          || !source.warehouseId().equals(candidate.reservation().warehouseId())
          || !java.util.Objects.equals(
              source.rentalItemId(), candidate.source().rentalItemId())
          || source.kind() != candidate.source().kind()) {
        throw new AssetConflictException(
            "Equipment movement reservation source changed concurrently");
      }
      sources.put(source.id(), source);
    }
    for (BalanceRow source : sources.values()) {
      expireHolds(source);
    }

    List<MovementReservationExecutionPlan> plans = new ArrayList<>();
    for (MovementReservationCandidate candidate : candidates) {
      EquipmentHoldResponse reservation = holdResponse(candidate.line().reservationId());
      assertMovementReservationOwner(reservation, request.movementId(), candidate.line().lineId());
      assertVersion(reservation.version(), candidate.line().expectedReservationVersion());
      if (!"ACTIVE".equals(reservation.state())
          || !reservation.expiresAt().isAfter(now())) {
        throw new AssetConflictException("Equipment movement reservation is not active");
      }
      BalanceRow source = requireBalanceById(reservation.sourceBalanceId());
      BalanceRow target = findBalance(
          reservation.equipmentId(),
          candidate.line().targetWarehouseId(),
          candidate.line().targetRentalItemId(),
          candidate.line().targetLocationKind()).orElseGet(
              () -> createEmptyBalance(
                  reservation.equipmentId(),
                  candidate.line().targetWarehouseId(),
                  candidate.line().targetRentalItemId(),
                  candidate.line().targetLocationKind()));
      if (source.id().equals(target.id())) {
        throw new AssetConflictException(
            "Equipment movement reservation source and target must differ");
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
            requireMaintenanceDispositionParentHold(
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
    events.lockStreams(plans.stream()
        .flatMap(plan -> java.util.stream.Stream.of(
            new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, plan.source().id()),
            new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, plan.target().id())))
        .toList());

    List<LogisticsEquipmentMovementExecutionLine> responseLines = new ArrayList<>();
    for (MovementReservationExecutionPlan plan : plans) {
      BalanceRow source = requireBalanceById(plan.source().id());
      BalanceRow target = requireBalanceById(plan.target().id());
      decrement(source, plan.reservation().quantity(), source.version());
      increment(target, plan.reservation().quantity(), target.version());
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
      String kind = movementKind(source.kind(), target.kind());
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
      MovementResponse movement = movementResponse(movementId);
      events.initialize(
          AssetAggregateType.EQUIPMENT_MOVEMENT,
          movementId,
          0,
          movementEventType(kind),
          movementFact(movement),
          Map.of("movementId", movementId.toString(), "version", 0));
      int changed = jdbc.update(
          """
          update equipment_allocation_hold
          set version=version+1,state='EXECUTED',executed_at=clock_timestamp(),updated_at=clock_timestamp()
          where id=? and version=? and state='ACTIVE' and expires_at>clock_timestamp()
          """,
          plan.reservation().id(),
          plan.reservation().version());
      if (changed != 1) {
        throw new AssetConflictException(
            "Equipment movement reservation changed concurrently during execution");
      }
      EquipmentHoldResponse executed = holdResponse(plan.reservation().id());
      events.append(
          AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
          executed.id(),
          plan.reservation().version(),
          AssetEventType.EQUIPMENT_HOLD_EXECUTED,
          holdFact(executed),
          holdSnapshot(executed));
      responseLines.add(new LogisticsEquipmentMovementExecutionLine(
          executed.id(),
          executed.version(),
          plan.line().lineId(),
          movement));
    }
    LogisticsEquipmentMovementExecutionResponse response =
        new LogisticsEquipmentMovementExecutionResponse(request.movementId(), List.copyOf(responseLines));
    idempotency.store(
        subjectId,
        "logistics.equipment-movement-reservation.execute",
        key,
        hash,
        201,
        response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public CreateResult<RentalItemResponse> createRentalItem(UUID subjectId, UUID key, CreateRentalItemRequest request) {
    warehouses.requireIncoming(request.warehouseId());
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "rental-item.create", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), RentalItemResponse.class), true);
    CabinCompositionService.CabinSelection selection = cabinComposition.requireSelection(
        request.rentalTypeId(),
        request.dimensionId(),
        request.finishingId(),
        request.characteristicIds());
    CabinCompositionService.CategorySelection category =
        cabinComposition.requireCategory(request.category());
    RentalItem candidate = RentalItem.create(
        request.warehouseId(),
        request.number(),
        selection.rentalTypeId(),
        selection.dimensionId(),
        selection.finishingId(),
        category.id(),
        category.name(),
        request.linoleum(),
        jsonObject(request.passport()),
        jsonArray(request.tags()));
    if (rentalItems.existsByWarehouseIdAndIdentityMatchKey(
        candidate.getWarehouseId(), candidate.getIdentityMatchKey())) {
      throw new AssetConflictException(
          "Rental item number identity is already used in this warehouse");
    }
    RentalItem persisted = rentalItems.saveAndFlush(candidate);
    cabinComposition.replaceRentalItemCharacteristics(
        persisted.getId(), selection.characteristicIds());
    events.initialize(AssetAggregateType.RENTAL_ITEM, persisted.getId(), persisted.getVersion(), AssetEventType.RENTAL_ITEM_CREATED,
        rentalFact(persisted), rentalSnapshot(persisted));
    RentalItemResponse response = rentalResponse(persisted);
    idempotency.store(subjectId, "rental-item.create", key, hash, 201, response);
    return new CreateResult<>(response, false);
  }

  /**
   * Creates one cabin from a reviewed HTML import. This is intentionally not
   * the public cabin-create path: legacy rows may have no type, dimensions or
   * category. The importer has already resolved every supplied catalog UUID;
   * normal interactive creation remains strict through {@link
   * #createRentalItem(UUID, UUID, CreateRentalItemRequest)}.
   */
  @Transactional
  public RentalItemResponse createRentalItemFromHtmlImport(
      UUID warehouseId,
      String number,
      RentalItemStatus status,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      String category,
      List<UUID> characteristicIds,
      Boolean linoleum,
      Map<String, Object> passport) {
    if (warehouseId == null || status == null || characteristicIds == null) {
      throw new IllegalArgumentException("HTML import cabin identity is incomplete");
    }
    if (!isHtmlImportManualStatus(status)) {
      throw new IllegalArgumentException(
          "HTML import can only create SALE, USED_SALE, FREE, WAREHOUSE, or OWN_NEEDS cabins");
    }
    if (finishingId == null) {
      throw new IllegalArgumentException("HTML import finishing is required");
    }
    warehouses.requireIncoming(warehouseId);
    List<UUID> characteristics = List.copyOf(characteristicIds);
    RentalItem candidate =
        RentalItem.createFromHtmlImport(
            warehouseId,
            number,
            status,
            rentalTypeId,
            dimensionId,
            finishingId,
            categoryId,
            category,
            linoleum,
            jsonObject(passport),
            jsonArray(List.of()));
    if (rentalItems.existsByWarehouseIdAndIdentityMatchKey(
        candidate.getWarehouseId(), candidate.getIdentityMatchKey())) {
      throw new AssetConflictException(
          "Rental item number identity is already used in this warehouse");
    }
    RentalItem persisted = rentalItems.saveAndFlush(candidate);
    cabinComposition.replaceRentalItemCharacteristics(persisted.getId(), characteristics);
    events.initialize(
        AssetAggregateType.RENTAL_ITEM,
        persisted.getId(),
        persisted.getVersion(),
        AssetEventType.RENTAL_ITEM_CREATED,
        rentalFact(persisted),
        rentalSnapshot(persisted));
    return rentalResponse(persisted);
  }

  @Transactional
  public RentalItemResponse updatePassport(UUID id, UpdatePassportRequest request) {
    assertNoActiveLease(id);
    RentalItem item = requireRentalItem(id);
    assertVersion(item.getVersion(), request.expectedVersion());
    CabinCompositionService.CabinSelection selection = cabinComposition.requireSelection(
        request.rentalTypeId(),
        request.dimensionId(),
        request.finishingId(),
        request.characteristicIds());
    CabinCompositionService.CategorySelection category =
        cabinComposition.requireCategory(request.category());
    boolean passportChanged = item.changePassport(
        selection.rentalTypeId(),
        selection.dimensionId(),
        selection.finishingId(),
        category.id(),
        category.name(),
        request.linoleum(),
        jsonObject(request.passport()),
        jsonArray(request.tags()));
    boolean characteristicsChanged = cabinComposition.replaceRentalItemCharacteristics(
        item.getId(), selection.characteristicIds());
    if (!passportChanged && !characteristicsChanged) return rentalResponse(item);
    if (characteristicsChanged && !passportChanged) item.touchActivity();
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
    String hash = hash(new ResourceCommand<>(id, request));
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
    return equipment.findAllByOrderByNameAscIdAsc().stream()
        .map(this::equipmentResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<EquipmentWarehouseResponse> equipmentAtWarehouse(UUID warehouseId) {
    List<EquipmentCatalogItem> catalog = equipment.findAllByOrderByNameAscIdAsc();
    Map<UUID, List<EquipmentAllocationPolicy.SourceAvailability>> sources =
        equipmentAllocationPolicy.sourcesAtWarehouse(warehouseId);
    Map<UUID, Long> reservations = activeOrderReservedAtWarehouse(warehouseId);
    Set<UUID> historicalEquipment = equipmentHistoryAtWarehouse(warehouseId);
    return catalog.stream()
        .filter(item -> item.isActive() || historicalEquipment.contains(item.getId()))
        .map(
            item ->
                new EquipmentWarehouseResponse(
                    equipmentResponse(item),
                    equipmentTotals(
                        item.getId(),
                        warehouseId,
                        sources.getOrDefault(item.getId(), List.of()),
                        reservations.getOrDefault(item.getId(), 0L))))
        .toList();
  }

  @Transactional(readOnly = true)
  public EquipmentResponse equipment(UUID id) { return equipmentResponse(requireEquipment(id)); }

  /**
   * Receives the initial furniture snapshot for one newly-created legacy HTML
   * row.  This is intentionally an append-only receipt, never a generic
   * "set balance" operation: retrying the same source row is a no-op and a
   * cabin that already has contents is rejected rather than rewritten.
   */
  @Transactional
  public void recordHtmlImportEquipmentReceipts(
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
    RentalItem rentalItem = requireRentalItem(rentalItemId);
    if (!isHtmlImportManualStatus(rentalItem.getStatus())) {
      throw new AssetConflictException("HTML import can only receive furniture into a manual-status cabin");
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

    assertNoActiveLease(rentalItemId);
    assertNoActiveOrderReservation(
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
      throw new AssetConflictException(
          "Presented rental-item cannot receive HTML import furniture");
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
      throw new AssetConflictException(
          "HTML import never rewrites equipment contents of a live cabin");
    }

    for (Map.Entry<UUID, Long> entry : entries) {
      EquipmentCatalogItem catalogItem = requireEquipment(entry.getKey());
      if (!catalogItem.isActive() || catalogItem.getCategory() != EquipmentCategory.FURNITURE) {
        throw new AssetConflictException("HTML import equipment target must be active furniture");
      }
      advisoryLock(
          balanceLockKey(
              entry.getKey(),
              rentalItem.getWarehouseId(),
              rentalItemId,
              BalanceLocationKind.CABIN_NON_RENTED));
    }

    for (Map.Entry<UUID, Long> entry : entries) {
      BalanceRow target =
          findBalance(
                  entry.getKey(),
                  rentalItem.getWarehouseId(),
                  rentalItemId,
                  BalanceLocationKind.CABIN_NON_RENTED)
              .orElseGet(
                  () ->
                      createEmptyBalance(
                          entry.getKey(),
                          rentalItem.getWarehouseId(),
                          rentalItemId,
                          BalanceLocationKind.CABIN_NON_RENTED));
      if (target.quantity() != 0) {
        throw new AssetConflictException(
            "HTML import equipment receipt target balance is not empty");
      }
      long streamVersion =
          events.lockCurrentVersion(AssetAggregateType.EQUIPMENT_BALANCE, target.id());
      if (streamVersion != target.version()) {
        throw new AssetConflictException("HTML import equipment balance changed concurrently");
      }
      increment(target, entry.getValue(), streamVersion);
      BalanceRow after = requireBalanceById(target.id());
      events.append(
          AssetAggregateType.EQUIPMENT_BALANCE,
          after.id(),
          streamVersion,
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          balanceFact(after),
          balanceSnapshot(after));
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

  @Transactional
  public CreateResult<EquipmentResponse> createEquipment(UUID subjectId, UUID key, CreateEquipmentRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "equipment-catalog.create", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), EquipmentResponse.class), true);
    EquipmentCatalogItem candidate =
        EquipmentCatalogItem.create(request.name(), request.category(), request.comment());
    if (equipment.existsByNormalizedName(candidate.getNormalizedName())) {
      throw new AssetConflictException("Equipment with this name already exists");
    }
    EquipmentCatalogItem saved;
    try {
      saved = equipment.saveAndFlush(candidate);
    } catch (DataIntegrityViolationException exception) {
      throw new AssetConflictException("Equipment with this name already exists");
    }
    events.initialize(AssetAggregateType.EQUIPMENT_CATALOG, saved.getId(), saved.getVersion(), AssetEventType.EQUIPMENT_CATALOG_CREATED,
        equipmentFact(saved), equipmentSnapshot(saved));
    EquipmentResponse response = equipmentResponse(saved);
    idempotency.store(subjectId, "equipment-catalog.create", key, hash, 201, response);
    return new CreateResult<>(response, false);
  }

  /**
   * Permanently binds one maintenance catalog node to one canonical furniture item. The mapping,
   * rather than the expiring generic idempotency cache, is the late-retry source of truth.
   */
  @Transactional
  public CreateResult<MaintenanceFurnitureEquipmentResponse> ensureMaintenanceFurnitureEquipment(
      UUID subjectId,
      UUID key,
      EnsureMaintenanceFurnitureEquipmentRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(
            subjectId, "maintenance.equipment-catalog.ensure", key, hash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), MaintenanceFurnitureEquipmentResponse.class), true);
    }
    advisoryLock(
        "maintenance-equipment:" + request.externalReferenceId());
    MaintenanceFurnitureEquipmentResponse existing =
        maintenanceFurnitureReference(request.externalReferenceId()).orElse(null);
    if (existing != null) {
      idempotency.store(
          subjectId,
          "maintenance.equipment-catalog.ensure",
          key,
          hash,
          200,
          existing);
      return new CreateResult<>(existing, true);
    }

    String normalizedName = EquipmentCatalogItem.normalizeName(request.equipmentName());
    EquipmentCatalogItem catalogItem =
        equipment.findAllByOrderByNameAscIdAsc().stream()
            .filter(item -> item.getNormalizedName().equals(normalizedName))
            .findFirst()
            .orElse(null);
    if (catalogItem == null) {
      catalogItem =
          equipment.saveAndFlush(
              EquipmentCatalogItem.create(
                  request.equipmentName(), EquipmentCategory.FURNITURE, null));
      events.initialize(
          AssetAggregateType.EQUIPMENT_CATALOG,
          catalogItem.getId(),
          catalogItem.getVersion(),
          AssetEventType.EQUIPMENT_CATALOG_CREATED,
          equipmentFact(catalogItem),
          equipmentSnapshot(catalogItem));
    } else if (catalogItem.getCategory() != EquipmentCategory.FURNITURE) {
      throw new AssetConflictException(
          "Maintenance furniture reference conflicts with a non-furniture catalog item");
    }
    try {
      jdbc.update(
          """
          insert into equipment_external_reference(
            source_system,external_reference_id,equipment_id,created_at)
          values ('MAINTENANCE_CATALOG_NODE',?,?,clock_timestamp())
          """,
          request.externalReferenceId(),
          catalogItem.getId());
    } catch (DataIntegrityViolationException exception) {
      MaintenanceFurnitureEquipmentResponse raced =
          maintenanceFurnitureReference(request.externalReferenceId()).orElse(null);
      if (raced == null) {
        throw new AssetConflictException(
            "Maintenance equipment reference changed concurrently");
      }
      return new CreateResult<>(raced, true);
    }
    MaintenanceFurnitureEquipmentResponse response =
        new MaintenanceFurnitureEquipmentResponse(
            request.externalReferenceId(), catalogItem.getId(), catalogItem.getName());
    idempotency.store(
        subjectId,
        "maintenance.equipment-catalog.ensure",
        key,
        hash,
        201,
        response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public EquipmentResponse updateEquipment(UUID id, UpdateEquipmentRequest request) {
    EquipmentCatalogItem item = requireEquipment(id);
    assertVersion(item.getVersion(), request.expectedVersion());
    if (equipment.existsByNormalizedNameAndIdNot(
        EquipmentCatalogItem.normalizeName(request.name()), id)) {
      throw new AssetConflictException("Equipment with this name already exists");
    }
    if (item.getCategory() != request.category() && hasEquipmentUsage(id)) {
      throw new AssetConflictException(
          "Equipment category is immutable after the item has balances or workflow references");
    }
    if (item.isActive() && !request.active() && hasLiveEquipmentUsage(id)) {
      throw new AssetConflictException(
          "Equipment with non-terminal quantity, reservations or holds cannot be deactivated");
    }
    if (!item.change(request.name(), request.category(), request.active(), request.comment())) {
      return equipmentResponse(item);
    }
    EquipmentCatalogItem saved;
    try {
      saved = equipment.saveAndFlush(item);
    } catch (DataIntegrityViolationException exception) {
      throw new AssetConflictException("Equipment with this name already exists");
    }
    events.append(AssetAggregateType.EQUIPMENT_CATALOG, id, request.expectedVersion(), AssetEventType.EQUIPMENT_CATALOG_CHANGED,
        equipmentFact(saved), equipmentSnapshot(saved));
    return equipmentResponse(saved);
  }

  @Transactional(readOnly = true)
  public EquipmentTotalsResponse equipmentTotals(UUID equipmentId, UUID warehouseId) {
    requireEquipment(equipmentId);
    List<EquipmentAllocationPolicy.SourceAvailability> availability =
        equipmentAllocationPolicy.sources(equipmentId, warehouseId);
    return equipmentTotals(
        equipmentId,
        warehouseId,
        availability,
        activeOrderReserved(equipmentId, warehouseId));
  }

  private EquipmentTotalsResponse equipmentTotals(
      UUID equipmentId,
      UUID warehouseId,
      List<EquipmentAllocationPolicy.SourceAvailability> availability,
      long reserved) {
    List<BalanceRow> rows =
        availability.stream()
            .map(
                source ->
                    new BalanceRow(
                        source.balanceId(),
                        source.version(),
                        source.equipmentId(),
                        source.warehouseId(),
                        source.rentalItemId(),
                        source.locationKind(),
                        source.quantity()))
            .toList();
    long stock = sum(rows, BalanceLocationKind.STOCK);
    long nonRented = sum(rows, BalanceLocationKind.CABIN_NON_RENTED);
    long rented = sum(rows, BalanceLocationKind.CABIN_RENTED);
    long writtenOff = sum(rows, BalanceLocationKind.WRITTEN_OFF);
    long lost = sum(rows, BalanceLocationKind.LOST);
    long activeHeld =
        availability.stream()
            .filter(EquipmentAllocationPolicy.SourceAvailability::allocatable)
            .mapToLong(EquipmentAllocationPolicy.SourceAvailability::activeHeldQuantity)
            .sum();
    List<EquipmentBalanceResponse> values = availability.stream()
        .map(AssetService::balanceResponse)
        .toList();
    long physicalAvailable =
        availability.stream()
            .mapToLong(EquipmentAllocationPolicy.SourceAvailability::availableQuantity)
            .sum();
    long available = Math.max(0, Math.subtractExact(physicalAvailable, reserved));
    long availableStock =
        availability.stream()
            .filter(source -> source.locationKind() == BalanceLocationKind.STOCK)
            .mapToLong(EquipmentAllocationPolicy.SourceAvailability::availableQuantity)
            .sum();
    return new EquipmentTotalsResponse(equipmentId, warehouseId, Math.addExact(Math.addExact(Math.addExact(stock, nonRented), Math.addExact(rented, writtenOff)), lost),
        stock, nonRented, rented, writtenOff, lost, activeHeld, reserved, available, availableStock, values);
  }

  @Transactional(readOnly = true)
  public List<EquipmentDispositionResponse> dispositions(UUID warehouseId) {
    return jdbc.query("""
        select m.id,m.version,m.equipment_id,m.source_balance_id,m.target_balance_id,m.quantity,m.movement_kind,m.occurred_at,
          c.name
        from equipment_movement m
        join equipment_catalog_item c on c.id=m.equipment_id
        join equipment_balance target on target.id=m.target_balance_id
        where target.warehouse_id=? and m.movement_kind in ('WRITE_OFF','LOSS')
        order by m.occurred_at desc,m.id desc
        """, (rs, row) -> new EquipmentDispositionResponse(
            new MovementResponse(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getObject("equipment_id", UUID.class),
                rs.getObject("source_balance_id", UUID.class), rs.getObject("target_balance_id", UUID.class), rs.getLong("quantity"),
                rs.getString("movement_kind"), rs.getObject("occurred_at", OffsetDateTime.class)),
            rs.getString("name")), warehouseId);
  }

  @Transactional
  public CreateResult<MovementResponse> transfer(UUID subjectId, UUID key, TransferEquipmentRequest request) {
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

  /**
   * Joins an order command to the canonical rental-item/lease lock order before the order service
   * obtains a row lock. The caller must already own a transaction.
   */
  void lockOrderRentalItemForOrder(UUID rentalItemId) {
    assertNoActiveLease(rentalItemId);
  }

  /**
   * Applies the asset-owned order booking status after the caller has joined
   * the canonical rental-item/lease lock order.
   */
  RentalItemResponse bookOrderRentalItem(UUID rentalItemId) {
    RentalItem item = rentalItems
        .findByIdForUpdate(rentalItemId)
        .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    if (item.getStatus() == RentalItemStatus.BOOKED) {
      return rentalResponse(item);
    }
    if (item.getStatus() != RentalItemStatus.FREE) {
      throw new AssetConflictException("Only a free rental item can be booked for an order");
    }
    return changeOrderBookingStatus(item, RentalItemStatus.BOOKED);
  }

  /** Releases only the order-owned booking state; later lifecycle states stay fenced. */
  RentalItemResponse releaseOrderBooking(UUID rentalItemId) {
    RentalItem item = rentalItems
        .findByIdForUpdate(rentalItemId)
        .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    if (item.getStatus() != RentalItemStatus.BOOKED) {
      return rentalResponse(item);
    }
    return changeOrderBookingStatus(item, RentalItemStatus.FREE);
  }

  private CreateResult<MovementResponse> move(
      UUID subjectId,
      UUID key,
      String idempotencyScope,
      TransferEquipmentRequest request,
      AssetEventType eventType,
      boolean resolveTargetVersionFromServer) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, idempotencyScope, key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), MovementResponse.class), true);
    if (isTerminalEquipmentLocation(request.sourceLocationKind())) {
      throw new AssetConflictException("Written-off or lost equipment cannot be moved again");
    }
    warehouses.requireOutgoing(request.sourceWarehouseId());
    if (!isTerminalEquipmentLocation(request.targetLocationKind())) {
      warehouses.requireIncoming(request.targetWarehouseId());
    }
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
    long targetExpectedVersion = resolveTargetVersionFromServer
        ? target.version()
        : request.targetExpectedVersion();
    assertVersion(target.version(), targetExpectedVersion);
    if (source.quantity() < request.quantity()) throw new AssetConflictException("Equipment balance cannot become negative");
    if (Math.subtractExact(source.quantity(), request.quantity()) < activeHeld(source)) {
      throw new AssetConflictException(
          "Equipment transfer would consume quantities reserved by an active hold");
    }
    events.lockStreams(List.of(new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, source.id()), new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, target.id())));
    decrement(source, request.quantity(), request.sourceExpectedVersion());
    increment(target, request.quantity(), targetExpectedVersion);
    BalanceRow sourceAfter = requireBalanceById(source.id());
    BalanceRow targetAfter = requireBalanceById(target.id());
    events.append(AssetAggregateType.EQUIPMENT_BALANCE, source.id(), request.sourceExpectedVersion(), AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        balanceFact(sourceAfter), balanceSnapshot(sourceAfter));
    events.append(AssetAggregateType.EQUIPMENT_BALANCE, target.id(), targetExpectedVersion, AssetEventType.EQUIPMENT_BALANCE_CHANGED,
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
    return move(subjectId, key, "equipment.disposition", move, eventType, true);
  }

  @Transactional
  public CreateResult<EquipmentHoldResponse> acquireHold(UUID subjectId, UUID key, AcquireEquipmentHoldRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "equipment-hold.acquire", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), EquipmentHoldResponse.class), true);
    warehouses.requireOutgoing(request.warehouseId());
    requireEquipment(request.equipmentId());
    advisoryLock(balanceLockKey(request.equipmentId(), request.warehouseId(), null, BalanceLocationKind.STOCK));
    BalanceRow stock = requireBalance(request.equipmentId(), request.warehouseId(), null, BalanceLocationKind.STOCK);
    expireHolds(stock);
    assertVersion(stock.version(), request.expectedStockVersion());
    long available = Math.subtractExact(stock.quantity(), activeHeld(stock));
    if (available < request.quantity()) throw new AssetConflictException("Active equipment holds reduce available stock");
    UUID id = UUID.randomUUID();
    OffsetDateTime expiry = now().plus(holdTtl);
    jdbc.update("""
        insert into equipment_allocation_hold(id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,quantity,state,idempotency_key,expires_at,created_at,updated_at)
        values (?,0,?,?,?,?,?,?,'ACTIVE',?,?,clock_timestamp(),clock_timestamp())
        """, id, request.equipmentId(), request.warehouseId(), stock.id(), canonicalOwnerType(request.ownerType()), canonicalOwnerId(request.ownerId()),
        request.quantity(), key, expiry);
    EquipmentHoldResponse response = holdResponse(id);
    events.initialize(AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD, id, 0, AssetEventType.EQUIPMENT_HOLD_ACQUIRED, holdFact(response), holdSnapshot(response));
    idempotency.store(subjectId, "equipment-hold.acquire", key, hash, 201, response);
    return new CreateResult<>(response, false);
  }

  @Transactional
  public CreateResult<EquipmentHoldResponse> renewHold(
      UUID subjectId, UUID key, UUID id, RenewEquipmentHoldRequest request) {
    String hash = hash(new ResourceCommand<>(id, request));
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
    String hash = hash(new ResourceCommand<>(id, request));
    Optional<JsonNode> replay = idempotency.replay(subjectId, "equipment-hold.commit", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), EquipmentHoldResponse.class), true);
    EquipmentHoldResponse current = holdResponse(id);
    expireHolds(current);
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
    String hash = hash(new ResourceCommand<>(id, request));
    Optional<JsonNode> replay = idempotency.replay(subjectId, "equipment-hold.release", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), EquipmentHoldResponse.class), true);
    EquipmentHoldResponse current = holdResponse(id);
    expireHolds(current);
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
    assertNoActiveOrderReservation(
        request.rentalItemId(), "Reserved order unit cannot acquire an operation lease");
    RentalItem item = requireRentalItem(request.rentalItemId());
    assertVersion(item.getVersion(), request.expectedRentalItemVersion());
    assertRentalItemAllowsLeaseEffects(item);
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
    String hash = hash(new ResourceCommand<>(id, request));
    Optional<JsonNode> replay = idempotency.replay(subjectId, "operation-lease.renew", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), OperationLeaseResponse.class), true);
    OperationLeaseResponse updated = renewLeaseState(id, request.expectedVersion(), request.fencingToken());
    idempotency.store(subjectId, "operation-lease.renew", key, hash, 200, updated);
    return new CreateResult<>(updated, false);
  }

  @Transactional
  public CreateResult<OperationLeaseResponse> releaseLease(
      UUID subjectId, UUID key, UUID id, ReleaseOperationLeaseRequest request) {
    String hash = hash(new ResourceCommand<>(id, request));
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
    assertNoActiveOrderReservation(
        request.rentalItemId(), "Reserved order unit cannot acquire an operation lease");
    RentalItem item = requireRentalItem(request.rentalItemId());
    assertVersion(item.getVersion(), request.expectedRentalItemVersion());
    assertRentalItemAllowsLeaseEffects(item);
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
    if (current.getStatus() == RentalItemStatus.WRITTEN_OFF
        && request.action() == MaintenanceStatusAction.WRITE_OFF) {
      RentalItemResponse response = rentalResponse(current);
      idempotency.store(
          subjectId, "maintenance.rental-item.fenced-status", key, hash, 200, response);
      return new CreateResult<>(response, false);
    }
    assertRentalItemAllowsLeaseEffects(current);
    List<MaintenanceFurniturePendingReturn> furniturePendingReturns =
        maintenanceFurniturePendingReturns(request);
    assertVersion(current.getVersion(), request.expectedVersion());
    RentalItemStatus target = MaintenanceAssetTransitionPolicy.target(
        current.getStatus(),
        request.action(),
        request.ownerType(),
        request.ownerId(),
        request.linkedReturnEstimateId());
    maintenanceFurnitureCustody.selectFromCabin(
        subjectId,
        key,
        hash,
        current,
        request.ownerType(),
        request.ownerId(),
        furniturePendingReturns);
    RentalItemResponse updated = changeStatusLocked(id, request.expectedVersion(), target, true);
    idempotency.store(
        subjectId, "maintenance.rental-item.fenced-status", key, hash, 200, updated);
    return new CreateResult<>(updated, false);
  }

  /**
   * Applies an accepted maintenance material characteristic without replacing
   * any existing cabin characteristic. The rental-item aggregate lock,
   * relation uniqueness and subject-bound idempotency make repeated acceptance
   * safe.
   */
  @Transactional
  public CreateResult<MaintenanceCharacteristicApplicationResponse>
      applyMaintenanceCharacteristic(
          UUID subjectId,
          UUID key,
          UUID rentalItemId,
          UUID characteristicId) {
    MaintenanceCharacteristicCommand command =
        new MaintenanceCharacteristicCommand(rentalItemId, characteristicId);
    String requestHash = hash(command);
    Optional<JsonNode> replay =
        idempotency.replay(
            subjectId,
            "maintenance.rental-item.characteristic.apply",
            key,
            requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(
          read(replay.get(), MaintenanceCharacteristicApplicationResponse.class),
          true);
    }

    advisoryLock(rentalItemLockKey(rentalItemId));
    RentalItem item =
        rentalItems
            .findByIdForUpdate(rentalItemId)
            .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    assertRentalItemAllowsLeaseEffects(item);
    long expectedVersion = item.getVersion();
    boolean added =
        cabinComposition.appendRentalItemCharacteristic(
            rentalItemId, characteristicId);
    RentalItem current = item;
    if (added) {
      item.touchActivity();
      current = rentalItems.saveAndFlush(item);
      events.append(
          AssetAggregateType.RENTAL_ITEM,
          current.getId(),
          expectedVersion,
          AssetEventType.RENTAL_ITEM_PASSPORT_CHANGED,
          rentalFact(current),
          rentalSnapshot(current));
    }

    MaintenanceCharacteristicApplicationResponse response =
        new MaintenanceCharacteristicApplicationResponse(
            rentalItemId,
            characteristicId,
            added,
            current.getVersion());
    idempotency.store(
        subjectId,
        "maintenance.rental-item.characteristic.apply",
        key,
        requestHash,
        200,
        response);
    return new CreateResult<>(response, false);
  }

  private List<MaintenanceFurniturePendingReturn> maintenanceFurniturePendingReturns(
      MaintenanceFencedStatusRequest request) {
    if (request.furniturePendingReturns() == null) {
      throw new IllegalArgumentException("furniturePendingReturns is required");
    }
    Map<UUID, MaintenanceFurniturePendingReturn> unique = new LinkedHashMap<>();
    for (MaintenanceFurniturePendingReturn line : request.furniturePendingReturns()) {
      if (line == null || line.equipmentId() == null) {
        throw new IllegalArgumentException("Furniture pending-return equipmentId is required");
      }
      if (line.expectedSourceBalanceVersion() == null
          || line.expectedSourceBalanceVersion() < 0) {
        throw new IllegalArgumentException(
            "Furniture pending-return expectedSourceBalanceVersion is required");
      }
      if (line.quantity() < 1) {
        throw new IllegalArgumentException("Furniture pending-return quantity must be positive");
      }
      if (unique.putIfAbsent(line.equipmentId(), line) != null) {
        throw new IllegalArgumentException(
            "Furniture pending returns must contain unique equipment items");
      }
    }
    if (!unique.isEmpty()
        && request.action() != MaintenanceStatusAction.QUEUE_FOR_REPAIR
        && request.action() != MaintenanceStatusAction.QUEUE_FOR_CAPITAL_REPAIR) {
      throw new AssetConflictException(
          "Furniture pending returns require a maintenance-owned queue action");
    }
    return unique.values().stream()
        .sorted(Comparator.comparing(line -> line.equipmentId().toString()))
        .toList();
  }

  private OperationLeaseResponse renewLeaseState(UUID id, Long expectedVersion, long fencingToken) {
    OperationLease current = requireLeaseForUpdate(id);
    assertVersion(current.getVersion(), expectedVersion);
    OffsetDateTime renewedAt = now();
    assertFencing(current, fencingToken, renewedAt);
    assertRentalItemAllowsLeaseEffects(requireRentalItem(current.getRentalItemId()));
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
    return jdbc.query("select id,version,classifier_type,parent_id,name,active,sort_order from asset_classifier where (? is null or classifier_type=?) order by classifier_type,sort_order nulls last,name,id",
        (rs, row) -> new ClassifierResponse(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("classifier_type"),
            rs.getObject("parent_id", UUID.class), rs.getString("name"), rs.getBoolean("active"), rs.getObject("sort_order", Integer.class)), type, type);
  }

  @Transactional
  public CreateResult<ClassifierResponse> createClassifier(UUID subjectId, UUID key, CreateClassifierRequest request) {
    String hash = hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "classifier.create", key, hash);
    if (replay.isPresent()) return new CreateResult<>(read(replay.get(), ClassifierResponse.class), true);
    UUID id = UUID.randomUUID();
    jdbc.update("insert into asset_classifier(id,version,classifier_type,parent_id,name,active,sort_order,created_at,updated_at) values (?,0,?,?,?,?,?,clock_timestamp(),clock_timestamp())",
        id, classifierType(request.type()), request.parentId(), required(request.name(), 255), request.active(), request.sortOrder());
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
        update asset_classifier set classifier_type=?,parent_id=?,name=?,active=?,sort_order=?,version=version+1,updated_at=clock_timestamp()
        where id=? and version=?
        """, classifierType(request.type()), request.parentId(), required(request.name(), 255), request.active(), request.sortOrder(), id, request.expectedVersion());
    if (changed != 1) throw new AssetConflictException("Classifier changed concurrently");
    ClassifierResponse updated = classifier(id);
    events.append(AssetAggregateType.CLASSIFIER, id, request.expectedVersion(), AssetEventType.CLASSIFIER_CHANGED,
        classifierFact(updated), classifierSnapshot(updated));
    return updated;
  }

  private RentalItemResponse changeStatus(UUID id, Long expectedVersion, RentalItemStatus status, boolean fenced) {
    advisoryLock(rentalItemLockKey(id));
    if (!fenced) {
      assertNoActiveLease(id);
      if (status != null) {
        assertNoActiveOrderReservation(
            id, "Reserved order unit cannot change to an incompatible status");
      }
    }
    return changeStatusLocked(id, expectedVersion, status, fenced);
  }

  private RentalItemResponse changeStatusLocked(
      UUID id, Long expectedVersion, RentalItemStatus status, boolean fenced) {
    RentalItem item = requireRentalItem(id);
    if (fenced
        && item.getStatus().isTerminalDispositionStatus()
        && status == item.getStatus()) {
      return rentalResponse(item);
    }
    assertVersion(item.getVersion(), expectedVersion);
    if (fenced) assertRentalItemAllowsLeaseEffects(item);
    RentalItemStatus previous = item.getStatus();
    boolean changed = fenced ? item.changeStatusUnderLease(status) : item.changeStatus(status);
    if (!changed) return rentalResponse(item);
    RentalItem saved = rentalItems.saveAndFlush(item);
    events.append(AssetAggregateType.RENTAL_ITEM, saved.getId(), expectedVersion, AssetEventType.RENTAL_ITEM_STATUS_CHANGED,
        rentalFact(saved), rentalSnapshot(saved));
    reclassifyCabinBalances(saved, previous);
    return rentalResponse(saved);
  }

  private RentalItemResponse changeOrderBookingStatus(
      RentalItem item, RentalItemStatus targetStatus) {
    long expectedVersion = item.getVersion();
    RentalItemStatus previous = item.getStatus();
    item.changeStatusUnderLease(targetStatus);
    RentalItem saved = rentalItems.saveAndFlush(item);
    events.append(
        AssetAggregateType.RENTAL_ITEM,
        saved.getId(),
        expectedVersion,
        AssetEventType.RENTAL_ITEM_STATUS_CHANGED,
        rentalFact(saved),
        rentalSnapshot(saved));
    reclassifyCabinBalances(saved, previous);
    return rentalResponse(saved);
  }

  private RentalItem requireRentalItem(UUID id) { return rentalItems.findById(id).orElseThrow(() -> new AssetNotFoundException("Rental item was not found")); }
  private void assertRentalNumberAvailableInWarehouse(
      RentalItem item, UUID warehouseId) {
    if (rentalItems.existsByWarehouseIdAndIdentityMatchKeyAndIdNot(
        warehouseId, item.getIdentityMatchKey(), item.getId())) {
      throw new AssetConflictException(
          "Rental item number identity is already used in the destination warehouse");
    }
  }
  private EquipmentCatalogItem requireEquipment(UUID id) { return equipment.findById(id).orElseThrow(() -> new AssetNotFoundException("Equipment catalog item was not found")); }
  private void assertVersion(long actual, Long expected) {
    if (expected == null || expected < 0) throw new IllegalArgumentException("expectedVersion is required");
    if (actual != expected) throw new AssetConflictException("Asset data changed concurrently");
  }

  private static boolean isTerminalEquipmentLocation(BalanceLocationKind location) {
    return location == BalanceLocationKind.WRITTEN_OFF || location == BalanceLocationKind.LOST;
  }

  private static void assertRentalItemAllowsLeaseEffects(RentalItem item) {
    if (item.getStatus().isTerminalDispositionStatus()) {
      throw new AssetConflictException(
          item.getStatus() == RentalItemStatus.LOST
              ? "Lost rental item cannot receive lease or maintenance effects"
              : "Written-off rental item cannot receive lease or maintenance effects");
    }
  }

  private static Duration requireTtl(Duration ttl, String name) {
    if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(1)) > 0) throw new IllegalArgumentException(name + " ttl must be between 1 ms and 1 h");
    return ttl;
  }
  private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }

  private static boolean matchesRentalSearch(RentalItemResponse item, String needle) {
    if (needle == null || needle.isEmpty()) return true;
    return contains(item.number(), needle)
        || contains(item.rentalType(), needle)
        || contains(item.dimensions(), needle)
        || contains(item.finishing(), needle)
        || contains(item.category(), needle)
        || item.characteristics().stream()
            .map(CabinCatalogValueResponse::name)
            .anyMatch(value -> contains(value, needle));
  }

  private static boolean contains(String value, String needle) {
    return value != null && value.toUpperCase(java.util.Locale.ROOT).contains(needle);
  }

  private RentalItemResponse rentalResponse(RentalItem item) {
    return rentalResponse(
        item,
        cabinComposition.compositionsFor(List.of(item)).get(item.getId()));
  }

  private RentalItemResponse rentalResponse(
      RentalItem item, CabinCompositionService.CabinComposition composition) {
    ActiveOrderReservationResponse activeOrderReservation = orderUnitReservations
        .findByRentalItemIdAndState(item.getId(), OrderUnitReservationState.ACTIVE)
        .map(orderAssetMapper::toActiveOrderReservation)
        .orElse(null);
    return rentalResponse(item, composition, contents(item.getId()), activeOrderReservation);
  }

  private RentalItemResponse rentalResponse(
      RentalItem item,
      CabinCompositionService.CabinComposition composition,
      List<EquipmentContentResponse> equipmentContents,
      ActiveOrderReservationResponse activeOrderReservation) {
    return new RentalItemResponse(
        item.getId(),
        item.getVersion(),
        item.getWarehouseId(),
        item.getNumber(),
        item.getStatus(),
        item.getRentalTypeId(),
        composition == null || composition.rentalType() == null
            ? null
            : composition.rentalType().name(),
        item.getDimensionId(),
        composition == null || composition.dimensions() == null
            ? null
            : composition.dimensions().name(),
        item.getFinishingId(),
        composition == null || composition.finishing() == null
            ? null
            : composition.finishing().name(),
        item.getCategory(),
        composition == null ? List.of() : composition.characteristics(),
        item.getLinoleum(),
        item.getGeneralComment(),
        map(item.getPassportJson()),
        strings(item.getTagsJson()),
        equipmentContents,
        activeOrderReservation,
        item.getCreatedAt(),
        item.getUpdatedAt());
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
        select b.equipment_id,c.name,b.quantity,b.location_kind from equipment_balance b
        join equipment_catalog_item c on c.id=b.equipment_id
        where b.rental_item_id=? and b.location_kind in ('CABIN_NON_RENTED','CABIN_RENTED') and b.quantity>0 order by c.name,c.id
        """, (rs, row) -> new EquipmentContentResponse(
            rs.getObject("equipment_id", UUID.class),
            rs.getString("name"),
            rs.getLong("quantity"),
        BalanceLocationKind.valueOf(rs.getString("location_kind"))), rentalItemId);
  }

  private Map<UUID, List<EquipmentContentResponse>> contentsFor(Collection<UUID> rentalItemIds) {
    if (rentalItemIds == null || rentalItemIds.isEmpty()) return Map.of();
    List<UUID> ids = rentalItemIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) return Map.of();
    String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    Map<UUID, List<EquipmentContentResponse>> result = new LinkedHashMap<>();
    jdbc.query(
        """
        select b.rental_item_id,b.equipment_id,c.name,b.quantity,b.location_kind
        from equipment_balance b
        join equipment_catalog_item c on c.id=b.equipment_id
        where b.rental_item_id in (%s)
          and b.location_kind in ('CABIN_NON_RENTED','CABIN_RENTED')
          and b.quantity>0
        order by b.rental_item_id,c.name,c.id
        """.formatted(placeholders),
        rs -> {
          UUID rentalItemId = rs.getObject("rental_item_id", UUID.class);
          result.computeIfAbsent(rentalItemId, ignored -> new ArrayList<>())
              .add(
                  new EquipmentContentResponse(
                      rs.getObject("equipment_id", UUID.class),
                      rs.getString("name"),
                      rs.getLong("quantity"),
                      BalanceLocationKind.valueOf(rs.getString("location_kind"))));
        },
        ids.toArray());
    return result.entrySet().stream()
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
  }

  private List<BalanceRow> balances(UUID equipmentId, UUID warehouseId) {
    return jdbc.query("select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity from equipment_balance where equipment_id=? and warehouse_id=? order by location_kind,rental_item_id",
        (rs, row) -> balanceRow(rs), equipmentId, warehouseId);
  }
  private Optional<BalanceRow> findBalance(UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    return jdbc.query("select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity from equipment_balance where equipment_id=? and warehouse_id=? and rental_item_id is not distinct from ? and location_kind=? for update",
        (rs, row) -> balanceRow(rs), equipmentId, warehouseId, rentalItemId, kind.name()).stream().findFirst();
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
      BalanceRow target = balanceReadById(receipt.targetBalanceId());
      if (!target.equipmentId().equals(entry.getKey())
          || !target.warehouseId().equals(warehouseId)
          || !rentalItemId.equals(target.rentalItemId())
          || target.kind() != BalanceLocationKind.CABIN_NON_RENTED) {
        throw new AssetConflictException("HTML import equipment receipt target is malformed");
      }
    }
  }

  private Optional<ReturnEquipmentReceiptRow> findReturnEquipmentReceipt(
      UUID returnId, UUID returnLineId, UUID equipmentId) {
    return jdbc
        .query(
            """
            select id,return_id,return_line_id,equipment_id,warehouse_id,stock_balance_id,quantity
            from logistics_return_equipment_receipt
            where return_id=? and return_line_id=? and equipment_id=?
            """,
            (rs, row) ->
                new ReturnEquipmentReceiptRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("return_id", UUID.class),
                    rs.getObject("return_line_id", UUID.class),
                    rs.getObject("equipment_id", UUID.class),
                    rs.getObject("warehouse_id", UUID.class),
                    rs.getObject("stock_balance_id", UUID.class),
                    rs.getLong("quantity")),
            returnId,
            returnLineId,
            equipmentId)
        .stream()
        .findFirst();
  }

  private static void requireStockReceiptBalance(
      BalanceRow balance, UUID equipmentId, UUID warehouseId) {
    if (!balance.equipmentId().equals(equipmentId)
        || !balance.warehouseId().equals(warehouseId)
        || balance.rentalItemId() != null
        || balance.kind() != BalanceLocationKind.STOCK) {
      throw new AssetConflictException("Return equipment receipt stock balance is malformed");
    }
  }

  private static LogisticsReturnEquipmentReceiptLineResponse returnEquipmentReceiptResponse(
      ReturnEquipmentReceiptRow receipt, BalanceRow balance) {
    return new LogisticsReturnEquipmentReceiptLineResponse(
        receipt.id(),
        receipt.equipmentId(),
        receipt.quantity(),
        balance.id(),
        balance.version(),
        balance.quantity());
  }

  private static void validateReturnEquipmentReceiptRequest(
      LogisticsReturnEquipmentReceiptRequest request) {
    if (request == null
        || request.returnId() == null
        || request.returnLineId() == null
        || request.warehouseId() == null
        || request.lines() == null) {
      throw new IllegalArgumentException("Return equipment receipt is required");
    }
  }

  private static List<LogisticsReturnEquipmentReceiptLine> validatedReturnEquipmentReceiptLines(
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

  private BalanceRow requireBalance(UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    return findBalance(equipmentId, warehouseId, rentalItemId, kind).orElseThrow(() -> new AssetNotFoundException("Equipment balance was not found"));
  }
  private BalanceRow requireBalanceById(UUID id) {
    return jdbc.query("select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity from equipment_balance where id=? for update",
        (rs, row) -> balanceRow(rs), id).stream().findFirst().orElseThrow(() -> new AssetNotFoundException("Equipment balance was not found"));
  }
  private BalanceRow balanceReadById(UUID id) {
    return jdbc.query("select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity from equipment_balance where id=?",
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
  /**
   * A hold belongs to a physical source balance. Legacy rows without the new
   * source reference remain stock holds so an upgrade cannot make old shipment
   * reservations spendable.
   */
  private long activeHeld(BalanceRow source) {
    return activeHeldExcluding(source, Set.of());
  }

  private long activeOrderReserved(UUID equipmentId, UUID warehouseId) {
    Long result = jdbc.queryForObject(
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

  private boolean hasEquipmentUsage(UUID equipmentId) {
    Boolean result =
        jdbc.queryForObject(
            """
            select exists(
              select 1 from equipment_balance where equipment_id=?
              union all
              select 1 from equipment_movement where equipment_id=?
              union all
              select 1 from equipment_allocation_hold where equipment_id=?
              union all
              select 1 from order_equipment_reservation where equipment_id=?
              union all
              select 1 from equipment_external_reference where equipment_id=?
            )
            """,
            Boolean.class,
            equipmentId,
            equipmentId,
            equipmentId,
            equipmentId,
            equipmentId);
    return Boolean.TRUE.equals(result);
  }

  private boolean hasLiveEquipmentUsage(UUID equipmentId) {
    Boolean result =
        jdbc.queryForObject(
            """
            select exists(
              select 1
              from equipment_balance
              where equipment_id=?
                and quantity>0
                and location_kind not in ('WRITTEN_OFF','LOST')
              union all
              select 1
              from equipment_allocation_hold
              where equipment_id=?
                and (state='COMMITTED' or (state='ACTIVE' and expires_at>clock_timestamp()))
              union all
              select 1
              from order_equipment_reservation
              where equipment_id=? and state='ACTIVE'
              union all
              select 1
              from equipment_external_reference
              where equipment_id=?
            )
            """,
            Boolean.class,
            equipmentId,
            equipmentId,
            equipmentId,
            equipmentId);
    return Boolean.TRUE.equals(result);
  }

  private Optional<MaintenanceFurnitureEquipmentResponse> maintenanceFurnitureReference(
      UUID externalReferenceId) {
    return jdbc
        .query(
            """
            select reference.external_reference_id,item.id,item.name,item.category
            from equipment_external_reference reference
            join equipment_catalog_item item on item.id=reference.equipment_id
            where reference.source_system='MAINTENANCE_CATALOG_NODE'
              and reference.external_reference_id=?
            """,
            (rs, row) -> {
              if (EquipmentCategory.valueOf(rs.getString("category"))
                  != EquipmentCategory.FURNITURE) {
                throw new AssetConflictException(
                    "Stored maintenance equipment reference is not furniture");
              }
              return new MaintenanceFurnitureEquipmentResponse(
                  rs.getObject("external_reference_id", UUID.class),
                  rs.getObject("id", UUID.class),
                  rs.getString("name"));
            },
            externalReferenceId)
        .stream()
        .findFirst();
  }
  private long activeHeldExcluding(BalanceRow source, Set<UUID> excludedReservationIds) {
    List<HoldQuantity> holds = jdbc.query("""
        select id,quantity from equipment_allocation_hold
        where (
          source_balance_id=?
          or (
            source_balance_id is null
            and ?
            and equipment_id=?
            and warehouse_id=?
          )
        )
          and (state='COMMITTED' or (state='ACTIVE' and expires_at>clock_timestamp()))
        """,
        (rs, row) -> new HoldQuantity(rs.getObject("id", UUID.class), rs.getLong("quantity")),
        source.id(),
        source.kind() == BalanceLocationKind.STOCK,
        source.equipmentId(),
        source.warehouseId());
    return holds.stream()
        .filter(hold -> !excludedReservationIds.contains(hold.id()))
        .mapToLong(HoldQuantity::quantity)
        .sum();
  }
  private static EquipmentBalanceResponse balanceResponse(
      EquipmentAllocationPolicy.SourceAvailability source) {
    return new EquipmentBalanceResponse(
        source.balanceId(),
        source.version(),
        source.equipmentId(),
        source.warehouseId(),
        source.rentalItemId(),
        source.locationKind(),
        source.quantity(),
        source.activeHeldQuantity(),
        source.allocatable(),
        source.availableQuantity());
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
  private static AssetEventType movementEventType(String kind) {
    return switch (kind) {
      case "WRITE_OFF" -> AssetEventType.EQUIPMENT_WRITTEN_OFF;
      case "LOSS" -> AssetEventType.EQUIPMENT_LOST;
      default -> AssetEventType.EQUIPMENT_TRANSFERRED;
    };
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
      if (activeHeld(source) > 0) {
        throw new AssetConflictException(
            "Cabin relocation would consume quantities reserved by an active hold");
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

  private void expireHolds(BalanceRow source) {
    expireHolds(
        source.id(),
        source.equipmentId(),
        source.warehouseId(),
        source.kind() == BalanceLocationKind.STOCK);
  }
  private void expireHolds(EquipmentHoldResponse hold) {
    expireHolds(
        hold.sourceBalanceId(),
        hold.equipmentId(),
        hold.warehouseId(),
        hold.sourceBalanceId() == null);
  }
  private void expireHolds(
      UUID sourceBalanceId,
      UUID equipmentId,
      UUID warehouseId,
      boolean includeLegacyStockHolds) {
    List<EquipmentHoldResponse> expired = jdbc.query("""
        select id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,
          quantity,state,expires_at,committed_at,executed_at
        from equipment_allocation_hold
        where state='ACTIVE' and expires_at<=clock_timestamp()
          and (
            source_balance_id=?
            or (
              source_balance_id is null
              and ?
              and equipment_id=?
              and warehouse_id=?
            )
          )
        for update
        """,
        (rs, row) -> equipmentHoldRow(rs),
        sourceBalanceId,
        includeLegacyStockHolds,
        equipmentId,
        warehouseId);
    expired.forEach(this::expireHold);
  }
  private void expireHold(EquipmentHoldResponse current) {
    int changed = jdbc.update(
        """
        update equipment_allocation_hold
        set state='EXPIRED',released_at=clock_timestamp(),version=version+1,updated_at=clock_timestamp()
        where id=? and version=? and state='ACTIVE'
        """,
        current.id(),
        current.version());
    if (changed != 1) {
      throw new AssetConflictException("Equipment hold changed concurrently during expiry");
    }
    EquipmentHoldResponse updated = holdResponse(current.id());
    events.append(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        current.id(),
        current.version(),
        AssetEventType.EQUIPMENT_HOLD_EXPIRED,
        holdFact(updated),
        holdSnapshot(updated));
  }
  /** Expiry immediately stops availability blocking; this sweep persists the visible state/event. */
  @Scheduled(fixedDelayString = "${rwms.asset.equipment-hold.expiration-sweep-delay:PT30S}")
  @Transactional
  public void expireDueEquipmentHolds() {
    List<EquipmentHoldResponse> expired = jdbc.query("""
        select id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,
          quantity,state,expires_at,committed_at,executed_at
        from equipment_allocation_hold
        where state='ACTIVE' and expires_at<=clock_timestamp()
        order by expires_at,id
        for update skip locked
        limit 200
        """, (rs, row) -> equipmentHoldRow(rs));
    expired.forEach(this::expireHold);
  }
  private EquipmentHoldResponse holdRead(UUID id) {
    return jdbc.query("""
        select id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,
          quantity,state,expires_at,committed_at,executed_at
        from equipment_allocation_hold where id=?
        """, (rs, row) -> equipmentHoldRow(rs), id).stream().findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment hold was not found"));
  }
  private EquipmentHoldResponse holdResponse(UUID id) {
    return jdbc.query("""
        select id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,
          quantity,state,expires_at,committed_at,executed_at
        from equipment_allocation_hold where id=? for update
        """, (rs, row) -> equipmentHoldRow(rs), id).stream().findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment hold was not found"));
  }
  private static EquipmentHoldResponse equipmentHoldRow(java.sql.ResultSet rs)
      throws java.sql.SQLException {
    return new EquipmentHoldResponse(
        rs.getObject("id", UUID.class),
        rs.getLong("version"),
        rs.getObject("equipment_id", UUID.class),
        rs.getObject("warehouse_id", UUID.class),
        rs.getString("owner_type"),
        rs.getString("owner_id"),
        rs.getObject("source_balance_id", UUID.class),
        rs.getLong("quantity"),
        rs.getString("state"),
        rs.getObject("expires_at", OffsetDateTime.class),
        rs.getObject("committed_at", OffsetDateTime.class),
        rs.getObject("executed_at", OffsetDateTime.class));
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
  private void assertNoActiveMovementReservation(UUID movementId, UUID lineId) {
    String ownerId = logisticsOwnerId(movementId, lineId);
    Boolean active = jdbc.queryForObject(
        """
        select exists(
          select 1 from equipment_allocation_hold
          where owner_type in (?,?) and owner_id=? and state='ACTIVE'
        )
        """,
        Boolean.class,
        LogisticsEquipmentMovementReservationOwnerType
            .LOGISTICS_EQUIPMENT_MOVEMENT
            .name(),
        LogisticsEquipmentMovementReservationOwnerType
            .MAINTENANCE_DISPOSITION_MOVEMENT
            .name(),
        ownerId);
    if (Boolean.TRUE.equals(active)) {
      throw new AssetConflictException(
          "Equipment movement line already has an active reservation");
    }
  }
  private void assertMovementReservationOwner(
      EquipmentHoldResponse reservation, UUID movementId, UUID lineId) {
    boolean supportedOwner =
        reservation != null
            && (LogisticsEquipmentMovementReservationOwnerType
                    .LOGISTICS_EQUIPMENT_MOVEMENT
                    .name()
                    .equals(reservation.ownerType())
                || LogisticsEquipmentMovementReservationOwnerType
                    .MAINTENANCE_DISPOSITION_MOVEMENT
                    .name()
                    .equals(reservation.ownerType()));
    if (reservation == null
        || !supportedOwner
        || !logisticsOwnerId(movementId, lineId).equals(reservation.ownerId())) {
      throw new AssetConflictException(
          "Equipment movement reservation belongs to another logistics movement line");
    }
  }

  private static boolean isMaintenanceDispositionReservation(
      EquipmentHoldResponse reservation) {
    return reservation != null
        && LogisticsEquipmentMovementReservationOwnerType
            .MAINTENANCE_DISPOSITION_MOVEMENT
            .name()
            .equals(reservation.ownerType());
  }

  private void requireAllocatableSource(BalanceRow source) {
    boolean allocatable =
        equipmentAllocationPolicy.sources(source.equipmentId(), source.warehouseId()).stream()
            .anyMatch(
                candidate ->
                    candidate.balanceId().equals(source.id()) && candidate.allocatable());
    if (!allocatable) {
      throw new AssetConflictException(
          "Equipment source is not allocatable in its current cabin workflow state");
    }
  }

  private UUID requireMaintenanceDispositionParentHold(
      AcquireLogisticsEquipmentMovementReservationRequest request, BalanceRow source) {
    if (request.sourceRentalItemId() == null
        || request.sourceLocationKind() != BalanceLocationKind.CABIN_NON_RENTED
        || !request.sourceRentalItemId().equals(source.rentalItemId())) {
      throw new AssetConflictException(
          "Maintenance disposition movement must start in its prepared non-rented cabin");
    }
    return requireMaintenanceDispositionParentHold(
        request.movementId(),
        request.sourceWarehouseId(),
        request.sourceRentalItemId(),
        request.equipmentId(),
        source.id(),
        request.quantity());
  }

  private UUID requireMaintenanceDispositionParentHold(
      UUID decisionId,
      UUID warehouseId,
      UUID rentalItemId,
      UUID equipmentId,
      UUID sourceBalanceId,
      long moveQuantity) {
    return jdbc.query(
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
  private static boolean isMovableReservationSource(BalanceLocationKind kind) {
    return kind == BalanceLocationKind.STOCK
        || kind == BalanceLocationKind.CABIN_NON_RENTED
        || kind == BalanceLocationKind.CABIN_RENTED;
  }
  private LogisticsEquipmentMovementReservationResponse movementReservationResponse(
      EquipmentHoldResponse reservation) {
    EquipmentCatalogItem catalog = requireEquipment(reservation.equipmentId());
    if (reservation.sourceBalanceId() == null) {
      throw new AssetConflictException(
          "Equipment movement reservation has no concrete source balance");
    }
    return movementReservationResponse(
        reservation, catalog, balanceReadById(reservation.sourceBalanceId()));
  }
  private static LogisticsEquipmentMovementReservationResponse movementReservationResponse(
      EquipmentHoldResponse reservation,
      EquipmentCatalogItem catalog,
      BalanceRow source) {
    return new LogisticsEquipmentMovementReservationResponse(
        reservation.id(),
        reservation.version(),
        LogisticsEquipmentMovementReservationOwnerType.valueOf(reservation.ownerType()),
        ownerDocumentId(reservation.ownerId()),
        ownerLineId(reservation.ownerId()),
        reservation.equipmentId(),
        catalog.getName(),
        source.id(),
        source.warehouseId(),
        source.rentalItemId(),
        source.kind(),
        reservation.quantity(),
        reservation.state(),
        reservation.expiresAt(),
        reservation.executedAt());
  }
  private void validateMovementExecutionRequest(
      ExecuteLogisticsEquipmentMovementReservationsRequest request) {
    if (request == null || request.movementId() == null || request.lines() == null
        || request.lines().isEmpty()) {
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
  private void assertReservedExecutionAvailability(
      List<MovementReservationExecutionPlan> plans) {
    Map<UUID, List<MovementReservationExecutionPlan>> bySource = new LinkedHashMap<>();
    for (MovementReservationExecutionPlan plan : plans) {
      bySource.computeIfAbsent(plan.source().id(), ignored -> new ArrayList<>()).add(plan);
    }
    for (List<MovementReservationExecutionPlan> values : bySource.values()) {
      BalanceRow source = values.getFirst().source();
      long consumed = values.stream()
          .mapToLong(value -> value.reservation().quantity())
          .sum();
      Set<UUID> ownReservationIds = values.stream()
          .map(value -> value.reservation().id())
          .collect(java.util.stream.Collectors.toSet());
      values.stream()
          .map(MovementReservationExecutionPlan::nestedParentHoldId)
          .filter(java.util.Objects::nonNull)
          .forEach(ownReservationIds::add);
      long heldByOthers = activeHeldExcluding(source, ownReservationIds);
      if (Math.subtractExact(source.quantity(), consumed) < heldByOthers) {
        throw new AssetConflictException(
            "Equipment movement execution would consume quantities reserved by another hold");
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
  private void assertNoActiveOrderReservation(UUID rentalItemId, String message) {
    Boolean active = jdbc.queryForObject(
        """
        select exists(
          select 1 from order_unit_reservation
          where rental_item_id=? and state='ACTIVE'
        )
        """,
        Boolean.class,
        rentalItemId);
    if (Boolean.TRUE.equals(active)) {
      throw new AssetConflictException(message);
    }
  }

  /**
   * An order reservation remains the asset-side proof of the rental link while
   * a cabin is shipped and rented.  Logistics is allowed to create that proof
   * for a selected free cabin, or to consume an already matching reservation;
   * it can never take a cabin reserved by another rental order.
   */
  private void assertLogisticsOrderReservation(
      UUID subjectId, AcquireLogisticsOperationLeaseRequest request, RentalItem item) {
    OrderUnitReservation current =
        orderUnitReservations
            .findByRentalItemIdAndState(item.getId(), OrderUnitReservationState.ACTIVE)
            .orElse(null);
    if (request.ownerType() == LogisticsLeaseOwnerType.LOGISTICS_SHIPMENT) {
      if (request.rentalOrderId() == null) {
        if (current != null) {
          throw new AssetConflictException(
              "Reserved order unit cannot acquire an operation lease");
        }
        return;
      }
      if (current == null) {
        if (item.getStatus() != RentalItemStatus.FREE) {
          throw new AssetConflictException(
              "Only a free rental item can be added to a rental shipment");
        }
        orderUnitReservations.saveAndFlush(
            OrderUnitReservation.create(
                request.rentalOrderId(),
                item.getId(),
                item.getWarehouseId(),
                subjectId,
                "SYSTEM_ADMIN"));
        return;
      }
      if (!request.rentalOrderId().equals(current.getOrderId())) {
        throw new AssetConflictException(
            "Rental item is reserved by a different rental order");
      }
      return;
    }

    if (request.ownerType() == LogisticsLeaseOwnerType.LOGISTICS_RETURN
        && request.rentalOrderId() != null) {
      if (current == null || !request.rentalOrderId().equals(current.getOrderId())) {
        throw new AssetConflictException(
            "Rental item is not actively reserved by the selected rental order");
      }
      return;
    }

    if (current != null) {
      throw new AssetConflictException(
          "Reserved order unit cannot acquire an operation lease");
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
  private static boolean isHtmlImportManualStatus(RentalItemStatus status) {
    return status == RentalItemStatus.SALE
        || status == RentalItemStatus.USED_SALE
        || status == RentalItemStatus.FREE
        || status == RentalItemStatus.WAREHOUSE
        || status == RentalItemStatus.OWN_NEEDS;
  }
  private static String balanceLockKey(UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    return "asset-balance:" + equipmentId + ':' + warehouseId + ':' + (rentalItemId == null ? "-" : rentalItemId) + ':' + kind.name();
  }
  private void advisoryLocks(Collection<String> values) {
    values.stream().filter(java.util.Objects::nonNull).distinct().sorted().forEach(this::advisoryLock);
  }
  private void advisoryLock(String value) { jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, value); }

  private ClassifierResponse classifier(UUID id) {
    return jdbc.query("select id,version,classifier_type,parent_id,name,active,sort_order from asset_classifier where id=?",
        (rs, row) -> new ClassifierResponse(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("classifier_type"),
            rs.getObject("parent_id", UUID.class), rs.getString("name"), rs.getBoolean("active"), rs.getObject("sort_order", Integer.class)), id).stream().findFirst().orElseThrow(() -> new AssetNotFoundException("Classifier was not found"));
  }
  private static String classifierType(String value) {
    String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    if (!List.of("CATEGORY", "SUBCATEGORY", "TYPE", "CONDITION").contains(normalized)) throw new IllegalArgumentException("Unsupported classifier type");
    return normalized;
  }
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
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("rentalItemId", item.getId().toString());
    value.put("warehouseId", item.getWarehouseId().toString());
    value.put("status", item.getStatus().name());
    value.put(
        "numberSha256",
        AssetChecksum.sha256(
            item.getNumber().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    if (item.getTransferOriginStatus() != null) {
      value.put("transferAssetStatus", item.getTransferOriginStatus().name());
    }
    return Map.copyOf(value);
  }
  /** Full service-local state for deterministic replay; never serialized into a Kafka envelope. */
  private Map<String, ?> rentalSnapshot(RentalItem item) {
    Map<String, Object> value = new LinkedHashMap<>();
    CabinCompositionService.CabinComposition composition =
        cabinComposition.compositionsFor(List.of(item)).get(item.getId());
    value.put("rentalItemId", item.getId().toString());
    value.put("version", item.getVersion());
    value.put("warehouseId", item.getWarehouseId().toString());
    value.put("number", item.getNumber());
    value.put("status", item.getStatus().name());
    value.put(
        "transferOriginStatus",
        item.getTransferOriginStatus() == null
            ? null
            : item.getTransferOriginStatus().name());
    value.put(
        "rentalTypeId",
        item.getRentalTypeId() == null ? null : item.getRentalTypeId().toString());
    value.put(
        "dimensionId", item.getDimensionId() == null ? null : item.getDimensionId().toString());
    value.put(
        "finishingId", item.getFinishingId() == null ? null : item.getFinishingId().toString());
    value.put("category", item.getCategory());
    value.put(
        "characteristicIds",
        composition == null
            ? List.of()
            : composition.characteristics().stream()
                .map(CabinCatalogValueResponse::id)
                .map(UUID::toString)
                .toList());
    value.put("linoleum", item.getLinoleum());
    value.put("generalComment", item.getGeneralComment());
    value.put("passport", map(item.getPassportJson()));
    value.put("tags", strings(item.getTagsJson()));
    return value;
  }
  private Map<String, ?> equipmentFact(EquipmentCatalogItem item) {
    return Map.of("equipmentId", item.getId().toString(), "category", item.getCategory().name(), "active", item.isActive());
  }
  private Map<String, ?> equipmentSnapshot(EquipmentCatalogItem item) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("equipmentId", item.getId().toString());
    value.put("version", item.getVersion());
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
      fact.put(
          "sourceRentalItemId",
          context.sourceRentalItemId() == null
              ? null
              : context.sourceRentalItemId().toString());
      fact.put("sourceLocationKind", context.sourceLocationKind());
      fact.put("targetWarehouseId", context.targetWarehouseId().toString());
      fact.put(
          "targetRentalItemId",
          context.targetRentalItemId() == null
              ? null
              : context.targetRentalItemId().toString());
      fact.put("targetLocationKind", context.targetLocationKind());
    }
    return fact;
  }

  private MovementContext movementContext(UUID movementId) {
    return jdbc.query(
            """
            select equipment_category_snapshot,
              source_warehouse_id,source_rental_item_id,source_location_kind,
              target_warehouse_id,target_rental_item_id,target_location_kind,
              capture_origin
            from equipment_movement_context
            where movement_id=?
            """,
            (rs, row) ->
                new MovementContext(
                    rs.getString("equipment_category_snapshot"),
                    rs.getObject("source_warehouse_id", UUID.class),
                    rs.getObject("source_rental_item_id", UUID.class),
                    rs.getString("source_location_kind"),
                    rs.getObject("target_warehouse_id", UUID.class),
                    rs.getObject("target_rental_item_id", UUID.class),
                    rs.getString("target_location_kind"),
                    "AT_MOVEMENT".equals(rs.getString("capture_origin"))),
            movementId)
        .stream()
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Equipment movement context is missing for " + movementId));
  }
  private Map<String, ?> holdFact(EquipmentHoldResponse value) {
    // Facts must stay compatible with holds emitted before V7.  The concrete
    // source balance is durable reservation state and belongs in the snapshot,
    // not in the immutable projection fact used by replay verification.
    return Map.of(
        "holdId", value.id().toString(),
        "equipmentId", value.equipmentId().toString(),
        "warehouseId", value.warehouseId().toString(),
        "quantity", value.quantity(),
        "state", value.state());
  }
  private Map<String, ?> holdSnapshot(EquipmentHoldResponse value) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("holdId", value.id().toString());
    snapshot.put("version", value.version());
    snapshot.put("equipmentId", value.equipmentId().toString());
    snapshot.put("warehouseId", value.warehouseId().toString());
    snapshot.put("ownerType", value.ownerType());
    snapshot.put("ownerId", value.ownerId());
    snapshot.put(
        "sourceBalanceId",
        value.sourceBalanceId() == null ? null : value.sourceBalanceId().toString());
    snapshot.put("quantity", value.quantity());
    snapshot.put("state", value.state());
    snapshot.put("expiresAt", value.expiresAt().toString());
    snapshot.put("committedAt", value.committedAt() == null ? null : value.committedAt().toString());
    snapshot.put("executedAt", value.executedAt() == null ? null : value.executedAt().toString());
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
    try {
      String serialized = mapper.writeValueAsString(value == null ? Map.of() : value);
      Map<String, Object> passport =
          mapper.readValue(serialized, new TypeReference<Map<String, Object>>() {});
      return mapper.writeValueAsString(RentalPassportSanitizer.sanitize(passport));
    }
    catch (JacksonException exception) { throw new IllegalArgumentException("Asset JSON is invalid", exception); }
  }
  private String jsonArray(Object value) {
    try { return mapper.writeValueAsString(value == null ? List.of() : value); }
    catch (JacksonException exception) { throw new IllegalArgumentException("Asset JSON is invalid", exception); }
  }
  private Map<String, Object> map(String value) {
    try {
      Map<String, Object> passport =
          mapper.readValue(value, new TypeReference<Map<String, Object>>() {});
      return RentalPassportSanitizer.sanitize(passport);
    }
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
  private record HtmlImportEquipmentReceiptRow(
      UUID id,
      UUID actorSubjectId,
      String reason,
      UUID rentalItemId,
      UUID equipmentId,
      UUID targetBalanceId,
      long quantity) {}
  private record ReturnEquipmentReceiptRow(
      UUID id,
      UUID returnId,
      UUID returnLineId,
      UUID equipmentId,
      UUID warehouseId,
      UUID stockBalanceId,
      long quantity) {}
  private record HoldQuantity(UUID id, long quantity) {}
  private record MovementReservationCandidate(
      EquipmentHoldResponse reservation,
      BalanceRow source,
      ExecuteLogisticsEquipmentMovementReservationLine line) {}
  private record MovementReservationExecutionPlan(
      EquipmentHoldResponse reservation,
      BalanceRow source,
      BalanceRow target,
      ExecuteLogisticsEquipmentMovementReservationLine line,
      UUID nestedParentHoldId) {}
  private record MovementContext(
      String equipmentCategory,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      String targetLocationKind,
      boolean exact) {}
  private record ResourceCommand<T>(UUID resourceId, T request) {}
  private record MaintenanceLeaseCommand<T>(UUID resourceId, T request) {}
  private record LogisticsLeaseCommand(UUID resourceId, LogisticsLeaseCommandRequest request) {}
  private record LogisticsEffectCommand(UUID resourceId, LogisticsFencedEffectRequest request) {}
  private record MaintenanceCharacteristicCommand(
      UUID rentalItemId, UUID characteristicId) {}
  private record LogisticsHoldCommand(UUID resourceId, LogisticsEquipmentHoldCommandRequest request) {}
  private record LogisticsEquipmentMovementReservationCommand(
      UUID resourceId,
      LogisticsEquipmentMovementReservationCommandRequest request) {}
  public record CreateResult<T>(T response, boolean replayed) {}
}
