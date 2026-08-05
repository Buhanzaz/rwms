package dev.buhanzaz.rwms.asset;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseOwnerType.LOGISTICS_RETURN;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseOwnerType.LOGISTICS_SHIPMENT;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseOwnerType.LOGISTICS_TRANSFER;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsRentalItemAction.RETURN_INTAKE;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsRentalItemAction.RETURN_SETTLE_FREE;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsRentalItemAction.SHIPMENT_CONFIRM;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsRentalItemAction.TRANSFER_ARRIVE;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsRentalItemAction.TRANSFER_DEPART;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireLogisticsEquipmentHoldRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireLogisticsEquipmentMovementReservationRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireLogisticsOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireMaintenanceOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ExecuteLogisticsEquipmentMovementReservationLine;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ExecuteLogisticsEquipmentMovementReservationsRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsEquipmentHoldCommandRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsEquipmentMovementPurpose;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsFencedEffectRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseCommandRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsReturnEquipmentReceiptLine;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsReturnEquipmentReceiptRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceFencedStatusRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceStatusAction;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ReleaseMaintenanceOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferAssetStatus;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateStatusRequest;
import dev.buhanzaz.rwms.asset.api.LogisticsFurnitureMovementApiModels.CabinFurnitureMovementPlanRequest;
import dev.buhanzaz.rwms.asset.api.LogisticsFurnitureMovementApiModels.CabinFurnitureRequirement;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.eventing.AssetReplayVerifier;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.LogisticsFurnitureMovementPlanService;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Exercises the private Stage 8 boundary against real Flyway/JPA/PostgreSQL
 * state. The service itself remains the owner of all balance and ledger data.
 */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.asset.warehouse-registry.enabled=false",
      "spring.cloud.function.definition=",
      "spring.task.scheduling.enabled=false"
    })
