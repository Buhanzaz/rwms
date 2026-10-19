package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.OperationDirection;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.ReadinessWork;
import java.util.List;
import java.util.UUID;

/**
 * Durable local interlock shared by task admission and lifecycle readiness confirmation.
 *
 * <p>Callers first acquire an owner-admitted permit outside a task mutation transaction, then
 * terminalize it inside the local CAS transaction. This makes in-flight admission visible to a
 * concurrent readiness decision without keeping a database lock or transaction open for HTTP.
 */
public interface WarehouseLifecycleFence {
  AdmissionPermit acquireAdmission(UUID warehouseId, OperationDirection direction);

  AdmissionPermit acquireRelocation(UUID sourceWarehouseId, UUID targetWarehouseId);

  void terminalizeAdmission(AdmissionPermit permit);

  /**
   * Returns false when this task-board database still contains live work. A false result never
   * calls warehouse-service, so a stale or incomplete local observation cannot close admission.
   */
  boolean confirmReadinessIfNoLiveWork(ReadinessWork work);

  record AdmissionTarget(UUID warehouseId, OperationDirection direction) {}

  record AdmissionPermit(UUID operationId, List<AdmissionTarget> targets) {
    public AdmissionPermit {
      targets = targets == null ? List.of() : List.copyOf(targets);
    }
  }
}
