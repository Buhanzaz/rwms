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
}