@ActiveProfiles("test")
class LogisticsAssetBoundaryIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired AssetService service;
  @Autowired LogisticsFurnitureMovementPlanService furniturePlans;
  @Autowired AssetEventStore events;
  @Autowired AssetReplayVerifier replay;
  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManager entityManager;
  @Autowired PlatformTransactionManager transactions;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  @Transactional
  void typedLeaseIsSubjectBoundAndExpiredFenceRecoversToANewerLease() {
    UUID subject = UUID.randomUUID();
    RentalItemResponse rental = rental(subject, UUID.randomUUID(), RentalItemStatus.FREE);
    UUID document = UUID.randomUUID();
    UUID line = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    AcquireLogisticsOperationLeaseRequest request = new AcquireLogisticsOperationLeaseRequest(
        rental.id(), LOGISTICS_SHIPMENT, document, line, rental.version());

    var acquired = service.acquireLogisticsLease(subject, key, request);
    var replayed = service.acquireLogisticsLease(subject, key, request);
    assertThat(acquired.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(acquired.response());
    assertThat(jdbc.queryForMap(
        "select owner_type,owner_id from operation_lease where id=?",
        acquired.response().leaseId()))
        .containsEntry("owner_type", "LOGISTICS_SHIPMENT")
        .containsEntry("owner_id", document + ":" + line);

    assertThatThrownBy(() -> service.renewLogisticsLease(
        subject,
        UUID.randomUUID(),
        acquired.response().leaseId(),
        new LogisticsLeaseCommandRequest(
            acquired.response().version(),
            acquired.response().fencingToken(),
            LOGISTICS_SHIPMENT,
            document,
            UUID.randomUUID())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("another logistics document line");

    jdbc.update(
        "update operation_lease set expires_at=clock_timestamp() - interval '1 minute' where id=?",
        acquired.response().leaseId());
    entityManager.clear();
    UUID replacementDocument = UUID.randomUUID();
    UUID replacementLine = UUID.randomUUID();
    var replacement = service.acquireLogisticsLease(
        subject,
        UUID.randomUUID(),
        new AcquireLogisticsOperationLeaseRequest(
            rental.id(),
            LOGISTICS_SHIPMENT,
            replacementDocument,
            replacementLine,
            rental.version()));
    assertThat(replacement.response().fencingToken())
        .isEqualTo(acquired.response().fencingToken() + 1);
    assertThatThrownBy(() -> service.applyLogisticsEffect(
        subject,
        UUID.randomUUID(),
        rental.id(),
        effect(
            rental.version(),
            SHIPMENT_CONFIRM,
            acquired.response().leaseId(),
            acquired.response().fencingToken(),
            LOGISTICS_SHIPMENT,
            document,
            line,
            null)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("stale or fenced");
  }

  @Test
  @Transactional
  void returnEffectsUseClosedActionsTypedOwnerAndActiveFenceReplay() {
    UUID subject = UUID.randomUUID();
    RentalItemResponse rental = rental(subject, UUID.randomUUID(), RentalItemStatus.RENTED);
    int setupLogisticsEffects = jdbc.queryForObject(
        "select count(*) from domain_event where aggregate_id=? and event_type=?",
        Integer.class,
        rental.id().toString(),
        AssetEventType.RENTAL_ITEM_LOGISTICS_EFFECT_APPLIED.value());
    UUID document = UUID.randomUUID();
    UUID line = UUID.randomUUID();
    var lease = service.acquireLogisticsLease(
        subject,
        UUID.randomUUID(),
        new AcquireLogisticsOperationLeaseRequest(
            rental.id(), LOGISTICS_RETURN, document, line, rental.version()))
        .response();

    UUID intakeKey = UUID.randomUUID();
    var intake = service.applyLogisticsEffect(
        subject,
        intakeKey,
        rental.id(),
        effect(
            rental.version(),
            RETURN_INTAKE,
            lease.leaseId(),
            lease.fencingToken(),
            LOGISTICS_RETURN,
            document,
            line,
            null));
    var intakeReplay = service.applyLogisticsEffect(
        subject,
        intakeKey,
        rental.id(),
        effect(
            rental.version(),
            RETURN_INTAKE,
            lease.leaseId(),
            lease.fencingToken(),
            LOGISTICS_RETURN,
            document,
            line,
            null));
    assertThat(intake.response().status()).isEqualTo(RentalItemStatus.AFTER_RENT);
    assertThat(intakeReplay.replayed()).isTrue();
    assertThat(intakeReplay.response()).isEqualTo(intake.response());
    assertThatThrownBy(() -> service.applyLogisticsEffect(
        subject,
        UUID.randomUUID(),
        rental.id(),
        effect(
            intake.response().version(),
            SHIPMENT_CONFIRM,
            lease.leaseId(),
            lease.fencingToken(),
            LOGISTICS_RETURN,
            document,
            line,
            null)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("owner");
    assertThatThrownBy(() -> service.applyLogisticsEffect(
        subject,
        UUID.randomUUID(),
        rental.id(),
        effect(
            intake.response().version(),
            RETURN_SETTLE_FREE,
            lease.leaseId(),
            lease.fencingToken() + 1,
            LOGISTICS_RETURN,
            document,
            line,
            null)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("stale or fenced");

    var settled = service.applyLogisticsEffect(
        subject,
        UUID.randomUUID(),
        rental.id(),
        effect(
            intake.response().version(),
            RETURN_SETTLE_FREE,
            lease.leaseId(),
            lease.fencingToken(),
            LOGISTICS_RETURN,
            document,
            line,
            null));
    assertThat(settled.response().status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(jdbc.queryForObject(
        "select count(*) from domain_event where aggregate_id=? and event_type=?",
        Integer.class,
        rental.id().toString(),
        AssetEventType.RENTAL_ITEM_LOGISTICS_EFFECT_APPLIED.value()))
        .isEqualTo(setupLogisticsEffects + 2);
  }

  @Test
  @Transactional
  void transferArrivalMovesAttachedContentsThroughAssetLedgerAtomically() {
    UUID subject = UUID.randomUUID();
    UUID origin = UUID.randomUUID();
    UUID destination = UUID.randomUUID();
    RentalItemResponse rental = rental(subject, origin, RentalItemStatus.FREE);
    UUID equipmentId = service.createEquipment(
        subject,
        UUID.randomUUID(),
        new CreateEquipmentRequest(
            "Logistics test equipment",
            EquipmentCategory.FURNITURE,
            null))
        .response()
        .id();
    seedStockBalance(equipmentId, origin, 3);
    service.transfer(
        subject,
        UUID.randomUUID(),
        new TransferEquipmentRequest(
            equipmentId,
            origin,
            null,
            BalanceLocationKind.STOCK,
            0L,
            origin,
            rental.id(),
            BalanceLocationKind.CABIN_NON_RENTED,
            0L,
            3L));
    UUID document = UUID.randomUUID();
    UUID line = UUID.randomUUID();
    var lease = service.acquireLogisticsLease(
        subject,
        UUID.randomUUID(),
        new AcquireLogisticsOperationLeaseRequest(
            rental.id(), LOGISTICS_TRANSFER, document, line, rental.version()))
        .response();
    var departed = service.applyLogisticsEffect(
        subject,
        UUID.randomUUID(),
        rental.id(),
        effect(
            rental.version(),
            TRANSFER_DEPART,
            lease.leaseId(),
            lease.fencingToken(),
            LOGISTICS_TRANSFER,
            document,
            line,
            null));
    var arrived = service.applyLogisticsEffect(
        subject,
        UUID.randomUUID(),
        rental.id(),
        effect(
            departed.response().version(),
            TRANSFER_ARRIVE,
            lease.leaseId(),
            lease.fencingToken(),
            LOGISTICS_TRANSFER,
            document,
            line,
            destination));

    assertThat(arrived.response().warehouseId()).isEqualTo(destination);
    assertThat(arrived.response().status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(arrived.response().contents())
        .containsExactly(new dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsEquipmentContentSnapshot(
            equipmentId,
            3L));
    assertThat(jdbc.queryForObject(
        """
        select quantity from equipment_balance
        where equipment_id=? and warehouse_id=? and rental_item_id=? and location_kind='CABIN_NON_RENTED'
        """,
        Long.class,
        equipmentId,
        destination,
        rental.id()))
        .isEqualTo(3L);
    assertThat(jdbc.queryForObject(
        """
        select quantity from equipment_balance
        where equipment_id=? and warehouse_id=? and rental_item_id=? and location_kind='CABIN_NON_RENTED'
        """,
        Long.class,
        equipmentId,
        origin,
        rental.id()))
        .isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from equipment_movement where equipment_id=?",
        Integer.class,
        equipmentId))
        .isEqualTo(2);
    assertThat(replay.rebuildAndVerify().aggregateCount()).isGreaterThanOrEqualTo(6);
  }

  @Test
  @Transactional
  void transferRestoresThePersistedActiveRepairStatusAndRejectsAMismatchedArrival() {
    UUID subject = UUID.randomUUID();
    UUID origin = UUID.randomUUID();
    UUID destination = UUID.randomUUID();
    RentalItemResponse rental =
        rental(subject, origin, RentalItemStatus.REPAIR);
    UUID document = UUID.randomUUID();
    UUID line = UUID.randomUUID();
    var lease =
        service
            .acquireLogisticsLease(
                subject,
                UUID.randomUUID(),
                new AcquireLogisticsOperationLeaseRequest(
                    rental.id(),
                    LOGISTICS_TRANSFER,
                    document,
                    line,
                    rental.version()))
            .response();

    var departed =
        service.applyLogisticsEffect(
            subject,
            UUID.randomUUID(),
            rental.id(),
            effect(
                rental.version(),
                TRANSFER_DEPART,
                lease.leaseId(),
                lease.fencingToken(),
                LOGISTICS_TRANSFER,
                document,
                line,
                null,
                TransferAssetStatus.REPAIR));

    assertThat(departed.response().status())
        .isEqualTo(RentalItemStatus.IN_TRANSFER);
    assertThat(
            jdbc.queryForObject(
                "select transfer_origin_status from rental_item where id=?",
                String.class,
                rental.id()))
        .isEqualTo("REPAIR");
    assertThat(replay.rebuildAndVerify().aggregateCount())
        .isGreaterThanOrEqualTo(2);
    assertThatThrownBy(
            () ->
                service.applyLogisticsEffect(
                    subject,
                    UUID.randomUUID(),
                    rental.id(),
                    effect(
                        departed.response().version(),
                        TRANSFER_ARRIVE,
                        lease.leaseId(),
                        lease.fencingToken(),
                        LOGISTICS_TRANSFER,
                        document,
                        line,
                        destination,
                        TransferAssetStatus.FREE)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("persisted departure");

    var arrived =
        service.applyLogisticsEffect(
            subject,
            UUID.randomUUID(),
            rental.id(),
            effect(
                departed.response().version(),
                TRANSFER_ARRIVE,
                lease.leaseId(),
                lease.fencingToken(),
                LOGISTICS_TRANSFER,
                document,
                line,
                destination,
                TransferAssetStatus.REPAIR));

    assertThat(arrived.response().warehouseId()).isEqualTo(destination);
    assertThat(arrived.response().status()).isEqualTo(RentalItemStatus.REPAIR);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from rental_item
                where id=? and transfer_origin_status is null
                """,
                Integer.class,
                rental.id()))
        .isOne();

    RentalItemResponse capital =
        rental(subject, origin, RentalItemStatus.CAPITAL_REPAIR);
    UUID capitalDocument = UUID.randomUUID();
    UUID capitalLine = UUID.randomUUID();
    var capitalLease =
        service
            .acquireLogisticsLease(
                subject,
                UUID.randomUUID(),
                new AcquireLogisticsOperationLeaseRequest(
                    capital.id(),
                    LOGISTICS_TRANSFER,
                    capitalDocument,
                    capitalLine,
                    capital.version()))
            .response();
    var capitalDeparted =
        service.applyLogisticsEffect(
            subject,
            UUID.randomUUID(),
            capital.id(),
            effect(
                capital.version(),
                TRANSFER_DEPART,
                capitalLease.leaseId(),
                capitalLease.fencingToken(),
                LOGISTICS_TRANSFER,
                capitalDocument,
                capitalLine,
                null,
                TransferAssetStatus.REPAIR));
    var capitalArrived =
        service.applyLogisticsEffect(
            subject,
            UUID.randomUUID(),
            capital.id(),
            effect(
                capitalDeparted.response().version(),
                TRANSFER_ARRIVE,
                capitalLease.leaseId(),
                capitalLease.fencingToken(),
                LOGISTICS_TRANSFER,
                capitalDocument,
                capitalLine,
                UUID.randomUUID(),
                TransferAssetStatus.REPAIR));

    assertThat(capitalArrived.response().status())
        .isEqualTo(RentalItemStatus.REPAIR);
    assertThat(replay.rebuildAndVerify().aggregateCount())
        .isGreaterThanOrEqualTo(6);
  }

  @Test
  @Transactional
  void cabinFurniturePlanReplacesTheCompleteFurnitureCompositionWithoutMovingBalances() {
    UUID subject = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    RentalItemResponse cabin = rental(subject, warehouse, RentalItemStatus.FREE);
    UUID table = service.createEquipment(
        subject,
        UUID.randomUUID(),
        new CreateEquipmentRequest(
            "Current table",
            EquipmentCategory.FURNITURE,
            null))
        .response()
        .id();
    UUID bed = service.createEquipment(
        subject,
        UUID.randomUUID(),
        new CreateEquipmentRequest(
            "Requested bed",
            EquipmentCategory.FURNITURE,
            null))
        .response()
        .id();
    seedStockBalance(table, warehouse, 4);
    seedStockBalance(bed, warehouse, 4);
    service.transfer(
        subject,
        UUID.randomUUID(),
        new TransferEquipmentRequest(
            table,
            warehouse,
            null,
            BalanceLocationKind.STOCK,
            0L,
            warehouse,
            cabin.id(),
            BalanceLocationKind.CABIN_NON_RENTED,
            0L,
            4L));

    var plan = furniturePlans.plan(
        cabin.id(),
        new CabinFurnitureMovementPlanRequest(
            warehouse, List.of(new CabinFurnitureRequirement(bed, 4L))));

    assertThat(plan.rentalItemId()).isEqualTo(cabin.id());
    assertThat(plan.lines()).hasSize(2);
    assertThat(plan.lines()).anySatisfy(line -> {
      assertThat(line.equipmentId()).isEqualTo(table);
      assertThat(line.sourceRentalItemId()).isEqualTo(cabin.id());
      assertThat(line.sourceLocationKind()).isEqualTo(BalanceLocationKind.CABIN_NON_RENTED);
      assertThat(line.targetRentalItemId()).isNull();
      assertThat(line.targetLocationKind()).isEqualTo(BalanceLocationKind.STOCK);
      assertThat(line.quantity()).isEqualTo(4L);
    });
    assertThat(plan.lines()).anySatisfy(line -> {
      assertThat(line.equipmentId()).isEqualTo(bed);
      assertThat(line.sourceRentalItemId()).isNull();
      assertThat(line.sourceLocationKind()).isEqualTo(BalanceLocationKind.STOCK);
      assertThat(line.targetRentalItemId()).isEqualTo(cabin.id());
      assertThat(line.targetLocationKind()).isEqualTo(BalanceLocationKind.CABIN_NON_RENTED);
      assertThat(line.quantity()).isEqualTo(4L);
    });
    assertThat(service.equipmentTotals(table, warehouse).balances())
        .filteredOn(balance -> cabin.id().equals(balance.rentalItemId()))
        .singleElement()
        .extracting(balance -> balance.quantity())
        .isEqualTo(4L);
    assertThat(service.equipmentTotals(bed, warehouse).stockQuantity()).isEqualTo(4L);
  }

  @Test
  @Transactional
  void shipmentHoldUsesOpaqueOwnerAndChecksReplayCasAndOwner() {
    UUID subject = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    UUID equipmentId = service.createEquipment(
        subject,
        UUID.randomUUID(),
        new CreateEquipmentRequest(
            "Hold test equipment",
            EquipmentCategory.FURNITURE,
            null))
        .response()
        .id();
    seedStockBalance(equipmentId, warehouse, 4);
    UUID shipment = UUID.randomUUID();
    UUID line = UUID.randomUUID();
    UUID acquireKey = UUID.randomUUID();
    AcquireLogisticsEquipmentHoldRequest request = new AcquireLogisticsEquipmentHoldRequest(
        equipmentId, warehouse, shipment, line, 3L, 0L);

    var acquired = service.acquireLogisticsHold(subject, acquireKey, request);
    var acquireReplay = service.acquireLogisticsHold(subject, acquireKey, request);
    assertThat(acquired.replayed()).isFalse();
    assertThat(acquireReplay.replayed()).isTrue();
    assertThat(jdbc.queryForMap(
        "select owner_type,owner_id from equipment_allocation_hold where id=?",
        acquired.response().holdId()))
        .containsEntry("owner_type", "LOGISTICS_SHIPMENT")
        .containsEntry("owner_id", shipment + ":" + line);
    assertThatThrownBy(() -> service.commitLogisticsHold(
        subject,
        UUID.randomUUID(),
        acquired.response().holdId(),
        new LogisticsEquipmentHoldCommandRequest(
            acquired.response().version(),
            shipment,
            UUID.randomUUID())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("another logistics shipment line");
    assertThatThrownBy(() -> service.renewLogisticsHold(
        subject,
        UUID.randomUUID(),
        acquired.response().holdId(),
        new LogisticsEquipmentHoldCommandRequest(
            acquired.response().version() + 1,
            shipment,
            line)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("changed concurrently");

    var committed = service.commitLogisticsHold(
        subject,
        UUID.randomUUID(),
        acquired.response().holdId(),
        new LogisticsEquipmentHoldCommandRequest(
            acquired.response().version(),
            shipment,
            line));
    UUID releaseKey = UUID.randomUUID();
    var released = service.releaseLogisticsHold(
        subject,
        releaseKey,
        committed.response().holdId(),
        new LogisticsEquipmentHoldCommandRequest(
            committed.response().version(),
            shipment,
            line));
    var releaseReplay = service.releaseLogisticsHold(
        subject,
        releaseKey,
        committed.response().holdId(),
        new LogisticsEquipmentHoldCommandRequest(
            committed.response().version(),
            shipment,
            line));
    assertThat(committed.response().state()).isEqualTo("COMMITTED");
    assertThat(released.response().state()).isEqualTo("RELEASED");
    assertThat(releaseReplay.replayed()).isTrue();
    assertThat(releaseReplay.response()).isEqualTo(released.response());
  }

  @Test
  @Transactional
  void movementReservationFencesCabinSourceUntilWorkerExecutionThenWritesLedger() {
    UUID subject = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    RentalItemResponse cabin = rental(subject, warehouse, RentalItemStatus.FREE);
    UUID equipmentId = service.createEquipment(
        subject,
        UUID.randomUUID(),
        new CreateEquipmentRequest(
            "Worker table",
            EquipmentCategory.FURNITURE,
            null))
        .response()
        .id();
    seedStockBalance(equipmentId, warehouse, 10);
    service.transfer(
        subject,
        UUID.randomUUID(),
        new TransferEquipmentRequest(
            equipmentId,
            warehouse,
            null,
            BalanceLocationKind.STOCK,
            0L,
            warehouse,
            cabin.id(),
            BalanceLocationKind.CABIN_NON_RENTED,
            0L,
            4L));
    long cabinVersion = jdbc.queryForObject(
        """
        select version from equipment_balance
        where equipment_id=? and warehouse_id=? and rental_item_id=?
          and location_kind='CABIN_NON_RENTED'
        """,
        Long.class,
        equipmentId,
        warehouse,
        cabin.id());
    UUID movementId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    var reserved = service.acquireLogisticsEquipmentMovementReservation(
        subject,
        UUID.randomUUID(),
        new AcquireLogisticsEquipmentMovementReservationRequest(
            movementId,
            lineId,
            LogisticsEquipmentMovementPurpose.ALLOCATABLE_REBALANCE,
            equipmentId,
            warehouse,
            cabin.id(),
            BalanceLocationKind.CABIN_NON_RENTED,
            cabinVersion,
            2L,
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)))
        .response();

    assertThat(reserved.equipmentId()).isEqualTo(equipmentId);
    assertThat(reserved.equipmentName()).isEqualTo("Worker table");
    assertThat(reserved.sourceRentalItemId()).isEqualTo(cabin.id());
    assertThat(service.equipmentTotals(equipmentId, warehouse).balances())
        .filteredOn(balance -> cabin.id().equals(balance.rentalItemId()))
        .singleElement()
        .satisfies(
            balance -> {
              assertThat(balance.quantity()).isEqualTo(4L);
              assertThat(balance.activeHeldQuantity()).isEqualTo(2L);
              assertThat(balance.availableStock()).isEqualTo(2L);
            });
    assertThatThrownBy(() -> service.transfer(
        subject,
        UUID.randomUUID(),
        new TransferEquipmentRequest(
            equipmentId,
            warehouse,
            cabin.id(),
            BalanceLocationKind.CABIN_NON_RENTED,
            cabinVersion,
            warehouse,
            null,
            BalanceLocationKind.STOCK,
            1L,
            3L)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("reserved");

    UUID executionKey = UUID.randomUUID();
    ExecuteLogisticsEquipmentMovementReservationsRequest execution =
        new ExecuteLogisticsEquipmentMovementReservationsRequest(
            movementId,
            List.of(new ExecuteLogisticsEquipmentMovementReservationLine(
                reserved.reservationId(),
                reserved.version(),
                lineId,
                warehouse,
                null,
                BalanceLocationKind.STOCK)));
    var executed = service.executeLogisticsEquipmentMovementReservations(
        subject, executionKey, execution);
    var replayed = service.executeLogisticsEquipmentMovementReservations(
        subject, executionKey, execution);

    assertThat(executed.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(executed.response());
    assertThat(executed.response().lines()).singleElement().satisfies(line -> {
      assertThat(line.reservationId()).isEqualTo(reserved.reservationId());
      assertThat(line.reservationVersion()).isEqualTo(reserved.version() + 1);
      assertThat(line.movement().kind()).isEqualTo("CABIN_TO_STOCK");
      assertThat(line.movement().quantity()).isEqualTo(2L);
    });
    assertThat(jdbc.queryForObject(
        "select state from equipment_allocation_hold where id=?",
        String.class,
        reserved.reservationId())).isEqualTo("EXECUTED");
    assertThat(jdbc.queryForObject(
        """
        select quantity from equipment_balance
        where equipment_id=? and warehouse_id=? and rental_item_id=?
          and location_kind='CABIN_NON_RENTED'
        """,
        Long.class,
        equipmentId,
        warehouse,
        cabin.id())).isEqualTo(2L);
    assertThat(replay.rebuildAndVerify().aggregateCount()).isGreaterThanOrEqualTo(6);
  }

  @Test
  @Transactional
  void movementReservationTransfersFurnitureStockBetweenWarehouses() {
    UUID subject = UUID.randomUUID();
    UUID origin = UUID.randomUUID();
    UUID destination = UUID.randomUUID();
    UUID equipmentId = service.createEquipment(
        subject,
        UUID.randomUUID(),
        new CreateEquipmentRequest(
            "Transfer stock table",
            EquipmentCategory.FURNITURE,
            null))
        .response()
        .id();
    seedStockBalance(equipmentId, origin, 5);
    UUID movementId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    var reserved = service.acquireLogisticsEquipmentMovementReservation(
        subject,
        UUID.randomUUID(),
        new AcquireLogisticsEquipmentMovementReservationRequest(
            movementId,
            lineId,
            LogisticsEquipmentMovementPurpose.ALLOCATABLE_REBALANCE,
            equipmentId,
            origin,
            null,
            BalanceLocationKind.STOCK,
            0L,
            2L,
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)))
        .response();

    service.executeLogisticsEquipmentMovementReservations(
        subject,
        UUID.randomUUID(),
        new ExecuteLogisticsEquipmentMovementReservationsRequest(
            movementId,
            List.of(new ExecuteLogisticsEquipmentMovementReservationLine(
                reserved.reservationId(),
                reserved.version(),
                lineId,
                destination,
                null,
                BalanceLocationKind.STOCK))));

    assertThat(jdbc.queryForObject(
        "select quantity from equipment_balance where equipment_id=? and warehouse_id=? and location_kind='STOCK'",
        Long.class,
        equipmentId,
        origin)).isEqualTo(3L);
    assertThat(jdbc.queryForObject(
        "select quantity from equipment_balance where equipment_id=? and warehouse_id=? and location_kind='STOCK'",
        Long.class,
        equipmentId,
        destination)).isEqualTo(2L);
  }

  @Test
  @Transactional
  void returnEquipmentReceiptIncreasesWarehouseFurnitureStockExactlyOnce() {
    UUID subject = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    UUID equipmentId = service.createEquipment(
        subject,
        UUID.randomUUID(),
        new CreateEquipmentRequest(
            "Returned furniture chair",
            EquipmentCategory.FURNITURE,
            null))
        .response()
        .id();
    LogisticsReturnEquipmentReceiptRequest request =
        new LogisticsReturnEquipmentReceiptRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            warehouse,
            List.of(new LogisticsReturnEquipmentReceiptLine(equipmentId, 3L)));
    UUID key = UUID.randomUUID();

    var received = service.receiveLogisticsReturnEquipment(subject, key, request);
    var replayed = service.receiveLogisticsReturnEquipment(subject, key, request);

    assertThat(received.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(received.response());
    assertThat(jdbc.queryForObject(
        "select quantity from equipment_balance where equipment_id=? and warehouse_id=? and location_kind='STOCK'",
        Long.class,
        equipmentId,
        warehouse)).isEqualTo(3L);
    assertThat(jdbc.queryForObject(
        "select count(*) from logistics_return_equipment_receipt where return_id=? and return_line_id=?",
        Integer.class,
        request.returnId(),
        request.returnLineId())).isOne();
  }

  @Test
  void expiredLineRollsBackTheEntireReservedMovementBatch() {
    UUID subject = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    RentalItemResponse firstTarget = rental(subject, warehouse, RentalItemStatus.FREE);
    RentalItemResponse secondTarget = rental(subject, warehouse, RentalItemStatus.FREE);
    UUID equipmentId = service.createEquipment(
        subject,
        UUID.randomUUID(),
        new CreateEquipmentRequest(
            "Batch chair",
            EquipmentCategory.FURNITURE,
            null))
        .response()
        .id();
    new TransactionTemplate(transactions).executeWithoutResult(
        ignored -> seedStockBalance(equipmentId, warehouse, 10));
    UUID movementId = UUID.randomUUID();
    var first = service.acquireLogisticsEquipmentMovementReservation(
        subject,
        UUID.randomUUID(),
        new AcquireLogisticsEquipmentMovementReservationRequest(
            movementId,
            UUID.randomUUID(),
            LogisticsEquipmentMovementPurpose.ALLOCATABLE_REBALANCE,
            equipmentId,
            warehouse,
            null,
            BalanceLocationKind.STOCK,
            0L,
            2L,
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)))
        .response();
    var second = service.acquireLogisticsEquipmentMovementReservation(
        subject,
        UUID.randomUUID(),
        new AcquireLogisticsEquipmentMovementReservationRequest(
            movementId,
            UUID.randomUUID(),
            LogisticsEquipmentMovementPurpose.ALLOCATABLE_REBALANCE,
            equipmentId,
            warehouse,
            null,
            BalanceLocationKind.STOCK,
            0L,
            2L,
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)))
        .response();
    jdbc.update(
        "update equipment_allocation_hold set expires_at=clock_timestamp() - interval '1 minute' where id=?",
        second.reservationId());

    assertThatThrownBy(() -> service.executeLogisticsEquipmentMovementReservations(
        subject,
        UUID.randomUUID(),
        new ExecuteLogisticsEquipmentMovementReservationsRequest(
            movementId,
            List.of(
                new ExecuteLogisticsEquipmentMovementReservationLine(
                    first.reservationId(),
                    first.version(),
                    first.lineId(),
                    warehouse,
                    firstTarget.id(),
                    BalanceLocationKind.CABIN_NON_RENTED),
                new ExecuteLogisticsEquipmentMovementReservationLine(
                    second.reservationId(),
                    second.version(),
                    second.lineId(),
                    warehouse,
                    secondTarget.id(),
                    BalanceLocationKind.CABIN_NON_RENTED)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("changed concurrently");

    assertThat(jdbc.queryForObject(
        "select quantity from equipment_balance where equipment_id=? and warehouse_id=? and location_kind='STOCK'",
        Long.class,
        equipmentId,
        warehouse)).isEqualTo(10L);
    assertThat(jdbc.queryForObject(
        "select count(*) from equipment_movement where equipment_id=?",
        Integer.class,
        equipmentId)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from equipment_balance where equipment_id=? and rental_item_id in (?, ?)",
        Integer.class,
        equipmentId,
        firstTarget.id(),
        secondTarget.id())).isZero();
  }

  private RentalItemResponse rental(UUID subject, UUID warehouseId, RentalItemStatus status) {
    RentalItemResponse created = service.createRentalItem(
        subject,
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            warehouseId,
            "LOG-CABIN-" + UUID.randomUUID(),
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            null,
            List.of(),
            false,
            Map.of(),
            List.of()))
        .response();
    if (status == RentalItemStatus.FREE) {
      return created;
    }
    if (status == RentalItemStatus.RENTED) {
      UUID documentId = UUID.randomUUID();
      UUID lineId = UUID.randomUUID();
      var lease = service.acquireLogisticsLease(
          subject,
          UUID.randomUUID(),
          new AcquireLogisticsOperationLeaseRequest(
              created.id(), LOGISTICS_SHIPMENT, documentId, lineId, created.version()))
          .response();
      service.applyLogisticsEffect(
          subject,
          UUID.randomUUID(),
          created.id(),
          effect(
              created.version(),
              SHIPMENT_CONFIRM,
              lease.leaseId(),
              lease.fencingToken(),
              LOGISTICS_SHIPMENT,
              documentId,
              lineId,
              null));
      service.releaseLogisticsLease(
          subject,
          UUID.randomUUID(),
          lease.leaseId(),
          new LogisticsLeaseCommandRequest(
              lease.version(),
              lease.fencingToken(),
              LOGISTICS_SHIPMENT,
              documentId,
              lineId));
      return service.rentalItem(created.id());
    }
    if (status == RentalItemStatus.REPAIR || status == RentalItemStatus.CAPITAL_REPAIR) {
      UUID ownerId = UUID.randomUUID();
      var lease = service.acquireMaintenanceLease(
          subject,
          UUID.randomUUID(),
          new AcquireMaintenanceOperationLeaseRequest(
              created.id(),
              MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
              ownerId,
              created.version()))
          .response();
      service.maintenanceFencedStatus(
          subject,
          UUID.randomUUID(),
          created.id(),
          new MaintenanceFencedStatusRequest(
              created.version(),
              status == RentalItemStatus.REPAIR
                  ? MaintenanceStatusAction.QUEUE_FOR_REPAIR
                  : MaintenanceStatusAction.QUEUE_FOR_CAPITAL_REPAIR,
              lease.id(),
              lease.fencingToken(),
              MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
              ownerId,
              null));
      service.releaseMaintenanceLease(
          subject,
          UUID.randomUUID(),
          lease.id(),
          new ReleaseMaintenanceOperationLeaseRequest(
              lease.version(),
              lease.fencingToken(),
              MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
              ownerId));
      return service.rentalItem(created.id());
    }
    return service.updateStatus(
        created.id(),
        new UpdateStatusRequest(created.version(), status));
  }

  private void seedStockBalance(UUID equipmentId, UUID warehouseId, long quantity) {
    UUID balanceId = UUID.randomUUID();
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
    events.initialize(
        AssetAggregateType.EQUIPMENT_BALANCE,
        balanceId,
        0,
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        Map.of(
            "balanceId", balanceId.toString(),
            "equipmentId", equipmentId.toString(),
            "warehouseId", warehouseId.toString(),
            "locationKind", BalanceLocationKind.STOCK.name(),
            "quantity", quantity),
        Map.of(
            "balanceId", balanceId.toString(),
            "version", 0,
            "locationKind", BalanceLocationKind.STOCK.name(),
            "quantity", quantity));
  }

  private static LogisticsFencedEffectRequest effect(
      long expectedVersion,
      dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsRentalItemAction action,
      UUID leaseId,
      long fencingToken,
      dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID destinationWarehouseId) {
    TransferAssetStatus transferAssetStatus =
        action == TRANSFER_DEPART || action == TRANSFER_ARRIVE
            ? TransferAssetStatus.FREE
            : null;
    return effect(
        expectedVersion,
        action,
        leaseId,
        fencingToken,
        ownerType,
        documentId,
        lineId,
        destinationWarehouseId,
        transferAssetStatus);
  }

  private static LogisticsFencedEffectRequest effect(
      long expectedVersion,
      dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsRentalItemAction action,
      UUID leaseId,
      long fencingToken,
      dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID destinationWarehouseId,
      TransferAssetStatus transferAssetStatus) {
    return new LogisticsFencedEffectRequest(
        expectedVersion,
        action,
        leaseId,
        fencingToken,
        ownerType,
        documentId,
        lineId,
        destinationWarehouseId,
        transferAssetStatus);
  }
}
