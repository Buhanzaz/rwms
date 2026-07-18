package dev.buhanzaz.rwms.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class GatewayRouteIntegrationTest {

  private static HttpServer auth;
  private static HttpServer taskBoard;
  private static HttpServer warehouse;
  private static HttpServer asset;
  private static HttpServer maintenance;
  private static HttpServer media;
  private static HttpServer inventory;
  private static HttpServer logistics;
  private static final List<CapturedRequest> AUTH_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> TASK_BOARD_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> WAREHOUSE_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> ASSET_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> MAINTENANCE_REQUESTS =
      new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> MEDIA_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> INVENTORY_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> LOGISTICS_REQUESTS = new CopyOnWriteArrayList<>();

  @Autowired MockMvc mvc;

  @BeforeAll
  static void startDownstreams() throws IOException {
    auth = server(AUTH_REQUESTS);
    taskBoard = server(TASK_BOARD_REQUESTS);
    warehouse = server(WAREHOUSE_REQUESTS);
    asset = server(ASSET_REQUESTS);
    maintenance = server(MAINTENANCE_REQUESTS);
    media = server(MEDIA_REQUESTS);
    inventory = server(INVENTORY_REQUESTS);
    logistics = server(LOGISTICS_REQUESTS);
  }

  @AfterAll
  static void stopDownstreams() {
    auth.stop(0);
    taskBoard.stop(0);
    warehouse.stop(0);
    asset.stop(0);
    maintenance.stop(0);
    media.stop(0);
    inventory.stop(0);
    logistics.stop(0);
  }

  @DynamicPropertySource
  static void gatewayProperties(DynamicPropertyRegistry registry) {
    registry.add("rwms.gateway.routes.auth-uri", () -> origin(auth));
    registry.add("rwms.gateway.routes.task-board-uri", () -> origin(taskBoard));
    registry.add("rwms.gateway.routes.warehouse-uri", () -> origin(warehouse));
    registry.add("rwms.gateway.routes.asset-uri", () -> origin(asset));
    registry.add("rwms.gateway.routes.maintenance-uri", () -> origin(maintenance));
    registry.add("rwms.gateway.routes.media-uri", () -> origin(media));
    registry.add("rwms.gateway.routes.inventory-uri", () -> origin(inventory));
    registry.add("rwms.gateway.routes.logistics-uri", () -> origin(logistics));
    registry.add("rwms.gateway.public-base-uri", () -> "https://panel.example");
    registry.add("rwms.gateway.cors.allowed-origins", () -> "https://panel.example");
    registry.add("rwms.gateway.security.issuer", () -> "https://panel.example/auth");
    registry.add("rwms.gateway.security.audience", () -> "rwms-services");
    registry.add("rwms.gateway.security.jwk-set-uri", () -> origin(auth) + "/oauth2/jwks");
  }

  @Test
  void stripsExactlyOneAuthPrefixAndLeavesOidcPublic() throws Exception {
    AUTH_REQUESTS.clear();
    WAREHOUSE_REQUESTS.clear();

    mvc.perform(publicGet("/auth/.well-known/openid-configuration"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/.well-known/openid-configuration"));

    assertThat(AUTH_REQUESTS).singleElement().extracting(CapturedRequest::path)
        .isEqualTo("/.well-known/openid-configuration");
  }

  @Test
  void stripsOnlyTheFirstAuthPrefix() throws Exception {
    AUTH_REQUESTS.clear();

    mvc.perform(publicGet("/auth/auth/login"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/auth/login"));
  }

  @Test
  void panelCallbackIsNeverCapturedByAuthRoute() throws Exception {
    AUTH_REQUESTS.clear();

    mvc.perform(publicGet("/auth/callback")).andExpect(status().isNotFound());

    assertThat(AUTH_REQUESTS).isEmpty();
  }

  @Test
  void encodedCallbackInternalAndAmbiguousPathsNeverReachDownstream() throws Exception {
    AUTH_REQUESTS.clear();
    TASK_BOARD_REQUESTS.clear();

    mvc.perform(publicGet("/auth/%63allback")).andExpect(status().is4xxClientError());
    mvc.perform(publicGet("/auth/%2563allback")).andExpect(status().is4xxClientError());
    mvc.perform(publicGet("/auth/%2e%2e/login")).andExpect(status().is4xxClientError());
    mvc.perform(
            publicGet("/api/task-board/%69nternal/work-queues")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().is4xxClientError());

    assertThat(AUTH_REQUESTS).isEmpty();
    assertThat(TASK_BOARD_REQUESTS).isEmpty();
  }

  @Test
  void replacesTaskBoardPublicPrefixWithDownstreamApiAndPreservesBearer() throws Exception {
    TASK_BOARD_REQUESTS.clear();

    mvc.perform(
            publicGet("/api/task-board/warehouses/warehouse-1/task-board")
                .header(HttpHeaders.AUTHORIZATION, "Bearer original-token")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/api/warehouses/warehouse-1/task-board"));

    assertThat(TASK_BOARD_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.path()).isEqualTo("/api/warehouses/warehouse-1/task-board");
      assertThat(request.authorization()).isEqualTo("Bearer original-token");
    });
  }

  @Test
  void proxiesPublicAssetPathsWithoutCookiesAndNeverRewritesTheirVersionedPath() throws Exception {
    ASSET_REQUESTS.clear();

    mvc.perform(
            publicGet("/api/asset/v1/rental-items?warehouseId=warehouse-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer original-token")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/api/asset/v1/rental-items"));

    assertThat(ASSET_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.path()).isEqualTo("/api/asset/v1/rental-items");
      assertThat(request.authorization()).isEqualTo("Bearer original-token");
      assertThat(request.cookie()).isNull();
    });
  }

  @Test
  void proxiesOnlyPublicMaintenancePathsWithoutCookiesOrPathRewriting() throws Exception {
    MAINTENANCE_REQUESTS.clear();

    mvc.perform(
            publicGet("/api/maintenance/v1/repairs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer original-token")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/api/maintenance/v1/repairs"));

    assertThat(MAINTENANCE_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.path()).isEqualTo("/api/maintenance/v1/repairs");
      assertThat(request.authorization()).isEqualTo("Bearer original-token");
      assertThat(request.cookie()).isNull();
    });

    MAINTENANCE_REQUESTS.clear();
    mvc.perform(
            publicGet("/api/maintenance/%69nternal/tasks")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().is4xxClientError());
    assertThat(MAINTENANCE_REQUESTS).isEmpty();
  }

  @Test
  void proxiesOnlyPublicMediaPathsWithoutCookiesAndPreservesBearer() throws Exception {
    MEDIA_REQUESTS.clear();

    mvc.perform(
            publicGet("/api/media/v1/assets?ownerType=INVENTORY_FINDING")
                .header(HttpHeaders.AUTHORIZATION, "Bearer original-token")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/api/media/v1/assets"));

    assertThat(MEDIA_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.path()).isEqualTo("/api/media/v1/assets");
      assertThat(request.authorization()).isEqualTo("Bearer original-token");
      assertThat(request.cookie()).isNull();
    });

    MEDIA_REQUESTS.clear();
    mvc.perform(
            publicGet("/api/media/%69nternal/owner-bindings")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().is4xxClientError());
    mvc.perform(
            publicGet("/api/media/private/object-keys")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden());
    assertThat(MEDIA_REQUESTS).isEmpty();
  }

  @Test
  void proxiesOnlyPublicInventoryPathsWithoutCookiesOrPathRewriting() throws Exception {
    INVENTORY_REQUESTS.clear();

    mvc.perform(
            publicGet("/api/inventory/v1/sessions?warehouseId=warehouse-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer original-token")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/api/inventory/v1/sessions"));

    assertThat(INVENTORY_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.path()).isEqualTo("/api/inventory/v1/sessions");
      assertThat(request.authorization()).isEqualTo("Bearer original-token");
      assertThat(request.cookie()).isNull();
    });

    INVENTORY_REQUESTS.clear();
    mvc.perform(
            publicGet("/api/inventory/%69nternal/captures")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().is4xxClientError());
    mvc.perform(
            publicGet("/api/inventory/private/captures")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden());
    assertThat(INVENTORY_REQUESTS).isEmpty();
  }

  @Test
  void proxiesOnlyPublicLogisticsPathsWithoutCookiesOrPathRewriting() throws Exception {
    LOGISTICS_REQUESTS.clear();

    mvc.perform(
            publicGet("/api/logistics/v1/transfers?warehouseId=warehouse-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer original-token")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/api/logistics/v1/transfers"));

    assertThat(LOGISTICS_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.path()).isEqualTo("/api/logistics/v1/transfers");
      assertThat(request.authorization()).isEqualTo("Bearer original-token");
      assertThat(request.cookie()).isNull();
    });

    LOGISTICS_REQUESTS.clear();
    mvc.perform(
            publicGet("/api/logistics/%69nternal/attempts")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().is4xxClientError());
    mvc.perform(
            publicGet("/api/logistics/private/reconciliation")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden());
    assertThat(LOGISTICS_REQUESTS).isEmpty();
  }

  @Test
  void removesSpoofedForwardedMetadataFromUntrustedRequests() throws Exception {
    TASK_BOARD_REQUESTS.clear();

    mvc.perform(
            publicGet("/api/task-board/worker-classes")
                .header("Forwarded", "host=evil.example;proto=http")
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Prefix", "/evil")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk());

    assertThat(TASK_BOARD_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.forwarded()).doesNotContain("evil.example");
      assertThat(request.forwardedHost()).doesNotContain("evil.example");
      assertThat(request.forwardedPrefix()).doesNotContain("/evil");
    });
  }

  @Test
  void authReceivesOnlyCanonicalPublicForwardedMetadata() throws Exception {
    AUTH_REQUESTS.clear();

    mvc.perform(
            publicGet("/auth/login")
                .header("Forwarded", "host=evil.example;proto=http")
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Port", "81")
                .header("X-Forwarded-Prefix", "/evil"))
        .andExpect(status().isFound())
        .andExpect(header().string(HttpHeaders.LOCATION, "https://panel.example/auth/authorize"))
        .andExpect(header().string(HttpHeaders.SET_COOKIE, "AUTH_SESSION=session; Path=/auth; HttpOnly"));

    assertThat(AUTH_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.forwarded()).doesNotContain("evil.example");
      assertThat(request.forwardedHost()).containsExactly("panel.example");
      assertThat(request.forwardedProto()).containsExactly("https");
      assertThat(request.forwardedPort()).containsExactly("443");
      assertThat(request.forwardedPrefix()).containsExactly("/auth");
    });
  }

  @Test
  void rejectsHostOutsidePublicBoundaryBeforeRouting() throws Exception {
    AUTH_REQUESTS.clear();

    mvc.perform(get("/auth/login").header(HttpHeaders.HOST, "evil.example"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("GATEWAY_INVALID_HOST"));

    assertThat(AUTH_REQUESTS).isEmpty();
  }

  @Test
  void requiresPublicHostAndNormalizesItsDefaultPort() throws Exception {
    mvc.perform(get("/auth/login"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("GATEWAY_INVALID_HOST"));
    mvc.perform(get("/auth/login").header(HttpHeaders.HOST, "panel.example:443"))
        .andExpect(status().isFound());
    mvc.perform(get("/auth/login").header(HttpHeaders.HOST, "PANEL.EXAMPLE"))
        .andExpect(status().isFound());
    mvc.perform(get("/auth/login").header(HttpHeaders.HOST, "panel.example:444"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void stripsSessionCookieFromApiButPreservesItForAuth() throws Exception {
    TASK_BOARD_REQUESTS.clear();
    AUTH_REQUESTS.clear();

    mvc.perform(
            publicGet("/api/task-board/worker-classes")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk());
    mvc.perform(publicGet("/auth/.well-known/openid-configuration").header(HttpHeaders.COOKIE, "AUTH_SESSION=secret"))
        .andExpect(status().isOk());

    assertThat(TASK_BOARD_REQUESTS).singleElement().extracting(CapturedRequest::cookie).isNull();
    assertThat(AUTH_REQUESTS).singleElement().extracting(CapturedRequest::cookie)
        .isEqualTo("AUTH_SESSION=secret");
  }

  @Test
  void forwardsCanonicalCorrelationWhenInboundValueIsMissingOrInvalid() throws Exception {
    TASK_BOARD_REQUESTS.clear();

    mvc.perform(
            publicGet("/api/task-board/worker-classes")
                .header("X-Correlation-Id", "not-a-uuid")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Correlation-Id"));
    mvc.perform(
            publicGet("/api/task-board/worker-classes")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Correlation-Id"));

    assertThat(TASK_BOARD_REQUESTS).hasSize(2).allSatisfy(request -> {
      assertThat(request.correlationId()).isNotBlank();
      java.util.UUID.fromString(request.correlationId());
    });
  }

  @Test
  void propagatesW3cTraceContextWithoutConflatingItWithDomainCorrelation() throws Exception {
    TASK_BOARD_REQUESTS.clear();
    String traceId = "0af7651916cd43dd8448eb211c80319c";
    String traceParent = "00-" + traceId + "-b7ad6b7169203331-01";
    String traceState = "vendor=opaque";
    String correlationId = java.util.UUID.randomUUID().toString();

    mvc.perform(
            publicGet("/api/task-board/worker-classes")
                .header("traceparent", traceParent)
                .header("tracestate", traceState)
                .header("X-Correlation-Id", correlationId)
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-Id", correlationId));

    assertThat(TASK_BOARD_REQUESTS)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.traceParent())
                  .matches("00-" + traceId + "-[0-9a-f]{16}-0[01]");
              assertThat(request.traceState()).contains("vendor=opaque");
              assertThat(request.correlationId())
                  .isEqualTo(correlationId)
                  .doesNotContain(traceId);
            });
  }

  @Test
  void deniesPublicAttemptsToReachInternalEndpoints() throws Exception {
    TASK_BOARD_REQUESTS.clear();
    AUTH_REQUESTS.clear();
    WAREHOUSE_REQUESTS.clear();
    ASSET_REQUESTS.clear();
    MEDIA_REQUESTS.clear();
    INVENTORY_REQUESTS.clear();
    LOGISTICS_REQUESTS.clear();

    mvc.perform(
            publicGet("/api/task-board/internal/work-queues")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("GATEWAY_FORBIDDEN"));
    mvc.perform(
            publicGet("/auth/api/internal/worker-credentials/worker-1")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("GATEWAY_FORBIDDEN"));
    mvc.perform(
            publicGet("/api/warehouse/internal/exists")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("GATEWAY_FORBIDDEN"));
    mvc.perform(
            publicGet("/api/warehouse/%69nternal/exists")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().is4xxClientError());
    mvc.perform(
            publicGet("/api/asset/internal/operation-leases")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("GATEWAY_FORBIDDEN"));
    mvc.perform(
            publicGet("/api/media/internal/owner-bindings")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("GATEWAY_FORBIDDEN"));
    mvc.perform(
            publicGet("/api/inventory/internal/captures")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("GATEWAY_FORBIDDEN"));
    mvc.perform(
            publicGet("/api/logistics/internal/reconciliation")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("GATEWAY_FORBIDDEN"));

    assertThat(TASK_BOARD_REQUESTS).isEmpty();
    assertThat(AUTH_REQUESTS).isEmpty();
    assertThat(WAREHOUSE_REQUESTS).isEmpty();
    assertThat(ASSET_REQUESTS).isEmpty();
    assertThat(MEDIA_REQUESTS).isEmpty();
    assertThat(INVENTORY_REQUESTS).isEmpty();
    assertThat(LOGISTICS_REQUESTS).isEmpty();
  }

  @Test
  void authCsrfDecisionAndFormArePreservedFromDownstream() throws Exception {
    mvc.perform(publicPost("/auth/csrf").contentType("application/x-www-form-urlencoded").content("value=1"))
        .andExpect(status().isForbidden());
    mvc.perform(
            publicPost("/auth/csrf")
                .contentType("application/x-www-form-urlencoded")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=session")
                .header("X-XSRF-TOKEN", "csrf-token")
                .content("value=1"))
        .andExpect(status().isOk());
  }

  @Test
  void allowsConfiguredCorsPreflightAndExposesCorrelationId() throws Exception {
    mvc.perform(
            publicOptions("/api/task-board/worker-classes")
                .header(HttpHeaders.ORIGIN, "https://panel.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(
                    HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                    "authorization,x-correlation-id,x-xsrf-token,idempotency-key"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://panel.example"))
        .andExpect(
            header().string(
                HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                org.hamcrest.Matchers.containsString("x-xsrf-token")));
    mvc.perform(
            publicOptions("/api/inventory/v1/sessions")
                .header(HttpHeaders.ORIGIN, "https://panel.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "idempotency-key"))
        .andExpect(status().isOk())
        .andExpect(header().string(
            HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
            org.hamcrest.Matchers.containsString("idempotency-key")));
  }

  @Test
  void exposesCorrelationIdOnActualCorsResponse() throws Exception {
    mvc.perform(
            publicGet("/api/task-board/worker-classes")
                .header(HttpHeaders.ORIGIN, "https://panel.example")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://panel.example"))
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
        .andExpect(header().stringValues(HttpHeaders.VARY, "Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers"))
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "X-Correlation-Id"));
  }

  @Test
  void deniesUnconfiguredCorsOrigin() throws Exception {
    mvc.perform(
            publicOptions("/api/task-board/worker-classes")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
        .andExpect(status().isForbidden());
  }

  private static HttpServer server(List<CapturedRequest> requests) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          CapturedRequest captured = capture(exchange);
          requests.add(captured);
          if (exchange.getRequestURI().getPath().equals("/login")) {
            exchange.getResponseHeaders().set(HttpHeaders.LOCATION, "https://panel.example/auth/authorize");
            exchange.getResponseHeaders().set(HttpHeaders.SET_COOKIE, "AUTH_SESSION=session; Path=/auth; HttpOnly");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
          }
          if (exchange.getRequestURI().getPath().equals("/csrf")) {
            boolean accepted =
                "AUTH_SESSION=session".equals(captured.cookie())
                    && "csrf-token".equals(captured.xsrfToken())
                    && "value=1".equals(captured.body());
            exchange.sendResponseHeaders(accepted ? 200 : 403, -1);
            exchange.close();
            return;
          }
          byte[] body = ("{\"path\":\"" + exchange.getRequestURI().getPath() + "\"}")
              .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    return server;
  }

  private static CapturedRequest capture(HttpExchange exchange) {
    return new CapturedRequest(
        exchange.getRequestURI().getPath(),
        exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION),
        exchange.getRequestHeaders().getOrDefault("Forwarded", List.of()),
        exchange.getRequestHeaders().getOrDefault("X-Forwarded-Host", List.of()),
        exchange.getRequestHeaders().getOrDefault("X-Forwarded-Prefix", List.of()),
        exchange.getRequestHeaders().getOrDefault("X-Forwarded-Proto", List.of()),
        exchange.getRequestHeaders().getOrDefault("X-Forwarded-Port", List.of()),
        exchange.getRequestHeaders().getFirst(HttpHeaders.COOKIE),
        exchange.getRequestHeaders().getFirst("X-Correlation-Id"),
        exchange.getRequestHeaders().getFirst("traceparent"),
        exchange.getRequestHeaders().getFirst("tracestate"),
        exchange.getRequestHeaders().getFirst("X-XSRF-TOKEN"),
        readBody(exchange));
  }

  private static String readBody(HttpExchange exchange) {
    try {
      return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String origin(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publicGet(
      String path) {
    return get(path).header(HttpHeaders.HOST, "panel.example");
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publicPost(
      String path) {
    return post(path).header(HttpHeaders.HOST, "panel.example");
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publicOptions(
      String path) {
    return options(path).header(HttpHeaders.HOST, "panel.example");
  }

  @TestConfiguration
  static class DecoderConfiguration {
    @Bean
    @Primary
    JwtDecoder jwtDecoder() {
      return token ->
          Jwt.withTokenValue(token)
              .header("alg", "none")
              .subject("test-user")
              .audience(List.of("rwms-services"))
              .issuedAt(Instant.now())
              .expiresAt(Instant.now().plusSeconds(60))
              .build();
    }
  }

  private record CapturedRequest(
      String path,
      String authorization,
      List<String> forwarded,
      List<String> forwardedHost,
      List<String> forwardedPrefix,
      List<String> forwardedProto,
      List<String> forwardedPort,
      String cookie,
      String correlationId,
      String traceParent,
      String traceState,
      String xsrfToken,
      String body) {}
}
