package dev.buhanzaz.rwms.auth.config;

import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rwms.auth.oauth")
public record OAuthClientProperties(List<Client> clients) {

    static final String INVENTORY_CLIENT_ID = "inventory-service";
    static final String INVENTORY_AUDIENCE = "rwms-services";
    static final String INVENTORY_SECRET_ENVIRONMENT = "INVENTORY_CLIENT_SECRET";
    static final Set<String> INVENTORY_SCOPES =
            Set.of("warehouse.read", "asset.inventory", "maintenance.inventory");
    static final String LOGISTICS_CLIENT_ID = "logistics-service";
    static final String LOGISTICS_AUDIENCE = "rwms-services";
    static final String LOGISTICS_SECRET_ENVIRONMENT = "LOGISTICS_CLIENT_SECRET";
    static final Set<String> LOGISTICS_SCOPES = Set.of(
            "warehouse.logistics",
            "asset.logistics",
            "task-board.logistics",
            "maintenance.logistics",
            "media.logistics");

    public OAuthClientProperties {
        clients = clients == null ? List.of() : List.copyOf(clients);
    }

    public Optional<Client> find(String clientId) {
        return clients.stream().filter(client -> client.clientId().equals(clientId)).findFirst();
    }

    public Set<String> reservedClientIds() {
        return clients.stream()
                .map(Client::clientId)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public record Client(
            String clientId,
            String clientName,
            boolean enabled,
            long revision,
            Set<String> authenticationMethods,
            Set<String> grantTypes,
            Set<String> redirectUris,
            Set<String> postLogoutRedirectUris,
            Set<String> scopes,
            boolean requireProofKey,
            Set<PrincipalType> allowedPrincipalTypes,
            Set<String> audiences,
            Set<String> allowedOrigins,
            Duration accessTokenTtl,
            String secretEnvironment,
            String developmentSecret,
            boolean revokeAuthorizations) {

        public Client {
            authenticationMethods = copy(authenticationMethods);
            grantTypes = copy(grantTypes);
            redirectUris = copy(redirectUris);
            postLogoutRedirectUris = copy(postLogoutRedirectUris);
            scopes = copy(scopes);
            allowedPrincipalTypes = allowedPrincipalTypes == null
                    ? Set.of()
                    : Set.copyOf(allowedPrincipalTypes);
            audiences = copy(audiences);
            allowedOrigins = copy(allowedOrigins);
            accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(5) : accessTokenTtl;
        }

        private static Set<String> copy(Set<String> values) {
            return values == null ? Set.of() : Set.copyOf(values);
        }

        boolean inventoryServiceClient() {
            return INVENTORY_CLIENT_ID.equals(clientId);
        }

        boolean logisticsServiceClient() {
            return LOGISTICS_CLIENT_ID.equals(clientId);
        }

        @Override
        public String toString() {
            return "OAuthClient[clientId=" + clientId + ", enabled=" + enabled + ", revision=" + revision + "]";
        }
    }
}
