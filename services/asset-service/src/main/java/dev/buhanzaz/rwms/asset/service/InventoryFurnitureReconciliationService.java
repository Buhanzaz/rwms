package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentBalance;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.InventoryFurnitureReconciliation;
import dev.buhanzaz.rwms.asset.domain.OrderEquipmentReservation;
import dev.buhanzaz.rwms.asset.domain.OrderEquipmentReservationState;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHold;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHoldState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.repository.EquipmentBalanceRepository;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryFurnitureReconciliationRepository;
import dev.buhanzaz.rwms.asset.repository.OrderEquipmentReservationRepository;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.PresentationUnitHoldRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Owns inventory's absolute FURNITURE count snapshot and reconciliation transaction work.
 *
 * <p>The caller supplies the serializable transaction. This collaborator preserves the asset lock
 * order, validates immutable request identity plus the current cabin/catalog scope, supersedes live
 * bindings, fences event streams, and appends one fact for each changed physical balance.
 */
@Service
final class InventoryFurnitureReconciliationService {
  private static final Set<BalanceLocationKind> FURNITURE_SNAPSHOT_BALANCE_KINDS =
      EnumSet.of(
          BalanceLocationKind.STOCK,
          BalanceLocationKind.CABIN_NON_RENTED,
          BalanceLocationKind.CABIN_RENTED);
  /** Placeholder used only to keep a native IN predicate valid when no bucket exists yet. */
  private static final UUID ABSENT_BALANCE_ID = new UUID(0L, 0L);
  private static final UUID INVENTORY_SERVICE_ACTOR =
      UUID.nameUUIDFromBytes(
          "service:inventory-service".getBytes(java.nio.charset.StandardCharsets.UTF_8));

  private final RentalItemRepository rentalItems;
  private final EquipmentBalanceRepository equipmentBalances;
  private final EquipmentCatalogItemRepository equipmentCatalog;
  private final InventoryFurnitureReconciliationRepository furnitureReconciliations;
  private final InventoryAssetBoundaryRegistrar registrar;
  private final AssetEventStore events;
  private final WarehouseRegistryClient warehouses;
  private final InventoryAssetSnapshotTransaction snapshotTransaction;
  private final InventoryAssetCodec codec;
  private final AssetLeaseService leases;
  private final OrderUnitReservationRepository orderReservations;
  private final PresentationUnitHoldRepository presentationHolds;
  private final OrderEquipmentReservationRepository orderEquipmentReservations;
  private final AssetEquipmentHoldService equipmentHolds;

  InventoryFurnitureReconciliationService(
      RentalItemRepository rentalItems,
      EquipmentBalanceRepository equipmentBalances,
      EquipmentCatalogItemRepository equipmentCatalog,
      InventoryFurnitureReconciliationRepository furnitureReconciliations,
      InventoryAssetBoundaryRegistrar registrar,
      AssetEventStore events,
      WarehouseRegistryClient warehouses,
      InventoryAssetSnapshotTransaction snapshotTransaction,
      InventoryAssetCodec codec,
      AssetLeaseService leases,
      OrderUnitReservationRepository orderReservations,
      PresentationUnitHoldRepository presentationHolds,
      OrderEquipmentReservationRepository orderEquipmentReservations,
      AssetEquipmentHoldService equipmentHolds) {
    this.rentalItems = rentalItems;
    this.equipmentBalances = equipmentBalances;
    this.equipmentCatalog = equipmentCatalog;
    this.furnitureReconciliations = furnitureReconciliations;
    this.registrar = registrar;
    this.events = events;
    this.warehouses = warehouses;
    this.snapshotTransaction = snapshotTransaction;
    this.codec = codec;
    this.leases = leases;
    this.orderReservations = orderReservations;
    this.presentationHolds = presentationHolds;
    this.orderEquipmentReservations = orderEquipmentReservations;
    this.equipmentHolds = equipmentHolds;
  }

