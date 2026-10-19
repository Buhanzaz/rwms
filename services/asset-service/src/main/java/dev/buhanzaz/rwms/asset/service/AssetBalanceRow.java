package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import java.util.UUID;

/**
 * Immutable physical balance identity read from the asset-owned equipment ledger.
 *
 * <p>The value carries the optimistic version used by ledger and hold operations; it has no
 * persistence behavior of its own.
 */
record AssetBalanceRow(
    UUID id,
    long version,
    UUID equipmentId,
    UUID warehouseId,
    UUID rentalItemId,
    BalanceLocationKind kind,
    long quantity) {}
