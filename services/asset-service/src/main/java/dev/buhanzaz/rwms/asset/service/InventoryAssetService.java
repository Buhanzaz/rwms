package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentBalance;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetCapture;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureMember;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureOperation;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureState;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetNumberClaim;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSource;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceId;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceOperation;
import dev.buhanzaz.rwms.asset.domain.InventoryFurnitureReconciliation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.repository.EquipmentBalanceRepository;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetCaptureMemberRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetCaptureOperationRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetCaptureRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetNumberClaimRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetSourceOperationRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetSourceRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryFurnitureReconciliationRepository;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/** Exact Stage 7 boundary: stable reads, permanent source creates, and furniture count truth. */
@Service
public class InventoryAssetService {
  private static final int MAX_PAGE_SIZE = 500;
  private static final Set<RentalItemStatus> CAPTURE_STATUSES = EnumSet.of(
      RentalItemStatus.BOOKED,
      RentalItemStatus.REPAIR,
      RentalItemStatus.WAITING_REPAIR_CHECK,
      RentalItemStatus.CAPITAL_REPAIR,
      RentalItemStatus.AFTER_RENT,
      RentalItemStatus.SALE,
      RentalItemStatus.USED_SALE,
      RentalItemStatus.RESERVED,
      RentalItemStatus.FREE,
      RentalItemStatus.WAREHOUSE,
      RentalItemStatus.OWN_NEEDS);
  private static final Set<BalanceLocationKind> CABIN_BALANCE_KINDS =
      EnumSet.of(BalanceLocationKind.CABIN_NON_RENTED, BalanceLocationKind.CABIN_RENTED);
  private static final Set<BalanceLocationKind> FURNITURE_SNAPSHOT_BALANCE_KINDS =
      EnumSet.of(
          BalanceLocationKind.STOCK,
          BalanceLocationKind.CABIN_NON_RENTED,
          BalanceLocationKind.CABIN_RENTED);
  /** Placeholder used only to keep a native IN predicate valid when no bucket exists yet. */
  private static final UUID ABSENT_BALANCE_ID = new UUID(0L, 0L);

  private final RentalItemRepository rentalItems;
  private final EquipmentBalanceRepository equipmentBalances;
  private final EquipmentCatalogItemRepository equipmentCatalog;
  private final InventoryAssetCaptureOperationRepository captureOperations;
  private final InventoryAssetCaptureRepository captures;
  private final InventoryAssetCaptureMemberRepository captureMembers;
  private final InventoryAssetSourceOperationRepository sourceOperations;
  private final InventoryAssetSourceRepository sources;
  private final InventoryAssetNumberClaimRepository numberClaims;
  private final InventoryFurnitureReconciliationRepository furnitureReconciliations;
  private final OrderUnitReservationRepository orderReservations;
  private final InventoryAssetBoundaryRegistrar registrar;
  private final AssetEventStore events;
  private final WarehouseRegistryClient warehouses;
  private final CabinCompositionService cabinComposition;
  private final ObjectMapper mapper;
  private final TransactionTemplate captureSnapshotTransaction;

