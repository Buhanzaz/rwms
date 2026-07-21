package dev.buhanzaz.rwms.maintenance.integration;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rwms.maintenance.dependencies")
public record MaintenanceDependencyProperties(
    boolean enabled,
    String tokenUri,
    String clientId,
    String clientSecret,
    String assetBaseUrl,
    String taskBoardBaseUrl,
    String mediaBaseUrl,
    Duration connectTimeout,
    Duration readTimeout) {

  public Validated validated() {
    if (!enabled) throw new IllegalStateException("Real maintenance dependencies are disabled");
    return new Validated(
        uri(tokenUri, "token-uri"), required(clientId, "client-id"),
        required(clientSecret, "client-secret"), uri(assetBaseUrl, "asset-base-url"),
        uri(taskBoardBaseUrl, "task-board-base-url"), uri(mediaBaseUrl, "media-base-url"),
        positive(connectTimeout, "connect-timeout"),
        positive(readTimeout, "read-timeout"));
  }

  private static URI uri(String value, String name) {
    try {
      URI uri = URI.create(required(value, name));
      if (!uri.isAbsolute() || uri.getHost() == null) {
        throw new IllegalArgumentException();
      }
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
      URI assetBaseUrl,
      URI taskBoardBaseUrl,
      URI mediaBaseUrl,
      Duration connectTimeout,
      Duration readTimeout) {}
}
