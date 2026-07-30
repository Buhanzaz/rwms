package dev.buhanzaz.rwms.assistant.service;

public class AssistantUpstreamException extends RuntimeException {
  public AssistantUpstreamException(String message) {
    super(message);
  }

  public AssistantUpstreamException(String message, Throwable cause) {
    super(message, cause);
  }
}
