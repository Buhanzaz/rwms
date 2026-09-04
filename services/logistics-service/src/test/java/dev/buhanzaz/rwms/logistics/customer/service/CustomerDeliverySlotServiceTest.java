package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.DeliverySlotSearchRequest;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.HoldCustomerDeliverySlotRequest;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.SearchCustomerBookingRescheduleRequest;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityIsochroneTariff;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityJob;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityPriceZone;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityRestrictionKind;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityRestrictionZone;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityTaskType;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityIsochroneTariffRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityJobRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityPriceZoneRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityRestrictionZoneRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityShiftRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacitySnapshotRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityShift;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacitySnapshot;
import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotKind;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.CapacityDecision;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.DeliveryJob;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix.GeoPoint;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerVehicleRouteProfile;
import dev.buhanzaz.rwms.logistics.customer.routing.ValhallaCustomerTravelTimeClient;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderService;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderPermissions;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderUnitResponse;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/** Covers delivery-priority workload selection before CustomerApp slot routing. */
class CustomerDeliverySlotServiceTest {
  private static final UUID SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-000000000501");
  private static final UUID INQUIRY =
      UUID.fromString("00000000-0000-0000-0000-000000000502");
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000503");
  private static final UUID PRICE_ZONE =
      UUID.fromString("00000000-0000-0000-0000-000000000504");
  private static final UUID RESTRICTION_ZONE =
      UUID.fromString("00000000-0000-0000-0000-000000000505");
  private static final UUID BOOKING =
      UUID.fromString("00000000-0000-0000-0000-000000000506");
  private static final UUID ORDER =
      UUID.fromString("00000000-0000-0000-0000-000000000507");
  private static final UUID CONFIRMED_SLOT =
      UUID.fromString("00000000-0000-0000-0000-000000000508");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-27T06:00:00Z"), ZoneOffset.UTC);

