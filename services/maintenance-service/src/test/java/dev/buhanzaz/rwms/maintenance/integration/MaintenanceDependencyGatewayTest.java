package dev.buhanzaz.rwms.maintenance.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MaintenanceDependencyGatewayTest {
  private OAuth2AuthorizedClientManager authorizedClients;
  private MockRestServiceServer server;
  private HttpMaintenanceDependencyGateway gateway;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    authorizedClients = mock(OAuth2AuthorizedClientManager.class);
    authorize("task-token", "task-board.task-sync");
    gateway = new HttpMaintenanceDependencyGateway(
        builder.build(),
        authorizedClients,
        new MaintenanceDependencyProperties.Validated(
            URI.create("http://auth.test/token"),
            "maintenance",
            "secret",
            URI.create("http://asset.test"),
            URI.create("http://task.test"),
            URI.create("http://media.test"),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)));
  }

  @Test
  void rentalItemSnapshotUsesTheExactPrivateGetAndAssetBearer() {
    UUID rentalItemId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    authorize("asset-token", "asset.maintenance");
    server.expect(requestTo(
        "http://asset.test/api/internal/asset/v1/maintenance/rental-items/"
            + rentalItemId + "/snapshot"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer asset-token"))
        .andExpect(headerDoesNotExist("Idempotency-Key"))
        .andRespond(withSuccess(
            """
            {"id":"%s","version":2,"warehouseId":"%s","status":"FREE"}
            """.formatted(rentalItemId, warehouseId),
            MediaType.APPLICATION_JSON));

    var snapshot = gateway.getRentalItemSnapshot(rentalItemId);

    assertThat(snapshot).isEqualTo(new MaintenanceDependencyGateway.AssetSnapshot(
        rentalItemId, 2, warehouseId, "FREE"));
    server.verify();
  }

  @Test
  void rentalItemSnapshotRejectsMismatchedTruth() {
    UUID rentalItemId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    authorize("asset-token", "asset.maintenance");
    server.expect(requestTo(
        "http://asset.test/api/internal/asset/v1/maintenance/rental-items/"
            + rentalItemId + "/snapshot"))
        .andRespond(withSuccess(
            """
            {"id":"%s","version":2,"warehouseId":"%s","status":"FREE"}
            """.formatted(UUID.randomUUID(), warehouseId),
            MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> gateway.getRentalItemSnapshot(rentalItemId))
        .isInstanceOfSatisfying(MaintenanceDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    server.verify();
  }

  @Test
  void rentalItemSnapshotRejectsIncompleteTruth() {
    UUID rentalItemId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    authorize("asset-token", "asset.maintenance");
    server.expect(requestTo(
        "http://asset.test/api/internal/asset/v1/maintenance/rental-items/"
            + rentalItemId + "/snapshot"))
        .andRespond(withSuccess(
            """
            {"id":"%s","warehouseId":"%s","status":"FREE"}
            """.formatted(rentalItemId, warehouseId),
            MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> gateway.getRentalItemSnapshot(rentalItemId))
        .isInstanceOfSatisfying(MaintenanceDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    server.verify();
  }

  @Test
  void furnitureEquipmentUsesExactAssetScopePathPayloadAndNoIdempotencyHeader() {
    UUID equipmentId = UUID.randomUUID();
    authorize("asset-token", "asset.maintenance");
    server.expect(requestTo(
        "http://asset.test/api/internal/asset/v1/maintenance/equipment-catalog/CHAIR"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer asset-token"))
        .andExpect(headerDoesNotExist("Idempotency-Key"))
        .andExpect(content().string(equalTo("{\"equipmentName\":\"Chair\"}")))
        .andRespond(withSuccess(
            """
            {"equipmentId":"%s","equipmentCode":"CHAIR","equipmentName":"Chair"}
            """.formatted(equipmentId),
            MediaType.APPLICATION_JSON));

    var response = gateway.ensureFurnitureEquipment("CHAIR", "Chair");

    assertThat(response).isEqualTo(
        new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
            equipmentId, "CHAIR", "Chair"));
    server.verify();
  }

  @Test
  void furnitureEquipmentRejectsMismatchedCanonicalTruth() {
    authorize("asset-token", "asset.maintenance");
    server.expect(requestTo(
        "http://asset.test/api/internal/asset/v1/maintenance/equipment-catalog/CHAIR"))
        .andRespond(withSuccess(
            """
            {"equipmentId":"%s","equipmentCode":"TABLE","equipmentName":"Chair"}
            """.formatted(UUID.randomUUID()),
            MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> gateway.ensureFurnitureEquipment("CHAIR", "Chair"))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception -> assertThat(exception.status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    server.verify();
  }

  @Test
  void furnitureEquipmentMapsAssetConflictToConflictWithoutLocalRetryAmbiguity() {
    authorize("asset-token", "asset.maintenance");
    server.expect(requestTo(
        "http://asset.test/api/internal/asset/v1/maintenance/equipment-catalog/CHAIR"))
        .andRespond(withStatus(HttpStatus.CONFLICT));

    assertThatThrownBy(() -> gateway.ensureFurnitureEquipment("CHAIR", "Chair"))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT));
    server.verify();
  }

  @Test
  void furnitureEquipmentRejectsBroaderAssetTokenBeforeHttp() {
    OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
    when(authorized.getAccessToken()).thenReturn(new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER,
        "broader-token",
        Instant.now(),
        Instant.now().plusSeconds(3600),
        Set.of("asset.maintenance", "media.maintenance")));
    when(authorizedClients.authorize(any())).thenReturn(authorized);

    assertThatThrownBy(() -> gateway.ensureFurnitureEquipment("CHAIR", "Chair"))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception -> assertThat(exception.status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    server.verify();
  }

  @Test
  void furnitureLossUsesTheExactFencedAssetRequest() {
    UUID key = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID leaseId = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    UUID chairId = UUID.fromString("52000000-0000-4000-8000-000000000001");
    UUID tableId = UUID.fromString("52000000-0000-4000-8000-000000000002");
    authorize("asset-token", "asset.maintenance");
    server.expect(requestTo(
        "http://asset.test/api/internal/asset/v1/maintenance/rental-items/"
            + rentalItemId + "/fenced-status"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer asset-token"))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andExpect(content().string(equalTo("""
            {"expectedVersion":7,"action":"QUEUE_FOR_REPAIR","leaseId":"%s","fencingToken":17,"ownerType":"MAINTENANCE_ESTIMATE","ownerId":"%s","linkedReturnEstimateId":null,"estimateId":"%s","furnitureLosses":[{"equipmentId":"%s","equipmentCode":"CHAIR","quantity":2},{"equipmentId":"%s","equipmentCode":"TABLE","quantity":4}]}
            """.formatted(leaseId, estimateId, estimateId, chairId, tableId).strip())))
        .andRespond(withSuccess(
            """
            {"id":"%s","version":8,"warehouseId":"%s","number":"C-1","status":"REPAIR"}
            """.formatted(rentalItemId, warehouseId),
            MediaType.APPLICATION_JSON));

    var snapshot = gateway.fencedStatus(
        key,
        rentalItemId,
        warehouseId,
        7,
        leaseId,
        17,
        "MAINTENANCE_ESTIMATE",
        estimateId.toString(),
        "QUEUE_TO_REPAIR",
        false,
        estimateId,
        List.of(
            new MaintenanceDependencyGateway.FurnitureLoss(chairId, "CHAIR", 2),
            new MaintenanceDependencyGateway.FurnitureLoss(tableId, "TABLE", 4)));

    assertThat(snapshot).isEqualTo(new MaintenanceDependencyGateway.AssetSnapshot(
        rentalItemId, 8, warehouseId, "REPAIR"));
    server.verify();
  }

  @Test
  void registerSendsExplicitNullWhenNoStageHasDeadline() {
    UUID externalTaskId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    server.expect(requestTo("http://task.test/api/internal/task-board/v1/tasks"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(containsString("\"deadlineAt\":null")))
        .andRespond(withSuccess(
            taskResponse(externalTaskId, warehouseId, null, List.of(queueId)),
            MediaType.APPLICATION_JSON));

    var response = gateway.registerTask(
        UUID.randomUUID(), externalTaskId, warehouseId, rentalItemId,
        List.of(taskStage(0, queueId, null)));

    assertThat(response.state()).isEqualTo("ACTIVE");
    server.verify();
  }

  @Test
  void preStartUpdateSendsTheOneIdenticalExplicitDeadline() {
    UUID externalTaskId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID firstQueue = UUID.randomUUID();
    UUID secondQueue = UUID.randomUUID();
    OffsetDateTime deadline = OffsetDateTime.parse("2026-07-18T10:15:30+03:00");
    server.expect(requestTo(
        "http://task.test/api/internal/task-board/v1/tasks/" + externalTaskId))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(content().string(containsString(
            "\"deadlineAt\":\"2026-07-18T10:15:30+03:00\"")))
        .andRespond(withSuccess(
            taskResponse(
                externalTaskId, warehouseId, deadline, List.of(firstQueue, secondQueue)),
            MediaType.APPLICATION_JSON));

    var response = gateway.updatePreStartTask(
        UUID.randomUUID(), externalTaskId, 0,
        List.of(
            taskStage(0, firstQueue, deadline),
            taskStage(1, secondQueue, deadline)));

    assertThat(response.version()).isOne();
    server.verify();
  }

  @Test
  void conflictingStageDeadlinesAreRejectedBeforeAnyHttpRequest() {
    OffsetDateTime first = OffsetDateTime.parse("2026-07-18T10:00:00+03:00");
    OffsetDateTime second = first.plusMinutes(1);

    assertThatThrownBy(() -> gateway.registerTask(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        List.of(
            taskStage(0, UUID.randomUUID(), first),
            taskStage(1, UUID.randomUUID(), second))))
        .isInstanceOfSatisfying(MaintenanceDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    server.verify();
  }

  @Test
  void routingPreflightUsesTheExactPrivateEndpointAndTaskSyncScope() {
    UUID warehouseId = UUID.randomUUID();
    UUID repairQueue = UUID.randomUUID();
    UUID holdingQueue = UUID.randomUUID();
    server.expect(requestTo(
        "http://task.test/api/internal/task-board/v1/maintenance/routing-preflight"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer task-token"))
        .andExpect(headerDoesNotExist("Idempotency-Key"))
        .andExpect(content().string(equalTo("""
            {"warehouseId":"%s","queues":[{"queueId":"%s","code":"REPAIR","type":"REPAIR"},{"queueId":"%s","code":"SANITARY","type":"HOLDING"}]}
            """.formatted(warehouseId, repairQueue, holdingQueue).strip())))
        .andRespond(withSuccess("""
            {"warehouseId":"%s","ready":false,"missingQueueIds":["%s"],"mismatches":[{"queueId":"%s","fields":["HIDDEN"]}]}
            """.formatted(warehouseId, repairQueue, holdingQueue), MediaType.APPLICATION_JSON));

    var response = gateway.preflightMaintenanceRouting(
        warehouseId,
        List.of(
            new MaintenanceDependencyGateway.RoutingQueueRequirement(
                repairQueue, "REPAIR", "REPAIR"),
            new MaintenanceDependencyGateway.RoutingQueueRequirement(
                holdingQueue, "SANITARY", "HOLDING")));

    assertThat(response.ready()).isFalse();
    assertThat(response.missingQueueIds()).containsExactly(repairQueue);
    assertThat(response.mismatches()).singleElement().satisfies(
        mismatch -> {
          assertThat(mismatch.queueId()).isEqualTo(holdingQueue);
          assertThat(mismatch.fields()).containsExactly("HIDDEN");
        });
    server.verify();
  }

  @Test
  void routingPreflightRejectsBroaderServiceScopeBeforeHttp() {
    OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
    when(authorized.getAccessToken()).thenReturn(new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER,
        "broader-task-token",
        Instant.now(),
        Instant.now().plusSeconds(3600),
        Set.of("task-board.task-sync", "queue-registry.write")));
    when(authorizedClients.authorize(any())).thenReturn(authorized);

    assertThatThrownBy(() -> gateway.preflightMaintenanceRouting(
            UUID.randomUUID(),
            List.of(new MaintenanceDependencyGateway.RoutingQueueRequirement(
                UUID.randomUUID(), "REPAIR", "REPAIR"))))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception -> assertThat(exception.status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    server.verify();
  }

  @Test
  void routingPreflightRejectsInconsistentDependencyTruth() {
    UUID warehouseId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    server.expect(requestTo(
            "http://task.test/api/internal/task-board/v1/maintenance/routing-preflight"))
        .andRespond(withSuccess("""
            {"warehouseId":"%s","ready":true,"missingQueueIds":["%s"],"mismatches":[]}
            """.formatted(warehouseId, queueId), MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> gateway.preflightMaintenanceRouting(
            warehouseId,
            List.of(new MaintenanceDependencyGateway.RoutingQueueRequirement(
                queueId, "REPAIR", "REPAIR"))))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception -> assertThat(exception.status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    server.verify();
  }

  @Test
  void catalogPositionRegistrationAndDeletionUseRegistryScopeAndCasTruth() {
    UUID queueId = UUID.randomUUID();
    UUID referenceId = UUID.randomUUID();
    String externalReferenceId = "catalog:" + UUID.randomUUID() + ":" + UUID.randomUUID();
    authorize("registry-token", "queue-registry.write");
    server.expect(requestTo(
        "http://task.test/api/internal/work-queues/" + queueId + "/references"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer registry-token"))
        .andExpect(headerDoesNotExist("Idempotency-Key"))
        .andExpect(content().string(equalTo(
            "{\"type\":\"CATALOG_POSITION\",\"externalReferenceId\":\""
                + externalReferenceId + "\"}")))
        .andRespond(withSuccess("""
            {"id":"%s","version":3,"queueId":"%s","type":"CATALOG_POSITION","externalReferenceId":"%s"}
            """.formatted(referenceId, queueId, externalReferenceId), MediaType.APPLICATION_JSON));
    server.expect(requestTo(
        "http://task.test/api/internal/work-queues/references/CATALOG_POSITION/"
            + externalReferenceId + "?expectedVersion=3"))
        .andExpect(method(HttpMethod.DELETE))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer registry-token"))
        .andExpect(headerDoesNotExist("Idempotency-Key"))
        .andRespond(withNoContent());

    var registered = gateway.registerCatalogPosition(queueId, externalReferenceId);
    gateway.deleteCatalogPosition(externalReferenceId, registered.version());

    assertThat(registered.id()).isEqualTo(referenceId);
    assertThat(registered.version()).isEqualTo(3);
    server.verify();
  }

  @Test
  void mediaOwnerProofUsesOnlyMaintenanceScopeAndFrozenUuidPayload() {
    UUID ownerId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID proofEventId = UUID.randomUUID();
    authorize("media-token", "media.maintenance");
    server.expect(requestTo("http://media.test/api/internal/media/v1/owner-proofs"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer media-token"))
        .andExpect(headerDoesNotExist("Idempotency-Key"))
        .andExpect(content().string(equalTo("""
            {"ownerType":"MAINTENANCE_ACCEPTANCE","ownerId":"%s","warehouseId":"%s","ownerRevision":2,"aggregateVersion":7,"proofEventId":"%s","active":true}
            """.formatted(ownerId, warehouseId, proofEventId).strip())))
        .andRespond(withSuccess("""
            {"ownerType":"MAINTENANCE_ACCEPTANCE","ownerId":"%s","warehouseId":"%s","ownerRevision":2,"aggregateVersion":7,"proofEventId":"%s","active":true}
            """.formatted(ownerId, warehouseId, proofEventId), MediaType.APPLICATION_JSON));
    var proof = new MaintenanceDependencyGateway.MediaOwnerProof(
        "MAINTENANCE_ACCEPTANCE", ownerId, warehouseId, 2, 7, proofEventId, true);

    assertThat(gateway.upsertMediaOwnerProof(proof)).isEqualTo(proof);
    server.verify();
  }

  @Test
  void mediaOwnerProofRejectsABroaderTokenBeforeHttp() {
    OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
    when(authorized.getAccessToken()).thenReturn(new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER,
        "broader-token",
        Instant.now(),
        Instant.now().plusSeconds(3600),
        Set.of("media.maintenance", "asset.maintenance")));
    when(authorizedClients.authorize(any())).thenReturn(authorized);
    var proof = new MaintenanceDependencyGateway.MediaOwnerProof(
        "MAINTENANCE_REPAIR",
        UUID.randomUUID(),
        UUID.randomUUID(),
        0,
        0,
        UUID.randomUUID(),
        true);

    assertThatThrownBy(() -> gateway.upsertMediaOwnerProof(proof))
        .isInstanceOfSatisfying(MaintenanceDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    server.verify();
  }

  @Test
  void noOpFixtureReturnsTheCanonicalActiveTaskState() {
    UUID externalTaskId = UUID.randomUUID();
    var response = new NoOpMaintenanceDependencyGateway().registerTask(
        UUID.randomUUID(), externalTaskId, UUID.randomUUID(), UUID.randomUUID(),
        List.of(taskStage(0, UUID.randomUUID(), null)));

    assertThat(response.externalTaskId()).isEqualTo(externalTaskId);
    assertThat(response.state()).isEqualTo("ACTIVE");
    assertThat(response.stages()).singleElement()
        .extracting(MaintenanceDependencyGateway.TaskStageSnapshot::routeIndex)
        .isEqualTo(0);
  }

  @Test
  void noOpFixtureCannotFabricateCanonicalAssetTruth() {
    assertThatThrownBy(() ->
        new NoOpMaintenanceDependencyGateway().getRentalItemSnapshot(UUID.randomUUID()))
        .isInstanceOfSatisfying(MaintenanceDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  @Test
  void noOpFixtureProvidesDeterministicFurnitureEquipmentForDevelopment() {
    var first = new NoOpMaintenanceDependencyGateway()
        .ensureFurnitureEquipment("chair", " Chair ");
    var second = new NoOpMaintenanceDependencyGateway()
        .ensureFurnitureEquipment("CHAIR", "Chair");

    assertThat(first).isEqualTo(second);
    assertThat(first.equipmentCode()).isEqualTo("CHAIR");
    assertThat(first.equipmentName()).isEqualTo("Chair");
  }

  @Test
  void noOpFixtureCannotDropFurnitureLosses() {
    assertThatThrownBy(() -> new NoOpMaintenanceDependencyGateway().fencedStatus(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        0,
        UUID.randomUUID(),
        1,
        "MAINTENANCE_ESTIMATE",
        UUID.randomUUID().toString(),
        "QUEUE_TO_REPAIR",
        false,
        UUID.randomUUID(),
        List.of(new MaintenanceDependencyGateway.FurnitureLoss(
            UUID.randomUUID(), "CHAIR", 1))))
        .isInstanceOfSatisfying(MaintenanceDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  private void authorize(String token, String scope) {
    OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
    when(authorized.getAccessToken()).thenReturn(new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER,
        token,
        Instant.now(),
        Instant.now().plusSeconds(3600),
        Set.of(scope)));
    when(authorizedClients.authorize(any())).thenReturn(authorized);
  }

  private static MaintenanceDependencyGateway.TaskStage taskStage(
      int order, UUID queueId, OffsetDateTime deadline) {
    return new MaintenanceDependencyGateway.TaskStage(
        UUID.randomUUID(), order, RepairStageKind.REPAIR_WORK,
        "Repair stage " + order, queueId.toString(), deadline);
  }

  private static String taskResponse(
      UUID externalTaskId,
      UUID warehouseId,
      OffsetDateTime deadline,
      List<UUID> queues) {
    StringBuilder route = new StringBuilder();
    for (int index = 0; index < queues.size(); index++) {
      if (index > 0) route.append(',');
      route.append("""
          {"entryId":"%s","entryVersion":0,"queueId":"%s",
           "queueCode":"Q%s","routeIndex":%s}
          """.formatted(UUID.randomUUID(), queues.get(index), index, index));
    }
    String deadlineJson = deadline == null ? "null" : "\"" + deadline + "\"";
    return """
        {"taskId":"%s","taskVersion":1,"warehouseId":"%s",
         "externalTaskId":"%s","title":"Maintenance repair","unitNumber":null,
         "description":null,"status":"ACTIVE","plannedDurationMinutes":null,
         "deadlineAt":%s,"doneAt":null,"route":[%s]}
        """.formatted(
        UUID.randomUUID(), warehouseId, externalTaskId, deadlineJson, route);
  }
}
