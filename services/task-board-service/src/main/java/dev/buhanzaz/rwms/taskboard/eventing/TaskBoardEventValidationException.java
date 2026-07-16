package dev.buhanzaz.rwms.taskboard.eventing;

public class TaskBoardEventValidationException extends RuntimeException {
  public TaskBoardEventValidationException() {
    super("Task-board event failed schema validation");
  }
}
