package dev.buhanzaz.rwms.inventory.domain;

/**
 * Single inventory-owned policy that converts inspected final-plan evidence into the authoritative
 * cabin status and maintenance target used by every downstream owner.
 */
public final class InventoryFinalPlanOutcomePolicy {
  private InventoryFinalPlanOutcomePolicy() {}

  /** Whether this entry may mutate asset state through the normal publication pipeline. */
  public static boolean assetPublicationRequired(InventoryFinalPlanEntry entry) {
    return entry.getDispositionKind() != InventoryCabinDispositionKind.WRITE_OFF;
  }

  /** Shipment remains rented; local evidence selects free, ordinary or capital repair. */
  public static InventoryAssetOutcomeStatus desiredAssetStatus(InventoryFinalPlanEntry entry) {
    if (entry.getDispositionKind() == InventoryCabinDispositionKind.PRESERVE) return null;
    if (entry.getDispositionKind() == InventoryCabinDispositionKind.WRITE_OFF) {
      throw new IllegalArgumentException("Write-off decisions do not publish an asset status");
    }
    if (entry.getDispositionKind() == InventoryCabinDispositionKind.SHIPMENT) {
      return InventoryAssetOutcomeStatus.RENTED;
    }
    if (!entry.isHasWork()) return InventoryAssetOutcomeStatus.FREE;
    return entry.isForceCapitalRepair()
        ? InventoryAssetOutcomeStatus.CAPITAL_REPAIR
        : InventoryAssetOutcomeStatus.REPAIR;
  }

  /** Inventory creates no acceptance target; only findings with work receive a repair target. */
  public static FinalPlanTargetKind maintenanceTarget(InventoryFinalPlanEntry entry) {
    return entry.getDispositionKind() == InventoryCabinDispositionKind.LOCAL && entry.isHasWork()
        ? FinalPlanTargetKind.REPAIR
        : null;
  }
}
