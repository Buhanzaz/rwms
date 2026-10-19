package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentBalance;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureMember;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.repository.EquipmentBalanceRepository;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

/**
 * Builds inventory-safe asset projections and the immutable material captured by inventory
 * attempts.
 *
 * <p>This service owns one mapping truth for tenant snapshots, passport/catalog detail, and cabin
 * contents. It performs no durable capture/source/furniture command transition itself.
 */
@Service
final class InventoryAssetProjectionService {
  private static final Set<RentalItemStatus> CAPTURE_STATUSES =
      EnumSet.of(
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
  private final OrderUnitReservationRepository orderReservations;
  private final CabinCompositionService cabinComposition;
  private final InventoryAssetCodec codec;
  private final InventoryAssetSnapshotTransaction snapshotTransaction;
  private final InventoryAssetSourceService sources;

  InventoryAssetProjectionService(
      RentalItemRepository rentalItems,
      EquipmentBalanceRepository equipmentBalances,
      EquipmentCatalogItemRepository equipmentCatalog,
      OrderUnitReservationRepository orderReservations,
      CabinCompositionService cabinComposition,
      InventoryAssetCodec codec,
      InventoryAssetSnapshotTransaction snapshotTransaction,
      InventoryAssetSourceService sources) {
    this.rentalItems = rentalItems;
    this.equipmentBalances = equipmentBalances;
    this.equipmentCatalog = equipmentCatalog;
    this.orderReservations = orderReservations;
    this.cabinComposition = cabinComposition;
    this.codec = codec;
    this.snapshotTransaction = snapshotTransaction;
    this.sources = sources;
  }

  List<CaptureMemberRow> captureMemberSnapshot(UUID warehouseId) {
    return snapshotTransaction.execute(() -> captureMemberRows(warehouseId));
  }

  InventoryAssetCurrentSnapshot currentAssetSnapshot(UUID assetId) {
    return snapshotTransaction.execute(
        () -> {
          RentalItem item =
              rentalItems
                  .findById(assetId)
                  .or(() -> sources.findHeldLegacySourceItem(assetId))
                  .orElse(null);
          if (item == null) {
            InventoryAssetCurrentSnapshot proposal = sources.currentProposal(assetId);
            if (proposal != null) return proposal;
            throw new AssetNotFoundException("Rental item was not found");
          }
          String tenantSnapshot = activeTenantSnapshots(List.of(assetId)).get(assetId);
          List<EquipmentContentResponse> contentsSnapshot =
              List.copyOf(contentsByRentalItem(List.of(assetId)).getOrDefault(assetId, List.of()));
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

  InventoryNumberResolutionResponse resolveNumber(InventoryNumberResolutionRequest request) {
    String display = RentalItem.canonicalNumber(request.number());
    String key = RentalItem.identityMatchKey(display);
    RentalItem item =
        rentalItems
            .findByWarehouseIdAndIdentityMatchKey(request.warehouseId(), key)
            .or(() -> rentalItems.findFirstByIdentityMatchKeyOrderByIdAsc(key))
            .orElse(null);
    return java.util.Optional.ofNullable(item)
        .map(value -> new InventoryNumberResolutionResponse(display, key, true, snapshot(value)))
        .orElseGet(() -> new InventoryNumberResolutionResponse(display, key, false, null));
  }

  InventoryValidationResponse validateAssets(InventoryValidationRequest request) {
    if (Set.copyOf(request.assetIds()).size() != request.assetIds().size()) {
      throw new IllegalArgumentException("Inventory validation asset IDs must be unique");
    }
    List<UUID> ids = request.assetIds().stream().sorted().toList();
    Map<UUID, RentalItem> current =
        rentalItems.findAllById(ids).stream()
            .collect(Collectors.toMap(RentalItem::getId, Function.identity()));
    sources
        .findHeldLegacySourceItems(
            ids.stream().filter(id -> !current.containsKey(id)).toList(), false)
        .forEach(item -> current.put(item.getId(), item));
    Map<UUID, String> tenants = activeTenantSnapshots(ids);
    Map<UUID, List<EquipmentContentResponse>> contents = contentsByRentalItem(ids);
    List<InventoryValidationItem> values =
        ids.stream()
            .map(
                id -> {
                  RentalItem item = current.get(id);
                  if (item == null) {
                    InventoryAssetCurrentSnapshot proposal = sources.currentProposal(id);
                    return proposal == null
                        ? new InventoryValidationItem(
                            id, false, null, null, null, null, null, null, null, null)
                        : new InventoryValidationItem(
                            id,
                            true,
                            proposal.version(),
                            proposal.warehouseId(),
                            proposal.status(),
                            proposal.displayCanonicalNumber(),
                            proposal.identityMatchKey(),
                            proposal.tenantSnapshot(),
                            proposal.passportSnapshot(),
                            proposal.contentsSnapshot());
                  }
                  return new InventoryValidationItem(
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
    return new InventoryValidationResponse(now(), codec.canonicalHash(values), values);
  }

  InventoryAssetSnapshot snapshot(RentalItem item) {
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

  private List<CaptureMemberRow> captureMemberRows(UUID warehouseId) {
    List<RentalItem> items =
        rentalItems.findAllByWarehouseIdAndStatusInOrderByIdentityMatchKeyAscIdAsc(
            warehouseId, CAPTURE_STATUSES);
    List<UUID> rentalItemIds = items.stream().map(RentalItem::getId).toList();
    Map<UUID, String> tenants = activeTenantSnapshots(rentalItemIds);
    Map<UUID, List<EquipmentContentResponse>> contentsByRentalItem =
        contentsByRentalItem(rentalItemIds);

    List<CaptureMemberRow> result = new ArrayList<>(items.size());
    for (int index = 0; index < items.size(); index++) {
      RentalItem item = items.get(index);
      result.add(
          new CaptureMemberRow(
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
    if (rentalItemIds.isEmpty()) {
      return Map.of();
    }
    List<EquipmentBalance> balances =
        equipmentBalances.findAllByRentalItemIdInAndQuantityGreaterThanAndLocationKindIn(
            rentalItemIds, 0, CABIN_BALANCE_KINDS);
    Map<UUID, EquipmentCatalogItem> equipmentItems =
        equipmentCatalog
            .findAllById(
                balances.stream()
                    .map(EquipmentBalance::getEquipmentId)
                    .collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(EquipmentCatalogItem::getId, item -> item));
    return balances.stream()
        .map(
            balance -> {
              EquipmentCatalogItem item = requireEquipment(equipmentItems, balance.getEquipmentId());
              return new EquipmentContentWithOwner(
                  balance.getRentalItemId(),
                  new EquipmentContentResponse(
                      balance.getEquipmentId(),
                      item.getName(),
                      balance.getQuantity(),
                      balance.getLocationKind()));
            })
        .sorted(
            Comparator.comparing(
                    (EquipmentContentWithOwner value) -> value.content().equipmentName())
                .thenComparing(value -> value.content().equipmentId()))
        .collect(
            Collectors.groupingBy(
                EquipmentContentWithOwner::rentalItemId,
                LinkedHashMap::new,
                Collectors.mapping(EquipmentContentWithOwner::content, Collectors.toList())));
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
    value.put(
        "passport", codec.read(item.getPassportJson(), new TypeReference<Map<String, Object>>() {}));
    value.put("tags", codec.read(item.getTagsJson(), new TypeReference<List<String>>() {}));
    value.put("tenant", tenantSnapshot);
    return value;
  }

  private Map<UUID, String> activeTenantSnapshots(List<UUID> rentalItemIds) {
    if (rentalItemIds.isEmpty()) {
      return Map.of();
    }
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

  private static EquipmentCatalogItem requireEquipment(
      Map<UUID, EquipmentCatalogItem> items, UUID equipmentId) {
    EquipmentCatalogItem value = items.get(equipmentId);
    if (value == null) {
      throw new IllegalStateException("Inventory capture equipment catalog reference is missing");
    }
    return value;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  /**
   * Joins a content projection to its owning cabin while inventory rows are grouped into immutable
   * member snapshots.
   */
  private record EquipmentContentWithOwner(
      UUID rentalItemId, EquipmentContentResponse content) {}

  /** Immutable repeatable-read material that becomes a persisted capture member. */
  record CaptureMemberRow(
      long sequence,
      UUID assetId,
      long version,
      UUID warehouseId,
      RentalItemStatus status,
      String displayCanonicalNumber,
      String identityMatchKey,
      Map<String, Object> passportSnapshot,
      List<EquipmentContentResponse> contentsSnapshot) {
    InventoryAssetCaptureMember toEntity(UUID captureId, InventoryAssetCodec codec) {
      return InventoryAssetCaptureMember.create(
          captureId,
          sequence,
          assetId,
          version,
          warehouseId,
          status,
          displayCanonicalNumber,
          identityMatchKey,
          codec.write(passportSnapshot),
          codec.write(contentsSnapshot));
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
