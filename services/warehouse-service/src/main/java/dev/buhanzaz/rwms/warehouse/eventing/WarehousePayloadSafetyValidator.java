package dev.buhanzaz.rwms.warehouse.eventing;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaPayloadSafetyValidator;
import tools.jackson.databind.JsonNode;

/** Adapts one warehouse event type to the platform outbound-payload safety hook. */
final class WarehousePayloadSafetyValidator implements RwmsKafkaPayloadSafetyValidator {
  private final WarehouseEventType eventType;
  private final WarehouseEventPayloadPolicy policy;

  WarehousePayloadSafetyValidator(WarehouseEventType eventType, WarehouseEventPayloadPolicy policy) {
    this.eventType = eventType;
    this.policy = policy;
  }

  @Override
  public String eventType() {
    return eventType.value();
  }

  @Override
  public void validate(JsonNode payload) {
    policy.validate(eventType.value(), payload);
  }
}