  /**
   * Returns all currently active FURNITURE positions and only the caller-selected cabins. The hash
   * is over the canonical response body without its self-referential hash field.
   */
  InventoryFurnitureSnapshot furnitureSnapshot(InventoryFurnitureSnapshotRequest request) {
    FurnitureSnapshotRequest normalized = normalizeFurnitureSnapshotRequest(request);
    warehouses.requireIncoming(normalized.warehouseId());
    FurnitureSnapshotState snapshot =
        Objects.requireNonNull(
            snapshotTransaction.execute(
                () -> currentFurnitureSnapshot(normalized.warehouseId(), normalized.assetIds())));
    return snapshot.response();
  }

  /**
   * Applies one reviewed absolute furniture count after durable registration, stable scope checks,
   * guard supersession, and event-stream fencing. The source snapshot hash remains immutable
   * request evidence but is not compared with mutable cabin status/version or balance fields.
   */
  boolean reconcile(
      UUID inventoryId,
      UUID idempotencyKey,
      InventoryFurnitureReconciliationRequest request) {
    if (inventoryId == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Inventory furniture reconciliation identity is required");
    }
    FurnitureReconciliationPlan plan = normalizeFurnitureReconciliationRequest(request);
    warehouses.requireIncoming(plan.warehouseId());
    String requestSha256 =
        codec.canonicalHash(new FurnitureReconciliationFingerprint(inventoryId, plan));

    registerConcurrentSafe(
        () -> registrar.registerFurnitureReconciliation(inventoryId, requestSha256, idempotencyKey));
    InventoryFurnitureReconciliation source =
        furnitureReconciliations
            .findByInventoryIdForUpdate(inventoryId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Inventory furniture reconciliation registration failed"));
    if (!source.getRequestSha256().equals(requestSha256)) {
      throw new AssetConflictException(
          "Inventory furniture reconciliation identity is bound to another request");
    }
    if (source.isCompleted()) {
      return true;
    }

    FurnitureSnapshotState current = lockedFurnitureSnapshot(plan.warehouseId(), plan.assetIds());
    assertFurnitureReconciliationScopeAndCatalog(plan, current);
    supersedeLiveFurnitureReconciliationGuards(current);

    Map<UUID, Long> streamVersions = lockFurnitureBalanceStreams(current);
    applyFurnitureReconciliation(plan, current, streamVersions);
    if (!source.complete()) {
      throw new AssetConflictException("Inventory furniture reconciliation was already completed");
    }
    furnitureReconciliations.saveAndFlush(source);
    return false;
  }

  private FurnitureSnapshotState currentFurnitureSnapshot(UUID warehouseId, List<UUID> assetIds) {
    List<RentalItem> cabins = selectedFurnitureCabins(warehouseId, assetIds, false);
    List<EquipmentCatalogItem> catalog =
        equipmentCatalog.findAllByCategoryAndActiveTrueOrderByNameAscIdAsc(EquipmentCategory.FURNITURE);
    List<EquipmentBalance> balances =
        catalog.isEmpty()
            ? List.of()
            : assetIds.isEmpty()
                ? equipmentBalances.findFurnitureStockScope(
                    warehouseId, catalog.stream().map(EquipmentCatalogItem::getId).toList())
                : equipmentBalances.findFurnitureScope(
                    warehouseId,
                    catalog.stream().map(EquipmentCatalogItem::getId).toList(),
                    assetIds,
                    FURNITURE_SNAPSHOT_BALANCE_KINDS);
    return furnitureSnapshotState(warehouseId, cabins, catalog, balances);
  }

  /** Takes the same advisory locks as asset-owned operations before selecting the counted state. */
  private FurnitureSnapshotState lockedFurnitureSnapshot(UUID warehouseId, List<UUID> assetIds) {
    lockTransactionKeys(
        assetIds.stream()
            .map(InventoryFurnitureReconciliationService::rentalItemLockKey)
            .toList());
    lockTransactionKeys(
        assetIds.stream().map(InventoryFurnitureReconciliationService::leaseLockKey).toList());
    List<RentalItem> cabins = selectedFurnitureCabins(warehouseId, assetIds, true);
    List<EquipmentCatalogItem> catalog =
        equipmentCatalog.findAllActiveByCategoryForUpdate(EquipmentCategory.FURNITURE);
    if (catalog.isEmpty()) {
      return furnitureSnapshotState(warehouseId, cabins, catalog, List.of());
    }

    List<UUID> equipmentIds = catalog.stream().map(EquipmentCatalogItem::getId).toList();
    lockTransactionKeys(
        equipmentIds.stream()
            .map(equipmentId -> orderEquipmentLockKey(warehouseId, equipmentId))
            .toList());
    List<String> balanceLockKeys = new ArrayList<>();
    for (UUID equipmentId : equipmentIds) {
      balanceLockKeys.add(
          balanceLockKey(equipmentId, warehouseId, null, BalanceLocationKind.STOCK));
      for (RentalItem cabin : cabins) {
        balanceLockKeys.add(
            balanceLockKey(
                equipmentId,
                warehouseId,
                cabin.getId(),
                BalanceLocationKind.CABIN_NON_RENTED));
        balanceLockKeys.add(
            balanceLockKey(
                equipmentId,
                warehouseId,
                cabin.getId(),
                BalanceLocationKind.CABIN_RENTED));
      }
    }
    lockTransactionKeys(balanceLockKeys);
    List<EquipmentBalance> balances =
        assetIds.isEmpty()
            ? equipmentBalances.findFurnitureStockScopeForUpdate(warehouseId, equipmentIds)
            : equipmentBalances.findFurnitureScopeForUpdate(
                warehouseId, equipmentIds, assetIds, FURNITURE_SNAPSHOT_BALANCE_KINDS);
    return furnitureSnapshotState(warehouseId, cabins, catalog, balances);
  }

  private FurnitureSnapshotState furnitureSnapshotState(
      UUID warehouseId,
      List<RentalItem> cabins,
      List<EquipmentCatalogItem> catalog,
      List<EquipmentBalance> balances) {
    Map<BalanceKey, EquipmentBalance> balancesByKey = new LinkedHashMap<>();
    for (EquipmentBalance balance : balances) {
      BalanceKey key =
          new BalanceKey(
              balance.getEquipmentId(), balance.getRentalItemId(), balance.getLocationKind());
      if (balancesByKey.putIfAbsent(key, balance) != null) {
        throw new IllegalStateException("Furniture balance location uniqueness is corrupted");
      }
    }
    List<RentalItem> orderedCabins =
        cabins.stream()
            .sorted(Comparator.comparing(RentalItem::getNumber).thenComparing(RentalItem::getId))
            .toList();
    List<EquipmentCatalogItem> orderedCatalog =
        catalog.stream()
            .sorted(
                Comparator.comparing(EquipmentCatalogItem::getName)
                    .thenComparing(EquipmentCatalogItem::getId))
            .toList();
    List<InventoryFurnitureSnapshotItem> items =
        orderedCatalog.stream()
            .map(
                equipment -> {
                  EquipmentBalance stockBalance =
                      balancesByKey.get(
                          new BalanceKey(equipment.getId(), null, BalanceLocationKind.STOCK));
                  return new InventoryFurnitureSnapshotItem(
                      equipment.getId(),
                      equipment.getVersion(),
                      equipment.getName(),
                      stockBalance == null ? 0L : stockBalance.getQuantity(),
                      stockBalance == null ? null : stockBalance.getVersion(),
                      orderedCabins.stream()
                          .map(
                              cabin ->
                                  new InventoryFurnitureSnapshotCabin(
                                      cabin.getId(),
                                      cabin.getVersion(),
                                      cabin.getNumber(),
                                      cabin.getStatus(),
                                      cabinQuantity(
                                          balancesByKey, equipment.getId(), cabin.getId())))
                          .toList());
                })
            .toList();
    String snapshotSha256 =
        codec.canonicalHash(new FurnitureSnapshotFingerprint(warehouseId, items));
    return new FurnitureSnapshotState(
        warehouseId,
        List.copyOf(orderedCabins),
        List.copyOf(orderedCatalog),
        Map.copyOf(balancesByKey),
        List.copyOf(items),
        snapshotSha256);
  }

  private List<RentalItem> selectedFurnitureCabins(
      UUID warehouseId, List<UUID> assetIds, boolean forUpdate) {
    if (assetIds.isEmpty()) {
      return List.of();
    }
    List<RentalItem> selected =
        forUpdate ? rentalItems.findAllByIdInForUpdate(assetIds) : rentalItems.findAllById(assetIds);
    if (selected.size() != assetIds.size()) {
      throw new AssetNotFoundException("Selected furniture reconciliation cabin was not found");
    }
    if (selected.stream().anyMatch(item -> !warehouseId.equals(item.getWarehouseId()))) {
      throw new AssetConflictException(
          "Selected furniture reconciliation cabins must belong to the requested warehouse");
    }
    return selected;
  }

  private static FurnitureSnapshotRequest normalizeFurnitureSnapshotRequest(
      InventoryFurnitureSnapshotRequest request) {
    if (request == null || request.warehouseId() == null) {
      throw new IllegalArgumentException("Inventory furniture snapshot warehouse is required");
    }
    List<UUID> assetIds = normalizedFurnitureAssetIds(request.assetIds());
    return new FurnitureSnapshotRequest(request.warehouseId(), assetIds);
  }

  private static FurnitureReconciliationPlan normalizeFurnitureReconciliationRequest(
      InventoryFurnitureReconciliationRequest request) {
    if (request == null || request.warehouseId() == null) {
      throw new IllegalArgumentException("Inventory furniture reconciliation warehouse is required");
    }
    if (!sha256(request.expectedSnapshotSha256()) || !sha256(request.reviewSha256())) {
      throw new IllegalArgumentException("Inventory furniture reconciliation hashes are invalid");
    }
    if (request.items() == null || request.items().isEmpty() || request.items().size() > 1000) {
      throw new IllegalArgumentException("Inventory furniture reconciliation items are required");
    }

    Set<UUID> equipmentIds = new LinkedHashSet<>();
    List<FurnitureReconciliationItemPlan> items = new ArrayList<>(request.items().size());
    List<UUID> commonAssetIds = null;
    for (InventoryFurnitureReconciliationItem item : request.items()) {
      if (item == null
          || item.equipmentId() == null
          || item.catalogVersion() == null
          || item.catalogVersion() < 0
          || item.stockQuantity() == null
          || item.stockQuantity() < 0) {
        throw new IllegalArgumentException("Inventory furniture reconciliation item is invalid");
      }
      if (!equipmentIds.add(item.equipmentId())) {
        throw new IllegalArgumentException(
            "Inventory furniture reconciliation equipment is duplicated");
      }
      List<FurnitureReconciliationCabinPlan> cabins = normalizedFurnitureCabins(item.cabins());
      List<UUID> assetIds =
          cabins.stream().map(FurnitureReconciliationCabinPlan::assetId).toList();
      if (commonAssetIds == null) {
        commonAssetIds = assetIds;
      } else if (!commonAssetIds.equals(assetIds)) {
        throw new IllegalArgumentException(
            "Every furniture reconciliation item must cover the same cabins");
      }
      items.add(
          new FurnitureReconciliationItemPlan(
              item.equipmentId(), item.catalogVersion(), item.stockQuantity(), cabins));
    }
    items.sort(Comparator.comparing(item -> item.equipmentId().toString()));
    if (commonAssetIds == null) {
      throw new IllegalArgumentException("Inventory furniture reconciliation cabins are invalid");
    }
    return new FurnitureReconciliationPlan(
        request.warehouseId(),
        request.expectedSnapshotSha256(),
        request.reviewSha256(),
        List.copyOf(items),
        List.copyOf(commonAssetIds));
  }

  private static List<UUID> normalizedFurnitureAssetIds(List<UUID> assetIds) {
    if (assetIds == null || assetIds.size() > 5000) {
      throw new IllegalArgumentException("Inventory furniture cabin selection is invalid");
    }
    Set<UUID> unique = new LinkedHashSet<>();
    for (UUID assetId : assetIds) {
      if (assetId == null || !unique.add(assetId)) {
        throw new IllegalArgumentException("Inventory furniture cabin identity is invalid");
      }
    }
    return unique.stream().sorted(Comparator.comparing(UUID::toString)).toList();
  }

  private static List<FurnitureReconciliationCabinPlan> normalizedFurnitureCabins(
      List<InventoryFurnitureReconciliationCabin> cabins) {
    if (cabins == null || cabins.size() > 5000) {
      throw new IllegalArgumentException("Inventory furniture reconciliation cabins are invalid");
    }
    Set<UUID> assetIds = new LinkedHashSet<>();
    List<FurnitureReconciliationCabinPlan> result = new ArrayList<>(cabins.size());
    for (InventoryFurnitureReconciliationCabin cabin : cabins) {
      if (cabin == null
          || cabin.assetId() == null
          || cabin.quantity() == null
          || cabin.quantity() < 0
          || !assetIds.add(cabin.assetId())) {
        throw new IllegalArgumentException("Inventory furniture reconciliation cabin is invalid");
      }
      result.add(new FurnitureReconciliationCabinPlan(cabin.assetId(), cabin.quantity()));
    }
    result.sort(Comparator.comparing(cabin -> cabin.assetId().toString()));
    return List.copyOf(result);
  }

  private static boolean sha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  /**
   * Rejects only changes that invalidate the reviewed scope: terminal/missing/moved cabins or a
   * changed active furniture catalog. Non-terminal cabin and physical-balance drift is expected
   * because completed inventory counts replace that mutable state.
   */
  private void assertFurnitureReconciliationScopeAndCatalog(
      FurnitureReconciliationPlan plan, FurnitureSnapshotState current) {
    if (plan.items().size() != current.items().size()) {
      throw new AssetConflictException(
          "Furniture reconciliation must contain every active FURNITURE position");
    }
    if (current.cabins().stream()
        .anyMatch(cabin -> cabin.getStatus().isTerminalDispositionStatus())) {
      throw new AssetConflictException(
          "Lost or written-off cabin cannot be overwritten by furniture reconciliation");
    }
    Map<UUID, InventoryFurnitureSnapshotItem> currentByEquipment =
        current.items().stream()
            .collect(Collectors.toMap(InventoryFurnitureSnapshotItem::equipmentId, Function.identity()));
    Set<UUID> currentCabinIds =
        current.cabins().stream()
            .map(RentalItem::getId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (!currentCabinIds.equals(new LinkedHashSet<>(plan.assetIds()))) {
      throw new AssetConflictException("Furniture reconciliation cabin scope is stale");
    }
    for (FurnitureReconciliationItemPlan item : plan.items()) {
      InventoryFurnitureSnapshotItem actual = currentByEquipment.get(item.equipmentId());
      if (actual == null || actual.catalogVersion() != item.catalogVersion()) {
        throw new AssetConflictException("Furniture reconciliation catalog version is stale");
      }
      Set<UUID> actualCabinIds =
          actual.cabins().stream()
              .map(InventoryFurnitureSnapshotCabin::assetId)
              .collect(Collectors.toCollection(LinkedHashSet::new));
      Set<UUID> requestedCabinIds =
          item.cabins().stream()
              .map(FurnitureReconciliationCabinPlan::assetId)
              .collect(Collectors.toCollection(LinkedHashSet::new));
      if (!actualCabinIds.equals(requestedCabinIds)) {
        throw new AssetConflictException("Furniture reconciliation cabin scope is stale");
      }
    }
  }

  /**
   * Ends every live binding invalidated by the reviewed absolute count while preserving its
   * durable row and ordinary lease/hold event history.
   */
  private void supersedeLiveFurnitureReconciliationGuards(FurnitureSnapshotState current) {
    List<UUID> assetIds =
        current.cabins().stream()
            .map(RentalItem::getId)
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
    List<UUID> equipmentIds =
        current.catalog().stream()
            .map(EquipmentCatalogItem::getId)
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
    OffsetDateTime timestamp = now();
    if (!assetIds.isEmpty()) {
      for (UUID assetId : assetIds) {
        leases.releaseForCompletedInventory(assetId);
      }
      List<OrderUnitReservation> unitReservations =
          orderReservations.findAllByRentalItemIdsAndStateForUpdate(
              assetIds, OrderUnitReservationState.ACTIVE);
      for (OrderUnitReservation reservation : unitReservations) {
        reservation.release(INVENTORY_SERVICE_ACTOR, "SYSTEM_ADMIN");
      }
      orderReservations.saveAllAndFlush(unitReservations);
      List<PresentationUnitHold> holds =
          presentationHolds.findAllByRentalItemIdsAndStateForUpdate(
              assetIds, PresentationUnitHoldState.ACTIVE);
      for (PresentationUnitHold hold : holds) {
        hold.release(timestamp);
      }
      presentationHolds.saveAllAndFlush(holds);
    }
    if (equipmentIds.isEmpty()) return;
    List<OrderEquipmentReservation> equipmentReservations =
        orderEquipmentReservations.findAllActiveByWarehouseAndEquipmentIdsForUpdate(
            current.warehouseId(), equipmentIds, OrderEquipmentReservationState.ACTIVE);
    for (OrderEquipmentReservation reservation : equipmentReservations) {
      reservation.release();
    }
    orderEquipmentReservations.saveAllAndFlush(equipmentReservations);
    List<UUID> balanceIds =
        current.balancesByKey().values().stream().map(EquipmentBalance::getId).toList();
    if (balanceIds.isEmpty()) {
      balanceIds = List.of(ABSENT_BALANCE_ID);
    }
    equipmentHolds.releaseForCompletedInventory(
        furnitureReconciliations.lockLiveEquipmentHoldIds(
            current.warehouseId(), equipmentIds, balanceIds, timestamp));
  }

  private Map<UUID, Long> lockFurnitureBalanceStreams(FurnitureSnapshotState current) {
    List<AssetEventStore.StreamRef> streams =
        current.balancesByKey().values().stream()
            .map(
                balance ->
                    new AssetEventStore.StreamRef(
                        AssetAggregateType.EQUIPMENT_BALANCE, balance.getId()))
            .toList();
    Map<AssetEventStore.StreamRef, Long> locked = events.lockStreams(streams);
    Map<UUID, Long> versions = new HashMap<>();
    for (EquipmentBalance balance : current.balancesByKey().values()) {
      Long streamVersion =
          locked.get(
              new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, balance.getId()));
      if (streamVersion == null || streamVersion != balance.getVersion()) {
        throw new AssetConflictException("Furniture balance stream changed concurrently");
      }
      versions.put(balance.getId(), streamVersion);
    }
    return Map.copyOf(versions);
  }

  private void applyFurnitureReconciliation(
      FurnitureReconciliationPlan plan,
      FurnitureSnapshotState current,
      Map<UUID, Long> streamVersions) {
    Map<BalanceKey, EquipmentBalance> balances = new HashMap<>(current.balancesByKey());
    Map<UUID, RentalItem> cabinsById =
        current.cabins().stream().collect(Collectors.toMap(RentalItem::getId, Function.identity()));
    for (FurnitureReconciliationItemPlan item : plan.items()) {
      replaceFurnitureBalanceQuantity(
          balances,
          streamVersions,
          new BalanceKey(item.equipmentId(), null, BalanceLocationKind.STOCK),
          plan.warehouseId(),
          item.stockQuantity());
      for (FurnitureReconciliationCabinPlan cabin : item.cabins()) {
        RentalItem asset = cabinsById.get(cabin.assetId());
        if (asset == null) {
          throw new AssetConflictException("Furniture reconciliation cabin scope is stale");
        }
        BalanceLocationKind selectedKind =
            asset.getStatus() == RentalItemStatus.RENTED
                ? BalanceLocationKind.CABIN_RENTED
                : BalanceLocationKind.CABIN_NON_RENTED;
        BalanceLocationKind oppositeKind =
            selectedKind == BalanceLocationKind.CABIN_RENTED
                ? BalanceLocationKind.CABIN_NON_RENTED
                : BalanceLocationKind.CABIN_RENTED;
        replaceFurnitureBalanceQuantity(
            balances,
            streamVersions,
            new BalanceKey(item.equipmentId(), asset.getId(), selectedKind),
            plan.warehouseId(),
            cabin.quantity());
        replaceFurnitureBalanceQuantity(
            balances,
            streamVersions,
            new BalanceKey(item.equipmentId(), asset.getId(), oppositeKind),
            plan.warehouseId(),
            0L);
      }
    }
  }

  private void replaceFurnitureBalanceQuantity(
      Map<BalanceKey, EquipmentBalance> balances,
      Map<UUID, Long> streamVersions,
      BalanceKey key,
      UUID warehouseId,
      long quantity) {
    EquipmentBalance current = balances.get(key);
    if (current == null) {
      if (quantity == 0) {
        return;
      }
      EquipmentBalance created =
          equipmentBalances.saveAndFlush(
              EquipmentBalance.create(
                  key.equipmentId(), warehouseId, key.assetId(), key.locationKind(), quantity));
      events.initialize(
          AssetAggregateType.EQUIPMENT_BALANCE,
          created.getId(),
          created.getVersion(),
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          furnitureBalanceFact(created),
          furnitureBalanceSnapshot(created));
      balances.put(key, created);
      return;
    }
    if (!current.replaceQuantity(quantity)) {
      return;
    }
    Long expectedStreamVersion = streamVersions.get(current.getId());
    if (expectedStreamVersion == null || expectedStreamVersion != current.getVersion()) {
      throw new AssetConflictException("Furniture balance stream changed concurrently");
    }
    EquipmentBalance changed = equipmentBalances.saveAndFlush(current);
    events.append(
        AssetAggregateType.EQUIPMENT_BALANCE,
        changed.getId(),
        expectedStreamVersion,
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        furnitureBalanceFact(changed),
        furnitureBalanceSnapshot(changed));
    balances.put(key, changed);
  }

  private static long balanceQuantity(Map<BalanceKey, EquipmentBalance> balances, BalanceKey key) {
    EquipmentBalance balance = balances.get(key);
    return balance == null ? 0L : balance.getQuantity();
  }

  private static long cabinQuantity(
      Map<BalanceKey, EquipmentBalance> balances, UUID equipmentId, UUID assetId) {
    try {
      return Math.addExact(
          balanceQuantity(
              balances,
              new BalanceKey(equipmentId, assetId, BalanceLocationKind.CABIN_NON_RENTED)),
          balanceQuantity(
              balances,
              new BalanceKey(equipmentId, assetId, BalanceLocationKind.CABIN_RENTED)));
    } catch (ArithmeticException exception) {
      throw new AssetConflictException("Furniture cabin balance quantity is invalid");
    }
  }

  private static Map<String, ?> furnitureBalanceFact(EquipmentBalance balance) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("balanceId", balance.getId().toString());
    value.put("equipmentId", balance.getEquipmentId().toString());
    value.put("warehouseId", balance.getWarehouseId().toString());
    value.put(
        "rentalItemId",
        balance.getRentalItemId() == null ? null : balance.getRentalItemId().toString());
    value.put("locationKind", balance.getLocationKind().name());
    value.put("quantity", balance.getQuantity());
    return value;
  }

