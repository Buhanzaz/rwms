package dev.buhanzaz.rwms.inventory.domain;

/**
 * Asset status that a completed inventory authoritatively applies to a found cabin.
 *
 * <p>A cabin without planned work becomes {@link #FREE}; ordinary work becomes {@link #REPAIR};
 * and an explicit manager decision selects {@link #CAPITAL_REPAIR}.
 */
public enum InventoryAssetOutcomeStatus {
  FREE,
  REPAIR,
  CAPITAL_REPAIR,
  RENTED
}
