package dev.buhanzaz.rwms.asset.integration.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.buhanzaz.rwms.asset.service.AssetDependencyException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Proves exact OAuth scope, narrow request and strict media proof decoding. */
class OAuthMediaCabinCreationSnapshotClientTest {
  private final UUID warehouseId = UUID.randomUUID();
  private final UUID cabinId = UUID.randomUUID();
  private final UUID folderId = UUID.randomUUID();
  private final UUID coverMediaId = UUID.randomUUID();
  private final UUID secondMediaId = UUID.randomUUID();
  private final AtomicReference<String> grantedScope =
      new AtomicReference<>("media.asset");
  private final AtomicReference<String> tokenBody = new AtomicReference<>();
  private final AtomicReference<String> mediaAuthorization = new AtomicReference<>();
  private final AtomicReference<String> mediaBody = new AtomicReference<>();
  private final AtomicReference<String> mediaResponse = new AtomicReference<>();
  private final AtomicReference<String> cacheControl =
      new AtomicReference<>("no-store");
  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    mediaResponse.set(snapshot());
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/oauth2/token", this::token);
    server.createContext(
        "/api/internal/media/v1/assets/cabin-creation-snapshots",
        this::snapshot);
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void requestsExactAssetScopeAndReturnsOnlyOpaqueCurrentProof()
      throws Exception {
    var result = client().read(warehouseId, cabinId).orElseThrow();

    assertThat(tokenBody.get())
        .isEqualTo("grant_type=client_credentials&scope=media.asset");
    assertThat(mediaAuthorization.get()).isEqualTo("Bearer media-token");
    JsonNode request = new ObjectMapper().readTree(mediaBody.get());
    assertThat(request.path("warehouseId").asText())
        .isEqualTo(warehouseId.toString());
    assertThat(request.path("cabinIds")).hasSize(1);
    assertThat(request.path("cabinIds").path(0).asText())
        .isEqualTo(cabinId.toString());
    assertThat(result.cabinId()).isEqualTo(cabinId);
    assertThat(result.warehouseId()).isEqualTo(warehouseId);
    assertThat(result.activeFolderId()).isEqualTo(folderId);
    assertThat(result.coverMediaId()).isEqualTo(coverMediaId);
    assertThat(result.photoCount()).isEqualTo(2);
    assertThat(result.readyPhotos())
        .extracting(MediaCabinCreationSnapshotClient.ReadyPhoto::mediaId)
        .containsExactly(coverMediaId, secondMediaId);
    assertThat(result.readyPhotos())
        .extracting(MediaCabinCreationSnapshotClient.ReadyPhoto::photoIndex)
        .containsExactly(0L, 1L);
    assertThat(result.readyPhotos())
        .extracting(MediaCabinCreationSnapshotClient.ReadyPhoto::checksumSha256)
        .containsExactly("a".repeat(64), "b".repeat(64));
  }

  @Test
  void missingOwnerProofReturnsEmptyWithoutFabricatingSnapshot() {
    mediaResponse.set("{\"items\":[]}");

    assertThat(client().read(warehouseId, cabinId)).isEmpty();
  }

  @Test
  void rejectsCombinedScopeOrMismatchedAndCacheableResponses() {
    grantedScope.set("media.asset media.asset-import");
    assertThatThrownBy(() -> client().read(warehouseId, cabinId))
        .isInstanceOfSatisfying(
            AssetDependencyException.class,
            exception ->
                assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY));
    assertThat(mediaBody.get()).isNull();

    grantedScope.set("media.asset");
    mediaResponse.set(
        snapshot().replace(cabinId.toString(), UUID.randomUUID().toString()));
    assertThatThrownBy(() -> client().read(warehouseId, cabinId))
        .isInstanceOfSatisfying(
            AssetDependencyException.class,
            exception ->
                assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY));

    mediaResponse.set(snapshot());
    cacheControl.set("max-age=60");
    assertThatThrownBy(() -> client().read(warehouseId, cabinId))
        .isInstanceOfSatisfying(
            AssetDependencyException.class,
            exception ->
                assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY));
  }

  private OAuthMediaCabinCreationSnapshotClient client() {
    return new OAuthMediaCabinCreationSnapshotClient(
        properties().requireEnabledConfiguration(),
        new ObjectMapper(),
        OAuthMediaCabinCreationSnapshotClient.httpClient(
            Duration.ofSeconds(1)));
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
    tokenBody.set(
        new String(
            exchange.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8));
    respond(
        exchange,
        "{\"access_token\":\"media-token\",\"token_type\":\"Bearer\",\"scope\":\""
            + grantedScope.get()
            + "\"}",
        "no-store");
  }

  private void snapshot(HttpExchange exchange) throws IOException {
    mediaAuthorization.set(
        exchange.getRequestHeaders().getFirst("Authorization"));
    mediaBody.set(
        new String(
            exchange.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8));
    respond(exchange, mediaResponse.get(), cacheControl.get());
  }

  private String snapshot() {
    return """
        {"items":[{
          "cabinId":"%s",
          "warehouseId":"%s",
          "activeFolderId":"%s",
          "coverMediaId":"%s",
          "photoCount":2,
          "readyPhotos":[
            {"mediaId":"%s","generation":3,"photoIndex":0,"checksumSha256":"%s","contentType":"image/webp","contentLength":101},
            {"mediaId":"%s","generation":2,"photoIndex":1,"checksumSha256":"%s","contentType":"image/jpeg","contentLength":102}
          ]
        }]}
        """
        .formatted(
            cabinId,
            warehouseId,
            folderId,
            coverMediaId,
            coverMediaId,
            "a".repeat(64),
            secondMediaId,
            "b".repeat(64));
  }

  private static void respond(
      HttpExchange exchange, String body, String cacheControl) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("Cache-Control", cacheControl);
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
