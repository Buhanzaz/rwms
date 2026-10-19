package dev.buhanzaz.rwms.asset.disposition;

import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyAssetSnapshot;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyContentSnapshot;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyAssetKind;

import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import dev.buhanzaz.rwms.asset.service.AssetNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds the current asset-side disposition snapshot inside the original default local
 * transaction template.
 *
 * <p>The snapshot is a read projection only. Prepare/apply still re-read and lock the same facts
 * before they make a durable decision.
 */
@Service
final class PropertyDispositionSnapshotService {
  private final TransactionTemplate transactions;
  private final RentalItemRepository rentalItems;
  private final PropertyDispositionLedgerService ledger;
  private final PropertyDispositionEligibilityService eligibility;
  private final PropertyDispositionDecisionStore decisions;

  PropertyDispositionSnapshotService(
      PlatformTransactionManager transactionManager,
      RentalItemRepository rentalItems,
      PropertyDispositionLedgerService ledger,
      PropertyDispositionEligibilityService eligibility,
      PropertyDispositionDecisionStore decisions) {
    this.transactions = new TransactionTemplate(transactionManager);
    this.rentalItems = rentalItems;
    this.ledger = ledger;
    this.eligibility = eligibility;
    this.decisions = decisions;
  }

  MaintenancePropertyAssetSnapshot snapshot(
      PropertyAssetKind assetKind, UUID assetId, UUID warehouseId) {
    eligibility.requireSnapshotIdentity(assetKind, assetId, warehouseId);
    return required(
        transactions.execute(
            ignored ->
                assetKind == PropertyAssetKind.CABIN
                    ? cabinSnapshot(assetId, warehouseId)
                    : equipmentSnapshot(assetId, warehouseId)));
  }

  private MaintenancePropertyAssetSnapshot cabinSnapshot(UUID rentalItemId, UUID warehouseId) {
    RentalItem cabin = rentalItems.findById(rentalItemId)
        .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    if (!warehouseId.equals(cabin.getWarehouseId())) {
      throw new AssetNotFoundException("Rental item was not found in this warehouse");
    }
    List<PropertyDispositionLedgerService.ContentBalanceRow> currentContents =
        ledger.cabinContents(rentalItemId, warehouseId);
    boolean activeReservation = eligibility.hasActiveCabinReservation(rentalItemId);
    boolean activeHold = ledger.hasActiveHold(currentContents.stream().map(value -> value.id()).toList());
    boolean activeLease = eligibility.hasActiveLease(rentalItemId);
    boolean preparedFence = decisions.hasPreparedFence(PropertyAssetKind.CABIN, rentalItemId, warehouseId);
    boolean allowed = !activeReservation
        && !activeHold
        && !activeLease
        && !preparedFence
        && eligibility.cabinStatusAllowsDisposition(cabin);
    return new MaintenancePropertyAssetSnapshot(
        PropertyAssetKind.CABIN,
        cabin.getId(),
        cabin.getNumber(),
        warehouseId,
        cabin.getVersion(),
        null,
        cabin.getStatus(),
        null,
        currentContents.stream()
            .map(
                line ->
                    new MaintenancePropertyContentSnapshot(
                        line.equipmentId(), line.equipmentName(), null, line.version(), line.quantity()))
            .toList(),
        activeReservation,
        activeHold,
        activeLease,
        allowed);
  }

  private MaintenancePropertyAssetSnapshot equipmentSnapshot(UUID equipmentId, UUID warehouseId) {
    PropertyDispositionLedgerService.StockEquipmentRow stock =
        ledger.stockEquipment(equipmentId, warehouseId);
    boolean activeReservation = eligibility.hasActiveEquipmentReservation(equipmentId, warehouseId);
    boolean activeHold = ledger.hasActiveHold(List.of(stock.balanceId()));
    boolean preparedFence = decisions.hasPreparedFence(PropertyAssetKind.EQUIPMENT, equipmentId, warehouseId);
    return new MaintenancePropertyAssetSnapshot(
        PropertyAssetKind.EQUIPMENT,
        equipmentId,
        stock.equipmentName(),
        warehouseId,
        stock.catalogVersion(),
        stock.balanceVersion(),
        null,
        stock.quantity(),
        List.of(),
        activeReservation,
        activeHold,
        false,
        stock.quantity() > 0 && !activeReservation && !activeHold && !preparedFence);
  }

  private static <T> T required(T value) {
    if (value == null) {
      throw new IllegalStateException("Property disposition transaction did not return a result");
    }
    return value;
  }
}
