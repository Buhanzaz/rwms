package dev.buhanzaz.rwms.asset;

import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.CHARACTERISTIC_ELECTRICS_KK;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.CHARACTERISTIC_PLASTIC_WINDOW;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseOwnerType.LOGISTICS_SHIPMENT;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseOwnerType.LOGISTICS_TRANSFER;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsRentalItemAction.TRANSFER_DEPART;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireLogisticsOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsFencedEffectRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseCommandRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferAssetStatus;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateStatusRequest;
import dev.buhanzaz.rwms.asset.api.TransferUnitReservationApiModels.ConfirmTransferUnitReservationLine;
import dev.buhanzaz.rwms.asset.api.TransferUnitReservationApiModels.ConfirmTransferUnitReservationsRequest;
import dev.buhanzaz.rwms.asset.api.TransferUnitReservationApiModels.ReleaseTransferUnitReservationLine;
import dev.buhanzaz.rwms.asset.api.TransferUnitReservationApiModels.ReleaseTransferUnitReservationsRequest;
import dev.buhanzaz.rwms.asset.api.TransferUnitReservationApiModels.TransferUnitReservationStateResponse;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.domain.TransferUnitReservationState;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.TransferUnitReservationRepository;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.TransferUnitReservationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies asset-owned transfer cabin reservation fencing against real Flyway, JPA and PostgreSQL.
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
class TransferUnitReservationIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired AssetService assets;
  @Autowired TransferUnitReservationService reservations;
  @Autowired TransferUnitReservationRepository reservationRows;
  @Autowired OrderUnitReservationRepository orderReservations;
  @Autowired JdbcTemplate jdbc;

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
  void confirmReservesWholeBatchAndExactReplayReturnsTheOriginalReceipt() {
    UUID subject = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    RentalItemResponse first = rental(subject, warehouse, List.of(CHARACTERISTIC_PLASTIC_WINDOW), true);
    RentalItemResponse second = rental(subject, warehouse, List.of(), false);
    UUID transferId = UUID.randomUUID();
    UUID firstLineId = UUID.randomUUID();
    UUID secondLineId = UUID.randomUUID();
    var request =
        new ConfirmTransferUnitReservationsRequest(
            transferId,
            warehouse,
            List.of(line(first, firstLineId), line(second, secondLineId)));
    UUID key = UUID.randomUUID();

    var confirmed = reservations.confirm(subject, key, request);
    var replayed = reservations.confirm(subject, key, request);

    assertThat(confirmed.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(confirmed.response());
    assertThat(confirmed.response().lines())
        .hasSize(2)
        .allMatch(line -> line.state() == TransferUnitReservationStateResponse.ACTIVE);
    assertThat(assets.rentalItem(first.id()).status()).isEqualTo(RentalItemStatus.RESERVED);
    assertThat(assets.rentalItem(second.id()).status()).isEqualTo(RentalItemStatus.RESERVED);
    assertThat(jdbc.queryForObject(
            "select count(*) from transfer_unit_reservation where transfer_id=? and state='ACTIVE'",
            Integer.class,
            transferId))
        .isEqualTo(2);
  }

  @Test
  void sourceWarehouseAndEveryCompositionRequirementAreAuthoritativeConflicts() {
    UUID subject = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    RentalItemResponse rental =
        rental(subject, warehouse, List.of(CHARACTERISTIC_PLASTIC_WINDOW), true);
    UUID otherType =
        jdbc.queryForObject(
            "select id from cabin_catalog_item where kind='TYPE' and active and id<>? order by id limit 1",
            UUID.class,
            TYPE_BK_1);
    UUID otherFinishing =
        jdbc.queryForObject(
            "select id from cabin_catalog_item where kind='FINISHING' and active and id<>? order by id limit 1",
            UUID.class,
            FINISHING_DVP);

    assertThatThrownBy(
            () ->
                confirm(
                    subject,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new ConfirmTransferUnitReservationLine(
                        UUID.randomUUID(),
                        rental.id(),
                        rental.version(),
                        TYPE_BK_1,
                        DIMENSION_24_X_6,
                        FINISHING_DVP,
                        List.of(CHARACTERISTIC_PLASTIC_WINDOW),
                        true)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("source warehouse");
    assertMismatch(subject, warehouse, rental, otherType, DIMENSION_24_X_6, FINISHING_DVP,
        List.of(CHARACTERISTIC_PLASTIC_WINDOW), true, "type");
    assertMismatch(subject, warehouse, rental, TYPE_BK_1, DIMENSION_24_X_6, otherFinishing,
        List.of(CHARACTERISTIC_PLASTIC_WINDOW), true, "finishing");
    assertMismatch(subject, warehouse, rental, TYPE_BK_1, DIMENSION_24_X_6, FINISHING_DVP,
        List.of(CHARACTERISTIC_ELECTRICS_KK), true, "characteristics");
    assertMismatch(subject, warehouse, rental, TYPE_BK_1, DIMENSION_24_X_6, FINISHING_DVP,
        List.of(CHARACTERISTIC_PLASTIC_WINDOW), false, "linoleum");

    jdbc.update("update cabin_catalog_item set active=false where id=?", TYPE_BK_1);
    try {
      assertThatThrownBy(
              () ->
                  confirm(
                      subject,
                      UUID.randomUUID(),
                      warehouse,
                      line(rental, UUID.randomUUID())))
          .isInstanceOf(AssetConflictException.class)
          .hasMessageContaining("inactive");
      assertThat(assets.rentalItem(rental.id()).status()).isEqualTo(RentalItemStatus.FREE);
    } finally {
      jdbc.update("update cabin_catalog_item set active=true where id=?", TYPE_BK_1);
    }

    var exact =
        confirm(
            subject,
            UUID.randomUUID(),
            warehouse,
            line(rental, UUID.randomUUID()));
    assertThat(exact.response().lines()).singleElement().satisfies(
        line -> assertThat(line.state()).isEqualTo(TransferUnitReservationStateResponse.ACTIVE));
  }

  @Test
  void rejectedBatchRollsBackEveryEarlierRentalItemReservation() {
    UUID subject = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    RentalItemResponse left = rental(subject, warehouse, List.of(), false);
    RentalItemResponse right = rental(subject, warehouse, List.of(), false);
    List<RentalItemResponse> sorted =
        List.of(left, right).stream()
            .sorted((first, second) -> first.id().toString().compareTo(second.id().toString()))
            .toList();
    UUID otherType =
        jdbc.queryForObject(
            "select id from cabin_catalog_item where kind='TYPE' and active and id<>? order by id limit 1",
            UUID.class,
            TYPE_BK_1);
    UUID transferId = UUID.randomUUID();
    ConfirmTransferUnitReservationLine valid = line(sorted.getFirst(), UUID.randomUUID());
    ConfirmTransferUnitReservationLine invalid =
        new ConfirmTransferUnitReservationLine(
            UUID.randomUUID(),
            sorted.getLast().id(),
            sorted.getLast().version(),
            otherType,
            sorted.getLast().dimensionId(),
            sorted.getLast().finishingId(),
            List.of(),
            sorted.getLast().linoleum());

    assertThatThrownBy(
            () ->
                reservations.confirm(
                    subject,
                    UUID.randomUUID(),
                    new ConfirmTransferUnitReservationsRequest(
                        transferId, warehouse, List.of(valid, invalid))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("type");

    assertThat(assets.rentalItem(left.id()).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(assets.rentalItem(right.id()).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from transfer_unit_reservation where transfer_id=?",
                Integer.class,
                transferId))
        .isZero();
  }

  @Test
  void nonFreeOrderHeldLeaseHeldAndSecondTransferCabinsAreRejected() {
    UUID subject = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();

    RentalItemResponse nonFree = rental(subject, warehouse, List.of(), false);
    nonFree =
        assets.updateStatus(
            nonFree.id(), new UpdateStatusRequest(nonFree.version(), RentalItemStatus.SALE));
    RentalItemResponse finalNonFree = nonFree;
    assertThatThrownBy(
            () ->
                confirm(
                    subject,
                    UUID.randomUUID(),
                    warehouse,
                    line(finalNonFree, UUID.randomUUID())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("FREE");

    RentalItemResponse orderHeld = rental(subject, warehouse, List.of(), false);
    orderReservations.saveAndFlush(
        OrderUnitReservation.create(
            UUID.randomUUID(), orderHeld.id(), warehouse, subject, "SYSTEM_ADMIN"));
    assertThatThrownBy(
            () ->
                confirm(
                    subject,
                    UUID.randomUUID(),
                    warehouse,
                    line(orderHeld, UUID.randomUUID())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("rental order");

    RentalItemResponse leaseHeld = rental(subject, warehouse, List.of(), false);
    var lease =
        assets
            .acquireLogisticsLease(
                subject,
                UUID.randomUUID(),
                new AcquireLogisticsOperationLeaseRequest(
                    leaseHeld.id(),
                    LOGISTICS_SHIPMENT,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    leaseHeld.version()))
            .response();
    assertThatThrownBy(
            () ->
                confirm(
                    subject,
                    UUID.randomUUID(),
                    warehouse,
                    line(leaseHeld, UUID.randomUUID())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("operation lease");
    assertThat(lease.state()).isEqualTo("ACTIVE");

    RentalItemResponse alreadyReserved = rental(subject, warehouse, List.of(), false);
    var first =
        confirm(
            subject,
            UUID.randomUUID(),
            warehouse,
            line(alreadyReserved, UUID.randomUUID()));
    RentalItemResponse reservedCurrent = assets.rentalItem(alreadyReserved.id());
    assertThatThrownBy(
            () ->
                confirm(
                    subject,
                    UUID.randomUUID(),
                    warehouse,
                    line(reservedCurrent, UUID.randomUUID())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("another transfer");
    assertThat(first.response().lines()).hasSize(1);
  }

  @Test
  void concurrentTransfersSerializeOnTheCabinAndOnlyOneReservationCommits() throws Exception {
    UUID subject = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    RentalItemResponse rental = rental(subject, warehouse, List.of(), false);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> first =
          executor.submit(
              () -> concurrentConfirm(subject, warehouse, rental, ready, start));
      Future<Boolean> second =
          executor.submit(
              () -> concurrentConfirm(subject, warehouse, rental, ready, start));
      ready.await();
      start.countDown();

      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
      assertThat(jdbc.queryForObject(
              "select count(*) from transfer_unit_reservation where rental_item_id=? and state='ACTIVE'",
              Integer.class,
              rental.id()))
          .isOne();
      assertThat(assets.rentalItem(rental.id()).status()).isEqualTo(RentalItemStatus.RESERVED);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void releaseRestoresFreeOnceAndExactReplayDoesNotCreateMoreHistory() {
    UUID subject = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    RentalItemResponse rental = rental(subject, warehouse, List.of(), false);
    UUID transferId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    var confirmed =
        reservations
            .confirm(
                subject,
                UUID.randomUUID(),
                new ConfirmTransferUnitReservationsRequest(
                    transferId, warehouse, List.of(line(rental, lineId))))
            .response()
            .lines()
            .getFirst();
    var request =
        new ReleaseTransferUnitReservationsRequest(
            transferId,
            List.of(
                new ReleaseTransferUnitReservationLine(
                    confirmed.reservationId(),
                    lineId,
                    rental.id(),
                    confirmed.version())));
    UUID releaseKey = UUID.randomUUID();

    var released = reservations.release(subject, releaseKey, request);
    var replayed = reservations.release(subject, releaseKey, request);

    assertThat(released.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(released.response());
    assertThat(released.response().lines())
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.state()).isEqualTo(TransferUnitReservationStateResponse.RELEASED);
              assertThat(line.version()).isEqualTo(1);
            });
    assertThat(assets.rentalItem(rental.id()).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(jdbc.queryForObject(
            "select count(*) from transfer_unit_reservation where id=?",
            Integer.class,
            confirmed.reservationId()))
        .isOne();
  }

  @Test
  void wrongTransferCannotConsumeButCorrectFencedDepartureConsumesAndReplays() {
    UUID subject = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    RentalItemResponse rental = rental(subject, warehouse, List.of(), false);
    UUID transferId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    var confirmed =
        reservations
            .confirm(
                subject,
                UUID.randomUUID(),
                new ConfirmTransferUnitReservationsRequest(
                    transferId, warehouse, List.of(line(rental, lineId))))
            .response()
            .lines()
            .getFirst();

    UUID wrongTransferId = UUID.randomUUID();
    var wrongLease =
        assets
            .acquireLogisticsLease(
                subject,
                UUID.randomUUID(),
                new AcquireLogisticsOperationLeaseRequest(
                    rental.id(),
                    LOGISTICS_TRANSFER,
                    wrongTransferId,
                    lineId,
                    confirmed.currentRentalItemVersion()))
            .response();
    assertThatThrownBy(
            () ->
                assets.applyLogisticsEffect(
                    subject,
                    UUID.randomUUID(),
                    rental.id(),
                    departure(
                        confirmed.currentRentalItemVersion(), wrongLease, wrongTransferId, lineId)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("not reserved by this transfer");
    assertThat(assets.rentalItem(rental.id()).status()).isEqualTo(RentalItemStatus.RESERVED);
    assets.releaseLogisticsLease(
        subject,
        UUID.randomUUID(),
        wrongLease.leaseId(),
        new LogisticsLeaseCommandRequest(
            wrongLease.version(),
            wrongLease.fencingToken(),
            LOGISTICS_TRANSFER,
            wrongTransferId,
            lineId));

    var correctLease =
        assets
            .acquireLogisticsLease(
                subject,
                UUID.randomUUID(),
                new AcquireLogisticsOperationLeaseRequest(
                    rental.id(),
                    LOGISTICS_TRANSFER,
                    transferId,
                    lineId,
                    confirmed.currentRentalItemVersion()))
            .response();
    LogisticsFencedEffectRequest departure =
        departure(
            confirmed.currentRentalItemVersion(), correctLease, transferId, lineId);
    UUID departureKey = UUID.randomUUID();
    var departed = assets.applyLogisticsEffect(subject, departureKey, rental.id(), departure);
    var replayed = assets.applyLogisticsEffect(subject, departureKey, rental.id(), departure);

    assertThat(departed.response().status()).isEqualTo(RentalItemStatus.IN_TRANSFER);
    assertThat(departed.response().warehouseId()).isEqualTo(warehouse);
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(departed.response());
    var row = reservationRows.findByTransferIdAndLineId(transferId, lineId).orElseThrow();
    assertThat(row.getState()).isEqualTo(TransferUnitReservationState.CONSUMED);
    assertThat(row.getVersion()).isEqualTo(1);
    assertThat(row.getTerminalRentalItemVersion())
        .isEqualTo(confirmed.currentRentalItemVersion() + 1);
    assertThat(jdbc.queryForObject(
            "select transfer_origin_status from rental_item where id=?", String.class, rental.id()))
        .isEqualTo("FREE");
  }

  private AssetService.CreateResult<
          dev.buhanzaz.rwms.asset.api.TransferUnitReservationApiModels.TransferUnitReservationReceipt>
      confirm(
          UUID subject,
          UUID idempotencyKey,
          UUID sourceWarehouseId,
          ConfirmTransferUnitReservationLine line) {
    return reservations.confirm(
        subject,
        idempotencyKey,
        new ConfirmTransferUnitReservationsRequest(
            UUID.randomUUID(), sourceWarehouseId, List.of(line)));
  }

  private void assertMismatch(
      UUID subject,
      UUID warehouse,
      RentalItemResponse rental,
      UUID typeId,
      UUID dimensionId,
      UUID finishingId,
      List<UUID> characteristicIds,
      Boolean linoleum,
      String expectedReason) {
    ConfirmTransferUnitReservationLine mismatch =
        new ConfirmTransferUnitReservationLine(
            UUID.randomUUID(),
            rental.id(),
            rental.version(),
            typeId,
            dimensionId,
            finishingId,
            characteristicIds,
            linoleum);
    assertThatThrownBy(
            () -> confirm(subject, UUID.randomUUID(), warehouse, mismatch))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining(expectedReason);
    assertThat(assets.rentalItem(rental.id()).status()).isEqualTo(RentalItemStatus.FREE);
  }

  private boolean concurrentConfirm(
      UUID subject,
      UUID warehouse,
      RentalItemResponse rental,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    start.await();
    try {
      confirm(
          subject,
          UUID.randomUUID(),
          warehouse,
          line(rental, UUID.randomUUID()));
      return true;
    } catch (AssetConflictException exception) {
      return false;
    }
  }

  private RentalItemResponse rental(
      UUID subject, UUID warehouseId, List<UUID> characteristicIds, boolean linoleum) {
    return assets
        .createRentalItem(
            subject,
            UUID.randomUUID(),
            new CreateRentalItemRequest(
                warehouseId,
                "TRANSFER-CABIN-" + UUID.randomUUID(),
                TYPE_BK_1,
                DIMENSION_24_X_6,
                FINISHING_DVP,
                null,
                characteristicIds,
                linoleum,
                Map.of(),
                List.of()))
        .response();
  }

  private static ConfirmTransferUnitReservationLine line(
      RentalItemResponse rental, UUID lineId) {
    return new ConfirmTransferUnitReservationLine(
        lineId,
        rental.id(),
        rental.version(),
        rental.rentalTypeId(),
        rental.dimensionId(),
        rental.finishingId(),
        rental.characteristics().stream().map(value -> value.id()).toList(),
        rental.linoleum());
  }

  private static LogisticsFencedEffectRequest departure(
      long expectedVersion,
      dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsOperationLeaseResponse lease,
      UUID transferId,
      UUID lineId) {
    return new LogisticsFencedEffectRequest(
        expectedVersion,
        TRANSFER_DEPART,
        lease.leaseId(),
        lease.fencingToken(),
        LOGISTICS_TRANSFER,
        transferId,
        lineId,
        null,
        TransferAssetStatus.FREE);
  }
}
