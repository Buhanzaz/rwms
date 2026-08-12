package dev.buhanzaz.rwms.gateway.config;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.prefixPath;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.removeRequestHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.stripPrefix;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.method;
import static org.springframework.web.servlet.function.RequestPredicates.path;

import dev.buhanzaz.rwms.gateway.web.GatewayUpstreamProblemHandler;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.UriUtils;

/**
 * Defines the public, transport-only route table for RWMS domain services.
 *
 * <p>Each route accepts only its documented public path, rejects encoded traversal-style paths,
 * removes browser cookies before forwarding, and maps connectivity failures to the shared gateway
 * Problem Details contract. This configuration must not aggregate responses or implement domain
 * workflow.
 */
@Configuration
public class GatewayRouteConfiguration {

  /**
   * Relays public OIDC routes to auth-service while retaining the SPA-owned callback and denying
   * auth-service's private API path.
   */
  @Bean
  RouterFunction<ServerResponse> authRoutes(
      GatewayProperties properties, GatewayUpstreamProblemHandler upstreamProblems) {
    RequestPredicate authPath =
        path("/auth/**")
            .and(request -> safePath(request.path()))
            .and(request -> !decodedPath(request.path()).equals("/auth/callback"))
            .and(request -> !decodedPath(request.path()).startsWith("/auth/api/internal"));
    return route("auth-service")
        .route(authPath, http())
        .before(uri(properties.getRoutes().getAuthUri()))
        .before(stripPrefix(1))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /**
   * Relays the worker task-board invalidation stream through the bounded asynchronous SSE proxy.
   *
   * <p>The lower order makes this exact event route win over the generic task-board route.
   */
  @Bean
  @Order(-100)
  RouterFunction<ServerResponse> taskBoardWorkerEventsRoute(
      GatewayProperties properties,
      GatewayUpstreamProblemHandler upstreamProblems,
      SseProxyHandler sseProxyHandler) {
    RequestPredicate workerEventsPath =
        path("/api/task-board/worker/v1/events")
            .and(method(HttpMethod.GET))
            .and(request -> safePath(request.path()));
    return route("task-board-worker-events")
        .route(workerEventsPath, sseProxyHandler)
        .before(uri(properties.getRoutes().getTaskBoardUri()))
        .before(stripPrefix(2))
        .before(prefixPath("/api"))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /** Relays the DriverApp invalidation stream through the bounded asynchronous SSE proxy. */
  @Bean
  @Order(-101)
  RouterFunction<ServerResponse> taskBoardDriverEventsRoute(
      GatewayProperties properties,
      GatewayUpstreamProblemHandler upstreamProblems,
      SseProxyHandler sseProxyHandler) {
    RequestPredicate driverEventsPath =
        path("/api/task-board/driver/v1/events")
            .and(method(HttpMethod.GET))
            .and(request -> safePath(request.path()));
    return route("task-board-driver-events")
        .route(driverEventsPath, sseProxyHandler)
        .before(uri(properties.getRoutes().getTaskBoardUri()))
        .before(stripPrefix(2))
        .before(prefixPath("/api"))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /** Relays the public asset invalidation stream through the bounded asynchronous SSE proxy. */
  @Bean
  @Order(-99)
  RouterFunction<ServerResponse> assetEventsRoute(
      GatewayProperties properties,
      GatewayUpstreamProblemHandler upstreamProblems,
      SseProxyHandler sseProxyHandler) {
    RequestPredicate assetEventsPath =
        path("/api/asset/v1/events")
            .and(method(HttpMethod.GET))
            .and(request -> safePath(request.path()));
    return route("asset-events")
        .route(assetEventsPath, sseProxyHandler)
        .before(uri(properties.getRoutes().getAssetUri()))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /** Relays the public media invalidation stream through the bounded asynchronous SSE proxy. */
  @Bean
  @Order(-98)
  RouterFunction<ServerResponse> mediaEventsRoute(
      GatewayProperties properties,
      GatewayUpstreamProblemHandler upstreamProblems,
      SseProxyHandler sseProxyHandler) {
    RequestPredicate mediaEventsPath =
        path("/api/media/v1/events")
            .and(method(HttpMethod.GET))
            .and(request -> safePath(request.path()));
    return route("media-events")
        .route(mediaEventsPath, sseProxyHandler)
        .before(uri(properties.getRoutes().getMediaUri()))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /**
   * Relays public task-board APIs and rewrites their external prefix to the downstream
   * {@code /api} namespace.
   *
   * <p>It excludes the private namespace and the exact native event streams owned by their
   * asynchronous SSE routes.
   */
  @Bean
  RouterFunction<ServerResponse> taskBoardRoutes(
      GatewayProperties properties, GatewayUpstreamProblemHandler upstreamProblems) {
    RequestPredicate publicTaskBoardPath =
        path("/api/task-board/**")
            .and(request -> safePath(request.path()))
            .and(
                request ->
                    !decodedPath(request.path()).startsWith("/api/task-board/internal"))
            .and(
                request ->
                    request.method() != HttpMethod.GET
                        || (!decodedPath(request.path())
                                .equals("/api/task-board/worker/v1/events")
                            && !decodedPath(request.path())
                                .equals("/api/task-board/driver/v1/events")));
    return route("task-board-service")
        .route(publicTaskBoardPath, http())
        .before(uri(properties.getRoutes().getTaskBoardUri()))
        .before(stripPrefix(2))
        .before(prefixPath("/api"))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /** Relays only public warehouse APIs to the reserved warehouse-service route. */
  @Bean
  RouterFunction<ServerResponse> warehouseRoutes(
      GatewayProperties properties, GatewayUpstreamProblemHandler upstreamProblems) {
    RequestPredicate publicWarehousePath =
        path("/api/warehouse/**")
            .and(request -> safePath(request.path()))
            .and(
                request ->
                    !decodedPath(request.path()).startsWith("/api/warehouse/internal"));
    return route("warehouse-service-reserved")
        .route(publicWarehousePath, http())
        .before(uri(properties.getRoutes().getWarehouseUri()))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /**
   * Uses the dedicated bounded proxy for the public HTML-import commit command instead of the
   * generic asset route.
   */
  @Bean
  @Order(-80)
  RouterFunction<ServerResponse> htmlImportCommitRoute(
      GatewayProperties properties,
      GatewayUpstreamProblemHandler upstreamProblems,
      HtmlImportCommitProxyHandler htmlImportCommitProxyHandler) {
    RequestPredicate htmlImportCommitPath =
        path("/api/asset/v1/html-imports/*/commit")
            .and(method(HttpMethod.POST))
            .and(request -> safePath(request.path()));
    return route("asset-html-import-commit")
        .route(htmlImportCommitPath, htmlImportCommitProxyHandler)
        .before(uri(properties.getRoutes().getAssetUri()))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /** Relays public asset APIs while keeping asset-service internal endpoints unreachable. */
  @Bean
  RouterFunction<ServerResponse> assetRoutes(
      GatewayProperties properties, GatewayUpstreamProblemHandler upstreamProblems) {
    RequestPredicate publicAssetPath =
        path("/api/asset/**")
            .and(request -> safePath(request.path()))
            .and(request -> !decodedPath(request.path()).startsWith("/api/asset/internal"));
    return route("asset-service")
        .route(publicAssetPath, http())
        .before(uri(properties.getRoutes().getAssetUri()))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /** Relays public maintenance APIs while keeping maintenance-service internal endpoints unreachable. */
  @Bean
  RouterFunction<ServerResponse> maintenanceRoutes(
      GatewayProperties properties, GatewayUpstreamProblemHandler upstreamProblems) {
    RequestPredicate publicMaintenancePath =
        path("/api/maintenance/**")
            .and(request -> safePath(request.path()))
            .and(
                request ->
                    !decodedPath(request.path()).startsWith("/api/maintenance/internal"));
    return route("maintenance-service")
        .route(publicMaintenancePath, http())
        .before(uri(properties.getRoutes().getMaintenanceUri()))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /**
   * Uses the dedicated bounded proxy for media upload bytes instead of the generic media route.
   */
  @Bean
  @Order(-79)
  RouterFunction<ServerResponse> mediaUploadContentRoute(
      GatewayProperties properties,
      GatewayUpstreamProblemHandler upstreamProblems,
      MediaUploadContentProxyHandler mediaUploadContentProxyHandler) {
    RequestPredicate mediaUploadContentPath =
        path("/api/media/v1/upload-sessions/*/content")
            .and(method(HttpMethod.PUT))
            .and(request -> safePath(request.path()));
    return route("media-upload-content")
        .route(mediaUploadContentPath, mediaUploadContentProxyHandler)
        .before(uri(properties.getRoutes().getMediaUri()))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /**
   * Relays public media APIs while excluding private/internal paths and the upload-content
   * endpoint handled by {@link #mediaUploadContentRoute(GatewayProperties,
   * GatewayUpstreamProblemHandler, MediaUploadContentProxyHandler)}.
   */
  @Bean
  RouterFunction<ServerResponse> mediaRoutes(
      GatewayProperties properties, GatewayUpstreamProblemHandler upstreamProblems) {
    RequestPredicate publicMediaPath =
        path("/api/media/**")
            .and(request -> safePath(request.path()))
            .and(
                request -> {
                  String decoded = decodedPath(request.path());
                  return !decoded.startsWith("/api/media/internal")
                      && !decoded.startsWith("/api/media/private");
                })
            .and(request -> !isMediaUploadContentRequest(request));
    return route("media-service")
        .route(publicMediaPath, http())
        .before(uri(properties.getRoutes().getMediaUri()))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /** Relays public inventory APIs while keeping inventory-service private and internal paths unreachable. */
  @Bean
  RouterFunction<ServerResponse> inventoryRoutes(
      GatewayProperties properties, GatewayUpstreamProblemHandler upstreamProblems) {
    RequestPredicate publicInventoryPath =
        path("/api/inventory/**")
            .and(request -> safePath(request.path()))
            .and(
                request -> {
                  String decoded = decodedPath(request.path());
                  return !decoded.startsWith("/api/inventory/internal")
                      && !decoded.startsWith("/api/inventory/private");
                });
    return route("inventory-service")
        .route(publicInventoryPath, http())
        .before(uri(properties.getRoutes().getInventoryUri()))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /** Relays public logistics APIs while keeping logistics-service private and internal paths unreachable. */
  @Bean
  RouterFunction<ServerResponse> logisticsRoutes(
      GatewayProperties properties, GatewayUpstreamProblemHandler upstreamProblems) {
    RequestPredicate publicLogisticsPath =
        path("/api/logistics/**")
            .and(request -> safePath(request.path()))
            .and(
                request -> {
                  String decoded = decodedPath(request.path());
                  return !decoded.startsWith("/api/logistics/internal")
                      && !decoded.startsWith("/api/logistics/private");
                });
    return route("logistics-service")
        .route(publicLogisticsPath, http())
        .before(uri(properties.getRoutes().getLogisticsUri()))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /**
   * Uses the dedicated streaming proxy for assistant turns so a legitimate long-lived answer does
   * not inherit the ordinary proxy read deadline.
   */
  @Bean
  @Order(-90)
  RouterFunction<ServerResponse> assistantTurnsRoute(
      GatewayProperties properties,
      GatewayUpstreamProblemHandler upstreamProblems,
      AssistantTurnsProxyHandler assistantTurnsProxyHandler) {
    RequestPredicate turnsPath =
        path("/api/assistant/v1/conversations/*/turns")
            .and(method(HttpMethod.POST))
            .and(request -> safePath(request.path()));
    return route("assistant-turns")
        .route(turnsPath, assistantTurnsProxyHandler)
        .before(uri(properties.getRoutes().getAssistantUri()))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /**
   * Relays the remaining public assistant APIs while excluding private/internal paths and the
   * dedicated assistant-turn streaming command.
   */
  @Bean
  RouterFunction<ServerResponse> assistantRoutes(
      GatewayProperties properties, GatewayUpstreamProblemHandler upstreamProblems) {
    RequestPredicate publicAssistantPath =
        path("/api/assistant/**")
            .and(request -> safePath(request.path()))
            .and(
                request -> {
                  String decoded = decodedPath(request.path());
                  return !decoded.startsWith("/api/assistant/internal")
                      && !decoded.startsWith("/api/assistant/private")
                      && (request.method() != HttpMethod.POST
                          || !decoded.matches(
                              "^/api/assistant/v1/conversations/[^/]+/turns$"));
                });
    return route("assistant-service")
        .route(publicAssistantPath, http())
        .before(uri(properties.getRoutes().getAssistantUri()))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /**
   * Relays only public dossier reads. Dossier-service is a read model and receives no gateway
   * command route.
   */
  @Bean
  RouterFunction<ServerResponse> dossierRoutes(
      GatewayProperties properties, GatewayUpstreamProblemHandler upstreamProblems) {
    RequestPredicate publicDossierPath =
        path("/api/dossier/**")
            .and(method(HttpMethod.GET))
            .and(request -> safePath(request.path()))
            .and(
                request -> {
                  String decoded = decodedPath(request.path());
                  return !decoded.startsWith("/api/dossier/internal")
                      && !decoded.startsWith("/api/dossier/private");
                });
    return route("dossier-service")
        .route(publicDossierPath, http())
        .before(uri(properties.getRoutes().getDossierUri()))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  /**
   * Relays authenticated analytics reads and rewrites the public {@code /api/analytics/v1}
   * prefix to analytics-service's {@code /api/v1} namespace.
   */
  @Bean
  RouterFunction<ServerResponse> analyticsRoutes(
      GatewayProperties properties, GatewayUpstreamProblemHandler upstreamProblems) {
    RequestPredicate publicAnalyticsPath =
        path("/api/analytics/v1/**")
            .and(method(HttpMethod.GET))
            .and(request -> safePath(request.path()));
    return route("analytics-service")
        .route(publicAnalyticsPath, http())
        .before(uri(properties.getRoutes().getAnalyticsUri()))
        .before(stripPrefix(2))
        .before(prefixPath("/api"))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

  private static String decodedPath(String path) {
    return UriUtils.decode(path, StandardCharsets.UTF_8);
  }

  private static boolean isMediaUploadContentRequest(ServerRequest request) {
    return request.method() == HttpMethod.PUT
        && decodedPath(request.path())
            .matches("^/api/media/v1/upload-sessions/[^/]+/content$");
  }

  private static boolean safePath(String path) {
    String normalized = path.toLowerCase(Locale.ROOT);
    return !normalized.contains("\\")
        && !normalized.contains("..")
        && !normalized.contains("%2f")
        && !normalized.contains("%5c")
        && !normalized.contains("%2e")
        && !normalized.contains("%25");
  }
}
