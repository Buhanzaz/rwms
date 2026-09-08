package dev.buhanzaz.rwms.gateway.config;

import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
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
    List<DownstreamTarget> downstreamTargets = requireDownstreamTargets();
    downstreamTargets.forEach(target -> forbidPublicGatewayHost(publicBase, target));
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
      downstreamTargets.forEach(target -> forbidLoopback(target.label(), target.origin()));
    }
  }

  /** Builds the complete validated private target inventory from the route configuration. */
  private List<DownstreamTarget> requireDownstreamTargets() {
    GatewayProperties.Routes routes = properties.getRoutes();
    List<DownstreamTarget> targets =
        new ArrayList<>(
            List.of(
                downstreamTarget("auth target", "rwms.gateway.routes.auth-uri", routes.getAuthUri()),
                downstreamTarget(
                    "task-board target",
                    "rwms.gateway.routes.task-board-uri",
                    routes.getTaskBoardUri()),
                downstreamTarget(
                    "warehouse target", "rwms.gateway.routes.warehouse-uri", routes.getWarehouseUri()),
                downstreamTarget("asset target", "rwms.gateway.routes.asset-uri", routes.getAssetUri()),
                downstreamTarget(
                    "maintenance target",
                    "rwms.gateway.routes.maintenance-uri",
                    routes.getMaintenanceUri()),
                downstreamTarget("media target", "rwms.gateway.routes.media-uri", routes.getMediaUri()),
                downstreamTarget(
                    "inventory target",
                    "rwms.gateway.routes.inventory-uri",
                    routes.getInventoryUri()),
                downstreamTarget(
                    "logistics target",
                    "rwms.gateway.routes.logistics-uri",
                    routes.getLogisticsUri()),
                downstreamTarget(
                    "logistics planner target",
                    "rwms.gateway.routes.logistics-planner-uri",
                    routes.getLogisticsPlannerUri()),
                downstreamTarget(
                    "dossier target", "rwms.gateway.routes.dossier-uri", routes.getDossierUri()),
                downstreamTarget(
                    "analytics target", "rwms.gateway.routes.analytics-uri", routes.getAnalyticsUri()),
                downstreamTarget(
                    "assistant target", "rwms.gateway.routes.assistant-uri", routes.getAssistantUri())));
    if (routes.getCadUri() != null) {
      targets.add(downstreamTarget("cad target", "rwms.gateway.routes.cad-uri", routes.getCadUri()));
    }
    return List.copyOf(targets);
  }

  private static DownstreamTarget downstreamTarget(String label, String property, URI uri) {
    return new DownstreamTarget(label, requireOrigin(property, uri));
  }

  private static void forbidPublicGatewayHost(URI publicBase, DownstreamTarget target) {
    if (sharesHost(publicBase, target.origin())) {
      throw new IllegalStateException(
          "Gateway " + target.label() + " must not use the public gateway host");
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

  /** A validated downstream label and origin governed by the same private-target policy. */
  private record DownstreamTarget(String label, URI origin) {}
}
