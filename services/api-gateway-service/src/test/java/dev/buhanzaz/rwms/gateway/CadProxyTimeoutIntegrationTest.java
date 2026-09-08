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

/** Verifies the CAD-specific streaming proxy deadline and binary transport policy. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.http.clients.connect-timeout=100ms", "spring.http.clients.read-timeout=100ms"})
class CadProxyTimeoutIntegrationTest {

  private static final List<CapturedRequest> CAD_REQUESTS = new CopyOnWriteArrayList<>();
  private static HttpServer cad;

  @LocalServerPort int gatewayPort;

  @BeforeAll
  static void startCadService() throws IOException {
    cad = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    cad.createContext("/", CadProxyTimeoutIntegrationTest::respondSlowly);
    cad.start();
  }

  @AfterAll
  static void stopCadService() {
    cad.stop(0);
  }

  @BeforeEach
  void clearCapturedRequests() {
    CAD_REQUESTS.clear();
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
    registry.add("rwms.gateway.routes.inventory-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.logistics-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.logistics-planner-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.dossier-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.analytics-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.assistant-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.cad-uri", CadProxyTimeoutIntegrationTest::cadOrigin);
    registry.add("rwms.gateway.security.issuer", () -> "http://gateway.test/auth");
    registry.add("rwms.gateway.security.audience", () -> "rwms-services");
    registry.add("rwms.gateway.cors.allowed-origins", () -> "https://panel.example");
  }

  @Test
  void documentUploadUsesItsFortyFiveSecondDeadlineAndStreamsBinaryBytes() throws Exception {
    byte[] document = deterministicDocument();
    String path = "/api/cad/v1/projects/project-1/document?expectedVersion=4";

    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                request(path, true)
                    .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                    .header("Idempotency-Key", "cad-document-1")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(document))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.body()).isEqualTo("{\"revision\":2}");
    assertThat(CAD_REQUESTS)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.path()).isEqualTo("/api/cad/v1/projects/project-1/document");
              assertThat(request.query()).isEqualTo("expectedVersion=4");
              assertThat(request.method()).isEqualTo("PUT");
              assertThat(request.authorization()).isEqualTo("Bearer valid");
              assertThat(request.cookie()).isNull();
              assertThat(request.idempotencyKey()).isEqualTo("cad-document-1");
              assertThat(request.contentType()).startsWith("application/octet-stream");
              assertThat(request.body()).containsExactly(document);
            });
  }

  @Test
  void cadDocumentUploadStillRequiresGatewayAuthentication() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                request("/api/cad/v1/projects/project-1/document", false)
                    .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[] {1}))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(CAD_REQUESTS).isEmpty();
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

  private static byte[] deterministicDocument() {
    byte[] document = new byte[256 * 1024];
    for (int index = 0; index < document.length; index++) {
      document[index] = (byte) (index % 251);
    }
    return document;
  }

  private static String cadOrigin() {
    return "http://127.0.0.1:" + cad.getAddress().getPort();
  }

  private static void respondSlowly(HttpExchange exchange) throws IOException {
    try {
      byte[] requestBody = exchange.getRequestBody().readAllBytes();
      CAD_REQUESTS.add(
          new CapturedRequest(
              exchange.getRequestURI().getPath(),
              exchange.getRequestURI().getRawQuery(),
              exchange.getRequestMethod(),
              exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION),
              exchange.getRequestHeaders().getFirst(HttpHeaders.COOKIE),
              exchange.getRequestHeaders().getFirst("Idempotency-Key"),
              exchange.getRequestHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
              requestBody));
      Thread.sleep(250);
      byte[] responseBody = "{\"revision\":2}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
      exchange.sendResponseHeaders(201, responseBody.length);
      exchange.getResponseBody().write(responseBody);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  /** Captured downstream request data needed to prove transparent binary forwarding. */
  private record CapturedRequest(
      String path,
      String query,
      String method,
      String authorization,
      String cookie,
      String idempotencyKey,
      String contentType,
      byte[] body) {}

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