  public InventoryAssetService(
      RentalItemRepository rentalItems,
      EquipmentBalanceRepository equipmentBalances,
      EquipmentCatalogItemRepository equipmentCatalog,
      InventoryAssetCaptureOperationRepository captureOperations,
      InventoryAssetCaptureRepository captures,
      InventoryAssetCaptureMemberRepository captureMembers,
      InventoryAssetSourceOperationRepository sourceOperations,
      InventoryAssetSourceRepository sources,
      InventoryAssetNumberClaimRepository numberClaims,
      InventoryFurnitureReconciliationRepository furnitureReconciliations,
      OrderUnitReservationRepository orderReservations,
      InventoryAssetBoundaryRegistrar registrar,
      AssetEventStore events,
      WarehouseRegistryClient warehouses,
      CabinCompositionService cabinComposition,
      ObjectMapper mapper,
      PlatformTransactionManager transactionManager) {
    this.rentalItems = rentalItems;
    this.equipmentBalances = equipmentBalances;
    this.equipmentCatalog = equipmentCatalog;
    this.captureOperations = captureOperations;
    this.captures = captures;
    this.captureMembers = captureMembers;
    this.sourceOperations = sourceOperations;
    this.sources = sources;
    this.numberClaims = numberClaims;
    this.furnitureReconciliations = furnitureReconciliations;
    this.orderReservations = orderReservations;
    this.registrar = registrar;
    this.events = events;
    this.warehouses = warehouses;
    this.cabinComposition = cabinComposition;
    this.mapper = mapper;
    this.captureSnapshotTransaction = new TransactionTemplate(transactionManager);
    this.captureSnapshotTransaction.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.captureSnapshotTransaction.setIsolationLevel(
        TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.captureSnapshotTransaction.setReadOnly(true);
  }

  @Transactional
  public InventoryCaptureResponse createCapture(InventoryCaptureRequest request) {
    registerConcurrentSafe(() -> registrar.registerCaptureOperation(
        request.operationId(), request.warehouseId(), request.requestFingerprint()));
    InventoryAssetCaptureOperation operation = captureOperations
        .findByIdForUpdate(request.operationId())
        .orElseThrow(() -> new IllegalStateException("Inventory capture operation registration failed"));
    if (!operation.getWarehouseId().equals(request.warehouseId())
        || !operation.getRequestFingerprint().equals(request.requestFingerprint())) {
      throw new AssetConflictException("Inventory capture operation is bound to another request");
    }

    expireCapturesNow();
    InventoryAssetCapture existing = captures
        .findByOperationIdAndTechnicalAttempt(request.operationId(), request.technicalAttempt())
        .orElse(null);
    if (existing != null) {
      if (!existing.getRequestFingerprint().equals(request.requestFingerprint())
          || !existing.getWarehouseId().equals(request.warehouseId())) {
        throw new AssetConflictException("Inventory capture attempt is bound to another request");
      }
      if (existing.getState() != InventoryAssetCaptureState.ACTIVE) {
        throw new AssetConflictException("Inventory capture attempt is no longer active");
      }
      return captureResponse(existing);
    }

    InventoryAssetCapture latest = captures
        .findFirstByOperationIdOrderByTechnicalAttemptDesc(request.operationId())
        .orElse(null);
    if (latest != null
        && (request.technicalAttempt() <= latest.getTechnicalAttempt()
            || latest.getState() != InventoryAssetCaptureState.EXPIRED)) {
      throw new AssetConflictException(
          "A new monotonic inventory capture attempt is allowed only after the previous attempt expires");
    }

    warehouses.requireIncoming(request.warehouseId());
    List<CaptureMemberRow> members = captureSnapshotTransaction.execute(
        ignored -> captureMemberRows(request.warehouseId()));
    String digest = canonicalHash(members.stream().map(CaptureMemberRow::digestValue).toList());
    InventoryAssetCapture capture = captures.saveAndFlush(InventoryAssetCapture.create(
        request.operationId(),
        request.technicalAttempt(),
        request.warehouseId(),
        request.requestFingerprint(),
        digest,
        members.size(),
        now()));
    captureMembers.saveAllAndFlush(members.stream()
        .map(member -> member.toEntity(capture.getCaptureId(), this::write))
        .toList());
    return captureResponse(capture);
  }

  @Transactional(readOnly = true)
  public InventoryCapturePage capturePage(UUID captureId, String cursor, int size) {
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("Inventory capture page size must be between 1 and 500");
    }
    InventoryAssetCapture capture = requireCapture(captureId);
    if (capture.getState() != InventoryAssetCaptureState.ACTIVE
        || !capture.getExpiresAt().isAfter(now())) {
      throw new AssetConflictException("Inventory capture is released or expired");
    }
    long after = decodeCursor(cursor, capture);
    List<InventoryAssetCaptureMember> fetched = captureMembers.pageAfter(
        captureId, after, PageRequest.of(0, size + 1));
    boolean hasMore = fetched.size() > size;
    List<InventoryAssetCaptureMember> page =
        hasMore ? List.copyOf(fetched.subList(0, size)) : fetched;
    List<InventoryCaptureMember> content = page.stream().map(this::memberResponse).toList();
    String nextCursor = hasMore
        ? encodeCursor(capture, content.getLast().sequence())
        : null;
    return new InventoryCapturePage(
        capture.getCaptureId(),
        capture.getOperationId(),
        capture.getTechnicalAttempt(),
        capture.getWarehouseId(),
        capture.getTotalCount(),
        capture.getMembershipDigest(),
        nextCursor,
        content);
  }

  @Transactional
  public void releaseCapture(UUID captureId) {
    InventoryAssetCapture capture = captures.findByIdForUpdate(captureId)
        .orElseThrow(() -> new AssetNotFoundException("Inventory capture was not found"));
    capture.release(now());
    captures.saveAndFlush(capture);
  }

  @Transactional(readOnly = true)
  public InventoryNumberResolutionResponse resolveNumber(InventoryNumberResolutionRequest request) {
    String display = RentalItem.canonicalNumber(request.number());
    String key = RentalItem.identityMatchKey(display);
    RentalItem item = rentalItems.findByWarehouseIdAndIdentityMatchKey(request.warehouseId(), key)
        .or(() -> rentalItems.findFirstByIdentityMatchKeyOrderByIdAsc(key))
        .orElse(null);
    return java.util.Optional.ofNullable(item)
        .map(value -> new InventoryNumberResolutionResponse(display, key, true, snapshot(value)))
        .orElseGet(() -> new InventoryNumberResolutionResponse(display, key, false, null));
  }

  /** Reads one current inventory-safe asset projection from one repeatable-read transaction. */
  public InventoryAssetCurrentSnapshot currentAssetSnapshot(UUID assetId) {
    return captureSnapshotTransaction.execute(ignored -> {
      RentalItem item = rentalItems.findById(assetId)
          .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
      String tenantSnapshot = activeTenantSnapshots(List.of(assetId)).get(assetId);
      List<EquipmentContentResponse> contentsSnapshot = List.copyOf(
          contentsByRentalItem(List.of(assetId)).getOrDefault(assetId, List.of()));
      return new InventoryAssetCurrentSnapshot(
          item.getId(),
          item.getVersion(),
          item.getWarehouseId(),
          item.getStatus(),
          item.getNumber(),
          item.getIdentityMatchKey(),
          tenantSnapshot,
          passportSnapshot(item, tenantSnapshot),
          contentsSnapshot);
    });
  }

