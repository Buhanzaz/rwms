package dev.buhanzaz.rwms.asset.integration.warehouse;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration value for the asset private integration boundary.
 */
@ConfigurationProperties("rwms.asset.warehouse-registry")
public record WarehouseRegistryProperties(
    boolean enabled,
    String baseUrl,
    String tokenUri,
    String clientId,
    String clientSecret,
    Duration connectTimeout,
    Duration readTimeout) {
  public WarehouseRegistryProperties {
    clientId = clientId == null || clientId.isBlank() ? "asset-service" : clientId.trim();
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
  }

  public Validated requireEnabledConfiguration() {
    if (!enabled) throw new IllegalStateException("Warehouse registry client is disabled");
    URI base = uri(baseUrl, true, "base-url");
    URI token = uri(tokenUri, false, "token-uri");
    if (!"/oauth2/token".equals(token.getPath())) throw new IllegalStateException("token-uri path must be /oauth2/token");
    if (!"asset-service".equals(clientId)) throw new IllegalStateException("warehouse registry client-id must be asset-service");
    if (clientSecret == null || clientSecret.isBlank()) throw new IllegalStateException("warehouse registry client-secret is required when enabled");
    bounded(connectTimeout, "connect-timeout");
    bounded(readTimeout, "read-timeout");
    return new Validated(base, token, clientId, clientSecret, connectTimeout, readTimeout);
  }

  private static URI uri(String value, boolean base, String name) {
    try {
      URI uri = URI.create(value == null ? "" : value.trim());
      boolean scheme = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
      boolean badPath = base && uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath());
      if (!uri.isAbsolute() || !scheme || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || badPath) throw new IllegalArgumentException();
      String normalized = uri.toString();
      return URI.create(base && normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized);
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Warehouse registry " + name + " must be a safe absolute HTTP(S) URI", exception);
    }
  }
  private static void bounded(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalStateException("Warehouse registry " + name + " must be between 1 ms and 30 s");
  }

  public record Validated(URI baseUrl, URI tokenUri, String clientId, String clientSecret, Duration connectTimeout, Duration readTimeout) {
    @Override public String toString() { return "Validated[baseUrl=" + baseUrl + ", clientId=" + clientId + "]"; }
  }
}
