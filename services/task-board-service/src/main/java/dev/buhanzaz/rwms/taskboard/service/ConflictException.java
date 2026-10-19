package dev.buhanzaz.rwms.taskboard.service;

/** Base domain conflict mapped to an explicit HTTP 409 without exposing internal state. */
public class ConflictException extends RuntimeException {
  public ConflictException(String message) {
    super(message);
  }
}
