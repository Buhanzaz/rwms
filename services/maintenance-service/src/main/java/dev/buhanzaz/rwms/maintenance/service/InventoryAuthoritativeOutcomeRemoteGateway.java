package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcomeTarget;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.MaintenanceDriverTaskCompensation;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.MaintenanceDriverTaskKind;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.TaskSnapshot;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Executes source-owned task, logistics and lease commands outside local database transactions.
 * Callers persist an attempt before entry and reuse the supplied stable key after transport loss.
 */
@Service
public class InventoryAuthoritativeOutcomeRemoteGateway {
  private static final Set<String> TERMINAL_TASK_STATES =
      Set.of("CANCELLED", "COMPLETED", "DONE", "NOT_FOUND");

  private final MaintenanceDependencyGateway dependencies;

  public InventoryAuthoritativeOutcomeRemoteGateway(MaintenanceDependencyGateway dependencies) {
    this.dependencies = dependencies;
  }

  /** Reads current source-owned task truth before the durable attempt checkpoint. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public TaskCancellation task(InventoryAuthoritativeOutcomeTarget target) {
    requireNoTransaction();
    if (target == null || target.getTaskExternalId() == null) {
      throw new IllegalArgumentException("Authoritative task identity is required");
    }
    TaskSnapshot current;
    try {
      current = dependencies.getTask(target.getTaskExternalId());
    } catch (MaintenanceDependencyException exception) {
      if (exception.status() == HttpStatus.NOT_FOUND) {
        return new TaskCancellation("NOT_FOUND", 0L);
      }
      throw exception;
    }
    requireTask(target, current);
    return new TaskCancellation(normalize(current.state()), current.version());
  }

  /** Broadly cancels WAITING or STARTED source-owned work under the committed live version. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public TaskCancellation cancelTask(
      InventoryAuthoritativeOutcomeTarget target, UUID idempotencyKey) {
    requireNoTransaction();
    if (target == null
        || target.getTaskExternalId() == null
        || target.getTaskExpectedVersion() == null
        || idempotencyKey == null) {
      throw new IllegalArgumentException("Authoritative task cancellation identity is required");
    }
    TaskSnapshot cancelled =
        dependencies.cancelTask(
            idempotencyKey, target.getTaskExternalId(), target.getTaskExpectedVersion());
    requireTask(target, cancelled);
    String state = normalize(cancelled.state());
    if (!TERMINAL_TASK_STATES.contains(state)) {
      throw unavailable("Task-board did not return terminal authoritative cancellation truth");
    }
    return new TaskCancellation(state, cancelled.version());
  }

  /** Cancels a pending logistics-owned movement and returns started/completed movement as history. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public DriverCompensation compensateDriver(
      InventoryAuthoritativeOutcomeTarget target, UUID idempotencyKey) {
    requireNoTransaction();
    if (target == null || target.getDriverKind() == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Authoritative driver compensation identity is required");
    }
    MaintenanceDriverTaskKind kind = driverKind(target.getDriverKind());
    MaintenanceDriverTaskCompensation truth =
        dependencies.maintenanceDriverTaskCompensation(target.getTargetId(), kind);
    if (truth == null || truth.outcome() == null) {
      throw unavailable("Logistics-service omitted authoritative driver truth");
    }
    if (truth.outcome() == MaintenanceDriverTaskCompensationOutcome.PENDING) {
      truth = dependencies.cancelMaintenanceDriverTaskCompensation(
          idempotencyKey, target.getTargetId(), kind);
      if (truth == null || truth.outcome() == null) {
        throw unavailable("Logistics-service omitted authoritative driver cancellation truth");
      }
    }
    if (truth.outcome() == MaintenanceDriverTaskCompensationOutcome.PENDING) {
      throw unavailable("Logistics driver movement is still pending after cancellation");
    }
    if (truth.outcome() == MaintenanceDriverTaskCompensationOutcome.STARTED) {
      throw unavailable(
          "Started logistics driver movement must complete before inventory supersession");
    }
    if (truth.outcome() == MaintenanceDriverTaskCompensationOutcome.RECONCILIATION_REQUIRED) {
      throw new MaintenanceDependencyException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Logistics driver movement requires reconciliation before inventory supersession");
    }
    if (truth.outcome() == MaintenanceDriverTaskCompensationOutcome.COMPLETED
        && kind == MaintenanceDriverTaskKind.DELIVER_TO_REPAIR
        && (truth.repairPlaceAllocationId() == null
            || truth.repairPlaceAllocationVersion() == null)) {
      throw unavailable(
          "Completed inbound logistics truth omitted its occupied repair-place allocation");
    }
    return new DriverCompensation(
        truth.outcome().name(),
        truth.taskId(),
        truth.repairPlaceAllocationId(),
        truth.repairPlaceAllocationVersion());
  }

  /** Releases the exact captured asset operation lease under its owning fence. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void releaseLease(
      InventoryAuthoritativeOutcomeTarget target, UUID idempotencyKey) {
    requireNoTransaction();
    if (target == null || target.getLeaseId() == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Authoritative lease release identity is required");
    }
    dependencies.releaseLease(
        idempotencyKey,
        target.getLeaseId(),
        target.getLeaseVersion(),
        target.getFencingToken(),
        target.getLeaseOwnerType(),
        target.getLeaseOwnerId().toString());
  }

  private static void requireTask(
      InventoryAuthoritativeOutcomeTarget target, TaskSnapshot snapshot) {
    if (snapshot == null
        || !target.getTaskExternalId().equals(snapshot.externalTaskId())
        || snapshot.version() < 0
        || snapshot.state() == null
        || snapshot.state().isBlank()) {
      throw unavailable("Task-board returned malformed authoritative cancellation truth");
    }
  }

  private static MaintenanceDriverTaskKind driverKind(String value) {
    try {
      return MaintenanceDriverTaskKind.valueOf(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Stored authoritative driver kind is invalid", exception);
    }
  }

  private static String normalize(String value) {
    return value == null ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
  }

  /** Returns true when a readback proves that no task-board work remains active. */
  public static boolean terminalTask(String state) {
    return TERMINAL_TASK_STATES.contains(normalize(state));
  }

  private static void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Authoritative inventory remote effect must not run in a database transaction");
    }
  }

  private static MaintenanceDependencyException unavailable(String detail) {
    return new MaintenanceDependencyException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }

  /** Terminal task-board state recorded after cancellation or readback recovery. */
  public record TaskCancellation(String outcome, long taskVersion) {}

  /** Terminal logistics movement and optional occupied repair-place identity. */
  public record DriverCompensation(
      String outcome,
      UUID driverTaskId,
      UUID repairPlaceAllocationId,
      Long repairPlaceAllocationVersion) {}
}
