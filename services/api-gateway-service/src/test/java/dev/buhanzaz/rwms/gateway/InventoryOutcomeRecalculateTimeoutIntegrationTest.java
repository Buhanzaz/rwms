package dev.buhanzaz.rwms.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Verifies the isolated timeout and transport policy for inventory outcome recalculation. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.http.clients.connect-timeout=100ms", "spring.http.clients.read-timeout=100ms"})
class InventoryOutcomeRecalculateTimeoutIntegrationTest {

  private static final List<CapturedRequest> INVENTORY_REQUESTS = new CopyOnWriteArrayList<>();
  private static HttpServer inventory;

  @LocalServerPort int gatewayPort;

  @BeforeAll
  static void startInventoryService() throws IOException {
    inventory = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    inventory.createContext("/", InventoryOutcomeRecalculateTimeoutIntegrationTest::respondSlowly);
    inventory.start();
  }

  @AfterAll
  static void stopInventoryService() {
    inventory.stop(0);
  }

  @BeforeEach
  void clearCapturedRequests() {
    INVENTORY_REQUESTS.clear();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("rwms.gateway.public-base-uri", () -> "http://gateway.test");
    registry.add("rwms.gateway.routes.auth-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.task-board-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.warehouse-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.asset-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.maintenance-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.media-uri", () -> "http://127.0.0.1:9");
    registry.add(
        "rwms.gateway.routes.inventory-uri",
        InventoryOutcomeRecalculateTimeoutIntegrationTest::inventoryOrigin);
    registry.add("rwms.gateway.routes.logistics-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.logistics-planner-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.dossier-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.analytics-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.assistant-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.security.issuer", () -> "http://gateway.test/auth");
    registry.add("rwms.gateway.security.audience", () -> "rwms-services");
    registry.add("rwms.gateway.cors.allowed-origins", () -> "https://panel.example");
  }

  @Test
  void recalculateUsesItsSixtySecondDeadlineWhilePreservingPublicProxyHeaders()
      throws Exception {
    String requestBody =
        "{\"expectedSessionRevision\":80,\"finalPlanVersion\":2,\"finalPlanSha256\":\"hash\"}";

    HttpResponse<String> response =
        post(
            "/api/inventory/v1/sessions/inventory-1/outcome/recalculate",
            requestBody,
            true);

    assertThat(response.statusCode()).isEqualTo(202);
    assertThat(response.body()).isEqualTo("{\"state\":\"SCHEDULED\"}");
    assertThat(INVENTORY_REQUESTS)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.path())
                  .isEqualTo(
                      "/api/inventory/v1/sessions/inventory-1/outcome/recalculate");
              assertThat(request.method()).isEqualTo("POST");
              assertThat(request.authorization()).isEqualTo("Bearer valid");
              assertThat(request.cookie()).isNull();
              assertThat(request.idempotencyKey()).isEqualTo("recalculate-1");
              assertThat(request.body()).isEqualTo(requestBody);
            });
  }

  @Test
  void ordinaryInventoryRequestsKeepTheSharedReadDeadlineAndProblemMapping() throws Exception {
    HttpResponse<String> response = get("/api/inventory/v1/sessions/inventory-1");

    assertThat(response.statusCode()).isEqualTo(504);
    assertThat(response.headers().firstValue("X-Correlation-Id")).isPresent();
    assertThat(response.body())
        .contains("GATEWAY_UPSTREAM_TIMEOUT")
        .contains("The downstream service did not respond in time")
        .doesNotContain(inventoryOrigin());
  }

  @Test
  void recalculateStillRequiresGatewayAuthentication() throws Exception {
    HttpResponse<String> response =
        post(
            "/api/inventory/v1/sessions/inventory-1/outcome/recalculate",
            "{\"expectedSessionRevision\":80}",
            false);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(INVENTORY_REQUESTS).isEmpty();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(request(path, true).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body, boolean authenticated)
      throws Exception {
    return HttpClient.newHttpClient()
        .send(
            request(path, authenticated)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header("Idempotency-Key", "recalculate-1")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path, boolean authenticated) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
            .header(HttpHeaders.HOST, "gateway.test")
            .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret");
    if (authenticated) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer valid");
    }
    return request;
  }

  private static String inventoryOrigin() {
    return "http://127.0.0.1:" + inventory.getAddress().getPort();
  }

  private static void respondSlowly(HttpExchange exchange) throws IOException {
    try {
      byte[] requestBody = exchange.getRequestBody().readAllBytes();
      INVENTORY_REQUESTS.add(
          new CapturedRequest(
              exchange.getRequestURI().getPath(),
              exchange.getRequestMethod(),
              exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION),
              exchange.getRequestHeaders().getFirst(HttpHeaders.COOKIE),
              exchange.getRequestHeaders().getFirst("Idempotency-Key"),
              new String(requestBody, StandardCharsets.UTF_8)));
      Thread.sleep(250);
      byte[] responseBody = "{\"state\":\"SCHEDULED\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
      exchange.sendResponseHeaders(202, responseBody.length);
      exchange.getResponseBody().write(responseBody);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  /** Captured downstream request data needed to prove transparent and cookie-free forwarding. */
  private record CapturedRequest(
      String path,
      String method,
      String authorization,
      String cookie,
      String idempotencyKey,
      String body) {}

  /** Provides a deterministic JWT decoder for the gateway integration fixture. */
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
}
