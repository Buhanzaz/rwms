package dev.buhanzaz.rwms.warehouse.eventing;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class WarehouseEventPayloadPolicy {
  private static final Set<String> FIELDS =
      Set.of("warehouseId", "code", "timeZone", "active", "sortOrder");
  private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,63}$");
  private final ObjectMapper objectMapper;
  private final ObjectMapper strictObjectMapper;

  public WarehouseEventPayloadPolicy(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    strictObjectMapper =
        objectMapper.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  }

  public JsonNode validateAndConvert(WarehouseEventType eventType, WarehouseEventPayload payload) {
    if (payload == null) throw new IllegalArgumentException("Warehouse event payload is required");
    JsonNode node = objectMapper.valueToTree(payload);
    validate(eventType.value(), node);
    return node;
  }

  public void validate(String eventType, JsonNode payload) {
    WarehouseEventType.require(eventType);
    if (payload == null || !payload.isObject()) {
      throw new IllegalArgumentException("Warehouse event payload must be an object");
    }
    Set<String> fields = new HashSet<>(payload.propertyNames());
    if (!FIELDS.equals(fields)) {
      throw new IllegalArgumentException("Warehouse event payload has an unexpected field");
    }
    WarehouseEventPayload typed;
    try {
      typed = strictObjectMapper.readerFor(WarehouseEventPayload.class).readValue(payload);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Warehouse event payload has an invalid shape", exception);
    }
    if (typed.warehouseId() == null) throw new IllegalArgumentException("warehouseId is required");
    if (typed.code() == null || !CODE.matcher(typed.code()).matches()) {
      throw new IllegalArgumentException("Warehouse event code is invalid");
    }
    if (typed.timeZone() == null || typed.timeZone().length() > 64) {
      throw new IllegalArgumentException("Warehouse event timeZone is invalid");
    }
    try {
      ZoneId.of(typed.timeZone());
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Warehouse event timeZone is invalid", exception);
    }
    if (typed.sortOrder() != null && typed.sortOrder() < 0) {
      throw new IllegalArgumentException("Warehouse event sortOrder is invalid");
    }
  }
}
