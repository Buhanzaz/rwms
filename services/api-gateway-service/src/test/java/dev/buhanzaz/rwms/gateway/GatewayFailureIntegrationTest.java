package dev.buhanzaz.rwms.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.http.clients.connect-timeout=100ms", "spring.http.clients.read-timeout=100ms"})
class GatewayFailureIntegrationTest {

  private static HttpServer slowServer;
  @LocalServerPort int gatewayPort;

  @BeforeAll
  static void startSlowServer() throws Exception {
    slowServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    slowServer.createContext(
        "/",
        exchange -> {
          try {
            if ("/api/worker/v1/events".equals(exchange.getRequestURI().getPath())
                || "/api/asset/v1/events".equals(exchange.getRequestURI().getPath())
                || "/api/media/v1/events".equals(exchange.getRequestURI().getPath())
                || exchange
                    .getRequestURI()
                    .getPath()
                    .matches("^/api/assistant/v1/conversations/[^/]+/turns$")) {
              exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
              exchange.sendResponseHeaders(200, 0);
              exchange
                  .getResponseBody()
                  .write("id: first\ndata: {\"revision\":1}\n\n".getBytes(StandardCharsets.UTF_8));
              exchange.getResponseBody().flush();
              Thread.sleep(250);
              exchange
                  .getResponseBody()
                  .write("id: second\ndata: {\"revision\":2}\n\n".getBytes(StandardCharsets.UTF_8));
              return;
            }
            Thread.sleep(1_000);
            exchange.sendResponseHeaders(204, -1);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    slowServer.start();
  }

  @AfterAll
  static void stopSlowServer() {
    slowServer.stop(0);
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("rwms.gateway.public-base-uri", () -> "http://gateway.test");
    registry.add("rwms.gateway.routes.auth-uri", GatewayFailureIntegrationTest::slowOrigin);
    registry.add("rwms.gateway.routes.task-board-uri", GatewayFailureIntegrationTest::slowOrigin);
    registry.add("rwms.gateway.routes.warehouse-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.asset-uri", GatewayFailureIntegrationTest::slowOrigin);
    registry.add("rwms.gateway.routes.maintenance-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.media-uri", GatewayFailureIntegrationTest::slowOrigin);
    registry.add("rwms.gateway.routes.inventory-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.logistics-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.logistics-planner-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.dossier-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.analytics-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.assistant-uri", GatewayFailureIntegrationTest::slowOrigin);
    registry.add("rwms.gateway.security.issuer", () -> "http://gateway.test/auth");
    registry.add("rwms.gateway.security.audience", () -> "rwms-services");
    registry.add("rwms.gateway.cors.allowed-origins", () -> "https://panel.example");
  }

  @Test
  void returnsSanitizedBadGatewayWhenConnectionIsRefused() throws Exception {
    HttpResponse<String> response = request("/api/dossier/v1/cabins/cabin-1");
    org.assertj.core.api.Assertions.assertThat(response.statusCode())
        .withFailMessage("Expected 502, got %s with %s", response.statusCode(), response.body())
        .isEqualTo(502);
    org.assertj.core.api.Assertions.assertThat(response.headers().firstValue("X-Correlation-Id")).isPresent();
    org.assertj.core.api.Assertions.assertThat(response.body())
        .contains("GATEWAY_UPSTREAM_UNAVAILABLE")
        .contains("The downstream service is unavailable")
        .doesNotContain("127.0.0.1:9");
  }

  @Test
  void returnsSanitizedGatewayTimeoutWhenReadDeadlineExpires() throws Exception {
    HttpResponse<String> response = request("/api/task-board/slow");
    org.assertj.core.api.Assertions.assertThat(response.statusCode())
        .withFailMessage("Expected 504, got %s with %s", response.statusCode(), response.body())
        .isEqualTo(504);
    org.assertj.core.api.Assertions.assertThat(response.headers().firstValue("X-Correlation-Id")).isPresent();
    org.assertj.core.api.Assertions.assertThat(response.body())
        .contains("GATEWAY_UPSTREAM_TIMEOUT")
        .contains("The downstream service did not respond in time")
        .doesNotContain(slowOrigin());
  }

  @Test
  void returnsExplicitServiceUnavailableWhenCadTargetIsNotConfigured() throws Exception {
    HttpResponse<String> response = request("/api/cad/v1/projects");

    org.assertj.core.api.Assertions.assertThat(response.statusCode())
        .withFailMessage("Expected 503, got %s with %s", response.statusCode(), response.body())
        .isEqualTo(503);
    org.assertj.core.api.Assertions.assertThat(response.headers().firstValue("X-Correlation-Id"))
        .isPresent();
    org.assertj.core.api.Assertions.assertThat(response.body())
        .contains("GATEWAY_CAD_SERVICE_UNCONFIGURED")
        .contains("The CAD service is not configured")
        .doesNotContain("CAD_SERVICE_URL", "127.0.0.1:9");
  }

  @Test
  void workerEventStreamIsNotCutOffByOrdinaryReadDeadline() throws Exception {
    HttpResponse<String> response = request("/api/task-board/worker/v1/events");

    org.assertj.core.api.Assertions.assertThat(response.statusCode()).isEqualTo(200);
    org.assertj.core.api.Assertions.assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
        .contains("text/event-stream");
    org.assertj.core.api.Assertions.assertThat(response.body())
        .contains("id: first", "id: second");
  }

  @Test
  void assetEventStreamIsNotCutOffByOrdinaryReadDeadline() throws Exception {
    HttpResponse<String> response = request("/api/asset/v1/events?warehouseId=warehouse-1");

    org.assertj.core.api.Assertions.assertThat(response.statusCode()).isEqualTo(200);
    org.assertj.core.api.Assertions.assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
        .contains("text/event-stream");
    org.assertj.core.api.Assertions.assertThat(response.body())
        .contains("id: first", "id: second");
  }

  @Test
  void mediaEventStreamIsNotCutOffByOrdinaryReadDeadline() throws Exception {
    HttpResponse<String> response = request("/api/media/v1/events?warehouseId=warehouse-1");

    org.assertj.core.api.Assertions.assertThat(response.statusCode()).isEqualTo(200);
    org.assertj.core.api.Assertions.assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
        .contains("text/event-stream");
    org.assertj.core.api.Assertions.assertThat(response.body())
        .contains("id: first", "id: second");
  }

  @Test
  void assistantTurnStreamIsNotCutOffByOrdinaryReadDeadline() throws Exception {
    HttpResponse<String> response =
        post("/api/assistant/v1/conversations/conversation-1/turns");

    org.assertj.core.api.Assertions.assertThat(response.statusCode()).isEqualTo(200);
    org.assertj.core.api.Assertions.assertThat(
            response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
        .contains("text/event-stream");
    org.assertj.core.api.Assertions.assertThat(response.body())
        .contains("id: first", "id: second");
  }

  private HttpResponse<String> request(String path) throws Exception {
    return HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
            .header(HttpHeaders.HOST, "gateway.test")
            .header(HttpHeaders.AUTHORIZATION, "Bearer valid")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path) throws Exception {
    return HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
            .header(HttpHeaders.HOST, "gateway.test")
            .header(HttpHeaders.AUTHORIZATION, "Bearer valid")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"test\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String slowOrigin() {
    return "http://127.0.0.1:" + slowServer.getAddress().getPort();
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
              .claim("scope", "worker.tasks")
              .issuedAt(Instant.now())
              .expiresAt(Instant.now().plusSeconds(60))
              .build();
    }
  }
}
