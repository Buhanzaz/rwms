package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.CATEGORY_NEW;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.plasticWindow;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireEquipmentHoldRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireMaintenanceOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ClassifierRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CommitEquipmentHoldRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateClassifierRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.Disposition;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.DispositionEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.FencedStatusRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryCaptureRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryNumberResolutionRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventorySourceAssetRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceFencedStatusRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceStatusAction;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ReleaseMaintenanceOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ReleaseOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RenewMaintenanceOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateGeneralCommentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdatePassportRequest;
import dev.buhanzaz.rwms.asset.administrative.AdministrativeAssetCorrectionService;
import dev.buhanzaz.rwms.asset.api.AdministrativeAssetCorrectionApiModels.CreateCabinAdministrativeCorrectionRequest;
import dev.buhanzaz.rwms.asset.api.AdministrativeAssetCorrectionApiModels.AdministrativeCorrectionAssetKind;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceId;
import dev.buhanzaz.rwms.asset.domain.OperationLease;
import dev.buhanzaz.rwms.asset.domain.OperationLeaseState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.eventing.AssetReplayVerifier;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.OperationLeaseRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.InventoryAssetBoundaryRegistrar;
import dev.buhanzaz.rwms.asset.service.InventoryAssetService;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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

/** Starts the actual application after Flyway so Hibernate must validate V1. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.asset.warehouse-registry.enabled=false",
      "spring.cloud.function.definition=",
      "spring.task.scheduling.enabled=false"
    })
@ActiveProfiles("test")
class AssetJpaValidationIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired RentalItemRepository rentalItems;
  @Autowired EquipmentCatalogItemRepository equipment;
  @Autowired OperationLeaseRepository operationLeases;
  @Autowired AssetEventStore events;
  @Autowired AssetReplayVerifier replay;
  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManager entityManager;
  @Autowired AssetService service;
  @Autowired AdministrativeAssetCorrectionService administrativeCorrections;
  @Autowired InventoryAssetBoundaryRegistrar inventoryAssetRegistrar;
  @Autowired InventoryAssetService inventoryAssetService;
  @Autowired DataSource dataSource;

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
  @Transactional
  void flywayV3ValidatesJpaMappingsAndPersistsCanonicalRoots() {
    RentalItem rental = rentalItems.saveAndFlush(
        RentalItem.create(
            UUID.randomUUID(),
            " cabin-101 ",
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            null,
            null,
            "{}",
            "[]"));
    EquipmentCatalogItem catalog = equipment.saveAndFlush(
        EquipmentCatalogItem.create("Chair", EquipmentCategory.FURNITURE, null));

    assertThat(rental.getNumber()).isEqualTo("CABIN-101");
    assertThat(rental.getIdentityMatchKey()).isEqualTo("CABIN101");
    assertThat(rental.getVersion()).isZero();
    assertThat(catalog.getName()).isEqualTo("Chair");
    assertThat(catalog.getVersion()).isZero();
  }

  @Test
  void rentalNumberIdentityIsWarehouseScopedAndDestinationCollisionIsRejected() {
    UUID firstWarehouseId = UUID.randomUUID();
    UUID secondWarehouseId = UUID.randomUUID();
    UUID absentWarehouseId = UUID.randomUUID();
    String number = "LOCAL-" + UUID.randomUUID();
    RentalItemResponse first = service.createRentalItem(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            firstWarehouseId,
            number,
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            null,
            List.of(),
            false,
            Map.of(),
            List.of()))
        .response();
    RentalItemResponse second = service.createRentalItem(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            secondWarehouseId,
            number,
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            null,
            List.of(),
            false,
            Map.of(),
            List.of()))
        .response();

    assertThat(inventoryAssetService.resolveNumber(
        new InventoryNumberResolutionRequest(firstWarehouseId, number)).asset().assetId())
        .isEqualTo(first.id());
    assertThat(inventoryAssetService.resolveNumber(
        new InventoryNumberResolutionRequest(secondWarehouseId, number)).asset().assetId())
        .isEqualTo(second.id());
    var crossWarehouse =
        inventoryAssetService.resolveNumber(
            new InventoryNumberResolutionRequest(absentWarehouseId, number));
    assertThat(crossWarehouse.found()).isTrue();
    assertThat(crossWarehouse.asset().warehouseId())
        .isIn(firstWarehouseId, secondWarehouseId);
    assertThatThrownBy(() -> service.createRentalItem(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            firstWarehouseId,
            number,
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            null,
            List.of(),
            false,
            Map.of(),
            List.of())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("this warehouse");
    assertThatThrownBy(
            () ->
                administrativeCorrections.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new CreateCabinAdministrativeCorrectionRequest(
                        AdministrativeCorrectionAssetKind.CABIN,
                        first.id(),
                        first.version(),
                        firstWarehouseId,
                        secondWarehouseId,
                        "duplicate recorded warehouse correction",
                        "https://evidence.example/correction")))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("destination warehouse");
  }

  @Test
  void preV9UnscopedNumberClaimBindsOnlyThroughItsSourceRetry() {
    InventoryAssetSourceId sourceId =
        new InventoryAssetSourceId(UUID.randomUUID(), UUID.randomUUID());
    UUID warehouseId = UUID.randomUUID();
    String identityMatchKey =
        "LEGACY" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    jdbc.update("""
        insert into inventory_asset_source_operation(
          inventory_id,finding_id,version,request_fingerprint,created_at)
        values (?, ?, 0, ?, clock_timestamp())
        """, sourceId.getInventoryId(), sourceId.getFindingId(), "c".repeat(64));
    jdbc.update("""
        insert into inventory_asset_number_claim(
          claim_id,warehouse_id,identity_match_key,version,inventory_id,finding_id,created_at)
        values (?, null, ?, 0, ?, ?, clock_timestamp())
        """, UUID.randomUUID(), identityMatchKey,
        sourceId.getInventoryId(), sourceId.getFindingId());

    assertThat(inventoryAssetService.resolveNumber(
        new InventoryNumberResolutionRequest(warehouseId, identityMatchKey)).found())
        .isFalse();
    inventoryAssetRegistrar.claimNumber(warehouseId, identityMatchKey, sourceId);

    assertThat(jdbc.queryForObject("""
        select warehouse_id from inventory_asset_number_claim
        where inventory_id=? and finding_id=?
        """, UUID.class, sourceId.getInventoryId(), sourceId.getFindingId()))
        .isEqualTo(warehouseId);
    assertThatThrownBy(() -> inventoryAssetRegistrar.claimNumber(
        UUID.randomUUID(), identityMatchKey, sourceId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("another warehouse");
  }

  @Test
  void inventorySourceNumberClaimsAreIndependentAcrossWarehouses() {
    UUID firstWarehouseId = UUID.randomUUID();
    UUID secondWarehouseId = UUID.randomUUID();
    String number = "INVLOCAL-" + UUID.randomUUID();
    InventorySourceAssetRequest firstRequest = new InventorySourceAssetRequest(
        UUID.randomUUID(), UUID.randomUUID(), firstWarehouseId, number,
        TYPE_BK_1, DIMENSION_24_X_6, FINISHING_DVP, null, List.of(), false, Map.of(), List.of());
    InventorySourceAssetRequest secondRequest = new InventorySourceAssetRequest(
        UUID.randomUUID(), UUID.randomUUID(), secondWarehouseId, number,
        TYPE_BK_1, DIMENSION_24_X_6, FINISHING_DVP, null, List.of(), false, Map.of(), List.of());

    var first = inventoryAssetService.createSourceAsset(firstRequest);
    var second = inventoryAssetService.createSourceAsset(secondRequest);

    assertThat(first.response().asset().assetId())
        .isNotEqualTo(second.response().asset().assetId());
    assertThat(jdbc.queryForObject("""
        select count(*) from inventory_asset_number_claim
        where identity_match_key=? and warehouse_id in (?, ?)
        """, Integer.class, RentalItem.identityMatchKey(number),
        firstWarehouseId, secondWarehouseId)).isEqualTo(2);
    assertThat(inventoryAssetService.resolveNumber(
        new InventoryNumberResolutionRequest(firstWarehouseId, number)).asset().assetId())
        .isEqualTo(first.response().asset().assetId());
    assertThat(inventoryAssetService.resolveNumber(
        new InventoryNumberResolutionRequest(secondWarehouseId, number)).asset().assetId())
        .isEqualTo(second.response().asset().assetId());
  }

  @Test
  @Transactional
  void inventorySourceIdentityReplaysPermanentlyAndCreatesFreeNormalAssetFact() {
    int ordinaryIdempotencyBefore = jdbc.queryForObject(
        "select count(*) from asset_idempotency_record", Integer.class);
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    InventorySourceAssetRequest request = new InventorySourceAssetRequest(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        " ИНВ- 901 ",
        TYPE_BK_1,
        DIMENSION_24_X_6,
        FINISHING_DVP,
        null,
        plasticWindow(),
        false,
        Map.of("safe", "value"),
        List.of("TAG"));

    var created = inventoryAssetService.createSourceAsset(request);
    var replayed = inventoryAssetService.createSourceAsset(request);

    assertThat(created.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(created.response());
    assertThat(created.response().asset().status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(created.response().asset().displayCanonicalNumber()).isEqualTo("ИНВ- 901");
    assertThat(created.response().asset().identityMatchKey()).isEqualTo("ИНВ901");
    assertThat(jdbc.queryForObject(
        "select count(*) from inventory_asset_source where inventory_id=? and finding_id=?",
        Integer.class, inventoryId, findingId)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select count(*) from domain_event where aggregate_id=? and event_type='asset.rental-item.created.v1'",
        Integer.class, created.response().asset().assetId().toString())).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from asset_idempotency_record", Integer.class))
        .isEqualTo(ordinaryIdempotencyBefore);

    InventorySourceAssetRequest changed = new InventorySourceAssetRequest(
        inventoryId,
        findingId,
        request.warehouseId(),
        "ИНВ-902",
        TYPE_BK_1,
        DIMENSION_24_X_6,
        FINISHING_DVP,
        null,
        plasticWindow(),
        false,
        Map.of("safe", "value"),
        List.of("TAG"));
    assertThatThrownBy(() -> inventoryAssetService.createSourceAsset(changed))
        .isInstanceOf(AssetConflictException.class);
  }

  @Test
  void concurrentInventorySourceRetriesConvergeOnOneAsset() throws Exception {
    InventorySourceAssetRequest request = new InventorySourceAssetRequest(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SRC-" + UUID.randomUUID(),
        TYPE_BK_1, DIMENSION_24_X_6, FINISHING_DVP, null, List.of(), false, Map.of(), List.of());
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> inventoryAssetService.createSourceAsset(request));
      var second = executor.submit(() -> inventoryAssetService.createSourceAsset(request));
      var results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

      assertThat(results).extracting(result -> result.response().asset().assetId())
          .containsOnly(results.getFirst().response().asset().assetId());
      assertThat(results).extracting(result -> result.replayed()).containsExactlyInAnyOrder(false, true);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void captureAttemptIdentityRejectsWrongWarehouseAndChangedFingerprint() {
    UUID warehouseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    var capture = inventoryAssetService.createCapture(new InventoryCaptureRequest(
        operationId, 1L, "c".repeat(64), warehouseId));

    assertThatThrownBy(() -> inventoryAssetService.createCapture(new InventoryCaptureRequest(
        operationId, 1L, "c".repeat(64), UUID.randomUUID())))
        .isInstanceOf(AssetConflictException.class);
    assertThatThrownBy(() -> inventoryAssetService.createCapture(new InventoryCaptureRequest(
        operationId, 1L, "d".repeat(64), warehouseId)))
        .isInstanceOf(AssetConflictException.class);
    inventoryAssetService.releaseCapture(capture.captureId());
  }

  @Test
  void concurrentCaptureRetriesConvergeOnOneJpaLockedAttempt() throws Exception {
    UUID operationId = UUID.randomUUID();
    InventoryCaptureRequest request = new InventoryCaptureRequest(
        operationId, 1L, "9".repeat(64), UUID.randomUUID());
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> inventoryAssetService.createCapture(request));
      var second = executor.submit(() -> inventoryAssetService.createCapture(request));

      assertThat(List.of(
              first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
          .containsOnly(inventoryAssetService.createCapture(request));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void expiredCaptureCannotReviveButHigherMonotonicAttemptCanReplaceIt() {
    UUID captureId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    String fingerprint = "e".repeat(64);
    jdbc.update("""
        insert into inventory_asset_capture_operation(
          operation_id,version,warehouse_id,request_fingerprint,created_at)
        values (?,0,?,?,clock_timestamp())
        """, operationId, warehouseId, fingerprint);
    jdbc.update("""
        with instant as (select clock_timestamp() as value)
        insert into inventory_asset_capture(capture_id,operation_id,technical_attempt,warehouse_id,
          request_fingerprint,membership_digest,total_count,state,created_at,expires_at)
        select ?,?,?,?, ?,?,0,'ACTIVE',value - interval '31 minutes',value - interval '1 minute'
        from instant
        """, captureId, operationId, 1L, warehouseId, fingerprint, "f".repeat(64));

    assertThatThrownBy(() -> inventoryAssetService.capturePage(captureId, null, 200))
        .isInstanceOf(AssetConflictException.class);
    assertThatThrownBy(() -> inventoryAssetService.createCapture(new InventoryCaptureRequest(
        operationId, 1L, fingerprint, warehouseId)))
        .isInstanceOf(AssetConflictException.class);

    var replacement = inventoryAssetService.createCapture(new InventoryCaptureRequest(
        operationId, 2L, fingerprint, warehouseId));
    assertThat(replacement.expiresAt()).isEqualTo(replacement.createdAt().plusMinutes(30));
    assertThat(jdbc.queryForObject(
        "select state from inventory_asset_capture where capture_id=?", String.class, captureId))
        .isEqualTo("EXPIRED");
    assertThat(inventoryAssetService.capturePage(replacement.captureId(), null, 200).content())
        .isEmpty();
    inventoryAssetService.releaseCapture(replacement.captureId());
  }

  @Test
  void concurrentDifferentSourcesWithOneNumberProduceOneAssetAndOneDomainConflict()
      throws Exception {
    UUID warehouseId = UUID.randomUUID();
    String number = "RACE-" + UUID.randomUUID();
    InventorySourceAssetRequest firstRequest = new InventorySourceAssetRequest(
        UUID.randomUUID(), UUID.randomUUID(), warehouseId, number,
        TYPE_BK_1, DIMENSION_24_X_6, FINISHING_DVP, null, List.of(), false, Map.of(), List.of());
    InventorySourceAssetRequest secondRequest = new InventorySourceAssetRequest(
        UUID.randomUUID(), UUID.randomUUID(), warehouseId, number,
        TYPE_BK_1, DIMENSION_24_X_6, FINISHING_DVP, null, List.of(), false, Map.of(), List.of());
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> inventoryAssetService.createSourceAsset(firstRequest));
      var second = executor.submit(() -> inventoryAssetService.createSourceAsset(secondRequest));
      int successes = 0;
      int conflicts = 0;
      for (var future : List.of(first, second)) {
        try {
          future.get(30, TimeUnit.SECONDS);
          successes++;
        } catch (ExecutionException exception) {
          assertThat(exception.getCause()).isInstanceOf(AssetConflictException.class);
          conflicts++;
        }
      }

      assertThat(successes).isEqualTo(1);
      assertThat(conflicts).isEqualTo(1);
      assertThat(jdbc.queryForObject(
          "select count(*) from rental_item where identity_match_key=?", Integer.class,
          RentalItem.identityMatchKey(number))).isEqualTo(1);
      assertThat(jdbc.queryForObject("""
          select count(*) from inventory_asset_source
          where (inventory_id=? and finding_id=?) or (inventory_id=? and finding_id=?)
          """, Integer.class, firstRequest.inventoryId(), firstRequest.findingId(),
          secondRequest.inventoryId(), secondRequest.findingId())).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @Transactional
  void flywayV3ValidatesOperationLeaseJpaMappingRepositoryLocksAndVersioning() {
    RentalItem rental = rentalItems.saveAndFlush(RentalItem.create(
        UUID.randomUUID(),
        "lease-jpa-" + UUID.randomUUID(),
        TYPE_BK_1,
        DIMENSION_24_X_6,
        FINISHING_DVP,
        null,
        null,
        "{}",
        "[]"));
    OffsetDateTime acquiredAt = OffsetDateTime.now(ZoneOffset.UTC);
    OperationLease lease = operationLeases.saveAndFlush(OperationLease.acquire(
        rental.getId(),
        "MAINTENANCE_REPAIR",
        UUID.randomUUID().toString(),
        1,
        UUID.randomUUID(),
        acquiredAt,
        acquiredAt.plusMinutes(15)));

    assertThat(lease.getId()).isNotNull();
    assertThat(lease.getVersion()).isZero();
    assertThat(operationLeases.findByIdForUpdate(lease.getId())).containsSame(lease);
    assertThat(operationLeases.findByRentalItemIdAndStateForUpdate(
            rental.getId(), OperationLeaseState.ACTIVE))
        .containsExactly(lease);
    assertThat(operationLeases.maximumFencingToken(rental.getId())).isEqualTo(1);

    lease.renew(acquiredAt.plusMinutes(1), acquiredAt.plusMinutes(16));
    operationLeases.saveAndFlush(lease);
    assertThat(lease.getVersion()).isEqualTo(1);

    lease.release(acquiredAt.plusMinutes(2));
    operationLeases.saveAndFlush(lease);
    assertThat(lease.getVersion()).isEqualTo(2);
    assertThat(lease.getState()).isEqualTo(OperationLeaseState.RELEASED);
    assertThat(jdbc.queryForObject(
            "select count(*) from operation_lease where id=? and version=2 and state='RELEASED' "
                + "and released_at is not null and idempotency_key is not null",
            Integer.class,
            lease.getId()))
        .isEqualTo(1);
  }

  @Test
  @Transactional
  void storesLocalSnapshotsButNeverPlacesCommentTextInTheKafkaEnvelope() {
    UUID id = UUID.randomUUID();
    String secretComment = "operator@example.test / local-only comment";

    events.initialize(
        AssetAggregateType.RENTAL_ITEM,
        id,
        0,
        AssetEventType.RENTAL_ITEM_GENERAL_COMMENT_CHANGED,
        Map.of("rentalItemId", id.toString(), "commentRevision", 0),
        Map.of("generalComment", secretComment));

    String envelope = jdbc.queryForObject(
        "select envelope_body::text from outbox_event where aggregate_id=?", String.class, id.toString());
    String snapshot = jdbc.queryForObject(
        "select state::text from aggregate_snapshot where aggregate_type='RENTAL_ITEM' and aggregate_id=?", String.class, id.toString());
    assertThat(envelope).doesNotContain(secretComment).doesNotContain("generalComment");
    assertThat(snapshot).contains(secretComment);
  }

  @Test
  @Transactional
  void canonicalRentalSnapshotRetainsPrivateStateWhileKafkaFactsStaySanitized() {
    UUID warehouseId = UUID.randomUUID();
    AssetService.CreateResult<RentalItemResponse> created = service.createRentalItem(
        UUID.randomUUID(),
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
            Map.of("privatePassportValue", "local-only"),
            List.of()));
    RentalItemResponse rental = created.response();
    String privateComment = "comment visible only in asset snapshots";
    service.updateGeneralComment(rental.id(), new UpdateGeneralCommentRequest(rental.version(), privateComment));

    String snapshot = jdbc.queryForObject(
        "select state::text from aggregate_snapshot where aggregate_type='RENTAL_ITEM' and aggregate_id=? and aggregate_version=1",
        String.class,
        rental.id().toString());
    List<String> envelopes = jdbc.queryForList(
        "select envelope_body::text from outbox_event where aggregate_type='RENTAL_ITEM' and aggregate_id=? order by aggregate_version",
        String.class,
        rental.id().toString());
    assertThat(snapshot).contains(privateComment, "privatePassportValue");
    assertThat(envelopes).allSatisfy(body -> assertThat(body).doesNotContain(privateComment, "privatePassportValue"));
  }

  @Test
  @Transactional
  void flywayAcceptsTheExpiryFactsUsedByHoldsAndLeases() {
    UUID holdId = UUID.randomUUID();
    UUID leaseId = UUID.randomUUID();
    events.initialize(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        holdId,
        0,
        AssetEventType.EQUIPMENT_HOLD_EXPIRED,
        Map.of(
            "holdId", holdId.toString(),
            "equipmentId", UUID.randomUUID().toString(),
            "warehouseId", UUID.randomUUID().toString(),
            "quantity", 1,
            "state", "EXPIRED"),
        Map.of("holdId", holdId.toString(), "version", 0, "state", "EXPIRED"));
    events.initialize(
        AssetAggregateType.OPERATION_LEASE,
        leaseId,
        0,
        AssetEventType.OPERATION_LEASE_EXPIRED,
        Map.of(
            "leaseId", leaseId.toString(),
            "rentalItemId", UUID.randomUUID().toString(),
            "fencingToken", 1,
            "state", "EXPIRED"),
        Map.of("leaseId", leaseId.toString(), "version", 0, "fencingToken", 1, "state", "EXPIRED"));

    assertThat(jdbc.queryForList("""
        select event_type from domain_event
        where (aggregate_type='EQUIPMENT_ALLOCATION_HOLD' and aggregate_id=?)
           or (aggregate_type='OPERATION_LEASE' and aggregate_id=?)
        order by event_type
        """, String.class, holdId.toString(), leaseId.toString()))
        .containsExactly(
            AssetEventType.EQUIPMENT_HOLD_EXPIRED.value(),
            AssetEventType.OPERATION_LEASE_EXPIRED.value());
  }

  @Test
  @Transactional
  void transfersAndDispositionsConserveLedgerTotalsWhileHoldsOnlyReduceAvailability() {
    UUID warehouseId = UUID.randomUUID();
    UUID subjectId = UUID.randomUUID();
    var catalog = service
        .createEquipment(
            subjectId,
            UUID.randomUUID(),
            new CreateEquipmentRequest("Chair", EquipmentCategory.FURNITURE, null))
        .response();
    var rental = service
        .createRentalItem(
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
                List.of()))
        .response();
    UUID stockBalanceId = seedStockBalance(catalog.id(), warehouseId, 10);

    UUID transferKey = UUID.randomUUID();
    var transfer = new TransferEquipmentRequest(
        catalog.id(),
        warehouseId,
        null,
        BalanceLocationKind.STOCK,
        0L,
        warehouseId,
        rental.id(),
        BalanceLocationKind.CABIN_NON_RENTED,
        0L,
        5L);
    var moved = service.transfer(subjectId, transferKey, transfer);
    var replayed = service.transfer(subjectId, transferKey, transfer);

    assertThat(moved.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response().id()).isEqualTo(moved.response().id());
    var afterTransfer = service.equipmentTotals(catalog.id(), warehouseId);
    List<Long> transferTotals =
        List.of(
            afterTransfer.totalQuantity(),
            afterTransfer.stockQuantity(),
            afterTransfer.nonRentedCabinQuantity(),
            afterTransfer.rentedCabinQuantity(),
            afterTransfer.writtenOffQuantity(),
            afterTransfer.lostQuantity(),
            afterTransfer.availableStock());
    assertThat(transferTotals)
        .containsExactly(10L, 5L, 5L, 0L, 0L, 0L, 5L);
    assertThat(jdbc.queryForObject("select coalesce(sum(quantity_delta), 0) from equipment_movement_ledger", Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("select count(*) from equipment_movement_ledger", Integer.class))
        .isEqualTo(2);

    var held = service.acquireHold(
        subjectId,
        UUID.randomUUID(),
        new AcquireEquipmentHoldRequest(catalog.id(), warehouseId, "MAINTENANCE", "case-1", 5L, 1L));
    var afterHold = service.equipmentTotals(catalog.id(), warehouseId);
    List<Long> holdTotals =
        List.of(
            afterHold.totalQuantity(),
            afterHold.stockQuantity(),
            afterHold.activeHeldQuantity(),
            afterHold.availableStock());
    assertThat(holdTotals)
        .containsExactly(10L, 5L, 5L, 0L);
    var committed = service.commitHold(
        subjectId,
        UUID.randomUUID(),
        held.response().id(),
        new CommitEquipmentHoldRequest(held.response().version()));
    assertThat(committed.response().state()).isEqualTo("COMMITTED");
    assertThat(committed.response().committedAt()).isNotNull();
    assertThat(service.equipmentTotals(catalog.id(), warehouseId).availableStock()).isZero();

    var lease = service
        .acquireLease(
            subjectId,
            UUID.randomUUID(),
            new AcquireOperationLeaseRequest(rental.id(), "MAINTENANCE", "case-1", 0L))
        .response();
    service.fencedStatus(
        rental.id(),
        new FencedStatusRequest(0L, RentalItemStatus.RENTED, lease.id(), lease.fencingToken()));
    var afterRental = service.equipmentTotals(catalog.id(), warehouseId);
    assertThat(afterRental.nonRentedCabinQuantity()).isZero();
    assertThat(afterRental.rentedCabinQuantity()).isEqualTo(5);

    assertThatThrownBy(() -> service.updateGeneralComment(
        rental.id(), new UpdateGeneralCommentRequest(1L, "must not bypass active lease")))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("active operation lease");
    assertThatThrownBy(() -> service.dispose(
        subjectId,
        UUID.randomUUID(),
        new DispositionEquipmentRequest(
            catalog.id(), warehouseId, rental.id(), BalanceLocationKind.CABIN_RENTED, 2L, 1L, Disposition.WRITE_OFF)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("active operation lease");
    service.releaseLease(
        subjectId,
        UUID.randomUUID(),
        lease.id(),
        new ReleaseOperationLeaseRequest(lease.version(), lease.fencingToken()));

    var disposed = service.dispose(
        subjectId,
        UUID.randomUUID(),
        new DispositionEquipmentRequest(
            catalog.id(),
            warehouseId,
            rental.id(),
            BalanceLocationKind.CABIN_RENTED,
            2L,
            2L,
            Disposition.WRITE_OFF));
    assertThat(disposed.replayed()).isFalse();
    var afterDisposition = service.equipmentTotals(catalog.id(), warehouseId);
    List<Long> dispositionTotals =
        List.of(
            afterDisposition.totalQuantity(),
            afterDisposition.stockQuantity(),
            afterDisposition.nonRentedCabinQuantity(),
            afterDisposition.rentedCabinQuantity(),
            afterDisposition.writtenOffQuantity(),
            afterDisposition.lostQuantity(),
            afterDisposition.availableStock());
    assertThat(dispositionTotals)
        .containsExactly(10L, 5L, 0L, 3L, 2L, 0L, 0L);
    assertThat(jdbc.queryForObject("select coalesce(sum(quantity_delta), 0) from equipment_movement_ledger", Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("select count(*) from equipment_movement_ledger", Integer.class))
        .isEqualTo(4);
    assertThat(jdbc.queryForObject("select count(*) from equipment_balance where id=?", Integer.class, stockBalanceId))
        .isEqualTo(1);

    var warehouseListEntry =
        service.equipmentAtWarehouse(warehouseId).stream()
            .filter(value -> value.equipment().id().equals(catalog.id()))
            .findFirst()
            .orElseThrow();
    assertThat(warehouseListEntry.totals()).isEqualTo(afterDisposition);
  }

  @Test
  @Transactional
  void expiresLeasesBeforeRenewingFencingAndRejectsTheStaleLease() {
    UUID subjectId = UUID.randomUUID();
    var rental = service
        .createRentalItem(
            subjectId,
            UUID.randomUUID(),
            new CreateRentalItemRequest(
                UUID.randomUUID(),
                "cabin-" + UUID.randomUUID(),
                TYPE_BK_1,
                DIMENSION_24_X_6,
                FINISHING_DVP,
                null,
                List.of(),
                false,
                Map.of(),
                List.of()))
        .response();
    var first = service
        .acquireLease(
            subjectId,
            UUID.randomUUID(),
            new AcquireOperationLeaseRequest(rental.id(), "MAINTENANCE", "case-1", 0L))
        .response();
    jdbc.update("update operation_lease set expires_at=clock_timestamp() - interval '1 second' where id=?", first.id());
    entityManager.clear();

    var second = service
        .acquireLease(
            subjectId,
            UUID.randomUUID(),
            new AcquireOperationLeaseRequest(rental.id(), "MAINTENANCE", "case-2", 0L))
        .response();

    assertThat(second.fencingToken()).isEqualTo(first.fencingToken() + 1);
    assertThat(jdbc.queryForObject(
            "select count(*) from domain_event where event_type=?", Integer.class, AssetEventType.OPERATION_LEASE_EXPIRED.value()))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                service.fencedStatus(
                    rental.id(),
                    new FencedStatusRequest(0L, RentalItemStatus.RENTED, first.id(), first.fencingToken())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("stale or fenced");
    assertThat(replay.rebuildAndVerify().aggregateCount()).isGreaterThanOrEqualTo(3);
  }

  @Test
  @Transactional
  void canonicalReplayParityAcceptsNumericNodeWidthButRejectsRealLeaseDrift() {
    int initialAggregateCount = replay.rebuildAndVerify().aggregateCount();
    UUID subjectId = UUID.randomUUID();
    var rental = service.createRentalItem(
        subjectId,
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            UUID.randomUUID(),
            "replay-lease-" + UUID.randomUUID(),
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            null,
            List.of(),
            false,
            Map.of(),
            List.of()))
        .response();
    var lease = service.acquireLease(
        subjectId,
        UUID.randomUUID(),
        new AcquireOperationLeaseRequest(rental.id(), "MAINTENANCE", "replay-owner", rental.version()))
        .response();

    assertThat(replay.rebuildAndVerify().aggregateCount()).isEqualTo(initialAggregateCount + 2);

    jdbc.update(
        "update operation_lease set fencing_token=fencing_token+1 where id=?",
        lease.id());
    entityManager.clear();

    assertThatThrownBy(replay::rebuildAndVerify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Asset replay parity mismatch")
        .hasMessageContaining("fencingToken");
  }

  @Test
  @Transactional
  void fencedLeaseCanWriteOffTheCabinWhileThePublicManualTransitionCannot() {
    UUID subjectId = UUID.randomUUID();
    var rental = service.createRentalItem(
        subjectId,
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            UUID.randomUUID(),
            "cabin-" + UUID.randomUUID(),
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            null,
            List.of(),
            false,
            Map.of(),
            List.of()))
        .response();
    var lease = service.acquireLease(
        subjectId,
        UUID.randomUUID(),
        new AcquireOperationLeaseRequest(rental.id(), "MAINTENANCE", "write-off-1", rental.version()))
        .response();

    assertThatThrownBy(() -> service.updateStatus(rental.id(), new dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateStatusRequest(
        rental.version(), RentalItemStatus.WRITTEN_OFF)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("active operation lease");

    var writtenOff = service.fencedStatus(
        rental.id(), new FencedStatusRequest(rental.version(), RentalItemStatus.WRITTEN_OFF, lease.id(), lease.fencingToken()));
    assertThat(writtenOff.status()).isEqualTo(RentalItemStatus.WRITTEN_OFF);
    assertThat(jdbc.queryForObject(
        "select count(*) from domain_event where aggregate_id=? and event_type=?", Integer.class,
        rental.id().toString(), AssetEventType.RENTAL_ITEM_STATUS_CHANGED.value())).isEqualTo(1);
  }

  @Test
  @Transactional
  void maintenanceLeaseCommandsBindAndRecheckTheOwnerWithSubjectBoundReplay() {
    UUID subjectId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    var rental = service.createRentalItem(
        subjectId,
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            UUID.randomUUID(),
            "cabin-" + UUID.randomUUID(),
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            null,
            List.of(),
            false,
            Map.of(),
            List.of()))
        .response();
    UUID acquireKey = UUID.randomUUID();
    var acquireRequest = new AcquireMaintenanceOperationLeaseRequest(
        rental.id(), MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, ownerId, rental.version());
    var acquired = service.acquireMaintenanceLease(
        subjectId,
        acquireKey,
        acquireRequest);
    var lease = acquired.response();
    var acquireReplay = service.acquireMaintenanceLease(subjectId, acquireKey, acquireRequest);

    assertThat(lease.ownerType()).isEqualTo("MAINTENANCE_REPAIR");
    assertThat(lease.ownerId()).isEqualTo(ownerId.toString());
    assertThat(acquireReplay.replayed()).isTrue();
    assertThat(acquireReplay.response()).isEqualTo(lease);
    assertThatThrownBy(() -> service.acquireMaintenanceLease(
        subjectId, UUID.randomUUID(), acquireRequest))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("reacquisition is forbidden");
    assertThatThrownBy(() -> service.renewMaintenanceLease(
        subjectId,
        UUID.randomUUID(),
        lease.id(),
        new RenewMaintenanceOperationLeaseRequest(
            lease.version(), lease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, UUID.randomUUID())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("another maintenance owner");

    UUID renewKey = UUID.randomUUID();
    var renewed = service.renewMaintenanceLease(
        subjectId,
        renewKey,
        lease.id(),
        new RenewMaintenanceOperationLeaseRequest(
            lease.version(), lease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, ownerId));
    var renewedReplay = service.renewMaintenanceLease(
        subjectId,
        renewKey,
        lease.id(),
        new RenewMaintenanceOperationLeaseRequest(
            lease.version(), lease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, ownerId));
    assertThat(renewedReplay.replayed()).isTrue();
    assertThat(renewedReplay.response()).isEqualTo(renewed.response());

    UUID releaseKey = UUID.randomUUID();
    var released = service.releaseMaintenanceLease(
        subjectId,
        releaseKey,
        lease.id(),
        new ReleaseMaintenanceOperationLeaseRequest(
            renewed.response().version(), lease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, ownerId));
    var releasedReplay = service.releaseMaintenanceLease(
        subjectId,
        releaseKey,
        lease.id(),
        new ReleaseMaintenanceOperationLeaseRequest(
            renewed.response().version(), lease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, ownerId));
    assertThat(released.response().state()).isEqualTo("RELEASED");
    assertThat(releasedReplay.replayed()).isTrue();
    assertThatThrownBy(() -> service.releaseMaintenanceLease(
        UUID.randomUUID(),
        releaseKey,
        lease.id(),
        new ReleaseMaintenanceOperationLeaseRequest(
            renewed.response().version(), lease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, ownerId)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessage("Asset data changed concurrently")
        .hasMessageNotContaining(ownerId.toString());
  }

  @Test
  void maintenanceAcquireRechecksRentalVersionAfterTheStableLockOrder() throws Exception {
    UUID subjectId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    var rental = service.createRentalItem(
        subjectId,
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            UUID.randomUUID(),
            "cabin-" + UUID.randomUUID(),
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            null,
            List.of(),
            false,
            Map.of(),
            List.of()))
        .response();
    var request = new AcquireMaintenanceOperationLeaseRequest(
        rental.id(), MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, ownerId, rental.version());
    String idempotencyLock = subjectId + ":maintenance.operation-lease.acquire:" + key;

    var executor = Executors.newSingleThreadExecutor();
    try (var blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.prepareStatement(
          "select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
        statement.setString(1, idempotencyLock);
        statement.execute();
      }

      var acquire = executor.submit(() -> {
        try {
          service.acquireMaintenanceLease(subjectId, key, request);
          return (Throwable) null;
        } catch (Throwable failure) {
          return failure;
        }
      });
      awaitAdvisoryWait();

      var changed = service.updateStatus(
          rental.id(),
          new dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateStatusRequest(
              rental.version(), RentalItemStatus.WAREHOUSE));
      assertThat(changed.version()).isEqualTo(rental.version() + 1);
      blocker.commit();

      assertThat(acquire.get(10, TimeUnit.SECONDS))
          .isInstanceOf(AssetConflictException.class)
          .hasMessageContaining("changed concurrently");
      assertThat(jdbc.queryForObject(
          "select count(*) from operation_lease where rental_item_id=?",
          Integer.class,
          rental.id())).isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @Transactional
  void maintenanceFencedStatusQueuesFreeRentalItemAndUsesActiveFenceForReplay() {
    UUID subjectId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    var rental = service.createRentalItem(
        subjectId,
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            UUID.randomUUID(),
            "cabin-" + UUID.randomUUID(),
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            null,
            List.of(),
            false,
            Map.of(),
            List.of()))
        .response();
    var lease = service.acquireMaintenanceLease(
        subjectId,
        UUID.randomUUID(),
        new AcquireMaintenanceOperationLeaseRequest(
            rental.id(), MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR, ownerId, rental.version()))
        .response();

    UUID queueKey = UUID.randomUUID();
    var queueRequest = new MaintenanceFencedStatusRequest(
        rental.version(),
        MaintenanceStatusAction.QUEUE_FOR_REPAIR,
        lease.id(),
        lease.fencingToken(),
        MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
        ownerId,
        null);
    var queued = service.maintenanceFencedStatus(subjectId, queueKey, rental.id(), queueRequest);
    var queuedReplay = service.maintenanceFencedStatus(subjectId, queueKey, rental.id(), queueRequest);
    assertThat(queued.response().status()).isEqualTo(RentalItemStatus.REPAIR);
    assertThat(queuedReplay.replayed()).isTrue();
    assertThat(queuedReplay.response()).isEqualTo(queued.response());
    assertThatThrownBy(() -> service.maintenanceFencedStatus(
        UUID.randomUUID(), queueKey, rental.id(), queueRequest))
        .isInstanceOf(AssetConflictException.class);

    var pending = service.maintenanceFencedStatus(
        subjectId,
        UUID.randomUUID(),
        rental.id(),
        new MaintenanceFencedStatusRequest(
            queued.response().version(),
            MaintenanceStatusAction.MARK_PENDING_ACCEPTANCE,
            lease.id(),
            lease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
            ownerId,
            null));
    var accepted = service.maintenanceFencedStatus(
        subjectId,
        UUID.randomUUID(),
        rental.id(),
        new MaintenanceFencedStatusRequest(
            pending.response().version(),
            MaintenanceStatusAction.ACCEPT_REPAIR,
            lease.id(),
            lease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
            ownerId,
            null));
    var writtenOff = service.maintenanceFencedStatus(
        subjectId,
        UUID.randomUUID(),
        rental.id(),
        new MaintenanceFencedStatusRequest(
            accepted.response().version(),
            MaintenanceStatusAction.WRITE_OFF,
            lease.id(),
            lease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
            ownerId,
            null));
    assertThat(pending.response().status()).isEqualTo(RentalItemStatus.WAITING_REPAIR_CHECK);
    assertThat(accepted.response().status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(writtenOff.response().status()).isEqualTo(RentalItemStatus.WRITTEN_OFF);
  }

  @Test
  @Transactional
  void maintenanceCapitalQueueUsesTheSameVersionFenceAndCanReachAcceptance() {
    UUID subjectId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    var rental =
        service
            .createRentalItem(
                subjectId,
                UUID.randomUUID(),
                new CreateRentalItemRequest(
                    UUID.randomUUID(),
                    "capital-" + UUID.randomUUID(),
                    TYPE_BK_1,
                    DIMENSION_24_X_6,
                    FINISHING_DVP,
                    null,
                    List.of(),
                    false,
                    Map.of(),
                    List.of()))
            .response();
    var lease =
        service
            .acquireMaintenanceLease(
                subjectId,
                UUID.randomUUID(),
                new AcquireMaintenanceOperationLeaseRequest(
                    rental.id(),
                    MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
                    ownerId,
                    rental.version()))
            .response();
    UUID queueKey = UUID.randomUUID();
    var request =
        new MaintenanceFencedStatusRequest(
            rental.version(),
            MaintenanceStatusAction.QUEUE_FOR_CAPITAL_REPAIR,
            lease.id(),
            lease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
            ownerId,
            null);

    var queued =
        service.maintenanceFencedStatus(
            subjectId, queueKey, rental.id(), request);
    var replayed =
        service.maintenanceFencedStatus(
            subjectId, queueKey, rental.id(), request);

    assertThat(queued.response().status())
        .isEqualTo(RentalItemStatus.CAPITAL_REPAIR);
    assertThat(queued.response().version()).isEqualTo(rental.version() + 1);
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(queued.response());
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from domain_event
                where aggregate_type='RENTAL_ITEM' and aggregate_id=?
                  and event_type=?
                """,
                Integer.class,
                rental.id().toString(),
                AssetEventType.RENTAL_ITEM_STATUS_CHANGED.value()))
        .isOne();

    var pending =
        service.maintenanceFencedStatus(
            subjectId,
            UUID.randomUUID(),
            rental.id(),
            new MaintenanceFencedStatusRequest(
                queued.response().version(),
                MaintenanceStatusAction.MARK_PENDING_ACCEPTANCE,
                lease.id(),
                lease.fencingToken(),
                MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
                ownerId,
                null));
    assertThat(pending.response().status())
        .isEqualTo(RentalItemStatus.WAITING_REPAIR_CHECK);
  }

  @Test
  @Transactional
  void repeatedRepairQueueIsAnIdempotentNoOpForStatusEventsAndCabinBalances() {
    UUID subjectId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    var catalog = service
        .createEquipment(
            subjectId,
            UUID.randomUUID(),
            new CreateEquipmentRequest("Chair", EquipmentCategory.FURNITURE, null))
        .response();
    var created = service
        .createRentalItem(
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
                List.of()))
        .response();
    var rental = service.updateStatus(
        created.id(),
        new dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateStatusRequest(
            created.version(), RentalItemStatus.FREE));
    seedStockBalance(catalog.id(), warehouseId, 2);
    service.transfer(
        subjectId,
        UUID.randomUUID(),
        new TransferEquipmentRequest(
            catalog.id(),
            warehouseId,
            null,
            BalanceLocationKind.STOCK,
            0L,
            warehouseId,
            rental.id(),
            BalanceLocationKind.CABIN_NON_RENTED,
            0L,
            1L));
    UUID cabinBalanceId = jdbc.queryForObject(
        """
        select id from equipment_balance
        where equipment_id=? and warehouse_id=? and rental_item_id=?
          and location_kind='CABIN_NON_RENTED'
        """,
        UUID.class,
        catalog.id(),
        warehouseId,
        rental.id());
    var lease = service
        .acquireMaintenanceLease(
            subjectId,
            UUID.randomUUID(),
            new AcquireMaintenanceOperationLeaseRequest(
                rental.id(),
                MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
                ownerId,
                rental.version()))
        .response();
    var queued = service.maintenanceFencedStatus(
        subjectId,
        UUID.randomUUID(),
        rental.id(),
        new MaintenanceFencedStatusRequest(
            rental.version(),
            MaintenanceStatusAction.QUEUE_FOR_REPAIR,
            lease.id(),
            lease.fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
            ownerId,
            null));
    long statusEventCount = jdbc.queryForObject(
        """
        select count(*) from domain_event
        where aggregate_id=? and event_type=?
        """,
        Long.class,
        rental.id().toString(),
        AssetEventType.RENTAL_ITEM_STATUS_CHANGED.value());
    long balanceVersion = jdbc.queryForObject(
        "select version from equipment_balance where id=?", Long.class, cabinBalanceId);
    OffsetDateTime balanceUpdatedAt = jdbc.queryForObject(
        "select updated_at from equipment_balance where id=?",
        OffsetDateTime.class,
        cabinBalanceId);
    long balanceEventCount = jdbc.queryForObject(
        "select count(*) from domain_event where aggregate_type='EQUIPMENT_BALANCE' and aggregate_id=?",
        Long.class,
        cabinBalanceId.toString());

    UUID requeueKey = UUID.randomUUID();
    var requeueRequest = new MaintenanceFencedStatusRequest(
        queued.response().version(),
        MaintenanceStatusAction.QUEUE_FOR_REPAIR,
        lease.id(),
        lease.fencingToken(),
        MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR,
        ownerId,
        null);
    var requeued =
        service.maintenanceFencedStatus(subjectId, requeueKey, rental.id(), requeueRequest);
    var replayed =
        service.maintenanceFencedStatus(subjectId, requeueKey, rental.id(), requeueRequest);

    assertThat(requeued.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(requeued.response().status()).isEqualTo(RentalItemStatus.REPAIR);
    assertThat(requeued.response().version()).isEqualTo(queued.response().version());
    assertThat(requeued.response().updatedAt()).isEqualTo(queued.response().updatedAt());
    assertThat(replayed.response()).isEqualTo(requeued.response());
    assertThat(jdbc.queryForObject(
            """
            select count(*) from domain_event
            where aggregate_id=? and event_type=?
            """,
            Long.class,
            rental.id().toString(),
            AssetEventType.RENTAL_ITEM_STATUS_CHANGED.value()))
        .isEqualTo(statusEventCount);
    assertThat(jdbc.queryForObject(
            "select version from equipment_balance where id=?", Long.class, cabinBalanceId))
        .isEqualTo(balanceVersion);
    assertThat(jdbc.queryForObject(
            "select updated_at from equipment_balance where id=?",
            OffsetDateTime.class,
            cabinBalanceId))
        .isEqualTo(balanceUpdatedAt);
    assertThat(jdbc.queryForObject(
            "select count(*) from domain_event where aggregate_type='EQUIPMENT_BALANCE' and aggregate_id=?",
            Long.class,
            cabinBalanceId.toString()))
        .isEqualTo(balanceEventCount);
  }

  @Test
  @Transactional
  void classifierStreamsAndNonSecretAssetFactsReplayDeterministicallyAgainstLiveProjection() {
    UUID subjectId = UUID.randomUUID();
    var rental = service.createRentalItem(
        subjectId,
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            UUID.randomUUID(),
            "cabin-" + UUID.randomUUID(),
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            CATEGORY_NEW,
            plasticWindow(),
            false,
            Map.of(),
            List.of("A")))
        .response();
    service.updatePassport(
        rental.id(),
        new UpdatePassportRequest(
            rental.version(),
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            CATEGORY_NEW,
            plasticWindow(),
            false,
            Map.of("local", "only"),
            List.of("A")));
    var classifier = service.createClassifier(
        subjectId,
        UUID.randomUUID(),
        new CreateClassifierRequest("CATEGORY", null, "Demo label", true, 1))
        .response();
    service.updateClassifier(
        classifier.id(), new ClassifierRequest(
            classifier.version(), "CATEGORY", null, "Updated label", false, 2));

    AssetReplayVerifier.ReplayParityResult first = replay.rebuildAndVerify();
    AssetReplayVerifier.ReplayParityResult repeated = replay.rebuildAndVerify();

    assertThat(first).isEqualTo(repeated);
    assertThat(first.aggregateCount()).isGreaterThanOrEqualTo(2);
    assertThat(first.canonicalChecksum()).matches("^[0-9a-f]{64}$");
    assertThat(jdbc.queryForObject(
        "select count(*) from projection_checkpoint where projection_name=?", Integer.class,
        AssetReplayVerifier.SHADOW_PROJECTION)).isEqualTo(first.aggregateCount());
  }

  private UUID seedStockBalance(UUID equipmentId, UUID warehouseId, long quantity) {
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
    return balanceId;
  }

  private void awaitAdvisoryWait() throws InterruptedException {
    for (int attempt = 0; attempt < 200; attempt++) {
      Integer waiters = jdbc.queryForObject(
          "select count(*) from pg_locks where locktype='advisory' and not granted",
          Integer.class);
      if (waiters != null && waiters > 0) return;
      Thread.sleep(25);
    }
    throw new AssertionError("Maintenance acquire did not reach the idempotency lock wait");
  }
}
