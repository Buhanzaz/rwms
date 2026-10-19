package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceId;
import dev.buhanzaz.rwms.asset.domain.InventorySourceIsolationRepair;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetSourceOperationRepository;
import dev.buhanzaz.rwms.asset.repository.InventoryAssetSourceRepository;
import dev.buhanzaz.rwms.asset.repository.InventorySourceIsolationRepairRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit administrative correction of approved, untouched pre-proposal sources. This is not a
 * public command: operators must freeze the active inventory and recheck cross-service dependencies
 * before invoking the opt-in runner. All local validation, visibility facts and receipt are atomic.
 */
@Service
class InventorySourceIsolationRepairService {
  private final InventoryAssetSourceOperationRepository operations;
  private final InventoryAssetSourceRepository sources;
  private final RentalItemRepository rentalItems;
  private final InventorySourceIsolationRepairRepository receipts;
  private final AssetLeaseService leases;
  private final InventorySourceIsolationService isolation;
  private final AssetJsonCodec json;
  private final JdbcTemplate jdbc;

  InventorySourceIsolationRepairService(
      InventoryAssetSourceOperationRepository operations,
      InventoryAssetSourceRepository sources,
      RentalItemRepository rentalItems,
      InventorySourceIsolationRepairRepository receipts,
      AssetLeaseService leases,
      InventorySourceIsolationService isolation,
      AssetJsonCodec json,
      JdbcTemplate jdbc) {
    this.operations = operations;
    this.sources = sources;
    this.rentalItems = rentalItems;
    this.receipts = receipts;
    this.leases = leases;
    this.isolation = isolation;
    this.json = json;
    this.jdbc = jdbc;
  }

  /**
   * A byte-equivalent normalized manifest is a permanent no-op, even after inventory completion.
   */
  @Transactional
  public boolean isolate(Manifest input) {
    Manifest manifest = validate(input);
    String fingerprint = json.hash(manifest);
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        rs -> {},
        "inventory-source-isolation:" + manifest.inventoryId());
    var previous = receipts.findById(manifest.inventoryId()).orElse(null);
    if (previous != null) {
      if (!previous.getManifestSha256().equals(fingerprint)
          || !previous.getWarehouseId().equals(manifest.warehouseId())
          || previous.getSourceCount() != manifest.sources().size()) {
        throw new AssetConflictException("Inventory isolation repair is bound to another manifest");
      }
      return false;
    }
    // Match normal source-completion lock order: source operations before cabin/lease locks.
    for (Source source : manifest.sources()) {
      var id = new InventoryAssetSourceId(manifest.inventoryId(), source.findingId());
      var operation =
          operations
              .findByIdForUpdate(id)
              .orElseThrow(
                  () -> new AssetConflictException("Approved legacy source operation is missing"));
      var receipt =
          sources
              .findById(id)
              .orElseThrow(
                  () -> new AssetConflictException("Approved legacy source receipt is missing"));
      if (operation.getSourcePlan() != null
          || !source.assetId().equals(operation.getReservedRentalItemId())
          || !source.assetId().equals(receipt.getRentalItemId())
          || !operation.getRequestFingerprint().equals(receipt.getRequestFingerprint())) {
        throw new AssetConflictException(
            "Approved source does not match its immutable legacy receipt");
      }
    }
    List<RentalItem> items = new ArrayList<>();
    for (Source source : manifest.sources()) {
      leases.lockRentalItemAndLease(source.assetId());
      RentalItem item =
          rentalItems
              .findByIdForUpdate(source.assetId())
              .orElseThrow(
                  () -> new AssetConflictException("Approved legacy rental item is unavailable"));
      if (item.getVersion() != source.expectedVersion()
          || item.getStatus() != RentalItemStatus.FREE
          || !manifest.warehouseId().equals(item.getWarehouseId())
          || item.getInventoryIsolationId() != null) {
        throw new AssetConflictException("Approved legacy rental item has changed");
      }
      requireNoOperationalReferences(item.getId());
      items.add(item);
    }
    for (RentalItem item : items) {
      long version = item.getVersion();
      item.isolateForInventory(manifest.inventoryId());
      rentalItems.saveAndFlush(item);
      isolation.appendVisibility(item, version, manifest.inventoryId(), true);
    }
    receipts.saveAndFlush(
        InventorySourceIsolationRepair.complete(
            manifest.inventoryId(), manifest.warehouseId(), fingerprint, items.size()));
    return true;
  }

  private void requireNoOperationalReferences(UUID id) {
    Boolean occupied =
        jdbc.queryForObject(
            """
            select exists(select 1 from equipment_balance where rental_item_id=? )
              or exists(select 1 from inventory_asset_capture_member where asset_id=? )
              or exists(select 1 from operation_lease where rental_item_id=? )
              or exists(select 1 from order_unit_reservation where rental_item_id=? )
              or exists(select 1 from presentation_unit_hold where rental_item_id=? )
              or exists(select 1 from transfer_unit_reservation where rental_item_id=? )
              or exists(select 1 from maintenance_furniture_custody_claim where rental_item_id=? )
              or exists(select 1 from inventory_asset_outcome_receipt where asset_id=? )
              or exists(select 1 from inventory_asset_outcome_watermark where asset_id=? )
            """,
            Boolean.class,
            id,
            id,
            id,
            id,
            id,
            id,
            id,
            id,
            id);
    if (!Boolean.FALSE.equals(occupied)) {
      throw new AssetConflictException("Approved legacy rental item has operational references");
    }
  }

  private static Manifest validate(Manifest input) {
    if (input == null
        || input.inventoryId() == null
        || input.warehouseId() == null
        || input.sources() == null
        || input.sources().isEmpty()
        || input.sources().size() > 200) {
      throw new IllegalArgumentException(
          "An explicit bounded inventory isolation manifest is required");
    }
    var findings = new HashSet<UUID>();
    var assets = new HashSet<UUID>();
    for (Source source : input.sources()) {
      if (source == null
          || source.findingId() == null
          || source.assetId() == null
          || source.expectedVersion() == null
          || source.expectedVersion() != 0
          || !findings.add(source.findingId())
          || !assets.add(source.assetId())) {
        throw new IllegalArgumentException("Repair requires unique untouched version-zero sources");
      }
    }
    return new Manifest(
        input.inventoryId(),
        input.warehouseId(),
        input.sources().stream()
            .sorted(Comparator.comparing(source -> source.findingId().toString()))
            .toList());
  }

  /** Explicit reviewed target set; no warehouse-wide or inferred target selection is permitted. */
  record Manifest(UUID inventoryId, UUID warehouseId, List<Source> sources) {}

  /** Immutable source binding and the observed live version approved for this repair. */
  record Source(UUID findingId, UUID assetId, Long expectedVersion) {}
}
