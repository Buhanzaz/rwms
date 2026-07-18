package dev.buhanzaz.rwms.logistics.integration;

/** Safe classification used by the durable saga; raw dependency bodies stay local. */
public class LogisticsDependencyException extends RuntimeException {
  private final FailureKind kind;

  public LogisticsDependencyException(FailureKind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public LogisticsDependencyException(FailureKind kind, String message) {
    this(kind, message, null);
  }

  public FailureKind kind() {
    return kind;
  }

  public enum FailureKind {
    PERMANENT_REJECTION,
    TRANSIENT,
    CONFIGURATION
  }
}
