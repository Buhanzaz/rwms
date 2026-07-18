package dev.buhanzaz.rwms.auth.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OAuthClientProvisioner implements ApplicationRunner {

    static final String MANAGED_SETTING = "rwms.client.managed";
    static final String ENABLED_SETTING = "rwms.client.enabled";
    static final String REVISION_SETTING = "rwms.client.revision";
    static final String FINGERPRINT_SETTING = "rwms.client.configuration-fingerprint";
    static final String REVOKED_REVISION_SETTING = "rwms.client.revoked-revision";

    private final RegisteredClientRepository clients;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Environment environment;
    private final AuthProperties authProperties;
    private final OAuthClientProperties properties;

    public OAuthClientProvisioner(
            @Qualifier("jdbcRegisteredClientRepository") RegisteredClientRepository clients,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            Environment environment,
            AuthProperties authProperties,
            OAuthClientProperties properties) {
        this.clients = clients;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.environment = environment;
        this.authProperties = authProperties;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ValidatedClient> validated = validateConfiguration();
        transactions.executeWithoutResult(status -> {
            acquireProvisioningLock();
            rejectOmittedManagedClients(validated);
            validated.forEach(this::reconcile);
        });
    }

    private void acquireProvisioningLock() {
        String database = jdbc.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
        if (database != null && "PostgreSQL".equalsIgnoreCase(database.trim())) {
            jdbc.queryForObject(
                    "select pg_advisory_xact_lock(hashtextextended('rwms-auth:oauth-client-provisioning', 0))",
                    Object.class);
        }
    }

    private void rejectOmittedManagedClients(List<ValidatedClient> validated) {
        Set<String> configuredIds = validated.stream()
                .map(value -> value.configuration().clientId())
                .collect(java.util.stream.Collectors.toSet());
        List<String> managedIds = jdbc.queryForList(
                "select client_id from oauth2_registered_client "
                        + "where client_settings like '%\"rwms.client.managed\":true%'",
                String.class);
        managedIds.stream()
                .filter(clientId -> !configuredIds.contains(clientId))
                .findFirst()
                .ifPresent(clientId -> {
                    throw new IllegalStateException(
                            "Managed OAuth client is omitted; configure enabled=false explicitly: " + clientId);
                });
    }

    private void reconcile(ValidatedClient validated) {
        OAuthClientProperties.Client configured = validated.configuration();
        RegisteredClient existing = clients.findByClientId(configured.clientId());
        Long existingRevision = settingAsLong(existing, REVISION_SETTING);
        String existingFingerprint = settingAsString(existing, FINGERPRINT_SETTING);

        if (configured.revokeAuthorizations() && existing == null) {
            throw new IllegalStateException(
                    "OAuth authorization revocation requires an existing client: " + configured.clientId());
        }

        if (existingRevision != null && configured.revision() < existingRevision) {
            throw new IllegalStateException("OAuth client revision rollback is forbidden: " + configured.clientId());
        }
        if (existingRevision != null
                && configured.revision() == existingRevision
                && existingFingerprint != null
                && !existingFingerprint.equals(validated.fingerprint())) {
            throw new IllegalStateException(
                    "OAuth client configuration changed without revision increment: " + configured.clientId());
        }

        String encodedSecret = encodedSecret(existing, validated);
        if (existingRevision != null
                && configured.revision() == existingRevision
                && existing != null
                && existing.getClientSecret() != null
                && encodedSecret != null
                && !existing.getClientSecret().equals(encodedSecret)) {
            throw new IllegalStateException(
                    "OAuth client secret changed without revision increment: " + configured.clientId());
        }

        Long revokedRevision = settingAsLong(existing, REVOKED_REVISION_SETTING);
        if (configured.revokeAuthorizations()
                && existing != null
                && !Objects.equals(revokedRevision, configured.revision())
                && existingRevision != null
                && configured.revision() <= existingRevision) {
            throw new IllegalStateException(
                    "OAuth authorization revocation requires a new client revision: " + configured.clientId());
        }
        boolean revoke = configured.revokeAuthorizations()
                && !Objects.equals(revokedRevision, configured.revision());
        if (revoke && existing != null) {
            revoke(existing.getId());
            revokedRevision = configured.revision();
        }

        RegisteredClient desired = buildClient(
                existing, validated, encodedSecret, revokedRevision);
        if (!equivalent(existing, desired)) {
            clients.save(desired);
        }
    }

    private RegisteredClient buildClient(
            RegisteredClient existing,
            ValidatedClient validated,
            String encodedSecret,
            Long revokedRevision) {
        OAuthClientProperties.Client configured = validated.configuration();
        RegisteredClient.Builder builder = existing == null
                ? RegisteredClient.withId(UUID.randomUUID().toString())
                        .clientId(configured.clientId())
                        .clientIdIssuedAt(Instant.now())
                : RegisteredClient.from(existing);
        ClientSettings.Builder clientSettings = ClientSettings.builder()
                .requireProofKey(configured.requireProofKey())
                .requireAuthorizationConsent(false)
                .setting(MANAGED_SETTING, true)
                .setting(ENABLED_SETTING, configured.enabled())
                .setting(REVISION_SETTING, Long.toString(configured.revision()))
                .setting(FINGERPRINT_SETTING, validated.fingerprint());
        if (revokedRevision != null) {
            clientSettings.setting(REVOKED_REVISION_SETTING, Long.toString(revokedRevision));
        }
        builder.clientName(configured.clientName())
                .clientSecret(encodedSecret)
                .clientAuthenticationMethods(methods -> replace(methods, validated.authenticationMethods()))
                .authorizationGrantTypes(types -> replace(types, validated.grantTypes()))
                .redirectUris(uris -> replace(uris, configured.redirectUris()))
                .postLogoutRedirectUris(uris -> replace(uris, configured.postLogoutRedirectUris()))
                .scopes(scopes -> replace(scopes, configured.scopes()))
                .clientSettings(clientSettings.build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(configured.accessTokenTtl())
                        .build());
        return builder.build();
    }

    private String encodedSecret(RegisteredClient existing, ValidatedClient validated) {
        if (!validated.configuration().enabled()) {
            return existing == null ? null : existing.getClientSecret();
        }
        String rawSecret = validated.rawSecret();
        if (rawSecret == null) {
            return null;
        }
        if (existing != null
                && existing.getClientSecret() != null
                && passwordEncoder.matches(rawSecret, existing.getClientSecret())) {
            return existing.getClientSecret();
        }
        return passwordEncoder.encode(rawSecret);
    }

    private void revoke(String registeredClientId) {
        jdbc.update(
                "delete from oauth2_authorization_consent where registered_client_id = ?", registeredClientId);
        jdbc.update("delete from oauth2_authorization where registered_client_id = ?", registeredClientId);
    }

    private List<ValidatedClient> validateConfiguration() {
        if (properties.clients().isEmpty()) {
            throw new IllegalStateException("At least one OAuth client must be configured");
        }
        Set<String> ids = new HashSet<>();
        List<ValidatedClient> validated = new ArrayList<>();
        for (OAuthClientProperties.Client client : properties.clients()) {
            requireText(client.clientId(), "client-id");
            requireText(client.clientName(), "client-name");
            if (!ids.add(client.clientId())) {
                throw new IllegalStateException("Duplicate OAuth client-id: " + client.clientId());
            }
            if (client.revision() < 1) {
                throw new IllegalStateException("OAuth client revision must be positive: " + client.clientId());
            }
            Set<ClientAuthenticationMethod> authenticationMethods = client.authenticationMethods().stream()
                    .map(this::authenticationMethod)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<AuthorizationGrantType> grantTypes = client.grantTypes().stream()
                    .map(this::grantType)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            validateGrantContract(client, authenticationMethods, grantTypes);
            validateUris(client);
            String rawSecret = resolveSecret(client, authenticationMethods);
            validated.add(new ValidatedClient(
                    client,
                    authenticationMethods,
                    grantTypes,
                    rawSecret,
                    fingerprint(client)));
        }
        return List.copyOf(validated);
    }

    private void validateGrantContract(
            OAuthClientProperties.Client client,
            Set<ClientAuthenticationMethod> authenticationMethods,
            Set<AuthorizationGrantType> grantTypes) {
        if (client.inventoryServiceClient()) {
            validateInventoryClientContract(client, authenticationMethods, grantTypes);
        }
        if (client.logisticsServiceClient()) {
            validateLogisticsClientContract(client, authenticationMethods, grantTypes);
        }
        if (!client.enabled()) {
            return;
        }
        if (client.scopes().isEmpty() || client.audiences().isEmpty()) {
            throw new IllegalStateException("OAuth scopes and audiences are required: " + client.clientId());
        }
        if (client.accessTokenTtl().isZero() || client.accessTokenTtl().isNegative()) {
            throw new IllegalStateException("OAuth access token TTL must be positive: " + client.clientId());
        }
        boolean authorizationCode = grantTypes.contains(AuthorizationGrantType.AUTHORIZATION_CODE);
        boolean clientCredentials = grantTypes.contains(AuthorizationGrantType.CLIENT_CREDENTIALS);
        if (authorizationCode == clientCredentials) {
            throw new IllegalStateException("OAuth client must use exactly one supported grant: " + client.clientId());
        }
        if (authorizationCode) {
            if (!authenticationMethods.equals(Set.of(ClientAuthenticationMethod.NONE))
                    || !client.requireProofKey()
                    || client.allowedPrincipalTypes().size() != 1
                    || client.redirectUris().isEmpty()) {
                throw new IllegalStateException(
                        "Authorization-code client must be public PKCE with one principal type: "
                                + client.clientId());
            }
        } else if (!authenticationMethods.equals(Set.of(ClientAuthenticationMethod.CLIENT_SECRET_BASIC))
                || !client.allowedPrincipalTypes().isEmpty()
                || !client.redirectUris().isEmpty()
                || !client.postLogoutRedirectUris().isEmpty()
                || client.scopes().contains("openid")
                || client.scopes().contains("profile")) {
            throw new IllegalStateException("Client-credentials contract is invalid: " + client.clientId());
        }
    }

    private void validateInventoryClientContract(
            OAuthClientProperties.Client client,
            Set<ClientAuthenticationMethod> authenticationMethods,
            Set<AuthorizationGrantType> grantTypes) {
        validateExactServiceClientContract(
                client,
                authenticationMethods,
                grantTypes,
                OAuthClientProperties.INVENTORY_SCOPES,
                OAuthClientProperties.INVENTORY_AUDIENCE,
                OAuthClientProperties.INVENTORY_SECRET_ENVIRONMENT);
    }

    private void validateLogisticsClientContract(
            OAuthClientProperties.Client client,
            Set<ClientAuthenticationMethod> authenticationMethods,
            Set<AuthorizationGrantType> grantTypes) {
        validateExactServiceClientContract(
                client,
                authenticationMethods,
                grantTypes,
                OAuthClientProperties.LOGISTICS_SCOPES,
                OAuthClientProperties.LOGISTICS_AUDIENCE,
                OAuthClientProperties.LOGISTICS_SECRET_ENVIRONMENT);
    }

    private void validateExactServiceClientContract(
            OAuthClientProperties.Client client,
            Set<ClientAuthenticationMethod> authenticationMethods,
            Set<AuthorizationGrantType> grantTypes,
            Set<String> expectedScopes,
            String expectedAudience,
            String expectedSecretEnvironment) {
        boolean repositorySecret = client.developmentSecret() != null && !client.developmentSecret().isBlank();
        if (!authenticationMethods.equals(Set.of(ClientAuthenticationMethod.CLIENT_SECRET_BASIC))
                || !grantTypes.equals(Set.of(AuthorizationGrantType.CLIENT_CREDENTIALS))
                || !client.scopes().equals(expectedScopes)
                || !client.audiences().equals(Set.of(expectedAudience))
                || !client.allowedPrincipalTypes().isEmpty()
                || !client.redirectUris().isEmpty()
                || !client.postLogoutRedirectUris().isEmpty()
                || !client.allowedOrigins().isEmpty()
                || client.requireProofKey()
                || client.revokeAuthorizations()
                || client.accessTokenTtl().isZero()
                || client.accessTokenTtl().isNegative()
                || !expectedSecretEnvironment.equals(client.secretEnvironment())
                || repositorySecret) {
            throw new IllegalStateException(
                    client.clientId()
                            + " OAuth client must use its exact SERVICE scopes, audience, and external secret");
        }
    }

    private void validateUris(OAuthClientProperties.Client client) {
        client.redirectUris().forEach(value -> validateUri(client.clientId(), value, false));
        client.postLogoutRedirectUris().forEach(value -> validateUri(client.clientId(), value, false));
        client.allowedOrigins().forEach(value -> validateUri(client.clientId(), value, true));
    }

    private void validateUri(String clientId, String value, boolean originOnly) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("OAuth URI is invalid for " + clientId, exception);
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalStateException("OAuth URI must be absolute and safe for " + clientId);
        }
        if (originOnly
                && ((uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
                        || uri.getQuery() != null)) {
            throw new IllegalStateException("OAuth CORS origin cannot contain path or query: " + clientId);
        }
        if (!authProperties.devDefaultCredentials() && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("OAuth URI must use HTTPS outside dev/test: " + clientId);
        }
    }

    private String resolveSecret(
            OAuthClientProperties.Client client,
            Set<ClientAuthenticationMethod> authenticationMethods) {
        if (!authenticationMethods.contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)) {
            return null;
        }
        if (!client.enabled()) {
            return null;
        }
        String secret = client.secretEnvironment() == null
                ? null
                : environment.getProperty(client.secretEnvironment());
        if ((secret == null || secret.isBlank()) && authProperties.devDefaultCredentials()) {
            secret = client.developmentSecret();
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "OAuth client secret environment is required for " + client.clientId());
        }
        if (!authProperties.devDefaultCredentials()
                && client.developmentSecret() != null
                && !client.developmentSecret().isBlank()) {
            throw new IllegalStateException("Development OAuth secret is forbidden outside dev/test");
        }
        return secret;
    }

    private String fingerprint(OAuthClientProperties.Client client) {
        String canonical = String.join(
                "|",
                client.clientId(),
                client.clientName(),
                Boolean.toString(client.enabled()),
                sorted(client.authenticationMethods()),
                sorted(client.grantTypes()),
                sorted(client.redirectUris()),
                sorted(client.postLogoutRedirectUris()),
                sorted(client.scopes()),
                sorted(client.allowedPrincipalTypes().stream()
                        .map(Enum::name)
                        .collect(java.util.stream.Collectors.toSet())),
                sorted(client.audiences()),
                sorted(client.allowedOrigins()),
                Boolean.toString(client.requireProofKey()),
                client.accessTokenTtl().toString(),
                Objects.toString(client.secretEnvironment(), ""));
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String sorted(Set<String> values) {
        return values.stream().sorted(Comparator.naturalOrder()).collect(java.util.stream.Collectors.joining(","));
    }

    private boolean equivalent(RegisteredClient left, RegisteredClient right) {
        return left != null
                && Objects.equals(left.getId(), right.getId())
                && Objects.equals(left.getClientId(), right.getClientId())
                && Objects.equals(left.getClientIdIssuedAt(), right.getClientIdIssuedAt())
                && Objects.equals(left.getClientSecret(), right.getClientSecret())
                && Objects.equals(left.getClientName(), right.getClientName())
                && Objects.equals(left.getClientAuthenticationMethods(), right.getClientAuthenticationMethods())
                && Objects.equals(left.getAuthorizationGrantTypes(), right.getAuthorizationGrantTypes())
                && Objects.equals(left.getRedirectUris(), right.getRedirectUris())
                && Objects.equals(left.getPostLogoutRedirectUris(), right.getPostLogoutRedirectUris())
                && Objects.equals(left.getScopes(), right.getScopes())
                && Objects.equals(left.getClientSettings().getSettings(), right.getClientSettings().getSettings())
                && Objects.equals(left.getTokenSettings().getSettings(), right.getTokenSettings().getSettings());
    }

    private Long settingAsLong(RegisteredClient client, String name) {
        Object value = client == null ? null : client.getClientSettings().getSetting(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private String settingAsString(RegisteredClient client, String name) {
        Object value = client == null ? null : client.getClientSettings().getSetting(name);
        return value == null ? null : value.toString();
    }

    private ClientAuthenticationMethod authenticationMethod(String value) {
        return switch (value) {
            case "none" -> ClientAuthenticationMethod.NONE;
            case "client_secret_basic" -> ClientAuthenticationMethod.CLIENT_SECRET_BASIC;
            default -> throw new IllegalStateException("Unsupported OAuth authentication method: " + value);
        };
    }

    private AuthorizationGrantType grantType(String value) {
        return switch (value) {
            case "authorization_code" -> AuthorizationGrantType.AUTHORIZATION_CODE;
            case "client_credentials" -> AuthorizationGrantType.CLIENT_CREDENTIALS;
            default -> throw new IllegalStateException("Unsupported OAuth grant type: " + value);
        };
    }

    private <T> void replace(Set<T> target, Set<T> values) {
        target.clear();
        target.addAll(values);
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("OAuth " + field + " must not be blank");
        }
    }

    private record ValidatedClient(
            OAuthClientProperties.Client configuration,
            Set<ClientAuthenticationMethod> authenticationMethods,
            Set<AuthorizationGrantType> grantTypes,
            String rawSecret,
            String fingerprint) {
    }
}
