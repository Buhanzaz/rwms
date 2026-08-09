package dev.buhanzaz.rwms.assistant.service;

/** Represents a safe logistics dependency failure that must not leak an upstream response body. */
public class AssistantUpstreamException extends RuntimeException {
  public AssistantUpstreamException(String message) {
    super(message);
  }

  public AssistantUpstreamException(String message, Throwable cause) {
    super(message, cause);
  }
}
