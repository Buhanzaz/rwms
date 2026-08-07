package dev.buhanzaz.rwms.gateway.config;

import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Rejects unsafe gateway configuration before the application starts accepting traffic.
 *
 * <p>The validator enforces a public/private origin split, derives the public issuer invariant,
 * rejects wildcard CORS, and applies production-only HTTPS, certificate, and non-loopback
 * requirements. It does not probe downstream availability; that remains deployment health
 * infrastructure's responsibility.
 */
@Component
@RequiredArgsConstructor
public class GatewayProductionSafetyValidator {

  private final GatewayProperties properties;
  private final Environment environment;

  /** Validates all configured public and private edge endpoints. */
  @PostConstruct
  void validate() {
    URI publicBase = requireOrigin("rwms.gateway.public-base-uri", properties.getPublicBaseUri());
    URI authTarget = requireOrigin("rwms.gateway.routes.auth-uri", properties.getRoutes().getAuthUri());
    if (sharesHost(publicBase, authTarget)) {
      throw new IllegalStateException(
          "Gateway auth target must not use the public gateway host");
    }
    URI taskBoardTarget =
        requireOrigin("rwms.gateway.routes.task-board-uri", properties.getRoutes().getTaskBoardUri());
    URI warehouseTarget =
        requireOrigin("rwms.gateway.routes.warehouse-uri", properties.getRoutes().getWarehouseUri());
    URI assetTarget =
        requireOrigin("rwms.gateway.routes.asset-uri", properties.getRoutes().getAssetUri());
    URI maintenanceTarget =
        requireOrigin(
            "rwms.gateway.routes.maintenance-uri", properties.getRoutes().getMaintenanceUri());
    URI mediaTarget =
        requireOrigin("rwms.gateway.routes.media-uri", properties.getRoutes().getMediaUri());
    URI inventoryTarget =
        requireOrigin(
            "rwms.gateway.routes.inventory-uri", properties.getRoutes().getInventoryUri());
    URI logisticsTarget =
        requireOrigin(
            "rwms.gateway.routes.logistics-uri", properties.getRoutes().getLogisticsUri());
    URI dossierTarget =
        requireOrigin(
            "rwms.gateway.routes.dossier-uri", properties.getRoutes().getDossierUri());
    URI analyticsTarget =
        requireOrigin(
            "rwms.gateway.routes.analytics-uri", properties.getRoutes().getAnalyticsUri());
    URI assistantTarget =
        requireOrigin(
            "rwms.gateway.routes.assistant-uri", properties.getRoutes().getAssistantUri());
    URI issuer = requireHttpUri("rwms.gateway.security.issuer", URI.create(properties.getSecurity().getIssuer()));
    String expectedIssuer = trimSlash(publicBase.toString()) + "/auth";
    if (!expectedIssuer.equals(trimSlash(issuer.toString()))) {
      throw new IllegalStateException("Gateway issuer must equal public base URI plus /auth");
    }
    properties.getCors().getAllowedOrigins().forEach(origin -> requireOrigin("CORS origin", URI.create(origin)));
    if (properties.getCors().getAllowedOrigins().stream().anyMatch(origin -> origin.contains("*"))) {
      throw new IllegalStateException("Wildcard CORS origins are forbidden");
    }

    if (environment.matchesProfiles("prod", "production")) {
      if (properties.getAppLinks().getSha256CertFingerprints().isEmpty()) {
        throw new IllegalStateException(
            "Worker Android Asset Links release certificate fingerprint is required in production");
      }
      requireHttps("gateway public base", publicBase);
      requireHttps("gateway issuer", issuer);
      properties.getCors().getAllowedOrigins().forEach(origin -> requireHttps("CORS origin", URI.create(origin)));
      forbidLoopback("auth target", authTarget);
      forbidLoopback("task-board target", taskBoardTarget);
      forbidLoopback("warehouse target", warehouseTarget);
      forbidLoopback("asset target", assetTarget);
      forbidLoopback("maintenance target", maintenanceTarget);
      forbidLoopback("media target", mediaTarget);
      forbidLoopback("inventory target", inventoryTarget);
      forbidLoopback("logistics target", logisticsTarget);
      forbidLoopback("dossier target", dossierTarget);
      forbidLoopback("analytics target", analyticsTarget);
      forbidLoopback("assistant target", assistantTarget);
    }
  }

  private static boolean sharesHost(URI first, URI second) {
    return first.getHost().equalsIgnoreCase(second.getHost());
  }

  private static URI requireOrigin(String name, URI uri) {
    URI value = requireHttpUri(name, uri);
    if ((value.getPath() != null && !value.getPath().isBlank() && !"/".equals(value.getPath()))
        || value.getQuery() != null
        || value.getFragment() != null) {
      throw new IllegalStateException(name + " must be an HTTP origin without path, query, or fragment");
    }
    return value;
  }

  private static URI requireHttpUri(String name, URI uri) {
    if (uri == null
        || !uri.isAbsolute()
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
      throw new IllegalStateException(name + " must be an absolute HTTP(S) URI without user info");
    }
    return uri;
  }

  private static void requireHttps(String name, URI uri) {
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      throw new IllegalStateException(name + " must use HTTPS in production");
    }
  }

  private static void forbidLoopback(String name, URI uri) {
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if ("localhost".equals(host) || host.endsWith(".localhost")) {
      throw new IllegalStateException(name + " must not use localhost in production");
    }
    try {
      if (InetAddress.getByName(host).isLoopbackAddress()) {
        throw new IllegalStateException(name + " must not use a loopback address in production");
      }
    } catch (UnknownHostException ignored) {
      // Internal DNS names are resolved by the deployment environment.
    }
  }

  private static String trimSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
