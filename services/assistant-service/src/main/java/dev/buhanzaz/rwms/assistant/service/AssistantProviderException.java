package dev.buhanzaz.rwms.assistant.service;

public class AssistantProviderException extends RuntimeException {
  public AssistantProviderException(String message) {
    super(message);
  }

  public AssistantProviderException(String message, Throwable cause) {
    super(message, cause);
  }
}
