package dev.buhanzaz.rwms.asset.disposition;

import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyAssetKind.CABIN;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyAssetKind.EQUIPMENT;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionContentsMode.DISPOSE_WITH_CABIN;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionContentsMode.MOVE_SELECTED_TO_STOCK;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionKind.LOSS;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionKind.WRITE_OFF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireMaintenanceOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireLogisticsEquipmentMovementReservationRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ExecuteLogisticsEquipmentMovementReservationLine;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ExecuteLogisticsEquipmentMovementReservationsRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.FencedStatusRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsEquipmentMovementPurpose;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceFencedStatusRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceFurnitureCustodyClaim;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceFurniturePendingReturn;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceStatusAction;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.OperationLeaseResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ReleaseOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateStatusRequest;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.ApplyMaintenancePropertyDispositionRequest;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyAssetSnapshot;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyDispositionLeaseProof;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PrepareMaintenancePropertyContentRequest;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PrepareMaintenancePropertyDispositionRequest;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.eventing.AssetReplayVerifier;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.MaintenanceFurnitureCustodyService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.asset.warehouse-registry.enabled=false",
      "spring.cloud.function.definition=",
      "spring.task.scheduling.enabled=false"
    })
@ActiveProfiles("test")
class PropertyDispositionServiceIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired AssetService assets;
  @Autowired MaintenanceFurnitureCustodyService custody;
  @Autowired PropertyDispositionService dispositions;
  @Autowired AssetEventStore events;
  @Autowired AssetReplayVerifier replay;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void stockLossIsPermanentlyBoundAndProducesOneTerminalMovement() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    EquipmentResponse equipment = createEquipment(subjectId);
    UUID stockBalanceId = seedStockBalance(equipment.id(), warehouseId, 5);

    MaintenancePropertyAssetSnapshot snapshot =
        dispositions.snapshot(EQUIPMENT, equipment.id(), warehouseId);
    assertThat(snapshot.sourceBalanceVersion()).isZero();
    assertThat(snapshot.quantity()).isEqualTo(5);
    assertThat(snapshot.dispositionAllowed()).isTrue();

    UUID decisionId = UUID.randomUUID();
    PrepareMaintenancePropertyDispositionRequest request = new PrepareMaintenancePropertyDispositionRequest(
        warehouseId,
        EQUIPMENT,
        equipment.id(),
        LOSS,
        null,
        snapshot.sourceBalanceVersion(),
        2L,
        null,
        null,
        null,
        List.of(),
        null);
    var prepared = dispositions.prepare(subjectId, decisionId, UUID.randomUUID(), request);
    var replayedPrepare = dispositions.prepare(subjectId, decisionId, UUID.randomUUID(), request);

    assertThat(prepared.replayed()).isFalse();
    assertThat(prepared.response().state().name()).isEqualTo("PREPARED");
    assertThat(replayedPrepare.replayed()).isTrue();
    assertThat(replayedPrepare.response()).isEqualTo(prepared.response());
    assertThat(holdState(decisionId, stockBalanceId)).isEqualTo("COMMITTED");
    assertThatThrownBy(
            () ->
                dispositions.prepare(
                    subjectId,
                    decisionId,
                    UUID.randomUUID(),
                    new PrepareMaintenancePropertyDispositionRequest(
                        warehouseId,
                        EQUIPMENT,
                        equipment.id(),
                        LOSS,
                        null,
                        snapshot.sourceBalanceVersion(),
                        3L,
                        null,
                        null,
                        null,
                        List.of(),
                        null)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("bound to another request");

    var applied = dispositions.apply(
        subjectId,
        decisionId,
        UUID.randomUUID(),
        new ApplyMaintenancePropertyDispositionRequest(null));
    var replayedApply = dispositions.apply(
        subjectId,
        decisionId,
        UUID.randomUUID(),
        new ApplyMaintenancePropertyDispositionRequest(null));

    assertThat(applied.replayed()).isFalse();
    assertThat(applied.response().equipmentMovements()).singleElement().satisfies(movement -> {
      assertThat(movement.kind()).isEqualTo("LOSS");
      assertThat(movement.quantity()).isEqualTo(2);
    });
    assertThat(replayedApply.replayed()).isTrue();
    assertThat(replayedApply.response()).isEqualTo(applied.response());
    assertThat(balance(equipment.id(), warehouseId, null, BalanceLocationKind.STOCK)).isEqualTo(3);
    assertThat(balance(equipment.id(), warehouseId, null, BalanceLocationKind.LOST)).isEqualTo(2);
    assertThat(holdState(decisionId, stockBalanceId)).isEqualTo("RELEASED");
    assertThat(movementCount(equipment.id(), "LOSS")).isEqualTo(1);
    assertThat(replay.rebuildAndVerify().aggregateCount()).isPositive();
  }

  @Test
  void cabinDisposesUnreturnedContentsAndRetainsAWorkerProofFenceForSelectedContents() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    EquipmentResponse equipment = createEquipment(subjectId);
    RentalItemResponse cabin = createFreeCabin(subjectId, warehouseId);
    attach(subjectId, warehouseId, cabin.id(), equipment.id(), 2);
    UUID estimateId = UUID.randomUUID();
    OperationLeaseResponse lease = assets.acquireMaintenanceLease(
        subjectId,
        UUID.randomUUID(),
        new AcquireMaintenanceOperationLeaseRequest(
            cabin.id(), MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE, estimateId, cabin.version()))
        .response();
    MaintenancePropertyAssetSnapshot snapshot = dispositions.snapshot(CABIN, cabin.id(), warehouseId);
    assertThat(snapshot.contents()).singleElement().satisfies(content -> {
      assertThat(content.equipmentId()).isEqualTo(equipment.id());
      assertThat(content.quantity()).isEqualTo(2);
    });

    UUID terminalDecision = UUID.randomUUID();
    PrepareMaintenancePropertyDispositionRequest terminalRequest = cabinRequest(
        warehouseId,
        cabin.id(),
        snapshot,
        LOSS,
        DISPOSE_WITH_CABIN,
        0,
        lease,
        estimateId);
    var prepared = dispositions.prepare(subjectId, terminalDecision, UUID.randomUUID(), terminalRequest);
    var applied = dispositions.apply(
        subjectId,
        terminalDecision,
        UUID.randomUUID(),
        new ApplyMaintenancePropertyDispositionRequest(null));

    assertThat(prepared.response().contents()).singleElement().satisfies(line -> {
      assertThat(line.currentQuantity()).isEqualTo(2);
      assertThat(line.moveQuantity()).isZero();
      assertThat(line.dispositionQuantity()).isEqualTo(2);
    });
    assertThat(applied.response().equipmentMovements()).singleElement().satisfies(movement -> {
      assertThat(movement.kind()).isEqualTo("LOSS");
      assertThat(movement.quantity()).isEqualTo(2);
    });
    assertThat(assets.rentalItem(cabin.id()).status()).isEqualTo(RentalItemStatus.LOST);
    assertThat(balance(equipment.id(), warehouseId, cabin.id(), BalanceLocationKind.CABIN_NON_RENTED))
        .isZero();
    assertThat(balance(equipment.id(), warehouseId, null, BalanceLocationKind.LOST)).isEqualTo(2);

    EquipmentResponse selectedEquipment = createEquipment(subjectId);
    RentalItemResponse selectedCabin = createFreeCabin(subjectId, warehouseId);
    attach(subjectId, warehouseId, selectedCabin.id(), selectedEquipment.id(), 1);
    UUID selectedEstimateId = UUID.randomUUID();
    OperationLeaseResponse selectedLease = assets.acquireMaintenanceLease(
        subjectId,
        UUID.randomUUID(),
        new AcquireMaintenanceOperationLeaseRequest(
            selectedCabin.id(),
            MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
            selectedEstimateId,
            selectedCabin.version()))
        .response();
    MaintenancePropertyAssetSnapshot selectedSnapshot =
        dispositions.snapshot(CABIN, selectedCabin.id(), warehouseId);
    UUID selectedDecision = UUID.randomUUID();
    dispositions.prepare(
        subjectId,
        selectedDecision,
        UUID.randomUUID(),
        cabinRequest(
            warehouseId,
            selectedCabin.id(),
            selectedSnapshot,
            WRITE_OFF,
            MOVE_SELECTED_TO_STOCK,
            1,
            selectedLease,
            selectedEstimateId));

    assertThatThrownBy(
            () ->
                dispositions.apply(
                    subjectId,
                    selectedDecision,
                    UUID.randomUUID(),
                    new ApplyMaintenancePropertyDispositionRequest(UUID.randomUUID())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("not been fully executed");
    assertThat(assets.rentalItem(selectedCabin.id()).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(fenceState(selectedDecision)).isEqualTo("PREPARED");

    var selectedContent = selectedSnapshot.contents().getFirst();
    UUID logisticsLineId = UUID.randomUUID();
    var reservation = assets.acquireLogisticsEquipmentMovementReservation(
        subjectId,
        UUID.randomUUID(),
        new AcquireLogisticsEquipmentMovementReservationRequest(
            selectedDecision,
            logisticsLineId,
            LogisticsEquipmentMovementPurpose.MAINTENANCE_DISPOSITION,
            selectedEquipment.id(),
            warehouseId,
            selectedCabin.id(),
            BalanceLocationKind.CABIN_NON_RENTED,
            selectedContent.balanceVersion(),
            1L,
            OffsetDateTime.now().plusHours(1)))
        .response();
    assertThatThrownBy(
            () ->
                assets.executeLogisticsEquipmentMovementReservations(
                    subjectId,
                    UUID.randomUUID(),
                    new ExecuteLogisticsEquipmentMovementReservationsRequest(
                        selectedDecision,
                        List.of(
                            new ExecuteLogisticsEquipmentMovementReservationLine(
                                reservation.reservationId(),
                                reservation.version(),
                                logisticsLineId,
                                warehouseId,
                                null,
                                BalanceLocationKind.WRITTEN_OFF)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("must return prepared cabin contents to stock");
    assets.executeLogisticsEquipmentMovementReservations(
        subjectId,
        UUID.randomUUID(),
        new ExecuteLogisticsEquipmentMovementReservationsRequest(
            selectedDecision,
            List.of(
                new ExecuteLogisticsEquipmentMovementReservationLine(
                    reservation.reservationId(),
                    reservation.version(),
                    logisticsLineId,
                    warehouseId,
                    null,
                    BalanceLocationKind.STOCK))));
    UUID completedTaskId = UUID.randomUUID();
    var selectedApplied = dispositions.apply(
        subjectId,
        selectedDecision,
        UUID.randomUUID(),
        new ApplyMaintenancePropertyDispositionRequest(completedTaskId));

    assertThat(selectedApplied.response().equipmentMovements()).isEmpty();
    assertThat(assets.rentalItem(selectedCabin.id()).status())
        .isEqualTo(RentalItemStatus.WRITTEN_OFF);
    assertThat(
            balance(
                selectedEquipment.id(),
                warehouseId,
                selectedCabin.id(),
                BalanceLocationKind.CABIN_NON_RENTED))
        .isZero();
    assertThat(balance(selectedEquipment.id(), warehouseId, null, BalanceLocationKind.STOCK))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from equipment_movement
                where origin_reservation_id=? and equipment_id=?
                """,
                Integer.class,
                reservation.reservationId(),
                selectedEquipment.id()))
        .isEqualTo(1);
  }

  @Test
  void inventoryWriteOffCanTerminalizeALaggingRentedCabinOnlyAfterLogisticsLeaseRelease() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse free = createFreeCabin(subjectId, warehouseId);
    OperationLeaseResponse logisticsLease =
        assets
            .acquireLease(
                subjectId,
                UUID.randomUUID(),
                new AcquireOperationLeaseRequest(
                    free.id(), "LOGISTICS_SHIPMENT", UUID.randomUUID().toString(), free.version()))
            .response();
    RentalItemResponse rented =
        assets.fencedStatus(
            free.id(),
            new FencedStatusRequest(
                free.version(),
                RentalItemStatus.RENTED,
                logisticsLease.id(),
                logisticsLease.fencingToken()));
    UUID decisionId = UUID.randomUUID();
    PrepareMaintenancePropertyDispositionRequest request =
        new PrepareMaintenancePropertyDispositionRequest(
            warehouseId,
            CABIN,
            rented.id(),
            WRITE_OFF,
            rented.version(),
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null);

    assertThat(dispositions.snapshot(CABIN, rented.id(), warehouseId).dispositionAllowed())
        .isFalse();
    assertThatThrownBy(
            () ->
                dispositions.prepare(
                    subjectId, decisionId, UUID.randomUUID(), request))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("active operation lease");
    assertThat(assets.rentalItem(rented.id()).status()).isEqualTo(RentalItemStatus.RENTED);

    assets.releaseLease(
        subjectId,
        UUID.randomUUID(),
        logisticsLease.id(),
        new ReleaseOperationLeaseRequest(
            logisticsLease.version(), logisticsLease.fencingToken()));
    RentalItemResponse afterRelease = assets.rentalItem(rented.id());
    assertThat(afterRelease.status()).isEqualTo(RentalItemStatus.RENTED);
    assertThat(afterRelease.version()).isEqualTo(rented.version());
    assertThat(dispositions.snapshot(CABIN, rented.id(), warehouseId).dispositionAllowed())
        .isTrue();

    var prepared =
        dispositions.prepare(subjectId, decisionId, UUID.randomUUID(), request);
    assertThat(prepared.response().state().name()).isEqualTo("PREPARED");
    RentalItemResponse afterPrepare = assets.rentalItem(rented.id());
    assertThat(afterPrepare.status()).isEqualTo(RentalItemStatus.RENTED);
    assertThat(afterPrepare.version()).isEqualTo(rented.version());

    OperationLeaseResponse competingLease =
        assets
            .acquireLease(
                subjectId,
                UUID.randomUUID(),
                new AcquireOperationLeaseRequest(
                    rented.id(), "LOGISTICS_SHIPMENT", UUID.randomUUID().toString(), rented.version()))
            .response();
    assertThatThrownBy(
            () ->
                dispositions.apply(
                    subjectId,
                    decisionId,
                    UUID.randomUUID(),
                    new ApplyMaintenancePropertyDispositionRequest(null)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("active operation lease");
    assertThat(assets.rentalItem(rented.id()).status()).isEqualTo(RentalItemStatus.RENTED);
    assertThat(fenceState(decisionId)).isEqualTo("PREPARED");

    assets.releaseLease(
        subjectId,
        UUID.randomUUID(),
        competingLease.id(),
        new ReleaseOperationLeaseRequest(
            competingLease.version(), competingLease.fencingToken()));
    var applied =
        dispositions.apply(
            subjectId,
            decisionId,
            UUID.randomUUID(),
            new ApplyMaintenancePropertyDispositionRequest(null));

    assertThat(applied.response().equipmentMovements()).isEmpty();
    assertThat(assets.rentalItem(rented.id()).status())
        .isEqualTo(RentalItemStatus.WRITTEN_OFF);
  }

  @Test
  void custodyFurnitureBecomesTerminalOnlyWhenAnApprovedEquipmentDecisionApplies() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    EquipmentResponse furniture = createEquipment(subjectId);
    RentalItemResponse cabin = createFreeCabin(subjectId, warehouseId);
    attach(subjectId, warehouseId, cabin.id(), furniture.id(), 2);
    OperationLeaseResponse lease = assets.acquireMaintenanceLease(
        subjectId,
        UUID.randomUUID(),
        new AcquireMaintenanceOperationLeaseRequest(
            cabin.id(), MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, repairId, cabin.version()))
        .response();
    long sourceVersion = assets.equipmentTotals(furniture.id(), warehouseId).balances().stream()
        .filter(value -> cabin.id().equals(value.rentalItemId()))
        .filter(value -> value.locationKind() == BalanceLocationKind.CABIN_NON_RENTED)
        .findFirst()
        .orElseThrow()
        .version();
    assets.maintenanceFencedStatus(
        subjectId,
        UUID.randomUUID(),
        cabin.id(),
        new MaintenanceFencedStatusRequest(
            cabin.version(),
            MaintenanceStatusAction.QUEUE_FOR_REPAIR,
            lease.id(),
            lease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
            repairId,
            null,
            List.of(new MaintenanceFurniturePendingReturn(furniture.id(), sourceVersion, 2))));
    MaintenanceFurnitureCustodyClaim claim = custody.unresolvedClaims(
        MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, repairId).getFirst();

    assertThat(balance(furniture.id(), warehouseId, null, BalanceLocationKind.LOST)).isZero();
    assertThat(movementCount(furniture.id(), "LOSS")).isZero();
    UUID decisionId = UUID.randomUUID();
    PrepareMaintenancePropertyDispositionRequest request =
        new PrepareMaintenancePropertyDispositionRequest(
            warehouseId,
            EQUIPMENT,
            furniture.id(),
            LOSS,
            null,
            null,
            2L,
            claim.id(),
            claim.custodyVersion(),
            null,
            List.of(),
            null);
    var prepared = dispositions.prepare(subjectId, decisionId, UUID.randomUUID(), request);
    assertThat(prepared.response().maintenanceCustodyClaimId()).isEqualTo(claim.id());
    assertThat(prepared.response().maintenanceCustodyVersion()).isEqualTo(claim.custodyVersion() + 1);
    assertThat(balance(furniture.id(), warehouseId, null, BalanceLocationKind.LOST)).isZero();

    var applied = dispositions.apply(
        subjectId,
        decisionId,
        UUID.randomUUID(),
        new ApplyMaintenancePropertyDispositionRequest(null));
    var replayed = dispositions.apply(
        subjectId,
        decisionId,
        UUID.randomUUID(),
        new ApplyMaintenancePropertyDispositionRequest(null));

    assertThat(applied.response().equipmentMovements()).isEmpty();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(applied.response());
    assertThat(balance(furniture.id(), warehouseId, null, BalanceLocationKind.LOST)).isEqualTo(2);
    assertThat(movementCount(furniture.id(), "LOSS")).isZero();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from maintenance_furniture_custody_event
                where claim_id=? and event_type='DISPOSITION_APPLIED'
                """,
                Integer.class,
                claim.id()))
        .isEqualTo(1);
    assertThat(custody.unresolvedClaims(MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, repairId))
        .isEmpty();
  }

  private PrepareMaintenancePropertyDispositionRequest cabinRequest(
      UUID warehouseId,
      UUID cabinId,
      MaintenancePropertyAssetSnapshot snapshot,
      dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionKind disposition,
      dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionContentsMode mode,
      long moveQuantity,
      OperationLeaseResponse lease,
      UUID estimateId) {
    var content = snapshot.contents().getFirst();
    return new PrepareMaintenancePropertyDispositionRequest(
        warehouseId,
        CABIN,
        cabinId,
        disposition,
        snapshot.version(),
        null,
        null,
        null,
        null,
        mode,
        List.of(
            new PrepareMaintenancePropertyContentRequest(
                content.equipmentId(),
                content.balanceVersion(),
                content.quantity(),
                moveQuantity)),
        new MaintenancePropertyDispositionLeaseProof(
            lease.id(), lease.fencingToken(), MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE, estimateId));
  }

  private EquipmentResponse createEquipment(UUID subjectId) {
    return assets.createEquipment(
        subjectId,
        UUID.randomUUID(),
        new CreateEquipmentRequest(
            "Property disposition furniture " + UUID.randomUUID(), EquipmentCategory.FURNITURE, null))
        .response();
  }

  private RentalItemResponse createFreeCabin(UUID subjectId, UUID warehouseId) {
    RentalItemResponse created = assets.createRentalItem(
        subjectId,
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            warehouseId,
            "property-cabin-" + UUID.randomUUID(),
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            null,
            List.of(),
            false,
            Map.of(),
            List.of()))
        .response();
    return assets.updateStatus(
        created.id(), new UpdateStatusRequest(created.version(), RentalItemStatus.FREE));
  }

  private void attach(
      UUID subjectId,
      UUID warehouseId,
      UUID cabinId,
      UUID equipmentId,
      long quantity) {
    seedStockBalance(equipmentId, warehouseId, quantity);
    assets.transfer(
        subjectId,
        UUID.randomUUID(),
        new TransferEquipmentRequest(
            equipmentId,
            warehouseId,
            null,
            BalanceLocationKind.STOCK,
            0L,
            warehouseId,
            cabinId,
            BalanceLocationKind.CABIN_NON_RENTED,
            0L,
            quantity));
  }

  private UUID seedStockBalance(UUID equipmentId, UUID warehouseId, long quantity) {
    UUID balanceId = UUID.randomUUID();
    new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
      jdbc.update(
          """
          insert into equipment_balance(
            id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,created_at,updated_at)
          values (?,0,?,?,null,'STOCK',?,clock_timestamp(),clock_timestamp())
          """,
          balanceId,
          equipmentId,
          warehouseId,
          quantity);
      Map<String, Object> fact = new LinkedHashMap<>();
      fact.put("balanceId", balanceId.toString());
      fact.put("equipmentId", equipmentId.toString());
      fact.put("warehouseId", warehouseId.toString());
      fact.put("rentalItemId", null);
      fact.put("locationKind", BalanceLocationKind.STOCK.name());
      fact.put("quantity", quantity);
      events.initialize(
          AssetAggregateType.EQUIPMENT_BALANCE,
          balanceId,
          0,
          AssetEventType.EQUIPMENT_BALANCE_CHANGED,
          fact,
          fact);
    });
    return balanceId;
  }

  private long balance(
      UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind kind) {
    return jdbc.query(
        """
        select quantity
        from equipment_balance
        where equipment_id=? and warehouse_id=? and rental_item_id is not distinct from ? and location_kind=?
        """,
        (rs, row) -> rs.getLong("quantity"),
        equipmentId,
        warehouseId,
        rentalItemId,
        kind.name()).stream().findFirst().orElse(0L);
  }

  private String holdState(UUID decisionId, UUID sourceBalanceId) {
    return jdbc.queryForObject(
        """
        select state from equipment_allocation_hold
        where owner_type='MAINTENANCE_PROPERTY_DISPOSITION' and owner_id=? and source_balance_id=?
        """,
        String.class,
        decisionId.toString(),
        sourceBalanceId);
  }

  private String fenceState(UUID decisionId) {
    return jdbc.queryForObject(
        "select state from property_disposition_fence where decision_id=?",
        String.class,
        decisionId);
  }

  private int movementCount(UUID equipmentId, String kind) {
    Integer count = jdbc.queryForObject(
        "select count(*) from equipment_movement where equipment_id=? and movement_kind=?",
        Integer.class,
        equipmentId,
        kind);
    return count == null ? 0 : count;
  }
}
