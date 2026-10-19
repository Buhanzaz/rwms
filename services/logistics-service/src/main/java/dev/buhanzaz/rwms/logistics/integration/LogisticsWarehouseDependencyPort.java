package dev.buhanzaz.rwms.logistics.integration;

import static dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyFailures.unavailable;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseLifecycleReadinessConfirmation;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseLifecycleReadinessWorkPage;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationAdmission;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseSupportLink;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseTimeZone;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Warehouse-service-owned identity, lifecycle, timezone and support-link port used by logistics.
 * Incoming user credentials never cross this private boundary.
 */
interface LogisticsWarehouseDependencyPort {
  WarehouseIdentity readWarehouseIdentity(UUID warehouseId);

  /** Exact owner-side admission truth for a new physical warehouse operation. */
  WarehouseOperationAdmission warehouseAdmission(
      UUID warehouseId, WarehouseOperationDirection direction);

  /** Durable warehouse-service worklist for logistics-owned draining readiness. */
  WarehouseLifecycleReadinessWorkPage warehouseLifecycleReadinessWork(UUID after, int limit);

  WarehouseLifecycleReadinessConfirmation confirmWarehouseLifecycleReadiness(
      UUID warehouseId, long expectedVersion);

  WarehouseTimeZone warehouseTimeZoneAt(UUID warehouseId, OffsetDateTime at);

  /** Idempotent immutable marker that makes a warehouse's operated boundary durable. */
  void markWarehouseOperation(UUID warehouseId, UUID operationId, OffsetDateTime occurredAt);

  default List<WarehouseIdentity> listWarehouseIdentities() {
    throw unavailable("Warehouse identity listing is not configured");
  }

  /** Directed owner-held support edges eligible at the served warehouse's local instant. */
  default List<WarehouseSupportLink> listWarehouseSupportLinks(
      UUID servedWarehouseId, OffsetDateTime at) {
    throw unavailable("Warehouse support links are not configured");
  }

  /** Active direct support topology without date filtering for planning-group discovery. */
  default List<WarehouseSupportLink> listWarehouseSupportNetwork(UUID warehouseId) {
    throw unavailable("Warehouse support network is not configured");
  }
}
