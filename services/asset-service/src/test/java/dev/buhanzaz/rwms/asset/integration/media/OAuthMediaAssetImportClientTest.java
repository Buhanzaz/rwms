package dev.buhanzaz.rwms.asset.integration.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetDependencyException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OAuthMediaAssetImportClientTest {
  private final UUID importId = UUID.randomUUID();
  private final UUID warehouseId = UUID.randomUUID();
  private final UUID sourceRowId = UUID.randomUUID();
  private final UUID jobId = UUID.randomUUID();
  private final AtomicReference<String> grantedScope =
      new AtomicReference<>("media.asset-import");
  private final AtomicReference<String> tokenAuthorization =
      new AtomicReference<>();
  private final AtomicReference<String> tokenBody = new AtomicReference<>();
  private final AtomicReference<String> mediaAuthorization =
      new AtomicReference<>();
  private final AtomicReference<String> mediaPath = new AtomicReference<>();
  private final AtomicReference<String> mediaQuery = new AtomicReference<>();
  private final AtomicReference<String> mediaIdempotency =
      new AtomicReference<>();
  private final AtomicReference<String> mediaBody = new AtomicReference<>();
  private final AtomicInteger mediaStatus = new AtomicInteger(202);
  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/oauth2/token", this::token);
    server.createContext(
        "/api/internal/media/v1/asset-imports/preflight", this::preflight);
    server.createContext(
        "/api/internal/media/v1/asset-imports/" + jobId + "/activate",
        this::activate);
    server.createContext(
        "/api/internal/media/v1/asset-imports/" + jobId + "/replace-sources",
        this::replaceSources);
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void usesExactServiceScopeAndSendsThePublicUrlOnlyInThePreflightCommand()
      throws Exception {
    OAuthMediaAssetImportClient client = client();
    UUID idempotencyKey = UUID.randomUUID();

    MediaAssetImportJob result =
        client.preflight(
            importId,
            warehouseId,
            List.of(
                new MediaAssetImportSource(
                    sourceRowId,
                    "https://disk.yandex.ru/d/AbCdEfGhIjKlMn")),
            idempotencyKey);

    assertThat(result.jobId()).isEqualTo(jobId);
    assertThat(result.status())
        .isEqualTo(MediaAssetImportJob.Status.PREFLIGHT_PENDING);
    assertThat(tokenAuthorization.get())
        .isEqualTo("Basic YXNzZXQtc2VydmljZTpzZWNyZXQ=");
    assertThat(tokenBody.get())
        .isEqualTo(
            "grant_type=client_credentials&scope=media.asset-import");
    assertThat(mediaAuthorization.get()).isEqualTo("Bearer media-token");
    assertThat(mediaQuery.get()).isNull();
    assertThat(mediaIdempotency.get()).isEqualTo(idempotencyKey.toString());
    JsonNode request = new ObjectMapper().readTree(mediaBody.get());
    assertThat(request.path("assetImportId").asText())
        .isEqualTo(importId.toString());
    assertThat(request.path("warehouseId").asText())
        .isEqualTo(warehouseId.toString());
    assertThat(request.path("sources").path(0).path("sourceRowId").asText())
        .isEqualTo(sourceRowId.toString());
    assertThat(request.path("sources").path(0).path("publicUrl").asText())
        .isEqualTo("https://disk.yandex.ru/d/AbCdEfGhIjKlMn");
    assertThat(new ObjectMapper().writeValueAsString(result))
        .doesNotContain("disk.yandex.ru", "AbCdEfGhIjKlMn");
  }

  @Test
  void rejectsACombinedTokenScopeBeforeCallingMedia() {
    grantedScope.set("media.asset-import warehouse.read");

    assertThatThrownBy(
            () ->
                client()
                    .preflight(
                        importId,
                        warehouseId,
                        List.of(
                            new MediaAssetImportSource(
                                sourceRowId,
                                "https://disk.yandex.ru/d/AbCdEfGhIjKlMn")),
                        UUID.randomUUID()))
        .isInstanceOfSatisfying(
            AssetDependencyException.class,
            exception ->
                assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY));

    assertThat(mediaBody.get()).isNull();
  }

  @Test
  void mapsAnOwnerProofConflictToARetryableAssetConflict() {
    mediaStatus.set(409);

    assertThatThrownBy(
            () ->
                client()
                    .activate(
                        jobId,
                        List.of(
                            new MediaAssetImportBinding(
                                sourceRowId, UUID.randomUUID())),
                        UUID.randomUUID()))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageNotContaining("AbCdEfGhIjKlMn");
  }

  @Test
  void sendsCorrectedLinksOnlyToThePrivateReplaceSourcesCommand()
      throws Exception {
    UUID idempotencyKey = UUID.randomUUID();

    MediaAssetImportJob result =
        client()
            .replacePreflightSources(
                jobId,
                List.of(
                    new MediaAssetImportSource(
                        sourceRowId,
                        "https://disk.yandex.ru/d/QrStUvWxYz0123")),
                idempotencyKey);

    assertThat(result.status())
        .isEqualTo(MediaAssetImportJob.Status.PREFLIGHT_PENDING);
    assertThat(mediaPath.get())
        .isEqualTo(
            "/api/internal/media/v1/asset-imports/" + jobId + "/replace-sources");
    assertThat(mediaQuery.get()).isNull();
    assertThat(mediaIdempotency.get()).isEqualTo(idempotencyKey.toString());
    JsonNode request = new ObjectMapper().readTree(mediaBody.get());
    assertThat(request.has("assetImportId")).isFalse();
    assertThat(request.has("warehouseId")).isFalse();
    assertThat(request.path("sources").path(0).path("sourceRowId").asText())
        .isEqualTo(sourceRowId.toString());
    assertThat(request.path("sources").path(0).path("publicUrl").asText())
        .isEqualTo("https://disk.yandex.ru/d/QrStUvWxYz0123");
    assertThat(new ObjectMapper().writeValueAsString(result))
        .doesNotContain("disk.yandex.ru", "QrStUvWxYz0123");
  }

  @Test
  void rejectsMoreThanFiveHundredSourcesBeforeObtainingAToken() {
    List<MediaAssetImportSource> sources =
        java.util.stream.IntStream.rangeClosed(
                1, MediaAssetImportClient.MAX_SOURCES + 1)
            .mapToObj(
                index ->
                    new MediaAssetImportSource(
                        UUID.randomUUID(),
                        "https://disk.yandex.ru/d/AbCdEfGhIjKlMn"))
            .toList();

    assertThatThrownBy(
            () ->
                client()
                    .preflight(
                        importId,
                        warehouseId,
                        sources,
                        UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(tokenBody.get()).isNull();
  }

  private OAuthMediaAssetImportClient client() {
    return new OAuthMediaAssetImportClient(
        properties().requireEnabledConfiguration(),
        new ObjectMapper(),
        OAuthMediaAssetImportClient.httpClient(Duration.ofSeconds(1)));
  }

  private MediaAssetImportProperties properties() {
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    return new MediaAssetImportProperties(
        true,
        base,
        base + "/oauth2/token",
        "asset-service",
        "secret",
        Duration.ofSeconds(1),
        Duration.ofSeconds(1));
  }

  private void token(HttpExchange exchange) throws IOException {
    tokenAuthorization.set(
        exchange.getRequestHeaders().getFirst("Authorization"));
    tokenBody.set(
        new String(
            exchange.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8));
    respond(
        exchange,
        200,
        "{\"access_token\":\"media-token\",\"token_type\":\"Bearer\",\"scope\":\""
            + grantedScope.get()
            + "\"}");
  }

  private void preflight(HttpExchange exchange) throws IOException {
    captureMediaCommand(exchange);
    respond(exchange, mediaStatus.get(), job("PREFLIGHT_PENDING"));
  }

  private void activate(HttpExchange exchange) throws IOException {
    captureMediaCommand(exchange);
    respond(exchange, mediaStatus.get(), job("ACTIVATION_PENDING"));
  }

  private void replaceSources(HttpExchange exchange) throws IOException {
    captureMediaCommand(exchange);
    respond(exchange, mediaStatus.get(), job("PREFLIGHT_PENDING"));
  }

  private void captureMediaCommand(HttpExchange exchange) throws IOException {
    mediaPath.set(exchange.getRequestURI().getPath());
    mediaQuery.set(exchange.getRequestURI().getRawQuery());
    mediaAuthorization.set(
        exchange.getRequestHeaders().getFirst("Authorization"));
    mediaIdempotency.set(
        exchange.getRequestHeaders().getFirst("Idempotency-Key"));
    mediaBody.set(
        new String(
            exchange.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8));
  }

  private String job(String status) {
    return """
        {
          "jobId":"%s",
          "assetImportId":"%s",
          "warehouseId":"%s",
          "status":"%s",
          "preflightAttempts":0,
          "activationAttempts":0,
          "results":[{
            "sourceRowId":"%s",
            "prepared":0,
            "downloading":0,
            "imported":0,
            "skipped":0,
            "failed":0,
            "mediaIds":[],
            "warningCodes":[]
          }]
        }
        """
        .formatted(jobId, importId, warehouseId, status, sourceRowId);
  }

  private static void respond(
      HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