  @Test
  void reservesDriversForDeliveriesButLeavesReturnsForBackhaul() {
    CustomerRentalService rentals = mock(CustomerRentalService.class);
    CustomerWarehouseService warehouses = mock(CustomerWarehouseService.class);
    CustomerRentalSessionStore sessions = mock(CustomerRentalSessionStore.class);
    CustomerDeliverySlotStore slotStore = mock(CustomerDeliverySlotStore.class);
    CustomerDeliverySlotHoldStore holdStore = mock(CustomerDeliverySlotHoldStore.class);
    WarehouseCapacityJobRepository generated = mock(WarehouseCapacityJobRepository.class);
    WarehouseCapacityShiftRepository shifts = mock(WarehouseCapacityShiftRepository.class);
    WarehouseCapacityIsochroneTariffRepository isochroneTariffs =
        mock(WarehouseCapacityIsochroneTariffRepository.class);
    WarehouseCapacityPriceZoneRepository priceZones =
        mock(WarehouseCapacityPriceZoneRepository.class);
    WarehouseCapacityRestrictionZoneRepository restrictionZones =
        mock(WarehouseCapacityRestrictionZoneRepository.class);
    WarehouseCapacitySnapshotRepository snapshots = mock(WarehouseCapacitySnapshotRepository.class);
    CustomerDeliveryPriceClassifier prices =
        new CustomerDeliveryPriceClassifier(new ObjectMapper());
    ValhallaCustomerTravelTimeClient travelTimes =
        mock(ValhallaCustomerTravelTimeClient.class);
    CustomerRouteCapacityPlanner capacity = mock(CustomerRouteCapacityPlanner.class);
    RepresentativeDeliverySlotPolicy representativePolicy =
        new RepresentativeDeliverySlotPolicy();
    DriverLogisticsTaskRepository driverTasks = mock(DriverLogisticsTaskRepository.class);
    RentalOrderService rentalOrders = mock(RentalOrderService.class);
    CustomerAuthorizer authorizer = mock(CustomerAuthorizer.class);
    CustomerDeliverySlotService service =
        new CustomerDeliverySlotService(
            rentals,
            warehouses,
            sessions,
            slotStore,
            holdStore,
            generated,
            shifts,
            isochroneTariffs,
            priceZones,
            restrictionZones,
            snapshots,
            prices,
            travelTimes,
            capacity,
            representativePolicy,
            driverTasks,
            rentalOrders,
            authorizer,
            CLOCK);
    CustomerRentalSession session = CustomerRentalSession.create(INQUIRY, SUBJECT, WAREHOUSE);
    CustomerDeliveryProperties.Validated configuration = configuration();
    CustomerTravelTimeMatrix matrix =
        new CustomerTravelTimeMatrix(
            List.of(new GeoPoint(55.75, 37.61), new GeoPoint(55.80, 37.70)),
            List.of(List.of(0L, 600L), List.of(600L, 0L)));
    when(sessions.required(SUBJECT, INQUIRY)).thenReturn(session);
    when(warehouses.validated(WAREHOUSE)).thenReturn(configuration);
    when(warehouses.required(WAREHOUSE))
        .thenReturn(new WarehouseIdentity(WAREHOUSE, 0, true, "Europe/Moscow"));
    when(rentals.selectedCabinIds(any(), eq(INQUIRY))).thenReturn(List.of(UUID.randomUUID()));
    when(slotStore.workload(eq(WAREHOUSE), any(), any()))
        .thenReturn(List.of());
    when(generated.findCapacityWorkload(eq(WAREHOUSE), any())).thenReturn(List.of());
    when(shifts.findCapacityShifts(eq(WAREHOUSE), any())).thenReturn(List.of());
    WarehouseCapacitySnapshot snapshot = snapshot(4);
    when(isochroneTariffs.findTariffs(WAREHOUSE)).thenReturn(snapshot.getIsochroneTariffs());
    when(priceZones.findTariffZones(WAREHOUSE)).thenReturn(List.of());
    when(restrictionZones.findRestrictionZones(WAREHOUSE)).thenReturn(List.of());
    when(snapshots.findByWarehouseId(WAREHOUSE)).thenReturn(Optional.of(snapshot));
    when(travelTimes.matrix(
            anyList(),
            any(),
            any(),
            same(configuration),
            any(CustomerVehicleRouteProfile.class)))
        .thenReturn(matrix);
    when(capacity.evaluate(same(matrix), anyList(), same(configuration), anyList(), anyInt()))
        .thenReturn(new CapacityDecision(true, 0));
    when(
            slotStore.replaceOffers(eq(SUBJECT), eq(INQUIRY), anyList()))
        .thenAnswer(invocation -> invocation.getArgument(2));

    var offers = service.search(
        new CustomerIdentity(SUBJECT, "customer"),
        new DeliverySlotSearchRequest(
            INQUIRY,
            "Москва",
            BigDecimal.valueOf(55.80),
            BigDecimal.valueOf(37.70),
            2,
            false,
            false));

    verify(driverTasks, times(1))
        .countWholeDayDeliveryReservations(eq(WAREHOUSE), any());
    verify(travelTimes, times(4))
        .matrix(
            anyList(),
            any(),
            any(),
            same(configuration),
            any(CustomerVehicleRouteProfile.class));
    assertThat(offers).isNotEmpty();
    assertThat(offers)
        .filteredOn(offer -> offer.kind() == CustomerDeliverySlotKind.FIXED_WINDOW)
        .hasSize(3);
    assertThat(offers)
        .filteredOn(offer -> offer.kind() == CustomerDeliverySlotKind.DURING_DAY)
        .singleElement()
        .satisfies(
            offer -> {
              assertThat(offer.start()).isEqualTo(LocalTime.of(9, 0));
              assertThat(offer.end()).isEqualTo(LocalTime.of(18, 0));
            });
    assertThat(offers)
        .allSatisfy(
            offer -> {
              assertThat(offer.privateSiteAccessConfirmed()).isFalse();
              assertThat(offer.failedTripChargeAcknowledged()).isFalse();
              assertThat(offer.siteCabinCapacity()).isEqualTo(1);
              assertThat(offer.deliveryPriceRubles()).isEqualTo(10_000L);
              assertThat(offer.priceZoneId()).isNull();
              assertThat(offer.priceIsochroneMinutes()).isEqualTo(60);
            });
  }

