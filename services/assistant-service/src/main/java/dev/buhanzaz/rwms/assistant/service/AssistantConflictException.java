package dev.buhanzaz.rwms.assistant.service;

/** Signals a stable conversation invariant conflict, such as an idempotency replay with a different client. */
public class AssistantConflictException extends RuntimeException {
  public AssistantConflictException(String message) {
    super(message);
  }
}
