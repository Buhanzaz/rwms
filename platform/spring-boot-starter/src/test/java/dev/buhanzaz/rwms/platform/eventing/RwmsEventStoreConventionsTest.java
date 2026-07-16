package dev.buhanzaz.rwms.platform.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RwmsEventStoreConventionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final JsonSchemaFactory JSON_SCHEMA_FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    @Test
    void exposesOnlyTheApprovedServiceLocalTablesAndSnapshotThreshold() {
        assertThat(RwmsEventStoreConventions.SNAPSHOT_THRESHOLD).isEqualTo(100);
        assertThat(RwmsEventStoreConventions.REQUIRED_TABLES)
                .containsExactlyInAnyOrder(
                        "event_stream_head",
                        "domain_event",
                        "aggregate_snapshot",
                        "projection_checkpoint",
                        "outbox_event",
                        "inbox_message",
                        "consumer_aggregate_checkpoint");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> RwmsEventStoreConventions.REQUIRED_TABLES.add("shared_aggregate"));
    }

    @Test
    void rejectsIncompleteOrUnexpectedServiceSchemas() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> RwmsEventStoreConventions.requireExactTableSet(Set.of("domain_event")))
                .withMessageContaining("required service-local event-store tables");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> RwmsEventStoreConventions.requireExactTableSet(
                        Set.of(
                                "event_stream_head",
                                "domain_event",
                                "aggregate_snapshot",
                                "projection_checkpoint",
                                "outbox_event",
                                "inbox_message",
                                "consumer_aggregate_checkpoint",
                                "shared_entity")))
                .withMessageContaining("required service-local event-store tables");
    }

    @Test
    void canonicalYamlDocumentsDeliveryCheckpointAndEventStoreInvariants() throws IOException {
        String delivery = Files.readString(findContract("event-delivery-policy-v1.schema.yaml"));
        String checkpoint = Files.readString(findContract("aggregate-checkpoint-policy-v1.schema.yaml"));
        String eventStore = Files.readString(findContract("event-store-convention-v1.schema.yaml"));

        assertThat(delivery)
                .contains("totalDeliveryAttempts: 4")
                .contains("retryBackoffSeconds: [1, 2, 4]")
                .contains("<topic>.<consumer-group>.dlt")
                .contains("validation: DEAD_LETTER")
                .contains("transientInfrastructure: RETRY_THEN_DEAD_LETTER")
                .contains("infiniteRequeue: false");
        assertThat(checkpoint)
                .contains("DUPLICATE")
                .contains("APPLY_NEXT")
                .contains("QUARANTINE_GAP")
                .contains("BLOCKED")
                .contains("explicitReconciliationRequired: true");
        assertThat(eventStore)
                .contains("snapshotThreshold: 100")
                .contains("event_stream_head")
                .contains("domain_event")
                .contains("aggregate_snapshot")
                .contains("projection_checkpoint")
                .contains("outbox_event")
                .contains("inbox_message")
                .contains("consumer_aggregate_checkpoint")
                .contains("unique: [aggregate_type, aggregate_id, aggregate_version]")
                .contains("appendProjectionAndOutbox: ONE_POSTGRES_TRANSACTION")
                .contains("consumerEffectInboxAndCheckpoint: ONE_POSTGRES_TRANSACTION")
                .contains("streamHeadLockOrder: STABLE_AGGREGATE_TYPE_AND_ID")
                .contains("casAppendsAndProjections: ONE_POSTGRES_TRANSACTION")
                .contains("sharedJpaEntities: false");
    }

    @Test
    void allCanonicalTechnicalYamlSchemasParseAndValidateRepresentativeDocuments() throws IOException {
        assertSchemaAccepts(
                "event-delivery-policy-v1.schema.yaml",
                """
                {
                  "topic": "rwms.task-board.board-task.v1",
                  "consumerGroup": "audit-projection",
                  "failureCategory": "TRANSIENT_INFRASTRUCTURE",
                  "failedDeliveryAttempt": 4
                }
                """);
        assertSchemaAccepts(
                "aggregate-checkpoint-policy-v1.schema.yaml",
                """
                {
                  "consumerGroup": "audit-projection",
                  "aggregateType": "BOARD_TASK",
                  "aggregateId": "4f9d267b-7492-47bc-8710-cae757175185",
                  "appliedVersion": -1,
                  "blocked": false,
                  "gapExpectedVersion": null,
                  "quarantinedVersion": null
                }
                """);
        assertSchemaAccepts(
                "event-store-convention-v1.schema.yaml",
                """
                {
                  "serviceOwner": "task-board-service",
                  "tables": [
                    "event_stream_head",
                    "domain_event",
                    "aggregate_snapshot",
                    "projection_checkpoint",
                    "outbox_event",
                    "inbox_message",
                    "consumer_aggregate_checkpoint"
                  ]
                }
                """);
        assertSchemaAccepts(
                "domain-event-envelope-v2.schema.yaml",
                """
                {
                  "envelopeVersion": 2,
                  "eventId": "479c9c2c-a7bd-4b04-912f-bfd02c45f956",
                  "eventType": "task-board.board-task.created.v1",
                  "eventVersion": 1,
                  "occurredAt": null,
                  "recordedAt": "2026-07-13T12:00:00Z",
                  "producer": "task-board-service",
                  "aggregateType": "BOARD_TASK",
                  "aggregateId": "4f9d267b-7492-47bc-8710-cae757175185",
                  "aggregateVersion": 0,
                  "correlation": {
                    "correlationId": "3c0e30d4-6dcb-463c-956f-9a7059fc34de",
                    "causationId": null
                  },
                  "actorRef": null,
                  "payload": {}
                }
                """);
    }

    @Test
    void rejectsMalformedYamlAndInvalidDocumentsInsteadOfRelyingOnTextFragments() {
        assertThatExceptionOfType(JsonProcessingException.class)
                .isThrownBy(() -> YAML.readTree("$schema: [unterminated"));
    }

    private static void assertSchemaAccepts(String fileName, String representativeDocument) throws IOException {
        JsonNode schemaDocument = YAML.readTree(Files.readString(findContract(fileName)));
        var schema = JSON_SCHEMA_FACTORY.getSchema(schemaDocument);

        assertThat(schema.validate(JSON.readTree(representativeDocument)))
                .as("representative document must satisfy %s", fileName)
                .isEmpty();
        assertThat(schema.validate(JSON.createObjectNode()))
                .as("required constraints in %s must reject an empty document", fileName)
                .isNotEmpty();
    }

    private static Path findContract(String fileName) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("contracts/events/technical").resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("canonical technical event contract was not found: " + fileName);
    }
}
