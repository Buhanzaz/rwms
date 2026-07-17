package dev.buhanzaz.rwms.taskboard.eventing;

import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.QueueUsageReference;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.domain.Worker;
import dev.buhanzaz.rwms.taskboard.domain.WorkerClass;
import dev.buhanzaz.rwms.taskboard.domain.WorkerGroup;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskBoardEventSourcing {
  private final TaskBoardEventStore store;
  private final TaskBoardEventFactFactory facts;

  @Transactional(propagation = Propagation.MANDATORY)
  public long lock(TaskBoardAggregateType type, UUID id) {
    return store.lockCurrentVersion(type, id);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<TaskBoardEventStore.StreamRef, Long> lockStreams(
      Collection<TaskBoardEventStore.StreamRef> streams) {
    return store.lockStreams(streams);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void created(WorkerClass value) {
    store.initialize(TaskBoardAggregateType.WORKER_CLASS, value.getId(), value.getVersion(),
        TaskBoardEventTypes.WORKER_CLASS_CREATED, facts.workerClass(value, false));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void changed(WorkerClass value, long streamVersion) {
    store.append(TaskBoardAggregateType.WORKER_CLASS, value.getId(), streamVersion,
        TaskBoardEventTypes.WORKER_CLASS_CHANGED, facts.workerClass(value, false));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void deleted(WorkerClass value, long streamVersion) {
    store.append(TaskBoardAggregateType.WORKER_CLASS, value.getId(), streamVersion,
        TaskBoardEventTypes.WORKER_CLASS_DELETED, facts.workerClass(value, true));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void created(Worker value) {
    store.initialize(TaskBoardAggregateType.WORKER, value.getId(), value.getVersion(),
        TaskBoardEventTypes.WORKER_CREATED, facts.worker(value, false));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void workerChanged(Worker value, long streamVersion, String eventType) {
    store.append(TaskBoardAggregateType.WORKER, value.getId(), streamVersion,
        eventType, facts.worker(value, false));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void deleted(Worker value, long streamVersion) {
    store.append(TaskBoardAggregateType.WORKER, value.getId(), streamVersion,
        TaskBoardEventTypes.WORKER_DELETED, facts.worker(value, true));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void created(WorkerGroup value) {
    store.initialize(TaskBoardAggregateType.WORKER_GROUP, value.getId(), value.getVersion(),
        TaskBoardEventTypes.WORKER_GROUP_CREATED, facts.workerGroup(value, false));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void groupChanged(WorkerGroup value, long streamVersion, String eventType) {
    store.append(TaskBoardAggregateType.WORKER_GROUP, value.getId(), streamVersion,
        eventType, facts.workerGroup(value, false));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void deleted(WorkerGroup value, long streamVersion) {
    store.append(TaskBoardAggregateType.WORKER_GROUP, value.getId(), streamVersion,
        TaskBoardEventTypes.WORKER_GROUP_DELETED, facts.workerGroup(value, true));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void created(WorkQueue value) {
    store.initialize(TaskBoardAggregateType.WORK_QUEUE, value.getId(), value.getVersion(),
        TaskBoardEventTypes.WORK_QUEUE_CREATED, facts.workQueue(value, false));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void queueChanged(WorkQueue value, long streamVersion, String eventType) {
    store.append(TaskBoardAggregateType.WORK_QUEUE, value.getId(), streamVersion,
        eventType, facts.workQueue(value, false));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void deleted(WorkQueue value, long streamVersion) {
    store.append(TaskBoardAggregateType.WORK_QUEUE, value.getId(), streamVersion,
        TaskBoardEventTypes.WORK_QUEUE_DELETED, facts.workQueue(value, true));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void created(QueueUsageReference value) {
    store.initialize(TaskBoardAggregateType.QUEUE_USAGE_REFERENCE, value.getId(), value.getVersion(),
        TaskBoardEventTypes.QUEUE_REFERENCE_CREATED, facts.queueUsageReference(value, false));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void deleted(QueueUsageReference value, long streamVersion) {
    store.append(TaskBoardAggregateType.QUEUE_USAGE_REFERENCE, value.getId(), streamVersion,
        TaskBoardEventTypes.QUEUE_REFERENCE_DELETED, facts.queueUsageReference(value, true));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void created(BoardTask value) {
    store.initialize(TaskBoardAggregateType.BOARD_TASK, value.getId(), value.getVersion(),
        TaskBoardEventTypes.BOARD_TASK_CREATED, facts.boardTask(value, false));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void taskChanged(BoardTask value, long streamVersion, String eventType) {
    store.append(TaskBoardAggregateType.BOARD_TASK, value.getId(), streamVersion,
        eventType, facts.boardTask(value, false));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void created(QueueEntry value) {
    store.initialize(TaskBoardAggregateType.QUEUE_ENTRY, value.getId(), value.getVersion(),
        TaskBoardEventTypes.QUEUE_ENTRY_CREATED, facts.queueEntry(value, false));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void entryChanged(QueueEntry value, long streamVersion, String eventType) {
    store.append(TaskBoardAggregateType.QUEUE_ENTRY, value.getId(), streamVersion,
        eventType, facts.queueEntry(value, false));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void entryDeleted(QueueEntry value, long streamVersion) {
    store.append(
        TaskBoardAggregateType.QUEUE_ENTRY,
        value.getId(),
        streamVersion,
        TaskBoardEventTypes.QUEUE_ENTRY_CHANGED,
        facts.queueEntry(value, true));
  }
}