  @Transactional(readOnly = true)
  public InventoryValidationResponse validateAssets(InventoryValidationRequest request) {
    if (Set.copyOf(request.assetIds()).size() != request.assetIds().size()) {
      throw new IllegalArgumentException("Inventory validation asset IDs must be unique");
    }
    List<UUID> ids = request.assetIds().stream().sorted().toList();
    Map<UUID, RentalItem> current = rentalItems.findAllById(ids).stream()
        .collect(Collectors.toMap(RentalItem::getId, Function.identity()));
    Map<UUID, String> tenants = activeTenantSnapshots(ids);
    Map<UUID, List<EquipmentContentResponse>> contents = contentsByRentalItem(ids);
    List<InventoryValidationItem> values = ids.stream()
        .map(id -> {
          RentalItem item = current.get(id);
          return item == null
              ? new InventoryValidationItem(
                  id, false, null, null, null, null, null, null, null, null)
              : new InventoryValidationItem(
                  id,
                  true,
                  item.getVersion(),
                  item.getWarehouseId(),
                  item.getStatus(),
                  item.getNumber(),
                  item.getIdentityMatchKey(),
                  tenants.get(id),
                  passportSnapshot(item, tenants.get(id)),
                  List.copyOf(contents.getOrDefault(id, List.of())));
        })
        .toList();
    return new InventoryValidationResponse(now(), canonicalHash(values), values);
  }

  /**
   * Returns all currently active FURNITURE positions and only the caller-selected cabins.
   * The hash is over the canonical response body without its self-referential hash field.
   */
  public InventoryFurnitureSnapshot furnitureSnapshot(InventoryFurnitureSnapshotRequest request) {
    FurnitureSnapshotRequest normalized = normalizeFurnitureSnapshotRequest(request);
    warehouses.requireIncoming(normalized.warehouseId());
    FurnitureSnapshotState snapshot = Objects.requireNonNull(
        captureSnapshotTransaction.execute(
            ignored -> currentFurnitureSnapshot(
                normalized.warehouseId(), normalized.assetIds())));
    return snapshot.response();
  }

  /**
   * Applies one reviewed absolute furniture count. The durable inventory identity is registered
   * before balance locks so a retry remains a replay even after ordinary idempotency retention.
   */
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public FurnitureReconciliationResult reconcileFurniture(
      UUID inventoryId,
      UUID idempotencyKey,
      InventoryFurnitureReconciliationRequest request) {
    if (inventoryId == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Inventory furniture reconciliation identity is required");
    }
    FurnitureReconciliationPlan plan = normalizeFurnitureReconciliationRequest(request);
    warehouses.requireIncoming(plan.warehouseId());
    String requestSha256 = canonicalHash(
        new FurnitureReconciliationFingerprint(inventoryId, plan));

    registerConcurrentSafe(
        () -> registrar.registerFurnitureReconciliation(inventoryId, requestSha256, idempotencyKey));
    InventoryFurnitureReconciliation source = furnitureReconciliations
        .findByInventoryIdForUpdate(inventoryId)
        .orElseThrow(
            () -> new IllegalStateException("Inventory furniture reconciliation registration failed"));
    if (!source.getRequestSha256().equals(requestSha256)) {
      throw new AssetConflictException(
          "Inventory furniture reconciliation identity is bound to another request");
    }
    if (source.isCompleted()) {
      return new FurnitureReconciliationResult(true);
    }

    FurnitureSnapshotState current = lockedFurnitureSnapshot(
        plan.warehouseId(), plan.assetIds());
    if (!current.snapshotSha256().equals(plan.expectedSnapshotSha256())) {
      throw new AssetConflictException("Furniture snapshot is stale");
    }
    assertFurnitureReconciliationMatchesCurrent(plan, current);
    assertNoLiveFurnitureReconciliationGuard(current);

    Map<UUID, Long> streamVersions = lockFurnitureBalanceStreams(current);
    applyFurnitureReconciliation(plan, current, streamVersions);
    if (!source.complete()) {
      throw new AssetConflictException("Inventory furniture reconciliation was already completed");
    }
    furnitureReconciliations.saveAndFlush(source);
    return new FurnitureReconciliationResult(false);
  }

