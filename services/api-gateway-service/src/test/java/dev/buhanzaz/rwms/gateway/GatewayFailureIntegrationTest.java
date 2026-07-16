package dev.buhanzaz.rwms.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    registry.add("rwms.gateway.routes.asset-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.security.issuer", () -> "http://gateway.test/auth");
    registry.add("rwms.gateway.security.audience", () -> "rwms-services");
    registry.add("rwms.gateway.security.jwk-set-uri", () -> slowOrigin() + "/oauth2/jwks");
    registry.add("rwms.gateway.cors.allowed-origins", () -> "https://panel.example");
  }

  @Test
  void returnsSanitizedBadGatewayWhenConnectionIsRefused() throws Exception {
    HttpResponse<String> response = request("/api/warehouse/v1/warehouses");
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

  private HttpResponse<String> request(String path) throws Exception {
    return HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
            .header(HttpHeaders.HOST, "gateway.test")
            .header(HttpHeaders.AUTHORIZATION, "Bearer valid")
            .GET()
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
              .issuedAt(Instant.now())
              .expiresAt(Instant.now().plusSeconds(60))
              .build();
    }
  }
}
