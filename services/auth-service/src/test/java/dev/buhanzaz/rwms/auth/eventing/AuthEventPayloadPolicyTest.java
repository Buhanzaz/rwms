package dev.buhanzaz.rwms.auth.eventing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.eventing.AuthEventPayloads.UserAuthorizationFact;
import dev.buhanzaz.rwms.auth.eventing.AuthEventPayloads.WorkerAccessFact;
import dev.buhanzaz.rwms.auth.eventing.AuthEventPayloads.WorkerCredentialFactStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AuthEventPayloadPolicyTest {

    private final AuthEventPayloadPolicy policy = new AuthEventPayloadPolicy(new ObjectMapper());

    @Test
    void acceptsOnlyTheApprovedNonSecretFactShapes() {
        UUID subjectId = UUID.randomUUID();

        assertThatCode(() -> policy.validateAndConvert(
                        AuthEventTypes.USER_CHANGED,
                        new UserAuthorizationFact(
                                subjectId,
                                true,
                                UserGlobalRole.VIEWER,
                                UUID.randomUUID(),
                                List.of())))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validateAndConvert(
                        AuthEventTypes.WORKER_CONFIGURED,
                        new WorkerAccessFact(
                                subjectId,
                                subjectId,
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                true,
                                WorkerCredentialFactStatus.ACTIVE)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPiiCanariesBeforePersistence() {
        var malicious = new ObjectMapper().createObjectNode();
        malicious.put("subjectId", UUID.randomUUID().toString());
        malicious.put("workerLink", UUID.randomUUID().toString());
        malicious.put("warehouseId", UUID.randomUUID().toString());
        malicious.put("active", true);
        malicious.put("credentialStatus", "ACTIVE");
        malicious.put("password", "pii-canary-password");

        assertThatThrownBy(() -> policy.validateNode(AuthEventTypes.WORKER_CONFIGURED, malicious))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact schema");
    }

    @Test
    void rejectsTypedSemanticTamperingAndCrossFamilyUse() {
        UUID subjectId = UUID.randomUUID();
        var invalid = new ObjectMapper().createObjectNode();
        invalid.put("subjectId", subjectId.toString());
        invalid.put("workerLink", UUID.randomUUID().toString());
        invalid.put("warehouseId", UUID.randomUUID().toString());
        invalid.put("active", true);
        invalid.put("credentialStatus", "DISABLED");

        assertThatThrownBy(() -> policy.validateNode(AuthEventTypes.WORKER_CONFIGURED, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semantic validation");
        assertThatThrownBy(() -> policy.requireAggregateType(
                        AuthEventTypes.USER_CHANGED, AuthAggregateType.WORKER_ACCESS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.requireAggregateIdentity(invalid, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPayloadClassOrEventTypeMismatch() {
        UUID subjectId = UUID.randomUUID();

        assertThatThrownBy(() -> policy.validateAndConvert(
                        AuthEventTypes.USER_CHANGED,
                        new WorkerAccessFact(
                                subjectId,
                                subjectId,
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                true,
                                WorkerCredentialFactStatus.ACTIVE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact safe payload");
    }
}
