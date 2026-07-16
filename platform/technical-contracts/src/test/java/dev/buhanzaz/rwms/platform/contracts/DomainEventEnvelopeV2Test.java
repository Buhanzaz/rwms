package dev.buhanzaz.rwms.platform.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class DomainEventEnvelopeV2Test {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void serializesMigrationBaselineWithoutInventingOccurrenceTime() throws Exception {
        var envelope = new DomainEventEnvelopeV2<>(
                2,
                UUID.fromString("00000000-0000-0000-0000-000000000301"),
                "task-board.board-task.baseline.v1",
                1,
                null,
                Instant.parse("2026-07-13T10:15:30Z"),
                "task-board-service",
                "BOARD_TASK",
                "00000000-0000-0000-0000-000000000302",
                12L,
                new CorrelationContext(UUID.fromString("00000000-0000-0000-0000-000000000303"), null),
                null,
                Map.of("status", "ACTIVE"));

        var json = objectMapper.writeValueAsString(envelope);
        DomainEventEnvelopeV2<Map<String, String>> restored = objectMapper
                .readerFor(new TypeReference<DomainEventEnvelopeV2<Map<String, String>>>() {})
                .readValue(json);

        assertEquals(envelope, restored);
        assertNull(restored.occurredAt());
        assertEquals(Instant.parse("2026-07-13T10:15:30Z"), restored.recordedAt());
        assertEquals(2, objectMapper.readTree(json).get("envelopeVersion").intValue());
    }

    @Test
    void exposesOnlyOpaqueActorReferenceFields() throws Exception {
        var actorRef = new OpaqueActorReference(
                "00000000-0000-0000-0000-000000000304",
                "USER",
                "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592");
        var envelope = new DomainEventEnvelopeV2<>(
                2,
                UUID.fromString("00000000-0000-0000-0000-000000000305"),
                "auth.user-authorization.changed.v1",
                1,
                Instant.parse("2026-07-13T10:00:00Z"),
                Instant.parse("2026-07-13T10:00:01Z"),
                "auth-service",
                "USER_AUTHORIZATION",
                "00000000-0000-0000-0000-000000000304",
                3L,
                new CorrelationContext(UUID.fromString("00000000-0000-0000-0000-000000000306"), null),
                actorRef,
                Map.of("active", true));

        var actorNode = objectMapper.readTree(objectMapper.writeValueAsString(envelope)).get("actorRef");
        var actorFields = actorNode.propertyStream().map(Map.Entry::getKey).collect(Collectors.toSet());

        assertEquals(Set.of("subjectId", "principalType", "profileRevision"), actorFields);
        assertFalse(actorNode.has("displayName"));
        assertFalse(actorNode.has("login"));
        assertFalse(actorNode.has("email"));
    }

    @Test
    void rejectsMissingRecordedAtAndInvalidMetadata() {
        var correlation = new CorrelationContext(UUID.randomUUID(), null);

        assertThrows(
                NullPointerException.class,
                () -> new DomainEventEnvelopeV2<>(
                        2,
                        UUID.randomUUID(),
                        "event.type.v1",
                        1,
                        null,
                        null,
                        "producer",
                        "AGGREGATE",
                        "id",
                        0L,
                        correlation,
                        null,
                        Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DomainEventEnvelopeV2<>(
                        1,
                        UUID.randomUUID(),
                        "event.type.v1",
                        1,
                        null,
                        Instant.now(),
                        "producer",
                        "AGGREGATE",
                        "id",
                        0L,
                        correlation,
                        null,
                        Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OpaqueActorReference(null, "USER", UUID.randomUUID().toString()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OpaqueActorReference(UUID.randomUUID().toString(), " ", UUID.randomUUID().toString()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OpaqueActorReference("ivan@example.com", "USER", "profile-revision-7"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OpaqueActorReference(UUID.randomUUID().toString(), "USER", "ivan@example.com"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OpaqueActorReference("ivan", "USER", UUID.randomUUID().toString()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OpaqueActorReference(UUID.randomUUID().toString(), "USER", "profile-revision-7"));
    }

    @Test
    void requiresCanonicalAggregateTypeAndObjectPayload() {
        var correlation = new CorrelationContext(UUID.randomUUID(), null);

        assertThrows(
                IllegalArgumentException.class,
                () -> envelope("task-board.board-task.changed.v1", "WORK_QUEUE", Map.of(), correlation));
        assertThrows(
                IllegalArgumentException.class,
                () -> envelope("task-board.board-task.changed.v1", "BOARD_TASK", "ACTIVE", correlation));
        assertThrows(
                IllegalArgumentException.class,
                () -> envelope("task-board.board-task.changed.v1", "BOARD_TASK", List.of("ACTIVE"), correlation));
        assertThrows(
                IllegalArgumentException.class,
                () -> envelope("task-board.board-task.changed.v1", "BOARD_TASK", new String[] {"ACTIVE"}, correlation));

        var recordPayload = envelope(
                "task-board.board-task.changed.v1", "BOARD_TASK", new StatusPayload("ACTIVE"), correlation);
        var mapPayload = envelope("task-board.board-task.changed.v1", "BOARD_TASK", Map.of("status", "ACTIVE"), correlation);
        var pojoPayload = envelope(
                "task-board.board-task.changed.v1", "BOARD_TASK", new MutableStatusPayload("ACTIVE"), correlation);

        assertEquals("ACTIVE", recordPayload.payload().status());
        assertEquals("ACTIVE", mapPayload.payload().get("status"));
        assertEquals("ACTIVE", pojoPayload.payload().status);
    }

    @Test
    void rejectsSensitiveAggregateIdentifiersWithoutEchoingTheirValue() {
        var correlation = new CorrelationContext(UUID.randomUUID(), null);
        String jwt = "abcdefghij.klmnopqrst.uvwxyzABCD";

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> envelope("task-board.board-task.changed.v1", "BOARD_TASK", Map.of(), correlation, jwt));

        assertEquals("aggregateId must be an opaque non-sensitive technical value", exception.getMessage());
        assertTrue(DomainEventEnvelopeV2.containsSensitiveTechnicalValue("ivan@example.com"));
        assertTrue(DomainEventEnvelopeV2.containsSensitiveTechnicalValue("Bearer abc.def"));
        assertTrue(DomainEventEnvelopeV2.containsSensitiveTechnicalValue("Basic dXNlcjpwYXNz"));
        assertTrue(DomainEventEnvelopeV2.containsSensitiveTechnicalValue(jwt));
        assertTrue(DomainEventEnvelopeV2.containsSensitiveTechnicalValue("-----BEGIN PRIVATE KEY-----"));
        assertTrue(DomainEventEnvelopeV2.containsSensitiveTechnicalValue("https://example.test/file?X-Amz-Signature=x"));
        assertTrue(DomainEventEnvelopeV2.containsSensitiveTechnicalValue("+7 999 123 45 67"));
        assertFalse(DomainEventEnvelopeV2.containsSensitiveTechnicalValue("task-1"));
        assertFalse(DomainEventEnvelopeV2.containsSensitiveTechnicalValue(UUID.randomUUID().toString()));
    }

    @Test
    void enforcesSchemaAlignedTechnicalStringLimits() {
        var correlation = new CorrelationContext(UUID.randomUUID(), null);
        String eventSegmentAtLimit = "a".repeat(231);
        String eventTypeAtLimit = "task-board.board-task." + eventSegmentAtLimit + ".v1";
        String producerAtLimit = "a".repeat(128);

        var envelope = new DomainEventEnvelopeV2<>(
                2,
                UUID.randomUUID(),
                eventTypeAtLimit,
                1,
                null,
                Instant.now(),
                producerAtLimit,
                "BOARD_TASK",
                "a".repeat(256),
                1,
                correlation,
                null,
                Map.of());
        assertEquals(256, envelope.eventType().length());

        assertThrows(
                IllegalArgumentException.class,
                () -> new DomainEventEnvelopeV2<>(
                        2,
                        UUID.randomUUID(),
                        "task-board.board-task." + "a".repeat(232) + ".v1",
                        1,
                        null,
                        Instant.now(),
                        "producer",
                        "BOARD_TASK",
                        "id",
                        1,
                        correlation,
                        null,
                        Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DomainEventEnvelopeV2<>(
                        2,
                        UUID.randomUUID(),
                        "task-board.board-task.changed.v1",
                        1,
                        null,
                        Instant.now(),
                        "a".repeat(129),
                        "BOARD_TASK",
                        "id",
                        1,
                        correlation,
                        null,
                        Map.of()));
    }

    @Test
    void canonicalSchemaDocumentsTheSameObjectAndLengthConstraints() throws IOException {
        String schema = Files.readString(findSchema());

        assertTrue(schema.contains("eventType:\n    type: string\n    maxLength: 256"));
        assertTrue(schema.contains("producer:\n    type: string\n    maxLength: 128"));
        String aggregateTypeSchema = schema.substring(schema.indexOf("  aggregateType:"), schema.indexOf("  aggregateId:"));
        assertTrue(aggregateTypeSchema.contains("type: string"));
        assertTrue(aggregateTypeSchema.contains("maxLength: 128"));
        assertTrue(schema.contains("aggregateId:\n    type: string\n    maxLength: 256"));
        assertTrue(schema.contains("payload:\n    type: object"));
        assertTrue(schema.contains("BOARD_TASK maps to board-task"));
    }

    private static Path findSchema() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("contracts/events/technical/domain-event-envelope-v2.schema.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("canonical DomainEventEnvelopeV2 schema was not found");
    }

    private static <T> DomainEventEnvelopeV2<T> envelope(
            String eventType, String aggregateType, T payload, CorrelationContext correlation) {
        return envelope(eventType, aggregateType, payload, correlation, "task-1");
    }

    private static <T> DomainEventEnvelopeV2<T> envelope(
            String eventType,
            String aggregateType,
            T payload,
            CorrelationContext correlation,
            String aggregateId) {
        return new DomainEventEnvelopeV2<>(
                2,
                UUID.randomUUID(),
                eventType,
                1,
                null,
                Instant.now(),
                "task-board-service",
                aggregateType,
                aggregateId,
                1,
                correlation,
                null,
                payload);
    }

    private record StatusPayload(String status) {}

    private static final class MutableStatusPayload {
        private final String status;

        private MutableStatusPayload(String status) {
            this.status = status;
        }
    }
}
