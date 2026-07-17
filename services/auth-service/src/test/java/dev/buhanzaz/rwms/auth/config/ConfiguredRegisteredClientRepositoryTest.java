package dev.buhanzaz.rwms.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

class ConfiguredRegisteredClientRepositoryTest {

    @Test
    void enabledLookupsReturnStoredInventoryAndOrdinaryClientsWithoutRequestDependentMutation() {
        RegisteredClient inventory = serviceClient(
                "inventory-id",
                "inventory-service",
                Set.of("warehouse.read", "asset.inventory", "maintenance.inventory"));
        RegisteredClient ordinary = serviceClient("ordinary-id", "ordinary-service", Set.of("ordinary.read"));
        RegisteredClientRepository delegate = mock(RegisteredClientRepository.class);
        when(delegate.findById("inventory-id")).thenReturn(inventory);
        when(delegate.findByClientId("inventory-service")).thenReturn(inventory);
        when(delegate.findByClientId("ordinary-service")).thenReturn(ordinary);
        var repository = new ConfiguredRegisteredClientRepository(
                delegate,
                new OAuthClientProperties(List.of(
                        configuredClient(
                                "inventory-service",
                                true,
                                Set.of("warehouse.read", "asset.inventory", "maintenance.inventory")),
                        configuredClient("ordinary-service", true, Set.of("ordinary.read")))));

        assertThat(repository.findById("inventory-id")).isSameAs(inventory);
        assertThat(repository.findByClientId("inventory-service")).isSameAs(inventory);
        assertThat(repository.findByClientId("ordinary-service")).isSameAs(ordinary);
        assertThat(repository.findByClientId("inventory-service").getScopes())
                .containsExactlyInAnyOrder("warehouse.read", "asset.inventory", "maintenance.inventory");
    }

    @Test
    void disabledAndOmittedClientsAreHiddenWithoutDeletingStoredRows() {
        RegisteredClient stored = RegisteredClient.withId("stored-id")
                .clientId("disabled-service")
                .clientName("Disabled")
                .clientSecret("{noop}secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("internal.read")
                .build();
        RegisteredClientRepository delegate = mock(RegisteredClientRepository.class);
        when(delegate.findById("stored-id")).thenReturn(stored);
        when(delegate.findByClientId("disabled-service")).thenReturn(stored);
        var properties = new OAuthClientProperties(List.of(new OAuthClientProperties.Client(
                "disabled-service",
                "Disabled",
                false,
                1,
                Set.of("client_secret_basic"),
                Set.of("client_credentials"),
                Set.of(),
                Set.of(),
                Set.of("internal.read"),
                false,
                Set.of(),
                Set.of("rwms-services"),
                Set.of(),
                Duration.ofMinutes(5),
                "DISABLED_SECRET",
                null,
                false)));
        var repository = new ConfiguredRegisteredClientRepository(delegate, properties);

        assertThat(repository.findById("stored-id")).isNull();
        assertThat(repository.findByClientId("disabled-service")).isNull();
        assertThat(repository.findByClientId("omitted-service")).isNull();
    }

    private RegisteredClient serviceClient(String id, String clientId, Set<String> scopes) {
        return RegisteredClient.withId(id)
                .clientId(clientId)
                .clientName(clientId)
                .clientSecret("{noop}secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scopes(values -> values.addAll(scopes))
                .build();
    }

    private OAuthClientProperties.Client configuredClient(String clientId, boolean enabled, Set<String> scopes) {
        return new OAuthClientProperties.Client(
                clientId,
                clientId,
                enabled,
                1,
                Set.of("client_secret_basic"),
                Set.of("client_credentials"),
                Set.of(),
                Set.of(),
                scopes,
                false,
                Set.of(),
                Set.of("rwms-services"),
                Set.of(),
                Duration.ofMinutes(5),
                clientId.toUpperCase(java.util.Locale.ROOT).replace('-', '_') + "_SECRET",
                null,
                false);
    }
}
