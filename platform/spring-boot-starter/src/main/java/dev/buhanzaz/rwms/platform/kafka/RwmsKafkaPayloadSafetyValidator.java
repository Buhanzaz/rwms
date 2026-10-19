package dev.buhanzaz.rwms.platform.kafka;

import tools.jackson.databind.JsonNode;

/** Validates the payload schema and safety rules for exactly one versioned event type. */
public interface RwmsKafkaPayloadSafetyValidator {

    String eventType();

    void validate(JsonNode payload);
}
