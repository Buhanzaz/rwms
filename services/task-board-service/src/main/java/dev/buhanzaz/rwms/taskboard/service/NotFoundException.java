package dev.buhanzaz.rwms.taskboard.service;

/** Indicates that a task-board-owned resource is absent within the authorized boundary. */
public class NotFoundException extends RuntimeException {
  public NotFoundException(String message) {
    super(message);
  }
}