  @Transactional
  public CreateResult<InventorySourceAssetResponse> createSourceAsset(
      InventorySourceAssetRequest request) {
    String passportJson = write(request.passport() == null ? Map.of() : request.passport());
    String tagsJson = write(request.tags() == null ? List.of() : request.tags());
    CabinCompositionService.CabinSelection selection = cabinComposition.requireSelection(
        request.rentalTypeId(),
        request.dimensionId(),
        request.finishingId(),
        request.characteristicIds());
    CabinCompositionService.CategorySelection category =
        cabinComposition.requireCategory(request.category());
    RentalItem candidate = RentalItem.createFromInventory(
        request.warehouseId(),
        request.number(),
        selection.rentalTypeId(),
        selection.dimensionId(),
        selection.finishingId(),
        category.id(),
        category.name(),
        request.linoleum(),
        passportJson,
        tagsJson);
    String fingerprint = canonicalHash(sourceFingerprint(request, candidate));
    warehouses.requireIncoming(request.warehouseId());

    InventoryAssetSourceId sourceId =
        new InventoryAssetSourceId(request.inventoryId(), request.findingId());
    registerConcurrentSafe(() -> registrar.registerSourceOperation(sourceId, fingerprint));
    String registeredFingerprint = sourceOperations.findRequestFingerprintById(sourceId)
        .orElseThrow(() -> new IllegalStateException("Inventory source registration failed"));
    if (!registeredFingerprint.equals(fingerprint)) {
      throw new AssetConflictException("Inventory source identity is bound to another asset request");
    }
    registerConcurrentSafe(() -> registrar.claimNumber(
        candidate.getWarehouseId(), candidate.getIdentityMatchKey(), sourceId));
    InventoryAssetSourceOperation operation = sourceOperations.findByIdForUpdate(sourceId)
        .orElseThrow(() -> new IllegalStateException("Inventory source registration failed"));
    if (!operation.getRequestFingerprint().equals(fingerprint)) {
      throw new AssetConflictException("Inventory source identity is bound to another asset request");
    }
    InventoryAssetNumberClaim claim = numberClaims
        .findByWarehouseIdAndIdentityMatchKeyForUpdate(
            candidate.getWarehouseId(), candidate.getIdentityMatchKey())
        .orElseThrow(() -> new IllegalStateException("Inventory number claim registration failed"));
    if (!claim.belongsTo(sourceId)) {
      throw new AssetConflictException(
          "Rental item number identity is already used in this warehouse");
    }
    InventoryAssetSource stored = sources.findById(sourceId).orElse(null);
    if (stored != null) {
      if (!stored.getRequestFingerprint().equals(fingerprint)) {
        throw new AssetConflictException(
            "Inventory source identity is bound to another asset request");
      }
      return new CreateResult<>(
          read(stored.getResponseBody(), InventorySourceAssetResponse.class), true);
    }
    if (rentalItems.existsByWarehouseIdAndIdentityMatchKey(
        candidate.getWarehouseId(), candidate.getIdentityMatchKey())) {
      throw new AssetConflictException(
          "Rental item number identity is already used in this warehouse");
    }

    RentalItem saved = rentalItems.saveAndFlush(candidate);
    cabinComposition.replaceRentalItemCharacteristics(
        saved.getId(), selection.characteristicIds());
    events.initialize(
        AssetAggregateType.RENTAL_ITEM,
        saved.getId(),
        saved.getVersion(),
        AssetEventType.RENTAL_ITEM_CREATED,
        rentalFact(saved),
        rentalSnapshot(saved));
    InventorySourceAssetResponse response = new InventorySourceAssetResponse(
        request.inventoryId(), request.findingId(), snapshot(saved));
    sources.saveAndFlush(
        InventoryAssetSource.complete(sourceId, fingerprint, saved.getId(), write(response)));
    return new CreateResult<>(response, false);
  }

  @Scheduled(fixedDelayString = "${rwms.asset.inventory-capture-cleanup-delay:PT1M}")
  @Transactional
  public void expireOrphanCaptures() {
    expireCapturesNow();
  }

  private void expireCapturesNow() {
    captures.expireActiveBefore(
        InventoryAssetCaptureState.ACTIVE, InventoryAssetCaptureState.EXPIRED, now());
  }

  private List<CaptureMemberRow> captureMemberRows(UUID warehouseId) {
    List<RentalItem> items = rentalItems
        .findAllByWarehouseIdAndStatusInOrderByIdentityMatchKeyAscIdAsc(
            warehouseId, CAPTURE_STATUSES);
    List<UUID> rentalItemIds = items.stream().map(RentalItem::getId).toList();
    Map<UUID, String> tenants = activeTenantSnapshots(rentalItemIds);
    Map<UUID, List<EquipmentContentResponse>> contentsByRentalItem =
        contentsByRentalItem(rentalItemIds);

    List<CaptureMemberRow> result = new ArrayList<>(items.size());
    for (int index = 0; index < items.size(); index++) {
      RentalItem item = items.get(index);
      result.add(new CaptureMemberRow(
          index,
          item.getId(),
          item.getVersion(),
          item.getWarehouseId(),
          item.getStatus(),
          item.getNumber(),
          item.getIdentityMatchKey(),
          passportSnapshot(item, tenants.get(item.getId())),
          List.copyOf(contentsByRentalItem.getOrDefault(item.getId(), List.of()))));
    }
    return List.copyOf(result);
  }

