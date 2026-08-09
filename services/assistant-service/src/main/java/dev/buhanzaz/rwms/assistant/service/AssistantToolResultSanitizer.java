package dev.buhanzaz.rwms.assistant.service;

import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Enforces the assistant boundary's ban on contact data and obsolete warehouse identity fields. */
final class AssistantToolResultSanitizer {
  private static final Set<String> PROHIBITED_RESULT_FIELDS =
      Set.of(
          "phone",
          "email",
          "contact",
          "contacts",
          "client",
          "clientid",
          "clientname",
          "newclient");

  private AssistantToolResultSanitizer() {}

  /** Rejects nested contact or client fields before upstream facts re-enter model context. */
  static void rejectContactFields(JsonNode value) {
    if (value == null) {
      throw new AssistantUpstreamException("Logistics returned no tool result");
    }
    if (value.isObject()) {
      value
          .properties()
          .forEach(
              entry -> {
                if (isProhibitedResultField(entry.getKey())) {
                  throw new AssistantUpstreamException("Logistics returned prohibited tool data");
                }
                rejectContactFields(entry.getValue());
              });
    } else if (value.isArray()) {
      value.forEach(AssistantToolResultSanitizer::rejectContactFields);
    }
  }

  /** Warehouse identity is UUID-only; an upstream legacy business code is invalid. */
  static void rejectWarehouseBusinessCode(JsonNode result) {
    JsonNode warehouses = result.path("warehouses");
    if (!warehouses.isArray()) return;
    for (JsonNode warehouse : warehouses) {
      if (warehouse.isObject() && warehouse.has("code")) {
        throw new AssistantUpstreamException("Logistics returned an obsolete warehouse field");
      }
    }
  }

  private static boolean isProhibitedResultField(String field) {
    String normalized = field.toLowerCase(Locale.ROOT);
    return PROHIBITED_RESULT_FIELDS.contains(normalized)
        || normalized.contains("phone")
        || normalized.contains("email")
        || normalized.contains("contact");
  }
}
