package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleFence.AdmissionPermit;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleFence.AdmissionTarget;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.OperationDirection;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.ReadinessWork;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleIntentStore.ReadinessReservation;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Coordinates task-board-local durable intents with the warehouse-owned lifecycle boundary.
 *
 * <p>Every remote call is explicitly outside the caller's transaction. Short independent local
 * transactions make the pending operation durable before that call, so readiness and task mutation
 * cannot pass one another in the network gap.
 */
@Component
public class TaskBoardWarehouseLifecycleFence implements WarehouseLifecycleFence {
  private final WarehouseLifecycleIntentStore intents;
  private final WarehouseLifecycleGateway warehouseLifecycle;

  public TaskBoardWarehouseLifecycleFence(
      WarehouseLifecycleIntentStore intents, WarehouseLifecycleGateway warehouseLifecycle) {
    this.intents = intents;
    this.warehouseLifecycle = warehouseLifecycle;
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public AdmissionPermit acquireAdmission(UUID warehouseId, OperationDirection direction) {
    return acquireAdmissions(List.of(new AdmissionTarget(warehouseId, direction)));
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public AdmissionPermit acquireRelocation(UUID sourceWarehouseId, UUID targetWarehouseId) {
    if (sourceWarehouseId == null || targetWarehouseId == null) {
      throw new IllegalArgumentException("Warehouse relocation lifecycle admission is incomplete");
    }
    if (sourceWarehouseId.equals(targetWarehouseId)) {
      return new AdmissionPermit(null, List.of());
    }
    return acquireAdmissions(
        List.of(
            new AdmissionTarget(sourceWarehouseId, OperationDirection.OUTGOING),
            new AdmissionTarget(targetWarehouseId, OperationDirection.INCOMING)));
  }

  @Override
  @Transactional
  public void terminalizeAdmission(AdmissionPermit permit) {
    if (permit == null || permit.targets().isEmpty()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != TransactionSynchronization.STATUS_COMMITTED) {
              intents.abandonAdmission(permit);
            }
          }
        });
    intents.terminalizeAdmission(permit);
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public boolean confirmReadinessIfNoLiveWork(ReadinessWork work) {
    ReadinessReservation reservation = intents.reserveReadinessIfClear(work);
    if (reservation == null) {
      return false;
    }
    try {
      warehouseLifecycle.confirmReadiness(reservation.warehouseId(), reservation.warehouseVersion());
      intents.completeReadiness(reservation);
      return true;
    } catch (RuntimeException exception) {
      intents.abandonReadiness(reservation);
      throw exception;
    }
  }

  private AdmissionPermit acquireAdmissions(List<AdmissionTarget> targets) {
    AdmissionPermit permit = intents.reserveAdmission(targets);
    try {
      for (AdmissionTarget target : permit.targets()) {
        warehouseLifecycle.requireAdmission(target.warehouseId(), target.direction());
      }
      intents.markAdmitted(permit);
      return permit;
    } catch (RuntimeException exception) {
      intents.abandonAdmission(permit);
      throw exception;
    }
  }
}
