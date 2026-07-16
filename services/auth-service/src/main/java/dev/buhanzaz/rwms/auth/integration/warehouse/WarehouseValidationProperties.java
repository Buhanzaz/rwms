package dev.buhanzaz.rwms.auth.integration.warehouse;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rwms.auth.warehouse-validation")
public record WarehouseValidationProperties(
        boolean enabled,
        String baseUrl,
        String tokenUri,
        String clientId,
        String clientSecret,
        Duration connectTimeout,
        Duration readTimeout) {

    public WarehouseValidationProperties {
        clientId = clientId == null || clientId.isBlank() ? "auth-service" : clientId.trim();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
    }

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

    private static void requireBoundedTimeout(Duration timeout, String property) {
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalStateException(
                    "Warehouse validation " + property + " must be between 1 ms and 30 s");
        }
    }

    public record Validated(
            URI baseUrl,
            URI tokenUri,
            String clientId,
            String clientSecret,
            Duration connectTimeout,
            Duration readTimeout) {

        @Override
        public String toString() {
            return "Validated[baseUrl=" + baseUrl + ", tokenUri=" + tokenUri + ", clientId=" + clientId + "]";
        }
    }

    @Override
    public String toString() {
        return "WarehouseValidationProperties[enabled=" + enabled + ", clientId=" + clientId + "]";
    }
}
