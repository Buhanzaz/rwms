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

@Service
@RequiredArgsConstructor
public class DriverTaskProcessor {
  private static final int MAX_STEPS_PER_DRAIN = 32;
  private static final Logger log = LoggerFactory.getLogger(DriverTaskProcessor.class);

  private final DriverTaskWorkflowStore store;
  private final LogisticsDependencyGateway dependencies;

  public int processUntilIdle(UUID taskId) {
    if (taskId == null) throw new IllegalArgumentException("taskId is required");
    int processed = 0;
    while (processed < MAX_STEPS_PER_DRAIN) {
      Optional<DriverTaskWorkflowStore.Work> next = store.nextWork(taskId);
      if (next.isEmpty()) return processed;
      execute(next.get());
      processed++;
      if (next.get() instanceof DriverTaskWorkflowStore.StatusWork) {
        return processed;
      }
    }
    throw new IllegalStateException("Driver task did not reach a stable local state");
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
                value.title(),
                value.queueDefinitionId(),
                value.scheduledDate(),
                value.priority()));
        return;
      }
      if (work instanceof DriverTaskWorkflowStore.StatusWork value) {
        store.confirmStatus(
            value.taskId(), dependencies.readDriverTask(value.externalTaskId()));
        return;
      }
      if (work instanceof DriverTaskWorkflowStore.EvidenceWork value) {
        store.confirmEvidence(
            value.taskId(),
            dependencies.readDriverCompletionEvidence(value.externalTaskId()));
        return;
      }
      if (work instanceof DriverTaskWorkflowStore.CoverWork value) {
        store.confirmCover(
            value.taskId(),
            dependencies.setCabinCoverFromTaskEvidence(
                derivedKey("cover", value.taskId()),
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
      throw new IllegalStateException("Unsupported driver workflow item");
    } catch (LogisticsDependencyException exception) {
      store.recordFailure(taskId(work), exception);
    } catch (RuntimeException exception) {
      UUID taskId = taskId(work);
      log.warn("Driver task {} produced an unexpected workflow error", taskId, exception);
      store.recordFailure(
          taskId,
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
      case DriverTaskWorkflowStore.EvidenceWork value -> value.taskId();
      case DriverTaskWorkflowStore.CoverWork value -> value.taskId();
      case DriverTaskWorkflowStore.RepairPlaceEffectWork value -> value.taskId();
    };
  }

  private static UUID derivedKey(String operation, UUID taskId) {
    return UUID.nameUUIDFromBytes(
        ("driver-task:" + operation + ":" + taskId).getBytes(StandardCharsets.UTF_8));
  }
}
