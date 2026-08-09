package dev.buhanzaz.rwms.assistant.service;

/** Represents a safe failure from the configured LLM provider or an invalid provider completion. */
public class AssistantProviderException extends RuntimeException {
  public AssistantProviderException(String message) {
    super(message);
  }

  public AssistantProviderException(String message, Throwable cause) {
    super(message, cause);
  }
}
