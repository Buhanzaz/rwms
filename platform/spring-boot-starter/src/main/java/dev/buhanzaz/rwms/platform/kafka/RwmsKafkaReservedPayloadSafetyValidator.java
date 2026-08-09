package dev.buhanzaz.rwms.platform.kafka;

import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Rejects reserved envelope names and sensitive technical values from a canonical event payload before publication. */
final class RwmsKafkaReservedPayloadSafetyValidator {

    private static final Set<String> RESERVED_KEYS = Set.of(
            "password",
            "passwordhash",
            "credential",
            "credentialmaterial",
            "secret",
            "clientsecret",
            "authorization",
            "apikey",
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "oauthtoken",
            "token",
            "privatekey",
            "signingkey",
            "signedurl",
            "presignedurl",
            "email",
            "login",
            "username",
            "displayname",
            "assignee",
            "firstname",
            "lastname",
            "fullname",
            "phone",
            "phonenumber");
    public void validate(JsonNode payload) {
        validateNode(payload);
    }

    private static void validateNode(JsonNode node) {
        if (node.isObject()) {
            for (var property : node.properties()) {
                if (RESERVED_KEYS.contains(normalize(property.getKey()))) {
                    throw new IllegalArgumentException("event payload contains a reserved field");
                }
                validateNode(property.getValue());
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(RwmsKafkaReservedPayloadSafetyValidator::validateNode);
            return;
        }
        if (node.isTextual()
                && DomainEventEnvelopeV2.containsSensitiveTechnicalValue(node.stringValue())) {
            throw new IllegalArgumentException("event payload contains a sensitive value");
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
