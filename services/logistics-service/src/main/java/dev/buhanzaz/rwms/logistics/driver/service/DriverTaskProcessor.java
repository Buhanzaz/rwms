package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Drains durable driver-task work items and records remote dependency outcomes for retryable recovery.
 */
@Service
@RequiredArgsConstructor
public class DriverTaskProcessor {
  private static final int MAX_STEPS_PER_DRAIN = 32;
  private static final Logger log = LoggerFactory.getLogger(DriverTaskProcessor.class);

  private final DriverTaskWorkflowStore store;
  private final LogisticsDependencyGateway dependencies;
  private final DriverTransferExecutionService transferExecution;

  /**
   * Performs a bounded sequence of due work for one task. Remote effects are confirmed through
   * DriverTaskWorkflowStore; recoverable dependency failures are recorded for a later relay pass.
   *
   * @param taskId durable logistics task identity
   * @return number of work items processed before the task became idle or required another pass
   */
  public int processUntilIdle(UUID taskId) {
    if (taskId == null) throw new IllegalArgumentException("taskId is required");
    Optional<UUID> calendarWarehouse = store.expirableTripWarehouse(taskId);
    java.time.ZoneId warehouseZone = null;
    if (calendarWarehouse.isPresent()) {
      var at = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
      warehouseZone =
          java.time.ZoneId.of(
              dependencies.warehouseTimeZoneAt(calendarWarehouse.orElseThrow(), at).timeZone());
    }
    int processed = 0;
    while (processed < MAX_STEPS_PER_DRAIN) {
      if (warehouseZone != null) {
        store.requestTripExpiry(taskId, java.time.LocalDate.now(warehouseZone));
      }
      Optional<DriverTaskWorkflowStore.Work> next = store.nextWork(taskId);
      if (next.isEmpty()) return processed;
      // A caller may own an outer document/queue transaction. Let its expiry intent commit
      // before the relay performs an irreversible task-board cancellation in a later pass.
      if (next.get() instanceof DriverTaskWorkflowStore.ExpiryWork
          && org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive()) return processed;
      execute(next.get());
      processed++;
      if (next.get() instanceof DriverTaskWorkflowStore.StatusWork) {
        return processed;
      }
    }
    throw new IllegalStateException("Driver task did not reach a stable local state");
  }

  /**
   * Reconciles a terminal dependency-failure checkpoint from task-board's authoritative snapshot.
   * A failed read leaves the checkpoint untouched for the next bounded relay pass.
   *
   * @param taskId durable logistics task identity
   */
  public void reconcileFromTaskBoard(UUID taskId) {
    if (taskId == null) throw new IllegalArgumentException("taskId is required");
    Optional<UUID> externalTaskId = store.recoverableReconciliationExternalTaskId(taskId);
    if (externalTaskId.isEmpty()) return;
    try {
      store.confirmReconciliationStatus(
          taskId, dependencies.readDriverTask(externalTaskId.orElseThrow()));
    } catch (RuntimeException exception) {
      log.warn("Driver task {} reconciliation remains pending", taskId, exception);
    }
  }

  /** Marks expiry durably, then drains the existing retryable workflow. */
  public void expireTrip(UUID taskId, java.time.LocalDate warehouseToday) {
    store.requestTripExpiry(taskId, warehouseToday);
    processUntilIdle(taskId);
  }

