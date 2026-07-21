package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireLogisticsOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireMaintenanceOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateStatusRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateWarehouseRequest;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.AdjustOrderEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderActorRequest;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitReservationView;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.ReserveOrderUnitRequest;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.OperationLease;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.repository.OperationLeaseRepository;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.OrderAssetService;
import dev.buhanzaz.rwms.asset.service.OrderUnitReservationConflictException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
class OrderAssetServiceIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired OrderAssetService orders;
  @Autowired AssetService assets;
  @Autowired AssetEventStore events;
  @Autowired OperationLeaseRepository leases;
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
  void concurrentReservationHasOneWinnerThenReleaseCanReplayAndAnotherOrderCanAdd()
      throws Exception {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = freeRental(UUID.randomUUID(), warehouseId).id();
    UUID firstOrderId = UUID.randomUUID();
    UUID secondOrderId = UUID.randomUUID();
    ReserveOrderUnitRequest firstRequest =
        reserveRequest(warehouseId, rentalItemId, UUID.randomUUID());
    ReserveOrderUnitRequest secondRequest =
        reserveRequest(warehouseId, rentalItemId, UUID.randomUUID());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);

    List<ReservationAttempt> attempts;
    try {
      var first =
          executor.submit(
              () ->
                  reserveConcurrently(
                      ready, start, UUID.randomUUID(), firstOrderId, firstRequest));
      var second =
          executor.submit(
              () ->
                  reserveConcurrently(
                      ready, start, UUID.randomUUID(), secondOrderId, secondRequest));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      attempts =
          List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }

    assertThat(attempts.stream().filter(value -> value.response() != null)).hasSize(1);
    assertThat(attempts.stream().filter(value -> value.failure() != null)).hasSize(1);
    ReservationAttempt failed =
        attempts.stream().filter(value -> value.failure() != null).findFirst().orElseThrow();
    assertThat(failed.failure())
        .isInstanceOf(OrderUnitReservationConflictException.class);
    assertThat(((OrderUnitReservationConflictException) failed.failure()).code())
        .isEqualTo("UNIT_ALREADY_RESERVED");
    ReservationAttempt winner =
        attempts.stream().filter(value -> value.response() != null).findFirst().orElseThrow();
    UUID losingOrderId =
        winner.orderId().equals(firstOrderId) ? secondOrderId : firstOrderId;
    ReserveOrderUnitRequest losingRequest =
        winner.orderId().equals(firstOrderId) ? secondRequest : firstRequest;

    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from order_unit_reservation
                where rental_item_id=? and state='ACTIVE'
                """,
                Integer.class,
                rentalItemId))
        .isEqualTo(1);
    assertThat(orders.units(winner.orderId()))
        .extracting(OrderUnitReservationView::rentalItemId)
        .containsExactly(rentalItemId);
    assertThat(orders.units(losingOrderId)).isEmpty();
    assertThat(orders.candidates(winner.orderId(), warehouseId, 0, 20, "").content())
        .anySatisfy(
            candidate -> {
              assertThat(candidate.unit().id()).isEqualTo(rentalItemId);
              assertThat(candidate.added()).isTrue();
            });
    assertThat(orders.candidates(losingOrderId, warehouseId, 0, 200, "").content())
        .noneSatisfy(candidate -> assertThat(candidate.unit().id()).isEqualTo(rentalItemId));

    OrderActorRequest releasingActor =
        new OrderActorRequest(winner.actorSubjectId(), "RENTAL_MANAGER");
    UUID releaseKey = UUID.randomUUID();
    var released =
        orders.release(
            releaseKey, winner.orderId(), rentalItemId, releasingActor);
    var releaseReplay =
        orders.release(
            releaseKey, winner.orderId(), rentalItemId, releasingActor);
    assertThat(released.replayed()).isFalse();
    assertThat(released.response().state()).isEqualTo("RELEASED");
    assertThat(releaseReplay.replayed()).isTrue();
    assertThat(releaseReplay.response().reservationId())
        .isEqualTo(released.response().reservationId());
    assertThat(releaseReplay.response().replayed()).isTrue();

    var readded =
        orders.reserve(UUID.randomUUID(), losingOrderId, losingRequest);
    assertThat(readded.replayed()).isFalse();
    assertThat(readded.response().reservationId())
        .isNotEqualTo(released.response().reservationId());
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from order_unit_reservation
                where rental_item_id=? and state='ACTIVE'
                """,
                Integer.class,
                rentalItemId))
        .isEqualTo(1);

    OrderActorRequest losingActor =
        new OrderActorRequest(losingRequest.actorSubjectId(), "RENTAL_MANAGER");
    UUID releaseAllKey = UUID.randomUUID();
    var releasedAll = orders.releaseAll(releaseAllKey, losingOrderId, losingActor);
    var releaseAllReplay = orders.releaseAll(releaseAllKey, losingOrderId, losingActor);
    assertThat(releasedAll.response()).hasSize(1);
    assertThat(releaseAllReplay.replayed()).isTrue();
    assertThat(releaseAllReplay.response())
        .extracting(OrderUnitReservationView::reservationId)
        .containsExactly(readded.response().reservationId());
    assertThat(releaseAllReplay.response()).allMatch(OrderUnitReservationView::replayed);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from order_unit_reservation
                where rental_item_id=? and state='ACTIVE'
                """,
                Integer.class,
                rentalItemId))
        .isZero();
  }

  @Test
  @Transactional
  void requiredEquipmentQuantityUsesLedgerAndRejectsStaleOrInsufficientStock() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse rental = freeRental(actorSubjectId, warehouseId);
    UUID orderId = UUID.randomUUID();
    orders.reserve(
        UUID.randomUUID(),
        orderId,
        reserveRequest(warehouseId, rental.id(), actorSubjectId));
    UUID equipmentId =
        assets
            .createEquipment(
                actorSubjectId,
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "ORDER-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                    "Order equipment",
                    EquipmentCategory.FURNITURE,
                    null))
            .response()
            .id();
    seedStockBalance(equipmentId, warehouseId, 8);

    UUID addKey = UUID.randomUUID();
    AdjustOrderEquipmentRequest addRequest =
        equipmentRequest(actorSubjectId, 0, 3);
    var added =
        orders.adjustEquipment(
            addKey, orderId, rental.id(), equipmentId, addRequest);
    var addReplay =
        orders.adjustEquipment(
            addKey, orderId, rental.id(), equipmentId, addRequest);
    assertThat(added.replayed()).isFalse();
    assertThat(added.response().previousQuantity()).isZero();
    assertThat(added.response().requiredQuantity()).isEqualTo(3);
    assertThat(added.response().delta()).isEqualTo(3);
    assertThat(added.response().movement().kind()).isEqualTo("STOCK_TO_CABIN");
    assertThat(addReplay.replayed()).isTrue();
    assertThat(addReplay.response()).isEqualTo(added.response());

    var increased =
        orders.adjustEquipment(
            UUID.randomUUID(),
            orderId,
            rental.id(),
            equipmentId,
            equipmentRequest(actorSubjectId, 3, 6));
    var decreased =
        orders.adjustEquipment(
            UUID.randomUUID(),
            orderId,
            rental.id(),
            equipmentId,
            equipmentRequest(actorSubjectId, 6, 2));
    assertThat(increased.response().delta()).isEqualTo(3);
    assertThat(decreased.response().delta()).isEqualTo(-4);
    assertThat(decreased.response().movement().kind()).isEqualTo("CABIN_TO_STOCK");
    assertThat(balance(equipmentId, warehouseId, rental.id(), "CABIN_NON_RENTED"))
        .isEqualTo(2);
    assertThat(balance(equipmentId, warehouseId, null, "STOCK")).isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from equipment_movement where equipment_id=?",
                Integer.class,
                equipmentId))
        .isEqualTo(3);

    assertConflict(
        "EQUIPMENT_QUANTITY_CONFLICT",
        () ->
            orders.adjustEquipment(
                UUID.randomUUID(),
                orderId,
                rental.id(),
                equipmentId,
                equipmentRequest(actorSubjectId, 1, 2)));
    assertConflict(
        "INSUFFICIENT_STOCK",
        () ->
            orders.adjustEquipment(
                UUID.randomUUID(),
                orderId,
                rental.id(),
                equipmentId,
                equipmentRequest(actorSubjectId, 2, 9)));
    assertThat(balance(equipmentId, warehouseId, rental.id(), "CABIN_NON_RENTED"))
        .isEqualTo(2);
    assertThat(balance(equipmentId, warehouseId, null, "STOCK")).isEqualTo(6);
  }

  @Test
  void reserveReturnsExactAvailabilityAndWarehouseConflicts() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse free = freeRental(actorSubjectId, warehouseId);
    assertConflict(
        "UNIT_WAREHOUSE_MISMATCH",
        () ->
            orders.reserve(
                UUID.randomUUID(),
                UUID.randomUUID(),
                reserveRequest(UUID.randomUUID(), free.id(), actorSubjectId)));

    RentalItemResponse unavailable =
        assets
            .createRentalItem(
                actorSubjectId,
                UUID.randomUUID(),
                rentalRequest(warehouseId))
            .response();
    assertThat(unavailable.status()).isEqualTo(RentalItemStatus.NEW);
    assertConflict(
        "UNIT_NOT_AVAILABLE",
        () ->
            orders.reserve(
                UUID.randomUUID(),
                UUID.randomUUID(),
                reserveRequest(warehouseId, unavailable.id(), actorSubjectId)));

    RentalItemResponse warehouseCandidate = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse warehouseOnly =
        assets.updateStatus(
            warehouseCandidate.id(),
            new UpdateStatusRequest(
                warehouseCandidate.version(), RentalItemStatus.WAREHOUSE));
    assertThat(
            orders
                .candidates(
                    UUID.randomUUID(), warehouseId, 0, 20, warehouseOnly.number())
                .content())
        .isEmpty();
    assertConflict(
        "UNIT_NOT_AVAILABLE",
        () ->
            orders.reserve(
                UUID.randomUUID(),
                UUID.randomUUID(),
                reserveRequest(
                    warehouseId, warehouseOnly.id(), actorSubjectId)));

    RentalItemResponse rentalCandidate = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse rented =
        assets.updateStatus(
            rentalCandidate.id(),
            new UpdateStatusRequest(rentalCandidate.version(), RentalItemStatus.RENTED));
    assertThat(
            orders
                .candidates(UUID.randomUUID(), warehouseId, 0, 20, rented.number())
                .content())
        .isEmpty();
  }

  @Test
  void candidatesHideLiveLeaseButKeepExpiredAndCurrentOrderUnitsVisible() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    RentalItemResponse liveLeased = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse expiredLeased = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse currentOrderUnit = freeRental(actorSubjectId, warehouseId);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    saveLease(liveLeased.id(), now.minusMinutes(1), now.plusMinutes(10));
    saveLease(expiredLeased.id(), now.minusMinutes(10), now.minusMinutes(1));
    orders.reserve(
        UUID.randomUUID(),
        orderId,
        reserveRequest(warehouseId, currentOrderUnit.id(), actorSubjectId));
    saveLease(currentOrderUnit.id(), now.minusMinutes(1), now.plusMinutes(10));

    assertThat(
            orders
                .candidates(orderId, warehouseId, 0, 20, liveLeased.number())
                .content())
        .isEmpty();
    assertThat(
            orders
                .candidates(orderId, warehouseId, 0, 20, expiredLeased.number())
                .content())
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.unit().id()).isEqualTo(expiredLeased.id());
          assertThat(candidate.added()).isFalse();
        });
    assertThat(
            orders
                .candidates(orderId, warehouseId, 0, 20, currentOrderUnit.number())
                .content())
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.unit().id()).isEqualTo(currentOrderUnit.id());
          assertThat(candidate.added()).isTrue();
        });
  }

  @Test
  void reservedOrderUnitRejectsEveryLeaseAcquisitionUntilRelease() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    RentalItemResponse rental = freeRental(actorSubjectId, warehouseId);
    orders.reserve(
        UUID.randomUUID(),
        orderId,
        reserveRequest(warehouseId, rental.id(), actorSubjectId));

    assertReservedLeaseConflict(
        () ->
            assets.acquireLease(
                actorSubjectId,
                UUID.randomUUID(),
                new AcquireOperationLeaseRequest(
                    rental.id(), "ORDER_TEST", UUID.randomUUID().toString(), rental.version())));
    assertReservedLeaseConflict(
        () ->
            assets.acquireMaintenanceLease(
                actorSubjectId,
                UUID.randomUUID(),
                new AcquireMaintenanceOperationLeaseRequest(
                    rental.id(),
                    MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
                    UUID.randomUUID(),
                    rental.version())));
    assertReservedLeaseConflict(
        () ->
            assets.acquireLogisticsLease(
                actorSubjectId,
                UUID.randomUUID(),
                new AcquireLogisticsOperationLeaseRequest(
                    rental.id(),
                    LogisticsLeaseOwnerType.LOGISTICS_RETURN,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    rental.version())));
    assertThat(activeLeaseCount(rental.id())).isZero();

    orders.release(
        UUID.randomUUID(),
        orderId,
        rental.id(),
        new OrderActorRequest(actorSubjectId, "RENTAL_MANAGER"));
    var acquired =
        assets.acquireLease(
            actorSubjectId,
            UUID.randomUUID(),
            new AcquireOperationLeaseRequest(
                rental.id(), "ORDER_TEST", UUID.randomUUID().toString(), rental.version()));

    assertThat(acquired.replayed()).isFalse();
    assertThat(acquired.response().state()).isEqualTo("ACTIVE");
    assertThat(activeLeaseCount(rental.id())).isEqualTo(1);
  }

  @Test
  void publicWarehouseAndIncompatibleStatusChangesRequireReservationRelease() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID nextWarehouseId = UUID.randomUUID();
    RentalItemResponse rental = freeRental(actorSubjectId, warehouseId);
    UUID orderId = UUID.randomUUID();
    orders.reserve(
        UUID.randomUUID(),
        orderId,
        reserveRequest(warehouseId, rental.id(), actorSubjectId));

    assertThatThrownBy(
            () ->
                assets.updateWarehouse(
                    rental.id(),
                    new UpdateWarehouseRequest(rental.version(), nextWarehouseId)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("cannot change warehouse");
    assertThatThrownBy(
            () ->
                assets.updateStatus(
                    rental.id(),
                    new UpdateStatusRequest(rental.version(), RentalItemStatus.RENTED)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("incompatible status");
    assertThat(assets.rentalItem(rental.id()).warehouseId()).isEqualTo(warehouseId);
    assertThat(assets.rentalItem(rental.id()).status()).isEqualTo(RentalItemStatus.FREE);

    orders.release(
        UUID.randomUUID(),
        orderId,
        rental.id(),
        new OrderActorRequest(actorSubjectId, "RENTAL_MANAGER"));
    RentalItemResponse moved =
        assets.updateWarehouse(
            rental.id(), new UpdateWarehouseRequest(rental.version(), nextWarehouseId));
    RentalItemResponse rented =
        assets.updateStatus(
            rental.id(),
            new UpdateStatusRequest(moved.version(), RentalItemStatus.RENTED));
    assertThat(rented.warehouseId()).isEqualTo(nextWarehouseId);
    assertThat(rented.status()).isEqualTo(RentalItemStatus.RENTED);
  }

  private ReservationAttempt reserveConcurrently(
      CountDownLatch ready,
      CountDownLatch start,
      UUID key,
      UUID orderId,
      ReserveOrderUnitRequest request)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      return new ReservationAttempt(
          orderId, request.actorSubjectId(), null, new IllegalStateException("Start timed out"));
    }
    try {
      return new ReservationAttempt(
          orderId,
          request.actorSubjectId(),
          orders.reserve(key, orderId, request).response(),
          null);
    } catch (RuntimeException exception) {
      return new ReservationAttempt(orderId, request.actorSubjectId(), null, exception);
    }
  }

  private RentalItemResponse freeRental(UUID actorSubjectId, UUID warehouseId) {
    RentalItemResponse created =
        assets
            .createRentalItem(
                actorSubjectId,
                UUID.randomUUID(),
                rentalRequest(warehouseId))
            .response();
    return assets.updateStatus(
        created.id(), new UpdateStatusRequest(created.version(), RentalItemStatus.FREE));
  }

  private CreateRentalItemRequest rentalRequest(UUID warehouseId) {
    return new CreateRentalItemRequest(
        warehouseId,
        "ORDER-CABIN-" + UUID.randomUUID(),
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of(),
        List.of());
  }

  private static ReserveOrderUnitRequest reserveRequest(
      UUID warehouseId, UUID rentalItemId, UUID actorSubjectId) {
    return new ReserveOrderUnitRequest(
        warehouseId, rentalItemId, actorSubjectId, "RENTAL_MANAGER");
  }

  private static AdjustOrderEquipmentRequest equipmentRequest(
      UUID actorSubjectId, long expected, long required) {
    return new AdjustOrderEquipmentRequest(
        actorSubjectId, "RENTAL_MANAGER", expected, required);
  }

  private void saveLease(
      UUID rentalItemId, OffsetDateTime acquiredAt, OffsetDateTime expiresAt) {
    leases.saveAndFlush(
        OperationLease.acquire(
            rentalItemId,
            "ORDER_TEST",
            UUID.randomUUID().toString(),
            1,
            UUID.randomUUID(),
            acquiredAt,
            expiresAt));
  }

  private int activeLeaseCount(UUID rentalItemId) {
    return jdbc.queryForObject(
        "select count(*) from operation_lease where rental_item_id=? and state='ACTIVE'",
        Integer.class,
        rentalItemId);
  }

  private static void assertReservedLeaseConflict(Runnable command) {
    assertThatThrownBy(command::run)
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Reserved order unit cannot acquire an operation lease");
  }

  private void seedStockBalance(UUID equipmentId, UUID warehouseId, long quantity) {
    UUID balanceId = UUID.randomUUID();
    jdbc.update(
        """
        insert into equipment_balance(
          id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,
          created_at,updated_at)
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
            "balanceId",
            balanceId.toString(),
            "equipmentId",
            equipmentId.toString(),
            "warehouseId",
            warehouseId.toString(),
            "locationKind",
            BalanceLocationKind.STOCK.name(),
            "quantity",
            quantity),
        Map.of(
            "balanceId",
            balanceId.toString(),
            "version",
            0,
            "locationKind",
            BalanceLocationKind.STOCK.name(),
            "quantity",
            quantity));
  }

  private long balance(
      UUID equipmentId, UUID warehouseId, UUID rentalItemId, String locationKind) {
    return jdbc.queryForObject(
        """
        select quantity from equipment_balance
        where equipment_id=? and warehouse_id=? and rental_item_id is not distinct from ?
          and location_kind=?
        """,
        Long.class,
        equipmentId,
        warehouseId,
        rentalItemId,
        locationKind);
  }

  private static void assertConflict(String code, Runnable command) {
    assertThatThrownBy(command::run)
        .isInstanceOf(OrderUnitReservationConflictException.class)
        .satisfies(
            exception ->
                assertThat(((OrderUnitReservationConflictException) exception).code())
                    .isEqualTo(code));
  }

  private record ReservationAttempt(
      UUID orderId,
      UUID actorSubjectId,
      OrderUnitReservationView response,
      RuntimeException failure) {}
}
