package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationPrestartReplacement;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.MaintenanceDriverTaskCompensation;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.MaintenanceDriverTaskKind;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.PreStartTaskCancellation;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.PreStartTaskCancellationOutcome;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The only place the pre-start replacement saga calls logistics/task-board/asset.  It explicitly
 * suspends any caller transaction because logistics cancellation can call maintenance back to
 * release a repair place.
 */
@Service
public class InventoryPublicationPrestartReplacementRemoteGateway {
  private final MaintenanceDependencyGateway dependencies;

  public InventoryPublicationPrestartReplacementRemoteGateway(
      MaintenanceDependencyGateway dependencies) {
    this.dependencies = dependencies;
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public RemoteCompensation compensate(
      InventoryPublicationPrestartReplacement intent,
      UUID driverCancellationKey,
      UUID taskCancellationKey) {
    requireNoTransaction();
    if (intent == null || driverCancellationKey == null || taskCancellationKey == null) {
      throw new IllegalArgumentException("Inventory pre-start remote compensation identity is required");
    }
    TaskGuard guard = guardRepairTask(intent, taskCancellationKey);
    if (guard.provedNoEffectVersionConflict()) {
      return RemoteCompensation.noEffectVersionConflict();
    }
    if (guard.repairWorkStarted()) {
      return RemoteCompensation.repairWorkStarted(guard.taskOutcome());
    }

    MaintenanceDriverTaskKind kind = driverKind(intent);
    MaintenanceDriverTaskCompensation driver =
        dependencies.maintenanceDriverTaskCompensation(intent.getPredecessorRepairId(), kind);
    if (driver == null || driver.outcome() == null) {
      throw unavailable("Logistics-service omitted driver-task compensation truth");
    }
    if (driver.outcome() == MaintenanceDriverTaskCompensationOutcome.PENDING) {
      driver =
          dependencies.cancelMaintenanceDriverTaskCompensation(
              driverCancellationKey, intent.getPredecessorRepairId(), kind);
      if (driver == null || driver.outcome() == null) {
        throw unavailable("Logistics-service omitted driver-task cancellation truth");
      }
    }
    return resolveDriverTruth(intent, driver, guard.taskOutcome());
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void releaseLease(InventoryPublicationPrestartReplacement intent, UUID key) {
    requireNoTransaction();
    if (intent == null || key == null) {
      throw new IllegalArgumentException("Inventory pre-start lease release identity is required");
    }
    if (intent.getLeaseId() == null) return;
    dependencies.releaseLease(
        key,
        intent.getLeaseId(),
        intent.getLeaseVersion(),
        intent.getFencingToken(),
        intent.getLeaseOwnerType(),
        intent.getLeaseOwnerId().toString());
  }

  private RemoteCompensation resolveDriverTruth(
      InventoryPublicationPrestartReplacement intent,
      MaintenanceDriverTaskCompensation driver,
      String taskOutcome) {
    return switch (driver.outcome()) {
      case STARTED ->
          "EXTERNAL_CAPITAL".equals(intent.getPredecessorMode())
              ? RemoteCompensation.repairWorkStarted(taskOutcome, driver.outcome().name())
              : RemoteCompensation.waitForInboundCompletion(
                  driver.outcome().name(), taskOutcome);
      case COMPLETED -> {
        if ("EXTERNAL_CAPITAL".equals(intent.getPredecessorMode())) {
          yield RemoteCompensation.repairWorkStarted(taskOutcome, driver.outcome().name());
        }
        if (driver.repairPlaceAllocationId() == null
            || driver.repairPlaceAllocationVersion() == null) {
          throw unavailable(
              "Completed inbound logistics truth omitted its occupied repair-place allocation");
        }
        yield RemoteCompensation.safe(
            driver.outcome().name(),
            taskOutcome,
            true,
            driver.repairPlaceAllocationId(),
            driver.repairPlaceAllocationVersion());
      }
      case ABSENT, CANCELLED ->
          RemoteCompensation.safe(driver.outcome().name(), taskOutcome, false, null, null);
      case PENDING ->
          throw unavailable("Logistics-service did not resolve pending driver-task cancellation");
      case RECONCILIATION_REQUIRED ->
          throw new MaintenanceConflictException(
              "MAINTENANCE_STATE_CONFLICT",
              "Logistics requires reconciliation before inventory can replace this repair");
    };
  }

  private TaskGuard guardRepairTask(
      InventoryPublicationPrestartReplacement intent,
      UUID taskCancellationKey) {
    if (!intent.isTaskGuardRequired()) {
      return TaskGuard.notRequired();
    }
    PreStartTaskCancellation task =
        dependencies.cancelTaskIfPreStart(
            taskCancellationKey,
            intent.getTaskExternalId(),
            intent.getTaskExpectedVersion());
    if (task == null || task.outcome() == null) {
      throw unavailable("Task-board omitted pre-start cancellation truth");
    }
    return switch (task.outcome()) {
      case CANCELLED, ALREADY_CANCELLED -> TaskGuard.cancelled(task.outcome().name());
      case STARTED -> TaskGuard.repairWorkStarted(task.outcome().name());
      case VERSION_CONFLICT -> TaskGuard.noEffectVersionConflict(task.outcome().name());
    };
  }

  private static MaintenanceDriverTaskKind driverKind(
      InventoryPublicationPrestartReplacement intent) {
    try {
      return MaintenanceDriverTaskKind.valueOf(intent.getDriverKind());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Stored inventory pre-start driver kind is invalid", exception);
    }
  }

  private static void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Inventory pre-start remote compensation must not run with an active database transaction");
    }
  }

