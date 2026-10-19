package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restores a legacy inventory source to ordinary operations only inside the owning completed
 * inventory outcome transaction. The original identity, history and source receipt remain intact.
 */
@Service
class InventorySourceIsolationService {
  private final RentalItemRepository rentalItems;
  private final AssetRentalProjectionService projections;
  private final AssetEventStore events;

  InventorySourceIsolationService(
      RentalItemRepository rentalItems,
      AssetRentalProjectionService projections,
      AssetEventStore events) {
    this.rentalItems = rentalItems;
    this.projections = projections;
    this.events = events;
  }

  /**
   * Locks the exact persisted legacy source tuple; never exposes an unscoped hidden-asset lookup.
   * Visibility and its outbox fact commit or roll back with the completed inventory outcome.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public RentalItem releaseForCompletedInventory(UUID inventoryId, UUID findingId, UUID assetId) {
    RentalItem item =
        rentalItems
            .findHeldLegacyInventorySourceForUpdate(inventoryId, findingId, assetId)
            .orElseThrow(
                () ->
                    new AssetConflictException("Isolated inventory source does not match outcome"));
    long expectedVersion = item.getVersion();
    item.releaseInventoryIsolation(inventoryId);
    rentalItems.saveAndFlush(item);
    appendVisibility(item, expectedVersion, inventoryId, false);
    return item;
  }

  /** Appends a separate version before the normal passport/status facts in the same transaction. */
  void appendVisibility(RentalItem item, long expectedVersion, UUID inventoryId, boolean isolated) {
    Map<String, Object> snapshot = new LinkedHashMap<>(projections.snapshot(item));
    snapshot.put("inventoryIsolationId", isolated ? inventoryId.toString() : null);
    events.append(
        AssetAggregateType.RENTAL_ITEM,
        item.getId(),
        expectedVersion,
        AssetEventType.RENTAL_ITEM_INVENTORY_VISIBILITY_CHANGED,
        Map.of(
            "rentalItemId", item.getId().toString(),
            "warehouseId", item.getWarehouseId().toString(),
            "status", item.getStatus().name(),
            "numberSha256", AssetChecksum.sha256(item.getNumber().getBytes(StandardCharsets.UTF_8)),
            "inventoryId", inventoryId.toString(),
            "isolated", isolated),
        snapshot);
  }
}
