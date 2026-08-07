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
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
  private final AtomicReference<String> idempotencyKey = new AtomicReference<>();
  private final AtomicReference<String> lifecycleAuthorization = new AtomicReference<>();
  private final AtomicReference<String> operationAuthorization = new AtomicReference<>();
  private final AtomicReference<String> timeZoneAuthorization = new AtomicReference<>();
  private final AtomicReference<String> readinessWorkAuthorization = new AtomicReference<>();
  private final AtomicReference<String> readinessConfirmAuthorization = new AtomicReference<>();
  private final AtomicReference<UUID> responseWarehouseId = new AtomicReference<>();
  private final AtomicReference<Boolean> assetPresent = new AtomicReference<>(true);
  private final AtomicReference<String> reconciliationResponse = new AtomicReference<>();
  private final AtomicInteger freezePlanCalls = new AtomicInteger();
  private final AtomicReference<Integer> firstFreezePlanStatus = new AtomicReference<>();
  private final AtomicReference<String> firstFreezePlanIdempotencyKey = new AtomicReference<>();
  private final AtomicReference<String> secondFreezePlanIdempotencyKey = new AtomicReference<>();
  private final AtomicReference<String> firstFreezePlanBody = new AtomicReference<>();
  private final AtomicReference<String> secondFreezePlanBody = new AtomicReference<>();
  private final UUID cabinId = UUID.randomUUID();
  private HttpServer server;
  private HttpInventoryDependencyGateway gateway;

  @BeforeEach
  void startServer() throws IOException {
    assetPresent.set(true);
    freezePlanCalls.set(0);
    firstFreezePlanStatus.set(null);
    firstFreezePlanIdempotencyKey.set(null);
    secondFreezePlanIdempotencyKey.set(null);
    firstFreezePlanBody.set(null);
    secondFreezePlanBody.set(null);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/internal/asset/v1/inventory/number-resolutions", this::resolveNumber);
    server.createContext(
        "/api/internal/asset/v1/inventory/assets", this::currentAsset);
    server.createContext(
        "/api/internal/asset/v1/inventory/validations", this::validateAssets);
    server.createContext(
        "/api/internal/maintenance/v1/inventory/repair-snapshots", this::repairSnapshots);
    server.createContext(
        "/api/internal/maintenance/v1/inventory/reconciliations", this::applyReconciliation);
    server.createContext(
        "/api/internal/maintenance/v1/inventory/dispositions", this::createLossDisposition);
    server.createContext(
        "/api/internal/maintenance/v1/inventory/plans", this::freezePlan);
    server.createContext(
        "/api/internal/warehouse/v1/warehouses", this::warehouseOperation);
    server.createContext(
        "/api/internal/warehouse/v1/lifecycle/readiness-work", this::warehouseReadinessWork);
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
                case "inventory-warehouse-lifecycle-read" ->
                    authorizedClient(
                        base,
                        "inventory-warehouse-lifecycle-read",
                        "inventory-warehouse-lifecycle-read-token",
                        "warehouse.lifecycle.read");
                case "inventory-warehouse-operation" ->
                    authorizedClient(
                        base,
                        "inventory-warehouse-operation",
                        "inventory-warehouse-operation-token",
                        "warehouse.operation.mark");
                case "inventory-warehouse-timezone" ->
                    authorizedClient(
                        base,
                        "inventory-warehouse-timezone",
                        "inventory-warehouse-timezone-token",
                        "warehouse.timezone.read");
                case "inventory-warehouse-lifecycle-confirm" ->
                    authorizedClient(
                        base,
                        "inventory-warehouse-lifecycle-confirm",
                        "inventory-warehouse-lifecycle-confirm-token",
                        "warehouse.lifecycle.confirm");
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
  void beginsIncomingWarehouseOperationWithAdmissionMarkAndEffectiveTimeZone()
      throws Exception {
    UUID warehouseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    responseWarehouseId.set(warehouseId);
    OffsetDateTime occurredAt = OffsetDateTime.parse("2026-09-02T08:15:30Z");

    InventoryDependencyGateway.WarehouseOperation operation =
        gateway.beginWarehouseOperation(
            warehouseId,
            operationId,
            occurredAt,
            InventoryDependencyGateway.WarehouseOperationDirection.INCOMING);

    assertThat(operation.warehouseId()).isEqualTo(warehouseId);
    assertThat(operation.warehouseVersion()).isEqualTo(7);
    assertThat(operation.lifecycleState()).isEqualTo("ACTIVE");
    assertThat(operation.timeZone()).isEqualTo("Europe/Samara");
    assertThat(operation.timeZoneEffectiveFrom())
        .isEqualTo(OffsetDateTime.parse("2026-09-01T00:00:00Z"));
    assertThat(lifecycleAuthorization.get())
        .isEqualTo("Bearer inventory-warehouse-lifecycle-read-token");
    assertThat(operationAuthorization.get())
        .isEqualTo("Bearer inventory-warehouse-operation-token");
    assertThat(timeZoneAuthorization.get())
        .isEqualTo("Bearer inventory-warehouse-timezone-token");
    JsonNode mark = mapper.readTree(requestBody.get());
    assertThat(mark.path("operationId").asText()).isEqualTo(operationId.toString());
    assertThat(mark.path("occurredAt").asText()).isEqualTo(occurredAt.toString());
  }

  @Test
  void readsAndConfirmsOwnerScopedWarehouseLifecycleReadiness() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    responseWarehouseId.set(warehouseId);

    InventoryDependencyGateway.WarehouseLifecycleReadinessWorkPage work =
        gateway.warehouseLifecycleReadinessWork(null, 100);
    assertThat(work.nextAfter()).isNull();
    assertThat(work.items())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.warehouseId()).isEqualTo(warehouseId);
              assertThat(item.warehouseVersion()).isEqualTo(11);
              assertThat(item.lifecycleState()).isEqualTo("DRAINING");
            });
    assertThat(readinessWorkAuthorization.get())
        .isEqualTo("Bearer inventory-warehouse-lifecycle-read-token");

    InventoryDependencyGateway.WarehouseLifecycleReadinessConfirmation confirmed =
        gateway.confirmWarehouseLifecycleReadiness(warehouseId, 11);
    assertThat(confirmed.readinessOwner()).isEqualTo("INVENTORY");
    assertThat(confirmed.warehouseVersion()).isEqualTo(11);
    assertThat(readinessConfirmAuthorization.get())
        .isEqualTo("Bearer inventory-warehouse-lifecycle-confirm-token");
    assertThat(mapper.readTree(requestBody.get()).path("expectedVersion").asLong())
        .isEqualTo(11);
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

  @Test
  void acceptsMatchedReconciliationWithoutATargetAndRejectsAnyMatchedTarget() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    reconciliationResponse.set(
        """
        {"source":{"inventoryId":"%s","finalPlanVersion":1,"findingId":"%s"},
         "outcome":"MATCHED","targetKind":null,"targetId":null,"estimateId":null,
         "repairId":null,"successor":null,"delta":{"lines":[]}}
        """.formatted(inventoryId, findingId));

    JsonNode matched =
        gateway.applyReconciliation(inventoryId, findingId, UUID.randomUUID(), mapper.createObjectNode());

    assertThat(matched.path("outcome").asText()).isEqualTo("MATCHED");
    assertThat(matched.path("targetId").isNull()).isTrue();
    assertThat(authorization.get()).isEqualTo("Bearer inventory-maintenance-token");

    UUID repairId = UUID.randomUUID();
    reconciliationResponse.set(
        """
        {"source":{"inventoryId":"%s","finalPlanVersion":1,"findingId":"%s"},
         "outcome":"MATCHED","targetKind":"REPAIR","targetId":"%s","estimateId":null,
         "repairId":"%s","successor":null,"delta":{"lines":[]}}
        """.formatted(inventoryId, findingId, repairId, repairId));

    assertThatThrownBy(
            () ->
                gateway.applyReconciliation(
                    inventoryId, findingId, UUID.randomUUID(), mapper.createObjectNode()))
        .isInstanceOf(InventoryException.class)
        .hasMessageContaining("malformed reconciliation result");
  }

  @Test
  void createsInventoryLossWithExactMaintenanceIdentityAndBalanceFence() throws Exception {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID key = UUID.randomUUID();

    InventoryDependencyGateway.InventoryLossDisposition response =
        gateway.createInventoryLossDisposition(
            key,
            new InventoryDependencyGateway.InventoryLossDispositionRequest(
                inventoryId,
                findingId,
                warehouseId,
                equipmentId,
                "Стул",
                4,
                3,
                9,
                "Недостача по итогам инвентаризации",
                null));

    assertThat(response.inventorySessionId()).isEqualTo(inventoryId);
    assertThat(response.findingId()).isEqualTo(findingId);
    assertThat(response.assetId()).isEqualTo(equipmentId);
    assertThat(response.disposition()).isEqualTo("LOSS");
    assertThat(authorization.get()).isEqualTo("Bearer inventory-maintenance-token");
    assertThat(idempotencyKey.get()).isEqualTo(key.toString());
    JsonNode body = mapper.readTree(requestBody.get());
    assertThat(body.path("expectedAssetVersion").asLong()).isEqualTo(4);
    assertThat(body.path("expectedSourceBalanceVersion").asLong()).isEqualTo(9);
    assertThat(body.path("quantity").asLong()).isEqualTo(3);
    assertThat(body.path("evidenceLink").isNull()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {502, 503, 504})
  void retriesOneTransientFreezePlanFailureWithTheSameRequestAndIdempotencyKey(
      int transientStatus) throws Exception {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    firstFreezePlanStatus.set(transientStatus);
    JsonNode request =
        mapper
            .createObjectNode()
            .put("warehouseId", warehouseId.toString())
            .put("inventoryId", inventoryId.toString())
            .put("findingId", findingId.toString());

    InventoryDependencyGateway.FrozenPlan plan = gateway.freezePlan(idempotencyKey, request);

    assertThat(plan.warehouseId()).isEqualTo(warehouseId);
    assertThat(plan.inventoryId()).isEqualTo(inventoryId);
    assertThat(plan.findingId()).isEqualTo(findingId);
    assertThat(plan.sourceRevision()).isEqualTo(1);
    assertThat(freezePlanCalls.get()).isEqualTo(2);
    assertThat(firstFreezePlanIdempotencyKey.get()).isEqualTo(idempotencyKey.toString());
    assertThat(secondFreezePlanIdempotencyKey.get()).isEqualTo(idempotencyKey.toString());
    assertThat(mapper.readTree(firstFreezePlanBody.get())).isEqualTo(request);
    assertThat(mapper.readTree(secondFreezePlanBody.get())).isEqualTo(request);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 422})
  void doesNotRetryFreezePlanClientErrors(int status) {
    firstFreezePlanStatus.set(status);
    JsonNode request =
        mapper
            .createObjectNode()
            .put("warehouseId", UUID.randomUUID().toString())
            .put("inventoryId", UUID.randomUUID().toString())
            .put("findingId", UUID.randomUUID().toString());

    assertThatThrownBy(() -> gateway.freezePlan(UUID.randomUUID(), request))
        .isInstanceOf(InventoryException.class)
        .hasMessage("Dependency rejected invalid inventory input");

    assertThat(freezePlanCalls.get()).isEqualTo(1);
    assertThat(secondFreezePlanIdempotencyKey.get()).isNull();
  }

  @Test
  void doesNotRetryFreezePlanInternalServerErrors() {
    firstFreezePlanStatus.set(500);
    JsonNode request =
        mapper
            .createObjectNode()
            .put("warehouseId", UUID.randomUUID().toString())
            .put("inventoryId", UUID.randomUUID().toString())
            .put("findingId", UUID.randomUUID().toString());

    assertThatThrownBy(() -> gateway.freezePlan(UUID.randomUUID(), request))
        .isInstanceOf(InventoryException.class)
        .hasMessage("Mandatory inventory dependency is unavailable");

    assertThat(freezePlanCalls.get()).isEqualTo(1);
    assertThat(secondFreezePlanIdempotencyKey.get()).isNull();
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

  private void warehouseOperation(HttpExchange exchange) throws IOException {
    UUID warehouseId = responseWarehouseId.get();
    String path = exchange.getRequestURI().getPath();
    if (path.endsWith("/admission")) {
      lifecycleAuthorization.set(
          exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
      assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("direction=INCOMING");
      respond(
          exchange,
          200,
          """
          {"warehouseId":"%s","warehouseVersion":7,"lifecycleState":"ACTIVE",
           "direction":"INCOMING","admitted":true}
          """.formatted(warehouseId));
      return;
    }
    if (path.endsWith("/operation-marks")) {
      operationAuthorization.set(
          exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
      requestBody.set(
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }
    if (path.endsWith("/lifecycle-readiness")) {
      readinessConfirmAuthorization.set(
          exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
      requestBody.set(
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(
          exchange,
          200,
          """
          {"warehouseId":"%s","warehouseVersion":11,"lifecycleState":"DRAINING",
           "readinessOwner":"INVENTORY","confirmedAt":"2026-09-02T09:00:00Z"}
          """.formatted(warehouseId));
      return;
    }
    if (path.endsWith("/time-zone")) {
      timeZoneAuthorization.set(
          exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
      assertThat(exchange.getRequestURI().getRawQuery()).contains("at=2026-09-02T08:15:30Z");
      respond(
          exchange,
          200,
          """
          {"warehouseId":"%s","timeZone":"Europe/Samara",
           "effectiveFrom":"2026-09-01T00:00:00Z"}
          """.formatted(warehouseId));
      return;
    }
    respond(exchange, 404, "{}");
  }

  private void warehouseReadinessWork(HttpExchange exchange) throws IOException {
    readinessWorkAuthorization.set(
        exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("limit=100");
    respond(
        exchange,
        200,
        """
        {"items":[{"warehouseId":"%s","warehouseVersion":11,
                    "lifecycleState":"DRAINING"}],"nextAfter":null}
        """.formatted(responseWarehouseId.get()));
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

  private void applyReconciliation(HttpExchange exchange) throws IOException {
    authorization.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    respond(exchange, 200, reconciliationResponse.get());
  }

  private void createLossDisposition(HttpExchange exchange) throws IOException {
    authorization.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
    requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    JsonNode request = mapper.readTree(requestBody.get());
    respond(
        exchange,
        201,
        """
        {"id":"%s","inventorySessionId":"%s","findingId":"%s", "warehouseId":"%s",
         "assetId":"%s","disposition":"LOSS","state":"PENDING_APPROVAL"}
        """
            .formatted(
                UUID.randomUUID(),
                request.path("inventorySessionId").asText(),
                request.path("findingId").asText(),
                request.path("warehouseId").asText(),
                request.path("equipmentId").asText()));
  }

  private void freezePlan(HttpExchange exchange) throws IOException {
    int call = freezePlanCalls.incrementAndGet();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
    if (call == 1) {
      firstFreezePlanIdempotencyKey.set(key);
      firstFreezePlanBody.set(body);
      Integer status = firstFreezePlanStatus.get();
      if (status != null) {
        respond(exchange, status, "{}");
        return;
      }
    } else {
      secondFreezePlanIdempotencyKey.set(key);
      secondFreezePlanBody.set(body);
    }
    JsonNode request = mapper.readTree(body);
    respond(
        exchange,
        200,
        """
        {"warehouseId":"%s","inventoryId":"%s","findingId":"%s","sourceRevision":1,
         "snapshot":{},"fingerprint":"%s"}
        """
            .formatted(
                request.path("warehouseId").asText(),
                request.path("inventoryId").asText(),
                request.path("findingId").asText(),
                "a".repeat(64)));
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