  private static MaintenanceDependencyException unavailable(String detail) {
    return new MaintenanceDependencyException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }

  public record RemoteCompensation(
      Disposition disposition,
      String driverOutcome,
      String taskOutcome,
      boolean occupancyReassignmentRequired,
      UUID repairPlaceAllocationId,
      Long repairPlaceAllocationVersion) {
    static RemoteCompensation safe(
        String driverOutcome,
        String taskOutcome,
        boolean occupancyReassignmentRequired,
        UUID repairPlaceAllocationId,
        Long repairPlaceAllocationVersion) {
      return new RemoteCompensation(
          Disposition.SAFE_REPLACEMENT,
          driverOutcome,
          taskOutcome,
          occupancyReassignmentRequired,
          repairPlaceAllocationId,
          repairPlaceAllocationVersion);
    }

    static RemoteCompensation repairWorkStarted(String taskOutcome) {
      return repairWorkStarted(taskOutcome, null);
    }

    static RemoteCompensation repairWorkStarted(String taskOutcome, String driverOutcome) {
      return new RemoteCompensation(
          Disposition.REPAIR_WORK_STARTED, driverOutcome, taskOutcome, false, null, null);
    }

    static RemoteCompensation waitForInboundCompletion(
        String driverOutcome, String taskOutcome) {
      return new RemoteCompensation(
          Disposition.WAIT_FOR_INBOUND_COMPLETION,
          driverOutcome,
          taskOutcome,
          false,
          null,
          null);
    }

    static RemoteCompensation noEffectVersionConflict() {
      return new RemoteCompensation(
          Disposition.NO_EFFECT_VERSION_CONFLICT, null, "VERSION_CONFLICT", false, null, null);
    }
  }

  public enum Disposition {
    SAFE_REPLACEMENT,
    REPAIR_WORK_STARTED,
    WAIT_FOR_INBOUND_COMPLETION,
    NO_EFFECT_VERSION_CONFLICT
  }

  private record TaskGuard(
      String taskOutcome, boolean repairWorkStarted, boolean provedNoEffectVersionConflict) {
    static TaskGuard notRequired() {
      return new TaskGuard(null, false, false);
    }

    static TaskGuard cancelled(String taskOutcome) {
      return new TaskGuard(taskOutcome, false, false);
    }

    static TaskGuard repairWorkStarted(String taskOutcome) {
      return new TaskGuard(taskOutcome, true, false);
    }

    static TaskGuard noEffectVersionConflict(String taskOutcome) {
      return new TaskGuard(taskOutcome, false, true);
    }
  }
}
