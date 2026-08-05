package dev.buhanzaz.rwms.asset.administrative;

import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;
import static dev.buhanzaz.rwms.asset.api.AdministrativeAssetCorrectionApiModels.AdministrativeCorrectionAssetKind.CABIN;
import static dev.buhanzaz.rwms.asset.api.AdministrativeAssetCorrectionApiModels.AdministrativeCorrectionAssetKind.EQUIPMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.asset.api.AdministrativeAssetCorrectionApiModels.CreateCabinAdministrativeCorrectionRequest;
import dev.buhanzaz.rwms.asset.api.AdministrativeAssetCorrectionApiModels.CreateEquipmentAdministrativeCorrectionRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentResponse;
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
class AdministrativeAssetCorrectionServiceIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired AssetService assets;
  @Autowired AdministrativeAssetCorrectionService corrections;
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
  void cabinCorrectionMovesCabinAndAllContentsWithoutCreatingPhysicalMovement() {
    UUID subjectId = UUID.randomUUID();
    UUID sourceWarehouseId = UUID.randomUUID();
    UUID targetWarehouseId = UUID.randomUUID();
    EquipmentResponse equipment = createEquipment(subjectId);
    RentalItemResponse cabin = createFreeCabin(subjectId, sourceWarehouseId);
    attach(subjectId, sourceWarehouseId, cabin.id(), equipment.id(), 2);
    int physicalMovementsBefore = movementCount(equipment.id());

    UUID idempotencyKey = UUID.randomUUID();
    CreateCabinAdministrativeCorrectionRequest request =
        new CreateCabinAdministrativeCorrectionRequest(
            CABIN,
            cabin.id(),
            cabin.version(),
            sourceWarehouseId,
            targetWarehouseId,
            "Corrected warehouse entered by mistake",
            "https://evidence.example/cabin-correction");

    var applied = corrections.create(subjectId, idempotencyKey, request);
    var replayed = corrections.create(subjectId, idempotencyKey, request);

    assertThat(applied.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(applied.response());
    assertThat(applied.response().assetKind()).isEqualTo(CABIN);
    assertThat(applied.response().quantity()).isNull();
    assertThat(assets.rentalItem(cabin.id()).warehouseId()).isEqualTo(targetWarehouseId);
    assertThat(balance(equipment.id(), sourceWarehouseId, cabin.id(), BalanceLocationKind.CABIN_NON_RENTED))
        .isZero();
    assertThat(balance(equipment.id(), targetWarehouseId, cabin.id(), BalanceLocationKind.CABIN_NON_RENTED))
        .isEqualTo(2);
    assertThat(movementCount(equipment.id())).isEqualTo(physicalMovementsBefore);
    assertThat(correctionCount(applied.response().id())).isEqualTo(1);
    assertThat(correctionEventCount(applied.response().id())).isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update administrative_asset_correction set reason='changed' where id=?",
                    applied.response().id()))
        .hasMessageContaining("cannot be");
    assertThat(replay.rebuildAndVerify().aggregateCount()).isPositive();
  }

  @Test
  void equipmentCorrectionCanCreateMissingTargetStockWithVersionZeroAndIsPermanentlyFenced() {
    UUID subjectId = UUID.randomUUID();
    UUID sourceWarehouseId = UUID.randomUUID();
    UUID targetWarehouseId = UUID.randomUUID();
    EquipmentResponse equipment = createEquipment(subjectId);
    seedStockBalance(equipment.id(), sourceWarehouseId, 5);
    UUID idempotencyKey = UUID.randomUUID();
    CreateEquipmentAdministrativeCorrectionRequest request =
        new CreateEquipmentAdministrativeCorrectionRequest(
            EQUIPMENT,
            equipment.id(),
            sourceWarehouseId,
            0L,
            targetWarehouseId,
            0L,
            2L,
            "Source stock was recorded in the wrong warehouse",
            "https://evidence.example/equipment-correction");

    var applied = corrections.create(subjectId, idempotencyKey, request);
    var replayed = corrections.create(subjectId, idempotencyKey, request);

    assertThat(applied.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(applied.response().assetKind()).isEqualTo(EQUIPMENT);
    assertThat(applied.response().quantity()).isEqualTo(2);
    assertThat(balance(equipment.id(), sourceWarehouseId, null, BalanceLocationKind.STOCK))
        .isEqualTo(3);
    assertThat(balance(equipment.id(), targetWarehouseId, null, BalanceLocationKind.STOCK))
        .isEqualTo(2);
    assertThat(movementCount(equipment.id())).isZero();
    assertThat(correctionEventCount(applied.response().id())).isEqualTo(1);
    assertThatThrownBy(
            () ->
                corrections.create(
                    subjectId,
                    idempotencyKey,
                    new CreateEquipmentAdministrativeCorrectionRequest(
                        EQUIPMENT,
                        equipment.id(),
                        sourceWarehouseId,
                        1L,
                        targetWarehouseId,
                        1L,
                        1L,
                        "Different request",
                        "https://evidence.example/different")))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("idempotency key");
    assertThat(replay.rebuildAndVerify().aggregateCount()).isPositive();
  }

  @Test
  void correctionEvidenceMustBeAnAbsoluteHttpsUriWithHost() {
    UUID subjectId = UUID.randomUUID();
    UUID sourceWarehouseId = UUID.randomUUID();
    UUID targetWarehouseId = UUID.randomUUID();

    for (String invalidEvidence : List.of("javascript:alert(1)", "file:///tmp/evidence", "https:/missing-host")) {
      assertThatThrownBy(
              () ->
                  corrections.create(
                      subjectId,
                      UUID.randomUUID(),
                      new CreateCabinAdministrativeCorrectionRequest(
                          CABIN,
                          UUID.randomUUID(),
                          0L,
                          sourceWarehouseId,
                          targetWarehouseId,
                          "Correction evidence validation",
                          invalidEvidence)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("absolute HTTPS URI with a host");
    }
  }

  private EquipmentResponse createEquipment(UUID subjectId) {
    return assets
        .createEquipment(
            subjectId,
            UUID.randomUUID(),
            new CreateEquipmentRequest(
                "Administrative correction furniture " + UUID.randomUUID(),
                EquipmentCategory.FURNITURE,
                null))
        .response();
  }

  private RentalItemResponse createFreeCabin(UUID subjectId, UUID warehouseId) {
    RentalItemResponse created =
        assets
            .createRentalItem(
                subjectId,
                UUID.randomUUID(),
                new CreateRentalItemRequest(
                    warehouseId,
                    "correction-cabin-" + UUID.randomUUID(),
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

  private void seedStockBalance(UUID equipmentId, UUID warehouseId, long quantity) {
    UUID balanceId = UUID.randomUUID();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
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
  }

  private long balance(
      UUID equipmentId, UUID warehouseId, UUID rentalItemId, BalanceLocationKind locationKind) {
    return jdbc
        .query(
            """
            select quantity
            from equipment_balance
            where equipment_id=? and warehouse_id=? and rental_item_id is not distinct from ? and location_kind=?
            """,
            (result, row) -> result.getLong("quantity"),
            equipmentId,
            warehouseId,
            rentalItemId,
            locationKind.name())
        .stream()
        .findFirst()
        .orElse(0L);
  }

  private int movementCount(UUID equipmentId) {
    Integer value =
        jdbc.queryForObject(
            "select count(*) from equipment_movement where equipment_id=?", Integer.class, equipmentId);
    return value == null ? 0 : value;
  }

  private int correctionCount(UUID correctionId) {
    Integer value =
        jdbc.queryForObject(
            "select count(*) from administrative_asset_correction where id=?",
            Integer.class,
            correctionId);
    return value == null ? 0 : value;
  }

  private int correctionEventCount(UUID correctionId) {
    Integer value =
        jdbc.queryForObject(
            "select count(*) from administrative_asset_correction_event where correction_id=?",
            Integer.class,
            correctionId);
    return value == null ? 0 : value;
  }
}
