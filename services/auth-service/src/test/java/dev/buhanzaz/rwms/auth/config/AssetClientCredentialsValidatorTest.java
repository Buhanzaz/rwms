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

class AssetClientCredentialsValidatorTest {

    @Test
    void acceptsOneExactScopeAndOnlyExactOptionalOverrides() {
        Map<String, Object> exactOverrides = Map.of(
                "principal_type", "SERVICE",
                "sub", "asset-service",
                "subject", "asset-service",
                "client_id", "asset-service",
                "audience", "rwms-services",
                "resource", "rwms-services");

        assertThatCode(() -> AuthorizationServerConfiguration.validateAssetDownstreamRequest(
                        context("asset-service", Set.of("media.asset-import"), exactOverrides)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuthorizationServerConfiguration.validateAssetDownstreamRequest(
                        context("asset-service", Set.of("media.asset"), exactOverrides)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsOmittedCombinedAndForeignScopesAsInvalidScope() {
        for (Set<String> scopes : Set.of(
                Set.<String>of(),
                Set.of("warehouse.read", "media.asset-import"),
                Set.of("media.asset", "media.asset-import"),
                Set.of("rwms.write"),
                Set.of("foreign.scope"))) {
            assertThatThrownBy(() -> AuthorizationServerConfiguration.validateAssetDownstreamRequest(
                            context("asset-service", scopes, Map.of())))
                    .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                            org.assertj.core.api.Assertions.assertThat(exception.getError().getErrorCode())
                                    .isEqualTo("invalid_scope"));
        }
    }

    @Test
    void rejectsWrongOrDuplicateOverridesAsInvalidRequest() {
        for (Map<String, Object> overrides : Set.of(
                Map.<String, Object>of("principal_type", "USER"),
                Map.<String, Object>of("sub", "other-service"),
                Map.<String, Object>of("subject", "other-service"),
                Map.<String, Object>of("client_id", new String[] {"asset-service", "asset-service"}),
                Map.<String, Object>of("audience", "other-audience"),
                Map.<String, Object>of("resource", "other-audience"))) {
            assertThatThrownBy(() -> AuthorizationServerConfiguration.validateAssetDownstreamRequest(
                            context("asset-service", Set.of("media.asset-import"), overrides)))
                    .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                            org.assertj.core.api.Assertions.assertThat(exception.getError().getErrorCode())
                                    .isEqualTo("invalid_request"));
        }
    }

    @Test
    void leavesOtherClientCredentialValidatorsUnchanged() {
        assertThatCode(() -> AuthorizationServerConfiguration.validateAssetDownstreamRequest(
                        context("maintenance-service", Set.of("asset.maintenance"), Map.of("sub", "unexpected"))))
                .doesNotThrowAnyException();
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
