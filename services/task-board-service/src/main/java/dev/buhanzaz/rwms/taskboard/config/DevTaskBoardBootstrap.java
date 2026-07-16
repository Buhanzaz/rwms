package dev.buhanzaz.rwms.taskboard.config;

import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueueClassBinding;
import dev.buhanzaz.rwms.taskboard.domain.WorkerClass;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueClassBindingRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerClassRepository;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevTaskBoardBootstrap implements ApplicationRunner {
  static final UUID SPB_WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  static final UUID MSK_WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final String WORKER_CLASS_CODE = "GENERAL_WORKER";
  private static final String MOVEMENT_QUEUE_CODE = "MOVEMENT";

  private final WorkerClassRepository classes;
  private final WorkQueueRepository queues;
  private final WorkQueueClassBindingRepository bindings;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    WorkerClass workerClass = ensureWorkerClass();
    for (UUID warehouseId : List.of(SPB_WAREHOUSE_ID, MSK_WAREHOUSE_ID)) {
      var ensured = ensureMovementQueue(warehouseId);
      WorkQueue queue = ensured.value();
      var existingBinding = bindings.findByQueueIdAndWorkerClassId(queue.getId(), workerClass.getId());
      if (existingBinding.isEmpty()) {
        long streamVersion = ensured.created()
            ? -1
            : eventSourcing.lock(TaskBoardAggregateType.WORK_QUEUE, queue.getId());
        var binding = new WorkQueueClassBinding();
        binding.setQueue(queue);
        binding.setWorkerClass(workerClass);
        binding.setStopTaskOnTake(false);
        projectionWriter.saveAndFlush(bindings, binding);
        projectionWriter.refresh(queue);
        if (ensured.created()) eventSourcing.created(queue);
        else eventSourcing.queueChanged(queue, streamVersion, TaskBoardEventTypes.WORK_QUEUE_CHANGED);
      } else if (ensured.created()) {
        eventSourcing.created(queue);
      }
    }
  }

  private WorkerClass ensureWorkerClass() {
    var existing = classes.findByCodeIgnoreCase(WORKER_CLASS_CODE);
    if (existing.isPresent()) return existing.get();
    var workerClass = new WorkerClass();
    workerClass.setCode(WORKER_CLASS_CODE);
    workerClass.setName("Разнорабочие");
    workerClass.setDescription("Базовый класс для логистических заданий в development");
    workerClass.setSortOrder(10);
    workerClass.setActive(true);
    workerClass = projectionWriter.saveAndFlush(classes, workerClass);
    projectionWriter.refresh(workerClass);
    eventSourcing.created(workerClass);
    return workerClass;
  }

  private EnsuredQueue ensureMovementQueue(UUID warehouseId) {
    var existing = queues.findByWarehouseIdAndCodeIgnoreCase(warehouseId, MOVEMENT_QUEUE_CODE);
    if (existing.isPresent()) return new EnsuredQueue(existing.get(), false);
    var queue = new WorkQueue();
    queue.setWarehouseId(warehouseId);
    queue.setCode(MOVEMENT_QUEUE_CODE);
    queue.setName("Перемещение");
    queue.setDescription("Базовая очередь логистических перемещений в development");
    queue.setType(QueueType.MOVEMENT);
    queue.setSortOrder(nextSortOrder(warehouseId));
    queue.setActive(true);
    queue.setHidden(false);
    queue.setCollapsed(false);
    queue.setHoldingPeriodMinutes(null);
    queue.setNotificationThreshold(null);
    queue.setNotifyWhenThresholdReached(false);
    queue = projectionWriter.saveAndFlush(queues, queue);
    projectionWriter.refresh(queue);
    return new EnsuredQueue(queue, true);
  }

  private int nextSortOrder(UUID warehouseId) {
    return queues.findAllByWarehouseIdOrderBySortOrderAscNameAsc(warehouseId).stream()
            .filter(queue -> queue.getType() != QueueType.HOLDING)
            .mapToInt(WorkQueue::getSortOrder)
            .max()
            .orElse(0)
        + 10;
  }

  private record EnsuredQueue(WorkQueue value, boolean created) {}
}
