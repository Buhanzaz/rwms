package dev.buhanzaz.rwms.logistics.order.service;

import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ApplyPlanningAssignmentsRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningAssignmentRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningAssignmentType;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverAudienceMode;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftPlanRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftRouteOperationKind;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftRouteOperationRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftTrailerRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftVehicleConfiguration;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftVehicleRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsDocumentView;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotKind;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerDeliverySlotStore;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRentalShipmentRequest;
import dev.buhanzaz.rwms.logistics.order.domain.DesiredDeliveryWindow;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** Verifies the versioned, date-fenced hand-off between RWMS orders and the route planner. */
class RentalOrderPlanningIntegrationServiceTest {
  private static final UUID WAREHOUSE_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID ORDER_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID DRIVER_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000003");
  private static final UUID REPRESENTATIVE_WAREHOUSE_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000007");
  private static final UUID UNIT_ONE =
      UUID.fromString("10000000-0000-0000-0000-000000000004");
  private static final UUID UNIT_TWO =
      UUID.fromString("10000000-0000-0000-0000-000000000005");
  private static final UUID UNIT_THREE =
      UUID.fromString("10000000-0000-0000-0000-000000000006");
  private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

  private final RentalOrderRepository orders = mock(RentalOrderRepository.class);
  private final RentalOrderReadService reads = mock(RentalOrderReadService.class);
  private final LogisticsDocumentLineRepository lines =
      mock(LogisticsDocumentLineRepository.class);
  private final LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
  private final DriverLogisticsTaskRepository driverTasks =
      mock(DriverLogisticsTaskRepository.class);
  private final RentalOrderService rentalOrders = mock(RentalOrderService.class);
  private final LogisticsWarehouseLifecycle lifecycle = mock(LogisticsWarehouseLifecycle.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final CustomerDeliverySlotStore customerDeliverySlots =
      mock(CustomerDeliverySlotStore.class);
  private final TransferRouteCargoEnricher transferRouteCargoEnricher =
      mock(TransferRouteCargoEnricher.class);
  private final RentalOrderPlanningIntegrationService service =
      new RentalOrderPlanningIntegrationService(
          orders,
          reads,
          lines,
          documents,
          driverTasks,
          rentalOrders,
          lifecycle,
          dependencies,
          customerDeliverySlots,
          transferRouteCargoEnricher);

  private final RentalOrder order = mock(RentalOrder.class);

  @BeforeEach
  void setUp() {
    when(transferRouteCargoEnricher.enrich(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    OffsetDateTime effectiveFrom = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
    when(dependencies.warehouseTimeZoneAt(eq(WAREHOUSE_ID), any()))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseTimeZone(
                WAREHOUSE_ID, MOSCOW.getId(), effectiveFrom));
    when(order.getId()).thenReturn(ORDER_ID);
    when(order.getVersion()).thenReturn(7L);
    when(order.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(order.getStatus()).thenReturn(RentalOrderStatus.SAVED);
    when(order.getPaymentState()).thenReturn(RentalOrderPaymentState.CONFIRMED);
    when(orders.findPlanningCandidateById(ORDER_ID)).thenReturn(Optional.of(order));
    when(lines.findAssignedRentalShipmentAssetIds(eq(ORDER_ID), any())).thenReturn(List.of());
    when(driverTasks.findAllBySourceTypeAndSourceIdIn(
            eq(DriverTaskSourceType.LOGISTICS_DOCUMENT),
            org.mockito.ArgumentMatchers.<List<UUID>>any()))
        .thenAnswer(
            invocation ->
                invocation.<List<UUID>>getArgument(1).stream()
                    .map(RentalOrderPlanningIntegrationServiceTest::plannerShipmentTask)
                    .toList());
    when(rentalOrders.rentalShipmentAdmissionWarehouse(any(), eq(ORDER_ID), any(), any()))
        .thenAnswer(
            invocation -> {
              UUID requestedSource = invocation.getArgument(3);
              return requestedSource == null ? order.getWarehouseId() : requestedSource;
            });
  }

  @Test
  void feedExportsOnlyUnplannedCabinsAndClientApprovedDays() {
    LocalDate first = LocalDate.now(MOSCOW).plusDays(2);
    LocalDate second = first.plusDays(1);
    OrderClient client = mock(OrderClient.class);
    when(client.getDisplayName()).thenReturn("ООО Ромашка");
    when(order.getOrderNumber()).thenReturn("А-142");
    when(order.getClient()).thenReturn(client);
    when(order.getDeliveryAddress()).thenReturn("Москва, Тестовая улица, 1");
    when(order.getLatitude()).thenReturn(new BigDecimal("55.751244"));
    when(order.getLongitude()).thenReturn(new BigDecimal("37.618423"));
    when(order.getCreatedAt()).thenReturn(OffsetDateTime.parse("2026-08-20T08:00:00Z"));
    when(order.getDesiredDeliveryWindows())
        .thenReturn(
            List.of(
                DesiredDeliveryWindow.create(first, first),
                DesiredDeliveryWindow.create(second, second)));
    when(orders.findAllPlanningCandidates(WAREHOUSE_ID, RentalOrderStatus.SAVED))
        .thenReturn(List.of(order));
    var firstReservation = reservation(UNIT_ONE);
    var secondReservation = reservation(UNIT_TWO);
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(firstReservation, secondReservation));
    when(lines.findAssignedRentalShipmentAssetIds(ORDER_ID, List.of(UNIT_ONE, UNIT_TWO)))
        .thenReturn(List.of(UNIT_ONE));

    var response = service.feed(WAREHOUSE_ID, first, second);

    assertThat(response.timeZone()).isEqualTo("Europe/Moscow");
    assertThat(response.requests()).singleElement().satisfies(request -> {
      assertThat(request.orderId()).isEqualTo(ORDER_ID);
      assertThat(request.sourceRevision()).matches("^[0-9a-f]{64}$");
      assertThat(request.unitIds()).containsExactly(UNIT_TWO);
      assertThat(request.unitReservations())
          .singleElement()
          .satisfies(reservation -> {
            assertThat(reservation.unitId()).isEqualTo(UNIT_TWO);
            assertThat(reservation.inventorySourceWarehouseId()).isEqualTo(WAREHOUSE_ID);
          });
      assertThat(request.quantity()).isEqualTo(1);
      assertThat(request.latitude()).isEqualByComparingTo("55.751244");
      assertThat(request.dateOptions()).extracting(option -> option.date())
          .containsExactly(first, second);
      assertThat(request.dateOptions()).extracting(option -> option.priority())
          .containsExactly(0, 1);
      assertThat(request.dateOptions()).allMatch(option -> !option.isHard());
      assertThat(request.deliveryPriceRubles()).isNull();
      assertThat(request.priceIsochroneMinutes()).isNull();
    });

    var replay = service.feed(WAREHOUSE_ID, first, second);
    assertThat(replay.requests().getFirst().sourceRevision())
        .isEqualTo(response.requests().getFirst().sourceRevision());
  }

  @Test
  void feedRevisionChangesWhenThePhysicalInventorySourceChanges() {
    LocalDate date = LocalDate.now(MOSCOW).plusDays(2);
    OrderClient client = mock(OrderClient.class);
    when(client.getDisplayName()).thenReturn("ООО Ромашка");
    when(order.getOrderNumber()).thenReturn("А-142");
    when(order.getClient()).thenReturn(client);
    when(order.getDeliveryAddress()).thenReturn("Москва, Тестовая улица, 1");
    when(order.getLatitude()).thenReturn(new BigDecimal("55.751244"));
    when(order.getLongitude()).thenReturn(new BigDecimal("37.618423"));
    when(order.getCreatedAt()).thenReturn(OffsetDateTime.parse("2026-08-20T08:00:00Z"));
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(date, date)));
    when(orders.findAllPlanningCandidates(WAREHOUSE_ID, RentalOrderStatus.SAVED))
        .thenReturn(List.of(order));
    when(reads.readUnitsForShipment(order))
        .thenReturn(
            List.of(reservation(UNIT_ONE, WAREHOUSE_ID)),
            List.of(reservation(UNIT_ONE, REPRESENTATIVE_WAREHOUSE_ID)));

    var local = service.feed(WAREHOUSE_ID, date, date).requests().getFirst();
    var remote = service.feed(WAREHOUSE_ID, date, date).requests().getFirst();

    assertThat(local.sourceRevision()).isNotEqualTo(remote.sourceRevision());
    assertThat(local.unitReservations().getFirst().inventorySourceWarehouseId())
        .isEqualTo(WAREHOUSE_ID);
    assertThat(remote.unitReservations().getFirst().inventorySourceWarehouseId())
        .isEqualTo(REPRESENTATIVE_WAREHOUSE_ID);
  }

