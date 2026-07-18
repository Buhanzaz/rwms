package dev.buhanzaz.rwms.gateway.config;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.prefixPath;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.removeRequestHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.stripPrefix;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;

import dev.buhanzaz.rwms.gateway.web.GatewayUpstreamProblemHandler;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.UriUtils;

@Configuration
public class GatewayRouteConfiguration {

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

  @Bean
  RouterFunction<ServerResponse> taskBoardRoutes(
      GatewayProperties properties, GatewayUpstreamProblemHandler upstreamProblems) {
    RequestPredicate publicTaskBoardPath =
        path("/api/task-board/**")
            .and(request -> safePath(request.path()))
            .and(
                request ->
                    !decodedPath(request.path()).startsWith("/api/task-board/internal"));
    return route("task-board-service")
        .route(publicTaskBoardPath, http())
        .before(uri(properties.getRoutes().getTaskBoardUri()))
        .before(stripPrefix(2))
        .before(prefixPath("/api"))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

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
                });
    return route("media-service")
        .route(publicMediaPath, http())
        .before(uri(properties.getRoutes().getMediaUri()))
        .before(removeRequestHeader(HttpHeaders.COOKIE))
        .onError(upstreamProblems::supports, upstreamProblems::handle)
        .build();
  }

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

  private static String decodedPath(String path) {
    return UriUtils.decode(path, StandardCharsets.UTF_8);
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
