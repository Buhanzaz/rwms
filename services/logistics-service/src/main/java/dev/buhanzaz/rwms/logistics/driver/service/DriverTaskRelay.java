package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled recovery loop for due driver work and warehouse queue promotion; failed work remains
 * recoverable through its durable task state.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "rwms.logistics.driver-queue",
    name = "relay-enabled",
    havingValue = "true")
class DriverTaskRelay {
  private static final int MAX_TASKS_PER_PASS = 100;
  private static final Logger log = LoggerFactory.getLogger(DriverTaskRelay.class);
  private static final List<DriverTaskState> ACTIVE_STATES =
      List.of(
          DriverTaskState.REGISTERING,
          DriverTaskState.SCHEDULED,
          DriverTaskState.CURRENT,
          DriverTaskState.FINALIZING);
  private static final List<DriverTaskState> RECONCILIATION_STATES =
      List.of(DriverTaskState.RECONCILIATION_REQUIRED);

  private final DriverLogisticsTaskRepository tasks;
  private final DriverTaskProcessor processor;
  private final DriverQueueScheduler scheduler;
  private final LogisticsDependencyGateway dependencies;
  private int reconciliationPageNumber;

  @Scheduled(
      fixedDelayString = "${rwms.logistics.driver-queue.relay-delay:1s}",
      initialDelayString = "${rwms.logistics.driver-queue.relay-initial-delay:1s}")
  void relay() {
    List<LogisticsDependencyGateway.WarehouseIdentity> warehouses;
    try {
      warehouses = dependencies.listWarehouseIdentities();
    } catch (RuntimeException exception) {
      log.warn(
          "Warehouse discovery failed; calendar passes deferred, due task recovery continues",
          exception);
      warehouses = List.of();
    }
    for (var warehouse : warehouses) {
      if (!warehouse.active()) continue;
      try {
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
        var today =
            at.atZoneSameInstant(
                    java.time.ZoneId.of(
                        dependencies.warehouseTimeZoneAt(warehouse.id(), at).timeZone()))
                .toLocalDate();
        for (UUID id :
            tasks.findOverdueTripIds(
                warehouse.id(), today, PageRequest.of(0, MAX_TASKS_PER_PASS))) {
          try {
            processor.expireTrip(id, today);
          } catch (RuntimeException exception) {
            log.warn("Overdue trip {} cancellation remains pending", id, exception);
          }
        }
      } catch (RuntimeException exception) {
        log.warn("Warehouse {} trip expiry pass failed", warehouse.id(), exception);
      }
    }
    for (UUID taskId :
        tasks.findDueIds(
            ACTIVE_STATES,
            OffsetDateTime.now(ZoneOffset.UTC),
            PageRequest.of(0, MAX_TASKS_PER_PASS))) {
      try {
        processor.processUntilIdle(taskId);
      } catch (RuntimeException exception) {
        log.warn("Driver task {} relay failed", taskId, exception);
      }
    }
    for (LogisticsDependencyGateway.WarehouseIdentity warehouse : warehouses) {
      if (!warehouse.active()) continue;
      try {
        scheduler.reconcileAndPromote(warehouse.id());
      } catch (RuntimeException exception) {
        log.warn("Driver queue {} scheduling pass failed", warehouse.id(), exception);
      }
    }
  }

  /**
   * Rechecks bounded dependency-failure reconciliation rows against task-board. A confirmed remote
   * task can safely resume its durable local workflow; business reconciliation codes remain
   * terminal and are never reopened by this recovery pass.
   */
  @Scheduled(
      fixedDelayString = "${rwms.logistics.driver-queue.reconciliation-delay:30s}",
      initialDelayString = "${rwms.logistics.driver-queue.reconciliation-initial-delay:2s}")
  void reconcile() {
    int pageNumber = reconciliationPageNumber;
    List<UUID> taskIds =
        tasks.findDueIds(
            RECONCILIATION_STATES,
            OffsetDateTime.now(ZoneOffset.UTC),
            PageRequest.of(pageNumber, MAX_TASKS_PER_PASS));
    reconciliationPageNumber = taskIds.size() == MAX_TASKS_PER_PASS ? pageNumber + 1 : 0;
    for (UUID taskId : taskIds) {
      try {
        processor.reconcileFromTaskBoard(taskId);
      } catch (RuntimeException exception) {
        log.warn("Driver task {} reconciliation pass failed", taskId, exception);
      }
    }
  }
}