  private static Map<String, ?> furnitureBalanceSnapshot(EquipmentBalance balance) {
    return furnitureBalanceFact(balance);
  }

  private void lockTransactionKeys(Collection<String> lockKeys) {
    lockKeys.stream()
        .filter(Objects::nonNull)
        .distinct()
        .sorted()
        .forEach(key -> furnitureReconciliations.acquireTransactionLock(key));
  }

  private static String rentalItemLockKey(UUID assetId) {
    return "asset-rental-item:" + assetId;
  }

  private static String leaseLockKey(UUID assetId) {
    return "lease:" + assetId;
  }

  private static String orderEquipmentLockKey(UUID warehouseId, UUID equipmentId) {
    return "order-equipment:" + warehouseId + ':' + equipmentId;
  }

  private static String balanceLockKey(
      UUID equipmentId, UUID warehouseId, UUID assetId, BalanceLocationKind locationKind) {
    return "asset-balance:"
        + equipmentId
        + ':'
        + warehouseId
        + ':'
        + (assetId == null ? "-" : assetId)
        + ':'
        + locationKind.name();
  }

  private static void registerConcurrentSafe(Runnable registration) {
    try {
      registration.run();
    } catch (DataIntegrityViolationException ignored) {
      // Another transaction registered the same immutable key; the caller locks and validates it.
    }
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  /** Canonical snapshot scope hashed to fence repeated inventory capture requests. */
  private record FurnitureSnapshotRequest(UUID warehouseId, List<UUID> assetIds) {}

  /**
   * Complete repeatable-read furniture state from which both the response and its content digest
   * are derived.
   */
  private record FurnitureSnapshotState(
      UUID warehouseId,
      List<RentalItem> cabins,
      List<EquipmentCatalogItem> catalog,
      Map<BalanceKey, EquipmentBalance> balancesByKey,
      List<InventoryFurnitureSnapshotItem> items,
      String snapshotSha256) {
    InventoryFurnitureSnapshot response() {
      return new InventoryFurnitureSnapshot(warehouseId, snapshotSha256, items);
    }
  }

  /** Stable warehouse-and-item material used to calculate the immutable snapshot digest. */
  private record FurnitureSnapshotFingerprint(
      UUID warehouseId, List<InventoryFurnitureSnapshotItem> items) {}

  /**
   * Normalized, version-fenced redistribution plan persisted and applied for one inventory review.
   */
  private record FurnitureReconciliationPlan(
      UUID warehouseId,
      String expectedSnapshotSha256,
      String reviewSha256,
      List<FurnitureReconciliationItemPlan> items,
      List<UUID> assetIds) {}

  /** Inventory identity and normalized plan hashed to detect conflicting reconciliation replay. */
  private record FurnitureReconciliationFingerprint(
      UUID inventoryId, FurnitureReconciliationPlan plan) {}

  /**
   * Per-catalog-item target distribution, fenced by catalog version and the expected stock
   * quantity.
   */
  private record FurnitureReconciliationItemPlan(
      UUID equipmentId,
      long catalogVersion,
      long stockQuantity,
      List<FurnitureReconciliationCabinPlan> cabins) {}

  /** Target quantity assigned to one cabin within an equipment reconciliation line. */
  private record FurnitureReconciliationCabinPlan(UUID assetId, long quantity) {}

  /**
   * Logical equipment location key used to compare captured and current balances independently of
   * persistence identifiers.
   */
  private record BalanceKey(UUID equipmentId, UUID assetId, BalanceLocationKind locationKind) {}
}
