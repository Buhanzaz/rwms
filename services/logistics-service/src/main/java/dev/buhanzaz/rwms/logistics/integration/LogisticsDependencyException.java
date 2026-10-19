package dev.buhanzaz.rwms.logistics.integration;

/** Safe classification used by the durable saga; raw dependency bodies stay local. */
public class LogisticsDependencyException extends RuntimeException {
  private final FailureKind kind;
  private final String dependencyCode;

  public LogisticsDependencyException(FailureKind kind, String message, Throwable cause) {
    this(kind, null, message, cause);
  }

  public LogisticsDependencyException(
      FailureKind kind, String dependencyCode, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
    this.dependencyCode = dependencyCode;
  }

  public LogisticsDependencyException(FailureKind kind, String message) {
    this(kind, message, null);
  }

  public FailureKind kind() {
    return kind;
  }

  public String dependencyCode() {
    return dependencyCode;
  }

  public enum FailureKind {
    PERMANENT_REJECTION,
    TRANSIENT,
    CONFIGURATION
  }
}
