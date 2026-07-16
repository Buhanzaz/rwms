package dev.buhanzaz.rwms.taskboard.eventing;

import java.util.Map;
import java.util.Set;

public final class TaskBoardEventTypes {
  public static final String WORKER_CLASS_CREATED = "task-board.worker-class.created.v1";
  public static final String WORKER_CLASS_CHANGED = "task-board.worker-class.changed.v1";
  public static final String WORKER_CLASS_DELETED = "task-board.worker-class.deleted.v1";
  public static final String WORKER_CREATED = "task-board.worker.created.v1";
  public static final String WORKER_CHANGED = "task-board.worker.changed.v1";
  public static final String WORKER_QUALIFICATIONS_CHANGED = "task-board.worker.qualifications-changed.v1";
  public static final String WORKER_CREDENTIAL_AUDIT = "task-board.worker.credential-audit.v1";
  public static final String WORKER_DELETED = "task-board.worker.deleted.v1";
  public static final String WORKER_GROUP_CREATED = "task-board.worker-group.created.v1";
  public static final String WORKER_GROUP_CHANGED = "task-board.worker-group.changed.v1";
  public static final String WORKER_GROUP_MEMBERS_CHANGED = "task-board.worker-group.members-changed.v1";
  public static final String WORKER_GROUP_DELETED = "task-board.worker-group.deleted.v1";
  public static final String WORK_QUEUE_CREATED = "task-board.work-queue.created.v1";
  public static final String WORK_QUEUE_CHANGED = "task-board.work-queue.changed.v1";
  public static final String WORK_QUEUE_REORDERED = "task-board.work-queue.reordered.v1";
  public static final String WORK_QUEUE_DELETED = "task-board.work-queue.deleted.v1";
  public static final String QUEUE_REFERENCE_CREATED = "task-board.queue-usage-reference.created.v1";
  public static final String QUEUE_REFERENCE_DELETED = "task-board.queue-usage-reference.deleted.v1";
  public static final String BOARD_TASK_CREATED = "task-board.board-task.created.v1";
  public static final String BOARD_TASK_CHANGED = "task-board.board-task.changed.v1";
  public static final String BOARD_TASK_COMPLETED = "task-board.board-task.completed.v1";
  public static final String BOARD_TASK_CANCELLED = "task-board.board-task.cancelled.v1";
  public static final String QUEUE_ENTRY_CREATED = "task-board.queue-entry.created.v1";
  public static final String QUEUE_ENTRY_CHANGED = "task-board.queue-entry.changed.v1";
  public static final String QUEUE_ENTRY_TAKEN = "task-board.queue-entry.taken.v1";
  public static final String QUEUE_ENTRY_PAUSED = "task-board.queue-entry.paused.v1";
  public static final String QUEUE_ENTRY_RESUMED = "task-board.queue-entry.resumed.v1";
  public static final String QUEUE_ENTRY_COMPLETED = "task-board.queue-entry.completed.v1";
  public static final String QUEUE_ENTRY_MOVED = "task-board.queue-entry.moved.v1";
  public static final String QUEUE_ENTRY_CANCELLED = "task-board.queue-entry.cancelled.v1";
  public static final String QUEUE_ENTRY_INTERRUPTED = "task-board.queue-entry.interrupted.v1";
  public static final String QUEUE_ENTRY_RETURNING = "task-board.queue-entry.returning.v1";

  public static final Map<TaskBoardAggregateType, Set<String>> BY_AGGREGATE = Map.of(
      TaskBoardAggregateType.WORKER_CLASS,
      Set.of(WORKER_CLASS_CREATED, WORKER_CLASS_CHANGED, WORKER_CLASS_DELETED),
      TaskBoardAggregateType.WORKER,
      Set.of(WORKER_CREATED, WORKER_CHANGED, WORKER_QUALIFICATIONS_CHANGED, WORKER_CREDENTIAL_AUDIT, WORKER_DELETED),
      TaskBoardAggregateType.WORKER_GROUP,
      Set.of(WORKER_GROUP_CREATED, WORKER_GROUP_CHANGED, WORKER_GROUP_MEMBERS_CHANGED, WORKER_GROUP_DELETED),
      TaskBoardAggregateType.WORK_QUEUE,
      Set.of(WORK_QUEUE_CREATED, WORK_QUEUE_CHANGED, WORK_QUEUE_REORDERED, WORK_QUEUE_DELETED),
      TaskBoardAggregateType.QUEUE_USAGE_REFERENCE,
      Set.of(QUEUE_REFERENCE_CREATED, QUEUE_REFERENCE_DELETED),
      TaskBoardAggregateType.BOARD_TASK,
      Set.of(BOARD_TASK_CREATED, BOARD_TASK_CHANGED, BOARD_TASK_COMPLETED, BOARD_TASK_CANCELLED),
      TaskBoardAggregateType.QUEUE_ENTRY,
      Set.of(QUEUE_ENTRY_CREATED, QUEUE_ENTRY_CHANGED, QUEUE_ENTRY_TAKEN, QUEUE_ENTRY_PAUSED,
          QUEUE_ENTRY_RESUMED, QUEUE_ENTRY_COMPLETED, QUEUE_ENTRY_MOVED, QUEUE_ENTRY_CANCELLED,
          QUEUE_ENTRY_INTERRUPTED, QUEUE_ENTRY_RETURNING));

  public static final Set<String> ALL = BY_AGGREGATE.values().stream()
      .flatMap(Set::stream)
      .collect(java.util.stream.Collectors.toUnmodifiableSet());

  private TaskBoardEventTypes() {}
}
