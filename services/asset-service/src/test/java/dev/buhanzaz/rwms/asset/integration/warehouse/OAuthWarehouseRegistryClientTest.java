package dev.buhanzaz.rwms.asset.integration.warehouse;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class OAuthWarehouseRegistryClientTest {
  private final UUID warehouseId = UUID.randomUUID();
  private final AtomicBoolean active = new AtomicBoolean(true);
  private final AtomicReference<String> tokenAuthorization = new AtomicReference<>();
  private final AtomicReference<String> tokenBody = new AtomicReference<>();
  private final AtomicReference<String> registryAuthorization = new AtomicReference<>();
  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/oauth2/token", this::token);
    server.createContext(
        "/api/internal/warehouse/v1/warehouses/asset/" + warehouseId + "/existence",
        this::warehouse);
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void usesOnlyTheAssetCredentialAndFailsClosedForAnInactiveWarehouse() {
    OAuthWarehouseRegistryClient client = new OAuthWarehouseRegistryClient(
        properties().requireEnabledConfiguration(),
        new ObjectMapper(),
        OAuthWarehouseRegistryClient.httpClient(Duration.ofSeconds(1)));

    client.requireActive(warehouseId);

    assertThat(tokenAuthorization.get()).isEqualTo("Basic YXNzZXQtc2VydmljZTpzZWNyZXQ=");
    assertThat(tokenBody.get()).isEqualTo("grant_type=client_credentials&scope=warehouse.read");
    assertThat(registryAuthorization.get()).isEqualTo("Bearer registry-token");

    active.set(false);
    assertThatThrownBy(() -> client.requireActive(warehouseId))
        .isInstanceOfSatisfying(
            AssetDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT));
  }

  private WarehouseRegistryProperties properties() {
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    return new WarehouseRegistryProperties(
        true,
        base,
        base + "/oauth2/token",
        "asset-service",
        "secret",
        Duration.ofSeconds(1),
        Duration.ofSeconds(1));
  }

  private void token(HttpExchange exchange) throws IOException {
    tokenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    tokenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    respond(exchange, 200, "{\"access_token\":\"registry-token\",\"token_type\":\"Bearer\",\"scope\":\"warehouse.read\"}");
  }

  private void warehouse(HttpExchange exchange) throws IOException {
    registryAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    respond(exchange, 200, "{\"id\":\"" + warehouseId + "\",\"version\":0,\"active\":" + active.get() + "}");
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
