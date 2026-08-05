package dev.buhanzaz.rwms.asset.integration.warehouse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Narrow client for warehouse-owned identity and lifecycle boundaries. Asset
 * never infers lifecycle state from an old metadata read.
 */
public interface WarehouseRegistryClient {
  /** Legacy ACTIVE-only lookup retained for unchanged callers during the directional rollout. */
  void requireActive(UUID warehouseId);

  default void requireIncoming(UUID warehouseId) {
    requireActive(warehouseId);
  }

  default void requireOutgoing(UUID warehouseId) {
    requireActive(warehouseId);
  }

  default void markOperation(UUID warehouseId, UUID operationId, OffsetDateTime occurredAt) {
    throw new UnsupportedOperationException("Warehouse operation marking is not configured");
  }

  default WarehouseTimeZoneAt timeZoneAt(UUID warehouseId, OffsetDateTime at) {
    throw new UnsupportedOperationException("Warehouse timezone lookup is not configured");
  }

  default WarehouseLifecycleReadinessWorkPage lifecycleReadinessWork(UUID after, int limit) {
    throw new UnsupportedOperationException("Warehouse lifecycle readiness is not configured");
  }

  default void confirmLifecycleReadiness(UUID warehouseId, long expectedVersion) {
    throw new UnsupportedOperationException("Warehouse lifecycle readiness is not configured");
  }

  /** Schedulers must not manufacture lifecycle confirmations in an explicitly disabled setup. */
  default boolean lifecycleIntegrationEnabled() {
    return false;
  }

  enum WarehouseOperationDirection {
    INCOMING,
    OUTGOING
  }

  record WarehouseTimeZoneAt(UUID warehouseId, String timeZone, OffsetDateTime effectiveFrom) {}

  record WarehouseLifecycleReadinessWork(
      UUID warehouseId, long warehouseVersion, String lifecycleState) {}

  record WarehouseLifecycleReadinessWorkPage(
      List<WarehouseLifecycleReadinessWork> items, UUID nextAfter) {}
}
