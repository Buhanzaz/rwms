package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.WarehouseOperationDirection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Maintenance's single policy boundary for warehouse admission, dates and operation marks. */
@Service
public class WarehouseLifecycleOperations {
  private final MaintenanceDependencyGateway dependencies;
  private final WarehouseOperationMarkStore operationMarks;

  public WarehouseLifecycleOperations(
      MaintenanceDependencyGateway dependencies, WarehouseOperationMarkStore operationMarks) {
    this.dependencies = dependencies;
    this.operationMarks = operationMarks;
  }

  public void requireIncoming(UUID warehouseId) {
    requireNoCallerTransaction("check incoming warehouse admission");
    requireAdmission(warehouseId, WarehouseOperationDirection.INCOMING);
  }

  public void requireOutgoing(UUID warehouseId) {
    requireNoCallerTransaction("check outgoing warehouse admission");
    requireAdmission(warehouseId, WarehouseOperationDirection.OUTGOING);
  }

  private void requireAdmission(UUID warehouseId, WarehouseOperationDirection direction) {
    if (!dependencies.productionReady()) return;
    MaintenanceDependencyGateway.WarehouseOperationAdmission admission =
        dependencies.warehouseAdmission(warehouseId, direction);
    if (!admission.admitted()) {
      throw new MaintenanceConflictException(
          "WAREHOUSE_OPERATION_NOT_ADMITTED",
          "Warehouse "
              + admission.lifecycleState()
              + " does not admit "
              + direction
              + " maintenance work");
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recordOperation(
      UUID warehouseId, UUID operationId, OffsetDateTime occurredAt) {
    operationMarks.enqueue(warehouseId, operationId, occurredAt);
  }

  public LocalDate localDateAt(UUID warehouseId, OffsetDateTime at) {
    requireNoCallerTransaction("resolve the warehouse-local operation date");
    if (!dependencies.productionReady()) {
      return at.toInstant().atZone(ZoneId.of("Europe/Moscow")).toLocalDate();
    }
    MaintenanceDependencyGateway.WarehouseTimeZone truth =
        dependencies.warehouseTimeZoneAt(warehouseId, at);
    return at.toInstant().atZone(ZoneId.of(truth.timeZone())).toLocalDate();
  }

  /**
   * Suspending a caller transaction keeps its database locks open while this boundary waits for a
   * warehouse-service response. Callers must instead commit their short prepare transaction
   * before asking for lifecycle truth.
   */
  private static void requireNoCallerTransaction(String operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Warehouse lifecycle cannot " + operation + " inside a caller transaction");
    }
  }
}
