package dev.buhanzaz.rwms.assistant.service;

/** Signals an owner-scoped missing conversation without disclosing another user's resource existence. */
public class AssistantNotFoundException extends RuntimeException {
  public AssistantNotFoundException(String message) {
    super(message);
  }
}
