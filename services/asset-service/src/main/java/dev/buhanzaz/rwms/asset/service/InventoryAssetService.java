package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentBalance;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetCapture;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureMember;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureOperation;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureState;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetNumberClaim;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSource;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceId;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceOperation;
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
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/** Exact Stage 7 boundary: stable reads plus one permanent inventory-source create. */
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

  private final RentalItemRepository rentalItems;
  private final EquipmentBalanceRepository equipmentBalances;
  private final EquipmentCatalogItemRepository equipmentCatalog;
  private final InventoryAssetCaptureOperationRepository captureOperations;
  private final InventoryAssetCaptureRepository captures;
  private final InventoryAssetCaptureMemberRepository captureMembers;
  private final InventoryAssetSourceOperationRepository sourceOperations;
  private final InventoryAssetSourceRepository sources;
  private final InventoryAssetNumberClaimRepository numberClaims;
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

    warehouses.requireActive(request.warehouseId());
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
    warehouses.requireActive(request.warehouseId());

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
