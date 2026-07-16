package dev.buhanzaz.rwms.taskboard.service;

public class ConflictException extends RuntimeException {
  public ConflictException(String message) {
    super(message);
  }
}