  @Test
  void priceUsesConfiguredCeilHourTierAndFifthIsochroneExtendsTheBoundary() {
    WarehouseCapacitySnapshot tariffs = snapshot(5);
    SlotHarness withinTier = new SlotHarness(1, 14_401, tariffs);

    var offers = withinTier.search();

    assertThat(offers)
        .isNotEmpty()
        .allSatisfy(
            offer -> {
              assertThat(offer.deliveryPriceRubles()).isEqualTo(30_000);
              assertThat(offer.priceIsochroneMinutes()).isEqualTo(300);
              assertThat(offer.priceZoneId()).isNull();
            });
    assertThat(new SlotHarness(1, 18_001, tariffs).search()).isEmpty();
  }

  @Test
  void specialPriceOverridesOnlyAfterTheDynamicIsochroneBoundaryIsProven() {
    WarehouseCapacitySnapshot tariffs = snapshot(5);
    SlotHarness insideBoundary = new SlotHarness(1, 18_000, tariffs);
    insideBoundary.withPriceZone(7_000);

    assertThat(insideBoundary.search())
        .isNotEmpty()
        .allSatisfy(
            offer -> {
              assertThat(offer.deliveryPriceRubles()).isEqualTo(7_000);
              assertThat(offer.priceZoneId()).isEqualTo(PRICE_ZONE);
              assertThat(offer.priceIsochroneMinutes()).isNull();
            });

    SlotHarness outsideBoundary = new SlotHarness(1, 18_001, tariffs);
    outsideBoundary.withPriceZone(7_000);
    assertThat(outsideBoundary.search()).isEmpty();
  }

  @Test
  void forbiddenZoneSuppressesOffersOnlyAfterExactRoutesAreEvaluated() {
    SlotHarness harness = new SlotHarness(1, 1_800, snapshot(4));
    harness.withRestriction(WarehouseCapacityRestrictionKind.FORBIDDEN);

    assertThat(harness.search()).isEmpty();
    verify(harness.travelTimes, times(4))
        .matrix(
            anyList(),
            any(),
            any(),
            any(CustomerDeliveryProperties.Validated.class),
            any(CustomerVehicleRouteProfile.class));
  }

  @Test
  void noTrailerZoneForcesSoloTruckRoutingAndTrailerFreeCandidateFeasibility() {
    SlotHarness harness = new SlotHarness(2, 1_800, snapshot(4));
    harness.withRestriction(WarehouseCapacityRestrictionKind.NO_TRAILER);

    assertThat(harness.search())
        .isNotEmpty()
        .allSatisfy(
            offer -> {
              assertThat(offer.routeProfile().combinationLengthMeters()).isEqualTo(10.0);
              assertThat(offer.routeProfile().axleCount()).isEqualTo(2);
            });
    verify(harness.capacity, times(4))
        .evaluate(
            any(CustomerTravelTimeMatrix.class),
            org.mockito.ArgumentMatchers.argThat(
                jobs ->
                    jobs.stream()
                        .filter(DeliveryJob::candidate)
                        .allMatch(job -> !job.trailerAccessAllowed())),
            any(CustomerDeliveryProperties.Validated.class),
            anyList(),
            anyInt());
  }