  private void execute(DriverTaskWorkflowStore.Work work) {
    try {
      if (work instanceof DriverTaskWorkflowStore.RegisterWork value) {
        store.confirmRegistration(
            value.taskId(),
            dependencies.registerDriverTask(
                value.warehouseId(),
                value.externalTaskId(),
                value.taskId(),
                value.title(),
                value.unitNumber(),
                value.description(),
                value.queueDefinitionId(),
                value.scheduledDate(),
                value.priority(),
                value.driverAudience(),
                value.workerContent(),
                value.plannerLineage()));
        return;
      }
      if (work instanceof DriverTaskWorkflowStore.StatusWork value) {
        store.confirmStatus(
            value.taskId(), dependencies.readDriverTask(value.externalTaskId()));
        return;
      }
      if (work instanceof DriverTaskWorkflowStore.ExpiryWork value) {
        var current = dependencies.readDriverTask(value.externalTaskId());
        if ("ACTIVE".equals(current.status())
            && "WAITING".equals(current.entryStatus())
            && !value.scheduledDate().equals(current.scheduledDate())) {
          store.confirmStatus(value.taskId(), current);
          return;
        }
        if ("ACTIVE".equals(current.status()) && !"DONE".equals(current.entryStatus())) {
          store.observeExpiredTripCargo(value.taskId(), current);
          current =
              dependencies.cancelDriverTask(
                  value.externalTaskId(),
                  current.taskVersion(),
                  "Запланированный день склада завершён: невыполненный рейс отменён. Проверить"
                      + " фактический груз и согласовать возврат или новую доставку. Без"
                      + " неустойки.");
        }
        store.confirmStatus(value.taskId(), current);
        return;
      }
      if (work instanceof DriverTaskWorkflowStore.EvidenceWork value) {
        store.confirmEvidence(
            value.taskId(),
            dependencies.readDriverCompletionEvidence(value.externalTaskId()));
        return;
      }
      if (work instanceof DriverTaskWorkflowStore.TransferDepartureWork value) {
        transferExecution.depart(value);
        return;
      }
      if (work instanceof DriverTaskWorkflowStore.TransferArrivalWork value) {
        transferExecution.arrive(value);
        return;
      }
      if (work instanceof DriverTaskWorkflowStore.CoverWork value) {
        store.confirmCover(
            value.taskId(),
            dependencies.setCabinCoverFromTaskEvidence(
                coverKey(value),
                value.cabinId(),
                value.taskBoardEntryId(),
                value.evidenceMediaId()));
        return;
      }
      if (work instanceof DriverTaskWorkflowStore.RepairPlaceEffectWork value) {
        store.confirmRepairPlaceEffect(
            value.taskId(),
            dependencies.transitionRepairPlace(
                derivedKey(value.transition(), value.taskId()),
                value.warehouseId(),
                value.repairId(),
                value.expectedVersion(),
                value.transition()));
        return;
      }
      if (work instanceof DriverTaskWorkflowStore.ReservationReleaseWork value) {
        store.confirmReservationRelease(
            value.taskId(),
            dependencies.transitionRepairPlace(
                derivedKey(
                    "manual-reservation-release:" + value.allocationId(),
                    value.taskId()),
                value.warehouseId(),
                value.repairId(),
                value.expectedVersion(),
                "release"));
        return;
      }
      throw new IllegalStateException("Unsupported driver workflow item");
    } catch (LogisticsDependencyException exception) {
      store.recordFailure(work, exception);
    } catch (RuntimeException exception) {
      UUID taskId = taskId(work);
      log.warn("Driver task {} produced an unexpected workflow error", taskId, exception);
      store.recordFailure(
          work,
          new LogisticsDependencyException(
              LogisticsDependencyException.FailureKind.TRANSIENT,
              "Driver task dependency outcome is unknown",
              exception));
    }
  }

  private static UUID taskId(DriverTaskWorkflowStore.Work work) {
    return switch (work) {
      case DriverTaskWorkflowStore.RegisterWork value -> value.taskId();
      case DriverTaskWorkflowStore.StatusWork value -> value.taskId();
      case DriverTaskWorkflowStore.ExpiryWork value -> value.taskId();
      case DriverTaskWorkflowStore.EvidenceWork value -> value.taskId();
      case DriverTaskWorkflowStore.TransferDepartureWork value -> value.taskId();
      case DriverTaskWorkflowStore.TransferArrivalWork value -> value.taskId();
      case DriverTaskWorkflowStore.CoverWork value -> value.taskId();
      case DriverTaskWorkflowStore.RepairPlaceEffectWork value -> value.taskId();
      case DriverTaskWorkflowStore.ReservationReleaseWork value -> value.taskId();
    };
  }

  private static UUID derivedKey(String operation, UUID taskId) {
    return UUID.nameUUIDFromBytes(
        ("driver-task:" + operation + ":" + taskId).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Preserves the legacy one-cabin cover key while giving each grouped cabin its own durable
   * media command key. Media-service rejects a reused key for a different cabin.
   */
  private static UUID coverKey(DriverTaskWorkflowStore.CoverWork work) {
    return work.groupedShipment()
        ? derivedKey("cover:" + work.cabinId(), work.taskId())
        : derivedKey("cover", work.taskId());
  }
}
