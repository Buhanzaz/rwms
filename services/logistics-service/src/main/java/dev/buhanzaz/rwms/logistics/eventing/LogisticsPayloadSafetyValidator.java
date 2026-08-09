package dev.buhanzaz.rwms.logistics.eventing;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaPayloadSafetyValidator;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Enforces the fixed safe shape and lifecycle correlation of logistics producer payloads before
 * they are enclosed in a transport event.
 */
final class LogisticsPayloadSafetyValidator implements RwmsKafkaPayloadSafetyValidator {
  private static final Set<String> FIELDS =
      Set.of(
          "documentId",
          "documentType",
          "state",
          "warehouseId",
          "destinationWarehouseId",
          "lineCount",
          "resultCode");

  private final LogisticsEventType eventType;

  LogisticsPayloadSafetyValidator(LogisticsEventType eventType) {
    this.eventType = eventType;
  }

  @Override
  public String eventType() {
    return eventType.value();
  }

  @Override
  public void validate(JsonNode payload) {
    if (payload == null || !payload.isObject() || !fieldNames(payload).equals(FIELDS)) {
      throw new IllegalArgumentException("Logistics event payload has an unsafe shape");
    }
    requireUuidField(payload, "documentId");
    requireUuidField(payload, "warehouseId");
    String documentType = requiredText(payload, "documentType");
    if (!eventType.name().startsWith(documentType + "_")) {
      throw new IllegalArgumentException("Logistics event type and document type differ");
    }
    if (!eventType.expectedState().equals(requiredText(payload, "state"))) {
      throw new IllegalArgumentException("Logistics event has an invalid lifecycle state");
    }
    JsonNode destination = payload.get("destinationWarehouseId");
    if ("TRANSFER".equals(documentType)) {
      requireUuid(destination, "destinationWarehouseId");
    } else if (destination == null || !destination.isNull()) {
      throw new IllegalArgumentException("Only transfer facts may include a destination warehouse");
    }
    JsonNode lineCount = payload.get("lineCount");
    if (lineCount == null || !lineCount.isInt() || lineCount.intValue() < 1 || lineCount.intValue() > 100) {
      throw new IllegalArgumentException("Logistics event lineCount is invalid");
    }
    JsonNode resultCode = payload.get("resultCode");
    if (resultCode == null || (!resultCode.isNull() && !resultCode.isTextual())) {
      throw new IllegalArgumentException("Logistics event result code is malformed");
    }
    if (resultCode.isTextual()
        && !resultCode.textValue().matches("[A-Z][A-Z0-9_]{0,63}")) {
      throw new IllegalArgumentException("Logistics event result code is unsafe");
    }
  }

  private static Set<String> fieldNames(JsonNode payload) {
    return Set.copyOf(payload.propertyNames());
  }

  private static void requireUuidField(JsonNode payload, String field) {
    JsonNode value = payload == null ? null : payload.get(field);
    requireUuid(value, field);
  }

  private static void requireUuid(JsonNode value, String field) {
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(field + " must be a UUID");
    }
    try {
      UUID.fromString(value.textValue());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + " must be a UUID", exception);
    }
  }

  private static String requiredText(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(field + " must be a nonblank string");
    }
    return value.textValue();
  }
}
