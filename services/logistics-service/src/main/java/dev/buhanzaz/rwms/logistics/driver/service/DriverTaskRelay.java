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

  private final DriverLogisticsTaskRepository tasks;
  private final DriverTaskProcessor processor;
  private final DriverQueueScheduler scheduler;
  private final LogisticsDependencyGateway dependencies;

  @Scheduled(
      fixedDelayString = "${rwms.logistics.driver-queue.relay-delay:1s}",
      initialDelayString = "${rwms.logistics.driver-queue.relay-initial-delay:1s}")
  void relay() {
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
    for (LogisticsDependencyGateway.WarehouseIdentity warehouse :
        dependencies.listWarehouseIdentities()) {
      if (!warehouse.active()) continue;
      try {
        scheduler.reconcileAndPromote(warehouse.id());
      } catch (RuntimeException exception) {
        log.warn("Driver queue {} scheduling pass failed", warehouse.id(), exception);
      }
    }
  }
}
