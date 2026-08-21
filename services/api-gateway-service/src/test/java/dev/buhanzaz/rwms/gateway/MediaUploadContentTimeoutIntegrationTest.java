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

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.http.clients.connect-timeout=100ms", "spring.http.clients.read-timeout=100ms"})
class MediaUploadContentTimeoutIntegrationTest {

  private static final List<CapturedRequest> MEDIA_REQUESTS = new CopyOnWriteArrayList<>();
  private static HttpServer media;

  @LocalServerPort int gatewayPort;

  @BeforeAll
  static void startMediaService() throws IOException {
    media = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    media.createContext("/", MediaUploadContentTimeoutIntegrationTest::respondSlowly);
    media.start();
  }

  @AfterAll
  static void stopMediaService() {
    media.stop(0);
  }

  @BeforeEach
  void clearCapturedRequests() {
    MEDIA_REQUESTS.clear();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("rwms.gateway.public-base-uri", () -> "http://gateway.test");
    registry.add("rwms.gateway.routes.auth-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.task-board-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.warehouse-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.asset-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.maintenance-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.media-uri", MediaUploadContentTimeoutIntegrationTest::mediaOrigin);
    registry.add("rwms.gateway.routes.inventory-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.logistics-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.dossier-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.analytics-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.routes.assistant-uri", () -> "http://127.0.0.1:9");
    registry.add("rwms.gateway.security.issuer", () -> "http://gateway.test/auth");
    registry.add("rwms.gateway.security.audience", () -> "rwms-services");
    registry.add("rwms.gateway.cors.allowed-origins", () -> "https://panel.example");
  }

  @Test
  void uploadContentUsesItsFiveMinuteDeadlineWhilePreservingPublicProxyHeaders()
      throws Exception {
    assertUploadContentProxy(
        "/api/media/v1/upload-sessions/session-1/content",
        "image/jpeg",
        "upload-1");
  }

  @Test
  void uploadVariantUsesTheSameFiveMinuteDeadlineWithoutRewritingItsPublicPath()
      throws Exception {
    assertUploadContentProxy(
        "/api/media/v1/upload-sessions/session-1/variants/SMALL/content",
        "image/webp",
        "upload-small-1");
  }

  private void assertUploadContentProxy(String path, String contentType, String idempotencyKey)
      throws Exception {
    byte[] content = {1, 2, 3, 4, 5};

    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                request(path)
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header("Idempotency-Key", idempotencyKey)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.body()).isEqualTo("{\"state\":\"UPLOADED\"}");
    assertThat(MEDIA_REQUESTS)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.path()).isEqualTo(path);
              assertThat(request.method()).isEqualTo("PUT");
              assertThat(request.authorization()).isEqualTo("Bearer valid");
              assertThat(request.cookie()).isNull();
              assertThat(request.idempotencyKey()).isEqualTo(idempotencyKey);
              assertThat(request.contentType()).startsWith(contentType);
              assertThat(request.body()).containsExactly(content);
            });
  }

  @Test
  void ordinaryMediaRequestsKeepTheSharedReadDeadlineAndProblemMapping() throws Exception {
    HttpResponse<String> response = get("/api/media/v1/assets?ownerType=WORK_RESULT");

    assertThat(response.statusCode()).isEqualTo(504);
    assertThat(response.headers().firstValue("X-Correlation-Id")).isPresent();
    assertThat(response.body())
        .contains("GATEWAY_UPSTREAM_TIMEOUT")
        .contains("The downstream service did not respond in time")
        .doesNotContain(mediaOrigin());
  }

  @Test
  void uploadContentStillRequiresGatewayAuthentication() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://127.0.0.1:"
                                + gatewayPort
                                + "/api/media/v1/upload-sessions/session-1/content"))
                    .header(HttpHeaders.HOST, "gateway.test")
                    .header(HttpHeaders.CONTENT_TYPE, "image/jpeg")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[] {1}))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(MEDIA_REQUESTS).isEmpty();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
        .header(HttpHeaders.HOST, "gateway.test")
        .header(HttpHeaders.AUTHORIZATION, "Bearer valid")
        .header(HttpHeaders.COOKIE, "AUTH_SESSION=secret");
  }

  private static String mediaOrigin() {
    return "http://127.0.0.1:" + media.getAddress().getPort();
  }

  private static void respondSlowly(HttpExchange exchange) throws IOException {
    try {
      byte[] requestBody = exchange.getRequestBody().readAllBytes();
      MEDIA_REQUESTS.add(
          new CapturedRequest(
              exchange.getRequestURI().getPath(),
              exchange.getRequestMethod(),
              exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION),
              exchange.getRequestHeaders().getFirst(HttpHeaders.COOKIE),
              exchange.getRequestHeaders().getFirst("Idempotency-Key"),
              exchange.getRequestHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
              requestBody));
      Thread.sleep(250);
      byte[] responseBody = "{\"state\":\"UPLOADED\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
      exchange.sendResponseHeaders(201, responseBody.length);
      exchange.getResponseBody().write(responseBody);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  private record CapturedRequest(
      String path,
      String method,
      String authorization,
      String cookie,
      String idempotencyKey,
      String contentType,
      byte[] body) {}

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
