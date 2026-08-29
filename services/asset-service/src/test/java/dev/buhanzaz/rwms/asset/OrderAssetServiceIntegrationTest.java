package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireLogisticsOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireLogisticsEquipmentMovementReservationRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireMaintenanceOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsEquipmentMovementPurpose;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsEquipmentMovementReservationResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceFencedStatusRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceStatusAction;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ReleaseMaintenanceOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ExecuteLogisticsEquipmentMovementReservationLine;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ExecuteLogisticsEquipmentMovementReservationsRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateStatusRequest;
import dev.buhanzaz.rwms.asset.administrative.AdministrativeAssetCorrectionService;
import dev.buhanzaz.rwms.asset.api.AdministrativeAssetCorrectionApiModels.AdministrativeCorrectionAssetKind;
import dev.buhanzaz.rwms.asset.api.AdministrativeAssetCorrectionApiModels.CreateCabinAdministrativeCorrectionRequest;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderActorRequest;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderEquipmentRequirement;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderEquipmentReservationView;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderFurnitureMovementPlan;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderFurnitureMovementPlanLine;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderFurnitureMovementPlanRequest;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitEquipmentRequirements;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitReplacement;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitReplacementMovementBundle;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitReplacementMovementLine;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitReservationView;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitsReplacementReceipt;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.ReplaceOrderEquipmentReservationsRequest;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.ReplaceOrderUnitsRequest;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.ReserveOrderUnitRequest;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.ConvertPresentationHoldsRequest;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.CabinAvailabilityRequest;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.ReplacePresentationHoldsRequest;
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
import dev.buhanzaz.rwms.asset.service.PresentationHoldService;
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
class OrderAssetServiceIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired OrderAssetService orders;
  @Autowired PresentationHoldService presentationHolds;
  @Autowired AssetService assets;
  @Autowired AdministrativeAssetCorrectionService administrativeCorrections;
  @Autowired AssetEventStore events;
  @Autowired OperationLeaseRepository leases;
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
    ReserveOrderUnitRequest winningRequest =
        winner.orderId().equals(firstOrderId) ? firstRequest : secondRequest;
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
    assertThat(assets.rentalItem(rentalItemId))
        .satisfies(
            booked -> {
              assertThat(booked.status()).isEqualTo(RentalItemStatus.BOOKED);
              assertThat(booked.activeOrderReservation()).isNotNull();
              assertThat(booked.activeOrderReservation().orderId()).isEqualTo(winner.orderId());
              assertThat(booked.activeOrderReservation().clientId())
                  .isEqualTo(winningRequest.clientId());
              assertThat(booked.activeOrderReservation().tenantSnapshot())
                  .isEqualTo(winningRequest.tenantSnapshot());
            });
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
    assertThat(assets.rentalItem(rentalItemId).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(assets.rentalItem(rentalItemId).activeOrderReservation()).isNull();

    var readded =
        orders.reserve(UUID.randomUUID(), losingOrderId, losingRequest);
    assertThat(readded.replayed()).isFalse();
    assertThat(readded.response().reservationId())
        .isNotEqualTo(released.response().reservationId());
    assertThat(assets.rentalItem(rentalItemId).status()).isEqualTo(RentalItemStatus.BOOKED);
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
    assertThat(assets.rentalItem(rentalItemId).status()).isEqualTo(RentalItemStatus.FREE);
  }

  @Test
  void releasesAnExpiredDraftReservationAndRestoresTheCabinToFree() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = freeRental(UUID.randomUUID(), warehouseId).id();
    UUID orderId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    ReserveOrderUnitRequest request =
        new ReserveOrderUnitRequest(
            warehouseId,
            rentalItemId,
            UUID.randomUUID(),
            "ООО Черновой резерв",
            OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
            actorId,
            "RENTAL_MANAGER");

    OrderUnitReservationView reserved =
        orders.reserve(UUID.randomUUID(), orderId, request).response();
    jdbc.update(
        "update order_unit_reservation set draft_reservation_expires_at=? where id=?",
        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
        reserved.reservationId());

    orders.releaseExpiredDraftReservations();

    assertThat(orders.units(orderId)).isEmpty();
    assertThat(assets.rentalItem(rentalItemId).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(
            jdbc.queryForObject(
                "select state from order_unit_reservation where id=?",
                String.class,
                reserved.reservationId()))
        .isEqualTo("RELEASED");
  }

  @Test
  @Transactional
  void desiredEquipmentReservesTheCatalogueWithoutMovingItAndPlansOnlyTheDifference() {
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
                    "Order equipment",
                    EquipmentCategory.FURNITURE,
                    null))
            .response()
            .id();
    seedStockBalance(equipmentId, warehouseId, 8);
    seedCabinBalance(equipmentId, warehouseId, rental.id(), 1);

    List<OrderEquipmentRequirement> requirements =
        List.of(new OrderEquipmentRequirement(equipmentId, 3L));
    ReplaceOrderEquipmentReservationsRequest request =
        equipmentReservationRequest(actorSubjectId, warehouseId, rental.id(), requirements);
    UUID reservationKey = UUID.randomUUID();
    var reserved = orders.replaceEquipmentReservations(reservationKey, orderId, request);
    var reservationReplay =
        orders.replaceEquipmentReservations(reservationKey, orderId, request);
    assertThat(reserved.replayed()).isFalse();
    assertThat(reserved.response()).singleElement().satisfies(
        value -> {
          assertThat(value.equipmentId()).isEqualTo(equipmentId);
          assertThat(value.quantity()).isEqualTo(3);
          assertThat(value.availableQuantity()).isEqualTo(6);
        });
    assertThat(reservationReplay.replayed()).isTrue();
    assertThat(balance(equipmentId, warehouseId, rental.id(), "CABIN_NON_RENTED"))
        .isEqualTo(1);
    assertThat(balance(equipmentId, warehouseId, null, "STOCK")).isEqualTo(8);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from equipment_movement where equipment_id=?",
                Integer.class,
                equipmentId))
        .isZero();

    var plan =
        orders.furnitureMovementPlan(
            orderId,
            new OrderFurnitureMovementPlanRequest(
                warehouseId,
                rental.id(),
                null,
                requirements,
                List.of(new OrderUnitEquipmentRequirements(rental.id(), requirements))));
    assertThat(plan.lines()).singleElement().satisfies(
        line -> {
          assertThat(line.quantity()).isEqualTo(2);
          assertThat(line.sourceLocationKind()).isEqualTo(BalanceLocationKind.STOCK);
          assertThat(line.targetLocationKind()).isEqualTo(BalanceLocationKind.CABIN_NON_RENTED);
          assertThat(line.targetRentalItemId()).isEqualTo(rental.id());
        });

    jdbc.update(
        """
        update equipment_balance
        set quantity=4
        where equipment_id=? and warehouse_id=? and rental_item_id=?
          and location_kind='CABIN_NON_RENTED'
        """,
        equipmentId,
        warehouseId,
        rental.id());
    var surplusPlan =
        orders.furnitureMovementPlan(
            orderId,
            new OrderFurnitureMovementPlanRequest(
                warehouseId,
                rental.id(),
                null,
                requirements,
                List.of(new OrderUnitEquipmentRequirements(rental.id(), requirements))));
    assertThat(surplusPlan.lines()).singleElement().satisfies(
        line -> {
          assertThat(line.quantity()).isEqualTo(1);
          assertThat(line.sourceRentalItemId()).isEqualTo(rental.id());
          assertThat(line.sourceLocationKind())
              .isEqualTo(BalanceLocationKind.CABIN_NON_RENTED);
          assertThat(line.targetRentalItemId()).isNull();
          assertThat(line.targetLocationKind()).isEqualTo(BalanceLocationKind.STOCK);
        });

    RentalItemResponse otherRental = freeRental(actorSubjectId, warehouseId);
    UUID otherOrderId = UUID.randomUUID();
    orders.reserve(
        UUID.randomUUID(),
        otherOrderId,
        reserveRequest(warehouseId, otherRental.id(), actorSubjectId));
    assertConflict(
        "INSUFFICIENT_EQUIPMENT",
        () ->
            orders.replaceEquipmentReservations(
                UUID.randomUUID(),
                otherOrderId,
                equipmentReservationRequest(
                    actorSubjectId,
                    warehouseId,
                    otherRental.id(),
                    List.of(new OrderEquipmentRequirement(equipmentId, 10L)))));
  }

  @Test
  void equipmentMaximumIsPerCabinAndSharedAvailabilityExcludesOtherOrders() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    RentalItemResponse first = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse second = freeRental(actorSubjectId, warehouseId);
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(warehouseId, first.id(), actorSubjectId));
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(warehouseId, second.id(), actorSubjectId));
    UUID equipmentId =
        assets
            .createEquipment(
                actorSubjectId,
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "Per-cabin bed " + UUID.randomUUID(),
                    EquipmentCategory.FURNITURE,
                    null,
                    4))
            .response()
            .id();
    seedStockBalance(equipmentId, warehouseId, 10);

    assertConflict(
        "EQUIPMENT_MAXIMUM_PER_CABIN_EXCEEDED",
        () ->
            orders.replaceEquipmentReservations(
                UUID.randomUUID(),
                orderId,
                new ReplaceOrderEquipmentReservationsRequest(
                    warehouseId,
                    actorSubjectId,
                    "RENTAL_MANAGER",
                    List.of(
                        new OrderUnitEquipmentRequirements(
                            first.id(),
                            List.of(new OrderEquipmentRequirement(equipmentId, 5L))),
                        new OrderUnitEquipmentRequirements(second.id(), List.of())))));

    var reserved =
        orders.replaceEquipmentReservations(
            UUID.randomUUID(),
            orderId,
            new ReplaceOrderEquipmentReservationsRequest(
                warehouseId,
                actorSubjectId,
                "RENTAL_MANAGER",
                List.of(
                    new OrderUnitEquipmentRequirements(
                        first.id(), List.of(new OrderEquipmentRequirement(equipmentId, 4L))),
                    new OrderUnitEquipmentRequirements(
                        second.id(), List.of(new OrderEquipmentRequirement(equipmentId, 4L))))));
    assertThat(reserved.response()).singleElement().satisfies(
        value -> {
          assertThat(value.quantity()).isEqualTo(8);
          assertThat(value.availableQuantity()).isEqualTo(2);
          assertThat(value.maximumPerCabin()).isEqualTo(4);
        });

    UUID otherOrderId = UUID.randomUUID();
    RentalItemResponse other = freeRental(actorSubjectId, warehouseId);
    orders.reserve(
        UUID.randomUUID(),
        otherOrderId,
        reserveRequest(warehouseId, other.id(), actorSubjectId));
    assertConflict(
        "INSUFFICIENT_EQUIPMENT",
        () ->
            orders.replaceEquipmentReservations(
                UUID.randomUUID(),
                otherOrderId,
                equipmentReservationRequest(
                    actorSubjectId,
                    warehouseId,
                    other.id(),
                    List.of(new OrderEquipmentRequirement(equipmentId, 3L)))));
  }

  @Test
  void concurrentEquipmentReservationsCannotOverbookSharedStock() throws Exception {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID firstOrderId = UUID.randomUUID();
    UUID secondOrderId = UUID.randomUUID();
    RentalItemResponse first = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse second = freeRental(actorSubjectId, warehouseId);
    orders.reserve(
        UUID.randomUUID(),
        firstOrderId,
        reserveRequest(warehouseId, first.id(), actorSubjectId));
    orders.reserve(
        UUID.randomUUID(),
        secondOrderId,
        reserveRequest(warehouseId, second.id(), actorSubjectId));
    UUID equipmentId =
        assets
            .createEquipment(
                actorSubjectId,
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "Concurrent bed " + UUID.randomUUID(), EquipmentCategory.FURNITURE, null))
            .response()
            .id();
    seedStockBalance(equipmentId, warehouseId, 4);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);

    Throwable firstFailure;
    Throwable secondFailure;
    try {
      var firstAttempt =
          executor.submit(
              () ->
                  replaceEquipmentConcurrently(
                      ready,
                      start,
                      firstOrderId,
                      equipmentReservationRequest(
                          actorSubjectId,
                          warehouseId,
                          first.id(),
                          List.of(new OrderEquipmentRequirement(equipmentId, 4L)))));
      var secondAttempt =
          executor.submit(
              () ->
                  replaceEquipmentConcurrently(
                      ready,
                      start,
                      secondOrderId,
                      equipmentReservationRequest(
                          actorSubjectId,
                          warehouseId,
                          second.id(),
                          List.of(new OrderEquipmentRequirement(equipmentId, 4L)))));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      firstFailure = firstAttempt.get(30, TimeUnit.SECONDS);
      secondFailure = secondAttempt.get(30, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertThat(java.util.stream.Stream.of(firstFailure, secondFailure)
            .filter(java.util.Objects::isNull))
        .hasSize(1);
    Throwable failure = firstFailure == null ? secondFailure : firstFailure;
    assertThat(failure).isInstanceOf(OrderUnitReservationConflictException.class);
    assertThat(((OrderUnitReservationConflictException) failure).code())
        .isEqualTo("INSUFFICIENT_EQUIPMENT");
    assertThat(
            jdbc.queryForObject(
                "select coalesce(sum(quantity),0) from order_equipment_reservation where equipment_id=? and state='ACTIVE'",
                Long.class,
                equipmentId))
        .isEqualTo(4L);
  }

  @Test
  void atomicReplacementConsumesOwnPresentationHoldAndLeavesOldCabinFenced() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    RentalItemResponse old = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse replacement = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse firstAlternative = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse secondAlternative = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse otherWarehouse = freeRental(actorSubjectId, UUID.randomUUID());
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(warehouseId, old.id(), actorSubjectId));
    RentalItemResponse booked = assets.rentalItem(old.id());
    assertThatThrownBy(
            () ->
                assets.acquireMaintenanceLease(
                    actorSubjectId,
                    UUID.randomUUID(),
                    new AcquireMaintenanceOperationLeaseRequest(
                        old.id(),
                        MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
                        UUID.randomUUID(),
                        booked.version())))
        .isInstanceOf(OrderUnitReservationConflictException.class)
        .satisfies(
            failure ->
                assertThat(((OrderUnitReservationConflictException) failure).code())
                    .isEqualTo("BOOKED_UNIT_REPLACEMENT_REQUIRED"));

    assertConflict(
        "UNIT_WAREHOUSE_MISMATCH",
        () ->
            orders.replaceUnits(
                UUID.randomUUID(),
                orderId,
                replacementRequest(
                    warehouseId,
                    old.id(),
                    otherWarehouse.id(),
                    null,
                    actorSubjectId,
                    List.of(),
                    null)));
    assertConflict(
        "REPLACEMENT_MOVEMENT_NOT_REQUIRED",
        () ->
            orders.replaceUnits(
                UUID.randomUUID(),
                orderId,
                replacementRequest(
                    warehouseId,
                    old.id(),
                    replacement.id(),
                    null,
                    actorSubjectId,
                    List.of(),
                    new OrderUnitReplacementMovementBundle(
                        UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10), List.of()))));

    UUID presentationId = UUID.randomUUID();
    presentationHolds.replace(
        UUID.randomUUID(),
        presentationId,
        new ReplacePresentationHoldsRequest(
            warehouseId,
            List.of(replacement.id(), firstAlternative.id(), secondAlternative.id()),
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
            actorSubjectId,
            "WAREHOUSE_MANAGER",
            null));
    assertConflict(
        "REPLACEMENT_UNIT_PRESENTATION_HELD",
        () ->
            orders.replaceUnits(
                UUID.randomUUID(),
                orderId,
                replacementRequest(
                    warehouseId,
                    old.id(),
                    replacement.id(),
                    null,
                    actorSubjectId,
                    List.of(),
                    null)));
    assertConflict(
        "REPLACEMENT_PRESENTATION_HOLD_REQUIRED",
        () ->
            orders.replaceUnits(
                UUID.randomUUID(),
                orderId,
                replacementRequest(
                    warehouseId,
                    old.id(),
                    replacement.id(),
                    UUID.randomUUID(),
                    actorSubjectId,
                    List.of(),
                    null)));

    ReplaceOrderUnitsRequest request =
        replacementRequest(
            warehouseId,
            old.id(),
            replacement.id(),
            presentationId,
            actorSubjectId,
            List.of(),
            null);
    UUID idempotencyKey = UUID.randomUUID();
    var result = orders.replaceUnits(idempotencyKey, orderId, request);
    var replay = orders.replaceUnits(idempotencyKey, orderId, request);
    assertThat(result.response().replacements()).singleElement().satisfies(
        receipt -> {
          assertThat(receipt.contentReady()).isTrue();
          assertThat(receipt.movementReservations()).isEmpty();
        });
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response().replayed()).isTrue();
    assertThat(orders.units(orderId))
        .extracting(OrderUnitReservationView::rentalItemId)
        .containsExactly(replacement.id());
    assertThat(assets.rentalItem(old.id())).satisfies(
        value -> {
          assertThat(value.status()).isEqualTo(RentalItemStatus.BOOKED);
          assertThat(value.activeOrderReservation()).isNull();
        });
    assertThat(assets.rentalItem(replacement.id()).status()).isEqualTo(RentalItemStatus.BOOKED);
    assertThat(
            jdbc.queryForList(
                "select rental_item_id,state from presentation_unit_hold where presentation_id=? order by rental_item_id",
                presentationId))
        .anySatisfy(
            row -> {
              assertThat(row.get("rental_item_id")).isEqualTo(replacement.id());
              assertThat(row.get("state")).isEqualTo("CONVERTED");
            })
        .filteredOn(row -> !replacement.id().equals(row.get("rental_item_id")))
        .extracting(row -> row.get("state"))
        .containsOnly("RELEASED");
    assertThat(assets.rentalItem(firstAlternative.id()).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(assets.rentalItem(secondAlternative.id()).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(orders.candidates(orderId, warehouseId, 0, 200, "").content())
        .extracting(candidate -> candidate.unit().id())
        .contains(firstAlternative.id(), secondAlternative.id());
    assertThat(orders.candidates(orderId, warehouseId, 0, 200, old.number()).content())
        .noneSatisfy(candidate -> assertThat(candidate.unit().id()).isEqualTo(old.id()));

    RentalItemResponse fencedOld = assets.rentalItem(old.id());
    UUID maintenanceOwnerId = UUID.randomUUID();
    var maintenanceLease =
        assets.acquireMaintenanceLease(
            actorSubjectId,
            UUID.randomUUID(),
            new AcquireMaintenanceOperationLeaseRequest(
                old.id(),
                MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
                maintenanceOwnerId,
                fencedOld.version()));
    assertThat(maintenanceLease.response().state()).isEqualTo("ACTIVE");
    var queued =
        assets.maintenanceFencedStatus(
            actorSubjectId,
            UUID.randomUUID(),
            old.id(),
            new MaintenanceFencedStatusRequest(
                fencedOld.version(),
                MaintenanceStatusAction.QUEUE_FOR_REPAIR,
                maintenanceLease.response().id(),
                maintenanceLease.response().fencingToken(),
                MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
                maintenanceOwnerId,
                null));
    assertThat(queued.response().status()).isEqualTo(RentalItemStatus.REPAIR);
  }

  @Test
  void concurrentPresentationReplacementConfirmationHasOneAtomicWinner() throws Exception {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    RentalItemResponse old = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse replacement = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse alternative = freeRental(actorSubjectId, warehouseId);
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(warehouseId, old.id(), actorSubjectId));
    UUID presentationId = UUID.randomUUID();
    presentationHolds.replace(
        UUID.randomUUID(),
        presentationId,
        new ReplacePresentationHoldsRequest(
            warehouseId,
            List.of(replacement.id(), alternative.id()),
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
            actorSubjectId,
            "WAREHOUSE_MANAGER",
            null));
    ReplaceOrderUnitsRequest request =
        replacementRequest(
            warehouseId,
            old.id(),
            replacement.id(),
            presentationId,
            actorSubjectId,
            List.of(),
            null);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    List<ReplacementAttempt> attempts;
    try {
      var first =
          executor.submit(
              () -> replaceUnitsConcurrently(ready, start, orderId, request));
      var second =
          executor.submit(
              () -> replaceUnitsConcurrently(ready, start, orderId, request));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      attempts = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }

    assertThat(attempts).filteredOn(attempt -> attempt.result() != null).singleElement();
    assertThat(attempts).filteredOn(attempt -> attempt.failure() != null).singleElement();
    assertThat(orders.units(orderId))
        .extracting(OrderUnitReservationView::rentalItemId)
        .containsExactly(replacement.id());
    assertThat(
            jdbc.queryForList(
                "select state from presentation_unit_hold where presentation_id=? order by rental_item_id",
                String.class,
                presentationId))
        .containsExactlyInAnyOrder("CONVERTED", "RELEASED");
    assertThat(assets.rentalItem(alternative.id()).status()).isEqualTo(RentalItemStatus.FREE);
  }

  @Test
  void replacementRejectsAnActiveOrderReservationWhoseCabinAlreadyLeftBookedState() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    RentalItemResponse old = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse replacement = freeRental(actorSubjectId, warehouseId);
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(warehouseId, old.id(), actorSubjectId));
    int activeHoldsBefore =
        jdbc.queryForObject(
            "select count(*) from equipment_allocation_hold where state='ACTIVE'",
            Integer.class);
    jdbc.update(
        "update rental_item set status='RENTED',version=version+1,updated_at=clock_timestamp() where id=?",
        old.id());

    assertConflict(
        "REPLACEMENT_UNIT_NOT_EDITABLE",
        () ->
            orders.replaceUnits(
                UUID.randomUUID(),
                orderId,
                replacementRequest(
                    warehouseId,
                    old.id(),
                    replacement.id(),
                    null,
                    actorSubjectId,
                    List.of(),
                    null)));
    assertThat(orders.units(orderId))
        .extracting(OrderUnitReservationView::rentalItemId)
        .containsExactly(old.id());
    assertThat(assets.rentalItem(replacement.id()).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from equipment_allocation_hold where state='ACTIVE'",
                Integer.class))
        .isEqualTo(activeHoldsBefore);
  }

  @Test
  void replacementContentReadinessDistinguishesMissingAndDirectlyHeldFurniture() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID equipmentId =
        assets
            .createEquipment(
                actorSubjectId,
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "Replacement bed " + UUID.randomUUID(),
                    EquipmentCategory.FURNITURE,
                    null,
                    4))
            .response()
            .id();
    seedStockBalance(equipmentId, warehouseId, 8);
    List<OrderEquipmentRequirement> desired =
        List.of(new OrderEquipmentRequirement(equipmentId, 2L));

    UUID missingOrderId = UUID.randomUUID();
    RentalItemResponse missingOld = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse missingReplacement = freeRental(actorSubjectId, warehouseId);
    orders.reserve(
        UUID.randomUUID(),
        missingOrderId,
        reserveRequest(warehouseId, missingOld.id(), actorSubjectId));
    orders.replaceEquipmentReservations(
        UUID.randomUUID(),
        missingOrderId,
        equipmentReservationRequest(actorSubjectId, warehouseId, missingOld.id(), desired));
    var missingResult =
        orders.replaceUnits(
            UUID.randomUUID(),
            missingOrderId,
            replacementRequest(
                warehouseId,
                missingOld.id(),
                missingReplacement.id(),
                null,
                actorSubjectId,
                desired,
                null));
    assertThat(missingResult.response().replacements()).singleElement().satisfies(
        receipt -> {
          assertThat(receipt.movementReservations()).isEmpty();
          assertThat(receipt.contentReady()).isFalse();
        });

    UUID directOrderId = UUID.randomUUID();
    RentalItemResponse directOld = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse directReplacement = freeRental(actorSubjectId, warehouseId);
    orders.reserve(
        UUID.randomUUID(),
        directOrderId,
        reserveRequest(warehouseId, directOld.id(), actorSubjectId));
    seedCabinBalance(equipmentId, warehouseId, directOld.id(), 2);
    orders.replaceEquipmentReservations(
        UUID.randomUUID(),
        directOrderId,
        equipmentReservationRequest(actorSubjectId, warehouseId, directOld.id(), desired));
    List<OrderUnitEquipmentRequirements> postReplacementUnits =
        List.of(new OrderUnitEquipmentRequirements(directReplacement.id(), desired));
    var plan =
        orders.furnitureMovementPlan(
            directOrderId,
            new OrderFurnitureMovementPlanRequest(
                warehouseId,
                directReplacement.id(),
                directOld.id(),
                desired,
                postReplacementUnits));
    assertThat(plan.lines()).singleElement();
    var planLine = plan.lines().getFirst();
    OrderUnitReplacementMovementBundle movement =
        new OrderUnitReplacementMovementBundle(
            UUID.randomUUID(),
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
            List.of(
                new OrderUnitReplacementMovementLine(
                    UUID.randomUUID(),
                    planLine.equipmentId(),
                    planLine.sourceBalanceId(),
                    planLine.expectedSourceBalanceVersion(),
                    directReplacement.id(),
                    planLine.quantity())));
    var directResult =
        orders.replaceUnits(
            UUID.randomUUID(),
            directOrderId,
            replacementRequest(
                warehouseId,
                directOld.id(),
                directReplacement.id(),
                null,
                actorSubjectId,
                desired,
                movement));
    assertThat(directResult.response().replacements()).singleElement().satisfies(
        receipt -> assertThat(receipt.contentReady()).isFalse());
    var directReceipt = directResult.response().replacements().getFirst();
    assertThat(directReceipt.movementReservations())
        .singleElement()
        .satisfies(
        held -> {
          assertThat(held.equipmentId()).isEqualTo(equipmentId);
          assertThat(held.sourceRentalItemId()).isEqualTo(directOld.id());
          assertThat(held.quantity()).isEqualTo(2);
          assertThat(held.state()).isEqualTo("ACTIVE");
        });
    RentalItemResponse pendingOld = assets.rentalItem(directOld.id());
    assertThatThrownBy(
            () ->
                assets.acquireMaintenanceLease(
                    actorSubjectId,
                    UUID.randomUUID(),
                    new AcquireMaintenanceOperationLeaseRequest(
                        directOld.id(),
                        MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
                        UUID.randomUUID(),
                        pendingOld.version())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("furniture movement must finish");
    jdbc.update(
        "update equipment_allocation_hold set expires_at=clock_timestamp()-interval '1 second' where id=?",
        directReceipt.movementReservations().getFirst().reservationId());
    UUID expiredMovementOwnerId = UUID.randomUUID();
    var expiredMovementLease =
        assets.acquireMaintenanceLease(
            actorSubjectId,
            UUID.randomUUID(),
            new AcquireMaintenanceOperationLeaseRequest(
                directOld.id(),
                MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
                expiredMovementOwnerId,
                pendingOld.version()));
    assets.releaseMaintenanceLease(
        actorSubjectId,
        UUID.randomUUID(),
        expiredMovementLease.response().id(),
        new ReleaseMaintenanceOperationLeaseRequest(
            expiredMovementLease.response().version(),
            expiredMovementLease.response().fencingToken(),
            MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
            expiredMovementOwnerId));
    jdbc.update(
        "update equipment_allocation_hold set expires_at=? where id=?",
        movement.reservedUntil(),
        directReceipt.movementReservations().getFirst().reservationId());

    var requestedMovement = movement.lines().getFirst();
    var preparedReservation = directReceipt.movementReservations().getFirst();
    AcquireLogisticsEquipmentMovementReservationRequest preparedReplay =
        new AcquireLogisticsEquipmentMovementReservationRequest(
            movement.movementId(),
            requestedMovement.lineId(),
            LogisticsEquipmentMovementPurpose.ALLOCATABLE_REBALANCE,
            requestedMovement.equipmentId(),
            warehouseId,
            directOld.id(),
            BalanceLocationKind.CABIN_NON_RENTED,
            requestedMovement.expectedSourceBalanceVersion(),
            requestedMovement.quantity(),
            movement.reservedUntil(),
            directOrderId,
            directReplacement.id(),
            postReplacementUnits,
            directReceipt.releasedReservation().reservationId());
    assertThatThrownBy(
            () ->
                assets.acquireLogisticsEquipmentMovementReservation(
                    actorSubjectId,
                    UUID.randomUUID(),
                    new AcquireLogisticsEquipmentMovementReservationRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        LogisticsEquipmentMovementPurpose.ALLOCATABLE_REBALANCE,
                        requestedMovement.equipmentId(),
                        warehouseId,
                        directOld.id(),
                        BalanceLocationKind.CABIN_NON_RENTED,
                        requestedMovement.expectedSourceBalanceVersion(),
                        requestedMovement.quantity(),
                        movement.reservedUntil(),
                        directOrderId,
                        directReplacement.id(),
                        postReplacementUnits,
                        directReceipt.releasedReservation().reservationId())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("must replay an atomic replacement reservation");
    assertThatThrownBy(
            () ->
                assets.acquireLogisticsEquipmentMovementReservation(
                    actorSubjectId,
                    UUID.randomUUID(),
                    new AcquireLogisticsEquipmentMovementReservationRequest(
                        preparedReplay.movementId(),
                        preparedReplay.lineId(),
                        preparedReplay.purpose(),
                        preparedReplay.equipmentId(),
                        preparedReplay.sourceWarehouseId(),
                        preparedReplay.sourceRentalItemId(),
                        preparedReplay.sourceLocationKind(),
                        preparedReplay.expectedSourceBalanceVersion(),
                        preparedReplay.quantity(),
                        preparedReplay.reservedUntil(),
                        preparedReplay.orderId(),
                        preparedReplay.targetRentalItemId(),
                        preparedReplay.units(),
                        UUID.randomUUID())))
        .isInstanceOf(AssetConflictException.class);
    assertThatThrownBy(
            () ->
                assets.acquireLogisticsEquipmentMovementReservation(
                    actorSubjectId,
                    UUID.randomUUID(),
                    replacementMovementReplayRequest(
                        preparedReplay,
                        UUID.randomUUID(),
                        directOld.id(),
                        directReplacement.id(),
                        directReceipt.releasedReservation().reservationId())))
        .isInstanceOf(AssetConflictException.class);
    assertThatThrownBy(
            () ->
                assets.acquireLogisticsEquipmentMovementReservation(
                    actorSubjectId,
                    UUID.randomUUID(),
                    replacementMovementReplayRequest(
                        preparedReplay,
                        directOrderId,
                        directOld.id(),
                        missingReplacement.id(),
                        directReceipt.releasedReservation().reservationId())))
        .isInstanceOf(AssetConflictException.class);
    RentalItemResponse tamperedSource = freeRental(actorSubjectId, warehouseId);
    seedCabinBalance(equipmentId, warehouseId, tamperedSource.id(), 1);
    assertThatThrownBy(
            () ->
                assets.acquireLogisticsEquipmentMovementReservation(
                    actorSubjectId,
                    UUID.randomUUID(),
                    replacementMovementReplayRequest(
                        preparedReplay,
                        directOrderId,
                        tamperedSource.id(),
                        directReplacement.id(),
                        directReceipt.releasedReservation().reservationId())))
        .isInstanceOf(AssetConflictException.class);

    var acquired =
        assets.acquireLogisticsEquipmentMovementReservation(
            actorSubjectId, UUID.randomUUID(), preparedReplay);
    assertThat(acquired.replayed()).isTrue();
    assertThat(acquired.response().reservationId())
        .isEqualTo(preparedReservation.reservationId());
    var executed =
        assets.executeLogisticsEquipmentMovementReservations(
            actorSubjectId,
            UUID.randomUUID(),
            new ExecuteLogisticsEquipmentMovementReservationsRequest(
                movement.movementId(),
                List.of(
                    new ExecuteLogisticsEquipmentMovementReservationLine(
                        preparedReservation.reservationId(),
                        preparedReservation.version(),
                        requestedMovement.lineId(),
                        warehouseId,
                        directReplacement.id(),
                        BalanceLocationKind.CABIN_NON_RENTED))));
    assertThat(executed.response().lines()).singleElement();
    assertThat(
            jdbc.queryForObject(
                "select quantity from equipment_balance where equipment_id=? and warehouse_id=? and rental_item_id=? and location_kind='CABIN_NON_RENTED'",
                Long.class,
                equipmentId,
                warehouseId,
                directOld.id()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select quantity from equipment_balance where equipment_id=? and warehouse_id=? and rental_item_id=? and location_kind='CABIN_NON_RENTED'",
                Long.class,
                equipmentId,
                warehouseId,
                directReplacement.id()))
        .isEqualTo(2L);
    assertThat(
            jdbc.queryForObject(
                "select state from equipment_allocation_hold where id=?",
                String.class,
                preparedReservation.reservationId()))
        .isEqualTo("EXECUTED");

    RentalItemResponse readyOld = assets.rentalItem(directOld.id());
    UUID maintenanceOwnerId = UUID.randomUUID();
    var maintenanceLease =
        assets.acquireMaintenanceLease(
            actorSubjectId,
            UUID.randomUUID(),
            new AcquireMaintenanceOperationLeaseRequest(
                directOld.id(),
                MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
                maintenanceOwnerId,
                readyOld.version()));
    var queued =
        assets.maintenanceFencedStatus(
            actorSubjectId,
            UUID.randomUUID(),
            directOld.id(),
            new MaintenanceFencedStatusRequest(
                readyOld.version(),
                MaintenanceStatusAction.QUEUE_FOR_REPAIR,
                maintenanceLease.response().id(),
                maintenanceLease.response().fencingToken(),
                MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
                maintenanceOwnerId,
                null));
    assertThat(queued.response().status()).isEqualTo(RentalItemStatus.REPAIR);
  }

  @Test
  void standardPlanMovesSameOrderSurplusBeforeProtectedStockAndFencesConcurrentAcquire()
      throws Exception {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID equipmentId =
        assets
            .createEquipment(
                actorSubjectId,
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "Same-order surplus bed " + UUID.randomUUID(),
                    EquipmentCategory.FURNITURE,
                    null,
                    4))
            .response()
            .id();
    seedStockBalance(equipmentId, warehouseId, 1);

    UUID otherOrderId = UUID.randomUUID();
    RentalItemResponse otherUnit = freeRental(actorSubjectId, warehouseId);
    orders.reserve(
        UUID.randomUUID(),
        otherOrderId,
        reserveRequest(warehouseId, otherUnit.id(), actorSubjectId));
    orders.replaceEquipmentReservations(
        UUID.randomUUID(),
        otherOrderId,
        equipmentReservationRequest(
            actorSubjectId,
            warehouseId,
            otherUnit.id(),
            List.of(new OrderEquipmentRequirement(equipmentId, 1L))));

    UUID orderId = UUID.randomUUID();
    RentalItemResponse sourceUnit = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse targetUnit = freeRental(actorSubjectId, warehouseId);
    seedCabinBalance(equipmentId, warehouseId, sourceUnit.id(), 4);
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(warehouseId, sourceUnit.id(), actorSubjectId));
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(warehouseId, targetUnit.id(), actorSubjectId));
    List<OrderUnitEquipmentRequirements> units =
        List.of(
            new OrderUnitEquipmentRequirements(sourceUnit.id(), List.of()),
            new OrderUnitEquipmentRequirements(
                targetUnit.id(), List.of(new OrderEquipmentRequirement(equipmentId, 4L))));
    orders.replaceEquipmentReservations(
        UUID.randomUUID(),
        orderId,
        new ReplaceOrderEquipmentReservationsRequest(
            warehouseId, actorSubjectId, "WAREHOUSE_MANAGER", units));
    assertThat(assets.equipmentTotals(equipmentId, warehouseId).availableQuantity()).isZero();

    OrderFurnitureMovementPlan plan =
        orders.furnitureMovementPlan(
            orderId,
            new OrderFurnitureMovementPlanRequest(
                warehouseId,
                targetUnit.id(),
                null,
                List.of(new OrderEquipmentRequirement(equipmentId, 4L)),
                units));
    assertThat(plan.lines()).singleElement().satisfies(
        line -> {
          assertThat(line.sourceRentalItemId()).isEqualTo(sourceUnit.id());
          assertThat(line.targetRentalItemId()).isEqualTo(targetUnit.id());
          assertThat(line.quantity()).isEqualTo(4);
        });
    var line = plan.lines().getFirst();
    OffsetDateTime reservedUntil = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10);
    AcquireLogisticsEquipmentMovementReservationRequest firstRequest =
        orderMovementAcquireRequest(
            orderId,
            sourceUnit.id(),
            targetUnit.id(),
            warehouseId,
            line,
            units,
            reservedUntil);
    AcquireLogisticsEquipmentMovementReservationRequest secondRequest =
        orderMovementAcquireRequest(
            orderId,
            sourceUnit.id(),
            targetUnit.id(),
            warehouseId,
            line,
            units,
            reservedUntil);
    assertThatThrownBy(
            () ->
                assets.acquireLogisticsEquipmentMovementReservation(
                    actorSubjectId,
                    UUID.randomUUID(),
                    new AcquireLogisticsEquipmentMovementReservationRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        LogisticsEquipmentMovementPurpose.ALLOCATABLE_REBALANCE,
                        line.equipmentId(),
                        warehouseId,
                        sourceUnit.id(),
                        BalanceLocationKind.CABIN_RENTED,
                        line.expectedSourceBalanceVersion(),
                        line.quantity(),
                        reservedUntil,
                        orderId,
                        targetUnit.id(),
                        units)))
        .isInstanceOf(IllegalArgumentException.class);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    List<MovementAcquireAttempt> attempts;
    try {
      var first =
          executor.submit(
              () -> acquireMovementConcurrently(ready, start, firstRequest, actorSubjectId));
      var second =
          executor.submit(
              () -> acquireMovementConcurrently(ready, start, secondRequest, actorSubjectId));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      attempts = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
    assertThat(attempts).filteredOn(attempt -> attempt.result() != null).singleElement();
    assertThat(attempts)
        .filteredOn(attempt -> attempt.failure() != null)
        .singleElement()
        .extracting(MovementAcquireAttempt::failure)
        .isInstanceOf(AssetConflictException.class);
    MovementAcquireAttempt winner =
        attempts.stream().filter(attempt -> attempt.result() != null).findFirst().orElseThrow();
    var replay =
        assets.acquireLogisticsEquipmentMovementReservation(
            actorSubjectId, UUID.randomUUID(), winner.request());
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response().reservationId())
        .isEqualTo(winner.result().response().reservationId());
    assertThat(
            orders
                .furnitureMovementPlan(
                    orderId,
                    new OrderFurnitureMovementPlanRequest(
                        warehouseId,
                        targetUnit.id(),
                        null,
                        List.of(new OrderEquipmentRequirement(equipmentId, 4L)),
                        units))
                .lines())
        .isEmpty();

    assertThatThrownBy(
            () ->
                assets.executeLogisticsEquipmentMovementReservations(
                    actorSubjectId,
                    UUID.randomUUID(),
                    new ExecuteLogisticsEquipmentMovementReservationsRequest(
                        winner.request().movementId(),
                        List.of(
                            new ExecuteLogisticsEquipmentMovementReservationLine(
                                winner.result().response().reservationId(),
                                winner.result().response().version(),
                                winner.request().lineId(),
                                warehouseId,
                                targetUnit.id(),
                                BalanceLocationKind.CABIN_RENTED)))))
        .isInstanceOf(AssetConflictException.class);
    assets.executeLogisticsEquipmentMovementReservations(
        actorSubjectId,
        UUID.randomUUID(),
        new ExecuteLogisticsEquipmentMovementReservationsRequest(
            winner.request().movementId(),
            List.of(
                new ExecuteLogisticsEquipmentMovementReservationLine(
                    winner.result().response().reservationId(),
                    winner.result().response().version(),
                    winner.request().lineId(),
                    warehouseId,
                    targetUnit.id(),
                    BalanceLocationKind.CABIN_NON_RENTED))));
    assertThat(
            jdbc.queryForObject(
                "select quantity from equipment_balance where equipment_id=? and warehouse_id=? and rental_item_id is null and location_kind='STOCK'",
                Long.class,
                equipmentId,
                warehouseId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select quantity from equipment_balance where equipment_id=? and warehouse_id=? and rental_item_id=? and location_kind='CABIN_NON_RENTED'",
                Long.class,
                equipmentId,
                warehouseId,
                targetUnit.id()))
        .isEqualTo(4);
  }

  @Test
  void standardPlanNeverUsesSurplusFromAnAlreadyRentedOrderCabin() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID equipmentId = createFurniture(actorSubjectId, "Rented source fence");
    RentalItemResponse rentedSource = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse bookedTarget = freeRental(actorSubjectId, warehouseId);
    seedCabinBalance(equipmentId, warehouseId, rentedSource.id(), 4);
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(warehouseId, rentedSource.id(), actorSubjectId));
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(warehouseId, bookedTarget.id(), actorSubjectId));
    List<OrderUnitEquipmentRequirements> units =
        List.of(
            new OrderUnitEquipmentRequirements(rentedSource.id(), List.of()),
            new OrderUnitEquipmentRequirements(
                bookedTarget.id(), List.of(new OrderEquipmentRequirement(equipmentId, 4L))));
    orders.replaceEquipmentReservations(
        UUID.randomUUID(),
        orderId,
        new ReplaceOrderEquipmentReservationsRequest(
            warehouseId, actorSubjectId, "WAREHOUSE_MANAGER", units));
    jdbc.update(
        "update rental_item set status='RENTED',version=version+1,updated_at=clock_timestamp() where id=?",
        rentedSource.id());
    jdbc.update(
        "update equipment_balance set location_kind='CABIN_RENTED',version=version+1,updated_at=clock_timestamp() where equipment_id=? and rental_item_id=?",
        equipmentId,
        rentedSource.id());

    assertConflict(
        "INSUFFICIENT_EQUIPMENT_SOURCE",
        () ->
            orders.furnitureMovementPlan(
                orderId,
                new OrderFurnitureMovementPlanRequest(
                    warehouseId,
                    bookedTarget.id(),
                    null,
                    List.of(new OrderEquipmentRequirement(equipmentId, 4L)),
                    units)));
  }

  @Test
  void twoCabinReplacementPreservesPairOrderAndReplaysAllPreheldMovements() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID firstEquipmentId =
        assets
            .createEquipment(
                actorSubjectId,
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "First mapped furniture " + UUID.randomUUID(),
                    EquipmentCategory.FURNITURE,
                    null,
                    2))
            .response()
            .id();
    UUID secondEquipmentId =
        assets
            .createEquipment(
                actorSubjectId,
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "Second mapped furniture " + UUID.randomUUID(),
                    EquipmentCategory.FURNITURE,
                    null,
                    3))
            .response()
            .id();
    RentalItemResponse firstOld = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse secondOld = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse firstReplacement = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse secondReplacement = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse firstAlternative = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse secondAlternative = freeRental(actorSubjectId, warehouseId);
    seedCabinBalance(firstEquipmentId, warehouseId, firstOld.id(), 1);
    seedCabinBalance(secondEquipmentId, warehouseId, secondOld.id(), 2);
    UUID orderId = UUID.randomUUID();
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(warehouseId, firstOld.id(), actorSubjectId));
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(warehouseId, secondOld.id(), actorSubjectId));
    List<OrderEquipmentRequirement> firstRequirements =
        List.of(new OrderEquipmentRequirement(firstEquipmentId, 1L));
    List<OrderEquipmentRequirement> secondRequirements =
        List.of(new OrderEquipmentRequirement(secondEquipmentId, 2L));
    orders.replaceEquipmentReservations(
        UUID.randomUUID(),
        orderId,
        new ReplaceOrderEquipmentReservationsRequest(
            warehouseId,
            actorSubjectId,
            "WAREHOUSE_MANAGER",
            List.of(
                new OrderUnitEquipmentRequirements(firstOld.id(), firstRequirements),
                new OrderUnitEquipmentRequirements(secondOld.id(), secondRequirements))));
    List<OrderUnitEquipmentRequirements> postSwapUnits =
        List.of(
            new OrderUnitEquipmentRequirements(firstReplacement.id(), firstRequirements),
            new OrderUnitEquipmentRequirements(secondReplacement.id(), secondRequirements));
    OrderUnitReplacementMovementBundle firstMovement =
        movementBundle(
            orders.furnitureMovementPlan(
                orderId,
                new OrderFurnitureMovementPlanRequest(
                    warehouseId,
                    firstReplacement.id(),
                    firstOld.id(),
                    firstRequirements,
                    postSwapUnits)));
    OrderUnitReplacementMovementBundle secondMovement =
        movementBundle(
            orders.furnitureMovementPlan(
                orderId,
                new OrderFurnitureMovementPlanRequest(
                    warehouseId,
                    secondReplacement.id(),
                    secondOld.id(),
                    secondRequirements,
                    postSwapUnits)));
    UUID presentationId = UUID.randomUUID();
    presentationHolds.replace(
        UUID.randomUUID(),
        presentationId,
        new ReplacePresentationHoldsRequest(
            warehouseId,
            List.of(
                firstReplacement.id(),
                secondReplacement.id(),
                firstAlternative.id(),
                secondAlternative.id()),
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
            actorSubjectId,
            "WAREHOUSE_MANAGER",
            null));
    ReplaceOrderUnitsRequest request =
        new ReplaceOrderUnitsRequest(
            warehouseId,
            presentationId,
            actorSubjectId,
            "WAREHOUSE_MANAGER",
            postSwapUnits,
            List.of(
                new OrderUnitReplacement(
                    secondOld.id(), secondReplacement.id(), secondMovement),
                new OrderUnitReplacement(firstOld.id(), firstReplacement.id(), firstMovement)));
    UUID key = UUID.randomUUID();

    var replaced = orders.replaceUnits(key, orderId, request);
    var replay = orders.replaceUnits(key, orderId, request);

    assertThat(replaced.response().replacements())
        .extracting(receipt -> receipt.replacementReservation().rentalItemId())
        .containsExactly(secondReplacement.id(), firstReplacement.id());
    assertThat(replaced.response().replacements())
        .allSatisfy(
            receipt -> {
              assertThat(receipt.movementReservations()).singleElement();
              assertThat(receipt.contentReady()).isFalse();
            });
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response().replayed()).isTrue();
    assertThat(replay.response().replacements())
        .isEqualTo(replaced.response().replacements());
    assertThat(orders.units(orderId))
        .extracting(OrderUnitReservationView::rentalItemId)
        .containsExactlyInAnyOrder(firstReplacement.id(), secondReplacement.id());
    assertThat(assets.rentalItem(firstOld.id()).status()).isEqualTo(RentalItemStatus.BOOKED);
    assertThat(assets.rentalItem(secondOld.id()).status()).isEqualTo(RentalItemStatus.BOOKED);
    assertThat(
            jdbc.queryForList(
                "select state from presentation_unit_hold where presentation_id=? and rental_item_id in (?,?) order by rental_item_id",
                String.class,
                presentationId,
                firstAlternative.id(),
                secondAlternative.id()))
        .containsOnly("RELEASED");
  }

  @Test
  void crossSourceReplacementMovesReservationAndFurnitureCapacityToThePhysicalSource() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID serviceWarehouseId = UUID.randomUUID();
    UUID inventorySourceWarehouseId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID equipmentId = createFurniture(actorSubjectId, "Cross-source bed");
    RentalItemResponse old = freeRental(actorSubjectId, serviceWarehouseId);
    RentalItemResponse replacement = freeRental(actorSubjectId, inventorySourceWarehouseId);
    seedStockBalance(equipmentId, serviceWarehouseId, 1);
    seedStockBalance(equipmentId, inventorySourceWarehouseId, 1);
    orders.reserve(
        UUID.randomUUID(),
        orderId,
        reserveRequest(serviceWarehouseId, old.id(), actorSubjectId));
    List<OrderEquipmentRequirement> requirements =
        List.of(new OrderEquipmentRequirement(equipmentId, 1L));
    orders.replaceEquipmentReservations(
        UUID.randomUUID(),
        orderId,
        equipmentReservationRequest(
            actorSubjectId, serviceWarehouseId, old.id(), requirements));
    List<OrderUnitEquipmentRequirements> postSwap =
        List.of(new OrderUnitEquipmentRequirements(replacement.id(), requirements));
    OrderFurnitureMovementPlan plan =
        orders.furnitureMovementPlan(
            orderId,
            new OrderFurnitureMovementPlanRequest(
                inventorySourceWarehouseId,
                replacement.id(),
                old.id(),
                requirements,
                postSwap));
    assertThat(plan.lines())
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.sourceWarehouseId()).isEqualTo(inventorySourceWarehouseId);
              assertThat(line.sourceRentalItemId()).isNull();
              assertThat(line.targetWarehouseId()).isEqualTo(inventorySourceWarehouseId);
              assertThat(line.targetRentalItemId()).isEqualTo(replacement.id());
            });
    UUID key = UUID.randomUUID();
    ReplaceOrderUnitsRequest request =
        new ReplaceOrderUnitsRequest(
            serviceWarehouseId,
            inventorySourceWarehouseId,
            null,
            actorSubjectId,
            "WAREHOUSE_MANAGER",
            postSwap,
            List.of(
                new OrderUnitReplacement(
                    old.id(), replacement.id(), movementBundle(plan))));

    var receipt = orders.replaceUnits(key, orderId, request);
    var replay = orders.replaceUnits(key, orderId, request);

    assertThat(receipt.response().replacements())
        .singleElement()
        .satisfies(
            pair -> {
              assertThat(pair.releasedReservation().warehouseId())
                  .isEqualTo(serviceWarehouseId);
              assertThat(pair.replacementReservation().warehouseId())
                  .isEqualTo(inventorySourceWarehouseId);
              assertThat(pair.movementReservations()).singleElement();
            });
    assertThat(replay.replayed()).isTrue();
    assertThat(orders.units(orderId))
        .singleElement()
        .satisfies(
            unit -> {
              assertThat(unit.rentalItemId()).isEqualTo(replacement.id());
              assertThat(unit.warehouseId()).isEqualTo(inventorySourceWarehouseId);
            });
    assertThat(
            jdbc.queryForList(
                "select warehouse_id from order_equipment_reservation where order_id=? and state='ACTIVE'",
                UUID.class,
                orderId))
        .containsExactly(inventorySourceWarehouseId);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from order_unit_reservation where order_id=? and state='ACTIVE'",
                Integer.class,
                orderId))
        .isEqualTo(1);
  }

  @Test
  void crossSourceReplacementUsesFurnitureAlreadyAttachedToTheReplacementCabin() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID serviceWarehouseId = UUID.randomUUID();
    UUID inventorySourceWarehouseId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID equipmentId = createFurniture(actorSubjectId, "Cross-source furnished cabin bed");
    RentalItemResponse old = freeRental(actorSubjectId, serviceWarehouseId);
    RentalItemResponse replacement = freeRental(actorSubjectId, inventorySourceWarehouseId);
    seedCabinBalance(equipmentId, serviceWarehouseId, old.id(), 1);
    seedCabinBalance(equipmentId, inventorySourceWarehouseId, replacement.id(), 1);
    orders.reserve(
        UUID.randomUUID(),
        orderId,
        reserveRequest(serviceWarehouseId, old.id(), actorSubjectId));
    List<OrderEquipmentRequirement> requirements =
        List.of(new OrderEquipmentRequirement(equipmentId, 1L));
    orders.replaceEquipmentReservations(
        UUID.randomUUID(),
        orderId,
        equipmentReservationRequest(
            actorSubjectId, serviceWarehouseId, old.id(), requirements));
    List<OrderUnitEquipmentRequirements> postSwap =
        List.of(new OrderUnitEquipmentRequirements(replacement.id(), requirements));
    OrderFurnitureMovementPlan plan =
        orders.furnitureMovementPlan(
            orderId,
            new OrderFurnitureMovementPlanRequest(
                inventorySourceWarehouseId,
                replacement.id(),
                old.id(),
                requirements,
                postSwap));
    assertThat(plan.lines()).isEmpty();

    var receipt =
        orders.replaceUnits(
            UUID.randomUUID(),
            orderId,
            new ReplaceOrderUnitsRequest(
                serviceWarehouseId,
                inventorySourceWarehouseId,
                null,
                actorSubjectId,
                "WAREHOUSE_MANAGER",
                postSwap,
                List.of(new OrderUnitReplacement(old.id(), replacement.id(), null))));

    assertThat(receipt.response().replacements())
        .singleElement()
        .satisfies(
            pair -> {
              assertThat(pair.contentReady()).isTrue();
              assertThat(pair.movementReservations()).isEmpty();
              assertThat(pair.replacementReservation().warehouseId())
                  .isEqualTo(inventorySourceWarehouseId);
            });
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from order_equipment_reservation
                where order_id=? and warehouse_id=? and equipment_id=? and state='ACTIVE'
                """,
                Integer.class,
                orderId,
                inventorySourceWarehouseId,
                equipmentId))
        .isOne();
  }

  @Test
  void crossSourceReplacementRejectsAReplacementCabinOutsideTheDeclaredSource() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID serviceWarehouseId = UUID.randomUUID();
    UUID actualSourceWarehouseId = UUID.randomUUID();
    UUID declaredSourceWarehouseId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    RentalItemResponse old = freeRental(actorSubjectId, serviceWarehouseId);
    RentalItemResponse replacement = freeRental(actorSubjectId, actualSourceWarehouseId);
    orders.reserve(
        UUID.randomUUID(),
        orderId,
        reserveRequest(serviceWarehouseId, old.id(), actorSubjectId));

    assertConflict(
        "UNIT_WAREHOUSE_MISMATCH",
        () ->
            orders.replaceUnits(
                UUID.randomUUID(),
                orderId,
                new ReplaceOrderUnitsRequest(
                    serviceWarehouseId,
                    declaredSourceWarehouseId,
                    null,
                    actorSubjectId,
                    "WAREHOUSE_MANAGER",
                    List.of(
                        new OrderUnitEquipmentRequirements(replacement.id(), List.of())),
                    List.of(new OrderUnitReplacement(old.id(), replacement.id(), null)))));

    assertThat(orders.units(orderId))
        .singleElement()
        .satisfies(
            unit -> {
              assertThat(unit.rentalItemId()).isEqualTo(old.id());
              assertThat(unit.warehouseId()).isEqualTo(serviceWarehouseId);
            });
    assertThat(assets.rentalItem(replacement.id()).status()).isEqualTo(RentalItemStatus.FREE);
  }

  @Test
  void equipmentReservationsRemainPartitionedForMixedSourceOrderCabins() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID firstWarehouseId = UUID.randomUUID();
    UUID secondWarehouseId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID equipmentId = createFurniture(actorSubjectId, "Mixed-source table");
    RentalItemResponse first = freeRental(actorSubjectId, firstWarehouseId);
    RentalItemResponse second = freeRental(actorSubjectId, secondWarehouseId);
    seedStockBalance(equipmentId, firstWarehouseId, 1);
    seedStockBalance(equipmentId, secondWarehouseId, 1);
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(firstWarehouseId, first.id(), actorSubjectId));
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(secondWarehouseId, second.id(), actorSubjectId));
    List<OrderEquipmentRequirement> one =
        List.of(new OrderEquipmentRequirement(equipmentId, 1L));

    var result =
        orders.replaceEquipmentReservations(
            UUID.randomUUID(),
            orderId,
            new ReplaceOrderEquipmentReservationsRequest(
                firstWarehouseId,
                actorSubjectId,
                "WAREHOUSE_MANAGER",
                List.of(
                    new OrderUnitEquipmentRequirements(first.id(), one),
                    new OrderUnitEquipmentRequirements(second.id(), one))));

    assertThat(result.response())
        .extracting(OrderEquipmentReservationView::warehouseId)
        .containsExactlyInAnyOrder(firstWarehouseId, secondWarehouseId);
    assertThat(result.response())
        .extracting(OrderEquipmentReservationView::quantity)
        .containsOnly(1L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from order_equipment_reservation where order_id=? and equipment_id=? and state='ACTIVE'",
                Integer.class,
                orderId,
                equipmentId))
        .isEqualTo(2);
  }

  @Test
  void invalidSecondReplacementAndCrossPairSourceCollisionRollBackWholeBatch() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID firstEquipmentId = createFurniture(actorSubjectId, "Rollback first");
    UUID secondEquipmentId = createFurniture(actorSubjectId, "Rollback second");
    RentalItemResponse firstOld = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse secondOld = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse firstReplacement = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse secondReplacement = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse firstAlternative = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse secondAlternative = freeRental(actorSubjectId, warehouseId);
    seedCabinBalance(firstEquipmentId, warehouseId, firstOld.id(), 1);
    seedCabinBalance(secondEquipmentId, warehouseId, secondOld.id(), 1);
    UUID orderId = UUID.randomUUID();
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(warehouseId, firstOld.id(), actorSubjectId));
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(warehouseId, secondOld.id(), actorSubjectId));
    List<OrderEquipmentRequirement> firstRequirements =
        List.of(new OrderEquipmentRequirement(firstEquipmentId, 1L));
    List<OrderEquipmentRequirement> secondRequirements =
        List.of(new OrderEquipmentRequirement(secondEquipmentId, 1L));
    orders.replaceEquipmentReservations(
        UUID.randomUUID(),
        orderId,
        new ReplaceOrderEquipmentReservationsRequest(
            warehouseId,
            actorSubjectId,
            "WAREHOUSE_MANAGER",
            List.of(
                new OrderUnitEquipmentRequirements(firstOld.id(), firstRequirements),
                new OrderUnitEquipmentRequirements(secondOld.id(), secondRequirements))));
    List<OrderUnitEquipmentRequirements> postSwapUnits =
        List.of(
            new OrderUnitEquipmentRequirements(firstReplacement.id(), firstRequirements),
            new OrderUnitEquipmentRequirements(secondReplacement.id(), secondRequirements));
    OrderUnitReplacementMovementBundle firstMovement =
        movementBundle(
            orders.furnitureMovementPlan(
                orderId,
                new OrderFurnitureMovementPlanRequest(
                    warehouseId,
                    firstReplacement.id(),
                    firstOld.id(),
                    firstRequirements,
                    postSwapUnits)));
    OrderUnitReplacementMovementBundle secondMovement =
        movementBundle(
            orders.furnitureMovementPlan(
                orderId,
                new OrderFurnitureMovementPlanRequest(
                    warehouseId,
                    secondReplacement.id(),
                    secondOld.id(),
                    secondRequirements,
                    postSwapUnits)));
    UUID presentationId = UUID.randomUUID();
    presentationHolds.replace(
        UUID.randomUUID(),
        presentationId,
        new ReplacePresentationHoldsRequest(
            warehouseId,
            List.of(
                firstReplacement.id(),
                secondReplacement.id(),
                firstAlternative.id(),
                secondAlternative.id()),
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
            actorSubjectId,
            "WAREHOUSE_MANAGER",
            null));
    OrderUnitReplacementMovementLine firstLine = firstMovement.lines().getFirst();
    OrderUnitReplacementMovementLine secondLine = secondMovement.lines().getFirst();
    OrderUnitReplacementMovementBundle collidingSecondMovement =
        new OrderUnitReplacementMovementBundle(
            secondMovement.movementId(),
            secondMovement.reservedUntil(),
            List.of(
                new OrderUnitReplacementMovementLine(
                    secondLine.lineId(),
                    secondLine.equipmentId(),
                    firstLine.sourceBalanceId(),
                    secondLine.expectedSourceBalanceVersion(),
                    secondLine.targetRentalItemId(),
                    secondLine.quantity())));

    assertThatThrownBy(
            () ->
                orders.replaceUnits(
                    UUID.randomUUID(),
                    orderId,
                    new ReplaceOrderUnitsRequest(
                        warehouseId,
                        presentationId,
                        actorSubjectId,
                        "WAREHOUSE_MANAGER",
                        postSwapUnits,
                        List.of(
                            new OrderUnitReplacement(
                                firstOld.id(), firstReplacement.id(), firstMovement),
                            new OrderUnitReplacement(
                                secondOld.id(),
                                secondReplacement.id(),
                                collidingSecondMovement)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source and line identities");

    OrderUnitReplacementMovementBundle staleSecondMovement =
        new OrderUnitReplacementMovementBundle(
            secondMovement.movementId(),
            secondMovement.reservedUntil(),
            List.of(
                new OrderUnitReplacementMovementLine(
                    secondLine.lineId(),
                    secondLine.equipmentId(),
                    secondLine.sourceBalanceId(),
                    Math.addExact(secondLine.expectedSourceBalanceVersion(), 1),
                    secondLine.targetRentalItemId(),
                    secondLine.quantity())));
    UUID failedKey = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                orders.replaceUnits(
                    failedKey,
                    orderId,
                    new ReplaceOrderUnitsRequest(
                        warehouseId,
                        presentationId,
                        actorSubjectId,
                        "WAREHOUSE_MANAGER",
                        postSwapUnits,
                        List.of(
                            new OrderUnitReplacement(
                                firstOld.id(), firstReplacement.id(), firstMovement),
                            new OrderUnitReplacement(
                                secondOld.id(), secondReplacement.id(), staleSecondMovement)))))
        .isInstanceOf(AssetConflictException.class);
    assertThat(orders.units(orderId))
        .extracting(OrderUnitReservationView::rentalItemId)
        .containsExactlyInAnyOrder(firstOld.id(), secondOld.id());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from equipment_allocation_hold where idempotency_key=? and state='ACTIVE'",
                Integer.class,
                failedKey))
        .isZero();
    assertThat(
            jdbc.queryForList(
                "select state from presentation_unit_hold where presentation_id=? order by rental_item_id",
                String.class,
                presentationId))
        .containsOnly("ACTIVE");
    assertThat(assets.rentalItem(firstReplacement.id()).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(assets.rentalItem(secondReplacement.id()).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(assets.rentalItem(firstAlternative.id()).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(assets.rentalItem(secondAlternative.id()).status()).isEqualTo(RentalItemStatus.FREE);
  }

  @Test
  void furnitureUpdateAndUnitReplacementShareTheOrderCompositionFence() throws Exception {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    RentalItemResponse old = freeRental(actorSubjectId, warehouseId);
    RentalItemResponse replacement = freeRental(actorSubjectId, warehouseId);
    orders.reserve(
        UUID.randomUUID(), orderId, reserveRequest(warehouseId, old.id(), actorSubjectId));
    UUID equipmentId =
        assets
            .createEquipment(
                actorSubjectId,
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "Composition fence " + UUID.randomUUID(), EquipmentCategory.FURNITURE, null))
            .response()
            .id();
    seedStockBalance(equipmentId, warehouseId, 1);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);

    Throwable equipmentFailure;
    Throwable replacementFailure;
    try {
      var equipmentAttempt =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                try {
                  orders.replaceEquipmentReservations(
                      UUID.randomUUID(),
                      orderId,
                      equipmentReservationRequest(
                          actorSubjectId,
                          warehouseId,
                          old.id(),
                          List.of(new OrderEquipmentRequirement(equipmentId, 1L))));
                  return null;
                } catch (RuntimeException failure) {
                  return failure;
                }
              });
      var replacementAttempt =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                try {
                  orders.replaceUnits(
                      UUID.randomUUID(),
                      orderId,
                      replacementRequest(
                          warehouseId,
                          old.id(),
                          replacement.id(),
                          null,
                          actorSubjectId,
                          List.of(),
                          null));
                  return null;
                } catch (RuntimeException failure) {
                  return failure;
                }
              });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      equipmentFailure = equipmentAttempt.get(30, TimeUnit.SECONDS);
      replacementFailure = replacementAttempt.get(30, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertThat(java.util.stream.Stream.of(equipmentFailure, replacementFailure)
            .filter(java.util.Objects::isNull))
        .hasSize(1);
    UUID activeRentalItemId = orders.units(orderId).getFirst().rentalItemId();
    long activeEquipmentQuantity =
        jdbc.queryForObject(
            "select coalesce(sum(quantity),0) from order_equipment_reservation where order_id=? and state='ACTIVE'",
            Long.class,
            orderId);
    if (activeRentalItemId.equals(old.id())) {
      assertThat(activeEquipmentQuantity).isEqualTo(1);
    } else {
      assertThat(activeRentalItemId).isEqualTo(replacement.id());
      assertThat(activeEquipmentQuantity).isZero();
    }
  }

  @Test
  void presentationConversionAtomicallyConsumesHeldCabinFurnitureAndRollsBackOnShortage() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID equipmentId =
        assets
            .createEquipment(
                actorSubjectId,
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "Held conversion beds " + UUID.randomUUID(),
                    EquipmentCategory.FURNITURE,
                    null,
                    4))
            .response()
            .id();
    RentalItemResponse selected = freeRental(actorSubjectId, warehouseId);
    seedCabinBalance(equipmentId, warehouseId, selected.id(), 4);
    UUID presentationId = UUID.randomUUID();
    presentationHolds.replace(
        UUID.randomUUID(),
        presentationId,
        new ReplacePresentationHoldsRequest(
            warehouseId,
            List.of(selected.id()),
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
            actorSubjectId,
            "RENTAL_MANAGER",
            null));
    assertThat(
            presentationHolds
                .snapshots(new CabinAvailabilityRequest(warehouseId, List.of(selected.id())))
                .items())
        .singleElement()
        .satisfies(
            cabin ->
                assertThat(cabin.contents())
                    .singleElement()
                    .satisfies(
                        content -> {
                          assertThat(content.equipmentId()).isEqualTo(equipmentId);
                          assertThat(content.quantity()).isEqualTo(4);
                        }));
    assertThat(assets.equipmentTotals(equipmentId, warehouseId).availableQuantity()).isZero();
    UUID orderId = UUID.randomUUID();
    ConvertPresentationHoldsRequest request =
        new ConvertPresentationHoldsRequest(
            orderId,
            warehouseId,
            List.of(selected.id()),
            UUID.randomUUID(),
            "ООО Атомарный выбор",
            actorSubjectId,
            "RENTAL_MANAGER",
            List.of(
                new OrderUnitEquipmentRequirements(
                    selected.id(), List.of(new OrderEquipmentRequirement(equipmentId, 4L)))));
    UUID key = UUID.randomUUID();

    var converted = presentationHolds.convert(key, presentationId, request);
    var replay = presentationHolds.convert(key, presentationId, request);

    assertThat(converted.response().equipmentReservations())
        .singleElement()
        .satisfies(
            reservation -> {
              assertThat(reservation.equipmentId()).isEqualTo(equipmentId);
              assertThat(reservation.quantity()).isEqualTo(4);
              assertThat(reservation.availableQuantity()).isZero();
            });
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response()).isEqualTo(converted.response());
    assertThat(assets.equipmentTotals(equipmentId, warehouseId).availableQuantity()).isZero();

    RentalItemResponse rejected = freeRental(actorSubjectId, warehouseId);
    UUID rejectedPresentationId = UUID.randomUUID();
    UUID rejectedOrderId = UUID.randomUUID();
    presentationHolds.replace(
        UUID.randomUUID(),
        rejectedPresentationId,
        new ReplacePresentationHoldsRequest(
            warehouseId,
            List.of(rejected.id()),
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
            actorSubjectId,
            "RENTAL_MANAGER",
            null));

    assertConflict(
        "INSUFFICIENT_EQUIPMENT",
        () ->
            presentationHolds.convert(
                UUID.randomUUID(),
                rejectedPresentationId,
                new ConvertPresentationHoldsRequest(
                    rejectedOrderId,
                    warehouseId,
                    List.of(rejected.id()),
                    UUID.randomUUID(),
                    "ООО Откат",
                    actorSubjectId,
                    "RENTAL_MANAGER",
                    List.of(
                        new OrderUnitEquipmentRequirements(
                            rejected.id(),
                            List.of(new OrderEquipmentRequirement(equipmentId, 1L)))))));
    assertThat(assets.rentalItem(rejected.id()).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(orders.units(rejectedOrderId)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select state from presentation_unit_hold where presentation_id=? and rental_item_id=?",
                String.class,
                rejectedPresentationId,
                rejected.id()))
        .isEqualTo("ACTIVE");
  }

  @Test
  void fulfilledFurnitureIsNotSubtractedTwiceAndOwnEditUsesOneFreeStockItem() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID equipmentId =
        assets
            .createEquipment(
                actorSubjectId,
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "Outstanding reservation beds " + UUID.randomUUID(),
                    EquipmentCategory.FURNITURE,
                    null,
                    10))
            .response()
            .id();
    RentalItemResponse bookedCabin = freeRental(actorSubjectId, warehouseId);
    seedCabinBalance(equipmentId, warehouseId, bookedCabin.id(), 4);
    seedStockBalance(equipmentId, warehouseId, 10);
    UUID orderId = UUID.randomUUID();
    orders.reserve(
        UUID.randomUUID(),
        orderId,
        reserveRequest(warehouseId, bookedCabin.id(), actorSubjectId));
    orders.replaceEquipmentReservations(
        UUID.randomUUID(),
        orderId,
        equipmentReservationRequest(
            actorSubjectId,
            warehouseId,
            bookedCabin.id(),
            List.of(new OrderEquipmentRequirement(equipmentId, 4L))));
    assertThat(assets.equipmentTotals(equipmentId, warehouseId).availableQuantity()).isEqualTo(10);

    UUID editEquipmentId =
        assets
            .createEquipment(
                actorSubjectId,
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "Own edit beds " + UUID.randomUUID(),
                    EquipmentCategory.FURNITURE,
                    null,
                    10))
            .response()
            .id();
    RentalItemResponse ownCabin = freeRental(actorSubjectId, warehouseId);
    seedCabinBalance(editEquipmentId, warehouseId, ownCabin.id(), 4);
    seedStockBalance(editEquipmentId, warehouseId, 1);
    UUID ownOrderId = UUID.randomUUID();
    orders.reserve(
        UUID.randomUUID(),
        ownOrderId,
        reserveRequest(warehouseId, ownCabin.id(), actorSubjectId));
    orders.replaceEquipmentReservations(
        UUID.randomUUID(),
        ownOrderId,
        equipmentReservationRequest(
            actorSubjectId,
            warehouseId,
            ownCabin.id(),
            List.of(new OrderEquipmentRequirement(editEquipmentId, 4L))));
    var edited =
        orders.replaceEquipmentReservations(
            UUID.randomUUID(),
            ownOrderId,
            equipmentReservationRequest(
                actorSubjectId,
                warehouseId,
                ownCabin.id(),
                List.of(new OrderEquipmentRequirement(editEquipmentId, 5L))));
    assertThat(edited.response()).singleElement().satisfies(
        reservation -> {
          assertThat(reservation.quantity()).isEqualTo(5);
          assertThat(reservation.availableQuantity()).isZero();
        });

    UUID otherOrderId = UUID.randomUUID();
    RentalItemResponse otherCabin = freeRental(actorSubjectId, warehouseId);
    orders.reserve(
        UUID.randomUUID(),
        otherOrderId,
        reserveRequest(warehouseId, otherCabin.id(), actorSubjectId));
    assertConflict(
        "INSUFFICIENT_EQUIPMENT",
        () ->
            orders.replaceEquipmentReservations(
                UUID.randomUUID(),
                otherOrderId,
                equipmentReservationRequest(
                    actorSubjectId,
                    warehouseId,
                    otherCabin.id(),
                    List.of(new OrderEquipmentRequirement(editEquipmentId, 1L)))));
  }

  @Test
  void semanticReserveRepairsLegacyFreeStatusAndMissingClientProjection() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    RentalItemResponse rental = freeRental(actorSubjectId, warehouseId);
    ReserveOrderUnitRequest request =
        reserveRequest(warehouseId, rental.id(), actorSubjectId);
    var first = orders.reserve(UUID.randomUUID(), orderId, request);

    jdbc.update(
        "update rental_item set status='FREE' where id=?",
        rental.id());
    jdbc.update(
        """
        update order_unit_reservation
        set client_id=null,tenant_snapshot=null
        where id=?
        """,
        first.response().reservationId());

    var repaired = orders.reserve(UUID.randomUUID(), orderId, request);

    assertThat(repaired.replayed()).isTrue();
    assertThat(assets.rentalItem(rental.id()))
        .satisfies(
            booked -> {
              assertThat(booked.status()).isEqualTo(RentalItemStatus.BOOKED);
              assertThat(booked.activeOrderReservation()).isNotNull();
              assertThat(booked.activeOrderReservation().clientId()).isEqualTo(request.clientId());
              assertThat(booked.activeOrderReservation().tenantSnapshot())
                  .isEqualTo(request.tenantSnapshot());
            });
  }

  @Test
  void warehouseCreatedCabinIsFreeWithNewCategoryAndReleaseRestoresFree() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse free = freeRental(actorSubjectId, warehouseId);
    assertThat(
            orders
                .candidates(UUID.randomUUID(), warehouseId, 0, 20, free.number())
                .content())
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.unit().id()).isEqualTo(free.id());
          assertThat(candidate.unit().status()).isEqualTo(RentalItemStatus.FREE);
        });
    assertConflict(
        "UNIT_WAREHOUSE_MISMATCH",
        () ->
            orders.reserve(
                UUID.randomUUID(),
                UUID.randomUUID(),
                reserveRequest(UUID.randomUUID(), free.id(), actorSubjectId)));

    RentalItemResponse newCategoryRental =
        assets
            .createRentalItem(
                actorSubjectId,
                UUID.randomUUID(),
                rentalRequest(warehouseId))
            .response();
    assertThat(newCategoryRental.status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(newCategoryRental.category()).isEqualTo("Новая");
    assertThat(
            orders
                .candidates(
                    UUID.randomUUID(), warehouseId, 0, 20, newCategoryRental.number())
                .content())
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.unit().id()).isEqualTo(newCategoryRental.id());
          assertThat(candidate.unit().status()).isEqualTo(RentalItemStatus.FREE);
          assertThat(candidate.unit().category()).isEqualTo("Новая");
        });
    UUID orderId = UUID.randomUUID();
    orders.reserve(
        UUID.randomUUID(),
        orderId,
        reserveRequest(warehouseId, newCategoryRental.id(), actorSubjectId));
    assertThat(assets.rentalItem(newCategoryRental.id()).status())
        .isEqualTo(RentalItemStatus.BOOKED);
    orders.release(
        UUID.randomUUID(),
        orderId,
        newCategoryRental.id(),
        new OrderActorRequest(actorSubjectId, "RENTAL_MANAGER"));
    assertThat(assets.rentalItem(newCategoryRental.id()).status())
        .isEqualTo(RentalItemStatus.FREE);

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
    RentalItemResponse ownNeeds =
        assets.updateStatus(
            rentalCandidate.id(),
            new UpdateStatusRequest(rentalCandidate.version(), RentalItemStatus.OWN_NEEDS));
    assertThat(
            orders
                .candidates(UUID.randomUUID(), warehouseId, 0, 20, ownNeeds.number())
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
    RentalItemResponse booked = assets.rentalItem(rental.id());

    assertReservedLeaseConflict(
        () ->
            assets.acquireLease(
                actorSubjectId,
                UUID.randomUUID(),
                new AcquireOperationLeaseRequest(
                    rental.id(), "ORDER_TEST", UUID.randomUUID().toString(), booked.version())));
    assertThatThrownBy(
            () ->
                assets.acquireMaintenanceLease(
                    actorSubjectId,
                    UUID.randomUUID(),
                    new AcquireMaintenanceOperationLeaseRequest(
                        rental.id(),
                        MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE,
                        UUID.randomUUID(),
                        booked.version())))
        .isInstanceOf(OrderUnitReservationConflictException.class)
        .satisfies(
            failure ->
                assertThat(((OrderUnitReservationConflictException) failure).code())
                    .isEqualTo("BOOKED_UNIT_REPLACEMENT_REQUIRED"));
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
                    booked.version())));
    assertThat(activeLeaseCount(rental.id())).isZero();

    orders.release(
        UUID.randomUUID(),
        orderId,
        rental.id(),
        new OrderActorRequest(actorSubjectId, "RENTAL_MANAGER"));
    RentalItemResponse released = assets.rentalItem(rental.id());
    var acquired =
        assets.acquireLease(
            actorSubjectId,
            UUID.randomUUID(),
            new AcquireOperationLeaseRequest(
                rental.id(), "ORDER_TEST", UUID.randomUUID().toString(), released.version()));

    assertThat(acquired.replayed()).isFalse();
    assertThat(acquired.response().state()).isEqualTo("ACTIVE");
    assertThat(activeLeaseCount(rental.id())).isEqualTo(1);
  }

  @Test
  void administrativeCorrectionAndIncompatibleStatusChangesRequireReservationRelease() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID nextWarehouseId = UUID.randomUUID();
    RentalItemResponse rental = freeRental(actorSubjectId, warehouseId);
    UUID orderId = UUID.randomUUID();
    orders.reserve(
        UUID.randomUUID(),
        orderId,
        reserveRequest(warehouseId, rental.id(), actorSubjectId));
    RentalItemResponse booked = assets.rentalItem(rental.id());

    assertThatThrownBy(
            () ->
                administrativeCorrections.create(
                    actorSubjectId,
                    UUID.randomUUID(),
                    new CreateCabinAdministrativeCorrectionRequest(
                        AdministrativeCorrectionAssetKind.CABIN,
                        rental.id(),
                        booked.version(),
                        warehouseId,
                        nextWarehouseId,
                        "booked cabin recorded at the wrong warehouse",
                        "https://evidence.example/booked-cabin")))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("manual-status");
    assertThatThrownBy(
            () ->
                assets.updateStatus(
                    rental.id(),
                    new UpdateStatusRequest(booked.version(), RentalItemStatus.RENTED)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("incompatible status");
    assertThatThrownBy(
            () ->
                assets.updateStatus(
                    rental.id(),
                    new UpdateStatusRequest(booked.version(), RentalItemStatus.FREE)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("incompatible status");
    assertThat(assets.rentalItem(rental.id()).warehouseId()).isEqualTo(warehouseId);
    assertThat(assets.rentalItem(rental.id()).status()).isEqualTo(RentalItemStatus.BOOKED);

    orders.release(
        UUID.randomUUID(),
        orderId,
        rental.id(),
        new OrderActorRequest(actorSubjectId, "RENTAL_MANAGER"));
    RentalItemResponse released = assets.rentalItem(rental.id());
    administrativeCorrections.create(
        actorSubjectId,
        UUID.randomUUID(),
        new CreateCabinAdministrativeCorrectionRequest(
            AdministrativeCorrectionAssetKind.CABIN,
            rental.id(),
            released.version(),
            warehouseId,
            nextWarehouseId,
            "released cabin recorded at the wrong warehouse",
            "https://evidence.example/released-cabin"));
    RentalItemResponse moved = assets.rentalItem(rental.id());
    RentalItemResponse ownNeeds =
        assets.updateStatus(
            rental.id(),
            new UpdateStatusRequest(moved.version(), RentalItemStatus.OWN_NEEDS));
    assertThat(ownNeeds.warehouseId()).isEqualTo(nextWarehouseId);
    assertThat(ownNeeds.status()).isEqualTo(RentalItemStatus.OWN_NEEDS);
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
    return assets
        .createRentalItem(
            actorSubjectId,
            UUID.randomUUID(),
            rentalRequest(warehouseId))
        .response();
  }

  private CreateRentalItemRequest rentalRequest(UUID warehouseId) {
    return new CreateRentalItemRequest(
        warehouseId,
        "ORDER-CABIN-" + UUID.randomUUID(),
        TYPE_BK_1,
        DIMENSION_24_X_6,
        FINISHING_DVP,
        null,
        List.of(),
        false,
        Map.of(),
        List.of());
  }

  private static ReserveOrderUnitRequest reserveRequest(
      UUID warehouseId, UUID rentalItemId, UUID actorSubjectId) {
    return new ReserveOrderUnitRequest(
        warehouseId,
        rentalItemId,
        UUID.randomUUID(),
        "ООО Тестовый арендатор",
        null,
        actorSubjectId,
        "RENTAL_MANAGER");
  }

  private static ReplaceOrderEquipmentReservationsRequest equipmentReservationRequest(
      UUID actorSubjectId,
      UUID warehouseId,
      UUID rentalItemId,
      List<OrderEquipmentRequirement> requirements) {
    return new ReplaceOrderEquipmentReservationsRequest(
        warehouseId,
        actorSubjectId,
        "RENTAL_MANAGER",
        List.of(new OrderUnitEquipmentRequirements(rentalItemId, requirements)));
  }

  private static ReplaceOrderUnitsRequest replacementRequest(
      UUID warehouseId,
      UUID rentalItemId,
      UUID replacementRentalItemId,
      UUID presentationId,
      UUID actorSubjectId,
      List<OrderEquipmentRequirement> requirements,
      OrderUnitReplacementMovementBundle movement) {
    return new ReplaceOrderUnitsRequest(
        warehouseId,
        presentationId,
        actorSubjectId,
        "WAREHOUSE_MANAGER",
        List.of(new OrderUnitEquipmentRequirements(replacementRentalItemId, requirements)),
        List.of(new OrderUnitReplacement(rentalItemId, replacementRentalItemId, movement)));
  }

  private UUID createFurniture(UUID actorSubjectId, String namePrefix) {
    return assets
        .createEquipment(
            actorSubjectId,
            UUID.randomUUID(),
            new CreateEquipmentRequest(
                namePrefix + " " + UUID.randomUUID(), EquipmentCategory.FURNITURE, null, 10))
        .response()
        .id();
  }

  private static OrderUnitReplacementMovementBundle movementBundle(
      OrderFurnitureMovementPlan plan) {
    assertThat(plan.lines()).singleElement();
    var line = plan.lines().getFirst();
    return new OrderUnitReplacementMovementBundle(
        UUID.randomUUID(),
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
        List.of(
            new OrderUnitReplacementMovementLine(
                UUID.randomUUID(),
                line.equipmentId(),
                line.sourceBalanceId(),
                line.expectedSourceBalanceVersion(),
                line.targetRentalItemId(),
                line.quantity())));
  }

  private static AcquireLogisticsEquipmentMovementReservationRequest orderMovementAcquireRequest(
      UUID orderId,
      UUID sourceRentalItemId,
      UUID targetRentalItemId,
      UUID warehouseId,
      OrderFurnitureMovementPlanLine line,
      List<OrderUnitEquipmentRequirements> units,
      OffsetDateTime reservedUntil) {
    return new AcquireLogisticsEquipmentMovementReservationRequest(
        UUID.randomUUID(),
        UUID.randomUUID(),
        LogisticsEquipmentMovementPurpose.ALLOCATABLE_REBALANCE,
        line.equipmentId(),
        warehouseId,
        sourceRentalItemId,
        line.sourceLocationKind(),
        line.expectedSourceBalanceVersion(),
        line.quantity(),
        reservedUntil,
        orderId,
        targetRentalItemId,
        units);
  }

  private static AcquireLogisticsEquipmentMovementReservationRequest
      replacementMovementReplayRequest(
          AcquireLogisticsEquipmentMovementReservationRequest source,
          UUID orderId,
          UUID sourceRentalItemId,
          UUID targetRentalItemId,
          UUID releasedSourceReservationId) {
    return new AcquireLogisticsEquipmentMovementReservationRequest(
        source.movementId(),
        source.lineId(),
        source.purpose(),
        source.equipmentId(),
        source.sourceWarehouseId(),
        sourceRentalItemId,
        source.sourceLocationKind(),
        source.expectedSourceBalanceVersion(),
        source.quantity(),
        source.reservedUntil(),
        orderId,
        targetRentalItemId,
        source.units(),
        releasedSourceReservationId);
  }

  private MovementAcquireAttempt acquireMovementConcurrently(
      CountDownLatch ready,
      CountDownLatch start,
      AcquireLogisticsEquipmentMovementReservationRequest request,
      UUID actorSubjectId)
      throws InterruptedException {
    ready.countDown();
    start.await();
    try {
      return new MovementAcquireAttempt(
          request,
          assets.acquireLogisticsEquipmentMovementReservation(
              actorSubjectId, UUID.randomUUID(), request),
          null);
    } catch (RuntimeException failure) {
      return new MovementAcquireAttempt(request, null, failure);
    }
  }

  private Throwable replaceEquipmentConcurrently(
      CountDownLatch ready,
      CountDownLatch start,
      UUID orderId,
      ReplaceOrderEquipmentReservationsRequest request)
      throws InterruptedException {
    ready.countDown();
    start.await();
    try {
      orders.replaceEquipmentReservations(UUID.randomUUID(), orderId, request);
      return null;
    } catch (RuntimeException failure) {
      return failure;
    }
  }

  private ReplacementAttempt replaceUnitsConcurrently(
      CountDownLatch ready,
      CountDownLatch start,
      UUID orderId,
      ReplaceOrderUnitsRequest request)
      throws InterruptedException {
    ready.countDown();
    start.await();
    try {
      return new ReplacementAttempt(
          orders.replaceUnits(UUID.randomUUID(), orderId, request), null);
    } catch (RuntimeException failure) {
      return new ReplacementAttempt(null, failure);
    }
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
    seedBalance(equipmentId, warehouseId, null, BalanceLocationKind.STOCK, quantity);
  }

  private void seedCabinBalance(
      UUID equipmentId, UUID warehouseId, UUID rentalItemId, long quantity) {
    seedBalance(
        equipmentId,
        warehouseId,
        rentalItemId,
        BalanceLocationKind.CABIN_NON_RENTED,
        quantity);
  }

  private void seedBalance(
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind,
      long quantity) {
    UUID balanceId = UUID.randomUUID();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
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
                  rentalItemId,
                  locationKind.name(),
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
                      locationKind.name(),
                      "quantity",
                      quantity),
                  Map.of(
                      "balanceId",
                      balanceId.toString(),
                      "version",
                      0,
                      "locationKind",
                      locationKind.name(),
                      "quantity",
                      quantity));
            });
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

  /** Result of one competing same-order equipment movement reservation attempt. */
  private record MovementAcquireAttempt(
      AcquireLogisticsEquipmentMovementReservationRequest request,
      AssetService.CreateResult<LogisticsEquipmentMovementReservationResponse> result,
      Throwable failure) {}

  /** Result of one competing presentation replacement confirmation. */
  private record ReplacementAttempt(
      AssetService.CreateResult<OrderUnitsReplacementReceipt> result,
      Throwable failure) {}
}
