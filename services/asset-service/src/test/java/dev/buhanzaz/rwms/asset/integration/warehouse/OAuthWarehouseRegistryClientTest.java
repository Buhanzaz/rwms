package dev.buhanzaz.rwms.asset.integration.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.buhanzaz.rwms.asset.service.AssetDependencyException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
  private final AtomicReference<String> tokenValue = new AtomicReference<>("registry-token");
  private final AtomicReference<String> lifecycleState = new AtomicReference<>("ACTIVE");
  private final AtomicReference<String> operationMarkBody = new AtomicReference<>();
  private final AtomicInteger tokenExpiresIn = new AtomicInteger(300);
  private final AtomicInteger tokenRequests = new AtomicInteger();
  private final AtomicInteger tokenResponseDelayMillis = new AtomicInteger();
  private final AtomicInteger warehouseRequests = new AtomicInteger();
  private final AtomicInteger unauthorizedWarehouseResponses = new AtomicInteger();
  private final AtomicInteger warehouseProblemStatus = new AtomicInteger();
  private final ConcurrentLinkedQueue<String> issuedTokens = new ConcurrentLinkedQueue<>();
  private final CopyOnWriteArrayList<String> tokenBodies = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<String> warehouseAuthorizations = new CopyOnWriteArrayList<>();
  private HttpServer server;
  private ExecutorService serverExecutor;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverExecutor = Executors.newCachedThreadPool();
    server.setExecutor(serverExecutor);
    server.createContext("/oauth2/token", this::token);
    server.createContext(
        "/api/internal/warehouse/v1/warehouses/asset/" + warehouseId + "/existence",
        this::warehouse);
    server.createContext("/api/internal/warehouse/v1/warehouses/", this::warehouseLifecycle);
    server.createContext(
        "/api/internal/warehouse/v1/lifecycle/readiness-work", this::readinessWork);
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
    if (serverExecutor != null) serverExecutor.shutdownNow();
  }

  @Test
  void cachesAValidTokenAndFailsClosedForAnInactiveWarehouse() {
    OAuthWarehouseRegistryClient client = client();

    client.requireActive(warehouseId);

    assertThat(tokenAuthorization.get()).isEqualTo("Basic YXNzZXQtc2VydmljZTpzZWNyZXQ=");
    assertThat(tokenBody.get()).isEqualTo("grant_type=client_credentials&scope=warehouse.read");
    assertThat(warehouseAuthorizations).containsExactly("Bearer registry-token");
    assertThat(tokenRequests.get()).isEqualTo(1);

    active.set(false);
    assertThatThrownBy(() -> client.requireActive(warehouseId))
        .isInstanceOfSatisfying(
            AssetDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT));
    assertThat(tokenRequests.get()).isEqualTo(1);
    assertThat(warehouseAuthorizations).containsExactly("Bearer registry-token", "Bearer registry-token");
  }

  @Test
  void refreshesTheCachedTokenAtExpiresInMinusSafetySkew() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    tokenExpiresIn.set(31);
    issuedTokens.add("first-token");
    issuedTokens.add("second-token");
    OAuthWarehouseRegistryClient client = client(clock);

    client.requireActive(warehouseId);
    client.requireActive(warehouseId);
    clock.advance(Duration.ofSeconds(1));
    client.requireActive(warehouseId);

    assertThat(tokenRequests.get()).isEqualTo(2);
    assertThat(warehouseAuthorizations)
        .containsExactly("Bearer first-token", "Bearer first-token", "Bearer second-token");
  }

  @Test
  void usesASingleTokenRequestForConcurrentLookups() throws Exception {
    int lookupCount = 12;
    tokenResponseDelayMillis.set(200);
    OAuthWarehouseRegistryClient client = client();
    ExecutorService callers = Executors.newFixedThreadPool(lookupCount);
    CountDownLatch ready = new CountDownLatch(lookupCount);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Void>> calls = new ArrayList<>();

    try {
      for (int index = 0; index < lookupCount; index++) {
        calls.add(callers.submit(() -> {
          ready.countDown();
          start.await();
          client.requireActive(warehouseId);
          return null;
        }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (Future<Void> call : calls) call.get(5, TimeUnit.SECONDS);
    } finally {
      callers.shutdownNow();
    }

    assertThat(tokenRequests.get()).isEqualTo(1);
    assertThat(warehouseRequests.get()).isEqualTo(lookupCount);
  }

  @Test
  void invalidatesAndRefreshesOnlyOnceAfterAnUnauthorizedWarehouseResponse() {
    issuedTokens.add("stale-token");
    issuedTokens.add("fresh-token");
    unauthorizedWarehouseResponses.set(1);
    OAuthWarehouseRegistryClient client = client();

    client.requireActive(warehouseId);

    assertThat(tokenRequests.get()).isEqualTo(2);
    assertThat(warehouseRequests.get()).isEqualTo(2);
    assertThat(warehouseAuthorizations).containsExactly("Bearer stale-token", "Bearer fresh-token");
  }

  @Test
  void propagatesARepeatedUnauthorizedProblemAfterOneRetry() {
    issuedTokens.add("stale-token");
    issuedTokens.add("still-rejected-token");
    unauthorizedWarehouseResponses.set(2);
    OAuthWarehouseRegistryClient client = client();

    assertThatThrownBy(() -> client.requireActive(warehouseId))
        .isInstanceOfSatisfying(
            AssetDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY));

    assertThat(tokenRequests.get()).isEqualTo(2);
    assertThat(warehouseRequests.get()).isEqualTo(2);
  }

  @Test
  void propagatesWarehouseProblemDetailsWithoutTreatingThemAsSuccess() {
    warehouseProblemStatus.set(503);
    OAuthWarehouseRegistryClient client = client();

    assertThatThrownBy(() -> client.requireActive(warehouseId))
        .isInstanceOfSatisfying(
            AssetDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

    assertThat(tokenRequests.get()).isEqualTo(1);
    assertThat(warehouseRequests.get()).isEqualTo(1);
  }

  @Test
  void propagatesWarehouseNetworkFailuresWithoutRetrying() {
    OAuthWarehouseRegistryClient client = client();
    client.requireActive(warehouseId);
    server.stop(0);

    assertThatThrownBy(() -> client.requireActive(warehouseId))
        .isInstanceOfSatisfying(
            AssetDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

    assertThat(tokenRequests.get()).isEqualTo(1);
    assertThat(warehouseRequests.get()).isEqualTo(1);
  }

  @Test
  void usesDirectionalAdmissionInsteadOfTreatingDrainingAsGloballyInactive() {
    lifecycleState.set("DRAINING");
    OAuthWarehouseRegistryClient client = client();

    client.requireOutgoing(warehouseId);

    assertThatThrownBy(() -> client.requireIncoming(warehouseId))
        .isInstanceOfSatisfying(
            AssetDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT));
    assertThat(tokenBodies)
        .containsExactly("grant_type=client_credentials&scope=warehouse.lifecycle.read");
  }

  @Test
  void usesSeparateExactScopesForTimezoneMarkAndReadinessBoundaries() {
    OAuthWarehouseRegistryClient client = client();
    OffsetDateTime at = OffsetDateTime.parse("2026-08-01T08:30:00Z");

    var timeZone = client.timeZoneAt(warehouseId, at);
    client.markOperation(warehouseId, UUID.randomUUID(), at);
    var work = client.lifecycleReadinessWork(null, 100);
    client.confirmLifecycleReadiness(warehouseId, work.items().getFirst().warehouseVersion());

    assertThat(timeZone.warehouseId()).isEqualTo(warehouseId);
    assertThat(timeZone.timeZone()).isEqualTo("Europe/Samara");
    assertThat(work.items())
        .containsExactly(new WarehouseRegistryClient.WarehouseLifecycleReadinessWork(warehouseId, 4L, "DRAINING"));
    assertThat(operationMarkBody.get()).contains("operationId", "occurredAt");
    assertThat(tokenBodies)
        .containsExactlyInAnyOrder(
            "grant_type=client_credentials&scope=warehouse.timezone.read",
            "grant_type=client_credentials&scope=warehouse.operation.mark",
            "grant_type=client_credentials&scope=warehouse.lifecycle.read",
            "grant_type=client_credentials&scope=warehouse.lifecycle.confirm");
  }

  private OAuthWarehouseRegistryClient client() { return client(Clock.systemUTC()); }

  private OAuthWarehouseRegistryClient client(Clock clock) {
    return new OAuthWarehouseRegistryClient(
        properties().requireEnabledConfiguration(),
        new ObjectMapper(),
        OAuthWarehouseRegistryClient.httpClient(Duration.ofSeconds(1)),
        clock);
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
    String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    tokenBody.set(payload);
    tokenBodies.add(payload);
    tokenRequests.incrementAndGet();
    delay(tokenResponseDelayMillis.get());
    String issuedToken = issuedTokens.poll();
    if (issuedToken == null) issuedToken = tokenValue.get();
    String scope = payload.substring(payload.indexOf("scope=") + "scope=".length());
    respond(exchange, 200, "{\"access_token\":\"" + issuedToken
        + "\",\"token_type\":\"Bearer\",\"scope\":\"" + scope + "\",\"expires_in\":" + tokenExpiresIn.get() + "}");
  }

  private void warehouse(HttpExchange exchange) throws IOException {
    warehouseRequests.incrementAndGet();
    warehouseAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
    int problemStatus = warehouseProblemStatus.get();
    if (problemStatus != 0) {
      respondProblem(exchange, problemStatus);
      return;
    }
    if (unauthorizedWarehouseResponses.getAndUpdate(value -> value > 0 ? value - 1 : 0) > 0) {
      respondProblem(exchange, 401);
      return;
    }
    respond(exchange, 200, "{\"id\":\"" + warehouseId + "\",\"version\":0,\"active\":" + active.get() + "}");
  }

  private void warehouseLifecycle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (path.endsWith("/admission")) {
      String direction = exchange.getRequestURI().getQuery().substring("direction=".length());
      String state = lifecycleState.get();
      boolean admitted = "ACTIVE".equals(state) || ("DRAINING".equals(state) && "OUTGOING".equals(direction));
      respond(
          exchange,
          200,
          "{\"warehouseId\":\""
              + warehouseId
              + "\",\"warehouseVersion\":4,\"lifecycleState\":\""
              + state
              + "\",\"direction\":\""
              + direction
              + "\",\"admitted\":"
              + admitted
              + "}");
      return;
    }
    if (path.endsWith("/time-zone")) {
      respond(
          exchange,
          200,
          "{\"warehouseId\":\""
              + warehouseId
              + "\",\"timeZone\":\"Europe/Samara\",\"effectiveFrom\":\"2026-08-01T00:00:00Z\"}");
      return;
    }
    if (path.endsWith("/operation-marks")) {
      operationMarkBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respondNoContent(exchange);
      return;
    }
    if (path.endsWith("/lifecycle-readiness")) {
      respond(
          exchange,
          200,
          "{\"warehouseId\":\""
              + warehouseId
              + "\",\"warehouseVersion\":5,\"lifecycleState\":\"DRAINING\",\"readinessOwner\":\"ASSET\",\"confirmedAt\":\"2026-08-01T08:31:00Z\"}");
      return;
    }
    respondProblem(exchange, 404);
  }

  private void readinessWork(HttpExchange exchange) throws IOException {
    respond(
        exchange,
        200,
        "{\"items\":[{\"warehouseId\":\""
            + warehouseId
            + "\",\"warehouseVersion\":4,\"lifecycleState\":\"DRAINING\"}],\"nextAfter\":null}");
  }

  private static void delay(int milliseconds) throws IOException {
    if (milliseconds == 0) return;
    try {
      Thread.sleep(milliseconds);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Token response delay was interrupted", exception);
    }
  }

  private static void respondProblem(HttpExchange exchange, int status) throws IOException {
    respond(exchange, status, "application/problem+json", "{\"status\":" + status + "}");
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    respond(exchange, status, "application/json", body);
  }

  private static void respondNoContent(HttpExchange exchange) throws IOException {
    exchange.sendResponseHeaders(204, -1);
    exchange.close();
  }

  private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static final class MutableClock extends Clock {
    private Instant current;

    private MutableClock(Instant current) { this.current = current; }

    @Override
    public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override
    public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }

    @Override
    public Instant instant() { return current; }

    private void advance(Duration duration) { current = current.plus(duration); }
  }
}
