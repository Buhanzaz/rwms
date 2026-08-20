package dev.buhanzaz.rwms.auth.config;

import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Declarative source of truth for OAuth client registrations managed by auth-service.
 *
 * <p>The provisioner validates this configuration against the supported public-PKCE and
 * service-client contracts, then reconciles it with the authorization-server tables. The immutable
 * copy prevents later configuration mutation from changing the security decision for a request.</p>
 *
 * @param clients managed OAuth client declarations
 */
@ConfigurationProperties("rwms.auth.oauth")
public record OAuthClientProperties(List<Client> clients) {

    static final String ASSET_CLIENT_ID = "asset-service";
    static final String ASSET_AUDIENCE = "rwms-services";
    static final String ASSET_SECRET_ENVIRONMENT = "ASSET_WAREHOUSE_CLIENT_SECRET";
    static final Set<String> ASSET_SCOPES = Set.of(
            "warehouse.read",
            "warehouse.timezone.read",
            "warehouse.operation.mark",
            "warehouse.lifecycle.read",
            "warehouse.lifecycle.confirm",
            "media.asset-import");
    static final String INVENTORY_CLIENT_ID = "inventory-service";
    static final String TASK_BOARD_CLIENT_ID = "task-board-service";
    static final String TASK_BOARD_AUDIENCE = "rwms-services";
    static final Set<String> TASK_BOARD_SCOPES =
            Set.of(
                    "worker-credentials.manage",
                    "warehouse.timezone.read",
                    "warehouse.lifecycle.read",
                    "warehouse.lifecycle.confirm");
    static final String WORKER_ANDROID_CLIENT_ID = "rwms-worker-android";
    static final String DRIVER_ANDROID_CLIENT_ID = "rwms-driver-android";
    public static final String MANAGER_ANDROID_CLIENT_ID = "rwms-manager-android";
    static final Set<String> MANAGER_ANDROID_SCOPES = Set.of(
            "openid",
            "profile",
            "offline_access",
            "rwms.read",
            "rwms.write",
            "warehouse.read");
    static final String INVENTORY_AUDIENCE = "rwms-services";
    static final String INVENTORY_SECRET_ENVIRONMENT = "INVENTORY_CLIENT_SECRET";
    static final Set<String> INVENTORY_SCOPES =
            Set.of(
                    "warehouse.timezone.read",
                    "warehouse.operation.mark",
                    "warehouse.lifecycle.read",
                    "warehouse.lifecycle.confirm",
                    "asset.inventory",
                    "maintenance.inventory",
                    "logistics.inventory",
                    "media.inventory");
    static final String LOGISTICS_CLIENT_ID = "logistics-service";
    static final String LOGISTICS_AUDIENCE = "rwms-services";
    static final String LOGISTICS_SECRET_ENVIRONMENT = "LOGISTICS_CLIENT_SECRET";
    static final Set<String> LOGISTICS_SCOPES = Set.of(
            "warehouse.logistics",
            "warehouse.timezone.read",
            "warehouse.operation.mark",
            "warehouse.lifecycle.read",
            "warehouse.lifecycle.confirm",
            "asset.logistics",
            "task-board.logistics",
            "maintenance.logistics",
            "media.logistics");

    /** Copies the bound declarations so later configuration mutation cannot alter a security decision. */
    public OAuthClientProperties {
        clients = clients == null ? List.of() : List.copyOf(clients);
    }

    /**
     * Returns a configured client declaration by its case-sensitive OAuth client identifier.
     *
     * @param clientId OAuth client identifier
     * @return the configured client when present
     */
    public Optional<Client> find(String clientId) {
        return clients.stream().filter(client -> client.clientId().equals(clientId)).findFirst();
    }

    /**
     * Returns normalized identifiers reserved by configured OAuth clients.
     *
     * <p>The result is used by principal naming rules so a human or worker principal cannot claim an
     * identifier that would be ambiguous with an OAuth client.</p>
     *
     * @return lower-case immutable set of configured client identifiers
     */
    public Set<String> reservedClientIds() {
        return clients.stream()
                .map(Client::clientId)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * One managed OAuth registration and its protocol, token, and browser-origin policy.
     *
     * <p>Values are copied into immutable sets and defaults are supplied only for token settings.
     * The {@link OAuthClientProvisioner} rejects combinations that do not match a supported client
     * contract; a declaration is not an unrestricted OAuth policy surface.</p>
     *
     * @param clientId public OAuth client identifier
     * @param clientName administrative name stored with the registration
     * @param enabled whether the client may be resolved by protocol requests
     * @param revision monotonic configuration revision used to prevent unsafe unnoticed changes
     * @param authenticationMethods allowed OAuth client-authentication methods
     * @param grantTypes allowed OAuth grant types
     * @param redirectUris authorized authorization-code callback URIs
     * @param postLogoutRedirectUris authorized OIDC post-logout redirect URIs
     * @param scopes scopes the client may request
     * @param requireProofKey whether authorization-code requests require PKCE
     * @param allowedPrincipalTypes principal types allowed to use a public client
     * @param audiences token audiences assigned to this client
     * @param allowedOrigins CORS origins associated with an interactive client
     * @param accessTokenTtl access-token lifetime
     * @param refreshTokenTtl refresh-token lifetime when refresh tokens are enabled
     * @param reuseRefreshTokens whether refresh tokens are reused instead of rotated
     * @param secretEnvironment environment property containing a service-client secret
     * @param developmentSecret development-only secret fallback
     * @param revokeAuthorizations whether the next new revision must revoke existing grants
     */
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
            Duration refreshTokenTtl,
            Boolean reuseRefreshTokens,
            String secretEnvironment,
            String developmentSecret,
            boolean revokeAuthorizations) {

        /** Copies set-valued policy and applies only the documented token-setting defaults. */
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
            refreshTokenTtl =
                    refreshTokenTtl == null ? Duration.ofHours(1) : refreshTokenTtl;
            reuseRefreshTokens =
                    reuseRefreshTokens == null ? Boolean.TRUE : reuseRefreshTokens;
        }

        private static Set<String> copy(Set<String> values) {
            return values == null ? Set.of() : Set.copyOf(values);
        }

        /**
         * Identifies the inventory-service machine client so its exact least-privilege contract can be
         * enforced during provisioning.
         *
         * @return whether this is the reserved inventory-service client
         */
        boolean inventoryServiceClient() {
            return INVENTORY_CLIENT_ID.equals(clientId);
        }

        /**
         * Identifies the asset-service machine client so its exact least-privilege contract can be
         * enforced during provisioning.
         *
         * @return whether this is the reserved asset-service client
         */
        boolean assetServiceClient() {
            return ASSET_CLIENT_ID.equals(clientId);
        }

        /**
         * Identifies the logistics-service machine client so its exact least-privilege contract can
         * be enforced during provisioning.
         *
         * @return whether this is the reserved logistics-service client
         */
        boolean logisticsServiceClient() {
            return LOGISTICS_CLIENT_ID.equals(clientId);
        }

        /**
         * Identifies the manager Android public client so its redirect and PKCE contract can be
         * enforced during provisioning.
         *
         * @return whether this is the reserved manager Android client
         */
        boolean managerAndroidClient() {
            return MANAGER_ANDROID_CLIENT_ID.equals(clientId);
        }

        @Override
        public String toString() {
            return "OAuthClient[clientId=" + clientId + ", enabled=" + enabled + ", revision=" + revision + "]";
        }
    }
}
