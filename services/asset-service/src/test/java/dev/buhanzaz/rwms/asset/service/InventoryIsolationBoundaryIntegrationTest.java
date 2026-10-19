package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.CATEGORY_NEW;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.CHARACTERISTIC_ELECTRICS_KK;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryCaptureRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryFurnitureSnapshotRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryNumberResolutionRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryValidationRequest;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHoldState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetReplayVerifier;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Exercises Hibernate filtering and the strictly receipt-scoped inventory escape hatch on PostgreSQL. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.asset.warehouse-registry.enabled=false",
      "spring.cloud.function.definition=",
      "spring.task.scheduling.enabled=false"
    })
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InventoryIsolationBoundaryIntegrationTest {
  private static final UUID CATEGORY_NEW_ID =
      UUID.fromString("af57f2b0-3a71-4b7f-8d2f-000000000401");
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired RentalItemRepository rentalItems;
  @Autowired EquipmentCatalogItemRepository equipment;
  @Autowired AssetService assets;
  @Autowired InventoryAssetService inventory;
  @Autowired InventoryAssetSourceService sources;
  @Autowired InventorySourceIsolationRepairService repairs;
  @Autowired AssetReplayVerifier replay;
  @Autowired EntityManager entityManager;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  @Order(1)
  @Transactional
  void hidesHeldRowsFromIdentifierJpqlPageOrderAndMutationWhileKeepingCatalogReferences() {
    UUID warehouseId = UUID.randomUUID();
    RentalItem visible = saveRentalItem(warehouseId, "VISIBLE-" + UUID.randomUUID());
    RentalItem held = saveRentalItem(warehouseId, "HELD-" + UUID.randomUUID());
    UUID inventoryId = UUID.randomUUID();
    held.isolateForInventory(inventoryId);
    rentalItems.saveAndFlush(held);
    UUID heldId = held.getId();
    UUID visibleId = visible.getId();
    entityManager.clear();

    assertThat(rentalItems.findById(heldId)).isEmpty();
    assertThat(
            entityManager
                .createQuery(
                    "select item from RentalItem item where item.warehouseId = :warehouseId",
                    RentalItem.class)
                .setParameter("warehouseId", warehouseId)
                .getResultList())
        .extracting(RentalItem::getId)
        .containsExactly(visibleId);
    assertThat(rentalItems.findAllByWarehouseIdOrderByNumber(warehouseId))
        .extracting(RentalItem::getId)
        .containsExactly(visibleId);

    var publicPage = rentalItems.findPublicPage(warehouseId, "", PageRequest.of(0, 20));
    assertThat(publicPage.getTotalElements()).isEqualTo(1);
    assertThat(publicPage.getContent()).extracting(RentalItem::getId).containsExactly(visibleId);

    var orderCandidates =
        rentalItems.findOrderCandidates(
            UUID.randomUUID(),
            warehouseId,
            Set.of(RentalItemStatus.FREE),
            OrderUnitReservationState.ACTIVE,
            PresentationUnitHoldState.ACTIVE,
            OffsetDateTime.now(ZoneOffset.UTC),
            "",
            PageRequest.of(0, 20));
    assertThat(orderCandidates.getTotalElements()).isEqualTo(1);
    assertThat(orderCandidates.getContent())
        .extracting(RentalItem::getId)
        .containsExactly(visibleId);

    assertThat(rentalItems.findByIdForUpdate(heldId)).isEmpty();
    rentalItems.deleteById(heldId);
    rentalItems.flush();
    entityManager.clear();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_item where id=?", Integer.class, heldId))
        .isEqualTo(1);

    assertThat(rentalItems.existsByRentalTypeId(TYPE_BK_1)).isTrue();
    assertThat(rentalItems.existsByDimensionId(DIMENSION_24_X_6)).isTrue();
    assertThat(rentalItems.existsByFinishingId(FINISHING_DVP)).isTrue();
    assertThat(rentalItems.existsByCategoryId(CATEGORY_NEW_ID)).isTrue();
  }

  @Test
  @Order(5)
  void exposesHeldLegacySourcesOnlyToReceiptProvenPrivateInventoryReads() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    RentalItem held = saveRentalItem(warehouseId, "LEGACY-HELD-" + UUID.randomUUID());
    jdbc.update(
        """
        insert into rental_item_characteristic(id,rental_item_id,characteristic_id,sort_order)
        values (?,?,?,0)
        """,
        UUID.randomUUID(),
        held.getId(),
        CHARACTERISTIC_ELECTRICS_KK);
    held.isolateForInventory(inventoryId);
    rentalItems.saveAndFlush(held);
    insertLegacySource(inventoryId, findingId, held.getId());
    EquipmentCatalogItem chair =
        equipment.saveAndFlush(
            EquipmentCatalogItem.create(
                "Инвентарный стул " + UUID.randomUUID(), EquipmentCategory.FURNITURE, null));
    UUID heldId = held.getId();
    entityManager.clear();

    assertThat(inventory.currentAssetSnapshot(heldId))
        .satisfies(
            snapshot -> {
              assertThat(snapshot.assetId()).isEqualTo(heldId);
              assertThat(snapshot.passportSnapshot())
                  .containsEntry("characteristics", List.of("Электрика КК"));
            });
    assertThat(inventory.validateAssets(new InventoryValidationRequest(List.of(heldId))).assets())
        .singleElement()
        .satisfies(value -> assertThat(value.found()).isTrue());
    assertThat(
            inventory.resolveNumber(
                new InventoryNumberResolutionRequest(warehouseId, held.getNumber())))
        .satisfies(value -> assertThat(value.found()).isFalse());
    assertThat(
            inventory.createCapture(
                new InventoryCaptureRequest(UUID.randomUUID(), 1L, "a".repeat(64), warehouseId)))
        .satisfies(capture -> assertThat(capture.totalCount()).isZero());
    assertThat(
            inventory
                .furnitureSnapshot(new InventoryFurnitureSnapshotRequest(warehouseId, List.of(heldId)))
                .items())
        .filteredOn(item -> item.equipmentId().equals(chair.getId()))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.equipmentId()).isEqualTo(chair.getId());
              assertThat(item.cabins())
                  .singleElement()
                  .satisfies(cabin -> assertThat(cabin.assetId()).isEqualTo(heldId));
            });

    RentalItem unproven = saveRentalItem(warehouseId, "UNPROVEN-HELD-" + UUID.randomUUID());
    unproven.isolateForInventory(inventoryId);
    rentalItems.saveAndFlush(unproven);
    entityManager.clear();
    assertThatThrownBy(() -> inventory.currentAssetSnapshot(unproven.getId()))
        .isInstanceOf(AssetNotFoundException.class);
    assertThat(inventory.validateAssets(new InventoryValidationRequest(List.of(unproven.getId()))).assets())
        .singleElement()
        .satisfies(value -> assertThat(value.found()).isFalse());
  }

  @Test
  @Order(2)
  @Transactional
  void repairsLegacySourcesWithOneVisibilityHoldAndReceiptProvenReleaseWithoutReplacingHistory() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    RentalItem item = createRentalItemWithEvent(warehouseId, "REPLAY-HELD-" + UUID.randomUUID());
    jdbc.update(
        """
        insert into rental_item_characteristic(id,rental_item_id,characteristic_id,sort_order)
        values (?,?,?,0)
        """,
        UUID.randomUUID(),
        item.getId(),
        CHARACTERISTIC_ELECTRICS_KK);
    insertLegacySource(inventoryId, findingId, item.getId());
    UUID itemId = item.getId();

    var manifest = repairManifest(inventoryId, warehouseId, findingId, itemId, 0L);
    assertThat(repairs.isolate(manifest)).isTrue();
    entityManager.clear();
    assertThat(rentalItems.findById(itemId)).isEmpty();
    assertThat(visibilityEventCount(itemId)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_source_isolation_repair where inventory_id=?",
                Integer.class,
                inventoryId))
        .isEqualTo(1);
    assertThat(replay.rebuildAndVerify().aggregateCount()).isPositive();

    assertThat(repairs.isolate(manifest)).isFalse();
    assertThat(visibilityEventCount(itemId)).isEqualTo(1);
    assertThat(replay.rebuildAndVerify().aggregateCount()).isPositive();

    RentalItem released =
        sources.materializeForOutcome(
            inventoryId, findingId, itemId, RentalItemStatus.FREE, null);

    assertThat(released.getId()).isEqualTo(itemId);
    assertThat(released.getInventoryIsolationId()).isNull();
    entityManager.clear();
    assertThat(rentalItems.findById(itemId)).isPresent();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_asset_source where inventory_id=? and finding_id=?",
                Integer.class,
                inventoryId,
                findingId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_item_characteristic where rental_item_id=?",
                Integer.class,
                itemId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from domain_event
                where aggregate_id=? and event_type=?
                """,
                Integer.class,
                itemId.toString(),
                AssetEventType.RENTAL_ITEM_CREATED.value()))
        .isEqualTo(1);
    assertThat(
            visibilityEventCount(itemId))
        .isEqualTo(2);
    assertThat(repairs.isolate(manifest)).isFalse();
    assertThat(visibilityEventCount(itemId)).isEqualTo(2);
    assertThat(replay.rebuildAndVerify().aggregateCount()).isPositive();
  }

  @Test
  @Order(3)
  @Transactional
  void rollsBackTheEntireRepairCohortWhenOneSourceVersionIsNoLongerApproved() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID acceptedFindingId = UUID.randomUUID();
    UUID staleFindingId = UUID.randomUUID();
    RentalItem accepted = createRentalItemWithEvent(warehouseId, "REPAIR-GOOD-" + UUID.randomUUID());
    RentalItem stale = createRentalItemWithEvent(warehouseId, "REPAIR-STALE-" + UUID.randomUUID());
    stale.changeGeneralComment("changed after inventory approval");
    rentalItems.saveAndFlush(stale);
    assertThat(stale.getVersion()).isEqualTo(1L);
    insertLegacySource(inventoryId, acceptedFindingId, accepted.getId());
    insertLegacySource(inventoryId, staleFindingId, stale.getId());

    assertThatThrownBy(
            () ->
                repairs.isolate(
                    new InventorySourceIsolationRepairService.Manifest(
                        inventoryId,
                        warehouseId,
                        List.of(
                            new InventorySourceIsolationRepairService.Source(
                                acceptedFindingId, accepted.getId(), 0L),
                            new InventorySourceIsolationRepairService.Source(
                                staleFindingId, stale.getId(), 0L)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("has changed");

    assertRepairCohortUntouched(inventoryId, accepted.getId(), stale.getId());
  }

  @Test
  @Order(4)
  @Transactional
  void rollsBackTheEntireRepairCohortWhenOneSourceDoesNotMatchItsReceipt() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID acceptedFindingId = UUID.randomUUID();
    UUID mismatchedFindingId = UUID.randomUUID();
    RentalItem accepted = createRentalItemWithEvent(warehouseId, "REPAIR-GOOD-" + UUID.randomUUID());
    RentalItem recorded = createRentalItemWithEvent(warehouseId, "REPAIR-RECORDED-" + UUID.randomUUID());
    RentalItem substituted =
        createRentalItemWithEvent(warehouseId, "REPAIR-SUBSTITUTED-" + UUID.randomUUID());
    insertLegacySource(inventoryId, acceptedFindingId, accepted.getId());
    insertLegacySource(inventoryId, mismatchedFindingId, recorded.getId());

    assertThatThrownBy(
            () ->
                repairs.isolate(
                    new InventorySourceIsolationRepairService.Manifest(
                        inventoryId,
                        warehouseId,
                        List.of(
                            new InventorySourceIsolationRepairService.Source(
                                acceptedFindingId, accepted.getId(), 0L),
                            new InventorySourceIsolationRepairService.Source(
                                mismatchedFindingId, substituted.getId(), 0L)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("does not match its immutable legacy receipt");

    assertRepairCohortUntouched(inventoryId, accepted.getId(), recorded.getId(), substituted.getId());
  }

  private RentalItem saveRentalItem(UUID warehouseId, String number) {
    return rentalItems.saveAndFlush(
        RentalItem.create(
            warehouseId,
            number,
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            CATEGORY_NEW_ID,
            CATEGORY_NEW,
            false,
            "{}",
            "[]"));
  }

  private RentalItem createRentalItemWithEvent(UUID warehouseId, String number) {
    UUID itemId =
        assets
            .createRentalItem(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new CreateRentalItemRequest(
                    warehouseId,
                    number,
                    TYPE_BK_1,
                    DIMENSION_24_X_6,
                    FINISHING_DVP,
                    CATEGORY_NEW,
                    List.of(),
                    false,
                    Map.of(),
                    List.of()))
            .response()
            .id();
    return rentalItems.findById(itemId).orElseThrow();
  }

  private InventorySourceIsolationRepairService.Manifest repairManifest(
      UUID inventoryId, UUID warehouseId, UUID findingId, UUID assetId, long expectedVersion) {
    return new InventorySourceIsolationRepairService.Manifest(
        inventoryId,
        warehouseId,
        List.of(
            new InventorySourceIsolationRepairService.Source(
                findingId, assetId, expectedVersion)));
  }

  private void assertRepairCohortUntouched(UUID inventoryId, UUID... assetIds) {
    entityManager.clear();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_source_isolation_repair where inventory_id=?",
                Integer.class,
                inventoryId))
        .isZero();
    for (UUID assetId : assetIds) {
      assertThat(rentalItems.findById(assetId)).isPresent();
      assertThat(
              jdbc.queryForObject(
                  "select inventory_isolation_id from rental_item where id=?", UUID.class, assetId))
          .isNull();
      assertThat(visibilityEventCount(assetId)).isZero();
    }
  }

  private int visibilityEventCount(UUID itemId) {
    return jdbc.queryForObject(
        """
        select count(*) from domain_event
        where aggregate_id=? and event_type=?
        """,
        Integer.class,
        itemId.toString(),
        AssetEventType.RENTAL_ITEM_INVENTORY_VISIBILITY_CHANGED.value());
  }

  private void insertLegacySource(UUID inventoryId, UUID findingId, UUID assetId) {
    String fingerprint = "b".repeat(64);
    jdbc.update(
        """
        insert into inventory_asset_source_operation(
          inventory_id,finding_id,version,request_fingerprint,
          reserved_rental_item_id,source_plan,proposal_response,created_at)
        values (?,?,0,?,?,null,cast(? as jsonb),clock_timestamp())
        """,
        inventoryId,
        findingId,
        fingerprint,
        assetId,
        "{}");
    jdbc.update(
        """
        insert into inventory_asset_source(
          inventory_id,finding_id,request_fingerprint,rental_item_id,response_body,created_at)
        values (?,?,?,?,cast(? as jsonb),clock_timestamp())
        """,
        inventoryId,
        findingId,
        fingerprint,
        assetId,
        "{}");
  }
}