  private Map<UUID, List<EquipmentContentResponse>> contentsByRentalItem(
      List<UUID> rentalItemIds) {
    if (rentalItemIds.isEmpty()) return Map.of();
    List<EquipmentBalance> balances =
        equipmentBalances.findAllByRentalItemIdInAndQuantityGreaterThanAndLocationKindIn(
            rentalItemIds, 0, CABIN_BALANCE_KINDS);
    Map<UUID, EquipmentCatalogItem> equipmentItems = equipmentCatalog
        .findAllById(balances.stream().map(EquipmentBalance::getEquipmentId).collect(Collectors.toSet()))
        .stream()
        .collect(Collectors.toMap(EquipmentCatalogItem::getId, item -> item));
    return balances.stream()
        .map(balance -> {
          EquipmentCatalogItem item = requireEquipment(equipmentItems, balance.getEquipmentId());
          return new EquipmentContentWithOwner(
              balance.getRentalItemId(),
              new EquipmentContentResponse(
                  balance.getEquipmentId(),
                  item.getName(),
                  balance.getQuantity(),
                  balance.getLocationKind()));
        })
        .sorted(Comparator.comparing((EquipmentContentWithOwner value) ->
                value.content().equipmentName())
            .thenComparing(value -> value.content().equipmentId()))
        .collect(Collectors.groupingBy(
            EquipmentContentWithOwner::rentalItemId,
            LinkedHashMap::new,
            Collectors.mapping(EquipmentContentWithOwner::content, Collectors.toList())));
  }

