package dev.buhanzaz.rwms.assistant.service;

public class AssistantNotFoundException extends RuntimeException {
  public AssistantNotFoundException(String message) {
    super(message);
  }
}
