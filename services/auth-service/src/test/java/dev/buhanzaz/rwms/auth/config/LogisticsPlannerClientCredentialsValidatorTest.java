package dev.buhanzaz.rwms.auth.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/** Verifies the exact token-request boundary reserved for the standalone logistics planner. */
class LogisticsPlannerClientCredentialsValidatorTest {

    @Test
    void acceptsOnlyThePlanningScopeAndExactOptionalOverrides() {
        Map<String, Object> exactOverrides = Map.of(
                "principal_type", "SERVICE",
                "sub", "logistics-planner",
                "subject", "logistics-planner",
                "client_id", "logistics-planner",
                "audience", "rwms-services",
                "resource", "rwms-services");

        assertThatCode(() -> AuthorizationServerConfiguration.validateLogisticsPlannerRequest(
                        context(Set.of("logistics.planning"), exactOverrides)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingCombinedAndForeignScopes() {
        for (Set<String> scopes : Set.of(
                Set.<String>of(),
                Set.of("logistics.planning", "warehouse.read"),
                Set.of("logistics.inventory"),
                Set.of("rwms.write"))) {
            assertThatThrownBy(() -> AuthorizationServerConfiguration.validateLogisticsPlannerRequest(
                            context(scopes, Map.of())))
                    .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                            org.assertj.core.api.Assertions.assertThat(exception.getError().getErrorCode())
                                    .isEqualTo("invalid_scope"));
        }
    }

    @Test
    void rejectsIdentityAndAudienceOverrides() {
        for (Map<String, Object> overrides : Set.of(
                Map.<String, Object>of("principal_type", "USER"),
                Map.<String, Object>of("sub", "logistics-service"),
                Map.<String, Object>of("subject", "logistics-service"),
                Map.<String, Object>of(
                        "client_id", new String[] {"logistics-planner", "logistics-planner"}),
                Map.<String, Object>of("audience", "other-audience"),
                Map.<String, Object>of("resource", "other-audience"))) {
            assertThatThrownBy(() -> AuthorizationServerConfiguration.validateLogisticsPlannerRequest(
                            context(Set.of("logistics.planning"), overrides)))
                    .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                            org.assertj.core.api.Assertions.assertThat(exception.getError().getErrorCode())
                                    .isEqualTo("invalid_request"));
        }
    }

    @Test
    void leavesOtherClientCredentialsRequestsUnchanged() {
        assertThatCode(() -> AuthorizationServerConfiguration.validateLogisticsPlannerRequest(
                        context("logistics-service", Set.of("warehouse.logistics"), Map.of("sub", "unexpected"))))
                .doesNotThrowAnyException();
    }

    private OAuth2ClientCredentialsAuthenticationContext context(
            Set<String> scopes, Map<String, Object> additionalParameters) {
        return context(OAuthClientProperties.LOGISTICS_PLANNER_CLIENT_ID, scopes, additionalParameters);
    }

    private OAuth2ClientCredentialsAuthenticationContext context(
            String clientId, Set<String> scopes, Map<String, Object> additionalParameters) {
        var clientPrincipal = new TestingAuthenticationToken(clientId, "credential");
        var authentication = new OAuth2ClientCredentialsAuthenticationToken(
                clientPrincipal, scopes, additionalParameters);
        RegisteredClient registeredClient = RegisteredClient.withId(clientId + "-id")
                .clientId(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scopes(values -> values.addAll(scopes))
                .build();
        return OAuth2ClientCredentialsAuthenticationContext.with(authentication)
                .registeredClient(registeredClient)
                .build();
    }
}
