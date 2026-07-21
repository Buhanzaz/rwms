package dev.buhanzaz.rwms.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.buhanzaz.rwms.inventory.service.InventoryException;
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
  private final UUID cabinId = UUID.randomUUID();
  private HttpServer server;
  private HttpInventoryDependencyGateway gateway;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/internal/asset/v1/inventory/number-resolutions", this::resolveNumber);
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    OAuth2AuthorizedClientManager authorizedClients = mock(OAuth2AuthorizedClientManager.class);
    when(authorizedClients.authorize(any())).thenReturn(authorizedClient(base));
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
  void sendsFrozenWarehouseScopedNumberRequestAndRejectsCrossWarehouseTruth()
      throws Exception {
    UUID warehouseId = UUID.randomUUID();
    responseWarehouseId.set(warehouseId);

    InventoryDependencyGateway.NumberResolution resolved =
        gateway.resolveNumber(warehouseId, "БЫТ-001");

    assertThat(resolved.asset().assetId()).isEqualTo(cabinId);
    assertThat(authorization.get()).isEqualTo("Bearer inventory-asset-token");
    JsonNode body = mapper.readTree(requestBody.get());
    assertThat(body.path("warehouseId").asText()).isEqualTo(warehouseId.toString());
    assertThat(body.path("number").asText()).isEqualTo("БЫТ-001");
    assertThat(body.size()).isEqualTo(2);

    responseWarehouseId.set(UUID.randomUUID());
    assertThatThrownBy(() -> gateway.resolveNumber(warehouseId, "БЫТ-001"))
        .isInstanceOfSatisfying(
            InventoryException.class,
            exception -> {
              assertThat(exception.status().value()).isEqualTo(503);
              assertThat(exception.code()).isEqualTo("INVENTORY_DEPENDENCY_UNAVAILABLE");
            });
  }

  private OAuth2AuthorizedClient authorizedClient(String base) {
    ClientRegistration registration = ClientRegistration
        .withRegistrationId("inventory-asset")
        .clientId("inventory-service")
        .clientSecret("secret")
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .tokenUri(base + "/oauth2/token")
        .build();
    OAuth2AccessToken token = new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER,
        "inventory-asset-token",
        Instant.now(),
        Instant.now().plusSeconds(60),
        Set.of("asset.inventory"));
    return new OAuth2AuthorizedClient(registration, "inventory-service", token);
  }

  private void resolveNumber(HttpExchange exchange) throws IOException {
    authorization.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    UUID warehouseId = responseWarehouseId.get();
    respond(exchange, 200, """
        {"displayCanonicalNumber":"БЫТ-001","identityMatchKey":"БЫТ001","found":true,
         "asset":{"assetId":"%s","version":0,"warehouseId":"%s","status":"FREE",
          "displayCanonicalNumber":"БЫТ-001","identityMatchKey":"БЫТ001"}}
        """.formatted(cabinId, warehouseId));
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
