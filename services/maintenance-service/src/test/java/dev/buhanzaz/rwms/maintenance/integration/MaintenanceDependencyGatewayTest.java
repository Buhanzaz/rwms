package dev.buhanzaz.rwms.maintenance.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
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
import dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
            URI.create("http://logistics.test"),
            URI.create("http://warehouse.test"),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)));
  }

  @Test
  void driverTaskUsesExactMaintenanceLogisticsBoundaryAndScope() {
    UUID key = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    LocalDate scheduledDate = LocalDate.of(2026, 8, 3);
    authorize("logistics-token", "logistics.maintenance");
    server
        .expect(
            requestTo(
                "http://logistics.test/api/internal/logistics/v1/maintenance/driver-tasks"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            header(
                HttpHeaders.AUTHORIZATION,
                "Bearer logistics-token"))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andExpect(
            content()
                .string(
                    equalTo(
                        """
                        {"warehouseId":"%s","cabinId":"%s","repairId":"%s","sourceType":"INVENTORY","sourceId":"%s","kind":"DELIVER_TO_REPAIR","planningMode":"FIXED_DATE","scheduledDate":"%s","priority":2,"activateNow":false}
                        """
                            .formatted(
                                warehouseId,
                                cabinId,
                                repairId,
                                findingId,
                                scheduledDate)
                            .strip())))
        .andRespond(
            withSuccess(
                """
                {"id":"%s","version":4,"warehouseId":"%s","cabinId":"%s","repairId":"%s","sourceType":"INVENTORY","sourceId":"%s","kind":"DELIVER_TO_REPAIR","planningMode":"FIXED_DATE","scheduledDate":"%s","priority":2,"state":"SCHEDULED"}
                """
                    .formatted(
                        taskId,
                        warehouseId,
                        cabinId,
                        repairId,
                        findingId,
                        scheduledDate),
                MediaType.APPLICATION_JSON));

    var response =
        gateway.createDriverTask(
            key,
            new MaintenanceDependencyGateway.DriverTaskCommand(
                warehouseId,
                cabinId,
                repairId,
                "INVENTORY",
                findingId,
                "DELIVER_TO_REPAIR",
                RepairLogisticsPlanningMode.FIXED_DATE,
                scheduledDate,
                2,
                false));

    assertThat(response.id()).isEqualTo(taskId);
    assertThat(response.scheduledDate()).isEqualTo(scheduledDate);
    server.verify();
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
            {"id":"%s","version":2,"warehouseId":"%s","number":"БТ-42","status":"FREE"}
            """.formatted(rentalItemId, warehouseId),
            MediaType.APPLICATION_JSON));

    var snapshot = gateway.getRentalItemSnapshot(rentalItemId);

    assertThat(snapshot).isEqualTo(new MaintenanceDependencyGateway.AssetSnapshot(
        rentalItemId, 2, warehouseId, "БТ-42", "FREE"));
    server.verify();
  }

  @Test
  void warehouseAdmissionAndReadinessUseSeparateExactLifecycleRegistrations() {
    UUID warehouseId = UUID.randomUUID();
    authorize("warehouse-read-token", "warehouse.lifecycle.read");
    server.expect(requestTo(
        "http://warehouse.test/api/internal/warehouse/v1/warehouses/"
            + warehouseId
            + "/admission?direction=INCOMING"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer warehouse-read-token"))
        .andRespond(withSuccess(
            """
            {"warehouseId":"%s","warehouseVersion":4,"lifecycleState":"ACTIVE","direction":"INCOMING","admitted":true}
            """.formatted(warehouseId),
            MediaType.APPLICATION_JSON));
    server.expect(requestTo(
        "http://warehouse.test/api/internal/warehouse/v1/lifecycle/readiness-work?limit=100"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer warehouse-read-token"))
        .andRespond(withSuccess(
            """
            {"items":[{"warehouseId":"%s","warehouseVersion":5,"lifecycleState":"DRAINING"}],"nextAfter":null}
            """.formatted(warehouseId),
            MediaType.APPLICATION_JSON));

    var admission = gateway.warehouseAdmission(
        warehouseId, MaintenanceDependencyGateway.WarehouseOperationDirection.INCOMING);
    var work = gateway.warehouseLifecycleReadinessWork(null, 100);

    assertThat(admission.admitted()).isTrue();
    assertThat(work.items()).singleElement()
        .extracting(MaintenanceDependencyGateway.WarehouseLifecycleReadinessWork::warehouseId)
        .isEqualTo(warehouseId);
    server.verify();
  }

  @Test
  void warehouseReadinessConfirmationUsesExactConfirmScopeAndVersionFence() {
    UUID warehouseId = UUID.randomUUID();
    authorize("warehouse-confirm-token", "warehouse.lifecycle.confirm");
    server.expect(requestTo(
        "http://warehouse.test/api/internal/warehouse/v1/warehouses/"
            + warehouseId
            + "/lifecycle-readiness"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer warehouse-confirm-token"))
        .andExpect(content().string(equalTo("{\"expectedVersion\":7}")))
        .andRespond(withSuccess(
            """
            {"warehouseId":"%s","warehouseVersion":7,"lifecycleState":"DRAINING","readinessOwner":"MAINTENANCE","confirmedAt":"2026-08-05T09:00:00Z"}
            """.formatted(warehouseId),
            MediaType.APPLICATION_JSON));

    var confirmation = gateway.confirmWarehouseLifecycleReadiness(warehouseId, 7);

    assertThat(confirmation.readinessOwner()).isEqualTo("MAINTENANCE");
    server.verify();
  }

  @Test
  void warehouseTimezoneUsesItsExactAsOfScope() {
    UUID warehouseId = UUID.randomUUID();
    OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-05T09:00:00Z");
    authorize("timezone-token", "warehouse.timezone.read");
    server.expect(requestTo(containsString(
        "http://warehouse.test/api/internal/warehouse/v1/warehouses/"
            + warehouseId
            + "/time-zone?at=")))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer timezone-token"))
        .andRespond(withSuccess(
            """
            {"warehouseId":"%s","timeZone":"Europe/Samara","effectiveFrom":"2026-08-01T00:00:00Z"}
            """.formatted(warehouseId),
            MediaType.APPLICATION_JSON));

    var timeZone = gateway.warehouseTimeZoneAt(warehouseId, occurredAt);

    assertThat(timeZone.timeZone()).isEqualTo("Europe/Samara");
    server.verify();
  }

  @Test
  void warehouseOperationMarkUsesItsSeparateExactScope() {
    UUID warehouseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-05T09:00:00Z");

    authorize("operation-token", "warehouse.operation.mark");
    server.expect(requestTo(
        "http://warehouse.test/api/internal/warehouse/v1/warehouses/"
            + warehouseId
            + "/operation-marks"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer operation-token"))
        .andExpect(content().string(equalTo(
            "{\"operationId\":\""
                + operationId
                + "\",\"occurredAt\":\"2026-08-05T09:00:00Z\"}")))
        .andRespond(withNoContent());

    gateway.markWarehouseOperation(warehouseId, operationId, occurredAt);
    server.verify();
  }

  @Test
  void propertyDispositionAssetCallsUseExactPrivateBoundariesAndPreserveFences() {
    UUID key = UUID.randomUUID();
    UUID decisionId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID effectId = UUID.randomUUID();
    Instant preparedAt = Instant.parse("2026-08-05T00:00:00Z");
    Instant appliedAt = Instant.parse("2026-08-05T00:01:00Z");
    authorize("asset-token", "asset.maintenance");
    server.expect(requestTo(
        "http://asset.test/api/internal/asset/v1/maintenance/property-assets/CABIN/"
            + cabinId
            + "/snapshot?warehouseId="
            + warehouseId))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer asset-token"))
        .andRespond(withSuccess(
            """
            {"assetKind":"CABIN","assetId":"%s","assetDisplayName":"БТ-42","warehouseId":"%s","version":7,"sourceBalanceVersion":null,"status":"REPAIR","quantity":null,"contents":[{"equipmentId":"%s","equipmentName":"Chair","equipmentFormat":"pcs","balanceVersion":3,"quantity":2}],"activeReservation":false,"activeHold":false,"activeLease":true,"dispositionAllowed":true}
            """.formatted(cabinId, warehouseId, equipmentId),
            MediaType.APPLICATION_JSON));
    server.expect(requestTo(
        "http://asset.test/api/internal/asset/v1/maintenance/property-dispositions/"
            + decisionId
            + "/prepare"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer asset-token"))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andExpect(content().string(equalTo(
            """
            {"warehouseId":"%s","assetKind":"CABIN","assetId":"%s","disposition":"WRITE_OFF","expectedAssetVersion":7,"expectedSourceBalanceVersion":null,"quantity":null,"maintenanceCustodyClaimId":null,"maintenanceCustodyVersion":null,"contentsMode":null,"contents":[],"authorizedMaintenanceLease":null}
            """.formatted(warehouseId, cabinId).strip())))
        .andRespond(withSuccess(
            """
            {"decisionId":"%s","state":"PREPARED","requestSha256":"%s","warehouseId":"%s","assetKind":"CABIN","assetId":"%s","disposition":"WRITE_OFF","maintenanceCustodyClaimId":null,"maintenanceCustodyVersion":null,"contents":[],"preparedAt":"%s","appliedAt":null}
            """.formatted(decisionId, "a".repeat(64), warehouseId, cabinId, preparedAt),
            MediaType.APPLICATION_JSON));
    server.expect(requestTo(
        "http://asset.test/api/internal/asset/v1/maintenance/property-dispositions/"
            + decisionId
            + "/apply"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer asset-token"))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andExpect(content().string(equalTo("{\"completedMovementTaskId\":null}")))
        .andRespond(withSuccess(
            """
            {"effectId":"%s","decisionId":"%s","assetKind":"CABIN","assetId":"%s","disposition":"WRITE_OFF","assetVersion":8,"equipmentMovements":[],"appliedAt":"%s"}
            """.formatted(effectId, decisionId, cabinId, appliedAt),
            MediaType.APPLICATION_JSON));

    var snapshot = gateway.getPropertyAssetSnapshot(
        MaintenanceDependencyGateway.PropertyAssetKind.CABIN, cabinId, warehouseId);
    var preparation = new MaintenanceDependencyGateway.PropertyDispositionPreparation(
        warehouseId,
        MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
        cabinId,
        MaintenanceDependencyGateway.PropertyDispositionKind.WRITE_OFF,
        7L,
        null,
        null,
        null,
        List.of(),
        null);
    var fence = gateway.preparePropertyDisposition(key, decisionId, preparation);
    var effect = gateway.applyPropertyDisposition(key, decisionId, null);

    assertThat(snapshot.contents()).singleElement()
        .extracting(MaintenanceDependencyGateway.PropertyAssetContentSnapshot::equipmentId)
        .isEqualTo(equipmentId);
    assertThat(fence.state()).isEqualTo("PREPARED");
    assertThat(effect).isEqualTo(new MaintenanceDependencyGateway.PropertyDispositionEffect(
        effectId,
        decisionId,
        MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
        cabinId,
        MaintenanceDependencyGateway.PropertyDispositionKind.WRITE_OFF,
        8L,
        appliedAt));
    server.verify();
  }

  @Test
  void maintenanceFurnitureMovementPreservesLogisticsOwnerProof() {
    UUID key = UUID.randomUUID();
    UUID decisionId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID externalTaskId = UUID.randomUUID();
    OffsetDateTime deadlineAt = OffsetDateTime.parse("2026-08-06T12:00:00Z");
    authorize("logistics-token", "logistics.maintenance");
    String task = """
        {"id":"%s","version":0,"warehouseId":"%s","ownerType":"MAINTENANCE_DISPOSITION","ownerId":"%s","externalTaskId":"%s","taskBoardTaskId":null,"taskBoardTaskVersion":null,"taskBoardDoneAt":null,"unitNumber":"БТ-42","plannedDurationMinutes":15,"deadlineAt":"%s","state":"SCHEDULED","terminalState":null,"failureCode":null,"lines":[],"createdAt":"2026-08-05T00:00:00Z","updatedAt":"2026-08-05T00:00:00Z"}
        """.formatted(taskId, warehouseId, decisionId, externalTaskId, deadlineAt);
    server.expect(requestTo(
        "http://logistics.test/api/internal/logistics/v1/maintenance/equipment-movement-tasks"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer logistics-token"))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andExpect(content().string(equalTo(
            """
            {"decisionId":"%s","warehouseId":"%s","unitNumber":"БТ-42","plannedDurationMinutes":15,"deadlineAt":"%s","lines":[{"equipmentId":"%s","sourceRentalItemId":"%s","expectedSourceBalanceVersion":3,"quantity":2}]}
            """.formatted(
                decisionId,
                warehouseId,
                "2026-08-06T12:00:00Z",
                equipmentId,
                cabinId).strip())))
        .andRespond(withSuccess(task, MediaType.APPLICATION_JSON));
    server.expect(requestTo(
        "http://logistics.test/api/internal/logistics/v1/maintenance/equipment-movement-tasks/"
            + taskId))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer logistics-token"))
        .andRespond(withSuccess(task, MediaType.APPLICATION_JSON));

    var command = new MaintenanceDependencyGateway.PropertyEquipmentMovementCommand(
        decisionId,
        warehouseId,
        "БТ-42",
        15,
        deadlineAt,
        List.of(new MaintenanceDependencyGateway.PropertyEquipmentMovementLine(
            equipmentId, cabinId, 3, 2)));
    var created = gateway.createPropertyEquipmentMovementTask(key, command);
    var observed = gateway.getPropertyEquipmentMovementTask(taskId);

    assertThat(created.isOwnedByMaintenanceDisposition(decisionId)).isTrue();
    assertThat(observed.ownerId()).isEqualTo(decisionId);
    assertThat(observed.ownerType()).isEqualTo("MAINTENANCE_DISPOSITION");
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
            {"id":"%s","version":2,"warehouseId":"%s","number":"БТ-42","status":"FREE"}
            """.formatted(UUID.randomUUID(), warehouseId),
            MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> gateway.getRentalItemSnapshot(rentalItemId))
        .isInstanceOfSatisfying(MaintenanceDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    server.verify();
  }

  @Test
  void rentalItemSnapshotRejectsTruthWithoutTheCanonicalNumber() {
    UUID rentalItemId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    authorize("asset-token", "asset.maintenance");
    server.expect(requestTo(
        "http://asset.test/api/internal/asset/v1/maintenance/rental-items/"
            + rentalItemId + "/snapshot"))
        .andRespond(withSuccess(
            """
            {"id":"%s","version":2,"warehouseId":"%s","status":"FREE"}
            """.formatted(rentalItemId, warehouseId),
            MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> gateway.getRentalItemSnapshot(rentalItemId))
        .isInstanceOfSatisfying(MaintenanceDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    server.verify();
  }

  @Test
  void renewLeaseUsesTheExactMaintenanceLeaseEndpointAndOwnerFence() {
    UUID key = UUID.randomUUID();
    UUID leaseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    OffsetDateTime expiresAt = OffsetDateTime.parse("2026-07-28T12:15:00Z");
    authorize("asset-token", "asset.maintenance");
    server.expect(requestTo(
        "http://asset.test/api/internal/asset/v1/maintenance/operation-leases/"
            + leaseId + "/renew"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer asset-token"))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andExpect(content().string(equalTo("""
            {"expectedVersion":7,"fencingToken":17,"ownerType":"MAINTENANCE_REPAIR","ownerId":"%s"}
            """.formatted(repairId).strip())))
        .andRespond(withSuccess("""
            {"id":"%s","version":8,"rentalItemId":"%s","ownerType":"MAINTENANCE_REPAIR","ownerId":"%s","fencingToken":17,"state":"ACTIVE","expiresAt":"%s"}
            """.formatted(leaseId, rentalItemId, repairId, expiresAt), MediaType.APPLICATION_JSON));

    var renewed = gateway.renewLease(
        key, leaseId, 7, 17, "MAINTENANCE_REPAIR", repairId.toString());

    assertThat(renewed).isEqualTo(new MaintenanceDependencyGateway.LeaseSnapshot(
        leaseId,
        8,
        rentalItemId,
        "MAINTENANCE_REPAIR",
        repairId,
        17,
        expiresAt));
    server.verify();
  }

  @Test
  void bookedCabinReplacementConflictIsTheOnlyAssetProblemCodePropagated() {
    UUID rentalItemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    authorize("asset-token", "asset.maintenance");
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/maintenance/operation-leases"))
        .andRespond(
            withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(
                    """
                    {"status":409,"code":"BOOKED_UNIT_REPLACEMENT_REQUIRED","detail":"reserved"}
                    """));

    assertThatThrownBy(
            () ->
                gateway.acquireLease(
                    UUID.randomUUID(),
                    rentalItemId,
                    3,
                    "MAINTENANCE_REPAIR",
                    ownerId.toString()))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(exception.code()).isEqualTo("BOOKED_UNIT_REPLACEMENT_REQUIRED");
              assertThat(exception.getMessage()).contains("замену");
            });
    server.verify();
  }

  @Test
  void unknownAssetConflictKeepsTheGenericMaintenanceDependencyCode() {
    UUID rentalItemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    authorize("asset-token", "asset.maintenance");
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/maintenance/operation-leases"))
        .andRespond(
            withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"status\":409,\"code\":\"FOREIGN_PRIVATE_CONFLICT\"}"));

    assertThatThrownBy(
            () ->
                gateway.acquireLease(
                    UUID.randomUUID(),
                    rentalItemId,
                    3,
                    "MAINTENANCE_REPAIR",
                    ownerId.toString()))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(exception.code())
                  .isEqualTo(MaintenanceDependencyException.DEFAULT_CODE);
            });
    server.verify();
  }

  @Test
  void furnitureEquipmentUsesExactAssetScopePayloadAndNodeIdempotencyHeader() {
    UUID equipmentId = UUID.randomUUID();
    UUID catalogNodeId = UUID.randomUUID();
    UUID idempotencyKey =
        UUID.nameUUIDFromBytes(
            (catalogNodeId + ":Chair:create:null")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    authorize("asset-token", "asset.maintenance");
    server.expect(requestTo(
        "http://asset.test/api/internal/asset/v1/maintenance/equipment-catalog"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer asset-token"))
        .andExpect(header("Idempotency-Key", idempotencyKey.toString()))
        .andExpect(content().string(equalTo(
            "{\"externalReferenceId\":\"%s\",\"equipmentName\":\"Chair\",\"expectedEquipmentVersion\":null,\"maximumPerCabin\":null}"
                .formatted(catalogNodeId))))
        .andRespond(withSuccess(
            """
            {"externalReferenceId":"%s","equipmentId":"%s","equipmentName":"Chair","equipmentVersion":0,"maximumPerCabin":null}
            """.formatted(catalogNodeId, equipmentId),
            MediaType.APPLICATION_JSON));

    var response = gateway.ensureFurnitureEquipment(catalogNodeId, "Chair");

    assertThat(response).isEqualTo(
        new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
            catalogNodeId, equipmentId, "Chair", 0, null));
    server.verify();
  }

  @Test
  void furnitureEquipmentRejectsMismatchedCanonicalTruth() {
    UUID catalogNodeId = UUID.randomUUID();
    authorize("asset-token", "asset.maintenance");
    server.expect(requestTo(
        "http://asset.test/api/internal/asset/v1/maintenance/equipment-catalog"))
        .andRespond(withSuccess(
            """
            {"externalReferenceId":"%s","equipmentId":"%s","equipmentName":"Table"}
            """.formatted(catalogNodeId, UUID.randomUUID()),
            MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> gateway.ensureFurnitureEquipment(catalogNodeId, "Chair"))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception -> assertThat(exception.status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    server.verify();
  }

  @Test
  void furnitureEquipmentMapsAssetConflictToConflictWithoutLocalRetryAmbiguity() {
    UUID catalogNodeId = UUID.randomUUID();
    authorize("asset-token", "asset.maintenance");
    server.expect(requestTo(
        "http://asset.test/api/internal/asset/v1/maintenance/equipment-catalog"))
        .andRespond(withStatus(HttpStatus.CONFLICT));

    assertThatThrownBy(() -> gateway.ensureFurnitureEquipment(catalogNodeId, "Chair"))
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

    assertThatThrownBy(() -> gateway.ensureFurnitureEquipment(UUID.randomUUID(), "Chair"))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception -> assertThat(exception.status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    server.verify();
  }

  @Test
  void furniturePendingReturnUsesTheExactFencedAssetRequest() {
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
            {"expectedVersion":7,"action":"QUEUE_FOR_REPAIR","leaseId":"%s","fencingToken":17,"ownerType":"MAINTENANCE_ESTIMATE","ownerId":"%s","linkedReturnEstimateId":null,"furniturePendingReturns":[{"equipmentId":"%s","expectedSourceBalanceVersion":3,"quantity":2},{"equipmentId":"%s","expectedSourceBalanceVersion":5,"quantity":4}]}
            """.formatted(leaseId, estimateId, chairId, tableId).strip())))
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
        List.of(
            new MaintenanceDependencyGateway.FurniturePendingReturn(chairId, 3, 2),
            new MaintenanceDependencyGateway.FurniturePendingReturn(tableId, 5, 4)));

    assertThat(snapshot).isEqualTo(new MaintenanceDependencyGateway.AssetSnapshot(
        rentalItemId, 8, warehouseId, "C-1", "REPAIR"));
    server.verify();
  }

  @Test
  void unresolvedFurnitureCustodyUsesTheExactOwnerBoundary() {
    UUID ownerId = UUID.randomUUID();
    UUID claimId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID sourceBalanceId = UUID.randomUUID();
    authorize("asset-token", "asset.maintenance");
    server.expect(requestTo(
            "http://asset.test/api/internal/asset/v1/maintenance/furniture-custody"
                + "?ownerType=MAINTENANCE_REPAIR&ownerId="
                + ownerId))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer asset-token"))
        .andRespond(withSuccess(
            """
            [{"id":"%s","custodyVersion":2,"ownerType":"MAINTENANCE_REPAIR","ownerId":"%s","rentalItemId":"%s","warehouseId":"%s","equipmentId":"%s","sourceBalanceId":"%s","sourceBalanceVersion":7,"quantity":4,"returnedToStockQuantity":1,"preparedDispositionQuantity":0,"terminalDispositionQuantity":0,"unresolvedQuantity":3,"availableForDispositionQuantity":3,"selectedAt":"2026-08-05T00:00:00Z"}]
            """.formatted(
                claimId, ownerId, rentalItemId, warehouseId, equipmentId, sourceBalanceId),
            MediaType.APPLICATION_JSON));

    var claims = gateway.unresolvedFurnitureCustody("MAINTENANCE_REPAIR", ownerId);

    assertThat(claims).singleElement().satisfies(claim -> {
      assertThat(claim.id()).isEqualTo(claimId);
      assertThat(claim.availableForDispositionQuantity()).isEqualTo(3);
    });
    server.verify();
  }

  @Test
  void repairQueueAcceptsAnUnchangedVersionWhenTheAssetIsAlreadyInRepair() {
    UUID key = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID leaseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    authorize("asset-token", "asset.maintenance");
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/maintenance/rental-items/"
                    + rentalItemId
                    + "/fenced-status"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer asset-token"))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andRespond(
            withSuccess(
                """
                {"id":"%s","version":7,"warehouseId":"%s","number":"C-1","status":"REPAIR"}
                """
                    .formatted(rentalItemId, warehouseId),
                MediaType.APPLICATION_JSON));

    var snapshot =
        gateway.fencedStatus(
            key,
            rentalItemId,
            warehouseId,
            7,
            leaseId,
            17,
            "MAINTENANCE_REPAIR",
            repairId.toString(),
            "QUEUE_TO_REPAIR",
            false);

    assertThat(snapshot)
        .isEqualTo(
            new MaintenanceDependencyGateway.AssetSnapshot(
                rentalItemId, 7, warehouseId, "C-1", "REPAIR"));
    server.verify();
  }

  @Test
  void registerSendsCapacityAndAcceptsTaskBoardAutoSchedulingOnALaterDate() {
    UUID externalTaskId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    server.expect(requestTo("http://task.test/api/internal/task-board/v1/tasks"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(containsString("\"unitNumber\":\"БТ-42\"")))
        .andExpect(content().string(not(containsString(rentalItemId.toString()))))
        .andExpect(content().string(containsString("\"deadlineAt\":null")))
        .andExpect(content().string(containsString("\"scheduledDate\":\"2026-07-24\"")))
        .andExpect(content().string(containsString("\"priority\":1")))
        .andExpect(content().string(containsString("\"dailyCapacity\":4")))
        .andExpect(content().string(containsString(
            "\"source\":{\"type\":\"MAINTENANCE_REPAIR\",\"sourceId\":\""
                + repairId + "\"}")))
        .andRespond(withSuccess(
            taskResponse(
                externalTaskId,
                warehouseId,
                null,
                LocalDate.of(2026, 7, 25),
                1,
                List.of(queueId)),
            MediaType.APPLICATION_JSON));

    var response = gateway.registerTask(
        UUID.randomUUID(), externalTaskId, repairId, warehouseId, rentalItemId,
        "БТ-42",
        LocalDate.of(2026, 7, 24), 1, 4,
        List.of(taskStage(0, queueId, null)));

    assertThat(response.state()).isEqualTo("ACTIVE");
    server.verify();
  }

  @Test
  void registerSendsWorkerWorksAndStageDurationWithoutAnyPriceFields() {
    UUID externalTaskId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    UUID stageId = UUID.randomUUID();
    UUID workCommentId = UUID.randomUUID();
    UUID groupCommentId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    OffsetDateTime recordedAt = OffsetDateTime.parse("2026-07-24T10:15:30+03:00");
    MaintenanceDependencyGateway.TaskStage stage = new MaintenanceDependencyGateway.TaskStage(
        stageId,
        0,
        RepairStageKind.REPAIR_WORK,
        "Замена профлиста",
        queueId,
        null,
        List.of(
            new MaintenanceDependencyGateway.TaskWork(
                UUID.randomUUID(),
                "Замена профлиста",
                2.5,
                "шт",
                15,
                "Проверить внешний угол",
                List.of(mediaId))),
        List.of(new MaintenanceDependencyGateway.TaskMaterial(
            UUID.randomUUID(), "Профлист", 3.0, "лист")),
        List.of(
            new MaintenanceDependencyGateway.TaskComment(
                workCommentId, "Проверить внешний угол", "Смета", recordedAt),
            new MaintenanceDependencyGateway.TaskComment(
                groupCommentId, "Срочно", "Диспетчер", recordedAt)),
        List.of(new MaintenanceDependencyGateway.TaskSourceMedia(
            mediaId, 2, "image/jpeg", recordedAt.minusMinutes(1), recordedAt)),
        38);
    server.expect(requestTo("http://task.test/api/internal/task-board/v1/tasks"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(containsString("\"plannedDurationMinutes\":38")))
        .andExpect(content().string(containsString(
            "\"works\":[{\"id\":")))
        .andExpect(content().string(containsString(
            "\"name\":\"Замена профлиста\",\"quantity\":2.5,\"unit\":\"шт\",\"durationMinutes\":15,"
                + "\"comment\":\"Проверить внешний угол\",\"sourceMediaIds\":[\""
                + mediaId + "\"]}]")))
        .andExpect(content().string(containsString(
            "\"materials\":[{\"id\":")))
        .andExpect(content().string(containsString(
            "\"name\":\"Профлист\",\"quantity\":3.0,\"unit\":\"лист\"}]")))
        .andExpect(content().string(containsString("\"comments\":[{")))
        .andExpect(content().string(containsString("\"sourceMedia\":[{")))
        .andExpect(content().string(not(containsString("\"unitPrice\""))))
        .andExpect(content().string(not(containsString("\"lineTotal\""))))
        .andRespond(withSuccess(
            taskResponse(
                externalTaskId,
                warehouseId,
                null,
                LocalDate.of(2026, 7, 24),
                3,
                List.of(queueId)),
            MediaType.APPLICATION_JSON));

    gateway.registerTask(
        UUID.randomUUID(),
        externalTaskId,
        repairId,
        warehouseId,
        rentalItemId,
        "БТ-42",
        LocalDate.of(2026, 7, 24),
        3,
        6,
        List.of(stage));

    server.verify();
  }

  @Test
  void registerUsesOnlyExplicitRepairStageDuration() {
    UUID externalTaskId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID queue = UUID.randomUUID();
    MaintenanceDependencyGateway.TaskStage repair =
        new MaintenanceDependencyGateway.TaskStage(
            UUID.randomUUID(),
            0,
            RepairStageKind.REPAIR_WORK,
            "Repair",
            queue,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            35);
    server.expect(requestTo("http://task.test/api/internal/task-board/v1/tasks"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(containsString("\"plannedDurationMinutes\":35")))
        .andExpect(content().string(containsString(
            "\"taskText\":\"Repair\",\"plannedDurationMinutes\":35")))
        .andRespond(withSuccess(
            taskResponse(
                externalTaskId,
                warehouseId,
                null,
                LocalDate.of(2026, 7, 24),
                3,
                List.of(queue)),
            MediaType.APPLICATION_JSON));

    gateway.registerTask(
        UUID.randomUUID(),
        externalTaskId,
        repairId,
        warehouseId,
        rentalItemId,
        "БТ-42",
        LocalDate.of(2026, 7, 24),
        3,
        6,
        List.of(repair));

    server.verify();
  }

  @Test
  void registerRejectsTaskBoardTruthWithAnotherPriority() {
    UUID externalTaskId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    server
        .expect(requestTo("http://task.test/api/internal/task-board/v1/tasks"))
        .andRespond(
            withSuccess(
                taskResponse(externalTaskId, warehouseId, null, List.of(queueId)),
                MediaType.APPLICATION_JSON));

    assertThatThrownBy(
            () ->
                gateway.registerTask(
                    UUID.randomUUID(),
                    externalTaskId,
                    warehouseId,
                    rentalItemId,
                    "БТ-42",
                    LocalDate.of(2026, 7, 24),
                    1,
                    List.of(taskStage(0, queueId, null))))
        .isInstanceOf(MaintenanceDependencyException.class)
        .hasMessageContaining("schedule, priority");
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
        .andExpect(content().string(containsString("\"unitNumber\":\"БТ-42\"")))
        .andExpect(content().string(containsString(
            "\"deadlineAt\":\"2026-07-18T10:15:30+03:00\"")))
        .andRespond(withSuccess(
            taskResponse(
                externalTaskId, warehouseId, deadline, List.of(firstQueue, secondQueue)),
            MediaType.APPLICATION_JSON));

    var response = gateway.updatePreStartTask(
        UUID.randomUUID(), externalTaskId, 0, "БТ-42",
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
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "БТ-42",
        LocalDate.of(2026, 7, 18),
        3,
        6,
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
            {"warehouseId":"%s","queues":[{"queueDefinitionId":"%s","type":"REPAIR"},{"queueDefinitionId":"%s","type":"HOLDING"}]}
            """.formatted(warehouseId, repairQueue, holdingQueue).strip())))
        .andRespond(withSuccess("""
            {"warehouseId":"%s","ready":false,"missingQueueDefinitionIds":["%s"],"missingWarehouseBindingDefinitionIds":[],"mismatches":[{"queueDefinitionId":"%s","fields":["HIDDEN"]}],"resolvedQueues":[]}
            """.formatted(warehouseId, repairQueue, holdingQueue), MediaType.APPLICATION_JSON));

    var response = gateway.preflightMaintenanceRouting(
        warehouseId,
        List.of(
            new MaintenanceDependencyGateway.RoutingQueueRequirement(repairQueue, "REPAIR"),
            new MaintenanceDependencyGateway.RoutingQueueRequirement(holdingQueue, "HOLDING")));

    assertThat(response.ready()).isFalse();
    assertThat(response.missingQueueDefinitionIds()).containsExactly(repairQueue);
    assertThat(response.mismatches()).singleElement().satisfies(
        mismatch -> {
          assertThat(mismatch.queueDefinitionId()).isEqualTo(holdingQueue);
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
                UUID.randomUUID(), "REPAIR"))))
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
            {"warehouseId":"%s","ready":true,"missingQueueIds":["%s"],"mismatches":[],"resolvedQueues":[]}
            """.formatted(warehouseId, queueId), MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> gateway.preflightMaintenanceRouting(
            warehouseId,
            List.of(new MaintenanceDependencyGateway.RoutingQueueRequirement(
                queueId, "REPAIR"))))
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
        "http://task.test/api/internal/queue-definitions/" + queueId + "/references"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer registry-token"))
        .andExpect(headerDoesNotExist("Idempotency-Key"))
        .andExpect(content().string(equalTo(
            "{\"type\":\"CATALOG_POSITION\",\"externalReferenceId\":\""
                + externalReferenceId + "\"}")))
        .andRespond(withSuccess("""
            {"id":"%s","version":3,"queueDefinitionId":"%s","type":"CATALOG_POSITION","externalReferenceId":"%s"}
            """.formatted(referenceId, queueId, externalReferenceId), MediaType.APPLICATION_JSON));
    server.expect(requestTo(
        "http://task.test/api/internal/queue-definitions/references/CATALOG_POSITION/"
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
        UUID.randomUUID(), externalTaskId, UUID.randomUUID(), UUID.randomUUID(), "БТ-42",
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
    UUID catalogNodeId = UUID.randomUUID();
    var first = new NoOpMaintenanceDependencyGateway()
        .ensureFurnitureEquipment(catalogNodeId, " Chair ");
    var second = new NoOpMaintenanceDependencyGateway()
        .ensureFurnitureEquipment(catalogNodeId, "Chair");

    assertThat(first).isEqualTo(second);
    assertThat(first.equipmentName()).isEqualTo("Chair");
  }

  @Test
  void noOpFixtureCannotDropFurniturePendingReturns() {
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
        List.of(new MaintenanceDependencyGateway.FurniturePendingReturn(
            UUID.randomUUID(), 0, 1))))
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
        "Repair stage " + order, queueId, deadline);
  }

  private static String taskResponse(
      UUID externalTaskId,
      UUID warehouseId,
      OffsetDateTime deadline,
      List<UUID> queues) {
    return taskResponse(
        externalTaskId,
        warehouseId,
        deadline,
        LocalDate.of(2026, 7, 24),
        3,
        queues);
  }

  private static String taskResponse(
      UUID externalTaskId,
      UUID warehouseId,
      OffsetDateTime deadline,
      LocalDate scheduledDate,
      int priority,
      List<UUID> queues) {
    StringBuilder route = new StringBuilder();
    for (int index = 0; index < queues.size(); index++) {
      if (index > 0) route.append(',');
      route.append("""
          {"entryId":"%s","entryVersion":0,"queueId":"%s","routeIndex":%s}
          """.formatted(UUID.randomUUID(), queues.get(index), index));
    }
    String deadlineJson = deadline == null ? "null" : "\"" + deadline + "\"";
    return """
        {"taskId":"%s","taskVersion":1,"warehouseId":"%s",
         "externalTaskId":"%s","title":"Maintenance repair","unitNumber":"БТ-42",
         "description":null,"status":"ACTIVE","plannedDurationMinutes":null,
         "deadlineAt":%s,"scheduledDate":"%s","priority":%s,"pinned":false,
         "doneAt":null,"route":[%s]}
        """.formatted(
        UUID.randomUUID(),
        warehouseId,
        externalTaskId,
        deadlineJson,
        scheduledDate,
        priority,
        route);
  }
}
