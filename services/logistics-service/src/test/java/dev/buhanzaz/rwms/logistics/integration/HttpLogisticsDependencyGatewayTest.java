package dev.buhanzaz.rwms.logistics.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpLogisticsDependencyGatewayTest {
  private OAuth2AuthorizedClientManager authorizedClients;
  private MockRestServiceServer server;
  private HttpLogisticsDependencyGateway gateway;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    authorizedClients = mock(OAuth2AuthorizedClientManager.class);
    when(authorizedClients.authorize(any())).thenAnswer(invocation -> {
      OAuth2AuthorizeRequest request = invocation.getArgument(0);
      String scope =
          switch (request.getClientRegistrationId()) {
            case "logistics-asset" -> "asset.logistics";
            case "logistics-warehouse" -> "warehouse.logistics";
            case "logistics-maintenance" -> "maintenance.logistics";
            case "logistics-media" -> "media.logistics";
            case "logistics-task-board" -> "task-board.logistics";
            default -> throw new IllegalArgumentException("unexpected registration");
          };
      OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
      when(authorized.getAccessToken())
          .thenReturn(
              new OAuth2AccessToken(
                  OAuth2AccessToken.TokenType.BEARER,
                  "test-" + scope,
                  Instant.now(),
                  Instant.now().plusSeconds(300),
                  Set.of(scope)));
      return authorized;
    });
    gateway =
        new HttpLogisticsDependencyGateway(
            builder.build(),
            authorizedClients,
            new LogisticsDependencyProperties.Validated(
                URI.create("http://auth.test/oauth2/token"),
                "logistics-service",
                "secret",
                URI.create("http://asset.test"),
                URI.create("http://warehouse.test"),
                URI.create("http://task-board.test"),
                URI.create("http://maintenance.test"),
                URI.create("http://media.test"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)));
  }

  @Test
  void usesTheExactAssetScopeAndTypedReturnLeasePayload() {
    UUID key = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    server
        .expect(requestTo("http://asset.test/api/internal/asset/v1/logistics/operation-leases"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andExpect(jsonPath("$.rentalItemId").value(assetId.toString()))
        .andExpect(jsonPath("$.ownerType").value("LOGISTICS_RETURN"))
        .andExpect(jsonPath("$.documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.lineId").value(lineId.toString()))
        .andExpect(jsonPath("$.expectedRentalItemVersion").value(7))
        .andRespond(
            withSuccess(
                """
                {
                  "leaseId":"00000000-0000-0000-0000-000000000501",
                  "version":3,
                  "rentalItemId":"%s",
                  "fencingToken":11,
                  "state":"ACTIVE",
                  "expiresAt":"2026-07-17T12:00:00Z"
                }
                """
                    .formatted(assetId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.OperationLease response =
        gateway.acquireReturnLease(key, assetId, 7, documentId, lineId);

    assertThat(response.rentalItemId()).isEqualTo(assetId);
    assertThat(response.fencingToken()).isEqualTo(11);
    server.verify();
  }

  @Test
  void usesTheExactWarehouseScopeForTheNarrowIdentityRoute() {
    UUID warehouseId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://warehouse.test/api/internal/warehouse/v1/warehouses/logistics/"
                    + warehouseId
                    + "/identity"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-warehouse.logistics"))
        .andRespond(
            withSuccess(
                """
                {
                  "id":"%s",
                  "version":4,
                  "active":true,
                  "timeZone":"Europe/Moscow"
                }
                """
                    .formatted(warehouseId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.WarehouseIdentity identity = gateway.readWarehouseIdentity(warehouseId);

    assertThat(identity).isEqualTo(
        new LogisticsDependencyGateway.WarehouseIdentity(
            warehouseId, 4, true, "Europe/Moscow"));
    server.verify();
  }

  @Test
  void rejectsACombinedOrBroaderClientTokenBeforeSendingARequest() {
    OAuth2AuthorizedClient combined = mock(OAuth2AuthorizedClient.class);
    when(combined.getAccessToken())
        .thenReturn(
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "combined",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Set.of("asset.logistics", "warehouse.logistics")));
    doReturn(combined).when(authorizedClients).authorize(any());

    assertThatThrownBy(() -> gateway.readRentalItemSnapshot(UUID.randomUUID()))
        .isInstanceOf(LogisticsDependencyException.class)
        .extracting(exception -> ((LogisticsDependencyException) exception).kind())
        .isEqualTo(LogisticsDependencyException.FailureKind.CONFIGURATION);
    server.verify();
  }

  @Test
  void reservesAnOrderUnitWithTheFrozenNestedAssetShapeAndIdempotencyHeader() {
    UUID key = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/orders/"
                    + orderId
                    + "/units"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andExpect(jsonPath("$.warehouseId").value(warehouseId.toString()))
        .andExpect(jsonPath("$.rentalItemId").value(unitId.toString()))
        .andExpect(jsonPath("$.actorSubjectId").value(actorId.toString()))
        .andExpect(jsonPath("$.actorRole").value("RENTAL_MANAGER"))
        .andRespond(
            withSuccess(
                """
                {
                  "reservationId":"%s",
                  "reservationVersion":0,
                  "orderId":"%s",
                  "rentalItemId":"%s",
                  "warehouseId":"%s",
                  "state":"ACTIVE",
                  "addedBySubjectId":"%s",
                  "addedByRole":"RENTAL_MANAGER",
                  "createdAt":"2026-07-19T12:00:00Z",
                  "releasedAt":null,
                  "replayed":false,
                  "unit":{
                    "id":"%s",
                    "version":3,
                    "warehouseId":"%s",
                    "number":"CAB-17",
                    "status":"FREE",
                    "rentalType":"STANDARD",
                    "dimensions":"6x2.4",
                    "finishing":"BASIC",
                    "category":"OFFICE",
                    "characteristics":"Утеплённая",
                    "linoleum":true,
                    "tags":[],
                    "contents":[{
                      "equipmentId":"%s",
                      "equipmentCode":"CHAIR",
                      "equipmentName":"Стул",
                      "quantity":2,
                      "locationKind":"CABIN_NON_RENTED"
                    }],
                    "createdAt":"2026-07-01T12:00:00Z",
                    "updatedAt":"2026-07-19T12:00:00Z"
                  }
                }
                """
                    .formatted(
                        reservationId,
                        orderId,
                        unitId,
                        warehouseId,
                        actorId,
                        unitId,
                        warehouseId,
                        equipmentId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.OrderUnitReservation response =
        gateway.reserveOrderUnit(
            key,
            orderId,
            warehouseId,
            unitId,
            actorId,
            "RENTAL_MANAGER");

    assertThat(response.reservationId()).isEqualTo(reservationId);
    assertThat(response.unitId()).isEqualTo(unitId);
    assertThat(response.unit().characteristics()).isEqualTo("Утеплённая");
    assertThat(response.unit().linoleum()).isTrue();
    assertThat(response.unit().contents())
        .singleElement()
        .satisfies(
            content -> {
              assertThat(content.equipmentId()).isEqualTo(equipmentId);
              assertThat(content.quantity()).isEqualTo(2);
            });
    server.verify();
  }

  @Test
  void propagatesTheSafeUnitReservationConflictCode() {
    UUID key = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/orders/"
                    + orderId
                    + "/units"))
        .andRespond(
            withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(
                    """
                    {"status":409,"code":"UNIT_ALREADY_RESERVED"}
                    """));

    assertThatThrownBy(
            () ->
                gateway.reserveOrderUnit(
                    key,
                    orderId,
                    warehouseId,
                    unitId,
                    actorId,
                    "RENTAL_MANAGER"))
        .isInstanceOf(LogisticsDependencyException.class)
        .satisfies(
            exception -> {
              LogisticsDependencyException dependency =
                  (LogisticsDependencyException) exception;
              assertThat(dependency.kind())
                  .isEqualTo(
                      LogisticsDependencyException.FailureKind.PERMANENT_REJECTION);
              assertThat(dependency.dependencyCode())
                  .isEqualTo("UNIT_ALREADY_RESERVED");
            });
    server.verify();
  }

  @Test
  void upsertsStructuredTransferOwnerProofWithTheExactMediaScope() {
    UUID documentId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID destinationWarehouseId = UUID.randomUUID();
    UUID proofEventId = UUID.randomUUID();
    server
        .expect(requestTo("http://media.test/api/internal/media/v1/owner-proofs"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-media.logistics"))
        .andExpect(jsonPath("$.ownerType").value("LOGISTICS_TRANSFER"))
        .andExpect(jsonPath("$.ownerId").doesNotExist())
        .andExpect(jsonPath("$.documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.lineId").value(lineId.toString()))
        .andExpect(jsonPath("$.warehouseId").value(destinationWarehouseId.toString()))
        .andExpect(jsonPath("$.ownerRevision").value(0))
        .andExpect(jsonPath("$.aggregateVersion").value(0))
        .andExpect(jsonPath("$.proofEventId").value(proofEventId.toString()))
        .andExpect(jsonPath("$.active").value(true))
        .andRespond(
            withSuccess(
                """
                {
                  "ownerType":"LOGISTICS_TRANSFER",
                  "documentId":"%s",
                  "lineId":"%s",
                  "warehouseId":"%s",
                  "ownerRevision":0,
                  "aggregateVersion":0,
                  "proofEventId":"%s",
                  "active":true
                }
                """
                    .formatted(documentId, lineId, destinationWarehouseId, proofEventId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.MediaOwnerProof proof =
        gateway.upsertMediaOwnerProof(
            LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER,
            documentId,
            lineId,
            destinationWarehouseId,
            0,
            0,
            proofEventId,
            true);

    assertThat(proof.documentId()).isEqualTo(documentId);
    assertThat(proof.lineId()).isEqualTo(lineId);
    assertThat(proof.warehouseId()).isEqualTo(destinationWarehouseId);
    assertThat(proof.proofEventId()).isEqualTo(proofEventId);
    server.verify();
  }

  @Test
  void refusesABroaderTokenBeforeSendingAnOwnerProof() {
    OAuth2AuthorizedClient combined = mock(OAuth2AuthorizedClient.class);
    when(combined.getAccessToken())
        .thenReturn(
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "combined",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Set.of("media.logistics", "asset.logistics")));
    doReturn(combined).when(authorizedClients).authorize(any());

    assertThatThrownBy(
            () ->
                gateway.upsertMediaOwnerProof(
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    0,
                    0,
                    UUID.randomUUID(),
                    true))
        .isInstanceOf(LogisticsDependencyException.class)
        .extracting(exception -> ((LogisticsDependencyException) exception).kind())
        .isEqualTo(LogisticsDependencyException.FailureKind.CONFIGURATION);
    server.verify();
  }

  @Test
  void usesFrozenEquipmentMovementReservationAndWorkerTaskContracts() {
    UUID movementId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID sourceBalanceId = UUID.randomUUID();
    OffsetDateTime deadline = OffsetDateTime.of(2026, 7, 20, 15, 0, 0, 0, ZoneOffset.UTC);

    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/equipment-movement-reservations"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andExpect(header("Idempotency-Key", lineId.toString()))
        .andExpect(jsonPath("$.movementId").value(movementId.toString()))
        .andExpect(jsonPath("$.lineId").value(lineId.toString()))
        .andExpect(jsonPath("$.equipmentId").value(equipmentId.toString()))
        .andExpect(jsonPath("$.sourceWarehouseId").value(warehouseId.toString()))
        .andExpect(jsonPath("$.sourceLocationKind").value("STOCK"))
        .andExpect(jsonPath("$.expectedSourceBalanceVersion").value(4))
        .andExpect(jsonPath("$.quantity").value(2))
        .andExpect(jsonPath("$.reservedUntil").value("2026-07-20T15:00:00Z"))
        .andRespond(
            withSuccess(
                """
                {"reservationId":"%s","version":0,"ownerType":"LOGISTICS_EQUIPMENT_MOVEMENT",
                 "movementId":"%s","lineId":"%s","equipmentId":"%s","equipmentCode":"TABLE",
                 "equipmentName":"Стол","sourceBalanceId":"%s","sourceWarehouseId":"%s",
                 "sourceRentalItemId":null,"sourceLocationKind":"STOCK","quantity":2,"state":"ACTIVE",
                 "reservedUntil":"%s","executedAt":null}
                """
                    .formatted(
                        reservationId,
                        movementId,
                        lineId,
                        equipmentId,
                        sourceBalanceId,
                        warehouseId,
                        deadline),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.EquipmentMovementReservation reservation =
        gateway.acquireEquipmentMovementReservation(
            lineId,
            movementId,
            lineId,
            equipmentId,
            warehouseId,
            null,
            "STOCK",
            4,
            2,
            deadline);
    assertThat(reservation.equipmentCode()).isEqualTo("TABLE");
    server.verify();
  }

  @Test
  void registersFrozenEquipmentMovementWorkerTask() {
    UUID movementId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID boardTaskId = UUID.randomUUID();
    OffsetDateTime deadline = OffsetDateTime.of(2026, 7, 20, 15, 0, 0, 0, ZoneOffset.UTC);
    server
        .expect(
            requestTo(
                "http://task-board.test/api/internal/task-board/v1/logistics/equipment-movement-tasks"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-task-board.logistics"))
        .andExpect(jsonPath("$.warehouseId").value(warehouseId.toString()))
        .andExpect(jsonPath("$.externalTaskId").value(movementId.toString()))
        .andExpect(jsonPath("$.unitNumber").value("CAB-17"))
        .andExpect(jsonPath("$.deadlineAt").value("2026-07-20T15:00:00Z"))
        .andExpect(jsonPath("$.operations[0].direction").value("BRING_TO_CABIN"))
        .andExpect(jsonPath("$.operations[0].equipmentCode").value("TABLE"))
        .andRespond(
            withSuccess(
                """
                {"taskId":"%s","taskVersion":0,"warehouseId":"%s",
                 "externalTaskId":"%s","status":"ACTIVE","doneAt":null}
                """.formatted(boardTaskId, warehouseId, movementId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.EquipmentMovementBoardTask boardTask =
        gateway.registerEquipmentMovementTask(
            warehouseId,
            movementId,
            "CAB-17",
            10,
            deadline,
            java.util.List.of(
                new LogisticsDependencyGateway.EquipmentMovementOperation(
                    "BRING_TO_CABIN", "TABLE", "Стол", 2)));
    assertThat(boardTask.taskId()).isEqualTo(boardTaskId);
    server.verify();
  }

  @Test
  void executesFrozenEquipmentMovementReservationBatch() {
    UUID movementId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID sourceBalanceId = UUID.randomUUID();
    UUID targetBalanceId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/equipment-movement-reservations/execute"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", movementId.toString()))
        .andExpect(jsonPath("$.movementId").value(movementId.toString()))
        .andExpect(jsonPath("$.lines[0].reservationId").value(reservationId.toString()))
        .andExpect(jsonPath("$.lines[0].targetWarehouseId").value(warehouseId.toString()))
        .andExpect(jsonPath("$.lines[0].targetRentalItemId").value(cabinId.toString()))
        .andExpect(jsonPath("$.lines[0].targetLocationKind").value("CABIN_NON_RENTED"))
        .andRespond(
            withSuccess(
                """
                {"movementId":"%s","lines":[{"reservationId":"%s","reservationVersion":1,
                "lineId":"%s","movement":{"id":"%s","version":0,"equipmentId":"%s",
                "sourceBalanceId":"%s","targetBalanceId":"%s","quantity":2,"kind":"STOCK_TO_CABIN",
                "occurredAt":"2026-07-20T14:55:00Z"}}]}
                """
                    .formatted(
                        movementId,
                        reservationId,
                        lineId,
                        eventId,
                        equipmentId,
                        sourceBalanceId,
                        targetBalanceId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.EquipmentMovementExecution execution =
        gateway.executeEquipmentMovement(
            movementId,
            movementId,
            java.util.List.of(
                new LogisticsDependencyGateway.EquipmentMovementExecutionRequestLine(
                    reservationId,
                    0,
                    lineId,
                    warehouseId,
                    cabinId,
                    "CABIN_NON_RENTED")));
    assertThat(execution.lines())
        .singleElement()
        .satisfies(line -> assertThat(line.reservationVersion()).isOne());
    server.verify();
  }
}
