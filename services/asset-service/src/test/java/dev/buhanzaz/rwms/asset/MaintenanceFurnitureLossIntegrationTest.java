package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireMaintenanceOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceFencedStatusRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceFurnitureLoss;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceStatusAction;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.OperationLeaseResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateStatusRequest;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetService;
import java.util.ArrayList;
import java.util.Comparator;
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
class MaintenanceFurnitureLossIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired AssetService service;
  @Autowired AssetEventStore events;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

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
  void queueMovesFurnitureToLostOnceAndReturnsTheOriginalReplay() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    EquipmentResponse furniture = createEquipment(subjectId, EquipmentCategory.FURNITURE);
    RentalItemResponse rental = createFreeRental(subjectId, warehouseId);
    attach(subjectId, warehouseId, rental.id(), furniture.id(), 4);
    OperationLeaseResponse lease = acquireEstimateLease(subjectId, rental, estimateId);
    MaintenanceFencedStatusRequest request = queueRequest(
        rental,
        lease,
        estimateId,
        List.of(new MaintenanceFurnitureLoss(furniture.id(), 2)));
    UUID idempotencyKey = UUID.randomUUID();

    var queued = service.maintenanceFencedStatus(
        subjectId, idempotencyKey, rental.id(), request);
    var replayed = service.maintenanceFencedStatus(
        subjectId, idempotencyKey, rental.id(), request);

    assertThat(queued.replayed()).isFalse();
    assertThat(queued.response().status()).isEqualTo(RentalItemStatus.REPAIR);
    assertThat(queued.response().version()).isEqualTo(rental.version() + 1);
    assertThat(queued.response().contents())
        .singleElement()
        .satisfies(content -> {
          assertThat(content.equipmentId()).isEqualTo(furniture.id());
          assertThat(content.quantity()).isEqualTo(2);
        });
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(queued.response());

    var totals = service.equipmentTotals(furniture.id(), warehouseId);
    assertThat(totals.nonRentedCabinQuantity()).isEqualTo(2);
    assertThat(totals.lostQuantity()).isEqualTo(2);
    assertThat(lossMovementCount(furniture.id())).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        """
        select count(*) from equipment_movement_ledger ledger
        join equipment_movement movement on movement.id=ledger.movement_id
        where movement.equipment_id=? and movement.movement_kind='LOSS'
        """,
        Integer.class,
        furniture.id())).isEqualTo(2);
    assertThat(jdbc.queryForObject(
        """
        select count(*) from domain_event event
        where event.event_type=?
          and event.aggregate_id in (
            select movement.id::text from equipment_movement movement
            where movement.equipment_id=? and movement.movement_kind='LOSS')
        """,
        Integer.class,
        AssetEventType.EQUIPMENT_LOST.value(),
        furniture.id())).isEqualTo(1);
  }

  @Test
  void rejectsStaleInactiveAndNonFurnitureReferencesAndInvalidCommandShapes() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    RentalItemResponse rental = createFreeRental(subjectId, warehouseId);
    EquipmentResponse furniture = createEquipment(subjectId, EquipmentCategory.FURNITURE);
    EquipmentResponse electrical = createEquipment(subjectId, EquipmentCategory.ELECTRICAL);
    EquipmentResponse inactive = createEquipment(subjectId, EquipmentCategory.FURNITURE);
    attach(subjectId, warehouseId, rental.id(), furniture.id(), 1);
    attach(subjectId, warehouseId, rental.id(), electrical.id(), 1);
    attach(subjectId, warehouseId, rental.id(), inactive.id(), 1);
    inactive = service.updateEquipment(
        inactive.id(),
        new UpdateEquipmentRequest(
            inactive.version(),
            inactive.name(),
            inactive.category(),
            false,
            inactive.comment()));
    OperationLeaseResponse lease = acquireEstimateLease(subjectId, rental, estimateId);

    EquipmentResponse finalInactive = inactive;
    assertThatThrownBy(() -> service.maintenanceFencedStatus(
        subjectId,
        UUID.randomUUID(),
        rental.id(),
        queueRequest(
            rental,
            lease,
            estimateId,
            List.of(new MaintenanceFurnitureLoss(electrical.id(), 1)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("active FURNITURE");
    assertThatThrownBy(() -> service.maintenanceFencedStatus(
        subjectId,
        UUID.randomUUID(),
        rental.id(),
        queueRequest(
            rental,
            lease,
            estimateId,
            List.of(new MaintenanceFurnitureLoss(finalInactive.id(), 1)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("active FURNITURE");
    assertThatThrownBy(() -> service.maintenanceFencedStatus(
        subjectId,
        UUID.randomUUID(),
        rental.id(),
        new MaintenanceFencedStatusRequest(
            rental.version(),
            MaintenanceStatusAction.MARK_PENDING_ACCEPTANCE,
            lease.id(),
            lease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
            estimateId,
            null,
            estimateId,
            List.of(new MaintenanceFurnitureLoss(furniture.id(), 1)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("estimate-owned queue action");
    assertThatThrownBy(() -> service.maintenanceFencedStatus(
        subjectId,
        UUID.randomUUID(),
        rental.id(),
        new MaintenanceFencedStatusRequest(
            rental.version(),
            MaintenanceStatusAction.QUEUE_FOR_REPAIR,
            lease.id(),
            lease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
            estimateId,
            null,
            UUID.randomUUID(),
            List.of(new MaintenanceFurnitureLoss(furniture.id(), 1)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("matching estimateId");
    UUID anotherEstimateId = UUID.randomUUID();
    assertThatThrownBy(() -> service.maintenanceFencedStatus(
        subjectId,
        UUID.randomUUID(),
        rental.id(),
        new MaintenanceFencedStatusRequest(
            rental.version(),
            MaintenanceStatusAction.QUEUE_FOR_REPAIR,
            lease.id(),
            lease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
            anotherEstimateId,
            null,
            anotherEstimateId,
            List.of(new MaintenanceFurnitureLoss(furniture.id(), 1)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("another maintenance owner");
    assertThatThrownBy(() -> service.maintenanceFencedStatus(
        subjectId,
        UUID.randomUUID(),
        rental.id(),
        queueRequest(
            rental,
            lease,
            estimateId,
            List.of(
                new MaintenanceFurnitureLoss(furniture.id(), 1),
                new MaintenanceFurnitureLoss(furniture.id(), 1)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique equipment");

    UUID directRepairId = UUID.randomUUID();
    RentalItemResponse directRepairRental = createFreeRental(subjectId, warehouseId);
    OperationLeaseResponse directRepairLease = acquireRepairLease(
        subjectId, directRepairRental, directRepairId);
    assertThatThrownBy(() -> service.maintenanceFencedStatus(
        subjectId,
        UUID.randomUUID(),
        directRepairRental.id(),
        new MaintenanceFencedStatusRequest(
            directRepairRental.version(),
            MaintenanceStatusAction.QUEUE_FOR_REPAIR,
            directRepairLease.id(),
            directRepairLease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
            directRepairId,
            null,
            estimateId,
            List.of(new MaintenanceFurnitureLoss(furniture.id(), 1)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("estimate-owned queue action");

    assertThat(service.rentalItem(rental.id()).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(lossMovementCount(furniture.id())).isZero();
    assertThat(lossMovementCount(electrical.id())).isZero();
    assertThat(lossMovementCount(inactive.id())).isZero();
  }

  @Test
  void insufficientLaterLossRollsBackEveryBalanceMovementAndStatusChange() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    RentalItemResponse rental = createFreeRental(subjectId, warehouseId);
    List<EquipmentResponse> furniture = new ArrayList<>(List.of(
        createEquipment(subjectId, EquipmentCategory.FURNITURE),
        createEquipment(subjectId, EquipmentCategory.FURNITURE)));
    furniture.sort(Comparator.comparing(item -> item.id().toString()));
    attach(subjectId, warehouseId, rental.id(), furniture.get(0).id(), 2);
    attach(subjectId, warehouseId, rental.id(), furniture.get(1).id(), 1);
    OperationLeaseResponse lease = acquireEstimateLease(subjectId, rental, estimateId);
    UUID idempotencyKey = UUID.randomUUID();

    assertThatThrownBy(() -> service.maintenanceFencedStatus(
        subjectId,
        idempotencyKey,
        rental.id(),
        queueRequest(
            rental,
            lease,
            estimateId,
            List.of(
                new MaintenanceFurnitureLoss(furniture.get(0).id(), 1),
                new MaintenanceFurnitureLoss(furniture.get(1).id(), 2)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("insufficient furniture");

    assertUnchangedAfterFailedLoss(rental, warehouseId, furniture.get(0), 2);
    assertUnchangedAfterFailedLoss(rental, warehouseId, furniture.get(1), 1);
    assertThat(jdbc.queryForObject(
        "select count(*) from asset_idempotency_record where idempotency_key=?",
        Integer.class,
        idempotencyKey)).isZero();
  }

  @Test
  void absentLaterBalanceRollsBackThePreparedLostBucketAndStatusChange() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    RentalItemResponse rental = createFreeRental(subjectId, warehouseId);
    List<EquipmentResponse> furniture = new ArrayList<>(List.of(
        createEquipment(subjectId, EquipmentCategory.FURNITURE),
        createEquipment(subjectId, EquipmentCategory.FURNITURE)));
    furniture.sort(Comparator.comparing(item -> item.id().toString()));
    attach(subjectId, warehouseId, rental.id(), furniture.get(0).id(), 1);
    OperationLeaseResponse lease = acquireEstimateLease(subjectId, rental, estimateId);

    assertThatThrownBy(() -> service.maintenanceFencedStatus(
        subjectId,
        UUID.randomUUID(),
        rental.id(),
        queueRequest(
            rental,
            lease,
            estimateId,
            List.of(
                new MaintenanceFurnitureLoss(furniture.get(0).id(), 1),
                new MaintenanceFurnitureLoss(furniture.get(1).id(), 1)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("does not contain");

    assertUnchangedAfterFailedLoss(rental, warehouseId, furniture.get(0), 1);
    assertUnchangedAfterFailedLoss(rental, warehouseId, furniture.get(1), 0);
    assertThat(jdbc.queryForObject(
        """
        select count(*) from equipment_balance
        where equipment_id=? and warehouse_id=? and rental_item_id is null
          and location_kind='LOST'
        """,
        Integer.class,
        furniture.get(0).id(),
        warehouseId)).isZero();
  }

  private EquipmentResponse createEquipment(UUID subjectId, EquipmentCategory category) {
    String name = "Equipment " + category.name() + ' '
        + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    return service.createEquipment(
        subjectId,
        UUID.randomUUID(),
        new CreateEquipmentRequest(name, category, null)).response();
  }

  private RentalItemResponse createFreeRental(UUID subjectId, UUID warehouseId) {
    RentalItemResponse created = service.createRentalItem(
        subjectId,
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            warehouseId,
            "cabin-" + UUID.randomUUID(),
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            null,
            List.of(),
            false,
            Map.of(),
            List.of())).response();
    return service.updateStatus(
        created.id(), new UpdateStatusRequest(created.version(), RentalItemStatus.FREE));
  }

  private void attach(
      UUID subjectId,
      UUID warehouseId,
      UUID rentalItemId,
      UUID equipmentId,
      long quantity) {
    seedStockBalance(equipmentId, warehouseId, quantity);
    service.transfer(
        subjectId,
        UUID.randomUUID(),
        new TransferEquipmentRequest(
            equipmentId,
            warehouseId,
            null,
            BalanceLocationKind.STOCK,
            0L,
            warehouseId,
            rentalItemId,
            BalanceLocationKind.CABIN_NON_RENTED,
            0L,
            quantity));
  }

  private OperationLeaseResponse acquireEstimateLease(
      UUID subjectId, RentalItemResponse rental, UUID estimateId) {
    return service.acquireMaintenanceLease(
        subjectId,
        UUID.randomUUID(),
        new AcquireMaintenanceOperationLeaseRequest(
            rental.id(),
            MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
            estimateId,
            rental.version())).response();
  }

  private OperationLeaseResponse acquireRepairLease(
      UUID subjectId, RentalItemResponse rental, UUID repairId) {
    return service.acquireMaintenanceLease(
        subjectId,
        UUID.randomUUID(),
        new AcquireMaintenanceOperationLeaseRequest(
            rental.id(),
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
            repairId,
            rental.version())).response();
  }

  private static MaintenanceFencedStatusRequest queueRequest(
      RentalItemResponse rental,
      OperationLeaseResponse lease,
      UUID estimateId,
      List<MaintenanceFurnitureLoss> losses) {
    return new MaintenanceFencedStatusRequest(
        rental.version(),
        MaintenanceStatusAction.QUEUE_FOR_REPAIR,
        lease.id(),
        lease.fencingToken(),
        MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
        estimateId,
        null,
        estimateId,
        losses);
  }

  private void seedStockBalance(UUID equipmentId, UUID warehouseId, long quantity) {
    new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
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
    });
  }

  private void assertUnchangedAfterFailedLoss(
      RentalItemResponse rental,
      UUID warehouseId,
      EquipmentResponse furniture,
      long expectedCabinQuantity) {
    assertThat(service.rentalItem(rental.id()).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(service.rentalItem(rental.id()).version()).isEqualTo(rental.version());
    var totals = service.equipmentTotals(furniture.id(), warehouseId);
    assertThat(totals.nonRentedCabinQuantity()).isEqualTo(expectedCabinQuantity);
    assertThat(totals.lostQuantity()).isZero();
    assertThat(lossMovementCount(furniture.id())).isZero();
  }

  private int lossMovementCount(UUID equipmentId) {
    Integer count = jdbc.queryForObject(
        "select count(*) from equipment_movement where equipment_id=? and movement_kind='LOSS'",
        Integer.class,
        equipmentId);
    return count == null ? 0 : count;
  }
}
