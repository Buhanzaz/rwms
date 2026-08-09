package dev.buhanzaz.rwms.taskboard.eventing;

/** Signals that an inbound or reconstructed event cannot satisfy the canonical event policy. */
public class TaskBoardEventValidationException extends RuntimeException {
  public TaskBoardEventValidationException() {
    super("Task-board event failed schema validation");
  }
}
