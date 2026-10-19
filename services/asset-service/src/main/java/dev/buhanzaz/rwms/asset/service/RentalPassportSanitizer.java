package dev.buhanzaz.rwms.asset.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Normalizes imported rental-passport content before it becomes asset-owned state or an event payload.
 */
final class RentalPassportSanitizer {
  private static final String LEGACY_PREFIX = "legacy";
  private static final String OLD_PANEL_SOURCE = "old-panel-rental-items-v1";
  private static final Set<String> OBSOLETE_TOP_LEVEL_FIELDS =
      Set.of(
          "locationnodeid",
          "hasphotos",
          "photocount",
          "mainphotourl",
          "previewphotourls");

  private RentalPassportSanitizer() {}

  static Map<String, Object> sanitize(Map<String, Object> source) {
    Map<String, Object> sanitized = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          if (!isObsoleteField(key, value)) {
            sanitized.put(key, sanitizeValue(value));
          }
        });
    return sanitized;
  }

  private static Object sanitizeValue(Object value) {
    if (value instanceof Map<?, ?> nested) {
      Map<String, Object> sanitized = new LinkedHashMap<>();
      nested.forEach(
          (key, child) -> {
            if (key instanceof String field && !isObsoleteField(field, child)) {
              sanitized.put(field, sanitizeValue(child));
            }
          });
      return sanitized;
    }
    if (value instanceof List<?> values) {
      List<Object> sanitized = new ArrayList<>(values.size());
      values.forEach(valueItem -> sanitized.add(sanitizeValue(valueItem)));
      return sanitized;
    }
    return value;
  }

  private static boolean isLegacyField(String key) {
    return key != null
        && key.regionMatches(true, 0, LEGACY_PREFIX, 0, LEGACY_PREFIX.length());
  }

  private static boolean isObsoleteField(String key, Object value) {
    if (isLegacyField(key)) {
      return true;
    }
    if (key == null) {
      return false;
    }
    String normalized = key.toLowerCase(Locale.ROOT);
    return OBSOLETE_TOP_LEVEL_FIELDS.contains(normalized)
        || (normalized.equals("source") && OLD_PANEL_SOURCE.equals(value));
  }
}
