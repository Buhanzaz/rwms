package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkerRequest;
import dev.buhanzaz.rwms.taskboard.domain.CredentialStatus;
import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.ExternalServiceException;
import dev.buhanzaz.rwms.taskboard.service.HttpWarehouseLifecycleGateway;
import dev.buhanzaz.rwms.taskboard.service.HttpWarehouseTimeZoneGateway;
import dev.buhanzaz.rwms.taskboard.service.OAuthWorkerCredentialGateway;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.OperationDirection;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleReadinessConflictException;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
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
  private static final List<String> TOKEN_FORMS = new CopyOnWriteArrayList<>();
  private static final AtomicInteger WAREHOUSE_REQUESTS = new AtomicInteger();
  private static final AtomicReference<String> LAST_WAREHOUSE_AUTH = new AtomicReference<>();
  private static final AtomicReference<WarehouseLifecycleState> WAREHOUSE_STATE =
      new AtomicReference<>(WarehouseLifecycleState.ACTIVE);
  private static final AtomicBoolean WAREHOUSE_CONFIRM_CONFLICT = new AtomicBoolean();
  private static final AtomicReference<List<WarehouseTimeZoneFact>> WAREHOUSE_TIME_ZONES =
      new AtomicReference<>();
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
      SERVER.createContext(
          "/api/internal/warehouse/v1/",
          OAuthWorkerCredentialGatewayIntegrationTest::warehouseLifecycle);
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
    properties.add("rwms.warehouse.lifecycle.base-url", () -> BASE_URL);
  }

  @Autowired OAuthWorkerCredentialGateway gateway;
  @Autowired HttpWarehouseLifecycleGateway warehouseLifecycle;
  @Autowired HttpWarehouseTimeZoneGateway warehouseTimeZones;
  @Autowired OAuth2AuthorizedClientService clients;
  @Autowired ClientRegistrationRepository registrations;
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
    clients.removeAuthorizedClient(
        "warehouse-lifecycle-read", "task-board-service:warehouse-lifecycle-read");
    clients.removeAuthorizedClient(
        "warehouse-lifecycle-confirm", "task-board-service:warehouse-lifecycle-confirm");
    clients.removeAuthorizedClient(
        "warehouse-timezone-read", "task-board-service:warehouse-timezone-read");
    TOKEN_REQUESTS.set(0);
    CREDENTIAL_REQUESTS.set(0);
    LAST_TOKEN_FORM.set(null);
    LAST_CREDENTIAL_AUTH.set(null);
    APPLIED_WORKER.set(null);
    APPLIED_WAREHOUSE.set(null);
    APPLIED_LOGIN.set(null);
    TOKEN_FORMS.clear();
    WAREHOUSE_REQUESTS.set(0);
    LAST_WAREHOUSE_AUTH.set(null);
    WAREHOUSE_STATE.set(WarehouseLifecycleState.ACTIVE);
    WAREHOUSE_CONFIRM_CONFLICT.set(false);
    WAREHOUSE_TIME_ZONES.set(
        List.of(
            new WarehouseTimeZoneFact("Europe/Moscow", "2026-01-01T00:00:00Z"),
            new WarehouseTimeZoneFact("Europe/Samara", "2026-09-01T20:30:00Z")));
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

  @Test
  void warehouseLifecycleUsesSeparateExactTokensAndEnforcesDirectionalStates() {
    UUID warehouseId = UUID.randomUUID();

    warehouseLifecycle.requireAdmission(warehouseId, OperationDirection.INCOMING);
    WAREHOUSE_STATE.set(WarehouseLifecycleState.DRAINING);
    assertThatThrownBy(
            () -> warehouseLifecycle.requireAdmission(warehouseId, OperationDirection.INCOMING))
        .isInstanceOf(ConflictException.class);
    warehouseLifecycle.requireAdmission(warehouseId, OperationDirection.OUTGOING);
    WAREHOUSE_STATE.set(WarehouseLifecycleState.INACTIVE);
    assertThatThrownBy(
            () -> warehouseLifecycle.requireAdmission(warehouseId, OperationDirection.OUTGOING))
        .isInstanceOf(ConflictException.class);

    assertThat(warehouseLifecycle.readinessWork(null, 100).items()).isEmpty();
    warehouseLifecycle.confirmReadiness(warehouseId, 3L);

    assertThat(TOKEN_FORMS)
        .anyMatch(form -> form.contains("scope=warehouse.lifecycle.read"))
        .anyMatch(form -> form.contains("scope=warehouse.lifecycle.confirm"));
    assertThat(LAST_WAREHOUSE_AUTH.get()).startsWith("Bearer token-");
    assertThat(registrations.findByRegistrationId("warehouse-lifecycle-read").getClientId())
        .isEqualTo("task-board-service");
    assertThat(registrations.findByRegistrationId("warehouse-lifecycle-read").getScopes())
        .containsExactly("warehouse.lifecycle.read");
    assertThat(registrations.findByRegistrationId("warehouse-lifecycle-confirm").getClientId())
        .isEqualTo("task-board-service");
    assertThat(registrations.findByRegistrationId("warehouse-lifecycle-confirm").getScopes())
        .containsExactly("warehouse.lifecycle.confirm");
  }

  @Test
  void warehouseLifecycleFailsClosedForWrongScopeAndReadinessVersionConflict() {
    UUID warehouseId = UUID.randomUUID();
    MODE.set(Mode.INSUFFICIENT_SCOPE);
    assertThatThrownBy(
            () -> warehouseLifecycle.requireAdmission(warehouseId, OperationDirection.INCOMING))
        .isInstanceOf(ExternalServiceException.class);
    assertThat(WAREHOUSE_REQUESTS).hasValue(0);

    clients.removeAuthorizedClient(
        "warehouse-lifecycle-confirm", "task-board-service:warehouse-lifecycle-confirm");
    MODE.set(Mode.LONG_LIVED);
    WAREHOUSE_CONFIRM_CONFLICT.set(true);
    assertThatThrownBy(() -> warehouseLifecycle.confirmReadiness(warehouseId, 4L))
        .isInstanceOf(WarehouseLifecycleReadinessConflictException.class);
  }

  @Test
  void warehouseTimezoneUsesExactScopeAndReconstructsScheduledDecisionAsSegments() {
    UUID warehouseId = UUID.randomUUID();
    var before = warehouseTimeZones.timeZoneAt(warehouseId, java.time.Instant.parse("2026-09-01T20:00:00Z"));
    var segments =
        warehouseTimeZones.timeline(
            warehouseId,
            java.time.Instant.parse("2026-09-01T20:00:00Z"),
            java.time.Instant.parse("2026-09-01T21:00:00Z"));

    assertThat(before.timeZone().getId()).isEqualTo("Europe/Moscow");
    assertThat(before.effectiveFrom()).isEqualTo(java.time.Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(segments)
        .extracting(segment -> segment.timeZone().getId())
        .containsExactly("Europe/Moscow", "Europe/Samara");
    assertThat(segments)
        .extracting(segment -> segment.fromInclusive(), segment -> segment.toExclusive())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                java.time.Instant.parse("2026-09-01T20:00:00Z"),
                java.time.Instant.parse("2026-09-01T20:30:00Z")),
            org.assertj.core.groups.Tuple.tuple(
                java.time.Instant.parse("2026-09-01T20:30:00Z"),
                java.time.Instant.parse("2026-09-01T21:00:00Z")));
    assertThat(TOKEN_FORMS).anyMatch(form -> form.contains("scope=warehouse.timezone.read"));
    assertThat(registrations.findByRegistrationId("warehouse-timezone-read").getClientId())
        .isEqualTo("task-board-service");
    assertThat(registrations.findByRegistrationId("warehouse-timezone-read").getScopes())
        .containsExactly("warehouse.timezone.read");
  }

  @Test
  void warehouseTimezoneFailsClosedBeforeAnyWarehouseRequestForWrongScope() {
    MODE.set(Mode.INSUFFICIENT_SCOPE);

    assertThatThrownBy(
            () ->
                warehouseTimeZones.timeZoneAt(
                    UUID.randomUUID(), java.time.Instant.parse("2026-09-01T20:00:00Z")))
        .isInstanceOf(ExternalServiceException.class);

    assertThat(WAREHOUSE_REQUESTS).hasValue(0);
  }

  @Test
  void warehouseTimezoneFailsClosedWhenHistoricalReverseWalkExceedsItsBound() {
    java.time.Instant start = java.time.Instant.parse("2026-01-01T00:00:00Z");
    WAREHOUSE_TIME_ZONES.set(
        java.util.stream.IntStream.rangeClosed(1, 101)
            .mapToObj(
                offset ->
                    new WarehouseTimeZoneFact(
                        offset % 2 == 0 ? "Europe/Moscow" : "Europe/Samara",
                        start.plusSeconds(offset).toString()))
            .toList());

    assertThatThrownBy(
            () ->
                warehouseTimeZones.timeline(
                    UUID.randomUUID(), start, start.plusSeconds(200)))
        .isInstanceOf(ExternalServiceException.class)
        .hasMessageContaining("bounded reconstruction");

    assertThat(WAREHOUSE_REQUESTS).hasValue(100);
  }

  private static void token(HttpExchange exchange) throws java.io.IOException {
    TOKEN_REQUESTS.incrementAndGet();
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("task-board-service:test-secret".getBytes(StandardCharsets.UTF_8));
    String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    LAST_TOKEN_FORM.set(requestBody);
    TOKEN_FORMS.add(requestBody);
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
    String scope =
        mode == Mode.INSUFFICIENT_SCOPE ? "rwms.read" : formValue(requestBody, "scope");
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

  private static void warehouseLifecycle(HttpExchange exchange) throws java.io.IOException {
    WAREHOUSE_REQUESTS.incrementAndGet();
    LAST_WAREHOUSE_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
    String path = exchange.getRequestURI().getPath();
    if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/time-zone")) {
      String warehouseId = between(path, "/warehouses/", "/time-zone");
      String at = formValue(exchange.getRequestURI().getRawQuery(), "at");
      WarehouseTimeZoneFact fact =
          WAREHOUSE_TIME_ZONES.get().stream()
              .filter(value -> !java.time.Instant.parse(value.effectiveFrom()).isAfter(java.time.Instant.parse(at)))
              .max(java.util.Comparator.comparing(WarehouseTimeZoneFact::effectiveFrom))
              .orElse(null);
      if (fact == null) {
        respond(exchange, 404, "{\"code\":\"NOT_FOUND\"}");
        return;
      }
      respond(
          exchange,
          200,
          "{\"warehouseId\":\""
              + warehouseId
              + "\",\"timeZone\":\""
              + fact.timeZone()
              + "\",\"effectiveFrom\":\""
              + fact.effectiveFrom()
              + "\"}");
      return;
    }
    if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/admission")) {
      String direction = formValue(exchange.getRequestURI().getRawQuery(), "direction");
      String warehouseId = between(path, "/warehouses/", "/admission");
      WarehouseLifecycleState state = WAREHOUSE_STATE.get();
      boolean admitted =
          switch (state) {
            case ACTIVE -> true;
            case DRAINING -> "OUTGOING".equals(direction);
            case INACTIVE -> false;
          };
      respond(
          exchange,
          200,
          "{\"warehouseId\":\""
              + warehouseId
              + "\",\"warehouseVersion\":3,\"lifecycleState\":\""
              + state
              + "\",\"direction\":\""
              + direction
              + "\",\"admitted\":"
              + admitted
              + "}");
      return;
    }
    if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/readiness-work")) {
      respond(exchange, 200, "{\"items\":[],\"nextAfter\":null}");
      return;
    }
    if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/lifecycle-readiness")) {
      if (WAREHOUSE_CONFIRM_CONFLICT.get()) {
        respond(exchange, 409, "{\"code\":\"STALE\"}");
        return;
      }
      String warehouseId = between(path, "/warehouses/", "/lifecycle-readiness");
      respond(
          exchange,
          200,
          "{\"warehouseId\":\""
              + warehouseId
              + "\",\"warehouseVersion\":3,\"lifecycleState\":\"DRAINING\",\"readinessOwner\":\"TASK_BOARD\",\"confirmedAt\":\"2026-08-05T10:00:00Z\"}");
      return;
    }
    respond(exchange, 404, "{\"code\":\"NOT_FOUND\"}");
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

  private static String formValue(String source, String field) {
    if (source == null) return null;
    for (String item : source.split("&")) {
      int separator = item.indexOf('=');
      if (separator < 0) continue;
      if (field.equals(item.substring(0, separator))) {
        return java.net.URLDecoder.decode(item.substring(separator + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static String between(String value, String prefix, String suffix) {
    int start = value.indexOf(prefix);
    int end = value.lastIndexOf(suffix);
    if (start < 0 || end < 0 || end <= start + prefix.length()) {
      throw new IllegalArgumentException("Unexpected warehouse lifecycle path: " + value);
    }
    return value.substring(start + prefix.length(), end);
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

  private enum WarehouseLifecycleState {
    ACTIVE,
    DRAINING,
    INACTIVE
  }

  private record WarehouseTimeZoneFact(String timeZone, String effectiveFrom) {}
}