  @Test
  void feedExportsConfirmedDeliveryPriceAndChangesRevisionWhenPricingChanges() {
    LocalDate date = LocalDate.now(MOSCOW).plusDays(2);
    OrderClient client = mock(OrderClient.class);
    CustomerDeliverySlot slot = mock(CustomerDeliverySlot.class);
    when(client.getDisplayName()).thenReturn("ООО Ромашка");
    when(order.getOrderNumber()).thenReturn("А-142");
    when(order.getClient()).thenReturn(client);
    when(order.getDeliveryAddress()).thenReturn("Москва, Тестовая улица, 1");
    when(order.getLatitude()).thenReturn(new BigDecimal("55.751244"));
    when(order.getLongitude()).thenReturn(new BigDecimal("37.618423"));
    when(order.getCreatedAt()).thenReturn(OffsetDateTime.parse("2026-08-20T08:00:00Z"));
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(date, date)));
    when(orders.findAllPlanningCandidates(WAREHOUSE_ID, RentalOrderStatus.SAVED))
        .thenReturn(List.of(order));
    var unitReservation = reservation(UNIT_ONE);
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(unitReservation));
    when(slot.getDeliveryDate()).thenReturn(date);
    when(slot.getKind()).thenReturn(CustomerDeliverySlotKind.FIXED_WINDOW);
    when(slot.getWindowStart()).thenReturn(LocalTime.of(9, 0));
    when(slot.getWindowEnd()).thenReturn(LocalTime.of(12, 0));
    when(slot.getTravelZoneHours()).thenReturn(1);
    when(slot.getSiteCabinCapacity()).thenReturn(2);
    when(slot.getDeliveryPriceRubles()).thenReturn(10_000L, 15_000L, 15_000L);
    when(slot.getPriceIsochroneMinutes()).thenReturn(60, 60, 120);
    when(customerDeliverySlots.confirmedForOrders(List.of(ORDER_ID)))
        .thenReturn(Map.of(ORDER_ID, slot));

    var oneHourPrice = service.feed(WAREHOUSE_ID, date, date).requests().getFirst();
    var repricedOneHour = service.feed(WAREHOUSE_ID, date, date).requests().getFirst();
    var repricedTwoHours = service.feed(WAREHOUSE_ID, date, date).requests().getFirst();

    assertThat(oneHourPrice.orderVersion()).isEqualTo(repricedTwoHours.orderVersion());
    assertThat(oneHourPrice.deliveryPriceRubles()).isEqualTo(10_000L);
    assertThat(oneHourPrice.priceIsochroneMinutes()).isEqualTo(60);
    assertThat(repricedOneHour.deliveryPriceRubles()).isEqualTo(15_000L);
    assertThat(repricedOneHour.priceIsochroneMinutes()).isEqualTo(60);
    assertThat(repricedTwoHours.deliveryPriceRubles()).isEqualTo(15_000L);
    assertThat(repricedTwoHours.priceIsochroneMinutes()).isEqualTo(120);
    assertThat(oneHourPrice.sourceRevision()).isNotEqualTo(repricedOneHour.sourceRevision());
    assertThat(repricedOneHour.sourceRevision()).isNotEqualTo(repricedTwoHours.sourceRevision());
  }

  @Test
  void feedRevisionChangesWhenAnIndependentCustomerSlotFactChanges() {
    LocalDate date = LocalDate.now(MOSCOW).plusDays(2);
    OrderClient client = mock(OrderClient.class);
    CustomerDeliverySlot slot = mock(CustomerDeliverySlot.class);
    when(client.getDisplayName()).thenReturn("ООО Ромашка");
    when(order.getOrderNumber()).thenReturn("А-142");
    when(order.getClient()).thenReturn(client);
    when(order.getDeliveryAddress()).thenReturn("Москва, Тестовая улица, 1");
    when(order.getLatitude()).thenReturn(new BigDecimal("55.751244"));
    when(order.getLongitude()).thenReturn(new BigDecimal("37.618423"));
    when(order.getCreatedAt()).thenReturn(OffsetDateTime.parse("2026-08-20T08:00:00Z"));
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(date, date)));
    when(orders.findAllPlanningCandidates(WAREHOUSE_ID, RentalOrderStatus.SAVED))
        .thenReturn(List.of(order));
    var unitReservation = reservation(UNIT_ONE);
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(unitReservation));
    when(slot.getDeliveryDate()).thenReturn(date);
    when(slot.getKind()).thenReturn(CustomerDeliverySlotKind.FIXED_WINDOW);
    when(slot.getWindowStart()).thenReturn(LocalTime.of(9, 0));
    when(slot.getWindowEnd()).thenReturn(LocalTime.of(12, 0));
    when(slot.getTravelZoneHours()).thenReturn(1);
    when(slot.getSiteCabinCapacity()).thenReturn(1, 2);
    when(customerDeliverySlots.confirmedForOrders(List.of(ORDER_ID)))
        .thenReturn(Map.of(ORDER_ID, slot));

    var withoutTrailer = service.feed(WAREHOUSE_ID, date, date).requests().getFirst();
    var withTrailer = service.feed(WAREHOUSE_ID, date, date).requests().getFirst();

    assertThat(withoutTrailer.orderVersion()).isEqualTo(withTrailer.orderVersion());
    assertThat(withoutTrailer.trailerAccessAllowed()).isFalse();
    assertThat(withTrailer.trailerAccessAllowed()).isTrue();
    assertThat(withoutTrailer.sourceRevision()).isNotEqualTo(withTrailer.sourceRevision());
  }

  @Test
  void freshFeedReadsANewCustomerBookingWithoutDependingOnAPlannerTaskIdentity() {
    LocalDate date = LocalDate.now(MOSCOW).plusDays(2);
    OrderClient client = mock(OrderClient.class);
    CustomerDeliverySlot slot = mock(CustomerDeliverySlot.class);
    when(client.getDisplayName()).thenReturn("Новый клиент CustomerApp");
    when(order.getOrderNumber()).thenReturn("APP-1");
    when(order.getClient()).thenReturn(client);
    when(order.getDeliveryAddress()).thenReturn("Санкт-Петербург, Шереметевский сквер");
    when(order.getLatitude()).thenReturn(new BigDecimal("59.932364"));
    when(order.getLongitude()).thenReturn(new BigDecimal("30.348501"));
    when(order.getCreatedAt()).thenReturn(OffsetDateTime.parse("2026-08-28T08:00:00Z"));
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(date, date)));
    when(orders.findAllPlanningCandidates(WAREHOUSE_ID, RentalOrderStatus.SAVED))
        .thenReturn(List.of(), List.of(order));
    var unitReservation = reservation(UNIT_ONE);
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(unitReservation));
    when(slot.getDeliveryDate()).thenReturn(date);
    when(slot.getKind()).thenReturn(CustomerDeliverySlotKind.FIXED_WINDOW);
    when(slot.getWindowStart()).thenReturn(LocalTime.of(12, 0));
    when(slot.getWindowEnd()).thenReturn(LocalTime.of(15, 0));
    when(slot.getTravelZoneHours()).thenReturn(1);
    when(slot.getSiteCabinCapacity()).thenReturn(2);
    when(customerDeliverySlots.confirmedForOrders(List.of(ORDER_ID)))
        .thenReturn(Map.of(ORDER_ID, slot));

    assertThat(service.feed(WAREHOUSE_ID, date, date).requests()).isEmpty();

    var refreshed = service.feed(WAREHOUSE_ID, date, date);

    assertThat(refreshed.requests()).singleElement().satisfies(request -> {
      assertThat(request.orderId()).isEqualTo(ORDER_ID);
      assertThat(request.unitIds()).containsExactly(UNIT_ONE);
      assertThat(request.dateOptions()).singleElement().satisfies(option -> {
        assertThat(option.isHard()).isTrue();
        assertThat(option.windowStart()).isEqualTo(LocalTime.of(12, 0));
        assertThat(option.windowEnd()).isEqualTo(LocalTime.of(15, 0));
      });
    });
  }

  @Test
  void feedExportsDuringDayBookingAsASoftDateOnlyPlannerChoice() {
    LocalDate date = LocalDate.now(MOSCOW).plusDays(2);
    OrderClient client = mock(OrderClient.class);
    CustomerDeliverySlot slot = mock(CustomerDeliverySlot.class);
    when(client.getDisplayName()).thenReturn("Клиент с доставкой в течение дня");
    when(order.getOrderNumber()).thenReturn("APP-DAY");
    when(order.getClient()).thenReturn(client);
    when(order.getDeliveryAddress()).thenReturn("Санкт-Петербург, Невский проспект, 1");
    when(order.getLatitude()).thenReturn(new BigDecimal("59.934280"));
    when(order.getLongitude()).thenReturn(new BigDecimal("30.335099"));
    when(order.getCreatedAt()).thenReturn(OffsetDateTime.parse("2026-08-28T08:00:00Z"));
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(date, date)));
    when(orders.findAllPlanningCandidates(WAREHOUSE_ID, RentalOrderStatus.SAVED))
        .thenReturn(List.of(order));
    var unitReservation = reservation(UNIT_ONE);
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(unitReservation));
    when(slot.getDeliveryDate()).thenReturn(date);
    when(slot.getKind()).thenReturn(CustomerDeliverySlotKind.DURING_DAY);
    when(slot.getWindowStart()).thenReturn(LocalTime.of(9, 0));
    when(slot.getWindowEnd()).thenReturn(LocalTime.of(18, 0));
    when(slot.getTravelZoneHours()).thenReturn(2);
    when(slot.getSiteCabinCapacity()).thenReturn(2);
    when(customerDeliverySlots.confirmedForOrders(List.of(ORDER_ID)))
        .thenReturn(Map.of(ORDER_ID, slot));

    var option =
        service.feed(WAREHOUSE_ID, date, date).requests().getFirst().dateOptions().getFirst();

    assertThat(option.isHard()).isFalse();
    assertThat(option.windowStart()).isNull();
    assertThat(option.windowEnd()).isNull();
    assertThat(option.travelZoneHours()).isEqualTo(2);
  }

  @Test
  void boundedFeedPreservesGlobalDatePriorityAndDoesNotMakeAFilteredOptionHard() {
    LocalDate first = LocalDate.now(MOSCOW).plusDays(2);
    LocalDate second = first.plusDays(1);
    OrderClient client = mock(OrderClient.class);
    when(client.getDisplayName()).thenReturn("ООО Ромашка");
    when(order.getOrderNumber()).thenReturn("А-142");
    when(order.getClient()).thenReturn(client);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(
            List.of(
                DesiredDeliveryWindow.create(first, first),
                DesiredDeliveryWindow.create(second, second)));
    when(orders.findAllPlanningCandidates(WAREHOUSE_ID, RentalOrderStatus.SAVED))
        .thenReturn(List.of(order));
    var unitReservation = reservation(UNIT_ONE);
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(unitReservation));

    var response = service.feed(WAREHOUSE_ID, second, second);

    assertThat(response.requests()).singleElement().satisfies(request ->
        assertThat(request.dateOptions()).singleElement().satisfies(option -> {
          assertThat(option.date()).isEqualTo(second);
          assertThat(option.priority()).isEqualTo(1);
          assertThat(option.isHard()).isFalse();
        }));
  }

  @ParameterizedTest
  @NullSource
  @EnumSource(
      value = RentalOrderPaymentState.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = "CONFIRMED")
  void unpaidAssignmentIsRejectedBeforeShipmentOrDriverEffects(
      RentalOrderPaymentState paymentState) {
    LocalDate date = LocalDate.now(MOSCOW).plusDays(3);
    when(order.getPaymentState()).thenReturn(paymentState);
    assertThatThrownBy(
            () ->
                service.apply(
                    UUID.randomUUID(),
                    request(
                        List.of(
                            new PlanningAssignmentRequest(
                                ORDER_ID, 7L, date, DRIVER_ID, "Водитель 1", List.of(UNIT_ONE))),
                        List.of(shiftPlan(date)))))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.code()).isEqualTo("ORDER_PAYMENT_REQUIRED"));
    verify(lifecycle, never()).prepareDocument(any(), any(), any(), any());
    verify(rentalOrders, never()).createRentalShipment(any(), any(), any(), any(), any(), any());
    verify(dependencies, never()).registerDriverShiftPlan(any(), any(), any());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void automaticAssignmentRejectsTodayAndTomorrowWithoutCreatingShipment(int daysAhead) {
    LocalDate tomorrow = LocalDate.now(MOSCOW).plusDays(daysAhead);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(tomorrow, tomorrow)));
    var unitReservation = reservation(UNIT_ONE);
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(unitReservation));

    assertThatThrownBy(
            () ->
                service.apply(
                    UUID.randomUUID(),
                    request(
                        List.of(
                            new PlanningAssignmentRequest(
                                ORDER_ID,
                                7L,
                                tomorrow,
                                DRIVER_ID,
                                "Водитель 1",
                                List.of(UNIT_ONE))),
                        List.of(shiftPlan(tomorrow)))))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.code()).isEqualTo("PLANNING_DATE_LOCKED"));
    verify(lifecycle, never()).prepareDocument(any(), any(), any(), any());
    verify(rentalOrders, never()).createRentalShipment(any(), any(), any(), any(), any(), any());
    verify(dependencies, never()).registerDriverShiftPlan(any(), any(), any());
  }

  @Test
  void missingNewOrderRejectsTheInputPlanBeforeAnyEffects() {
    LocalDate date = LocalDate.now(MOSCOW).plusDays(3);
    when(orders.findPlanningCandidateById(ORDER_ID)).thenReturn(Optional.empty());
    var command =
        request(
            List.of(
                new PlanningAssignmentRequest(
                    ORDER_ID, 7L, date, DRIVER_ID, "Driver", List.of(UNIT_ONE))),
            List.of(shiftPlan(date)));
    assertThatThrownBy(() -> service.apply(UUID.randomUUID(), command))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> {
              assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
              assertThat(error.code()).isEqualTo("ORDER_NOT_FOUND");
            });
    verify(dependencies, never()).registerDriverShiftPlan(any(), any(), any());
    verify(rentalOrders, never()).replayRentalShipment(any(), any(), any(), any());
    verify(rentalOrders, never()).createRentalShipment(any(), any(), any(), any(), any(), any());
    verify(lifecycle, never()).prepareDocument(any(), any(), any(), any());
  }

  @Test
  void anUnpaidPartInvalidatesTheWholeInputBeforeAnEarlierPaidPartCanProduceEffects() {
    UUID unpaidOrderId = UUID.randomUUID();
    RentalOrder unpaid = mock(RentalOrder.class);
    when(unpaid.getPaymentState()).thenReturn(RentalOrderPaymentState.PENDING);
    when(orders.findPlanningCandidateById(unpaidOrderId)).thenReturn(Optional.of(unpaid));
    LocalDate date = LocalDate.now(MOSCOW).plusDays(3);
    var command =
        request(
            List.of(
                new PlanningAssignmentRequest(
                    ORDER_ID, 7L, date, DRIVER_ID, "Driver", List.of(UNIT_ONE)),
                new PlanningAssignmentRequest(
                    unpaidOrderId, 0L, date, DRIVER_ID, "Driver", List.of(UNIT_TWO))),
            List.of(shiftPlan(date)));

    assertThatThrownBy(() -> service.apply(UUID.randomUUID(), command))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.code()).isEqualTo("ORDER_PAYMENT_REQUIRED"));
    verify(dependencies, never()).registerDriverShiftPlan(any(), any(), any());
    verify(rentalOrders, never()).replayRentalShipment(any(), any(), any(), any());
    verify(rentalOrders, never()).createRentalShipment(any(), any(), any(), any(), any(), any());
    verify(lifecycle, never()).prepareDocument(any(), any(), any(), any());
  }

  @Test
  void ownerEnrichedTransferOnlySnapshotRegistersWhileUnusedSnapshotsAreSkipped() {
    LocalDate date = LocalDate.now(MOSCOW).plusDays(3);
    UUID supportId = UUID.randomUUID();
    UUID transferId = UUID.randomUUID();
    PlanningDriverShiftPlanRequest base = crossWarehouseShiftPlan(date, WAREHOUSE_ID, supportId);
    PlanningDriverShiftPlanRequest unused = shiftPlan(date.plusDays(1), UUID.randomUUID());
    var vehicle = base.vehicle();
    OffsetDateTime departure = date.atTime(7, 0).atOffset(ZoneOffset.UTC);
    var operations =
        List.of(
            new PlanningDriverShiftRouteOperationRequest(
                1,
                PlanningDriverShiftRouteOperationKind.ORIGIN_START,
                WAREHOUSE_ID,
                null,
                "Origin",
                departure,
                departure,
                0,
                0),
            new PlanningDriverShiftRouteOperationRequest(
                2,
                PlanningDriverShiftRouteOperationKind.TRANSFER_LOAD,
                WAREHOUSE_ID,
                null,
                transferId,
                "Transfer",
                departure,
                departure,
                0,
                0),
            new PlanningDriverShiftRouteOperationRequest(
                3,
                PlanningDriverShiftRouteOperationKind.INBOUND_POSITIONING,
                REPRESENTATIVE_WAREHOUSE_ID,
                null,
                "Inbound",
                departure.plusHours(1),
                departure,
                0,
                0),
            new PlanningDriverShiftRouteOperationRequest(
                4,
                PlanningDriverShiftRouteOperationKind.TRANSFER_UNLOAD,
                REPRESENTATIVE_WAREHOUSE_ID,
                null,
                transferId,
                "Transfer",
                departure.plusHours(1),
                departure.plusHours(1),
                0,
                0),
            new PlanningDriverShiftRouteOperationRequest(
                5,
                PlanningDriverShiftRouteOperationKind.RETURN_POSITIONING,
                WAREHOUSE_ID,
                null,
                "Return",
                departure.plusHours(2),
                departure.plusHours(1),
                0,
                0));
    var transferPlan =
        new PlanningDriverShiftPlanRequest(
            base.sourceShiftId(),
            base.sourcePlanId(),
            base.sourcePlanVersion(),
            base.warehouseId(),
            base.routeOriginWarehouseId(),
            base.supportWarehouseLinkId(),
            base.driverId(),
            base.driverName(),
            date,
            new PlanningDriverShiftVehicleRequest(
                vehicle.id(),
                vehicle.name(),
                vehicle.registrationNumber(),
                vehicle.vehicleType(),
                vehicle.manufacturer(),
                vehicle.model(),
                vehicle.configurationType(),
                2,
                vehicle.startOdometer()),
            base.trailer(),
            base.tripCount(),
            base.routeDistanceMeters(),
            operations);
    var command = request(List.of(), List.of(base, unused));
    when(transferRouteCargoEnricher.enrich(command))
        .thenReturn(request(List.of(), List.of(transferPlan, unused)));
    when(dependencies.listWarehouseSupportNetwork(WAREHOUSE_ID))
        .thenReturn(List.of(supportLink(supportId, date, true, Set.of())));

    var result = service.apply(UUID.randomUUID(), command);

    assertThat(result.applied()).isEmpty();
    assertThat(result.rejected()).isEmpty();
    verify(dependencies).registerDriverShiftPlan(any(), eq(base.sourceShiftId()), any());
    verify(dependencies, never()).registerDriverShiftPlan(any(), eq(unused.sourceShiftId()), any());
    verify(rentalOrders, never()).hasRentalShipmentReceipt(any(), any(), any(), any());
    verify(rentalOrders, never()).replayRentalShipment(any(), any(), any(), any());
  }

  @Test
  void sharedEtaUsesItsOwnTimezoneRevisionOnceAlongsideTheBatchLocalDate() {
    LocalDate date = LocalDate.now(MOSCOW).plusDays(3);
    OffsetDateTime eta = date.atTime(2, 0).atOffset(ZoneOffset.ofHours(9));
    when(dependencies.warehouseTimeZoneAt(WAREHOUSE_ID, eta))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseTimeZone(
                WAREHOUSE_ID, "Asia/Tokyo", eta.minusDays(1)));
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(date, date)));
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(reservation(UNIT_ONE)));
    when(lifecycle.prepareDocument(any(), any(), any(), any())).thenReturn(mockAdmission());
    when(rentalOrders.createRentalShipment(any(), any(), any(), any(), any(), any()))
        .thenReturn(documentResult(UUID.randomUUID()));
    var assignment =
        new PlanningAssignmentRequest(
            ORDER_ID,
            null,
            null,
            7L,
            date,
            PlanningAssignmentType.ROUTE_PLAN,
            PlanningDriverAudienceMode.WAREHOUSE_DRIVERS,
            null,
            "Shared",
            List.of(UNIT_ONE),
            eta);

    assertThat(service.apply(UUID.randomUUID(), request(List.of(assignment))).applied()).hasSize(1);

    verify(dependencies, times(2)).warehouseTimeZoneAt(eq(WAREHOUSE_ID), any());
    verify(dependencies, times(1)).warehouseTimeZoneAt(WAREHOUSE_ID, eta);
  }

  @Test
  void explicitContractorHandoffPublishesTomorrowWithoutInternalShiftPlan() {
    LocalDate tomorrow = LocalDate.now(MOSCOW).plusDays(1);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(tomorrow, tomorrow)));
    var unitReservation = reservation(UNIT_ONE);
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(unitReservation));
    when(lifecycle.prepareDocument(any(), any(), any(), any())).thenReturn(mockAdmission());
    when(rentalOrders.createRentalShipment(any(), any(), any(), any(), any(), any()))
        .thenReturn(documentResult(UUID.randomUUID()));

    var response =
        service.apply(
            UUID.randomUUID(),
            request(
                List.of(
                    new PlanningAssignmentRequest(
                        ORDER_ID,
                        null,
                        7L,
                        tomorrow,
                        PlanningAssignmentType.CONTRACTOR_HANDOFF,
                        PlanningDriverAudienceMode.ASSIGNED_DRIVER,
                        DRIVER_ID,
                        "Наёмный водитель",
                        List.of(UNIT_ONE)))));

    assertThat(response.rejected()).isEmpty();
    assertThat(response.applied()).hasSize(1);
    ArgumentCaptor<CreateOrderRentalShipmentRequest> shipment =
        ArgumentCaptor.forClass(CreateOrderRentalShipmentRequest.class);
    verify(rentalOrders)
        .createRentalShipment(any(), eq(ORDER_ID), any(), any(), shipment.capture(), any());
    assertThat(shipment.getValue().warehouseDriverPool()).isFalse();
    assertThat(shipment.getValue().driverWorkerId()).isEqualTo(DRIVER_ID);
    verify(dependencies, never()).registerDriverShiftPlan(any(), any(), any());
  }

  @Test
  void contractorHandoffRejectsWarehouseDriverPool() {
    LocalDate future = LocalDate.now(MOSCOW).plusDays(2);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(future, future)));

    assertThatThrownBy(
            () ->
                service.apply(
                    UUID.randomUUID(),
                    request(
                        List.of(
                            new PlanningAssignmentRequest(
                                ORDER_ID,
                                null,
                                7L,
                                future,
                                PlanningAssignmentType.CONTRACTOR_HANDOFF,
                                PlanningDriverAudienceMode.WAREHOUSE_DRIVERS,
                                null,
                                "Наёмный водитель",
                                List.of(UNIT_ONE))))))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.code()).isEqualTo("CONTRACTOR_HANDOFF_REQUIRES_DRIVER"));
    verify(rentalOrders, never()).createRentalShipment(any(), any(), any(), any(), any(), any());
  }

  @Test
  void explicitSharedAssignmentPublishesTomorrowWithoutInventingDriverIdentity() {
    LocalDate tomorrow = LocalDate.now(MOSCOW).plusDays(1);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(tomorrow, tomorrow)));
    var unitReservation = reservation(UNIT_ONE);
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(unitReservation));
    when(lifecycle.prepareDocument(any(), any(), any(), any())).thenReturn(mockAdmission());
    when(rentalOrders.createRentalShipment(any(), any(), any(), any(), any(), any()))
        .thenReturn(documentResult(UUID.randomUUID()));

    var response =
        service.apply(
            UUID.randomUUID(),
            request(
                List.of(
                    new PlanningAssignmentRequest(
                        ORDER_ID,
                        7L,
                        tomorrow,
                        PlanningDriverAudienceMode.WAREHOUSE_DRIVERS,
                        null,
                        "Свободное задание",
                        List.of(UNIT_ONE)))));

    assertThat(response.rejected()).isEmpty();
    assertThat(response.applied()).hasSize(1);
    ArgumentCaptor<CreateOrderRentalShipmentRequest> shipment =
        ArgumentCaptor.forClass(CreateOrderRentalShipmentRequest.class);
    verify(rentalOrders)
        .createRentalShipment(any(), eq(ORDER_ID), any(), any(), shipment.capture(), any());
    assertThat(shipment.getValue().warehouseDriverPool()).isTrue();
    assertThat(shipment.getValue().driverWorkerId()).isNull();
    assertThat(shipment.getValue().inventorySourceWarehouseId()).isNull();
  }

  @Test
  void explicitInventorySourceIsForwardedToTheCanonicalShipmentOwnerAndAdmission() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(3);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(scheduled, scheduled)));
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(reservation(UNIT_ONE)));
    when(lifecycle.prepareDocument(any(), any(), any(), any())).thenReturn(mockAdmission());
    when(rentalOrders.createRentalShipment(any(), any(), any(), any(), any(), any()))
        .thenReturn(documentResult(UUID.randomUUID()));

    var response =
        service.apply(
            UUID.randomUUID(),
            request(
                List.of(
                    new PlanningAssignmentRequest(
                        ORDER_ID,
                        null,
                        WAREHOUSE_ID,
                        7L,
                        scheduled,
                        PlanningAssignmentType.ROUTE_PLAN,
                        PlanningDriverAudienceMode.ASSIGNED_DRIVER,
                        DRIVER_ID,
                        "Водитель 1",
                        List.of(UNIT_ONE)))));

    assertThat(response.rejected()).isEmpty();
    ArgumentCaptor<CreateOrderRentalShipmentRequest> shipment =
        ArgumentCaptor.forClass(CreateOrderRentalShipmentRequest.class);
    verify(rentalOrders)
        .createRentalShipment(any(), eq(ORDER_ID), any(), any(), shipment.capture(), any());
    assertThat(shipment.getValue().inventorySourceWarehouseId()).isEqualTo(WAREHOUSE_ID);
    verify(rentalOrders)
        .rentalShipmentAdmissionWarehouse(
            any(), eq(ORDER_ID), eq(scheduled), eq(WAREHOUSE_ID));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AdmissionRequirement>> requirements =
        ArgumentCaptor.forClass(List.class);
    verify(lifecycle)
        .prepareDocument(any(), eq("CREATE_RENTAL_ORDER_SHIPMENT"), any(), requirements.capture());
    assertThat(requirements.getValue())
        .singleElement()
        .satisfies(value -> assertThat(value.warehouseId()).isEqualTo(WAREHOUSE_ID));
  }

  @Test
  void assignmentRejectsASelectedCabinThatDoesNotBelongToTheRequestedSource() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(3);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(scheduled, scheduled)));
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(reservation(UNIT_ONE, WAREHOUSE_ID)));

    var response =
        service.apply(
            UUID.randomUUID(),
            request(
                List.of(
                    new PlanningAssignmentRequest(
                        ORDER_ID,
                        null,
                        REPRESENTATIVE_WAREHOUSE_ID,
                        7L,
                        scheduled,
                        PlanningAssignmentType.ROUTE_PLAN,
                        PlanningDriverAudienceMode.ASSIGNED_DRIVER,
                        DRIVER_ID,
                        "Водитель 1",
                        List.of(UNIT_ONE)))));

    assertThat(response.applied()).isEmpty();
    assertThat(response.rejected())
        .singleElement()
        .satisfies(
            rejected ->
                assertThat(rejected.code()).isEqualTo("INVENTORY_SOURCE_WAREHOUSE_MISMATCH"));
    verify(rentalOrders, never())
        .rentalShipmentAdmissionWarehouse(any(), any(), any(), any());
    verify(lifecycle, never()).prepareDocument(any(), any(), any(), any());
  }

  @Test
  void sharedAssignmentRejectsTodayBeforeCreatingShipment() {
    LocalDate today = LocalDate.now(MOSCOW);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(today, today)));

    assertThatThrownBy(
            () ->
                service.apply(
                    UUID.randomUUID(),
                    request(
                        List.of(
                            new PlanningAssignmentRequest(
                                ORDER_ID,
                                7L,
                                today,
                                PlanningDriverAudienceMode.WAREHOUSE_DRIVERS,
                                null,
                                "Свободное задание",
                                List.of(UNIT_ONE))))))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.code()).isEqualTo("SHARED_TASK_REQUIRES_FUTURE_DATE"));
    verify(rentalOrders, never()).createRentalShipment(any(), any(), any(), any(), any(), any());
  }

  @ParameterizedTest
  @NullSource
  @EnumSource(RentalOrderPaymentState.class)
  void retryReturnsPriorShipmentBeforePaymentDateVersionAndAssignmentChecks(
      RentalOrderPaymentState paymentState) {
    when(order.getPaymentState()).thenReturn(paymentState);
    LocalDate formerlyEligibleDate = LocalDate.now(MOSCOW).plusDays(1);
    var priorResult = documentResult(UUID.randomUUID());
    when(rentalOrders.hasRentalShipmentReceipt(any(), eq(ORDER_ID), any(), any())).thenReturn(true);
    when(rentalOrders.replayRentalShipment(any(), eq(ORDER_ID), any(), any()))
        .thenReturn(new LogisticsDocumentService.CreateResult(priorResult.response(), true));

    var response =
        service.apply(
            UUID.randomUUID(),
            request(
                List.of(
                    new PlanningAssignmentRequest(
                        ORDER_ID,
                        3L,
                        formerlyEligibleDate,
                        DRIVER_ID,
                        "Водитель 1",
                        List.of(UNIT_ONE))),
                List.of(shiftPlan(formerlyEligibleDate))));

    assertThat(response.rejected()).isEmpty();
    assertThat(response.applied()).singleElement().satisfies(applied -> {
      assertThat(applied.documentId()).isEqualTo(priorResult.response().id());
      assertThat(applied.replayed()).isTrue();
      assertThat(applied.orderVersion()).isEqualTo(7L);
    });
    verify(orders).findPlanningCandidateById(ORDER_ID);
    verify(lifecycle, never()).prepareDocument(any(), any(), any(), any());
    verify(dependencies).registerDriverShiftPlan(any(), any(), any());
  }

  @Test
  void registersShiftPlanBeforeCreatingShipmentAndPreservesTheExactSnapshot() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(3);
    LogisticsDependencyGateway.OrderUnitReservation unitReservation = reservation(UNIT_ONE);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(scheduled, scheduled)));
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(unitReservation));
    when(lifecycle.prepareDocument(any(), any(), any(), any())).thenReturn(mockAdmission());
    when(rentalOrders.createRentalShipment(any(), any(), any(), any(), any(), any()))
        .thenReturn(documentResult(UUID.randomUUID()));
    PlanningDriverShiftPlanRequest shiftPlan = shiftPlanWithOperations(scheduled);

    var response =
        service.apply(
            UUID.randomUUID(),
            request(
                List.of(
                    new PlanningAssignmentRequest(
                        ORDER_ID, 7L, scheduled, DRIVER_ID, "Водитель 1", List.of(UNIT_ONE))),
                List.of(shiftPlan)));

    assertThat(response.applied()).hasSize(1);
    ArgumentCaptor<UUID> registrationKey = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<UUID> registeredSourceShiftId = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<LogisticsDependencyGateway.DriverShiftPlanSnapshot> snapshot =
        ArgumentCaptor.forClass(LogisticsDependencyGateway.DriverShiftPlanSnapshot.class);
    InOrder sequence = inOrder(dependencies, rentalOrders);
    sequence
        .verify(dependencies)
        .registerDriverShiftPlan(
            registrationKey.capture(), registeredSourceShiftId.capture(), snapshot.capture());
    sequence.verify(rentalOrders).replayRentalShipment(any(), eq(ORDER_ID), any(), any());
    sequence
        .verify(rentalOrders)
        .createRentalShipment(any(), eq(ORDER_ID), any(), any(), any(), any());
    assertThat(registrationKey.getValue()).isNotNull();
    assertThat(registeredSourceShiftId.getValue()).isEqualTo(shiftPlan.sourceShiftId());
    assertThat(snapshot.getValue())
        .extracting(
            LogisticsDependencyGateway.DriverShiftPlanSnapshot::sourcePlanId,
            LogisticsDependencyGateway.DriverShiftPlanSnapshot::sourcePlanVersion,
            LogisticsDependencyGateway.DriverShiftPlanSnapshot::warehouseId,
            LogisticsDependencyGateway.DriverShiftPlanSnapshot::driverId,
            LogisticsDependencyGateway.DriverShiftPlanSnapshot::workDate,
            LogisticsDependencyGateway.DriverShiftPlanSnapshot::tripCount,
            LogisticsDependencyGateway.DriverShiftPlanSnapshot::routeDistanceMeters)
        .containsExactly(
            shiftPlan.sourcePlanId(),
            1L,
            WAREHOUSE_ID,
            DRIVER_ID,
            scheduled,
            3,
            247_500L);
    assertThat(snapshot.getValue().vehicle().configurationType())
        .isEqualTo("TRUCK_WITH_TRAILER");
    assertThat(snapshot.getValue().trailer().registrationNumber()).isEqualTo("В456ВВ78");
    assertThat(snapshot.getValue().operations())
        .extracting(LogisticsDependencyGateway.DriverShiftRouteOperation::kind)
        .containsExactly("DEPOT_LOAD", "DELIVERY", "DEPOT_RETURN");
    assertThat(snapshot.getValue().operations().get(1).plannedArrival())
        .isEqualTo(scheduled.atTime(9, 0).atOffset(ZoneOffset.UTC));
  }

  @Test
  void rootShiftCanApplyARepresentativeOrderThroughAnEligibleSupportEdge() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(3);
    UUID supportLinkId = UUID.randomUUID();
    when(order.getWarehouseId()).thenReturn(REPRESENTATIVE_WAREHOUSE_ID);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(scheduled, scheduled)));
    var unitReservation = reservation(UNIT_ONE, REPRESENTATIVE_WAREHOUSE_ID);
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(unitReservation));
    when(dependencies.warehouseTimeZoneAt(eq(REPRESENTATIVE_WAREHOUSE_ID), any()))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseTimeZone(
                REPRESENTATIVE_WAREHOUSE_ID,
                MOSCOW.getId(),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1)));
    when(dependencies.listWarehouseSupportNetwork(WAREHOUSE_ID))
        .thenReturn(List.of(supportLink(supportLinkId, scheduled, true, Set.of())));
    when(lifecycle.prepareDocument(any(), any(), any(), any())).thenReturn(mockAdmission());
    when(rentalOrders.createRentalShipment(any(), any(), any(), any(), any(), any()))
        .thenReturn(documentResult(UUID.randomUUID()));

    var response =
        service.apply(
            UUID.randomUUID(),
            request(
                List.of(
                    new PlanningAssignmentRequest(
                        ORDER_ID,
                        REPRESENTATIVE_WAREHOUSE_ID,
                        7L,
                        scheduled,
                        DRIVER_ID,
                        "Водитель 1",
                        List.of(UNIT_ONE))),
                List.of(crossWarehouseShiftPlan(scheduled, WAREHOUSE_ID, supportLinkId))));

    assertThat(response.rejected()).isEmpty();
    assertThat(response.applied()).hasSize(1);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AdmissionRequirement>> requirements =
        ArgumentCaptor.forClass(List.class);
    verify(lifecycle)
        .prepareDocument(any(), eq("CREATE_RENTAL_ORDER_SHIPMENT"), any(), requirements.capture());
    assertThat(requirements.getValue())
        .singleElement()
        .satisfies(
            admission ->
                assertThat(admission.warehouseId()).isEqualTo(REPRESENTATIVE_WAREHOUSE_ID));
    ArgumentCaptor<LogisticsDependencyGateway.DriverShiftPlanSnapshot> snapshot =
        ArgumentCaptor.forClass(LogisticsDependencyGateway.DriverShiftPlanSnapshot.class);
    verify(dependencies).registerDriverShiftPlan(any(), any(), snapshot.capture());
    assertThat(snapshot.getValue().warehouseId()).isEqualTo(WAREHOUSE_ID);
    assertThat(snapshot.getValue().operations())
        .extracting(LogisticsDependencyGateway.DriverShiftRouteOperation::kind)
        .containsExactly(
            "ORIGIN_START",
            "INBOUND_POSITIONING",
            "DEPOT_LOAD",
            "DELIVERY",
            "DEPOT_RETURN",
            "RETURN_POSITIONING");
    assertThat(snapshot.getValue().operations().get(1).warehouseId())
        .isEqualTo(REPRESENTATIVE_WAREHOUSE_ID);
    assertThat(snapshot.getValue().operations().getLast().warehouseId())
        .isEqualTo(WAREHOUSE_ID);
  }

  @Test
  void crossWarehouseShiftRejectsAPlannerSuppliedLinkThatIsNotTheExactActiveEdge() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(3);
    UUID actualLinkId = UUID.randomUUID();
    when(dependencies.listWarehouseSupportNetwork(WAREHOUSE_ID))
        .thenReturn(List.of(supportLink(actualLinkId, scheduled, true, Set.of())));
    PlanningAssignmentRequest assignment =
        new PlanningAssignmentRequest(
            ORDER_ID,
            REPRESENTATIVE_WAREHOUSE_ID,
            7L,
            scheduled,
            DRIVER_ID,
            "Водитель 1",
            List.of(UNIT_ONE));

    assertThatThrownBy(
            () ->
                service.apply(
                    UUID.randomUUID(),
                    request(
                        List.of(assignment),
                        List.of(
                            crossWarehouseShiftPlan(
                                scheduled, WAREHOUSE_ID, UUID.randomUUID())))))
        .isInstanceOf(OrderProblemException.class)
        .hasMessageContaining("не разрешает этот рейс");
    verify(rentalOrders, never()).replayRentalShipment(any(), any(), any(), any());
    verify(dependencies, never()).registerDriverShiftPlan(any(), any(), any());
  }

  @Test
  void crossWarehouseShiftRejectsAnEmptyLegacyRouteSnapshotBeforeSideEffects() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(3);
    PlanningAssignmentRequest assignment =
        new PlanningAssignmentRequest(
            ORDER_ID,
            REPRESENTATIVE_WAREHOUSE_ID,
            7L,
            scheduled,
            DRIVER_ID,
            "Водитель 1",
            List.of(UNIT_ONE));
    PlanningDriverShiftPlanRequest local = shiftPlan(scheduled);
    PlanningDriverShiftPlanRequest missingRoute =
        new PlanningDriverShiftPlanRequest(
            local.sourceShiftId(),
            local.sourcePlanId(),
            local.sourcePlanVersion(),
            local.warehouseId(),
            WAREHOUSE_ID,
            UUID.randomUUID(),
            local.driverId(),
            local.driverName(),
            local.workDate(),
            local.vehicle(),
            local.trailer(),
            local.tripCount(),
            local.routeDistanceMeters());

    assertThatThrownBy(
            () ->
                service.apply(
                    UUID.randomUUID(),
                    request(List.of(assignment), List.of(missingRoute))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("origin, inbound, and return positioning");
    verify(rentalOrders, never()).replayRentalShipment(any(), any(), any(), any());
    verify(dependencies, never()).registerDriverShiftPlan(any(), any(), any());
  }

  @Test
  void crossWarehouseShiftRejectsAnExcludedSupportCalendarDate() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(3);
    UUID supportLinkId = UUID.randomUUID();
    when(dependencies.listWarehouseSupportNetwork(WAREHOUSE_ID))
        .thenReturn(
            List.of(supportLink(supportLinkId, scheduled, true, Set.of(scheduled))));
    PlanningAssignmentRequest assignment =
        new PlanningAssignmentRequest(
            ORDER_ID,
            REPRESENTATIVE_WAREHOUSE_ID,
            7L,
            scheduled,
            DRIVER_ID,
            "Водитель 1",
            List.of(UNIT_ONE));

    assertThatThrownBy(
            () ->
                service.apply(
                    UUID.randomUUID(),
                    request(
                        List.of(assignment),
                        List.of(
                            crossWarehouseShiftPlan(
                                scheduled, WAREHOUSE_ID, supportLinkId)))))
        .isInstanceOf(OrderProblemException.class)
        .hasMessageContaining("не разрешает этот рейс");
    verify(rentalOrders, never()).replayRentalShipment(any(), any(), any(), any());
    verify(dependencies, never()).registerDriverShiftPlan(any(), any(), any());
  }

  @Test
  void representativeOrderIsRejectedWhenTheRootHasNoEligibleDriverSupportEdge() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(3);
    when(order.getWarehouseId()).thenReturn(REPRESENTATIVE_WAREHOUSE_ID);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(scheduled, scheduled)));
    var unitReservation = reservation(UNIT_ONE);
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(unitReservation));
    when(dependencies.warehouseTimeZoneAt(eq(REPRESENTATIVE_WAREHOUSE_ID), any()))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseTimeZone(
                REPRESENTATIVE_WAREHOUSE_ID,
                MOSCOW.getId(),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1)));
    when(dependencies.listWarehouseSupportNetwork(WAREHOUSE_ID)).thenReturn(List.of());

    var response =
        service.apply(
            UUID.randomUUID(),
            request(
                List.of(
                    new PlanningAssignmentRequest(
                        ORDER_ID,
                        REPRESENTATIVE_WAREHOUSE_ID,
                        7L,
                        scheduled,
                        DRIVER_ID,
                        "Водитель 1",
                        List.of(UNIT_ONE)))));

    assertThat(response.applied()).isEmpty();
    assertThat(response.rejected())
        .singleElement()
        .satisfies(
            rejected ->
                assertThat(rejected.code()).isEqualTo("SUPPORT_WAREHOUSE_NOT_AUTHORIZED"));
    verify(lifecycle, never()).prepareDocument(any(), any(), any(), any());
    verify(rentalOrders, never()).createRentalShipment(any(), any(), any(), any(), any(), any());
  }

  @Test
  void exactShiftPlanReplayUsesTheSameRegistrationKeyAcrossBatchRetries() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(3);
    var priorResult = documentResult(UUID.randomUUID());
    when(rentalOrders.hasRentalShipmentReceipt(any(), eq(ORDER_ID), any(), any())).thenReturn(true);
    when(rentalOrders.replayRentalShipment(any(), eq(ORDER_ID), any(), any()))
        .thenReturn(new LogisticsDocumentService.CreateResult(priorResult.response(), true));
    ApplyPlanningAssignmentsRequest command =
        request(
            List.of(
                new PlanningAssignmentRequest(
                    ORDER_ID, 7L, scheduled, DRIVER_ID, "Водитель 1", List.of(UNIT_ONE))),
            List.of(shiftPlan(scheduled)));

    service.apply(UUID.randomUUID(), command);
    service.apply(UUID.randomUUID(), command);

    ArgumentCaptor<UUID> keys = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(2)).registerDriverShiftPlan(keys.capture(), any(), any());
    assertThat(keys.getAllValues()).hasSize(2).doesNotContainNull();
    assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
  }

  @Test
  void taskBoardShiftPlanFailurePreventsAnyShipmentCreateOrReplay() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(3);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(scheduled, scheduled)));
    when(reads.readUnitsForShipment(order)).thenReturn(List.of(reservation(UNIT_ONE)));
    when(lifecycle.prepareDocument(any(), any(), any(), any())).thenReturn(mockAdmission());
    when(rentalOrders.createRentalShipment(any(), any(), any(), any(), any(), any()))
        .thenReturn(documentResult(UUID.randomUUID()));
    doThrow(new IllegalStateException("task-board unavailable"))
        .when(dependencies)
        .registerDriverShiftPlan(any(), any(), any());

    assertThatThrownBy(
            () ->
                service.apply(
                    UUID.randomUUID(),
                    request(
                        List.of(
                            new PlanningAssignmentRequest(
                                ORDER_ID,
                                7L,
                                scheduled,
                                DRIVER_ID,
                                "Водитель 1",
                                List.of(UNIT_ONE))),
                        List.of(shiftPlan(scheduled)))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("task-board unavailable");
    verify(rentalOrders, never()).createRentalShipment(any(), any(), any(), any(), any(), any());
    verify(rentalOrders, never()).replayRentalShipment(any(), any(), any(), any());
    verify(lifecycle, never()).prepareDocument(any(), any(), any(), any());
  }

  @Test
  void laterRegistrationFailurePreventsEvenReplayAndAdmissionOfTheEarlierPart() {
    LocalDate date = LocalDate.now(MOSCOW).plusDays(3);
    var firstShift = shiftPlan(date);
    var secondShift = shiftPlan(date.plusDays(1), UUID.randomUUID());
    doThrow(new IllegalStateException("second registration failed"))
        .when(dependencies)
        .registerDriverShiftPlan(any(), eq(secondShift.sourceShiftId()), any());
    var command =
        request(
            List.of(
                new PlanningAssignmentRequest(
                    ORDER_ID, 7L, date, DRIVER_ID, "Driver", List.of(UNIT_ONE)),
                new PlanningAssignmentRequest(
                    ORDER_ID, 7L, date.plusDays(1), DRIVER_ID, "Driver", List.of(UNIT_TWO))),
            List.of(firstShift, secondShift));

    assertThatThrownBy(() -> service.apply(UUID.randomUUID(), command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("second registration failed");

    InOrder sequence = inOrder(dependencies);
    sequence
        .verify(dependencies)
        .registerDriverShiftPlan(any(), eq(firstShift.sourceShiftId()), any());
    sequence
        .verify(dependencies)
        .registerDriverShiftPlan(any(), eq(secondShift.sourceShiftId()), any());
    verify(rentalOrders, never()).replayRentalShipment(any(), any(), any(), any());
    verify(rentalOrders, never()).createRentalShipment(any(), any(), any(), any(), any(), any());
    verify(lifecycle, never()).prepareDocument(any(), any(), any(), any());
  }

  @Test
  void paidVersionConflictRetainsSuccessfulPartAfterShiftRegistration() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(3);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(scheduled, scheduled)));
    when(reads.readUnitsForShipment(order))
        .thenReturn(List.of(reservation(UNIT_ONE), reservation(UNIT_TWO)));
    when(lifecycle.prepareDocument(any(), any(), any(), any())).thenReturn(mockAdmission());
    when(rentalOrders.createRentalShipment(any(), any(), any(), any(), any(), any()))
        .thenReturn(documentResult(UUID.randomUUID()));

    var response =
        service.apply(
            UUID.randomUUID(),
            request(
                List.of(
                    new PlanningAssignmentRequest(
                        ORDER_ID,
                        7L,
                        scheduled,
                        DRIVER_ID,
                        "Водитель 1",
                        List.of(UNIT_ONE)),
                    new PlanningAssignmentRequest(
                        ORDER_ID,
                        6L,
                        scheduled,
                        DRIVER_ID,
                        "Водитель 1",
                        List.of(UNIT_TWO))),
                List.of(shiftPlan(scheduled))));

    assertThat(response.applied()).hasSize(1);
    assertThat(response.rejected())
        .singleElement()
        .satisfies(rejected -> assertThat(rejected.code()).isEqualTo("ORDER_VERSION_CONFLICT"));
    InOrder sequence = inOrder(dependencies, rentalOrders);
    sequence.verify(dependencies).registerDriverShiftPlan(any(), any(), any());
    sequence
        .verify(rentalOrders)
        .createRentalShipment(any(), eq(ORDER_ID), any(), any(), any(), any());
  }

  @Test
  void rejectsTwoSourceShiftsForTheSameDriverWorkdayBeforePublication() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(3);
    PlanningDriverShiftPlanRequest first = shiftPlan(scheduled);
    PlanningDriverShiftPlanRequest second = shiftPlan(scheduled, UUID.randomUUID());

    assertThatThrownBy(
            () ->
                service.apply(
                    UUID.randomUUID(), request(List.of(), List.of(first, second))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one shift plan");
    verify(dependencies, never()).registerDriverShiftPlan(any(), any(), any());
  }

  @Test
  void rejectsDuplicateSourceShiftIdentityBeforePublication() {
    PlanningDriverShiftPlanRequest shiftPlan =
        shiftPlan(LocalDate.now(MOSCOW).plusDays(3));

    assertThatThrownBy(
            () ->
                service.apply(
                    UUID.randomUUID(), request(List.of(), List.of(shiftPlan, shiftPlan))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source driver shift");
    verify(dependencies, never()).registerDriverShiftPlan(any(), any(), any());
  }

  @Test
  void oneOrderCanBeAppliedAsSeveralNonOverlappingCabinPartsWithStableKeys() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(3);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(scheduled, scheduled)));
    var firstReservation = reservation(UNIT_ONE);
    var secondReservation = reservation(UNIT_TWO);
    var thirdReservation = reservation(UNIT_THREE);
    when(reads.readUnitsForShipment(order))
        .thenReturn(List.of(firstReservation, secondReservation, thirdReservation));
    var admission = mockAdmission();
    when(lifecycle.prepareDocument(any(), any(), any(), any())).thenReturn(admission);
    when(rentalOrders.createRentalShipment(any(), any(), any(), any(), any(), any()))
        .thenReturn(documentResult(UUID.randomUUID()), documentResult(UUID.randomUUID()));
    UUID batchKey = UUID.randomUUID();

    var response =
        service.apply(
            batchKey,
            request(
                List.of(
                    new PlanningAssignmentRequest(
                        ORDER_ID,
                        7L,
                        scheduled,
                        DRIVER_ID,
                        "Водитель 1",
                        List.of(UNIT_TWO, UNIT_ONE)),
                    new PlanningAssignmentRequest(
                        ORDER_ID,
                        7L,
                        scheduled,
                        DRIVER_ID,
                        "Водитель 1",
                        List.of(UNIT_THREE)))));

    assertThat(response.applied()).hasSize(2);
    verify(dependencies, times(1)).warehouseTimeZoneAt(eq(WAREHOUSE_ID), any());
    assertThat(response.rejected()).isEmpty();
    ArgumentCaptor<UUID> idempotencyKeys = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<CreateOrderRentalShipmentRequest> shipments =
        ArgumentCaptor.forClass(CreateOrderRentalShipmentRequest.class);
    verify(rentalOrders, times(2))
        .createRentalShipment(
            any(), eq(ORDER_ID), idempotencyKeys.capture(), any(), shipments.capture(), any());
    assertThat(idempotencyKeys.getAllValues()).doesNotHaveDuplicates();
    assertThat(shipments.getAllValues()).extracting(CreateOrderRentalShipmentRequest::unitIds)
        .containsExactly(List.of(UNIT_TWO, UNIT_ONE), List.of(UNIT_THREE));
  }

  @Test
  void assignmentStatusShowsTheQualifiedDriverWhoClaimedAnExplicitSharedPart() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(1);
    UUID documentId = UUID.randomUUID();
    LogisticsDocument document = mock(LogisticsDocument.class);
    LogisticsDocumentLine line = mock(LogisticsDocumentLine.class);
    DriverLogisticsTask task = mock(DriverLogisticsTask.class);
    when(document.getId()).thenReturn(documentId);
    when(document.getRentalOrderId()).thenReturn(ORDER_ID);
    when(document.getScheduledDate()).thenReturn(scheduled);
    when(line.getDocument()).thenReturn(document);
    when(line.getAssetId()).thenReturn(UNIT_ONE);
    when(task.getSourceId()).thenReturn(documentId);
    when(task.getKind()).thenReturn(DriverTaskKind.SHIPMENT);
    when(task.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(task.getScheduledDate()).thenReturn(scheduled);
    when(task.getDriverAudienceMode()).thenReturn(DriverTaskAudienceMode.ASSIGNED_DRIVER);
    when(task.getPlannedDriverWorkerId()).thenReturn(DRIVER_ID);
    when(task.getPlannedDriverNameSnapshot()).thenReturn("Водитель 1");
    when(task.getState()).thenReturn(DriverTaskState.SCHEDULED);
    UUID externalTaskId = UUID.randomUUID();
    when(task.getExternalTaskId()).thenReturn(externalTaskId);
    when(task.getVersion()).thenReturn(6L);
    when(documents
            .findAllByDocumentTypeAndWarehouseIdAndScheduledDateAndRequestedBySubjectIdOrderByCreatedAtAscIdAsc(
                eq(LogisticsDocumentType.SHIPMENT), eq(WAREHOUSE_ID), eq(scheduled), any()))
        .thenReturn(List.of(document));
    when(lines.findAllByDocumentIdIn(List.of(documentId))).thenReturn(List.of(line));
    when(driverTasks.findAllBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, List.of(documentId)))
        .thenReturn(List.of(task));
    when(orders.findAllWithClientByIdIn(List.of(ORDER_ID))).thenReturn(List.of(order));

    var response = service.assignmentStatuses(WAREHOUSE_ID, scheduled);

    assertThat(response.assignments()).singleElement().satisfies(status -> {
      assertThat(status.orderId()).isEqualTo(ORDER_ID);
      assertThat(status.orderVersion()).isEqualTo(7L);
      assertThat(status.documentId()).isEqualTo(documentId);
      assertThat(status.externalTaskId()).isEqualTo(externalTaskId);
      assertThat(status.taskVersion()).isEqualTo(6L);
      assertThat(status.unitIds()).containsExactly(UNIT_ONE);
      assertThat(status.driverAudienceMode()).isEqualTo(PlanningDriverAudienceMode.ASSIGNED_DRIVER);
      assertThat(status.driverWorkerId()).isEqualTo(DRIVER_ID);
      assertThat(status.driverName()).isEqualTo("Водитель 1");
      assertThat(status.taskState()).isEqualTo("SCHEDULED");
    });
  }

  @Test
  void assignmentStatusFailsClosedWhenOneDocumentHasAmbiguousTaskProjections() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(1);
    UUID documentId = UUID.randomUUID();
    LogisticsDocument document = mock(LogisticsDocument.class);
    LogisticsDocumentLine line = mock(LogisticsDocumentLine.class);
    when(document.getId()).thenReturn(documentId);
    when(document.getRentalOrderId()).thenReturn(ORDER_ID);
    when(line.getDocument()).thenReturn(document);
    when(documents
            .findAllByDocumentTypeAndWarehouseIdAndScheduledDateAndRequestedBySubjectIdOrderByCreatedAtAscIdAsc(
                eq(LogisticsDocumentType.SHIPMENT), eq(WAREHOUSE_ID), eq(scheduled), any()))
        .thenReturn(List.of(document));
    when(lines.findAllByDocumentIdIn(List.of(documentId))).thenReturn(List.of(line));
    when(driverTasks.findAllBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, List.of(documentId)))
        .thenReturn(List.of(mock(DriverLogisticsTask.class), mock(DriverLogisticsTask.class)));

    assertThatThrownBy(() -> service.assignmentStatuses(WAREHOUSE_ID, scheduled))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("ambiguous");
  }

  private static ApplyPlanningAssignmentsRequest request(
      List<PlanningAssignmentRequest> assignments) {
    return request(assignments, List.of());
  }

  private static ApplyPlanningAssignmentsRequest request(
      List<PlanningAssignmentRequest> assignments,
      List<PlanningDriverShiftPlanRequest> driverShiftPlans) {
    return new ApplyPlanningAssignmentsRequest(
        WAREHOUSE_ID,
        UUID.fromString("10000000-0000-0000-0000-000000000010"),
        1L,
        assignments,
        driverShiftPlans);
  }

  private static PlanningDriverShiftPlanRequest shiftPlan(LocalDate workDate) {
    return shiftPlan(
        workDate, UUID.fromString("10000000-0000-0000-0000-000000000011"));
  }

  private static PlanningDriverShiftPlanRequest shiftPlan(
      LocalDate workDate, UUID sourceShiftId) {
    return new PlanningDriverShiftPlanRequest(
        sourceShiftId,
        UUID.fromString("10000000-0000-0000-0000-000000000010"),
        1L,
        WAREHOUSE_ID,
        DRIVER_ID,
        "Водитель 1",
        workDate,
        new PlanningDriverShiftVehicleRequest(
            UUID.fromString("10000000-0000-0000-0000-000000000012"),
            "MAN TGS",
            "А123АА78",
            "FLATBED_CRANE",
            "MAN",
            "TGS",
            PlanningDriverShiftVehicleConfiguration.TRUCK_WITH_TRAILER,
            null),
        new PlanningDriverShiftTrailerRequest(
            UUID.fromString("10000000-0000-0000-0000-000000000013"),
            "Schmitz",
            "В456ВВ78"),
        3,
        247_500L);
  }

  private static PlanningDriverShiftPlanRequest shiftPlanWithOperations(LocalDate workDate) {
    PlanningDriverShiftPlanRequest local = shiftPlan(workDate);
    OffsetDateTime routeStart = workDate.atTime(8, 30).atOffset(ZoneOffset.UTC);
    return new PlanningDriverShiftPlanRequest(
        local.sourceShiftId(),
        local.sourcePlanId(),
        local.sourcePlanVersion(),
        local.warehouseId(),
        local.routeOriginWarehouseId(),
        local.supportWarehouseLinkId(),
        local.driverId(),
        local.driverName(),
        local.workDate(),
        local.vehicle(),
        local.trailer(),
        local.tripCount(),
        local.routeDistanceMeters(),
        List.of(
            new PlanningDriverShiftRouteOperationRequest(
                1,
                PlanningDriverShiftRouteOperationKind.DEPOT_LOAD,
                WAREHOUSE_ID,
                null,
                "Склад Санкт-Петербург",
                routeStart,
                routeStart.plusMinutes(15),
                0,
                1),
            new PlanningDriverShiftRouteOperationRequest(
                2,
                PlanningDriverShiftRouteOperationKind.DELIVERY,
                null,
                UUID.fromString("10000000-0000-0000-0000-000000000014"),
                "Клиент",
                routeStart.plusMinutes(30),
                routeStart.plusMinutes(60),
                1,
                0),
            new PlanningDriverShiftRouteOperationRequest(
                3,
                PlanningDriverShiftRouteOperationKind.DEPOT_RETURN,
                WAREHOUSE_ID,
                null,
                "Склад Санкт-Петербург",
                routeStart.plusMinutes(90),
                routeStart.plusMinutes(90),
                0,
                0)));
  }

  private static PlanningDriverShiftPlanRequest crossWarehouseShiftPlan(
      LocalDate workDate, UUID routeOriginWarehouseId, UUID supportWarehouseLinkId) {
    PlanningDriverShiftPlanRequest local = shiftPlan(workDate);
    OffsetDateTime originDeparture = workDate.atTime(7, 0).atOffset(ZoneOffset.UTC);
    return new PlanningDriverShiftPlanRequest(
        local.sourceShiftId(),
        local.sourcePlanId(),
        local.sourcePlanVersion(),
        local.warehouseId(),
        routeOriginWarehouseId,
        supportWarehouseLinkId,
        local.driverId(),
        local.driverName(),
        local.workDate(),
        local.vehicle(),
        local.trailer(),
        local.tripCount(),
        local.routeDistanceMeters(),
        List.of(
            new PlanningDriverShiftRouteOperationRequest(
                1,
                PlanningDriverShiftRouteOperationKind.ORIGIN_START,
                routeOriginWarehouseId,
                null,
                "Склад Санкт-Петербург",
                originDeparture,
                originDeparture,
                0,
                0),
            new PlanningDriverShiftRouteOperationRequest(
                2,
                PlanningDriverShiftRouteOperationKind.INBOUND_POSITIONING,
                REPRESENTATIVE_WAREHOUSE_ID,
                null,
                "Склад Великий Новгород",
                originDeparture.plusHours(1),
                originDeparture,
                0,
                0),
            new PlanningDriverShiftRouteOperationRequest(
                3,
                PlanningDriverShiftRouteOperationKind.DEPOT_LOAD,
                REPRESENTATIVE_WAREHOUSE_ID,
                null,
                "Склад Великий Новгород",
                originDeparture.plusHours(1),
                originDeparture.plusMinutes(75),
                0,
                1),
            new PlanningDriverShiftRouteOperationRequest(
                4,
                PlanningDriverShiftRouteOperationKind.DELIVERY,
                null,
                UUID.fromString("10000000-0000-0000-0000-000000000014"),
                "Клиент",
                originDeparture.plusMinutes(90),
                originDeparture.plusMinutes(105),
                1,
                0),
            new PlanningDriverShiftRouteOperationRequest(
                5,
                PlanningDriverShiftRouteOperationKind.DEPOT_RETURN,
                REPRESENTATIVE_WAREHOUSE_ID,
                null,
                "Склад Великий Новгород",
                originDeparture.plusHours(2),
                originDeparture.plusHours(2),
                0,
                0),
            new PlanningDriverShiftRouteOperationRequest(
                6,
                PlanningDriverShiftRouteOperationKind.RETURN_POSITIONING,
                routeOriginWarehouseId,
                null,
                "Склад Санкт-Петербург",
                originDeparture.plusHours(3),
                originDeparture.plusHours(2),
                0,
                0)));
  }

  private static LogisticsDependencyGateway.WarehouseSupportLink supportLink(
      UUID supportLinkId,
      LocalDate allowedDate,
      boolean allowDrivers,
      Set<LocalDate> excludedDates) {
    var rootIdentity =
        new LogisticsDependencyGateway.WarehouseIdentity(
            WAREHOUSE_ID, 1, true, "Санкт-Петербург", null, MOSCOW.getId());
    var representativeIdentity =
        new LogisticsDependencyGateway.WarehouseIdentity(
            REPRESENTATIVE_WAREHOUSE_ID,
            1,
            true,
            "Великий Новгород",
            null,
            MOSCOW.getId());
    return new LogisticsDependencyGateway.WarehouseSupportLink(
        supportLinkId,
        1,
        rootIdentity,
        representativeIdentity,
        1,
        allowDrivers,
        true,
        true,
        true,
        true,
        true,
        Set.of(allowedDate.getDayOfWeek()),
        Set.of(),
        excludedDates,
        null,
        null);
  }

  private static LogisticsDependencyGateway.OrderUnitReservation reservation(UUID unitId) {
    return reservation(unitId, WAREHOUSE_ID);
  }

  private static DriverLogisticsTask plannerShipmentTask(UUID documentId) {
    DriverLogisticsTask task = mock(DriverLogisticsTask.class);
    when(task.getKind()).thenReturn(DriverTaskKind.SHIPMENT);
    when(task.getExternalTaskId())
        .thenReturn(
            UUID.nameUUIDFromBytes(
                ("planner-task:" + documentId)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    when(task.getVersion()).thenReturn(4L);
    return task;
  }

  private static LogisticsDependencyGateway.OrderUnitReservation reservation(
      UUID unitId, UUID warehouseId) {
    return new LogisticsDependencyGateway.OrderUnitReservation(
        UUID.randomUUID(),
        0,
        ORDER_ID,
        unitId,
        warehouseId,
        "HELD",
        null,
        null,
        null,
        null,
        false,
        null);
  }

  private static LogisticsWarehouseLifecycle.AdmissionTicket mockAdmission() {
    return mock(LogisticsWarehouseLifecycle.AdmissionTicket.class);
  }

  private static LogisticsDocumentService.CreateResult documentResult(UUID documentId) {
    LogisticsDocumentView document =
        new LogisticsDocumentView(
            documentId,
            0,
            LogisticsDocumentType.SHIPMENT,
            LogisticsDocumentState.DRAFT,
            WAREHOUSE_ID,
            null,
            "ООО Ромашка",
            "Водитель 1",
            DRIVER_ID,
            null,
            false,
            null,
            LocalDate.now(MOSCOW).plusDays(3),
            ORDER_ID,
            null,
            null,
            null,
            null,
            List.of(),
            OffsetDateTime.now(ZoneOffset.UTC),
            OffsetDateTime.now(ZoneOffset.UTC));
    return new LogisticsDocumentService.CreateResult(document, false);
  }
}
