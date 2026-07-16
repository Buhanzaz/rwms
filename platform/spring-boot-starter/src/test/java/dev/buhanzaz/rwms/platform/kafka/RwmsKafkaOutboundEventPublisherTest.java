package dev.buhanzaz.rwms.platform.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RwmsKafkaOutboundEventPublisherTest {

    private static final String DESTINATION = "rwms.task-board.board-task.v1";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final StreamBridge streamBridge = mock(StreamBridge.class);
    private final RwmsKafkaOutboundEventPublisher publisher = new RwmsKafkaOutboundEventPublisher(
            streamBridge,
            objectMapper,
            new RwmsKafkaProperties(true, List.of(DESTINATION)),
            allowEventTypes(
                    "task-board.board-task.created.v1",
                    "task-board.board-task.changed.v1"));

    @Test
    void publishesCanonicalV2JsonWithAggregateKeyAndOnlyTechnicalHeaders() throws Exception {
        when(streamBridge.send(eq(DESTINATION), any(Message.class))).thenReturn(true);
        DomainEventEnvelopeV2<Map<String, String>> envelope = envelope(7);

        publisher.publish(DESTINATION, envelope);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<byte[]>> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(streamBridge).send(eq(DESTINATION), messageCaptor.capture());
        Message<byte[]> message = messageCaptor.getValue();
        assertThat(message.getPayload()).isEqualTo(objectMapper.writeValueAsBytes(envelope));
        assertThat((byte[]) message.getHeaders().get(KafkaHeaders.KEY))
                .containsExactly(envelope.aggregateId().getBytes(StandardCharsets.UTF_8));
        assertThat(message.getHeaders())
                .containsEntry(MessageHeaders.CONTENT_TYPE, org.springframework.util.MimeTypeUtils.APPLICATION_JSON)
                .containsEntry(RwmsKafkaHeaders.ENVELOPE_VERSION, 2)
                .containsEntry(RwmsKafkaHeaders.EVENT_ID, envelope.eventId().toString())
                .containsEntry(RwmsKafkaHeaders.EVENT_TYPE, envelope.eventType())
                .containsEntry(RwmsKafkaHeaders.EVENT_VERSION, envelope.eventVersion())
                .containsEntry(RwmsKafkaHeaders.PRODUCER, envelope.producer())
                .containsEntry(RwmsKafkaHeaders.AGGREGATE_TYPE, envelope.aggregateType())
                .containsEntry(RwmsKafkaHeaders.AGGREGATE_ID, envelope.aggregateId())
                .containsEntry(RwmsKafkaHeaders.AGGREGATE_VERSION, envelope.aggregateVersion())
                .containsEntry(RwmsKafkaHeaders.RECORDED_AT, envelope.recordedAt().toString())
                .containsEntry(RwmsKafkaHeaders.CORRELATION_ID, envelope.correlation().correlationId().toString());
        assertThat(message.getHeaders()).doesNotContainKeys("actorRef", "payload", RwmsKafkaHeaders.CAUSATION_ID);
    }

    @Test
    void validatesSerializedOutboxBodyAsV2BeforePublishing() throws Exception {
        when(streamBridge.send(eq(DESTINATION), any(Message.class))).thenReturn(true);
        byte[] serialized = objectMapper.writeValueAsBytes(envelope(7));

        publisher.publishSerializedV2(DESTINATION, serialized);

        verify(streamBridge).send(eq(DESTINATION), any(Message.class));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> publisher.publishSerializedV2(
                        DESTINATION, "{\"event\":\"safe-looking but not v2\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .withMessage("event envelope is missing a required field");
    }

    @Test
    void rejectsDestinationOutsideExactAllowList() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> publisher.publish("rwms.auth.user-authorization.v1", envelope(7)))
                .withMessageContaining("not configured");
    }

    @Test
    void rejectsAllowedDestinationThatDoesNotMatchEventFamilyOrVersion() {
        RwmsKafkaOutboundEventPublisher multiDestinationPublisher = new RwmsKafkaOutboundEventPublisher(
                streamBridge,
                objectMapper,
                new RwmsKafkaProperties(
                        true, List.of(DESTINATION, "rwms.auth.user-authorization.v1")),
                allowEventTypes("task-board.board-task.created.v1"));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> multiDestinationPublisher.publish(
                        "rwms.auth.user-authorization.v1", envelope(7)))
                .withMessageContaining("aggregate family");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DomainEventEnvelopeV2<>(
                        2,
                        UUID.randomUUID(),
                        "task-board.board-task.changed.v1",
                        2,
                        null,
                        Instant.now(),
                        "task-board-service",
                        "BOARD_TASK",
                        "task-1",
                        1,
                        new CorrelationContext(UUID.randomUUID(), null),
                        null,
                        Map.of()))
                .withMessageContaining("eventVersion");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DomainEventEnvelopeV2<>(
                        2,
                        UUID.randomUUID(),
                        "task-board.board-task.changed.v1",
                        1,
                        null,
                        Instant.now(),
                        "task-board-service",
                        "WORK_QUEUE",
                        "task-1",
                        1,
                        new CorrelationContext(UUID.randomUUID(), null),
                        null,
                        Map.of()))
                .withMessageContaining("aggregateType");
    }

    @Test
    void requiresExplicitNullableFieldsAndCanonicalizesNonNullApplicationMapper() throws Exception {
        byte[] missingNullableFields = ("""
                {
                  "envelopeVersion":2,
                  "eventId":"10000000-0000-0000-0000-000000000001",
                  "eventType":"task-board.board-task.created.v1",
                  "eventVersion":1,
                  "recordedAt":"2026-07-13T09:00:00Z",
                  "producer":"task-board-service",
                  "aggregateType":"BOARD_TASK",
                  "aggregateId":"task-1",
                  "aggregateVersion":1,
                  "correlation":{"correlationId":"30000000-0000-0000-0000-000000000003"},
                  "payload":{"status":"ACTIVE"}
                }
                """).getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> publisher.publishSerializedV2(DESTINATION, missingNullableFields))
                .withMessageContaining("required field");

        ObjectMapper nonNullMapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(value -> value.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        RwmsKafkaOutboundEventPublisher canonicalPublisher = new RwmsKafkaOutboundEventPublisher(
                streamBridge,
                nonNullMapper,
                new RwmsKafkaProperties(true, List.of(DESTINATION)),
                allowEventTypes("task-board.board-task.created.v1"));
        when(streamBridge.send(eq(DESTINATION), any(Message.class))).thenReturn(true);
        canonicalPublisher.publish(DESTINATION, envelope(7));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<byte[]>> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(streamBridge).send(eq(DESTINATION), messageCaptor.capture());
        var json = objectMapper.readTree(messageCaptor.getValue().getPayload());
        assertThat(json.has("occurredAt")).isTrue();
        assertThat(json.has("actorRef")).isTrue();
        assertThat(json.get("correlation").has("causationId")).isTrue();
    }

    @Test
    void rejectsRecursiveSecretsPiiUnsafeHeadersAndServiceValidatorFailures() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> publisher.publish(
                        DESTINATION,
                        envelopeWithPayload(Map.<String, Object>of(
                                "nested", Map.of("password", "secret")))))
                .withMessage("event payload failed safety validation");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> publisher.publish(
                        DESTINATION,
                        envelopeWithPayload(Map.<String, Object>of("contact", "ivan@example.com"))))
                .withMessage("event payload failed safety validation");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> publisher.publish(
                        DESTINATION,
                        envelopeWithPayload(Map.<String, Object>of("authorization", "Basic dXNlcjpwYXNz"))))
                .withMessage("event payload failed safety validation");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> publisher.publish(
                        DESTINATION,
                        envelopeWithPayload(Map.<String, Object>of("apiKey", "key-123"))))
                .withMessage("event payload failed safety validation");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> publisher.publish(
                        DESTINATION,
                        envelopeWithPayload(Map.<String, Object>of("display_name", "Ivan"))))
                .withMessage("event payload failed safety validation");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> publisher.publish(
                        DESTINATION,
                        envelopeWithPayload(Map.<String, Object>of("assignee", "worker-1"))))
                .withMessage("event payload failed safety validation");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> envelopeWithAggregateId("ivan@example.com"))
                .withMessageContaining("aggregateId");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> envelopeWithAggregateId("abcdefghij.klmnopqrst.uvwxyzABCD"))
                .withMessage("aggregateId must be an opaque non-sensitive technical value");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> envelopeWithProducer("task-board-service\r\nsecret"))
                .withMessageContaining("producer");

        RwmsKafkaOutboundEventPublisher serviceValidatedPublisher = new RwmsKafkaOutboundEventPublisher(
                streamBridge,
                objectMapper,
                new RwmsKafkaProperties(true, List.of(DESTINATION)),
                List.of(rejectingValidator("task-board.board-task.created.v1")));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> serviceValidatedPublisher.publish(DESTINATION, envelope(7)))
                .withMessage("event payload failed safety validation")
                .withNoCause();
    }

    @Test
    void rejectsPublishingWhenNoExactEventPayloadValidatorIsRegistered() {
        RwmsKafkaOutboundEventPublisher publisherWithoutSchemaValidator = new RwmsKafkaOutboundEventPublisher(
                streamBridge, objectMapper, new RwmsKafkaProperties(true, List.of(DESTINATION)));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> publisherWithoutSchemaValidator.publish(DESTINATION, envelope(7)))
                .withMessage("event payload validator is not registered for eventType");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new RwmsKafkaOutboundEventPublisher(
                        streamBridge,
                        objectMapper,
                        new RwmsKafkaProperties(true, List.of(DESTINATION)),
                        List.of(
                                allowValidator("task-board.board-task.created.v1"),
                                allowValidator("task-board.board-task.created.v1"))))
                .withMessageContaining("unique");
    }

    @Test
    void treatsFalseSendAsFailureSoOutboxCanRetainRow() {
        when(streamBridge.send(eq(DESTINATION), any(Message.class))).thenReturn(false);

        assertThatExceptionOfType(RwmsKafkaPublishException.class)
                .isThrownBy(() -> publisher.publish(DESTINATION, envelope(7)))
                .withMessageContaining(DESTINATION);
    }

    @Test
    void preservesStreamBridgeExceptionAsPublishFailureCause() {
        IllegalStateException brokerFailure = new IllegalStateException("broker unavailable");
        doThrow(brokerFailure).when(streamBridge).send(eq(DESTINATION), any(Message.class));

        assertThatExceptionOfType(RwmsKafkaPublishException.class)
                .isThrownBy(() -> publisher.publish(DESTINATION, envelope(7)))
                .withCause(brokerFailure);
    }

    @Test
    void rejectsEmptyPayloadAndInvalidMetadataBeforeCallingBinder() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> publisher.publishSerializedV2(DESTINATION, new byte[0]))
                .withMessageContaining("payload");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> RwmsKafkaEventMetadata.from(new DomainEventEnvelopeV2<>(
                                2,
                                UUID.randomUUID(),
                                "created",
                                1,
                                null,
                                Instant.now(),
                                "task-board-service",
                                "BOARD_TASK",
                                "task-1",
                                1,
                                new CorrelationContext(UUID.randomUUID(), null),
                                null,
                                Map.of())))
                .withMessageContaining("eventType");
    }

    private static DomainEventEnvelopeV2<Map<String, String>> envelope(long version) {
        return new DomainEventEnvelopeV2<>(
                2,
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                "task-board.board-task.created.v1",
                1,
                null,
                Instant.parse("2026-07-13T09:00:00Z"),
                "task-board-service",
                "BOARD_TASK",
                "20000000-0000-0000-0000-000000000002",
                version,
                new CorrelationContext(UUID.fromString("30000000-0000-0000-0000-000000000003"), null),
                null,
                Map.of("status", "ACTIVE"));
    }

    private static DomainEventEnvelopeV2<Map<String, Object>> envelopeWithPayload(Map<String, Object> payload) {
        return new DomainEventEnvelopeV2<>(
                2,
                UUID.randomUUID(),
                "task-board.board-task.changed.v1",
                1,
                null,
                Instant.now(),
                "task-board-service",
                "BOARD_TASK",
                "task-1",
                1,
                new CorrelationContext(UUID.randomUUID(), null),
                null,
                payload);
    }

    private static DomainEventEnvelopeV2<Map<String, String>> envelopeWithAggregateId(String aggregateId) {
        return new DomainEventEnvelopeV2<>(
                2,
                UUID.randomUUID(),
                "task-board.board-task.changed.v1",
                1,
                null,
                Instant.now(),
                "task-board-service",
                "BOARD_TASK",
                aggregateId,
                1,
                new CorrelationContext(UUID.randomUUID(), null),
                null,
                Map.of());
    }

    private static DomainEventEnvelopeV2<Map<String, String>> envelopeWithProducer(String producer) {
        return new DomainEventEnvelopeV2<>(
                2,
                UUID.randomUUID(),
                "task-board.board-task.changed.v1",
                1,
                null,
                Instant.now(),
                producer,
                "BOARD_TASK",
                "task-1",
                1,
                new CorrelationContext(UUID.randomUUID(), null),
                null,
                Map.of());
    }

    private static List<RwmsKafkaPayloadSafetyValidator> allowEventTypes(String... eventTypes) {
        return java.util.Arrays.stream(eventTypes).map(RwmsKafkaOutboundEventPublisherTest::allowValidator).toList();
    }

    private static RwmsKafkaPayloadSafetyValidator allowValidator(String eventType) {
        return new RwmsKafkaPayloadSafetyValidator() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public void validate(tools.jackson.databind.JsonNode payload) {
                // The test event schema intentionally accepts every object payload.
            }
        };
    }

    private static RwmsKafkaPayloadSafetyValidator rejectingValidator(String eventType) {
        return new RwmsKafkaPayloadSafetyValidator() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public void validate(tools.jackson.databind.JsonNode payload) {
                throw new IllegalStateException("do not expose service validator details");
            }
        };
    }
}