  @Test
  void holdRejectsAnOfferWhenItsSpecialPriceZoneChanges() {
    SlotHarness harness = new SlotHarness(1, 1_800, snapshot(4));
    harness.withPriceZone(7_000);
    harness.search();
    CustomerDeliverySlot offered = harness.latestOffers.getFirst();
    when(
            harness.slotStore.required(SUBJECT, INQUIRY, offered.getId()))
        .thenReturn(offered);
    harness.withPriceZone(7_500);

    assertThatThrownBy(
            () ->
                harness.service.hold(
                    new CustomerIdentity(SUBJECT, "customer"),
                    offered.getId(),
                    offered.getVersion(),
                    new HoldCustomerDeliverySlotRequest(INQUIRY, 0L, 1, true, true)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("CUSTOMER_DELIVERY_SLOT_TAKEN"));
    verify(harness.holdStore, never()).hold(any());
  }

  @Test
  void twoCabinDeliveryUsesTheExactTruckAndTrailerRouteProfile() {
    SlotHarness trailerHarness = new SlotHarness(2, 1_800, snapshot(4));
    assertThat(trailerHarness.search())
        .isNotEmpty()
        .allSatisfy(
            offer -> {
              assertThat(offer.routeProfile().combinationLengthMeters()).isEqualTo(12.0);
              assertThat(offer.routeProfile().axleCount()).isEqualTo(3);
            });
  }

  @Test
  void representativeWarehouseWithLocalCapacityOffersOnlyDuringDay() {
    SlotHarness harness =
        new SlotHarness(1, 1_800, snapshot(4), true, true, true);

    assertThat(harness.search())
        .singleElement()
        .satisfies(
            offer -> {
              assertThat(offer.kind()).isEqualTo(CustomerDeliverySlotKind.DURING_DAY);
              assertThat(offer.capacityRemaining()).isZero();
            });
  }

  @Test
  void ordinaryWarehouseWithoutLocalCapacityRemainsFailClosed() {
    SlotHarness harness =
        new SlotHarness(1, 1_800, snapshot(4), false, false, false);

    assertThat(harness.search()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {31, 126})
  void largePublishedWorkloadUsesExactRouteCapacityBeforeOfferingSlots(int existingJobs) {
    SlotHarness harness = new SlotHarness(1, 1_800, snapshot(4));
    harness.withGeneratedDeliveries(existingJobs);
    harness.withPriceZone(7_000);

    assertThat(harness.search())
        .hasSize(4)
        .allSatisfy(offer -> assertThat(offer.deliveryPriceRubles()).isEqualTo(7_000));
    verify(harness.travelTimes, times(4))
        .matrix(
            org.mockito.ArgumentMatchers.argThat(points -> points.size() == existingJobs + 2),
            any(),
            any(),
            any(CustomerDeliveryProperties.Validated.class),
            any(CustomerVehicleRouteProfile.class));
    verify(harness.capacity, times(4))
        .evaluate(
            any(CustomerTravelTimeMatrix.class),
            org.mockito.ArgumentMatchers.argThat(
                jobs ->
                    jobs.size() == existingJobs + 1
                        && jobs.stream().filter(DeliveryJob::candidate).count() == 1),
            any(CustomerDeliveryProperties.Validated.class),
            anyList(),
            anyInt());
  }

  @Test
  void oversizedWorkloadPropagatesRoutingLimitWithoutReplacingOffersWithAnEmptyList() {
    SlotHarness harness = new SlotHarness(1, 1_800, snapshot(4));
    harness.withGeneratedDeliveries(127);
    OrderProblemException workloadLimit =
        new OrderProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "CUSTOMER_DELIVERY_WORKLOAD_LIMIT",
            "Нагрузка склада слишком велика для онлайн-расчёта слотов");
    when(harness.travelTimes.matrix(
            org.mockito.ArgumentMatchers.argThat(points -> points.size() == 129),
            any(),
            any(),
            any(CustomerDeliveryProperties.Validated.class),
            any(CustomerVehicleRouteProfile.class)))
        .thenThrow(workloadLimit);

    assertThatThrownBy(harness::search).isSameAs(workloadLimit);
    verify(harness.slotStore, never()).replaceOffers(any(), any(), anyList());
    verify(harness.capacity, never()).evaluate(any(), anyList(), any(), anyList(), anyInt());
  }

  @Test
  void representativeWarehouseWithoutConfirmedCapacityRemainsFailClosed() {
    SlotHarness harness =
        new SlotHarness(1, 1_800, snapshot(4), true, false, false);

    assertThat(harness.search()).isEmpty();
  }

  @Test
  void holdRechecksConfirmedCapacityAndRejectsWhenItDisappears() {
    SlotHarness harness =
        new SlotHarness(1, 1_800, snapshot(4), true, true, true);
    harness.search();
    CustomerDeliverySlot offered = harness.latestOffers.getFirst();
    when(harness.capacity.evaluate(any(), anyList(), any(), anyList(), anyInt()))
        .thenReturn(new CapacityDecision(false, 0));
    when(
            harness.slotStore.required(SUBJECT, INQUIRY, offered.getId()))
        .thenReturn(offered);

    assertThatThrownBy(
            () ->
                harness.service.hold(
                    new CustomerIdentity(SUBJECT, "customer"),
                    offered.getId(),
                    offered.getVersion(),
                    new HoldCustomerDeliverySlotRequest(INQUIRY, 0L, 1, true, true)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("CUSTOMER_DELIVERY_SLOT_TAKEN"));
    verify(harness.holdStore, never()).hold(any());
  }

  @Test
  void bookingRescheduleSearchUsesTheSavedOrdersExactAddressAndCabinCount() {
    SlotHarness harness = new SlotHarness(1, 1_800, snapshot(4));
    CustomerRentalSession booked = mock(CustomerRentalSession.class);
    CustomerDeliverySlot confirmed = mock(CustomerDeliverySlot.class);
    OrderDetailResponse order = mock(OrderDetailResponse.class);
    OrderUnitResponse unit = mock(OrderUnitResponse.class);
    when(booked.getVersion()).thenReturn(4L);
    when(booked.getState()).thenReturn(dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState.BOOKED);
    when(booked.getInquiryId()).thenReturn(INQUIRY);
    when(booked.getWarehouseId()).thenReturn(WAREHOUSE);
    when(booked.getOrderId()).thenReturn(ORDER);
    when(booked.getDeliverySlotId()).thenReturn(CONFIRMED_SLOT);
    when(harness.sessions.requiredBooking(SUBJECT, BOOKING))
        .thenReturn(booked);
    when(
            harness.slotStore.required(SUBJECT, INQUIRY, CONFIRMED_SLOT))
        .thenReturn(confirmed);
    when(confirmed.getId()).thenReturn(CONFIRMED_SLOT);
    when(confirmed.getState()).thenReturn(CustomerDeliverySlotState.CONFIRMED);
    when(confirmed.getBookingId()).thenReturn(BOOKING);
    when(confirmed.getOrderId()).thenReturn(ORDER);
    when(confirmed.getDeliveryAddress()).thenReturn("Великий Новгород, тестовый адрес");
    when(confirmed.getLatitude()).thenReturn(new BigDecimal("55.800000"));
    when(confirmed.getLongitude()).thenReturn(new BigDecimal("37.700000"));
    when(confirmed.getDeliveryDate()).thenReturn(LocalDate.of(2026, 8, 28));
    when(confirmed.getSiteCabinCapacity()).thenReturn(1);
    when(confirmed.getCabinCount()).thenReturn(1);
    when(confirmed.isPrivateSiteAccessConfirmed()).thenReturn(true);
    when(confirmed.isFailedTripChargeAcknowledged()).thenReturn(true);
    when(order.status()).thenReturn(RentalOrderStatus.SAVED);
    when(order.permissions()).thenReturn(new OrderPermissions(true, true, true, false));
    when(order.warehouseId()).thenReturn(WAREHOUSE);
    when(order.deliveryAddress()).thenReturn("Великий Новгород, тестовый адрес");
    when(order.latitude()).thenReturn(new BigDecimal("55.800000"));
    when(order.longitude()).thenReturn(new BigDecimal("37.700000"));
    when(unit.added()).thenReturn(true);
    when(order.units()).thenReturn(List.of(unit));
    when(harness.authorizer.orderActor(any(), eq(WAREHOUSE))).thenReturn(mock(OrderActor.class));
    when(harness.rentalOrders.get(any(), eq(ORDER))).thenReturn(order);

    var offers =
        harness.service.searchBooking(
            new CustomerIdentity(SUBJECT, "customer"),
            BOOKING,
            new SearchCustomerBookingRescheduleRequest(4L));

    assertThat(offers).isNotEmpty();
    assertThat(harness.latestOffers)
        .allSatisfy(
            offer -> {
              assertThat(offer.getDeliveryAddress())
                  .isEqualTo("Великий Новгород, тестовый адрес");
              assertThat(offer.getCabinCount()).isOne();
            });
    verify(harness.rentals, never()).selectedCabinIds(any(), any());
  }

  /** Minimal deterministic collaborator set for dynamic isochrone search cases. */
  private static final class SlotHarness {
    private final CustomerRentalService rentals = mock(CustomerRentalService.class);
    private final CustomerWarehouseService warehouses = mock(CustomerWarehouseService.class);
    private final CustomerRentalSessionStore sessions = mock(CustomerRentalSessionStore.class);
    private final CustomerDeliverySlotStore slotStore = mock(CustomerDeliverySlotStore.class);
    private final CustomerDeliverySlotHoldStore holdStore = mock(CustomerDeliverySlotHoldStore.class);
    private final WarehouseCapacityJobRepository generated =
        mock(WarehouseCapacityJobRepository.class);
    private final WarehouseCapacityShiftRepository shifts =
        mock(WarehouseCapacityShiftRepository.class);
    private final WarehouseCapacityIsochroneTariffRepository isochroneTariffs =
        mock(WarehouseCapacityIsochroneTariffRepository.class);
    private final WarehouseCapacityPriceZoneRepository priceZones =
        mock(WarehouseCapacityPriceZoneRepository.class);
    private final WarehouseCapacityRestrictionZoneRepository restrictionZones =
        mock(WarehouseCapacityRestrictionZoneRepository.class);
    private final WarehouseCapacitySnapshotRepository snapshots =
        mock(WarehouseCapacitySnapshotRepository.class);
    private final CustomerDeliveryPriceClassifier prices =
        new CustomerDeliveryPriceClassifier(new ObjectMapper());
    private final ValhallaCustomerTravelTimeClient travelTimes =
        mock(ValhallaCustomerTravelTimeClient.class);
    private final CustomerRouteCapacityPlanner capacity = mock(CustomerRouteCapacityPlanner.class);
    private final RepresentativeDeliverySlotPolicy representativePolicy =
        new RepresentativeDeliverySlotPolicy();
    private final DriverLogisticsTaskRepository driverTasks =
        mock(DriverLogisticsTaskRepository.class);
    private final RentalOrderService rentalOrders = mock(RentalOrderService.class);
    private final CustomerAuthorizer authorizer = mock(CustomerAuthorizer.class);
    private final List<CustomerDeliverySlot> latestOffers = new java.util.ArrayList<>();
    private final CustomerDeliverySlotService service;

    private SlotHarness(
        int cabinCount,
        long oneWayTravelSeconds,
        WarehouseCapacitySnapshot snapshot) {
      this(cabinCount, oneWayTravelSeconds, snapshot, false, true, true);
    }

    private SlotHarness(
        int cabinCount,
        long oneWayTravelSeconds,
        WarehouseCapacitySnapshot snapshot,
        boolean representative,
        boolean localShift,
        boolean localCapacityFeasible) {
      CustomerRentalSession session = CustomerRentalSession.create(INQUIRY, SUBJECT, WAREHOUSE);
      CustomerDeliveryProperties.Validated configuration = configuration();
      CustomerTravelTimeMatrix matrix =
          new CustomerTravelTimeMatrix(
              List.of(new GeoPoint(55.75, 37.61), new GeoPoint(55.80, 37.70)),
              List.of(
                  List.of(0L, oneWayTravelSeconds),
                  List.of(oneWayTravelSeconds, 0L)));
      WarehouseCapacityShift shift = mock(WarehouseCapacityShift.class);
      when(shift.getSourceShiftId()).thenReturn(UUID.randomUUID());
      when(shift.getDeliveryDate()).thenReturn(LocalDate.of(2026, 8, 28));
      when(shift.getShiftStart()).thenReturn(LocalTime.of(8, 0));
      when(shift.getShiftEnd()).thenReturn(LocalTime.of(20, 0));
      when(shift.getBreakMinutes()).thenReturn(0);
      when(shift.getCabinCapacity()).thenReturn(2);
      when(sessions.required(SUBJECT, INQUIRY)).thenReturn(session);
      when(warehouses.validated(WAREHOUSE)).thenReturn(configuration);
      when(warehouses.required(WAREHOUSE))
          .thenReturn(warehouseIdentity(WAREHOUSE, representative));
      when(rentals.selectedCabinIds(any(), eq(INQUIRY)))
          .thenReturn(
              cabinCount == 1
                  ? List.of(UUID.randomUUID())
                  : List.of(UUID.randomUUID(), UUID.randomUUID()));
      when(slotStore.workload(eq(WAREHOUSE), any(), any()))
          .thenReturn(List.of());
      when(generated.findCapacityWorkload(eq(WAREHOUSE), any())).thenReturn(List.of());
      when(shifts.findCapacityShifts(eq(WAREHOUSE), any()))
          .thenReturn(localShift ? List.of(shift) : List.of());
      when(isochroneTariffs.findTariffs(WAREHOUSE))
          .thenReturn(snapshot.getIsochroneTariffs());
      when(priceZones.findTariffZones(WAREHOUSE)).thenReturn(List.of());
      when(restrictionZones.findRestrictionZones(WAREHOUSE)).thenReturn(List.of());
      when(snapshots.findByWarehouseId(WAREHOUSE)).thenReturn(Optional.ofNullable(snapshot));
      when(travelTimes.matrix(
              anyList(),
              any(),
              any(),
              same(configuration),
              any(CustomerVehicleRouteProfile.class)))
          .thenReturn(matrix);
      when(capacity.evaluate(same(matrix), anyList(), same(configuration), anyList(), anyInt()))
          .thenReturn(new CapacityDecision(localCapacityFeasible, 0));
      when(
              slotStore.replaceOffers(eq(SUBJECT), eq(INQUIRY), anyList()))
          .thenAnswer(
              invocation -> {
                latestOffers.clear();
                latestOffers.addAll(invocation.getArgument(2));
                return List.copyOf(latestOffers);
              });
      service =
          new CustomerDeliverySlotService(
              rentals,
              warehouses,
              sessions,
              slotStore,
              holdStore,
              generated,
              shifts,
              isochroneTariffs,
              priceZones,
              restrictionZones,
              snapshots,
              prices,
              travelTimes,
              capacity,
              representativePolicy,
              driverTasks,
              rentalOrders,
              authorizer,
              CLOCK);
    }

    private List<dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerDeliverySlotResponse>
        search() {
      return service.search(
          new CustomerIdentity(SUBJECT, "customer"),
          new DeliverySlotSearchRequest(
              INQUIRY,
              "Москва",
              BigDecimal.valueOf(55.80),
              BigDecimal.valueOf(37.70),
              2,
              false,
              false));
    }

    private void withPriceZone(long deliveryPriceRubles) {
      WarehouseCapacityPriceZone zone = mock(WarehouseCapacityPriceZone.class);
      when(zone.getSourceZoneId()).thenReturn(PRICE_ZONE);
      when(zone.getSourceZoneVersion()).thenReturn(1L);
      when(zone.getDeliveryPriceRubles()).thenReturn(deliveryPriceRubles);
      when(zone.getPickupPriceRubles()).thenReturn(4_000L);
      when(zone.getGeometryJson()).thenReturn(coveringGeometry());
      when(priceZones.findTariffZones(WAREHOUSE)).thenReturn(List.of(zone));
    }

    private void withRestriction(WarehouseCapacityRestrictionKind kind) {
      WarehouseCapacityRestrictionZone zone = mock(WarehouseCapacityRestrictionZone.class);
      when(zone.getSourceZoneId()).thenReturn(RESTRICTION_ZONE);
      when(zone.getSourceZoneVersion()).thenReturn(1L);
      when(zone.getKind()).thenReturn(kind);
      when(zone.getGeometryJson()).thenReturn(coveringGeometry());
      when(restrictionZones.findRestrictionZones(WAREHOUSE)).thenReturn(List.of(zone));
    }

    private void withGeneratedDeliveries(int count) {
      List<WarehouseCapacityJob> jobs =
          java.util.stream.IntStream.range(0, count)
              .mapToObj(
                  index -> {
                    WarehouseCapacityJob job = mock(WarehouseCapacityJob.class);
                    when(job.getSourceJobId())
                        .thenReturn(new UUID(0L, Integer.toUnsignedLong(index + 1)));
                    when(job.getTaskType()).thenReturn(WarehouseCapacityTaskType.DELIVERY);
                    when(job.getDeliveryDate()).thenReturn(LocalDate.of(2026, 8, 28));
                    when(job.getLatitude())
                        .thenReturn(BigDecimal.valueOf(55.70 + index / 10_000.0));
                    when(job.getLongitude())
                        .thenReturn(BigDecimal.valueOf(37.60 + index / 10_000.0));
                    when(job.getCabinCount()).thenReturn(1);
                    when(job.getWindowStart()).thenReturn(LocalTime.of(9, 0));
                    when(job.getWindowEnd()).thenReturn(LocalTime.of(18, 0));
                    when(job.getServiceMinutes()).thenReturn(30);
                    when(job.isTrailerAccessAllowed()).thenReturn(true);
                    when(job.getPriority()).thenReturn(index);
                    when(job.isMandatory()).thenReturn(true);
                    return job;
                  })
              .toList();
      int pointCount = count + 2;
      List<GeoPoint> points =
          java.util.stream.IntStream.range(0, pointCount)
              .mapToObj(index -> new GeoPoint(55.70 + index / 10_000.0, 37.60))
              .toList();
      List<List<Long>> seconds =
          java.util.stream.IntStream.range(0, pointCount)
              .mapToObj(
                  from ->
                      java.util.stream.IntStream.range(0, pointCount)
                          .mapToObj(to -> from == to ? 0L : 1_800L)
                          .toList())
              .toList();
      CustomerTravelTimeMatrix expandedMatrix = new CustomerTravelTimeMatrix(points, seconds);
      when(generated.findCapacityWorkload(eq(WAREHOUSE), any())).thenReturn(jobs);
      when(travelTimes.matrix(
              anyList(),
              any(),
              any(),
              any(CustomerDeliveryProperties.Validated.class),
              any(CustomerVehicleRouteProfile.class)))
          .thenReturn(expandedMatrix);
      when(capacity.evaluate(
              same(expandedMatrix),
              anyList(),
              any(CustomerDeliveryProperties.Validated.class),
              anyList(),
              anyInt()))
          .thenReturn(new CapacityDecision(true, 0));
    }
  }

  private static String coveringGeometry() {
    return """
        {"type":"MultiPolygon","coordinates":[[[
          [37.0,55.0],[38.0,55.0],[38.0,56.0],[37.0,56.0],[37.0,55.0]
        ]]]}
        """;
  }

  private static WarehouseIdentity warehouseIdentity(UUID id, boolean representative) {
    return new WarehouseIdentity(
        id,
        0,
        true,
        representative ? "Представительский склад" : "Основной склад",
        "",
        null,
        null,
        null,
        "Europe/Moscow",
        representative);
  }

  private static WarehouseCapacitySnapshot snapshot(int tierCount) {
    List<WarehouseCapacityIsochroneTariff.Facts> tariffs =
        java.util.stream.IntStream.rangeClosed(1, tierCount)
            .mapToObj(
                hour ->
                    new WarehouseCapacityIsochroneTariff.Facts(
                        hour * 60, 5_000L + hour * 5_000L))
            .toList();
    return WarehouseCapacitySnapshot.create(
        WAREHOUSE,
        1,
        "a".repeat(64),
        List.of(),
        List.of(),
        tariffs,
        OffsetDateTime.parse("2026-08-27T05:00:00Z"));
  }

  private static CustomerDeliveryProperties.Validated configuration() {
    return new CustomerDeliveryProperties(
            true,
            List.of(
                new CustomerDeliveryProperties.Depot(
                    true,
                    WAREHOUSE.toString(),
                    BigDecimal.valueOf(55.75),
                    BigDecimal.valueOf(37.61))),
            "http://127.0.0.1:8002",
            "test-routing-data-v1",
            Duration.ofMinutes(15),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            30,
            60,
            1,
            1,
            Duration.ofMinutes(10),
            Duration.ofMinutes(10),
            1.15,
            5,
            LocalTime.of(8, 0),
            LocalTime.of(9, 0),
            LocalTime.of(18, 0),
            LocalTime.of(20, 0),
            180,
            60,
            30,
            45,
            30,
            CustomerDeliveryProperties.DeliveryWindowSemantics.START_WITHIN_SLOT,
            CustomerDeliveryProperties.PickupPolicy.RETURN_LEG_ONLY,
            4.0,
            2.5,
            10.0,
            12.0,
            8.0,
            2,
            4.0,
            2.5,
            12.0,
            20.0,
            8.0,
            3)
        .validated(WAREHOUSE);
  }
}
