package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.PinTaskRequest;
import static dev.buhanzaz.rwms.taskboard.service.RegistryService.checkVersion;

import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.BoardTaskRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Owns the single manager-controlled ordering attribute retained by the ordinary board. */
@Service
class TaskBoardPinningService {
  private final BoardTaskRepository tasks;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;
  private final TaskBoardQueuePositionCoordinator queuePositions;

  TaskBoardPinningService(
      BoardTaskRepository tasks,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter,
      TaskBoardQueuePositionCoordinator queuePositions) {
    this.tasks = tasks;
    this.eventSourcing = eventSourcing;
    this.projectionWriter = projectionWriter;
    this.queuePositions = queuePositions;
  }

  /** Applies an idempotent task pin under the task version and warehouse queue lock. */
  void pin(UUID warehouseId, UUID taskId, PinTaskRequest request) {
    queuePositions.lockQueueMutation(warehouseId);
    BoardTask task = requireTask(warehouseId, taskId);
    checkVersion(task.getVersion(), request.expectedTaskVersion(), "Задача");
    if (task.getStatus() != TaskStatus.ACTIVE) {
      throw new ConflictException("Закрепить можно только активную задачу");
    }
    if (task.isPinned() == request.pinned()) return;
    long streamVersion = eventSourcing.lock(TaskBoardAggregateType.BOARD_TASK, task.getId());
    task.setPinned(request.pinned());
    projectionWriter.saveAndFlush(tasks, task);
    eventSourcing.taskChanged(task, streamVersion, TaskBoardEventTypes.BOARD_TASK_CHANGED);
  }

  private BoardTask requireTask(UUID warehouseId, UUID taskId) {
    BoardTask task =
        tasks.findById(taskId).orElseThrow(() -> new NotFoundException("Задача не найдена"));
    if (!task.getWarehouseId().equals(warehouseId)) {
      throw new NotFoundException("Задача не найдена");
    }
    return task;
  }
}
