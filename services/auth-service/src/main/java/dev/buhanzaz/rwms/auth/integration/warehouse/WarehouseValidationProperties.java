package dev.buhanzaz.rwms.auth.integration.warehouse;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for optional, synchronous validation of warehouse identifiers against Warehouse
 * Service.
 *
 * <p>Secrets are intentionally omitted from {@link #toString()}. The enabled configuration is
 * converted to {@link Validated} only after its private endpoints, client identity, and timeouts
 * have passed fail-closed checks.
 *
 * @param enabled whether the remote Warehouse Service check is active
 * @param baseUrl private Warehouse Service base URI when enabled
 * @param tokenUri private OAuth token URI when enabled
 * @param clientId fixed OAuth client identifier for the integration
 * @param clientSecret secret for the private OAuth client, never suitable for logging
 * @param connectTimeout bounded time allowed to establish a remote connection
 * @param readTimeout bounded time allowed for each remote response
 */
@ConfigurationProperties("rwms.auth.warehouse-validation")
public record WarehouseValidationProperties(
        boolean enabled,
        String baseUrl,
        String tokenUri,
        String clientId,
        String clientSecret,
        Duration connectTimeout,
        Duration readTimeout) {

    /** Applies safe defaults that keep the disabled integration free of required endpoint values. */
    public WarehouseValidationProperties {
        clientId = clientId == null || clientId.isBlank() ? "auth-service" : clientId.trim();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
    }

    /**
     * Verifies every property needed for the enabled remote integration and returns the normalized
     * value object used by the adapter.
     *
     * @return safe, enabled-only warehouse integration settings
     * @throws IllegalStateException when an enabled deployment has an unsafe or incomplete setting
     */
    public Validated validateEnabledConfiguration() {
        if (!enabled) {
            throw new IllegalStateException("Warehouse validation configuration is disabled");
        }
        URI validatedBaseUrl = safeUri(baseUrl, true, "base-url");
        URI validatedTokenUri = safeUri(tokenUri, false, "token-uri");
        if (!"/oauth2/token".equals(validatedTokenUri.getPath())) {
            throw new IllegalStateException("Warehouse validation token-uri path must be /oauth2/token");
        }
        if (!"auth-service".equals(clientId)) {
            throw new IllegalStateException("Warehouse validation client-id must be auth-service");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("Warehouse validation client-secret is required when enabled");
        }
        requireBoundedTimeout(connectTimeout, "connect-timeout");
        requireBoundedTimeout(readTimeout, "read-timeout");
        return new Validated(
                validatedBaseUrl,
                validatedTokenUri,
                clientId,
                clientSecret,
                connectTimeout,
                readTimeout);
    }

    /** Parses and normalizes a private HTTP(S) URI, rejecting credentials and ambiguous components. */
    private static URI safeUri(String value, boolean base, String property) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            boolean supportedScheme = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            boolean unsafeBasePath = base && uri.getPath() != null
                    && !uri.getPath().isEmpty()
                    && !"/".equals(uri.getPath());
            if (!uri.isAbsolute()
                    || !supportedScheme
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || unsafeBasePath) {
                throw new IllegalArgumentException();
            }
            String normalized = uri.toString();
            if (base && normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return URI.create(normalized);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Warehouse validation " + property + " must be a safe absolute HTTP(S) URI", exception);
        }
    }

    /** Ensures that integration timeouts remain positive and bounded to protect request capacity. */
    private static void requireBoundedTimeout(Duration timeout, String property) {
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalStateException(
                    "Warehouse validation " + property + " must be between 1 ms and 30 s");
        }
    }

    /**
     * Fully validated remote-integration settings that can safely be handed to the HTTP adapter.
     *
     * <p>The secret is deliberately excluded from {@link #toString()}.
     *
     * @param baseUrl normalized private Warehouse Service base URI
     * @param tokenUri normalized private OAuth token URI
     * @param clientId fixed OAuth client identifier
     * @param clientSecret OAuth client secret, never suitable for logging
     * @param connectTimeout validated bounded connection timeout
     * @param readTimeout validated bounded response timeout
     */
    public record Validated(
            URI baseUrl,
            URI tokenUri,
            String clientId,
            String clientSecret,
            Duration connectTimeout,
            Duration readTimeout) {

        /** Returns a diagnostic representation without the client secret. */
        @Override
        public String toString() {
            return "Validated[baseUrl=" + baseUrl + ", tokenUri=" + tokenUri + ", clientId=" + clientId + "]";
        }
    }

    /** Returns a diagnostic representation without endpoint credentials or the client secret. */
    @Override
    public String toString() {
        return "WarehouseValidationProperties[enabled=" + enabled + ", clientId=" + clientId + "]";
    }
}
