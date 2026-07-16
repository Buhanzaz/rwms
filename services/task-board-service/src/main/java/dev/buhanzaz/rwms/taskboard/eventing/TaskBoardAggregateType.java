package dev.buhanzaz.rwms.taskboard.eventing;

import java.util.List;

public enum TaskBoardAggregateType {
  WORKER_CLASS("rwms.task-board.worker-class.v1"),
  WORKER("rwms.task-board.worker.v1"),
  WORKER_GROUP("rwms.task-board.worker-group.v1"),
  WORK_QUEUE("rwms.task-board.work-queue.v1"),
  QUEUE_USAGE_REFERENCE("rwms.task-board.queue-usage-reference.v1"),
  BOARD_TASK("rwms.task-board.board-task.v1"),
  QUEUE_ENTRY("rwms.task-board.queue-entry.v1");

  public static final String CONSUMER_GROUP = "task-board-shadow-v1";

  private final String topic;

  TaskBoardAggregateType(String topic) {
    this.topic = topic;
  }

  public String topic() {
    return topic;
  }

  public String sanitizedDltTopic() {
    return topic + "." + CONSUMER_GROUP + ".dlt";
  }

  public List<String> outputDestinations() {
    return List.of(topic, sanitizedDltTopic());
  }

  public static TaskBoardAggregateType requireTopic(String topic) {
    for (var type : values()) {
      if (type.topic.equals(topic)) return type;
    }
    throw new IllegalArgumentException("Unsupported task-board aggregate-family topic");
  }
}
