package dev.buhanzaz.rwms.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.buhanzaz.rwms.gateway.config.GatewayProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class GatewayRouteIntegrationTest {
  private static final String RELEASE_CERT_SHA256 =
      "AA:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:"
          + "10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F";

  private static HttpServer auth;
  private static HttpServer taskBoard;
  private static HttpServer warehouse;
  private static HttpServer asset;
  private static HttpServer maintenance;
  private static HttpServer media;
  private static HttpServer inventory;
  private static HttpServer logistics;
  private static HttpServer logisticsPlanner;
  private static HttpServer dossier;
  private static HttpServer analytics;
  private static HttpServer assistant;
  private static HttpServer cad;
  private static final List<CapturedRequest> AUTH_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> TASK_BOARD_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> WAREHOUSE_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> ASSET_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> MAINTENANCE_REQUESTS =
      new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> MEDIA_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> INVENTORY_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> LOGISTICS_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> LOGISTICS_PLANNER_REQUESTS =
      new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> DOSSIER_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> ANALYTICS_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> ASSISTANT_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<CapturedRequest> CAD_REQUESTS = new CopyOnWriteArrayList<>();

  @Autowired MockMvc mvc;
  @Autowired GatewayProperties properties;

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
    logisticsPlanner = server(LOGISTICS_PLANNER_REQUESTS);
    dossier = server(DOSSIER_REQUESTS);
    analytics = server(ANALYTICS_REQUESTS);
    assistant = server(ASSISTANT_REQUESTS);
    cad = server(CAD_REQUESTS);
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
    logisticsPlanner.stop(0);
    dossier.stop(0);
    analytics.stop(0);
    assistant.stop(0);
    cad.stop(0);
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
    registry.add("rwms.gateway.routes.logistics-planner-uri", () -> origin(logisticsPlanner));
    registry.add("rwms.gateway.routes.dossier-uri", () -> origin(dossier));
    registry.add("rwms.gateway.routes.analytics-uri", () -> origin(analytics));
    registry.add("rwms.gateway.routes.assistant-uri", () -> origin(assistant));
    registry.add("rwms.gateway.routes.cad-uri", () -> origin(cad));
    registry.add("rwms.gateway.public-base-uri", () -> "https://panel.example");
    registry.add("rwms.gateway.cors.allowed-origins", () -> "https://panel.example");
    registry.add("rwms.gateway.security.issuer", () -> "https://panel.example/auth");
    registry.add("rwms.gateway.security.audience", () -> "rwms-services");
    registry.add(
        "rwms.gateway.app-links.sha256-cert-fingerprints[0]",
        () -> RELEASE_CERT_SHA256);
  }

  @Test
  void publishesAndroidAssetLinksWithoutAuthenticationOrRuntimeSecrets() throws Exception {
    mvc.perform(publicGet("/.well-known/assetlinks.json"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    HttpHeaders.CACHE_CONTROL,
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("max-age=3600"),
                        org.hamcrest.Matchers.containsString("public"),
                        org.hamcrest.Matchers.containsString("no-transform"))))
        .andExpect(jsonPath("$[0].relation[0]").value("delegate_permission/common.handle_all_urls"))
        .andExpect(jsonPath("$[0].target.namespace").value("android_app"))
        .andExpect(
            jsonPath("$[0].target.package_name").value("dev.buhanzaz.rwms.worker"))
        .andExpect(
            jsonPath("$[0].target.sha256_cert_fingerprints[0]")
                .value(RELEASE_CERT_SHA256));
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
  void routesGlobalKpiConfigurationToCanonicalTaskBoardPaths() throws Exception {
    TASK_BOARD_REQUESTS.clear();

    for (var mapping :
        Map.of(
                "/api/task-board/kpi-palette", "/api/kpi-palette",
                "/api/task-board/kpi-settings", "/api/kpi-settings")
            .entrySet()) {
      mvc.perform(
              publicGet(mapping.getKey())
                  .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.path").value(mapping.getValue()));
    }

    assertThat(TASK_BOARD_REQUESTS)
        .extracting(CapturedRequest::path)
        .containsExactlyInAnyOrder("/api/kpi-palette", "/api/kpi-settings");
  }

  @Test
  void workerTaskBoardSurfaceRequiresDedicatedScopeAtTheGateway() throws Exception {
    TASK_BOARD_REQUESTS.clear();

    mvc.perform(
            publicGet("/api/task-board/worker/v1/feed")
                .with(
                    jwt()
                        .jwt(token -> token.audience(List.of("rwms-services")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_rwms.read"))))
        .andExpect(status().isForbidden());
    assertThat(TASK_BOARD_REQUESTS).isEmpty();

    mvc.perform(
            publicGet("/api/task-board/worker/v1/feed")
                .with(
                    jwt()
                        .jwt(token -> token.audience(List.of("rwms-services")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_worker.tasks"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/api/worker/v1/feed"));
    assertThat(TASK_BOARD_REQUESTS)
        .singleElement()
        .extracting(CapturedRequest::path)
        .isEqualTo("/api/worker/v1/feed");
  }

  @Test
  void driverTaskBoardSurfaceRequiresDedicatedScopeAtTheGateway() throws Exception {
    TASK_BOARD_REQUESTS.clear();

    mvc.perform(
            publicGet("/api/task-board/driver/v1/feed")
                .with(
                    jwt()
                        .jwt(token -> token.audience(List.of("rwms-services")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_worker.tasks"))))
        .andExpect(status().isForbidden());
    assertThat(TASK_BOARD_REQUESTS).isEmpty();

    mvc.perform(
            publicGet("/api/task-board/driver/v1/feed")
                .with(
                    jwt()
                        .jwt(token -> token.audience(List.of("rwms-services")))
                        .authorities(
                            new SimpleGrantedAuthority("SCOPE_worker.tasks"),
                            new SimpleGrantedAuthority("SCOPE_driver.tasks"))))
        .andExpect(status().isForbidden());
    assertThat(TASK_BOARD_REQUESTS).isEmpty();

    mvc.perform(
            publicGet("/api/task-board/driver/v1/feed")
                .with(
                    jwt()
                        .jwt(token -> token.audience(List.of("rwms-services")))
                        .authorities(new SimpleGrantedAuthority("SCOPE_driver.tasks"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/api/driver/v1/feed"));
    assertThat(TASK_BOARD_REQUESTS)
        .singleElement()
        .extracting(CapturedRequest::path)
        .isEqualTo("/api/driver/v1/feed");
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

    mvc.perform(
            publicGet("/api/logistics/public/v1/client-presentations/public-token")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.path")
                .value("/api/logistics/public/v1/client-presentations/public-token"));
    assertThat(LOGISTICS_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.authorization()).isNull();
      assertThat(request.cookie()).isNull();
    });

    LOGISTICS_REQUESTS.clear();
    mvc.perform(
            publicGet(
                    "/api/logistics/public/v1/cabin-photo-presentations/public-photo-token")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.path")
                .value(
                    "/api/logistics/public/v1/cabin-photo-presentations/public-photo-token"));
    assertThat(LOGISTICS_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.authorization()).isNull();
      assertThat(request.cookie()).isNull();
    });

    LOGISTICS_REQUESTS.clear();
    mvc.perform(
            publicGet(
                    "/api/logistics/public/v1/cabin-photo-presentations/public-photo-token/media/10000000-0000-0000-0000-000000000014/3/SMALL")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.path")
                .value(
                    "/api/logistics/public/v1/cabin-photo-presentations/public-photo-token/media/10000000-0000-0000-0000-000000000014/3/SMALL"));
    assertThat(LOGISTICS_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.authorization()).isNull();
      assertThat(request.cookie()).isNull();
    });

    String contractorToken = "contractor-token";
    String externalTaskId = "10000000-0000-0000-0000-000000000021";
    String entryId = "10000000-0000-0000-0000-000000000022";
    String evidenceId = "10000000-0000-0000-0000-000000000023";
    String mediaId = "10000000-0000-0000-0000-000000000024";

    LOGISTICS_REQUESTS.clear();
    mvc.perform(
            publicGet(
                    "/api/logistics/public/v1/contractor-route-shares/"
                        + contractorToken)
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret"))
        .andExpect(status().isOk());
    assertThat(LOGISTICS_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.path())
          .isEqualTo(
              "/api/logistics/public/v1/contractor-route-shares/" + contractorToken);
      assertThat(request.authorization()).isNull();
      assertThat(request.cookie()).isNull();
    });

    LOGISTICS_REQUESTS.clear();
    String contractorActionPath =
        "/api/logistics/public/v1/contractor-route-shares/"
            + contractorToken
            + "/tasks/"
            + externalTaskId
            + "/entries/"
            + entryId
            + "/actions";
    mvc.perform(
            publicPost(contractorActionPath)
                .header("Idempotency-Key", evidenceId)
                .contentType("application/json")
                .content("{\"action\":\"START\",\"expectedVersion\":0,\"evidenceId\":null}")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret"))
        .andExpect(status().isOk());
    assertThat(LOGISTICS_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.path()).isEqualTo(contractorActionPath);
      assertThat(request.authorization()).isNull();
      assertThat(request.cookie()).isNull();
    });

    LOGISTICS_REQUESTS.clear();
    String contractorEvidencePath =
        "/api/logistics/public/v1/contractor-route-shares/"
            + contractorToken
            + "/tasks/"
            + externalTaskId
            + "/entries/"
            + entryId
            + "/evidence/"
            + evidenceId;
    mvc.perform(
            publicPost(contractorEvidencePath)
                .header("Idempotency-Key", evidenceId)
                .header(
                    "X-Content-SHA256",
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .header("X-Captured-At", "2026-08-31T09:00:00Z")
                .contentType("image/jpeg")
                .content("photo")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret"))
        .andExpect(status().isOk());
    assertThat(LOGISTICS_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.path()).isEqualTo(contractorEvidencePath);
      assertThat(request.authorization()).isNull();
      assertThat(request.cookie()).isNull();
      assertThat(request.contentLength()).isNull();
      assertThat(request.contractorEvidenceLength()).isEqualTo("5");
    });

    LOGISTICS_REQUESTS.clear();
    String contractorMediaPath =
        "/api/logistics/public/v1/contractor-route-shares/"
            + contractorToken
            + "/tasks/"
            + externalTaskId
            + "/entries/"
            + entryId
            + "/media/"
            + mediaId
            + "/generations/1/variants/SMALL/content";
    mvc.perform(
            publicGet(contractorMediaPath)
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret"))
        .andExpect(status().isOk());
    assertThat(LOGISTICS_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.path()).isEqualTo(contractorMediaPath);
      assertThat(request.authorization()).isNull();
      assertThat(request.cookie()).isNull();
    });

    LOGISTICS_REQUESTS.clear();
    mvc.perform(publicPost(
            "/api/logistics/public/v1/contractor-route-shares/" + contractorToken))
        .andExpect(status().isUnauthorized());
    mvc.perform(publicGet(contractorActionPath)).andExpect(status().isUnauthorized());
    mvc.perform(publicPost(contractorEvidencePath + "/unexpected"))
        .andExpect(status().isUnauthorized());
    assertThat(LOGISTICS_REQUESTS).isEmpty();

    LOGISTICS_REQUESTS.clear();
    mvc.perform(
            publicPost(
                "/api/logistics/public/v1/cabin-photo-presentations/public-photo-token"))
        .andExpect(status().isUnauthorized());
    assertThat(LOGISTICS_REQUESTS).isEmpty();

    LOGISTICS_REQUESTS.clear();
    mvc.perform(
            publicPost(
                "/api/logistics/v1/cabins/10000000-0000-0000-0000-000000000014/photo-presentations"))
        .andExpect(status().isUnauthorized());
    assertThat(LOGISTICS_REQUESTS).isEmpty();
  }

  @Test
  void rewritesPlannerAdminCatalogRequestsAndPreservesBearerIdempotencyAndBody()
      throws Exception {
    LOGISTICS_PLANNER_REQUESTS.clear();

    mvc.perform(
            publicPost(
                    "/api/logistics-planner/v1/admin/warehouses/warehouse-1/trailers?source=panel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer original-token")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret")
                .header("Idempotency-Key", "planner-create-1")
                .contentType("application/json")
                .content("{\"name\":\"Trailer\",\"registration_number\":\"P-1\",\"active\":true}")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.path").value("/api/admin/warehouses/warehouse-1/trailers"));

    assertThat(LOGISTICS_PLANNER_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.path()).isEqualTo("/api/admin/warehouses/warehouse-1/trailers");
      assertThat(request.query()).isEqualTo("source=panel");
      assertThat(request.authorization()).isEqualTo("Bearer original-token");
      assertThat(request.cookie()).isNull();
      assertThat(request.idempotencyKey()).isEqualTo("planner-create-1");
      assertThat(request.body())
          .isEqualTo("{\"name\":\"Trailer\",\"registration_number\":\"P-1\",\"active\":true}");
    });
  }

  @Test
  void proxiesAssistantPathsAndRejectsPrivateSurfaces() throws Exception {
    ASSISTANT_REQUESTS.clear();

    mvc.perform(
            publicGet("/api/assistant/v1/conversations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer original-token")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/api/assistant/v1/conversations"));

    assertThat(ASSISTANT_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.authorization()).isEqualTo("Bearer original-token");
      assertThat(request.cookie()).isNull();
    });

    ASSISTANT_REQUESTS.clear();
    mvc.perform(
            publicGet("/api/assistant/internal/conversations")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden());
    mvc.perform(
            publicGet("/api/assistant/private/conversations")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden());
    assertThat(ASSISTANT_REQUESTS).isEmpty();
  }

  @Test
  void proxiesConfiguredCadV1RequestsWithoutRewritingTheirPathOrForwardingCookies()
      throws Exception {
    CAD_REQUESTS.clear();
    String requestPath = "/api/cad/v1/projects?source=panel";
    String requestBody = "{\"name\":\"Network CAD project\"}";

    mvc.perform(
            publicPost(requestPath)
                .header(HttpHeaders.AUTHORIZATION, "Bearer original-token")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret")
                .header("Idempotency-Key", "cad-create-1")
                .contentType("application/json")
                .content(requestBody)
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/api/cad/v1/projects"));

    assertThat(CAD_REQUESTS)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.path()).isEqualTo("/api/cad/v1/projects");
              assertThat(request.query()).isEqualTo("source=panel");
              assertThat(request.authorization()).isEqualTo("Bearer original-token");
              assertThat(request.cookie()).isNull();
              assertThat(request.idempotencyKey()).isEqualTo("cad-create-1");
              assertThat(request.body()).isEqualTo(requestBody);
            });
  }

  @Test
  void cadPublicRouteRequiresAuthenticationAndPrivateAliasesStayBlocked() throws Exception {
    CAD_REQUESTS.clear();

    mvc.perform(publicGet("/api/cad/v1/projects")).andExpect(status().isUnauthorized());
    mvc.perform(
            publicGet("/api/cad/internal/v1/projects")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden());
    mvc.perform(
            publicGet("/api/cad/private/v1/projects")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden());

    assertThat(CAD_REQUESTS).isEmpty();
  }

  @Test
  void proxiesOnlyPublicDossierPathsWithoutCookiesOrPathRewriting() throws Exception {
    DOSSIER_REQUESTS.clear();

    mvc.perform(
            publicGet("/api/dossier/v1/cabins/cabin-1?limit=25")
                .header(HttpHeaders.AUTHORIZATION, "Bearer original-token")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/api/dossier/v1/cabins/cabin-1"));

    assertThat(DOSSIER_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.path()).isEqualTo("/api/dossier/v1/cabins/cabin-1");
      assertThat(request.query()).isEqualTo("limit=25");
      assertThat(request.authorization()).isEqualTo("Bearer original-token");
      assertThat(request.cookie()).isNull();
    });

    DOSSIER_REQUESTS.clear();
    mvc.perform(
            publicGet("/api/dossier/%69nternal/replay")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().is4xxClientError());
    mvc.perform(
            publicGet("/api/dossier/private/source-facts")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden());
    mvc.perform(
            publicGet("/api/dossier/%2e%2e/asset/v1/rental-items")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().is4xxClientError());
    mvc.perform(
            publicPost("/api/dossier/v1/cabins/cabin-1")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().is4xxClientError());
    mvc.perform(
            publicPut("/api/dossier/v1/cabins/cabin-1")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().is4xxClientError());
    assertThat(DOSSIER_REQUESTS).isEmpty();
  }

  @Test
  void forwardsAuthenticatedAnalyticsReadsToTheVersionedInternalApi() throws Exception {
    ANALYTICS_REQUESTS.clear();
    String warehouseId = "b81706f4-9b19-4cca-92a8-ece9afbf16a1";

    mvc.perform(
            publicGet(
                    "/api/analytics/v1/warehouses/"
                        + warehouseId
                        + "/group-kpi?periodType=DAY&year=2026&month=7&day=30")
                .header(HttpHeaders.AUTHORIZATION, "Bearer original-token")
                .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.path")
                .value("/api/v1/warehouses/" + warehouseId + "/group-kpi"));

    assertThat(ANALYTICS_REQUESTS)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.path())
                  .isEqualTo("/api/v1/warehouses/" + warehouseId + "/group-kpi");
              assertThat(request.query().split("&"))
                  .containsExactlyInAnyOrder(
                      "periodType=DAY", "year=2026", "month=7", "day=30");
              assertThat(request.authorization()).isEqualTo("Bearer original-token");
              assertThat(request.cookie()).isNull();
            });

    ANALYTICS_REQUESTS.clear();
    mvc.perform(
            publicPost("/api/analytics/v1/warehouses/" + warehouseId + "/group-kpi")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().is4xxClientError());
    mvc.perform(
            publicGet("/api/analytics/%2e%2e/task-board/worker-classes")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().is4xxClientError());
    assertThat(ANALYTICS_REQUESTS).isEmpty();
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
                .header("X-Real-IP", "203.0.113.90")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isOk());

    assertThat(TASK_BOARD_REQUESTS).singleElement().satisfies(request -> {
      assertThat(request.forwarded()).doesNotContain("evil.example");
      assertThat(request.realIp()).isEmpty();
      assertThat(request.forwardedHost()).doesNotContain("evil.example");
      assertThat(request.forwardedPrefix()).doesNotContain("/evil");
    });
  }

  @Test
  void authReceivesCanonicalMetadataAndVerifiedClientAddressesOnly() throws Exception {
    AUTH_REQUESTS.clear();
    assertThat(properties.getTrustedProxyAddresses()).containsExactly("127.0.0.1", "::1");

    mvc.perform(
            publicGet("/auth/login")
                .header("Forwarded", "host=evil.example;proto=http")
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Port", "81")
                .header("X-Forwarded-Prefix", "/evil")
                .header("X-Forwarded-For", "203.0.113.90")
                .header("X-Real-IP", "198.51.100.41")
                .with(request -> {
                  request.setRemoteAddr("127.0.0.1");
                  return request;
                }))
        .andExpect(status().isFound())
        .andExpect(header().string(HttpHeaders.LOCATION, "https://panel.example/auth/authorize"))
        .andExpect(header().string(HttpHeaders.SET_COOKIE, "AUTH_SESSION=session; Path=/auth; HttpOnly"));
    mvc.perform(
            publicGet("/auth/login")
                .header("X-Real-IP", "198.51.100.42")
                .with(request -> {
                  request.setRemoteAddr("127.0.0.1");
                  return request;
                }))
        .andExpect(status().isFound());
    mvc.perform(
            publicGet("/auth/login").with(request -> {
              request.setRemoteAddr("127.0.0.1");
              return request;
            }))
        .andExpect(status().isFound());
    mvc.perform(
            publicGet("/auth/login")
                .header("X-Real-IP", "203.0.113.99")
                .header("X-Forwarded-For", "203.0.113.98")
                .with(request -> {
                  request.setRemoteAddr("198.51.100.43");
                  return request;
                }))
        .andExpect(status().isFound());

    assertThat(AUTH_REQUESTS).allSatisfy(request -> {
      assertThat(request.forwarded()).doesNotContain("evil.example");
      assertThat(request.realIp()).isEmpty();
      assertThat(request.forwardedHost()).containsExactly("panel.example");
      assertThat(request.forwardedProto()).containsExactly("https");
      assertThat(request.forwardedPort()).containsExactly("443");
      assertThat(request.forwardedPrefix()).containsExactly("/auth");
    });
    assertThat(AUTH_REQUESTS)
        .extracting(request -> request.forwardedFor().getFirst())
        .containsExactly("198.51.100.41", "198.51.100.42", "127.0.0.1", "198.51.100.43");
  }

  @Test
  void trustedProxyAndClientIpv6FormsUseCanonicalNumericIdentity() throws Exception {
    AUTH_REQUESTS.clear();

    mvc.perform(
            publicGet("/auth/login")
                .header("X-Real-IP", "2001:db8::1")
                .with(request -> {
                  request.setRemoteAddr("::1");
                  return request;
                }))
        .andExpect(status().isFound());
    mvc.perform(
            publicGet("/auth/login")
                .header("X-Real-IP", "2001:0db8:0:0:0:0:0:1")
                .with(request -> {
                  request.setRemoteAddr("0:0:0:0:0:0:0:1");
                  return request;
                }))
        .andExpect(status().isFound());

    assertThat(AUTH_REQUESTS)
        .extracting(request -> request.forwardedFor().getFirst())
        .containsExactly("2001:db8:0:0:0:0:0:1", "2001:db8:0:0:0:0:0:1");
  }

  @Test
  void trustedProxyRejectsPresentMalformedOrMultipleClientAddresses() throws Exception {
    AUTH_REQUESTS.clear();

    mvc.perform(
            publicGet("/auth/login")
                .header("X-Real-IP", "client.example")
                .with(request -> {
                  request.setRemoteAddr("127.0.0.1");
                  return request;
                }))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("GATEWAY_INVALID_CLIENT_ADDRESS"));
    mvc.perform(
            publicGet("/auth/login")
                .header("X-Real-IP", "198.51.100.42", "203.0.113.42")
                .with(request -> {
                  request.setRemoteAddr("127.0.0.1");
                  return request;
                }))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("GATEWAY_INVALID_CLIENT_ADDRESS"));

    assertThat(AUTH_REQUESTS).isEmpty();
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
    DOSSIER_REQUESTS.clear();
    ANALYTICS_REQUESTS.clear();
    ASSISTANT_REQUESTS.clear();
    CAD_REQUESTS.clear();

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
    mvc.perform(
            publicGet("/api/dossier/internal/replay")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("GATEWAY_FORBIDDEN"));
    mvc.perform(
            publicGet("/api/analytics/internal/replay")
                .with(jwt().jwt(token -> token.audience(List.of("rwms-services")))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("GATEWAY_FORBIDDEN"));
    mvc.perform(
            publicGet("/api/assistant/internal/conversations")
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
    assertThat(DOSSIER_REQUESTS).isEmpty();
    assertThat(ANALYTICS_REQUESTS).isEmpty();
    assertThat(ASSISTANT_REQUESTS).isEmpty();
    assertThat(CAD_REQUESTS).isEmpty();
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
                    "authorization,x-correlation-id,x-xsrf-token,idempotency-key,"
                        + "if-none-match,last-event-id"))
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
        .andExpect(
            header()
                .string(
                    HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("X-Correlation-Id"),
                        org.hamcrest.Matchers.containsString("ETag"),
                        org.hamcrest.Matchers.containsString("Retry-After"))));
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
        exchange.getRequestURI().getRawQuery(),
        exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION),
        exchange.getRequestHeaders().getOrDefault("Forwarded", List.of()),
        exchange.getRequestHeaders().getOrDefault("X-Real-IP", List.of()),
        exchange.getRequestHeaders().getOrDefault("X-Forwarded-For", List.of()),
        exchange.getRequestHeaders().getOrDefault("X-Forwarded-Host", List.of()),
        exchange.getRequestHeaders().getOrDefault("X-Forwarded-Prefix", List.of()),
        exchange.getRequestHeaders().getOrDefault("X-Forwarded-Proto", List.of()),
        exchange.getRequestHeaders().getOrDefault("X-Forwarded-Port", List.of()),
        exchange.getRequestHeaders().getFirst(HttpHeaders.COOKIE),
        exchange.getRequestHeaders().getFirst("X-Correlation-Id"),
        exchange.getRequestHeaders().getFirst("traceparent"),
        exchange.getRequestHeaders().getFirst("tracestate"),
        exchange.getRequestHeaders().getFirst("X-XSRF-TOKEN"),
        exchange.getRequestHeaders().getFirst(HttpHeaders.CONTENT_LENGTH),
        exchange.getRequestHeaders().getFirst("X-RWMS-Contractor-Evidence-Length"),
        exchange.getRequestHeaders().getFirst("Idempotency-Key"),
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

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publicPut(
      String path) {
    return put(path).header(HttpHeaders.HOST, "panel.example");
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
      String query,
      String authorization,
      List<String> forwarded,
      List<String> realIp,
      List<String> forwardedFor,
      List<String> forwardedHost,
      List<String> forwardedPrefix,
      List<String> forwardedProto,
      List<String> forwardedPort,
      String cookie,
      String correlationId,
      String traceParent,
      String traceState,
      String xsrfToken,
      String contentLength,
      String contractorEvidenceLength,
      String idempotencyKey,
      String body) {}
}
