package dev.buhanzaz.rwms.platform.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class RwmsKafkaOutboundEventPublisher {

    private final StreamBridge streamBridge;
    private final ObjectMapper objectMapper;
    private final RwmsKafkaProperties properties;
    private final RwmsKafkaReservedPayloadSafetyValidator reservedPayloadSafetyValidator;
    private final Map<String, RwmsKafkaPayloadSafetyValidator> eventPayloadValidators;

    public RwmsKafkaOutboundEventPublisher(
            StreamBridge streamBridge, ObjectMapper objectMapper, RwmsKafkaProperties properties) {
        this(streamBridge, objectMapper, properties, List.of());
    }

    public RwmsKafkaOutboundEventPublisher(
            StreamBridge streamBridge,
            ObjectMapper objectMapper,
            RwmsKafkaProperties properties,
            List<RwmsKafkaPayloadSafetyValidator> servicePayloadSafetyValidators) {
        this.streamBridge = streamBridge;
        this.objectMapper = objectMapper
                .rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addMixIn(DomainEventEnvelopeV2.class, AlwaysIncludeMixin.class)
                .addMixIn(CorrelationContext.class, AlwaysIncludeMixin.class)
                .addMixIn(OpaqueActorReference.class, AlwaysIncludeMixin.class)
                .build();
        this.properties = properties;
        this.reservedPayloadSafetyValidator = new RwmsKafkaReservedPayloadSafetyValidator();
        this.eventPayloadValidators = indexValidators(servicePayloadSafetyValidators);
        properties.validate();
    }

    public void publish(String destination, DomainEventEnvelopeV2<?> envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("event envelope is required");
        }
        try {
            publishSerializedV2(destination, objectMapper.writeValueAsBytes(envelope));
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalArgumentException("event envelope cannot be serialized as canonical JSON");
        }
    }

    public void publishSerializedV2(String destination, byte[] serializedPayload) {
        publishSerializedV2(destination, destination, serializedPayload);
    }

    public void publishSerializedV2(
            String bindingName, String destination, byte[] serializedPayload) {
        if (bindingName == null || bindingName.isBlank()) {
            throw new IllegalArgumentException("Kafka output binding name must not be blank");
        }
        properties.requireAllowedDestination(destination);
        if (serializedPayload == null || serializedPayload.length == 0) {
            throw new IllegalArgumentException("serialized payload must not be empty");
        }

        JsonNode root = readRequiredTree(serializedPayload);
        PublishedEnvelope envelope = readEnvelope(root);
        RwmsKafkaEventMetadata metadata = envelope.metadata();
        if (!metadata.matchesDestination(destination)) {
            throw new IllegalArgumentException("Kafka destination does not match the event aggregate family");
        }
        validatePayload(envelope.eventType(), envelope.payload());

        MessageBuilder<byte[]> messageBuilder = MessageBuilder.withPayload(serializedPayload)
                .setHeader(
                        KafkaHeaders.KEY,
                        metadata.aggregateId().getBytes(StandardCharsets.UTF_8))
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                .setHeader(RwmsKafkaHeaders.ENVELOPE_VERSION, metadata.envelopeVersion())
                .setHeader(RwmsKafkaHeaders.EVENT_ID, metadata.eventId().toString())
                .setHeader(RwmsKafkaHeaders.EVENT_TYPE, metadata.eventType())
                .setHeader(RwmsKafkaHeaders.EVENT_VERSION, metadata.eventVersion())
                .setHeader(RwmsKafkaHeaders.PRODUCER, metadata.producer())
                .setHeader(RwmsKafkaHeaders.AGGREGATE_TYPE, metadata.aggregateType())
                .setHeader(RwmsKafkaHeaders.AGGREGATE_ID, metadata.aggregateId())
                .setHeader(RwmsKafkaHeaders.AGGREGATE_VERSION, metadata.aggregateVersion())
                .setHeader(RwmsKafkaHeaders.RECORDED_AT, metadata.recordedAt().toString())
                .setHeader(RwmsKafkaHeaders.CORRELATION_ID, metadata.correlationId().toString());
        if (metadata.occurredAt() != null) {
            messageBuilder.setHeader(RwmsKafkaHeaders.OCCURRED_AT, metadata.occurredAt().toString());
        }
        if (metadata.causationId() != null) {
            messageBuilder.setHeader(RwmsKafkaHeaders.CAUSATION_ID, metadata.causationId().toString());
        }
        Message<byte[]> message = messageBuilder.build();

        try {
            if (!streamBridge.send(bindingName, message)) {
                throw new RwmsKafkaPublishException("Kafka binder did not acknowledge destination " + destination);
            }
        } catch (RwmsKafkaPublishException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RwmsKafkaPublishException("Kafka binder failed destination " + destination, exception);
        }
    }

    private PublishedEnvelope readEnvelope(JsonNode root) {
        try {
            DomainEventEnvelopeV2<Map<String, Object>> envelope = objectMapper
                    .readerFor(new TypeReference<DomainEventEnvelopeV2<Map<String, Object>>>() {})
                    .readValue(root);
            return new PublishedEnvelope(RwmsKafkaEventMetadata.from(envelope), envelope.eventType(), root.get("payload"));
        } catch (tools.jackson.core.JacksonException exception) {
            return readFrozenTaskBoardOwnerProofEnvelope(root);
        }
    }

    private PublishedEnvelope readFrozenTaskBoardOwnerProofEnvelope(JsonNode root) {
        try {
            CanonicalEnvelope envelope = objectMapper.readerFor(CanonicalEnvelope.class).readValue(root);
            RwmsKafkaEventMetadata metadata = new RwmsKafkaEventMetadata(
                    envelope.envelopeVersion(),
                    envelope.eventId(),
                    envelope.eventType(),
                    envelope.eventVersion(),
                    envelope.occurredAt(),
                    envelope.aggregateType(),
                    envelope.aggregateId(),
                    envelope.aggregateVersion(),
                    envelope.producer(),
                    envelope.recordedAt(),
                    envelope.correlation().correlationId(),
                    envelope.correlation().causationId());
            if (!metadata.isFrozenTaskBoardOwnerProof()) {
                throw new IllegalArgumentException("serialized payload is not the frozen task-board owner proof contract");
            }
            return new PublishedEnvelope(metadata, envelope.eventType(), envelope.payload());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("serialized payload must be a valid DomainEventEnvelopeV2 JSON object");
        }
    }

    private JsonNode readRequiredTree(byte[] serializedPayload) {
        try {
            JsonNode root = objectMapper.readTree(serializedPayload);
            requireObjectAndFields(
                    root,
                    "envelopeVersion",
                    "eventId",
                    "eventType",
                    "eventVersion",
                    "occurredAt",
                    "recordedAt",
                    "producer",
                    "aggregateType",
                    "aggregateId",
                    "aggregateVersion",
                    "correlation",
                    "actorRef",
                    "payload");
            JsonNode correlation = root.get("correlation");
            requireObjectAndFields(correlation, "correlationId", "causationId");
            JsonNode actorRef = root.get("actorRef");
            if (actorRef != null && !actorRef.isNull()) {
                requireObjectAndFields(actorRef, "subjectId", "principalType", "profileRevision");
            }
            JsonNode payload = root.get("payload");
            if (payload == null || !payload.isObject()) {
                throw new IllegalArgumentException("event payload must be a JSON object");
            }
            return root;
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalArgumentException("serialized payload must be valid canonical JSON");
        }
    }

    private void validatePayload(String eventType, JsonNode payload) {
        try {
            reservedPayloadSafetyValidator.validate(payload);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("event payload failed safety validation");
        }
        RwmsKafkaPayloadSafetyValidator validator = eventPayloadValidators.get(eventType);
        if (validator == null) {
            throw new IllegalArgumentException("event payload validator is not registered for eventType");
        }
        try {
            validator.validate(payload);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("event payload failed safety validation");
        }
    }

    private static Map<String, RwmsKafkaPayloadSafetyValidator> indexValidators(
            List<RwmsKafkaPayloadSafetyValidator> validators) {
        Map<String, RwmsKafkaPayloadSafetyValidator> indexed = new LinkedHashMap<>();
        for (RwmsKafkaPayloadSafetyValidator validator : List.copyOf(validators)) {
            if (validator == null || validator.eventType() == null || validator.eventType().isBlank()) {
                throw new IllegalArgumentException("event payload validator must declare an exact eventType");
            }
            if (indexed.putIfAbsent(validator.eventType(), validator) != null) {
                throw new IllegalArgumentException("event payload validator registration must be unique per eventType");
            }
        }
        return Map.copyOf(indexed);
    }

    private static void requireObjectAndFields(JsonNode node, String... requiredFields) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("event envelope contains an invalid object");
        }
        for (String requiredField : requiredFields) {
            if (!node.has(requiredField)) {
                throw new IllegalArgumentException("event envelope is missing a required field");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private abstract static class AlwaysIncludeMixin {}

    private record PublishedEnvelope(RwmsKafkaEventMetadata metadata, String eventType, JsonNode payload) {}

    private record CanonicalEnvelope(
            int envelopeVersion,
            java.util.UUID eventId,
            String eventType,
            int eventVersion,
            java.time.Instant occurredAt,
            java.time.Instant recordedAt,
            String producer,
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            CorrelationContext correlation,
            OpaqueActorReference actorRef,
            JsonNode payload) {}
}
