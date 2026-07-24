package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryCaptureRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryNumberResolutionRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryValidationRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferEquipmentRequest;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.InventoryAssetService;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "rwms.platform.kafka.enabled=false",
    "rwms.asset.warehouse-registry.enabled=false",
    "spring.cloud.function.definition=",
    "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("test")
class InventoryAssetCaptureSnapshotIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @MockitoSpyBean RentalItemRepository rentalItems;
  @Autowired EquipmentCatalogItemRepository equipment;
  @Autowired OrderUnitReservationRepository orderReservations;
  @Autowired AssetService service;
  @Autowired InventoryAssetService inventory;
  @Autowired AssetEventStore events;
  @Autowired EntityManager entityManager;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

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
  void exposesTenantAndCurrentIdentityForInventoryReconciliation() {
    UUID warehouseId = UUID.randomUUID();
    UUID otherWarehouseId = UUID.randomUUID();
    var rental =
        service
            .createRentalItem(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new CreateRentalItemRequest(
                    warehouseId,
                    "TENANT-" + UUID.randomUUID(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Map.of(),
                    List.of()))
            .response();
    orderReservations.saveAndFlush(
        OrderUnitReservation.create(
            UUID.randomUUID(),
            rental.id(),
            warehouseId,
            UUID.randomUUID(),
            "Арендатор А",
            UUID.randomUUID(),
            "WMS_ADMIN"));

    var capture =
        inventory.createCapture(
            new InventoryCaptureRequest(UUID.randomUUID(), 1L, "c".repeat(64), warehouseId));
    assertThat(inventory.capturePage(capture.captureId(), null, 10).content())
        .singleElement()
        .satisfies(
            member ->
                assertThat(member.passportSnapshot())
                    .containsEntry("tenant", "Арендатор А"));

    assertThat(
            inventory
                .validateAssets(new InventoryValidationRequest(List.of(rental.id())))
                .assets())
        .singleElement()
        .satisfies(
            current -> {
              assertThat(current.displayCanonicalNumber()).isEqualTo(rental.number());
              assertThat(current.identityMatchKey()).isNotBlank();
              assertThat(current.tenantSnapshot()).isEqualTo("Арендатор А");
            });

    var crossWarehouse =
        inventory.resolveNumber(
            new InventoryNumberResolutionRequest(otherWarehouseId, rental.number()));
    assertThat(crossWarehouse.found()).isTrue();
    assertThat(crossWarehouse.asset().warehouseId()).isEqualTo(warehouseId);
    assertThat(crossWarehouse.asset().tenantSnapshot()).isEqualTo("Арендатор А");
  }

  @Test
  void readsAssetsBalancesAndCatalogFromOneRepeatableSnapshot() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    UUID subjectId = UUID.randomUUID();
    var rental = service.createRentalItem(subjectId, UUID.randomUUID(),
        new CreateRentalItemRequest(
            warehouseId, "SNAPSHOT-" + UUID.randomUUID(), null, null, null, null, null,
            null, Map.of(), List.of())).response();
    var catalogResponse = service.createEquipment(subjectId, UUID.randomUUID(),
        new CreateEquipmentRequest(
            "OLD-" + UUID.randomUUID(), "Snapshot item", EquipmentCategory.FURNITURE, null))
        .response();
    new TransactionTemplate(transactionManager).executeWithoutResult(
        ignored -> seedStockBalance(catalogResponse.id(), warehouseId, 1));
    service.transfer(subjectId, UUID.randomUUID(), new TransferEquipmentRequest(
        catalogResponse.id(), warehouseId, null, BalanceLocationKind.STOCK, 0L,
        warehouseId, rental.id(), BalanceLocationKind.CABIN_NON_RENTED, 0L, 1L));
    RentalItem rentalItem = rentalItems.findById(rental.id()).orElseThrow();
    var catalog = equipment.findById(catalogResponse.id()).orElseThrow();

    CountDownLatch snapshotOpened = new CountDownLatch(1);
    CountDownLatch captureMayContinue = new CountDownLatch(1);
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      var statuses = (java.util.Collection<RentalItemStatus>) invocation.getArgument(1);
      Object result = entityManager.createQuery("""
          select item from RentalItem item
          where item.warehouseId=:warehouseId and item.status in :statuses
          order by item.identityMatchKey asc, item.id asc
          """, RentalItem.class)
          .setParameter("warehouseId", warehouseId)
          .setParameter("statuses", statuses)
          .getResultList();
      snapshotOpened.countDown();
      if (!captureMayContinue.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Capture test was not released");
      }
      return result;
    }).when(rentalItems).findAllByWarehouseIdAndStatusInOrderByIdentityMatchKeyAscIdAsc(
        org.mockito.ArgumentMatchers.eq(warehouseId), org.mockito.ArgumentMatchers.anyCollection());

    var executor = Executors.newSingleThreadExecutor();
    var mutationExecutor = Executors.newFixedThreadPool(3);
    try {
      var captureFuture = executor.submit(() -> inventory.createCapture(
          new InventoryCaptureRequest(UUID.randomUUID(), 1L, "8".repeat(64), warehouseId)));
      assertThat(snapshotOpened.await(30, TimeUnit.SECONDS)).isTrue();
      var balanceUpdate = mutationExecutor.submit(() -> jdbc.update(
          """
          update equipment_balance set quantity=2
          where equipment_id=? and rental_item_id=? and location_kind='CABIN_NON_RENTED'
          """,
          catalog.getId(), rentalItem.getId()));
      var balanceInsert = mutationExecutor.submit(() -> jdbc.update("""
          insert into equipment_balance(
            id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,
            created_at,updated_at)
          values (?,0,?,?,?,'CABIN_RENTED',1,clock_timestamp(),clock_timestamp())
          """, UUID.randomUUID(), catalog.getId(), warehouseId, rentalItem.getId()));
      var catalogUpdate = mutationExecutor.submit(() -> jdbc.update(
          "update equipment_catalog_item set code='NEW-SNAPSHOT' where id=?", catalog.getId()));
      assertThat(balanceUpdate.get(30, TimeUnit.SECONDS)).isOne();
      assertThat(balanceInsert.get(30, TimeUnit.SECONDS)).isOne();
      assertThat(catalogUpdate.get(30, TimeUnit.SECONDS)).isOne();
      captureMayContinue.countDown();
      var capture = captureFuture.get(30, TimeUnit.SECONDS);

      var page = inventory.capturePage(capture.captureId(), null, 10);
      assertThat(page.content()).singleElement().satisfies(member -> {
        assertThat(member.assetId()).isEqualTo(rentalItem.getId());
        assertThat(member.contentsSnapshot()).singleElement().satisfies(content -> {
          assertThat(content.quantity()).isOne();
          assertThat(content.equipmentCode()).isEqualTo(catalog.getCode());
          assertThat(content.equipmentName()).isEqualTo(catalog.getName());
        });
      });
    } finally {
      captureMayContinue.countDown();
      executor.shutdownNow();
      mutationExecutor.shutdownNow();
    }
  }

  @Test
  void committedCaptureFixtureFreezesPagesAndValidationRemainsPointInTimeOnly() {
    UUID warehouseId = UUID.randomUUID();
    List<RentalItem> eligible = java.util.stream.IntStream.range(0, 205)
        .mapToObj(index -> RentalItem.create(warehouseId, "INV-" + index, null, null, null,
            null, null, null, "{}", "[]"))
        .toList();
    rentalItems.saveAllAndFlush(eligible);
    RentalItem excluded = RentalItem.create(
        warehouseId, "INV-EXCLUDED", null, null, null, null, null, null, "{}", "[]");
    excluded.changeStatusUnderLease(RentalItemStatus.RENTED);
    rentalItems.saveAndFlush(excluded);

    var capture = inventory.createCapture(new InventoryCaptureRequest(
        UUID.randomUUID(), 1L, "a".repeat(64), warehouseId));
    var replay = inventory.createCapture(new InventoryCaptureRequest(
        capture.operationId(), 1L, "a".repeat(64), warehouseId));
    assertThat(replay).isEqualTo(capture);
    assertThat(capture.totalCount()).isEqualTo(205);
    assertThat(capture.expiresAt()).isEqualTo(capture.createdAt().plusMinutes(30));

    eligible.getFirst().changeStatusUnderLease(RentalItemStatus.RENTED);
    rentalItems.saveAndFlush(eligible.getFirst());
    var first = inventory.capturePage(capture.captureId(), null, 200);
    var second = inventory.capturePage(capture.captureId(), first.nextCursor(), 200);
    assertThat(first.content()).hasSize(200);
    assertThat(second.content()).hasSize(5);
    assertThat(inventory.capturePage(capture.captureId(), first.nextCursor(), 200))
        .isEqualTo(second);
    assertThat(first.membershipDigest()).isEqualTo(second.membershipDigest());
    assertThat(first.content()).extracting(item -> item.assetId()).contains(eligible.getFirst().getId());
    assertThat(jdbc.queryForObject(
        "select expires_at from inventory_asset_capture where capture_id=?",
        OffsetDateTime.class, capture.captureId())).isEqualTo(capture.expiresAt());

    var validation = inventory.validateAssets(new InventoryValidationRequest(
        eligible.stream().map(RentalItem::getId).toList()));
    assertThat(validation.assets()).hasSize(205);
    assertThat(validation.validationDigest()).hasSize(64);
    assertThat(validation.assets()).filteredOn(item -> item.assetId().equals(eligible.getFirst().getId()))
        .singleElement().extracting(item -> item.status()).isEqualTo(RentalItemStatus.RENTED);

    var otherCapture = inventory.createCapture(new InventoryCaptureRequest(
        UUID.randomUUID(), 1L, "b".repeat(64), warehouseId));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> inventory.capturePage(
        otherCapture.captureId(), first.nextCursor(), 200))
        .isInstanceOf(AssetConflictException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> inventory.capturePage(
        capture.captureId(), tamperCursor(first.nextCursor()), 200))
        .isInstanceOf(AssetConflictException.class);

    inventory.releaseCapture(capture.captureId());
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> inventory.capturePage(capture.captureId(), null, 200))
        .isInstanceOf(AssetConflictException.class);
  }

  private UUID seedStockBalance(UUID equipmentId, UUID warehouseId, long quantity) {
    UUID balanceId = UUID.randomUUID();
    jdbc.update("""
        insert into equipment_balance(
          id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,
          created_at,updated_at)
        values (?,0,?,?,null,'STOCK',?,clock_timestamp(),clock_timestamp())
        """, balanceId, equipmentId, warehouseId, quantity);
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
    return balanceId;
  }

  private static String tamperCursor(String cursor) {
    String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
    char replacement = decoded.endsWith("a") ? 'b' : 'a';
    String changed = decoded.substring(0, decoded.length() - 1) + replacement;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
        changed.getBytes(StandardCharsets.US_ASCII));
  }
}
