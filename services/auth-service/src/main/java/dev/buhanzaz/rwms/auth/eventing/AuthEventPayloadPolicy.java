package dev.buhanzaz.rwms.auth.eventing;

import dev.buhanzaz.rwms.auth.eventing.AuthEventPayloads.UserAuthorizationFact;
import dev.buhanzaz.rwms.auth.eventing.AuthEventPayloads.WorkerAccessFact;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class AuthEventPayloadPolicy {

    private static final Set<String> USER_FIELDS = Set.of(
            "subjectId", "active", "globalRole", "profileRevision", "warehouseAccess");
    private static final Set<String> WORKER_FIELDS =
            Set.of("subjectId", "workerLink", "warehouseId", "active", "credentialStatus");
    private static final Set<String> GRANT_FIELDS =
            Set.of("accessId", "warehouseId", "level", "active", "noteRevision");
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "username",
            "login",
            "firstname",
            "lastname",
            "fullname",
            "displayname",
            "email",
            "timezone",
            "timezoneid",
            "password",
            "passwordhash",
            "externalworkerid",
            "comment",
            "commenttext",
            "token",
            "secret",
            "key",
            "session");

    private final ObjectMapper objectMapper;

    public AuthEventPayloadPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode validateAndConvert(String eventType, Object payload) {
        if (!AuthEventTypes.ALL.contains(eventType)) {
            throw new IllegalArgumentException("Unsupported auth event type");
        }
        if (AuthEventTypes.USER_FACTS.contains(eventType) && !(payload instanceof UserAuthorizationFact)) {
            throw new IllegalArgumentException("User authorization event requires its exact safe payload");
        }
        if (AuthEventTypes.WORKER_FACTS.contains(eventType) && !(payload instanceof WorkerAccessFact)) {
            throw new IllegalArgumentException("Worker access event requires its exact safe payload");
        }
        JsonNode node = objectMapper.valueToTree(payload);
        validateNode(eventType, node);
        return node;
    }

    public void validateNode(String eventType, JsonNode payload) {
        if (!AuthEventTypes.ALL.contains(eventType)) {
            throw new IllegalArgumentException("Unsupported auth event type");
        }
        Set<String> expected = AuthEventTypes.USER_FACTS.contains(eventType) ? USER_FIELDS : WORKER_FIELDS;
        requireExactFields(payload, expected);
        if (AuthEventTypes.USER_FACTS.contains(eventType)) {
            JsonNode grants = payload.get("warehouseAccess");
            if (grants == null || !grants.isArray()) {
                throw new IllegalArgumentException("warehouseAccess must be an array");
            }
            grants.forEach(grant -> requireExactFields(grant, GRANT_FIELDS));
        }
        validateSensitiveValues(payload);
        try {
            if (AuthEventTypes.USER_FACTS.contains(eventType)) {
                objectMapper.readerFor(UserAuthorizationFact.class).readValue(payload);
            } else {
                objectMapper.readerFor(WorkerAccessFact.class).readValue(payload);
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Auth event payload failed typed semantic validation");
        }
    }

    public void requireAggregateIdentity(JsonNode payload, UUID aggregateId) {
        JsonNode subjectId = payload == null ? null : payload.get("subjectId");
        if (subjectId == null || !subjectId.isTextual()) {
            throw new IllegalArgumentException("Auth event subjectId is required");
        }
        if (!aggregateId.equals(UUID.fromString(subjectId.stringValue()))) {
            throw new IllegalArgumentException("Auth event aggregateId must match payload subjectId");
        }
    }

    public void requireAggregateType(String eventType, AuthAggregateType aggregateType) {
        boolean valid = aggregateType == AuthAggregateType.USER_AUTHORIZATION
                ? AuthEventTypes.USER_FACTS.contains(eventType)
                : AuthEventTypes.WORKER_FACTS.contains(eventType);
        if (!valid) {
            throw new IllegalArgumentException("Auth event type does not match aggregate family");
        }
    }

    private static void requireExactFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Auth event payload must be an object");
        }
        Set<String> actual = new HashSet<>();
        node.properties().forEach(entry -> actual.add(entry.getKey()));
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("Auth event payload does not match its exact schema");
        }
    }

    private static void validateSensitiveValues(JsonNode node) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (FORBIDDEN_FIELDS.contains(normalize(entry.getKey()))) {
                    throw new IllegalArgumentException("Auth event payload contains a forbidden field");
                }
                validateSensitiveValues(entry.getValue());
            });
            return;
        }
        if (node.isArray()) {
            node.forEach(AuthEventPayloadPolicy::validateSensitiveValues);
            return;
        }
        if (node.isTextual()
                && DomainEventEnvelopeV2.containsSensitiveTechnicalValue(node.stringValue())) {
            throw new IllegalArgumentException("Auth event payload contains a sensitive value");
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
