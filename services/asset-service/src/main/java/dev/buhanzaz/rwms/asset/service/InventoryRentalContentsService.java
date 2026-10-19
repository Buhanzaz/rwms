package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentBalance;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.repository.EquipmentBalanceRepository;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Replaces one inventory-shipped cabin's exact physical contents without reading, reserving or
 * decrementing STOCK. The caller owns the surrounding serializable outcome transaction.
 */
@Service
final class InventoryRentalContentsService {
  private static final Set<BalanceLocationKind> CABIN_KINDS =
      EnumSet.of(BalanceLocationKind.CABIN_NON_RENTED, BalanceLocationKind.CABIN_RENTED);

  private final EquipmentCatalogItemRepository catalog;
  private final EquipmentBalanceRepository balances;
  private final AssetEquipmentLedgerService ledger;
  private final AssetEventStore events;

  InventoryRentalContentsService(
      EquipmentCatalogItemRepository catalog,
      EquipmentBalanceRepository balances,
      AssetEquipmentLedgerService ledger,
      AssetEventStore events) {
    this.catalog = catalog;
    this.balances = balances;
    this.ledger = ledger;
    this.events = events;
  }

  /** Validates active catalog versions and replaces every cabin-content quantity exactly once. */
  void replace(UUID warehouseId, UUID rentalItemId, List<Content> requestedContents) {
    Map<UUID, Content> desired = normalized(requestedContents);
    validateCatalog(desired);

    List<EquipmentBalance> observed =
        balances.findAllByRentalItemIdAndLocationKindInOrderByEquipmentIdAscWarehouseIdAsc(
            rentalItemId, CABIN_KINDS);
    Set<UUID> allEquipmentIds = new LinkedHashSet<>(desired.keySet());
    observed.stream().map(EquipmentBalance::getEquipmentId).forEach(allEquipmentIds::add);
    ledger.lockInventoryCabinBalanceBuckets(
        rentalItemId, warehouseId, observed, allEquipmentIds);

    List<EquipmentBalance> current =
        balances.findAllCabinBalancesForUpdate(rentalItemId, CABIN_KINDS);
    Map<AssetEventStore.StreamRef, Long> streamVersions =
        events.lockStreams(
            current.stream()
                .map(
                    balance ->
                        new AssetEventStore.StreamRef(
                            AssetAggregateType.EQUIPMENT_BALANCE, balance.getId()))
                .toList());
    Set<UUID> rentedAtTarget = new LinkedHashSet<>();
    for (EquipmentBalance balance : current) {
      Content desiredContent = desired.get(balance.getEquipmentId());
      long target =
          balance.getWarehouseId().equals(warehouseId)
                  && balance.getLocationKind() == BalanceLocationKind.CABIN_RENTED
                  && desiredContent != null
              ? desiredContent.quantity()
              : 0;
      replaceQuantity(balance, target, streamVersions);
      if (balance.getWarehouseId().equals(warehouseId)
          && balance.getLocationKind() == BalanceLocationKind.CABIN_RENTED) {
        rentedAtTarget.add(balance.getEquipmentId());
      }
    }
    for (Content content : desired.values()) {
      if (rentedAtTarget.contains(content.equipmentId())) continue;
      EquipmentBalance created =
          balances.saveAndFlush(
              EquipmentBalance.create(
                  content.equipmentId(),
                  warehouseId,
                  rentalItemId,
                  BalanceLocationKind.CABIN_RENTED,
                  content.quantity()));
      events.initialize(
          AssetAggregateType.EQUIPMENT_BALANCE,
          created.getId(),
          created.getVersion(),
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          fact(created),
          fact(created));
    }
  }

  private void validateCatalog(Map<UUID, Content> desired) {
    if (desired.isEmpty()) return;
    Map<UUID, EquipmentCatalogItem> activeFurniture =
        catalog
            .findAllActiveByIdInAndCategoryForUpdate(
                desired.keySet(), EquipmentCategory.FURNITURE)
            .stream()
            .collect(Collectors.toMap(EquipmentCatalogItem::getId, Function.identity()));
    for (Content content : desired.values()) {
      EquipmentCatalogItem item = activeFurniture.get(content.equipmentId());
      if (item == null || item.getVersion() != content.catalogVersion()) {
        throw new AssetConflictException(
            "Inventory shipment furniture catalog identity or version is stale");
      }
    }
  }

  private void replaceQuantity(
      EquipmentBalance balance,
      long quantity,
      Map<AssetEventStore.StreamRef, Long> streamVersions) {
    long expectedVersion = balance.getVersion();
    if (!balance.replaceQuantity(quantity)) return;
    AssetEventStore.StreamRef stream =
        new AssetEventStore.StreamRef(AssetAggregateType.EQUIPMENT_BALANCE, balance.getId());
    Long streamVersion = streamVersions.get(stream);
    if (streamVersion == null || streamVersion != expectedVersion) {
      throw new AssetConflictException("Inventory shipment balance stream changed concurrently");
    }
    EquipmentBalance changed = balances.saveAndFlush(balance);
    events.append(
        AssetAggregateType.EQUIPMENT_BALANCE,
        changed.getId(),
        expectedVersion,
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        fact(changed),
        fact(changed));
  }

  private static Map<UUID, Content> normalized(List<Content> contents) {
    if (contents == null || contents.size() > 100) {
      throw new IllegalArgumentException("Inventory shipment contents are required and bounded");
    }
    Map<UUID, Content> result = new LinkedHashMap<>();
    for (Content content : contents) {
      if (content == null
          || content.equipmentId() == null
          || content.catalogVersion() < 0
          || content.quantity() < 1) {
        throw new IllegalArgumentException(
            "Inventory shipment contents require unique active catalog items");
      }
    }
    contents.stream()
        .sorted(Comparator.comparing(content -> content.equipmentId().toString()))
        .forEach(
            content -> {
              if (result.putIfAbsent(content.equipmentId(), content) != null) {
                throw new IllegalArgumentException(
                    "Inventory shipment contents require unique active catalog items");
              }
            });
    return result;
  }

  private static Map<String, ?> fact(EquipmentBalance balance) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("balanceId", balance.getId().toString());
    value.put("equipmentId", balance.getEquipmentId().toString());
    value.put("warehouseId", balance.getWarehouseId().toString());
    value.put("rentalItemId", balance.getRentalItemId().toString());
    value.put("locationKind", balance.getLocationKind().name());
    value.put("quantity", balance.getQuantity());
    return value;
  }

  /** Canonical active-catalog quantity carried by the inventory shipment. */
  record Content(UUID equipmentId, long catalogVersion, long quantity) {}
}
