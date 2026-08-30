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
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityIsochroneTariff;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityIsochroneTariffRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityJobRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityShiftRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacitySnapshotRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityShift;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacitySnapshot;
import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotKind;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.CapacityDecision;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix.GeoPoint;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerVehicleRouteProfile;
import dev.buhanzaz.rwms.logistics.customer.routing.ValhallaCustomerTravelTimeClient;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseSupportLink;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Covers delivery-priority workload selection before CustomerApp slot routing. */
class CustomerDeliverySlotServiceTest {
  private static final UUID SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-000000000501");
  private static final UUID INQUIRY =
      UUID.fromString("00000000-0000-0000-0000-000000000502");
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000503");
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
    WarehouseCapacitySnapshotRepository snapshots = mock(WarehouseCapacitySnapshotRepository.class);
    ValhallaCustomerTravelTimeClient travelTimes =
        mock(ValhallaCustomerTravelTimeClient.class);
    CustomerRouteCapacityPlanner capacity = mock(CustomerRouteCapacityPlanner.class);
    RepresentativeDeliverySlotPolicy representativePolicy =
        new RepresentativeDeliverySlotPolicy(mock(LogisticsDependencyGateway.class));
    DriverLogisticsTaskRepository driverTasks = mock(DriverLogisticsTaskRepository.class);
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
            snapshots,
            travelTimes,
            capacity,
            representativePolicy,
            driverTasks,
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
    when(slotStore.workload(eq(WAREHOUSE), any(), any())).thenReturn(List.of());
    when(generated.findCapacityWorkload(eq(WAREHOUSE), any())).thenReturn(List.of());
    when(shifts.findCapacityShifts(eq(WAREHOUSE), any())).thenReturn(List.of());
    WarehouseCapacitySnapshot snapshot = snapshot(4);
    when(isochroneTariffs.findTariffs(WAREHOUSE)).thenReturn(snapshot.getIsochroneTariffs());
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
    when(slotStore.replaceOffers(eq(SUBJECT), eq(INQUIRY), anyList()))
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
        new SlotHarness(1, 1_800, snapshot(4), true, true, true, List.of());

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
        new SlotHarness(1, 1_800, snapshot(4), false, false, false, List.of());

    assertThat(harness.search()).isEmpty();
    verify(harness.dependencies, never()).listWarehouseSupportNetwork(WAREHOUSE);
  }

  @Test
  void eligibleSupportEdgeOffersFlexibleDayWithoutLocalShift() {
    SlotHarness harness =
        new SlotHarness(
            1, 1_800, snapshot(4), true, false, false, List.of(eligibleSupportLink(Set.of())));

    assertThat(harness.search())
        .singleElement()
        .satisfies(
            offer -> {
              assertThat(offer.kind()).isEqualTo(CustomerDeliverySlotKind.DURING_DAY);
              assertThat(offer.capacityRemaining()).isZero();
              assertThat(offer.roadRouteConfirmed()).isTrue();
            });
  }

  @Test
  void flexibleTwoCabinDayStillRequiresTruckAndTrailerRoadProfile() {
    SlotHarness harness =
        new SlotHarness(
            2, 1_800, snapshot(4), true, false, false, List.of(eligibleSupportLink(Set.of())));

    assertThat(harness.search())
        .singleElement()
        .satisfies(
            offer -> {
              assertThat(offer.routeProfile().combinationLengthMeters()).isEqualTo(12.0);
              assertThat(offer.routeProfile().axleCount()).isEqualTo(3);
            });
  }

  @Test
  void excludedSupportDateLeavesRepresentativeWarehouseWithoutOffers() {
    SlotHarness harness =
        new SlotHarness(
            1,
            1_800,
            snapshot(4),
            true,
            false,
            false,
            List.of(eligibleSupportLink(Set.of(LocalDate.of(2026, 8, 28)))));

    assertThat(harness.search()).isEmpty();
  }

  @Test
  void holdRechecksSupportPolicyAndRejectsEdgeRemovedAfterSearch() {
    SlotHarness harness =
        new SlotHarness(
            1, 1_800, snapshot(4), true, false, false, List.of(eligibleSupportLink(Set.of())));
    harness.search();
    CustomerDeliverySlot offered = harness.latestOffers.getFirst();
    when(harness.dependencies.listWarehouseSupportNetwork(WAREHOUSE)).thenReturn(List.of());
    when(harness.slotStore.required(SUBJECT, INQUIRY, offered.getId())).thenReturn(offered);

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
    private final WarehouseCapacitySnapshotRepository snapshots =
        mock(WarehouseCapacitySnapshotRepository.class);
    private final ValhallaCustomerTravelTimeClient travelTimes =
        mock(ValhallaCustomerTravelTimeClient.class);
    private final CustomerRouteCapacityPlanner capacity = mock(CustomerRouteCapacityPlanner.class);
    private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    private final RepresentativeDeliverySlotPolicy representativePolicy =
        new RepresentativeDeliverySlotPolicy(dependencies);
    private final DriverLogisticsTaskRepository driverTasks =
        mock(DriverLogisticsTaskRepository.class);
    private final List<CustomerDeliverySlot> latestOffers = new java.util.ArrayList<>();
    private final CustomerDeliverySlotService service;

    private SlotHarness(
        int cabinCount,
        long oneWayTravelSeconds,
        WarehouseCapacitySnapshot snapshot) {
      this(cabinCount, oneWayTravelSeconds, snapshot, false, true, true, List.of());
    }

    private SlotHarness(
        int cabinCount,
        long oneWayTravelSeconds,
        WarehouseCapacitySnapshot snapshot,
        boolean representative,
        boolean localShift,
        boolean localCapacityFeasible,
        List<WarehouseSupportLink> supportNetwork) {
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
      when(slotStore.workload(eq(WAREHOUSE), any(), any())).thenReturn(List.of());
      when(generated.findCapacityWorkload(eq(WAREHOUSE), any())).thenReturn(List.of());
      when(shifts.findCapacityShifts(eq(WAREHOUSE), any()))
          .thenReturn(localShift ? List.of(shift) : List.of());
      when(isochroneTariffs.findTariffs(WAREHOUSE))
          .thenReturn(snapshot.getIsochroneTariffs());
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
      when(dependencies.listWarehouseSupportNetwork(WAREHOUSE)).thenReturn(supportNetwork);
      when(slotStore.replaceOffers(eq(SUBJECT), eq(INQUIRY), anyList()))
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
              snapshots,
              travelTimes,
              capacity,
              representativePolicy,
              driverTasks,
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
  }

  private static WarehouseSupportLink eligibleSupportLink(Set<LocalDate> excludedDates) {
    UUID supportWarehouseId =
        UUID.fromString("00000000-0000-0000-0000-000000000504");
    return new WarehouseSupportLink(
        UUID.fromString("00000000-0000-0000-0000-000000000505"),
        0,
        warehouseIdentity(supportWarehouseId, false),
        warehouseIdentity(WAREHOUSE, true),
        1,
        true,
        true,
        false,
        false,
        false,
        false,
        Set.of(),
        Set.of(),
        excludedDates,
        LocalTime.of(8, 0),
        LocalTime.of(20, 0));
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
