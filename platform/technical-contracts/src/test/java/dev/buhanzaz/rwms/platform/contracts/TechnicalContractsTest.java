package dev.buhanzaz.rwms.platform.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class TechnicalContractsTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void serializesProblemDetailsWithStableFieldNames() throws Exception {
        var correlation = new CorrelationContext(
                UUID.fromString("00000000-0000-0000-0000-000000000101"),
                UUID.fromString("00000000-0000-0000-0000-000000000102"));
        var problem = new ApiProblem(
                URI.create("https://rwms.example/problems/validation"),
                "Validation failed",
                400,
                "Request contains invalid fields",
                URI.create("/api/warehouse/v1/warehouses"),
                "VALIDATION_FAILED",
                List.of(new FieldViolation("code", "NOT_BLANK", "Code is required")),
                correlation);

        var json = objectMapper.writeValueAsString(problem);
        ApiProblem restored = objectMapper.readerFor(ApiProblem.class).readValue(json);

        assertEquals(problem, restored);
        assertEquals("VALIDATION_FAILED", objectMapper.readTree(json).get("code").stringValue());
        assertEquals("code", objectMapper.readTree(json).get("violations").get(0).get("field").stringValue());
    }

    @Test
    void serializesGenericPageAndEventEnvelope() throws Exception {
        var page = new PageResponse<>(List.of("SPB", "MSK"), 0, 20, 2, 1);
        var pageJson = objectMapper.writeValueAsString(page);
        PageResponse<String> restoredPage =
                objectMapper.readerFor(new TypeReference<PageResponse<String>>() {}).readValue(pageJson);
        assertEquals(page, restoredPage);

        var envelope = new EventEnvelope<>(
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                "warehouse.warehouse.created.v1",
                1,
                Instant.parse("2026-07-12T12:00:00Z"),
                "warehouse-service",
                "Warehouse",
                "00000000-0000-0000-0000-000000000001",
                0L,
                new CorrelationContext(UUID.fromString("00000000-0000-0000-0000-000000000202"), null),
                new ActorSnapshot("admin", "USER", "Local administrator"),
                Map.of("code", "SPB"));
        var envelopeJson = objectMapper.writeValueAsString(envelope);
        EventEnvelope<Map<String, String>> restoredEnvelope = objectMapper
                .readerFor(new TypeReference<EventEnvelope<Map<String, String>>>() {})
                .readValue(envelopeJson);

        assertEquals(envelope, restoredEnvelope);
    }

    @Test
    void preservesV1EventEnvelopeApiAndJsonShape() throws Exception {
        var envelope = new EventEnvelope<>(
                UUID.fromString("00000000-0000-0000-0000-000000000211"),
                "warehouse.warehouse.created.v1",
                1,
                Instant.parse("2026-07-12T12:00:00Z"),
                "warehouse-service",
                "Warehouse",
                "00000000-0000-0000-0000-000000000001",
                0L,
                new CorrelationContext(UUID.fromString("00000000-0000-0000-0000-000000000212"), null),
                new ActorSnapshot("admin", "USER", "Local administrator"),
                Map.of("code", "SPB"));

        var componentNames = Stream.of(EventEnvelope.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();
        var actorComponentNames = Stream.of(ActorSnapshot.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();
        var json = objectMapper.readTree(objectMapper.writeValueAsString(envelope));

        assertEquals(
                List.of(
                        "eventId",
                        "eventType",
                        "eventVersion",
                        "occurredAt",
                        "producer",
                        "aggregateType",
                        "aggregateId",
                        "aggregateVersion",
                        "correlation",
                        "actor",
                        "payload"),
                componentNames);
        assertEquals(List.of("actorId", "actorType", "displayName"), actorComponentNames);
        assertEquals(
                Set.copyOf(componentNames),
                json.propertyStream().map(Map.Entry::getKey).collect(Collectors.toSet()));
        assertEquals("Local administrator", json.get("actor").get("displayName").stringValue());
        assertFalse(json.has("envelopeVersion"));
        assertFalse(json.has("recordedAt"));
        assertFalse(json.has("actorRef"));
    }

    @Test
    void preservesGoldenV1EventEnvelopeJsonFixture() throws Exception {
        String fixture = """
                {
                  "eventId": "00000000-0000-0000-0000-000000000211",
                  "eventType": "warehouse.warehouse.created.v1",
                  "eventVersion": 1,
                  "occurredAt": "2026-07-12T12:00:00Z",
                  "producer": "warehouse-service",
                  "aggregateType": "Warehouse",
                  "aggregateId": "00000000-0000-0000-0000-000000000001",
                  "aggregateVersion": 0,
                  "correlation": {
                    "correlationId": "00000000-0000-0000-0000-000000000212",
                    "causationId": null
                  },
                  "actor": {
                    "actorId": "admin",
                    "actorType": "USER",
                    "displayName": "Local administrator"
                  },
                  "payload": {
                    "code": "SPB"
                  }
                }
                """;

        EventEnvelope<Map<String, String>> restored = objectMapper
                .readerFor(new TypeReference<EventEnvelope<Map<String, String>>>() {})
                .readValue(fixture);
        var serialized = objectMapper.readTree(objectMapper.writeValueAsString(restored));

        assertEquals(objectMapper.readTree(fixture), serialized);
    }

    @Test
    void copiesCollectionsToKeepContractsImmutable() {
        var mutableItems = new ArrayList<>(List.of("SPB"));
        var page = new PageResponse<>(mutableItems, 0, 20, 1, 1);
        mutableItems.add("MSK");

        var mutableViolations = new ArrayList<>(List.of(new FieldViolation("code", "INVALID", "Invalid code")));
        var problem = new ApiProblem(
                URI.create("about:blank"),
                "Invalid request",
                400,
                null,
                null,
                "INVALID_REQUEST",
                mutableViolations,
                null);
        mutableViolations.clear();

        assertEquals(List.of("SPB"), page.items());
        assertEquals(1, problem.violations().size());
        assertThrows(UnsupportedOperationException.class, () -> page.items().add("MSK"));
        assertThrows(UnsupportedOperationException.class, () -> problem.violations().clear());
    }

    @Test
    void rejectsStructurallyInvalidContracts() {
        assertThrows(IllegalArgumentException.class, () -> new FieldViolation(" ", "INVALID", "message"));
        assertThrows(IllegalArgumentException.class, () -> new PageResponse<>(List.of(), -1, 20, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PageResponse<>(List.of(), 0, 0, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CorrelationContext(null, UUID.fromString("00000000-0000-0000-0000-000000000001")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EventEnvelope<>(
                        UUID.randomUUID(),
                        "event",
                        0,
                        Instant.now(),
                        "producer",
                        "aggregate",
                        "id",
                        0L,
                        new CorrelationContext(UUID.randomUUID(), null),
                        null,
                        Map.of()));
    }
}
