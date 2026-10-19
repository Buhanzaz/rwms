package dev.buhanzaz.rwms.asset;

import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireMaintenanceOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentBalanceResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceFencedStatusRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceFurnitureCustodyClaim;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceFurniturePendingReturn;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceStatusAction;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.OperationLeaseResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ReturnMaintenanceFurnitureCustodyToStockRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateStatusRequest;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.MaintenanceFurnitureCustodyService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Regression boundary for the unified estimate/direct-repair custody move.
 * Furniture leaves the cabin exactly once, but only an approved property
 * disposition can ever put it in a terminal balance.
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
class MaintenanceFurnitureLossIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired AssetService service;
  @Autowired MaintenanceFurnitureCustodyService custody;
  @Autowired AssetEventStore events;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @MockitoBean WarehouseRegistryClient warehouses;

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
  void estimateAndDirectRepairSelectTheSamePendingReturnEffectWithoutTerminalSink() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    EquipmentResponse estimateFurniture = createFurniture(subjectId);
    EquipmentResponse repairFurniture = createFurniture(subjectId);

    UUID estimateId = UUID.randomUUID();
    RentalItemResponse estimateCabin = createFreeRental(subjectId, warehouseId);
    attach(subjectId, warehouseId, estimateCabin.id(), estimateFurniture.id(), 3);
    OperationLeaseResponse estimateLease = acquireLease(
        subjectId, estimateCabin, MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE, estimateId);
    MaintenanceFurniturePendingReturn estimateLine = pendingLine(
        estimateFurniture.id(), warehouseId, estimateCabin.id());
    UUID estimateKey = UUID.randomUUID();

    var estimateQueued = service.maintenanceFencedStatus(
        subjectId,
        estimateKey,
        estimateCabin.id(),
        queueRequest(
            estimateCabin,
            estimateLease,
            MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
            estimateId,
            List.of(estimateLine)));
    var estimateReplay = service.maintenanceFencedStatus(
        subjectId,
        estimateKey,
        estimateCabin.id(),
        queueRequest(
            estimateCabin,
            estimateLease,
            MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
            estimateId,
            List.of(estimateLine)));

    UUID repairId = UUID.randomUUID();
    RentalItemResponse repairCabin = createFreeRental(subjectId, warehouseId);
    attach(subjectId, warehouseId, repairCabin.id(), repairFurniture.id(), 2);
    OperationLeaseResponse repairLease = acquireLease(
        subjectId, repairCabin, MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, repairId);
    MaintenanceFurniturePendingReturn repairLine = pendingLine(
        repairFurniture.id(), warehouseId, repairCabin.id());
    var repairQueued = service.maintenanceFencedStatus(
        subjectId,
        UUID.randomUUID(),
        repairCabin.id(),
        queueRequest(
            repairCabin,
            repairLease,
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
            repairId,
            List.of(repairLine)));

    assertThat(estimateQueued.response().status()).isEqualTo(RentalItemStatus.REPAIR);
    assertThat(repairQueued.response().status()).isEqualTo(RentalItemStatus.REPAIR);
    assertThat(estimateReplay.replayed()).isTrue();
    assertThat(estimateReplay.response()).isEqualTo(estimateQueued.response());
    assertThat(service.rentalItem(estimateCabin.id()).contents()).isEmpty();
    assertThat(service.rentalItem(repairCabin.id()).contents()).isEmpty();

    List<MaintenanceFurnitureCustodyClaim> estimateClaims = custody.unresolvedClaims(
        MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE, estimateId);
    List<MaintenanceFurnitureCustodyClaim> repairClaims = custody.unresolvedClaims(
        MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, repairId);
    assertThat(estimateClaims).singleElement().satisfies(claim -> {
      assertThat(claim.quantity()).isEqualTo(3);
      assertThat(claim.availableForDispositionQuantity()).isEqualTo(3);
      assertThat(claim.sourceBalanceVersion()).isEqualTo(estimateLine.expectedSourceBalanceVersion());
    });
    assertThat(repairClaims).singleElement().satisfies(claim -> {
      assertThat(claim.quantity()).isEqualTo(2);
      assertThat(claim.availableForDispositionQuantity()).isEqualTo(2);
      assertThat(claim.sourceBalanceVersion()).isEqualTo(repairLine.expectedSourceBalanceVersion());
    });

    var estimateTotals = service.equipmentTotals(estimateFurniture.id(), warehouseId);
    var repairTotals = service.equipmentTotals(repairFurniture.id(), warehouseId);
    assertThat(estimateTotals.nonRentedCabinQuantity()).isZero();
    assertThat(repairTotals.nonRentedCabinQuantity()).isZero();
    assertThat(estimateTotals.lostQuantity()).isZero();
    assertThat(repairTotals.writtenOffQuantity()).isZero();
    assertThat(terminalMovementCount(estimateFurniture.id())).isZero();
    assertThat(terminalMovementCount(repairFurniture.id())).isZero();
    assertThat(custodyEventCount("DISPOSITION_APPLIED")).isZero();

    assertThatThrownBy(
        () ->
            service.maintenanceFencedStatus(
                subjectId,
                estimateKey,
                estimateCabin.id(),
                queueRequest(
                    estimateCabin,
                    estimateLease,
                    MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
                    estimateId,
                    List.of(new MaintenanceFurniturePendingReturn(
                        estimateFurniture.id(), estimateLine.expectedSourceBalanceVersion(), 2)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Idempotency-Key");
  }

  @Test
  void returnToStockClosesSelectedQuantityWithPermanentReplayAndNoTerminalMovement() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    EquipmentResponse furniture = createFurniture(subjectId);
    RentalItemResponse cabin = createFreeRental(subjectId, warehouseId);
    attach(subjectId, warehouseId, cabin.id(), furniture.id(), 3);
    OperationLeaseResponse lease = acquireLease(
        subjectId, cabin, MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, repairId);
    MaintenanceFurniturePendingReturn selected = pendingLine(furniture.id(), warehouseId, cabin.id());
    service.maintenanceFencedStatus(
        subjectId,
        UUID.randomUUID(),
        cabin.id(),
        queueRequest(
            cabin,
            lease,
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
            repairId,
            List.of(selected)));
    MaintenanceFurnitureCustodyClaim claim = custody.unresolvedClaims(
        MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, repairId).getFirst();
    EquipmentBalanceResponse stockBefore = stockBalance(furniture.id(), warehouseId);
    UUID commandKey = UUID.randomUUID();
    ReturnMaintenanceFurnitureCustodyToStockRequest request =
        new ReturnMaintenanceFurnitureCustodyToStockRequest(
            claim.custodyVersion(),
            stockBefore.version(),
            2,
            UUID.randomUUID());

    clearInvocations(warehouses);
    var returned = custody.returnToStock(subjectId, commandKey, claim.id(), request);
    var replayed = custody.returnToStock(subjectId, commandKey, claim.id(), request);

    assertThat(returned.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    verify(warehouses, times(1)).requireIncoming(warehouseId);
    assertThat(replayed.response()).isEqualTo(returned.response());
    assertThat(returned.response().stockBalanceVersion()).isEqualTo(stockBefore.version() + 1);
    assertThat(returned.response().stockQuantity()).isEqualTo(2);
    MaintenanceFurnitureCustodyClaim remaining = custody.unresolvedClaims(
        MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, repairId).getFirst();
    assertThat(remaining.returnedToStockQuantity()).isEqualTo(2);
    assertThat(remaining.unresolvedQuantity()).isEqualTo(1);
    assertThat(remaining.availableForDispositionQuantity()).isEqualTo(1);
    assertThat(service.equipmentTotals(furniture.id(), warehouseId).stockQuantity()).isEqualTo(2);
    assertThat(service.equipmentTotals(furniture.id(), warehouseId).lostQuantity()).isZero();
    assertThat(terminalMovementCount(furniture.id())).isZero();

    assertThatThrownBy(
        () ->
            custody.returnToStock(
                subjectId,
                commandKey,
                claim.id(),
                new ReturnMaintenanceFurnitureCustodyToStockRequest(
                    claim.custodyVersion(),
                    stockBefore.version(),
                    2,
                    UUID.randomUUID())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("idempotency");
  }

  private EquipmentResponse createFurniture(UUID subjectId) {
    return service.createEquipment(
        subjectId,
        UUID.randomUUID(),
        new CreateEquipmentRequest(
            "Furniture " + UUID.randomUUID().toString().substring(0, 8),
            EquipmentCategory.FURNITURE,
            null)).response();
  }

  private RentalItemResponse createFreeRental(UUID subjectId, UUID warehouseId) {
    RentalItemResponse created = service.createRentalItem(
        subjectId,
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            warehouseId,
            "cabin" + UUID.randomUUID().toString().replace("-", ""),
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

  private OperationLeaseResponse acquireLease(
      UUID subjectId,
      RentalItemResponse rental,
      MaintenanceLeaseOwnerType ownerType,
      UUID ownerId) {
    return service.acquireMaintenanceLease(
        subjectId,
        UUID.randomUUID(),
        new AcquireMaintenanceOperationLeaseRequest(
            rental.id(), ownerType, ownerId, rental.version())).response();
  }

  private MaintenanceFurniturePendingReturn pendingLine(
      UUID equipmentId, UUID warehouseId, UUID rentalItemId) {
    EquipmentBalanceResponse balance = service.equipmentTotals(equipmentId, warehouseId).balances().stream()
        .filter(value -> rentalItemId.equals(value.rentalItemId()))
        .filter(value -> value.locationKind() == BalanceLocationKind.CABIN_NON_RENTED)
        .findFirst()
        .orElseThrow();
    return new MaintenanceFurniturePendingReturn(equipmentId, balance.version(), balance.quantity());
  }

  private MaintenanceFencedStatusRequest queueRequest(
      RentalItemResponse rental,
      OperationLeaseResponse lease,
      MaintenanceLeaseOwnerType ownerType,
      UUID ownerId,
      List<MaintenanceFurniturePendingReturn> lines) {
    return new MaintenanceFencedStatusRequest(
        rental.version(),
        MaintenanceStatusAction.QUEUE_FOR_REPAIR,
        lease.id(),
        lease.fencingToken(),
        ownerType,
        ownerId,
        null,
        lines);
  }

  private EquipmentBalanceResponse stockBalance(UUID equipmentId, UUID warehouseId) {
    return service.equipmentTotals(equipmentId, warehouseId).balances().stream()
        .filter(value -> value.rentalItemId() == null)
        .filter(value -> value.locationKind() == BalanceLocationKind.STOCK)
        .findFirst()
        .orElseThrow();
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

  private int terminalMovementCount(UUID equipmentId) {
    Integer value = jdbc.queryForObject(
        """
        select count(*) from equipment_movement
        where equipment_id=? and movement_kind in ('LOSS','WRITE_OFF')
        """,
        Integer.class,
        equipmentId);
    return value == null ? 0 : value;
  }

  private int custodyEventCount(String eventType) {
    Integer value = jdbc.queryForObject(
        "select count(*) from maintenance_furniture_custody_event where event_type=?",
        Integer.class,
        eventType);
    return value == null ? 0 : value;
  }
}
