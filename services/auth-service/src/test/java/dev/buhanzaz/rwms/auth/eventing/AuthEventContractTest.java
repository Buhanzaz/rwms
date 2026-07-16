package dev.buhanzaz.rwms.auth.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AuthEventContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void canonicalSchemaMatchesRuntimeEventTypesTopicsAndExactFamilyBranches() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(contractPath()));
        Set<String> eventTypes = strings(schema.at("/properties/eventType/enum"));
        Set<String> topics = strings(schema.get("x-rwms-topics"));
        Set<String> dltTopics = strings(schema.get("x-rwms-sanitized-dlt-topics"));
        Set<String> userBranch = strings(schema.at("/oneOf/0/properties/eventType/enum"));
        Set<String> workerBranch = strings(schema.at("/oneOf/1/properties/eventType/enum"));
        Set<String> requiredEnvelopeFields = strings(schema.get("required"));

        assertThat(eventTypes).isEqualTo(AuthEventTypes.ALL);
        assertThat(userBranch).isEqualTo(AuthEventTypes.USER_FACTS);
        assertThat(workerBranch).isEqualTo(AuthEventTypes.WORKER_FACTS);
        assertThat(topics).containsExactlyInAnyOrder(
                AuthAggregateType.USER_AUTHORIZATION.topic(),
                AuthAggregateType.WORKER_ACCESS.topic());
        assertThat(dltTopics).containsExactlyInAnyOrder(
                AuthAggregateType.USER_AUTHORIZATION.topic() + ".auth-shadow-v1.dlt",
                AuthAggregateType.WORKER_ACCESS.topic() + ".auth-shadow-v1.dlt");
        assertThat(schema.at("/oneOf/0/properties/aggregateType/const").stringValue())
                .isEqualTo(AuthAggregateType.USER_AUTHORIZATION.name());
        assertThat(schema.at("/oneOf/1/properties/aggregateType/const").stringValue())
                .isEqualTo(AuthAggregateType.WORKER_ACCESS.name());
        assertThat(schema.at("/$defs/workerAccess/description").stringValue())
                .contains("auth-owned opaque subject UUID")
                .contains("not the task-board workerId");
        assertThat(schema.at("/x-rwms-cutover/taskBoardWorkerCorrelation").stringValue())
                .contains("UNKNOWN", "F4T");
        assertThat(requiredEnvelopeFields).containsExactlyInAnyOrder(
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
        assertThat(schema.get("additionalProperties").booleanValue()).isFalse();
        assertThat(schema.at("/properties/producer/const").stringValue()).isEqualTo("auth-service");
        assertThat(schema.at("/properties/envelopeVersion/const").intValue()).isEqualTo(2);
    }

    @Test
    void schemasForbidAdditionalPayloadFieldsThatCouldCarryPiiOrSecrets() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(contractPath()));
        assertThat(schema.at("/$defs/userAuthorization/additionalProperties").booleanValue())
                .isFalse();
        assertThat(schema.at("/$defs/workerAccess/additionalProperties").booleanValue())
                .isFalse();
        assertThat(schema.at("/$defs/warehouseGrant/additionalProperties").booleanValue())
                .isFalse();
        assertThat(schema.at("/$defs/sanitizedDltBody/additionalProperties").booleanValue())
                .isFalse();
        assertThat(strings(schema.at("/$defs/sanitizedDltBody/properties/failureCode/enum")))
                .containsExactlyInAnyOrder("VALIDATION_REJECTED", "PROCESSING_FAILED");
        assertThat(schema.at("/$defs/sanitizedDltBody/properties/messageSha256/pattern").stringValue())
                .isEqualTo("^[0-9a-f]{64}$");
        String contract = Files.readString(contractPath()).toLowerCase(java.util.Locale.ROOT);
        assertThat(contract).doesNotContain("passwordhash", "externalworkerid", "commenttext");
    }

    private Set<String> strings(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.stringValue()));
        return Set.copyOf(values);
    }

    private Path contractPath() {
        Path fromService = Path.of("../../contracts/events/auth/auth-events-v1.schema.json").normalize();
        return Files.isRegularFile(fromService)
                ? fromService
                : Path.of("contracts/events/auth/auth-events-v1.schema.json");
    }
}
