package dev.buhanzaz.rwms.analytics.domain;

import java.util.UUID;

/** Centralizes defensive validation shared by analytics persistence records so malformed transport facts never become projection state. */
final class AnalyticsDomainRules {
  private AnalyticsDomainRules() {}

  static <T> T require(T value, String field) {
    if (value == null) throw new IllegalArgumentException(field + " is required");
    return value;
  }

  static String text(String value, int maximum, String field) {
    if (value == null || value.isBlank() || value.length() > maximum) {
      throw new IllegalArgumentException(field + " is required and must not exceed " + maximum);
    }
    return value;
  }

  static String digest(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
    }
    return value;
  }

  static UUID uuid(UUID value, String field) {
    return require(value, field);
  }
}
