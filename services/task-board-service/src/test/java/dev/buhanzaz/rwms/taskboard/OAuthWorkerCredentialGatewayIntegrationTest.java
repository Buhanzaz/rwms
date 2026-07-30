package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkerRequest;
import dev.buhanzaz.rwms.taskboard.domain.CredentialStatus;
import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.OAuthWorkerCredentialGateway;
import dev.buhanzaz.rwms.taskboard.service.WorkerCredentialGateway.WorkerCredentialStatus;
import dev.buhanzaz.rwms.taskboard.service.WorkerCredentialOperationCoordinator;
import dev.buhanzaz.rwms.taskboard.service.WorkforceService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "rwms.task-board.credential-lock-max-connections=1")
@ActiveProfiles("test")
class OAuthWorkerCredentialGatewayIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");
  private static final AtomicInteger TOKEN_REQUESTS = new AtomicInteger();
  private static final AtomicInteger CREDENTIAL_REQUESTS = new AtomicInteger();
  private static final AtomicReference<Mode> MODE = new AtomicReference<>(Mode.LONG_LIVED);
  private static final AtomicReference<String> LAST_TOKEN_FORM = new AtomicReference<>();
  private static final AtomicReference<String> LAST_CREDENTIAL_AUTH = new AtomicReference<>();
  private static final AtomicReference<String> APPLIED_WORKER = new AtomicReference<>();
  private static final AtomicReference<String> APPLIED_WAREHOUSE = new AtomicReference<>();
  private static final AtomicReference<String> APPLIED_LOGIN = new AtomicReference<>();
  private static final HttpServer SERVER;
  private static final ExecutorService SERVER_EXECUTOR = Executors.newCachedThreadPool();
  private static final String BASE_URL;
  private static final UUID STATUS_WORKER =
      UUID.fromString("00000000-0000-0000-0000-000000000601");
  private static final UUID STATUS_WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000602");

  static {
    try {
      POSTGRES.start();
      SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      BASE_URL = "http://127.0.0.1:" + SERVER.getAddress().getPort();
      SERVER.createContext("/oauth2/token", OAuthWorkerCredentialGatewayIntegrationTest::token);
      SERVER.createContext(
          "/api/internal/worker-credentials",
          OAuthWorkerCredentialGatewayIntegrationTest::credentials);
      SERVER.setExecutor(SERVER_EXECUTOR);
      SERVER.start();
    } catch (Exception exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    properties.add(
        "spring.security.oauth2.client.provider.auth-service.token-uri",
        () -> BASE_URL + "/oauth2/token");
    properties.add(
        "rwms.auth.worker-credentials-url",
        () -> BASE_URL + "/api/internal/worker-credentials");
  }

  @Autowired OAuthWorkerCredentialGateway gateway;
  @Autowired OAuth2AuthorizedClientService clients;
  @Autowired WorkforceService workforce;
  @Autowired WorkerCredentialOperationCoordinator credentialCoordinator;

  @AfterAll
  static void stopServer() {
    SERVER.stop(0);
    SERVER_EXECUTOR.shutdownNow();
  }

  @BeforeEach
  void reset() {
    clients.removeAuthorizedClient("auth-service", "task-board-service");
    TOKEN_REQUESTS.set(0);
    CREDENTIAL_REQUESTS.set(0);
    LAST_TOKEN_FORM.set(null);
    LAST_CREDENTIAL_AUTH.set(null);
    APPLIED_WORKER.set(null);
    APPLIED_WAREHOUSE.set(null);
    APPLIED_LOGIN.set(null);
    MODE.set(Mode.LONG_LIVED);
  }

  @Test
  void usesExactClientCredentialsScopeForwardsBearerAndCachesToken() {
    UUID worker = UUID.randomUUID();
    gateway.configure(worker, UUID.randomUUID(), "worker.login", "password-123");
    gateway.reset(worker, "password-456");
    gateway.enable(worker);

    assertThat(TOKEN_REQUESTS).hasValue(1);
    assertThat(CREDENTIAL_REQUESTS).hasValue(3);
    assertThat(LAST_TOKEN_FORM.get())
        .contains("grant_type=client_credentials")
        .contains("scope=worker-credentials.manage");
    assertThat(LAST_CREDENTIAL_AUTH.get()).isEqualTo("Bearer token-1");
  }

  @Test
  void expiredTokenIsRenewedAndFailuresRemainFailClosed() {
    MODE.set(Mode.SHORT_LIVED);
    gateway.disable(UUID.randomUUID());
    gateway.disable(UUID.randomUUID());
    assertThat(TOKEN_REQUESTS.get()).isGreaterThanOrEqualTo(2);

    for (Mode mode : new Mode[] {Mode.WRONG_SECRET, Mode.INSUFFICIENT_SCOPE, Mode.UNAVAILABLE}) {
      clients.removeAuthorizedClient("auth-service", "task-board-service");
      MODE.set(mode);
      int credentialCalls = CREDENTIAL_REQUESTS.get();
      assertThatThrownBy(() -> gateway.delete(UUID.randomUUID())).isInstanceOf(RuntimeException.class);
      assertThat(CREDENTIAL_REQUESTS.get()).isEqualTo(credentialCalls);
    }
  }

  @Test
  void readsTypedCredentialStatusAndMapsNotFoundToAbsent() {
    var active = gateway.status(STATUS_WORKER, STATUS_WAREHOUSE);
    var absent =
        gateway.status(
            UUID.fromString("00000000-0000-0000-0000-000000000404"), STATUS_WAREHOUSE);

    assertThat(active.appLogin()).isEqualTo("worker.status");
    assertThat(active.status()).isEqualTo(WorkerCredentialStatus.ACTIVE);
    assertThat(absent.status()).isEqualTo(WorkerCredentialStatus.ABSENT);

    for (Mode invalid : new Mode[] {Mode.MALFORMED_STATUS, Mode.MISROUTED_STATUS}) {
      MODE.set(invalid);
      assertThatThrownBy(() -> gateway.status(STATUS_WORKER, STATUS_WAREHOUSE))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void deleteTreatsMissingCredentialAsAlreadyDeleted() {
    MODE.set(Mode.MISSING_DELETE);
    UUID workerId = UUID.randomUUID();

    gateway.delete(workerId);
    gateway.delete(workerId);

    assertThat(CREDENTIAL_REQUESTS).hasValue(2);
    assertThat(TOKEN_REQUESTS).hasValue(1);
  }

  @Test
  void configureConflictIsReportedAsSafeLoginConflict() {
    MODE.set(Mode.LOGIN_CONFLICT);

    var worker =
        workforce.createWorker(
            UUID.randomUUID(),
            new WorkerRequest(
                0L,
                "Conflicting login",
                null,
                null,
                null,
                true,
                null,
                "admin",
                "password-123",
                List.of()));

    assertThat(worker.credentialStatus()).isEqualTo(CredentialStatus.ERROR);
    assertThat(worker.credentialError()).isEqualTo("Логин приложения уже используется");
  }

  @Test
  void delayedTokenEndpointTimesOutAndReleasesPhysicalCredentialLock() {
    MODE.set(Mode.DELAY_TOKEN);
    long started = System.nanoTime();
    var worker =
        workforce.createWorker(
            UUID.randomUUID(),
            new WorkerRequest(
                0L,
                "Token timeout",
                null,
                null,
                null,
                true,
                null,
                "token.timeout",
                "password-123",
                List.of()));

    assertThat(worker.credentialStatus()).isEqualTo(CredentialStatus.ERROR);
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
        .isLessThan(java.time.Duration.ofSeconds(3));
    try (var reacquired = credentialCoordinator.tryAcquire(worker.id())) {
      assertThat(reacquired.databaseNow()).isNotNull();
    }
  }

  @Test
  void delayedCredentialResponsePreservesOperationUntilStatusConvergence() throws Exception {
    MODE.set(Mode.DELAY_CREDENTIAL);
    UUID warehouseId = UUID.randomUUID();
    var worker =
        workforce.createWorker(
            warehouseId,
            new WorkerRequest(
                0L,
                "Credential timeout",
                null,
                null,
                null,
                true,
                null,
                "credential.timeout",
                "password-123",
                List.of()));
    assertThat(worker.credentialStatus()).isEqualTo(CredentialStatus.ERROR);
    assertThatThrownBy(
            () ->
                workforce.reconcileDisableCredentials(
                    warehouseId, worker.id(), worker.version()))
        .isInstanceOf(ConflictException.class);

    Thread.sleep(1_250);
    var converged =
        workforce.reconcileDisableCredentials(
            warehouseId, worker.id(), worker.version());

    assertThat(converged.credentialStatus()).isEqualTo(CredentialStatus.ACTIVE);
    assertThat(converged.appLogin()).isEqualTo("credential.timeout");
  }

  private static void token(HttpExchange exchange) throws java.io.IOException {
    TOKEN_REQUESTS.incrementAndGet();
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("task-board-service:test-secret".getBytes(StandardCharsets.UTF_8));
    LAST_TOKEN_FORM.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    Mode mode = MODE.get();
    if (!expected.equals(authorization) || mode == Mode.WRONG_SECRET) {
      respond(exchange, 401, "{\"error\":\"invalid_client\"}");
      return;
    }
    if (mode == Mode.UNAVAILABLE) {
      respond(exchange, 503, "{\"error\":\"temporarily_unavailable\"}");
      return;
    }
    if (mode == Mode.DELAY_TOKEN) {
      try {
        Thread.sleep(1_500);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    }
    String scope = mode == Mode.INSUFFICIENT_SCOPE ? "rwms.read" : "worker-credentials.manage";
    int expiry = mode == Mode.SHORT_LIVED ? 1 : 300;
    respond(
        exchange,
        200,
        "{\"access_token\":\"token-"
            + TOKEN_REQUESTS.get()
            + "\",\"token_type\":\"Bearer\",\"expires_in\":"
            + expiry
            + ",\"scope\":\""
            + scope
            + "\"}");
  }

  private static void credentials(HttpExchange exchange) throws java.io.IOException {
    CREDENTIAL_REQUESTS.incrementAndGet();
    LAST_CREDENTIAL_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
    String body =
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String path = exchange.getRequestURI().getPath();
    if ("PUT".equals(exchange.getRequestMethod())) {
      String prefix = "/api/internal/worker-credentials/";
      APPLIED_WORKER.set(path.substring(prefix.length()));
      APPLIED_WAREHOUSE.set(jsonField(body, "warehouseId"));
      APPLIED_LOGIN.set(jsonField(body, "appLogin"));
      if (MODE.get() == Mode.LOGIN_CONFLICT) {
        respond(
            exchange,
            409,
            "{\"type\":\"about:blank\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"Конфликт уникальных или связанных данных\"}");
        return;
      }
      if (MODE.get() == Mode.DELAY_CREDENTIAL) {
        try {
          Thread.sleep(1_500);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
      }
    }
    if ("DELETE".equals(exchange.getRequestMethod()) && MODE.get() == Mode.MISSING_DELETE) {
      respond(
          exchange,
          404,
          "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"Worker credential not found\"}");
      return;
    }
    if ("GET".equals(exchange.getRequestMethod())
        && path.endsWith("/status")) {
      if (path.contains("00000000-0000-0000-0000-000000000404")) {
        respond(exchange, 404, "{\"code\":\"NOT_FOUND\"}");
      } else if (MODE.get() == Mode.MALFORMED_STATUS) {
        respond(
            exchange,
            200,
            "{\"workerId\":\"not-a-uuid\",\"warehouseId\":\""
                + STATUS_WAREHOUSE
                + "\",\"appLogin\":\"\",\"status\":\"ACTIVE\"}");
      } else if (MODE.get() == Mode.MISROUTED_STATUS) {
        respond(
            exchange,
            200,
            "{\"workerId\":\""
                + STATUS_WORKER
                + "\",\"warehouseId\":\"00000000-0000-0000-0000-000000000699\",\"appLogin\":\"worker.status\",\"status\":\"ACTIVE\"}");
      } else if (MODE.get() == Mode.DELAY_CREDENTIAL) {
        respond(
            exchange,
            200,
            "{\"workerId\":\""
                + APPLIED_WORKER.get()
                + "\",\"warehouseId\":\""
                + APPLIED_WAREHOUSE.get()
                + "\",\"appLogin\":\""
                + APPLIED_LOGIN.get()
                + "\",\"status\":\"ACTIVE\"}");
      } else {
        respond(
            exchange,
            200,
            "{\"workerId\":\""
                + STATUS_WORKER
                + "\",\"warehouseId\":\""
                + STATUS_WAREHOUSE
                + "\",\"appLogin\":\"worker.status\",\"status\":\"ACTIVE\"}");
      }
      return;
    }
    exchange.sendResponseHeaders(204, -1);
    exchange.close();
  }

  private static void respond(HttpExchange exchange, int status, String body)
      throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static String jsonField(String body, String field) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\\\"" + field + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .matcher(body);
    if (!matcher.find()) throw new IllegalArgumentException("Missing JSON field " + field);
    return matcher.group(1);
  }

  private enum Mode {
    LONG_LIVED,
    SHORT_LIVED,
    WRONG_SECRET,
    INSUFFICIENT_SCOPE,
    UNAVAILABLE,
    DELAY_TOKEN,
    DELAY_CREDENTIAL,
    MALFORMED_STATUS,
    MISROUTED_STATUS,
    MISSING_DELETE,
    LOGIN_CONFLICT
  }
}
