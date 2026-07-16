package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireEquipmentHoldRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CommitEquipmentHoldRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.Disposition;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.DispositionEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.FencedStatusRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ReleaseOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateGeneralCommentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateClassifierRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ClassifierRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdatePassportRequest;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.eventing.AssetReplayVerifier;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
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
  @Autowired AssetEventStore events;
  @Autowired AssetReplayVerifier replay;
  @Autowired JdbcTemplate jdbc;
  @Autowired AssetService service;

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
  void flywayV1ValidatesJpaMappingsAndPersistsCanonicalRoots() {
    RentalItem rental = rentalItems.saveAndFlush(
        RentalItem.create(UUID.randomUUID(), " cabin-101 ", null, null, null, null, null, null, "{}", "[]"));
    EquipmentCatalogItem catalog = equipment.saveAndFlush(
        EquipmentCatalogItem.create(" chair-01 ", "Chair", EquipmentCategory.FURNITURE, null));

    assertThat(rental.getNumber()).isEqualTo("CABIN101");
    assertThat(rental.getVersion()).isZero();
    assertThat(catalog.getCode()).isEqualTo("CHAIR-01");
    assertThat(catalog.getVersion()).isZero();
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
            "Office",
            null,
            null,
            null,
            null,
            null,
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

    assertThat(jdbc.queryForList("select event_type from domain_event order by event_type", String.class))
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
            new CreateEquipmentRequest("chair-" + UUID.randomUUID(), "Chair", EquipmentCategory.FURNITURE, null))
        .response();
    var rental = service
        .createRentalItem(
            subjectId,
            UUID.randomUUID(),
            new CreateRentalItemRequest(
                warehouseId,
                "cabin-" + UUID.randomUUID(),
                null,
                null,
                null,
                null,
                null,
                null,
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
    assertThat(
            List.of(
                afterTransfer.totalQuantity(),
                afterTransfer.stockQuantity(),
                afterTransfer.nonRentedCabinQuantity(),
                afterTransfer.rentedCabinQuantity(),
                afterTransfer.writtenOffQuantity(),
                afterTransfer.lostQuantity(),
                afterTransfer.availableStock()))
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
    assertThat(
            List.of(
                afterHold.totalQuantity(),
                afterHold.stockQuantity(),
                afterHold.activeHeldQuantity(),
                afterHold.availableStock()))
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
    assertThat(
            List.of(
                afterDisposition.totalQuantity(),
                afterDisposition.stockQuantity(),
                afterDisposition.nonRentedCabinQuantity(),
                afterDisposition.rentedCabinQuantity(),
                afterDisposition.writtenOffQuantity(),
                afterDisposition.lostQuantity(),
                afterDisposition.availableStock()))
        .containsExactly(10L, 5L, 0L, 3L, 2L, 0L, 0L);
    assertThat(jdbc.queryForObject("select coalesce(sum(quantity_delta), 0) from equipment_movement_ledger", Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("select count(*) from equipment_movement_ledger", Integer.class))
        .isEqualTo(4);
    assertThat(jdbc.queryForObject("select count(*) from equipment_balance where id=?", Integer.class, stockBalanceId))
        .isEqualTo(1);
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
                null,
                null,
                null,
                null,
                null,
                null,
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
  }

  @Test
  @Transactional
  void fencedLeaseCanWriteOffTheCabinWhileThePublicManualTransitionCannot() {
    UUID subjectId = UUID.randomUUID();
    var rental = service.createRentalItem(
        subjectId,
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            UUID.randomUUID(), "cabin-" + UUID.randomUUID(), null, null, null, null, null, null, Map.of(), List.of()))
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
  void classifierStreamsAndNonSecretAssetFactsReplayDeterministicallyAgainstLiveProjection() {
    UUID subjectId = UUID.randomUUID();
    var rental = service.createRentalItem(
        subjectId,
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            UUID.randomUUID(), "cabin-" + UUID.randomUUID(), "Initial", null, null, null, null, null, Map.of(), List.of("A")))
        .response();
    service.updatePassport(
        rental.id(), new UpdatePassportRequest(rental.version(), "Updated", null, null, null, null, null, Map.of("local", "only"), List.of("A")));
    var classifier = service.createClassifier(
        subjectId,
        UUID.randomUUID(),
        new CreateClassifierRequest("CATEGORY", null, "DEMO-" + UUID.randomUUID().toString().substring(0, 8), "Demo label", true, 1))
        .response();
    service.updateClassifier(
        classifier.id(), new ClassifierRequest(classifier.version(), "CATEGORY", null, classifier.code(), "Updated label", false, 2));

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
}
