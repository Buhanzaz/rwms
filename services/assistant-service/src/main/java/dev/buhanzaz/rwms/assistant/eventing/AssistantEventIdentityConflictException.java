package dev.buhanzaz.rwms.assistant.eventing;

/** Signals reuse of an event identity or source receipt with different canonical evidence. */
public final class AssistantEventIdentityConflictException extends RuntimeException {
  public AssistantEventIdentityConflictException() {
    super("Assistant booking event identity conflicts with durable evidence");
  }
}
