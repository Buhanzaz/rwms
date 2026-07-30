package dev.buhanzaz.rwms.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class HttpInventoryDependencyGatewayTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<UUID> responseWarehouseId = new AtomicReference<>();
  private final AtomicReference<Boolean> assetPresent = new AtomicReference<>(true);
  private final UUID cabinId = UUID.randomUUID();
  private HttpServer server;
  private HttpInventoryDependencyGateway gateway;

  @BeforeEach
  void startServer() throws IOException {
    assetPresent.set(true);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/internal/asset/v1/inventory/number-resolutions", this::resolveNumber);
    server.createContext(
        "/api/internal/asset/v1/inventory/assets", this::currentAsset);
    server.createContext(
        "/api/internal/asset/v1/inventory/validations", this::validateAssets);
    server.createContext(
        "/api/internal/maintenance/v1/inventory/repair-snapshots", this::repairSnapshots);
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    OAuth2AuthorizedClientManager authorizedClients = mock(OAuth2AuthorizedClientManager.class);
    when(authorizedClients.authorize(any()))
        .thenAnswer(
            invocation -> {
              OAuth2AuthorizeRequest request =
                  invocation.getArgument(0, OAuth2AuthorizeRequest.class);
              return switch (request.getClientRegistrationId()) {
                case "inventory-asset" ->
                    authorizedClient(
                        base,
                        "inventory-asset",
                        "inventory-asset-token",
                        "asset.inventory");
                case "inventory-maintenance" ->
                    authorizedClient(
                        base,
                        "inventory-maintenance",
                        "inventory-maintenance-token",
                        "maintenance.inventory");
                default -> null;
              };
            });
    gateway = new HttpInventoryDependencyGateway(
        RestClient.builder().build(),
        authorizedClients,
        new InventoryDependencyProperties.Validated(
            URI.create(base + "/oauth2/token"),
            "inventory-service",
            "secret",
            URI.create(base),
            URI.create(base),
            URI.create(base),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1)));
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void sendsFrozenWarehouseScopedNumberRequestAndPreservesCrossWarehouseTruth()
      throws Exception {
    UUID warehouseId = UUID.randomUUID();
    responseWarehouseId.set(warehouseId);

    InventoryDependencyGateway.NumberResolution resolved =
        gateway.resolveNumber(warehouseId, "БЫТ-001");

    assertThat(resolved.asset().assetId()).isEqualTo(cabinId);
    assertThat(resolved.asset().tenantSnapshot()).isEqualTo("Арендатор А");
    assertThat(authorization.get()).isEqualTo("Bearer inventory-asset-token");
    JsonNode body = mapper.readTree(requestBody.get());
    assertThat(body.path("warehouseId").asText()).isEqualTo(warehouseId.toString());
    assertThat(body.path("number").asText()).isEqualTo("БЫТ-001");
    assertThat(body.size()).isEqualTo(2);

    UUID otherWarehouseId = UUID.randomUUID();
    responseWarehouseId.set(otherWarehouseId);
    InventoryDependencyGateway.NumberResolution crossWarehouse =
        gateway.resolveNumber(warehouseId, "БЫТ-001");
    assertThat(crossWarehouse.asset().warehouseId()).isEqualTo(otherWarehouseId);
  }

  @Test
  void readsAuthoritativeLiveAssetSnapshotAndTreatsNotFoundAsAbsence() {
    UUID warehouseId = UUID.randomUUID();
    responseWarehouseId.set(warehouseId);

    InventoryDependencyGateway.LiveAssetSnapshot snapshot =
        gateway.currentAsset(cabinId).orElseThrow();

    assertThat(snapshot.warehouseId()).isEqualTo(warehouseId);
    assertThat(snapshot.status()).isEqualTo("AFTER_RENT");
    assertThat(snapshot.passportSnapshot().path("tenant").asText())
        .isEqualTo("Арендатор А");
    assertThat(snapshot.contentsSnapshot().isArray()).isTrue();
    assertThat(authorization.get()).isEqualTo("Bearer inventory-asset-token");

    assetPresent.set(false);
    assertThat(gateway.currentAsset(cabinId)).isEmpty();
  }

  @Test
  void acceptsExplicitJsonNullSnapshotsForAnAbsentValidatedAsset() throws Exception {
    UUID missingAssetId = UUID.randomUUID();

    InventoryDependencyGateway.Validation validation =
        gateway.validateAssets(java.util.List.of(missingAssetId));

    assertThat(validation.assets())
        .singleElement()
        .satisfies(
            asset -> {
              assertThat(asset.assetId()).isEqualTo(missingAssetId);
              assertThat(asset.found()).isFalse();
            });
    assertThat(mapper.readTree(requestBody.get()).path("assetIds").get(0).asText())
        .isEqualTo(missingAssetId.toString());
    assertThat(authorization.get()).isEqualTo("Bearer inventory-asset-token");
  }

  @Test
  void readsSortedSemanticRepairSnapshot() throws Exception {
    InventoryDependencyGateway.RepairSnapshots snapshots =
        gateway.repairSnapshots(java.util.List.of(cabinId));

    assertThat(snapshots.assets()).singleElement().satisfies(
        asset -> {
          assertThat(asset.assetId()).isEqualTo(cabinId);
          assertThat(asset.repairs()).singleElement().satisfies(
              repair -> {
                assertThat(repair.executionState()).isEqualTo("DRAFT");
                assertThat(repair.planFingerprintSha256()).hasSize(64);
              });
        });
    assertThat(mapper.readTree(requestBody.get()).path("assetIds").get(0).asText())
        .isEqualTo(cabinId.toString());
  }

  private OAuth2AuthorizedClient authorizedClient(
      String base, String registrationId, String tokenValue, String scope) {
    ClientRegistration registration = ClientRegistration
        .withRegistrationId(registrationId)
        .clientId("inventory-service")
        .clientSecret("secret")
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .tokenUri(base + "/oauth2/token")
        .build();
    OAuth2AccessToken token = new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER,
        tokenValue,
        Instant.now(),
        Instant.now().plusSeconds(60),
        Set.of(scope));
    return new OAuth2AuthorizedClient(registration, "inventory-service", token);
  }

  private void resolveNumber(HttpExchange exchange) throws IOException {
    authorization.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    UUID warehouseId = responseWarehouseId.get();
    respond(exchange, 200, """
        {"displayCanonicalNumber":"БЫТ-001","identityMatchKey":"БЫТ001","found":true,
         "asset":{"assetId":"%s","version":0,"warehouseId":"%s","status":"FREE",
          "displayCanonicalNumber":"БЫТ-001","identityMatchKey":"БЫТ001",
          "tenantSnapshot":"Арендатор А"}}
        """.formatted(cabinId, warehouseId));
  }

  private void currentAsset(HttpExchange exchange) throws IOException {
    authorization.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    if (!assetPresent.get()) {
      respond(exchange, 404, "{}");
      return;
    }
    respond(
        exchange,
        200,
        """
        {"assetId":"%s","version":3,"warehouseId":"%s","status":"AFTER_RENT",
         "displayCanonicalNumber":"БЫТ-001","identityMatchKey":"БЫТ001",
         "tenantSnapshot":"Арендатор А",
         "passportSnapshot":{"tenant":"Арендатор А"},"contentsSnapshot":[]}
        """
            .formatted(cabinId, responseWarehouseId.get()));
  }

  private void validateAssets(HttpExchange exchange) throws IOException {
    authorization.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    UUID missingAssetId =
        UUID.fromString(mapper.readTree(requestBody.get()).path("assetIds").get(0).asText());
    respond(
        exchange,
        200,
        """
        {"validatedAt":"2026-07-27T12:00:00Z","validationDigest":"%s","assets":[{
          "assetId":"%s","found":false,"version":null,"warehouseId":null,
          "status":null,"displayCanonicalNumber":null,"identityMatchKey":null,
          "tenantSnapshot":null,"passportSnapshot":null,"contentsSnapshot":null
        }]}
        """
            .formatted("a".repeat(64), missingAssetId));
  }

  private void repairSnapshots(HttpExchange exchange) throws IOException {
    authorization.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    UUID repairId = UUID.randomUUID();
    respond(
        exchange,
        200,
        """
        {"assets":[{"assetId":"%s","repairs":[{
          "repairId":"%s","rootRepairId":"%s","origin":"DIRECT_REPAIR","kind":"PRIMARY",
          "executionState":"DRAFT","acceptanceState":"NOT_READY",
          "planFingerprintSha256":"%s"
        }]}]}
        """
            .formatted(cabinId, repairId, repairId, "a".repeat(64)));
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
