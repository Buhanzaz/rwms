package dev.buhanzaz.rwms.taskboard.service;

import java.util.List;
import java.util.UUID;

/**
 * Narrow private boundary to warehouse-service. It deliberately exposes no warehouse directory
 * data: task-board needs only admission and its own lifecycle readiness backlog.
 */
public interface WarehouseLifecycleGateway {
  void requireAdmission(UUID warehouseId, OperationDirection direction);

  ReadinessWorkPage readinessWork(UUID after, int limit);

  void confirmReadiness(UUID warehouseId, long expectedVersion);

  enum OperationDirection {
    INCOMING,
    OUTGOING
  }

  record ReadinessWork(UUID warehouseId, long warehouseVersion, String lifecycleState) {}

  record ReadinessWorkPage(List<ReadinessWork> items, UUID nextAfter) {
    public ReadinessWorkPage {
      items = items == null ? null : List.copyOf(items);
    }
  }
}
