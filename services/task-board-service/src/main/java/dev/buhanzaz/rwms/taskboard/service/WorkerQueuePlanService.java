package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkQueueDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkerQueuePlanRequest;
import static dev.buhanzaz.rwms.taskboard.service.RegistryService.checkVersion;

import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns warehouse-local WorkerApp publication settings for ordinary physical queues.
 *
 * <p>The same warehouse mutation fence used by TAKE and reordering prevents a plan switch from
 * racing task admission. Turning the switch off removes the whole queue from WorkerApp, including
 * active cards, exactly as the warehouse publication control declares.
 */
@Service
public class WorkerQueuePlanService {
  private final WorkQueueRepository queues;
  private final RegistryService registry;
  private final TaskBoardQueuePositionCoordinator queuePositions;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;

  WorkerQueuePlanService(
      WorkQueueRepository queues,
      RegistryService registry,
      TaskBoardQueuePositionCoordinator queuePositions,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter) {
    this.queues = queues;
    this.registry = registry;
    this.queuePositions = queuePositions;
    this.eventSourcing = eventSourcing;
    this.projectionWriter = projectionWriter;
  }

  /** Changes one queue's publication switch and waiting-real plan under its version fence. */
  @Transactional
  public WorkQueueDto update(UUID warehouseId, UUID queueId, WorkerQueuePlanRequest request) {
    queuePositions.lockQueueMutation(warehouseId);
    WorkQueue queue =
        queues
            .findByIdForUpdateWithDefinition(queueId)
            .orElseThrow(() -> new NotFoundException("Очередь не найдена"));
    if (!warehouseId.equals(queue.getWarehouseId())) {
      throw new NotFoundException("Очередь не найдена");
    }
    if (queue.getPurpose() != QueuePurpose.GENERAL) {
      throw new ConflictException("План WorkerApp настраивается только для обычной очереди");
    }
    checkVersion(queue.getVersion(), request.expectedVersion(), "Очередь");
    if (queue.isWorkerFeedEnabled() == request.workerFeedEnabled()
        && queue.getAvailableTaskLimit() == request.availableTaskLimit()) {
      return registry.dto(queue);
    }

    long streamVersion = eventSourcing.lock(TaskBoardAggregateType.WORK_QUEUE, queue.getId());
    queue.setWorkerFeedEnabled(request.workerFeedEnabled());
    queue.setAvailableTaskLimit(request.availableTaskLimit());
    queue.touch();
    queue = projectionWriter.saveAndFlush(queues, queue);
    eventSourcing.queueChanged(queue, streamVersion, TaskBoardEventTypes.WORK_QUEUE_CHANGED);
    return registry.dto(queue);
  }
}
