package dev.buhanzaz.rwms.logistics.integration;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds private dependency endpoint configuration for logistics; clients do not use the public gateway.
 */
@ConfigurationProperties("rwms.logistics.dependencies")
public record LogisticsDependencyProperties(
    boolean enabled,
    String tokenUri,
    String clientId,
    String clientSecret,
    String assetBaseUrl,
    String warehouseBaseUrl,
    String taskBoardBaseUrl,
    String maintenanceBaseUrl,
    String mediaBaseUrl,
    Duration connectTimeout,
    Duration readTimeout) {

  public Validated validated() {
    if (!enabled) throw new IllegalStateException("Real logistics dependencies are disabled");
    return new Validated(
        uri(tokenUri, "token-uri"),
        required(clientId, "client-id"),
        required(clientSecret, "client-secret"),
        uri(assetBaseUrl, "asset-base-url"),
        uri(warehouseBaseUrl, "warehouse-base-url"),
        uri(taskBoardBaseUrl, "task-board-base-url"),
        uri(maintenanceBaseUrl, "maintenance-base-url"),
        uri(mediaBaseUrl, "media-base-url"),
        positive(connectTimeout, "connect-timeout"),
        positive(readTimeout, "read-timeout"));
  }

  private static URI uri(String value, String name) {
    try {
      URI uri = URI.create(required(value, name));
      if (!uri.isAbsolute() || uri.getHost() == null) throw new IllegalArgumentException();
      return uri;
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(name + " must be an absolute URI", exception);
    }
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
    return value.trim();
  }

  private static Duration positive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalStateException(name + " must be positive");
    }
    return value;
  }

  public record Validated(
      URI tokenUri,
      String clientId,
      String clientSecret,
      URI assetBaseUrl,
      URI warehouseBaseUrl,
      URI taskBoardBaseUrl,
      URI maintenanceBaseUrl,
      URI mediaBaseUrl,
      Duration connectTimeout,
      Duration readTimeout) {}
}
