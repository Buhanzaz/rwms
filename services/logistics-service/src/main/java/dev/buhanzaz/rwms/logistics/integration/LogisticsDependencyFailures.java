package dev.buhanzaz.rwms.logistics.integration;

/** Creates the consistent configuration failure used by optional private dependency ports. */
final class LogisticsDependencyFailures {
  private LogisticsDependencyFailures() {}

  /** Returns the standard unavailable-dependency exception without exposing transport details. */
  static LogisticsDependencyException unavailable(String message) {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, message);
  }
}