  private FurnitureSnapshotState currentFurnitureSnapshot(
      UUID warehouseId, List<UUID> assetIds) {
    List<RentalItem> cabins = selectedFurnitureCabins(warehouseId, assetIds, false);
    List<EquipmentCatalogItem> catalog = equipmentCatalog
        .findAllByCategoryAndActiveTrueOrderByNameAscIdAsc(EquipmentCategory.FURNITURE);
    List<EquipmentBalance> balances = catalog.isEmpty()
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
  private FurnitureSnapshotState lockedFurnitureSnapshot(
      UUID warehouseId, List<UUID> assetIds) {
    lockTransactionKeys(
        assetIds.stream().map(InventoryAssetService::rentalItemLockKey).toList());
    lockTransactionKeys(assetIds.stream().map(InventoryAssetService::leaseLockKey).toList());
    List<RentalItem> cabins = selectedFurnitureCabins(warehouseId, assetIds, true);
    List<EquipmentCatalogItem> catalog = equipmentCatalog
        .findAllActiveByCategoryForUpdate(EquipmentCategory.FURNITURE);
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
      balanceLockKeys.add(balanceLockKey(
          equipmentId, warehouseId, null, BalanceLocationKind.STOCK));
      for (RentalItem cabin : cabins) {
        balanceLockKeys.add(balanceLockKey(
            equipmentId,
            warehouseId,
            cabin.getId(),
            BalanceLocationKind.CABIN_NON_RENTED));
        balanceLockKeys.add(balanceLockKey(
            equipmentId,
            warehouseId,
            cabin.getId(),
            BalanceLocationKind.CABIN_RENTED));
      }
    }
    lockTransactionKeys(balanceLockKeys);
    List<EquipmentBalance> balances = assetIds.isEmpty()
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
      BalanceKey key = new BalanceKey(
          balance.getEquipmentId(), balance.getRentalItemId(), balance.getLocationKind());
      if (balancesByKey.putIfAbsent(key, balance) != null) {
        throw new IllegalStateException("Furniture balance location uniqueness is corrupted");
      }
    }
    List<RentalItem> orderedCabins = cabins.stream()
        .sorted(Comparator.comparing(RentalItem::getNumber).thenComparing(RentalItem::getId))
        .toList();
    List<EquipmentCatalogItem> orderedCatalog = catalog.stream()
        .sorted(Comparator.comparing(EquipmentCatalogItem::getName)
            .thenComparing(EquipmentCatalogItem::getId))
        .toList();
    List<InventoryFurnitureSnapshotItem> items = orderedCatalog.stream()
        .map(equipment -> {
          EquipmentBalance stockBalance = balancesByKey.get(
              new BalanceKey(equipment.getId(), null, BalanceLocationKind.STOCK));
          return new InventoryFurnitureSnapshotItem(
              equipment.getId(),
              equipment.getVersion(),
              equipment.getName(),
              stockBalance == null ? 0L : stockBalance.getQuantity(),
              stockBalance == null ? null : stockBalance.getVersion(),
              orderedCabins.stream()
                  .map(
                      cabin -> new InventoryFurnitureSnapshotCabin(
                          cabin.getId(),
                          cabin.getVersion(),
                          cabin.getNumber(),
                          cabin.getStatus(),
                          cabinQuantity(balancesByKey, equipment.getId(), cabin.getId())))
                  .toList());
        })
        .toList();
    String snapshotSha256 = canonicalHash(new FurnitureSnapshotFingerprint(warehouseId, items));
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
    List<RentalItem> selected = forUpdate
        ? rentalItems.findAllByIdInForUpdate(assetIds)
        : rentalItems.findAllById(assetIds);
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
        throw new IllegalArgumentException("Inventory furniture reconciliation equipment is duplicated");
      }
      List<FurnitureReconciliationCabinPlan> cabins = normalizedFurnitureCabins(item.cabins());
      List<UUID> assetIds = cabins.stream()
          .map(FurnitureReconciliationCabinPlan::assetId)
          .toList();
      if (commonAssetIds == null) {
        commonAssetIds = assetIds;
      } else if (!commonAssetIds.equals(assetIds)) {
        throw new IllegalArgumentException(
            "Every furniture reconciliation item must cover the same cabins");
      }
      items.add(new FurnitureReconciliationItemPlan(
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

  private void assertFurnitureReconciliationMatchesCurrent(
      FurnitureReconciliationPlan plan, FurnitureSnapshotState current) {
    if (plan.items().size() != current.items().size()) {
      throw new AssetConflictException(
          "Furniture reconciliation must contain every active FURNITURE position");
    }
    Map<UUID, InventoryFurnitureSnapshotItem> currentByEquipment = current.items().stream()
        .collect(Collectors.toMap(InventoryFurnitureSnapshotItem::equipmentId, Function.identity()));
    Set<UUID> currentCabinIds = current.cabins().stream()
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
      Set<UUID> actualCabinIds = actual.cabins().stream()
          .map(InventoryFurnitureSnapshotCabin::assetId)
          .collect(Collectors.toCollection(LinkedHashSet::new));
      Set<UUID> requestedCabinIds = item.cabins().stream()
          .map(FurnitureReconciliationCabinPlan::assetId)
          .collect(Collectors.toCollection(LinkedHashSet::new));
      if (!actualCabinIds.equals(requestedCabinIds)) {
        throw new AssetConflictException("Furniture reconciliation cabin scope is stale");
      }
    }
  }

  /** Locks and rejects every live source that could make an absolute count unsafe. */
  private void assertNoLiveFurnitureReconciliationGuard(FurnitureSnapshotState current) {
    List<UUID> assetIds = current.cabins().stream().map(RentalItem::getId).toList();
    List<UUID> equipmentIds = current.catalog().stream().map(EquipmentCatalogItem::getId).toList();
    OffsetDateTime timestamp = now();
    if (!assetIds.isEmpty()) {
      if (!furnitureReconciliations.lockLiveOperationLeaseIds(assetIds, timestamp).isEmpty()) {
        throw new AssetConflictException(
            "Furniture reconciliation cannot overwrite a cabin with an active operation lease");
      }
      if (!furnitureReconciliations.lockActiveOrderUnitReservationIds(assetIds).isEmpty()) {
        throw new AssetConflictException(
            "Furniture reconciliation cannot overwrite a cabin with an active order reservation");
      }
      if (!furnitureReconciliations.lockLivePresentationHoldIds(assetIds, timestamp).isEmpty()) {
        throw new AssetConflictException(
            "Furniture reconciliation cannot overwrite a cabin with an active presentation hold");
      }
    }
    if (!furnitureReconciliations.lockActiveOrderEquipmentReservationIds(
        current.warehouseId(), equipmentIds).isEmpty()) {
      throw new AssetConflictException(
          "Furniture reconciliation cannot overwrite an active equipment reservation");
    }
    List<UUID> balanceIds = current.balancesByKey().values().stream()
        .map(EquipmentBalance::getId)
        .toList();
    if (balanceIds.isEmpty()) {
      balanceIds = List.of(ABSENT_BALANCE_ID);
    }
    if (!furnitureReconciliations.lockLiveEquipmentHoldIds(
        current.warehouseId(), equipmentIds, balanceIds, timestamp).isEmpty()) {
      throw new AssetConflictException(
          "Furniture reconciliation cannot overwrite quantities protected by an active hold");
    }
  }

  private Map<UUID, Long> lockFurnitureBalanceStreams(FurnitureSnapshotState current) {
    List<AssetEventStore.StreamRef> streams = current.balancesByKey().values().stream()
        .map(balance -> new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, balance.getId()))
        .toList();
    Map<AssetEventStore.StreamRef, Long> locked = events.lockStreams(streams);
    Map<UUID, Long> versions = new HashMap<>();
    for (EquipmentBalance balance : current.balancesByKey().values()) {
      Long streamVersion = locked.get(
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
    Map<UUID, RentalItem> cabinsById = current.cabins().stream()
        .collect(Collectors.toMap(RentalItem::getId, Function.identity()));
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
        BalanceLocationKind selectedKind = asset.getStatus() == RentalItemStatus.RENTED
            ? BalanceLocationKind.CABIN_RENTED
            : BalanceLocationKind.CABIN_NON_RENTED;
        BalanceLocationKind oppositeKind = selectedKind == BalanceLocationKind.CABIN_RENTED
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
      EquipmentBalance created = equipmentBalances.saveAndFlush(EquipmentBalance.create(
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

  private static long balanceQuantity(
      Map<BalanceKey, EquipmentBalance> balances, BalanceKey key) {
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
      UUID equipmentId,
      UUID warehouseId,
      UUID assetId,
      BalanceLocationKind locationKind) {
    return "asset-balance:"
        + equipmentId
        + ':'
        + warehouseId
        + ':'
        + (assetId == null ? "-" : assetId)
        + ':'
        + locationKind.name();
  }

  private static EquipmentCatalogItem requireEquipment(
      Map<UUID, EquipmentCatalogItem> items, UUID equipmentId) {
    EquipmentCatalogItem value = items.get(equipmentId);
    if (value == null) {
      throw new IllegalStateException("Inventory capture equipment catalog reference is missing");
    }
    return value;
  }

  private Map<String, Object> passportSnapshot(RentalItem item, String tenantSnapshot) {
    CabinCompositionService.CabinComposition composition =
        cabinComposition.compositionsFor(List.of(item)).get(item.getId());
    Map<String, Object> value = new LinkedHashMap<>();
    value.put(
        "rentalType",
        composition == null || composition.rentalType() == null
            ? null
            : composition.rentalType().name());
    value.put(
        "dimensions",
        composition == null || composition.dimensions() == null
            ? null
            : composition.dimensions().name());
    value.put(
        "finishing",
        composition == null || composition.finishing() == null
            ? null
            : composition.finishing().name());
    value.put("category", item.getCategory());
    value.put(
        "characteristics",
        composition == null
            ? List.of()
            : composition.characteristics().stream().map(CabinCatalogValueResponse::name).toList());
    value.put("linoleum", item.getLinoleum());
    value.put("passport", read(item.getPassportJson(), new TypeReference<Map<String, Object>>() {}));
    value.put("tags", read(item.getTagsJson(), new TypeReference<List<String>>() {}));
    value.put("tenant", tenantSnapshot);
    return value;
  }

  private Map<UUID, String> activeTenantSnapshots(List<UUID> rentalItemIds) {
    if (rentalItemIds.isEmpty()) return Map.of();
    return orderReservations
        .findAllByRentalItemIdInAndState(rentalItemIds, OrderUnitReservationState.ACTIVE)
        .stream()
        .filter(reservation -> reservation.getTenantSnapshot() != null)
        .collect(
            Collectors.toMap(
                OrderUnitReservation::getRentalItemId,
                OrderUnitReservation::getTenantSnapshot,
                (left, right) -> left));
  }

  private InventoryCaptureMember memberResponse(InventoryAssetCaptureMember member) {
    return new InventoryCaptureMember(
        member.getId().getSequenceNo(),
        member.getAssetId(),
        member.getAssetVersion(),
        member.getWarehouseId(),
        member.getStatus(),
        member.getDisplayCanonicalNumber(),
        member.getIdentityMatchKey(),
        read(member.getPassportSnapshot(), new TypeReference<Map<String, Object>>() {}),
        read(member.getContentsSnapshot(),
            new TypeReference<List<EquipmentContentResponse>>() {}));
  }

  private InventoryAssetCapture requireCapture(UUID captureId) {
    return captures.findById(captureId)
        .orElseThrow(() -> new AssetNotFoundException("Inventory capture was not found"));
  }

  private static InventoryCaptureResponse captureResponse(InventoryAssetCapture capture) {
    return new InventoryCaptureResponse(
        capture.getCaptureId(),
        capture.getOperationId(),
        capture.getTechnicalAttempt(),
        capture.getWarehouseId(),
        capture.getTotalCount(),
        capture.getMembershipDigest(),
        capture.getCreatedAt(),
        capture.getExpiresAt());
  }

  private InventoryAssetSnapshot snapshot(RentalItem item) {
    String tenant =
        orderReservations
            .findByRentalItemIdAndState(item.getId(), OrderUnitReservationState.ACTIVE)
            .map(OrderUnitReservation::getTenantSnapshot)
            .orElse(null);
    return new InventoryAssetSnapshot(
        item.getId(),
        item.getVersion(),
        item.getWarehouseId(),
        item.getStatus(),
        item.getNumber(),
        item.getIdentityMatchKey(),
        tenant);
  }

  private Map<String, ?> sourceFingerprint(InventorySourceAssetRequest request, RentalItem item) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("inventoryId", request.inventoryId());
    value.put("findingId", request.findingId());
    value.put("warehouseId", item.getWarehouseId());
    value.put("displayCanonicalNumber", item.getNumber());
    value.put("identityMatchKey", item.getIdentityMatchKey());
    value.put("rentalTypeId", item.getRentalTypeId());
    value.put("dimensionId", item.getDimensionId());
    value.put("finishingId", item.getFinishingId());
    value.put("category", item.getCategory());
    value.put("characteristicIds", request.characteristicIds());
    value.put("linoleum", item.getLinoleum());
    value.put("passport", request.passport() == null ? Map.of() : request.passport());
    value.put("tags", request.tags() == null ? List.of() : request.tags());
    return value;
  }

  private static Map<String, ?> rentalFact(RentalItem item) {
    return Map.of(
        "rentalItemId", item.getId().toString(),
        "warehouseId", item.getWarehouseId().toString(),
        "status", item.getStatus().name(),
        "numberSha256", AssetChecksum.sha256(item.getNumber().getBytes(StandardCharsets.UTF_8)));
  }

  private Map<String, ?> rentalSnapshot(RentalItem item) {
    Map<String, Object> value = new LinkedHashMap<>();
    CabinCompositionService.CabinComposition composition =
        cabinComposition.compositionsFor(List.of(item)).get(item.getId());
    value.put("rentalItemId", item.getId().toString());
    value.put("version", item.getVersion());
    value.put("warehouseId", item.getWarehouseId().toString());
    value.put("number", item.getNumber());
    value.put("identityMatchKey", item.getIdentityMatchKey());
    value.put("status", item.getStatus().name());
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
    value.put("passport", read(item.getPassportJson(), new TypeReference<Map<String, Object>>() {}));
    value.put("tags", read(item.getTagsJson(), new TypeReference<List<String>>() {}));
    return value;
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private String canonicalHash(Object value) {
    try {
      String canonical = mapper.writer()
          .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .writeValueAsString(value);
      return AssetChecksum.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory asset value cannot be canonicalized", exception);
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory asset value cannot be serialized", exception);
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored inventory asset value is corrupt", exception);
    }
  }

  private <T> T read(String value, TypeReference<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored inventory asset value is corrupt", exception);
    }
  }

  private static void registerConcurrentSafe(Runnable registration) {
    try {
      registration.run();
    } catch (DataIntegrityViolationException ignored) {
      // Another transaction registered the same immutable key; the caller locks and validates it.
    }
  }

  private static String encodeCursor(InventoryAssetCapture capture, long sequence) {
    String payload = capture.getCaptureId()
        + "."
        + capture.getMembershipDigest()
        + "."
        + sequence
        + "."
        + cursorSignature(capture, sequence);
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
  }

  private static long decodeCursor(String cursor, InventoryAssetCapture capture) {
    if (cursor == null || cursor.isBlank()) {
      return -1;
    }
    try {
      String decoded = new String(
          Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
      String[] parts = decoded.split("\\.", -1);
      if (parts.length != 4) {
        throw new IllegalArgumentException("Malformed capture cursor");
      }
      UUID cursorCaptureId = UUID.fromString(parts[0]);
      long sequence = Long.parseLong(parts[2]);
      if (sequence < 0) {
        throw new IllegalArgumentException("Negative capture cursor");
      }
      if (!capture.getCaptureId().equals(cursorCaptureId)
          || !capture.getMembershipDigest().equals(parts[1])
          || !cursorSignature(capture, sequence).equals(parts[3])) {
        throw new AssetConflictException("Inventory capture cursor does not belong to this capture");
      }
      return sequence;
    } catch (AssetConflictException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Inventory capture cursor is invalid", exception);
    }
  }

  private static String cursorSignature(InventoryAssetCapture capture, long sequence) {
    String value = capture.getCaptureId()
        + ":"
        + capture.getMembershipDigest()
        + ":"
        + sequence
        + ":"
        + capture.getRequestFingerprint();
    return AssetChecksum.sha256(value.getBytes(StandardCharsets.US_ASCII));
  }

  public record CreateResult<T>(T response, boolean replayed) {}

  public record FurnitureReconciliationResult(boolean replayed) {}

  private record FurnitureSnapshotRequest(UUID warehouseId, List<UUID> assetIds) {}

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

  private record FurnitureSnapshotFingerprint(
      UUID warehouseId, List<InventoryFurnitureSnapshotItem> items) {}

  private record FurnitureReconciliationPlan(
      UUID warehouseId,
      String expectedSnapshotSha256,
      String reviewSha256,
      List<FurnitureReconciliationItemPlan> items,
      List<UUID> assetIds) {}

  private record FurnitureReconciliationFingerprint(
      UUID inventoryId, FurnitureReconciliationPlan plan) {}

  private record FurnitureReconciliationItemPlan(
      UUID equipmentId,
      long catalogVersion,
      long stockQuantity,
      List<FurnitureReconciliationCabinPlan> cabins) {}

  private record FurnitureReconciliationCabinPlan(UUID assetId, long quantity) {}

  private record BalanceKey(
      UUID equipmentId, UUID assetId, BalanceLocationKind locationKind) {}

  private record EquipmentContentWithOwner(
      UUID rentalItemId, EquipmentContentResponse content) {}

  private record CaptureMemberRow(
      long sequence,
      UUID assetId,
      long version,
      UUID warehouseId,
      RentalItemStatus status,
      String displayCanonicalNumber,
      String identityMatchKey,
      Map<String, Object> passportSnapshot,
      List<EquipmentContentResponse> contentsSnapshot) {
    InventoryAssetCaptureMember toEntity(
        UUID captureId, Function<Object, String> serializer) {
      return InventoryAssetCaptureMember.create(
          captureId,
          sequence,
          assetId,
          version,
          warehouseId,
          status,
          displayCanonicalNumber,
          identityMatchKey,
          serializer.apply(passportSnapshot),
          serializer.apply(contentsSnapshot));
    }

    Map<String, Object> digestValue() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("sequence", sequence);
      value.put("assetId", assetId);
      value.put("version", version);
      value.put("warehouseId", warehouseId);
      value.put("status", status);
      value.put("displayCanonicalNumber", displayCanonicalNumber);
      value.put("identityMatchKey", identityMatchKey);
      value.put("passportSnapshot", passportSnapshot);
      value.put("contentsSnapshot", contentsSnapshot);
      return value;
    }
  }
}
