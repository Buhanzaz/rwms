package dev.buhanzaz.rwms.asset;

import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireLogisticsOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireMaintenanceOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.Disposition;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.DispositionEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.FencedStatusRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceFencedStatusRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceStatusAction;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ReleaseOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RenewOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferEquipmentRequest;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetService;
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
import org.springframework.transaction.annotation.Transactional;
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
class AssetTerminalIntegrityIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired AssetService assets;
  @Autowired AssetEventStore events;
  @Autowired JdbcTemplate jdbc;

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
  void dispositionUsesTheCurrentTerminalTargetVersionAndNeverMovesTerminalBalances() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID equipmentId =
        assets
            .createEquipment(
                subjectId,
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "Terminal integrity chair", EquipmentCategory.FURNITURE, null))
            .response()
            .id();
    seedBalance(equipmentId, warehouseId, BalanceLocationKind.STOCK, 3);

    DispositionEquipmentRequest firstRequest =
        new DispositionEquipmentRequest(
            equipmentId,
            warehouseId,
            null,
            BalanceLocationKind.STOCK,
            0L,
            1L,
            Disposition.WRITE_OFF);
    UUID firstKey = UUID.randomUUID();
    var first = assets.dispose(subjectId, firstKey, firstRequest);
    var replay = assets.dispose(subjectId, firstKey, firstRequest);
    var second =
        assets.dispose(
            subjectId,
            UUID.randomUUID(),
            new DispositionEquipmentRequest(
                equipmentId,
                warehouseId,
                null,
                BalanceLocationKind.STOCK,
                1L,
                1L,
                Disposition.WRITE_OFF));
    var lost =
        assets.dispose(
            subjectId,
            UUID.randomUUID(),
            new DispositionEquipmentRequest(
                equipmentId,
                warehouseId,
                null,
                BalanceLocationKind.STOCK,
                2L,
                1L,
                Disposition.LOSS));

    assertThat(first.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(second.replayed()).isFalse();
    assertThat(lost.replayed()).isFalse();
    assertThat(balance(equipmentId, warehouseId, BalanceLocationKind.WRITTEN_OFF)).isEqualTo(2);
    assertThat(balance(equipmentId, warehouseId, BalanceLocationKind.LOST)).isEqualTo(1);

    assertThatThrownBy(
            () ->
                assets.transfer(
                    subjectId,
                    UUID.randomUUID(),
                    new TransferEquipmentRequest(
                        equipmentId,
                        warehouseId,
                        null,
                        BalanceLocationKind.WRITTEN_OFF,
                        2L,
                        warehouseId,
                        null,
                        BalanceLocationKind.STOCK,
                        3L,
                        1L)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("cannot be moved again");
    assertThatThrownBy(
            () ->
                assets.dispose(
                    subjectId,
                    UUID.randomUUID(),
                    new DispositionEquipmentRequest(
                        equipmentId,
                        warehouseId,
                        null,
                        BalanceLocationKind.LOST,
                        1L,
                        1L,
                        Disposition.WRITE_OFF)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("cannot be moved again");
  }

  @Test
  void writtenOffCabinsRejectLaterLeaseAndMaintenanceEffectsButReplayDisposition() {
    UUID subjectId = UUID.randomUUID();
    RentalItemResponse genericRental = createRental(subjectId, UUID.randomUUID());
    var genericLease =
        assets
            .acquireLease(
                subjectId,
                UUID.randomUUID(),
                new AcquireOperationLeaseRequest(
                    genericRental.id(), "TERMINAL_TEST", "generic", genericRental.version()))
            .response();
    RentalItemResponse genericWrittenOff =
        assets.fencedStatus(
            genericRental.id(),
            new FencedStatusRequest(
                genericRental.version(),
                RentalItemStatus.WRITTEN_OFF,
                genericLease.id(),
                genericLease.fencingToken()));

    assertThatThrownBy(
            () ->
                assets.fencedStatus(
                    genericRental.id(),
                    new FencedStatusRequest(
                        genericWrittenOff.version(),
                        RentalItemStatus.FREE,
                        genericLease.id(),
                        genericLease.fencingToken())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Written-off rental item");
    assertThatThrownBy(
            () ->
                assets.renewLease(
                    subjectId,
                    UUID.randomUUID(),
                    genericLease.id(),
                    new RenewOperationLeaseRequest(
                        genericLease.version(), genericLease.fencingToken())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Written-off rental item");

    var released =
        assets
            .releaseLease(
                subjectId,
                UUID.randomUUID(),
                genericLease.id(),
                new ReleaseOperationLeaseRequest(
                    genericLease.version(), genericLease.fencingToken()))
            .response();
    assertThat(released.state()).isEqualTo("RELEASED");
    assertThatThrownBy(
            () ->
                assets.acquireLease(
                    subjectId,
                    UUID.randomUUID(),
                    new AcquireOperationLeaseRequest(
                        genericRental.id(),
                        "TERMINAL_TEST",
                        "next",
                        genericWrittenOff.version())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Written-off rental item");
    assertThatThrownBy(
            () ->
                assets.acquireLogisticsLease(
                    subjectId,
                    UUID.randomUUID(),
                    new AcquireLogisticsOperationLeaseRequest(
                        genericRental.id(),
                        LogisticsLeaseOwnerType.LOGISTICS_RETURN,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        genericWrittenOff.version())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Written-off rental item");
    assertThatThrownBy(
            () ->
                assets.acquireMaintenanceLease(
                    subjectId,
                    UUID.randomUUID(),
                    new AcquireMaintenanceOperationLeaseRequest(
                        genericRental.id(),
                        MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
                        UUID.randomUUID(),
                        genericWrittenOff.version())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Written-off rental item");

    RentalItemResponse maintenanceRental = createRental(subjectId, UUID.randomUUID());
    UUID ownerId = UUID.randomUUID();
    var maintenanceLease =
        assets
            .acquireMaintenanceLease(
                subjectId,
                UUID.randomUUID(),
                new AcquireMaintenanceOperationLeaseRequest(
                    maintenanceRental.id(),
                    MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
                    ownerId,
                    maintenanceRental.version()))
            .response();
    MaintenanceFencedStatusRequest writeOff =
        new MaintenanceFencedStatusRequest(
            maintenanceRental.version(),
            MaintenanceStatusAction.WRITE_OFF,
            maintenanceLease.id(),
            maintenanceLease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
            ownerId,
            null);
    var writtenOff =
        assets.maintenanceFencedStatus(
            subjectId, UUID.randomUUID(), maintenanceRental.id(), writeOff);
    var repeatedDisposition =
        assets.maintenanceFencedStatus(
            subjectId, UUID.randomUUID(), maintenanceRental.id(), writeOff);

    assertThat(writtenOff.response().status()).isEqualTo(RentalItemStatus.WRITTEN_OFF);
    assertThat(repeatedDisposition.replayed()).isFalse();
    assertThat(repeatedDisposition.response().id()).isEqualTo(writtenOff.response().id());
    assertThat(repeatedDisposition.response().version()).isEqualTo(writtenOff.response().version());
    assertThat(repeatedDisposition.response().status()).isEqualTo(RentalItemStatus.WRITTEN_OFF);
    assertThatThrownBy(
            () ->
                assets.maintenanceFencedStatus(
                    subjectId,
                    UUID.randomUUID(),
                    maintenanceRental.id(),
                    new MaintenanceFencedStatusRequest(
                        writtenOff.response().version(),
                        MaintenanceStatusAction.QUEUE_FOR_REPAIR,
                        maintenanceLease.id(),
                        maintenanceLease.fencingToken(),
                        MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
                        ownerId,
                        null)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Written-off rental item");
    assertThatThrownBy(
            () ->
                assets.applyMaintenanceCharacteristic(
                    subjectId,
                    UUID.randomUUID(),
                    maintenanceRental.id(),
                    UUID.randomUUID()))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Written-off rental item");
  }

  private RentalItemResponse createRental(UUID subjectId, UUID warehouseId) {
    return assets
        .createRentalItem(
            subjectId,
            UUID.randomUUID(),
            new CreateRentalItemRequest(
                warehouseId,
                "TERMINAL-CABIN-" + UUID.randomUUID(),
                TYPE_BK_1,
                DIMENSION_24_X_6,
                FINISHING_DVP,
                null,
                List.of(),
                false,
                Map.of(),
                List.of()))
        .response();
  }

  private void seedBalance(
      UUID equipmentId,
      UUID warehouseId,
      BalanceLocationKind locationKind,
      long quantity) {
    UUID balanceId = UUID.randomUUID();
    jdbc.update(
        """
        insert into equipment_balance(
          id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,
          created_at,updated_at)
        values (?,0,?,?,?,?,?,clock_timestamp(),clock_timestamp())
        """,
        balanceId,
        equipmentId,
        warehouseId,
        null,
        locationKind.name(),
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
            "locationKind", locationKind.name(),
            "quantity", quantity),
        Map.of(
            "balanceId", balanceId.toString(),
            "version", 0,
            "locationKind", locationKind.name(),
            "quantity", quantity));
  }

  private long balance(UUID equipmentId, UUID warehouseId, BalanceLocationKind locationKind) {
    return jdbc.queryForObject(
        """
        select quantity from equipment_balance
        where equipment_id=? and warehouse_id=? and rental_item_id is null
          and location_kind=?
        """,
        Long.class,
        equipmentId,
        warehouseId,
        locationKind.name());
  }
}
