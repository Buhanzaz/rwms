package dev.buhanzaz.rwms.inventory.integration;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration value for the inventory private integration boundary.
 */
@ConfigurationProperties("rwms.inventory.dependencies")
public record InventoryDependencyProperties(
    boolean enabled,
    String tokenUri,
    String clientId,
    String clientSecret,
    String warehouseBaseUrl,
    String assetBaseUrl,
    String maintenanceBaseUrl,
    String logisticsBaseUrl,
    String mediaBaseUrl,
    Duration connectTimeout,
    Duration readTimeout) {

  public Validated validated() {
    if (!enabled) throw new IllegalStateException("Real inventory dependencies are disabled");
    String validatedClientId = required(clientId, "client-id");
    if (!"inventory-service".equals(validatedClientId)) {
      throw new IllegalStateException("client-id must be exactly inventory-service");
    }
    return new Validated(
        uri(tokenUri, "token-uri"),
        validatedClientId,
        required(clientSecret, "client-secret"),
        uri(warehouseBaseUrl, "warehouse-base-url"),
        uri(assetBaseUrl, "asset-base-url"),
        uri(maintenanceBaseUrl, "maintenance-base-url"),
        uri(logisticsBaseUrl, "logistics-base-url"),
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
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalStateException(name + " must be positive");
    }
    return value;
  }

  public record Validated(
      URI tokenUri,
      String clientId,
      String clientSecret,
      URI warehouseBaseUrl,
      URI assetBaseUrl,
      URI maintenanceBaseUrl,
      URI logisticsBaseUrl,
      URI mediaBaseUrl,
      Duration connectTimeout,
      Duration readTimeout) {}
}
