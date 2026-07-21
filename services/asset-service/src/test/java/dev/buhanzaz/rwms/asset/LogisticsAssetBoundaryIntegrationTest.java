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

import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireLogisticsEquipmentHoldRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireLogisticsEquipmentMovementReservationRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireLogisticsOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ExecuteLogisticsEquipmentMovementReservationLine;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ExecuteLogisticsEquipmentMovementReservationsRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsEquipmentHoldCommandRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsFencedEffectRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseCommandRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateStatusRequest;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.eventing.AssetReplayVerifier;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetService;
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
        .isEqualTo(2);
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
            "LOG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
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
  void shipmentHoldUsesOpaqueOwnerAndChecksReplayCasAndOwner() {
    UUID subject = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    UUID equipmentId = service.createEquipment(
        subject,
        UUID.randomUUID(),
        new CreateEquipmentRequest(
            "HOLD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
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
            "MOVE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
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
            equipmentId,
            warehouse,
            cabin.id(),
            BalanceLocationKind.CABIN_NON_RENTED,
            cabinVersion,
            2L,
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)))
        .response();

    assertThat(reserved.equipmentCode()).startsWith("MOVE-");
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
  void expiredLineRollsBackTheEntireReservedMovementBatch() {
    UUID subject = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    RentalItemResponse firstTarget = rental(subject, warehouse, RentalItemStatus.FREE);
    RentalItemResponse secondTarget = rental(subject, warehouse, RentalItemStatus.FREE);
    UUID equipmentId = service.createEquipment(
        subject,
        UUID.randomUUID(),
        new CreateEquipmentRequest(
            "BATCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
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
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            List.of()))
        .response();
    if (status == RentalItemStatus.NEW) {
      return created;
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
    return new LogisticsFencedEffectRequest(
        expectedVersion,
        action,
        leaseId,
        fencingToken,
        ownerType,
        documentId,
        lineId,
        destinationWarehouseId);
  }
}
