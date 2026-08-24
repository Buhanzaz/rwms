package dev.buhanzaz.rwms.logistics.order.service;

import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ApplyPlanningAssignmentsRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningAssignmentRequest;
import static dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverAudienceMode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsDocumentView;
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
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies the versioned, date-fenced hand-off between RWMS orders and the route planner. */
class RentalOrderPlanningIntegrationServiceTest {
  private static final UUID WAREHOUSE_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID ORDER_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID DRIVER_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000003");
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
  private final RentalOrderPlanningIntegrationService service =
      new RentalOrderPlanningIntegrationService(
          orders, reads, lines, documents, driverTasks, rentalOrders, lifecycle, dependencies);

  private final RentalOrder order = mock(RentalOrder.class);

  @BeforeEach
  void setUp() {
    OffsetDateTime effectiveFrom = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
    when(dependencies.warehouseTimeZoneAt(eq(WAREHOUSE_ID), any()))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseTimeZone(
                WAREHOUSE_ID, MOSCOW.getId(), effectiveFrom));
    when(order.getId()).thenReturn(ORDER_ID);
    when(order.getVersion()).thenReturn(7L);
    when(order.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(order.getStatus()).thenReturn(RentalOrderStatus.SAVED);
    when(orders.findPlanningCandidateById(ORDER_ID)).thenReturn(Optional.of(order));
    when(lines.findAssignedRentalShipmentAssetIds(eq(ORDER_ID), any())).thenReturn(List.of());
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
    when(reads.readUnits(order)).thenReturn(List.of(firstReservation, secondReservation));
    when(lines.findAssignedRentalShipmentAssetIds(ORDER_ID, List.of(UNIT_ONE, UNIT_TWO)))
        .thenReturn(List.of(UNIT_ONE));

    var response = service.feed(WAREHOUSE_ID, first, second);

    assertThat(response.timeZone()).isEqualTo("Europe/Moscow");
    assertThat(response.requests()).singleElement().satisfies(request -> {
      assertThat(request.orderId()).isEqualTo(ORDER_ID);
      assertThat(request.unitIds()).containsExactly(UNIT_TWO);
      assertThat(request.quantity()).isEqualTo(1);
      assertThat(request.latitude()).isEqualByComparingTo("55.751244");
      assertThat(request.dateOptions()).extracting(option -> option.date())
          .containsExactly(first, second);
      assertThat(request.dateOptions()).extracting(option -> option.priority())
          .containsExactly(0, 1);
      assertThat(request.dateOptions()).allMatch(option -> !option.isHard());
    });
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
    when(reads.readUnits(order)).thenReturn(List.of(unitReservation));

    var response = service.feed(WAREHOUSE_ID, second, second);

    assertThat(response.requests()).singleElement().satisfies(request ->
        assertThat(request.dateOptions()).singleElement().satisfies(option -> {
          assertThat(option.date()).isEqualTo(second);
          assertThat(option.priority()).isEqualTo(1);
          assertThat(option.isHard()).isFalse();
        }));
  }

  @Test
  void automaticAssignmentRejectsTodayAndTomorrowWithoutCreatingShipment() {
    LocalDate tomorrow = LocalDate.now(MOSCOW).plusDays(1);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(tomorrow, tomorrow)));
    var unitReservation = reservation(UNIT_ONE);
    when(reads.readUnits(order)).thenReturn(List.of(unitReservation));

    var response =
        service.apply(
            UUID.randomUUID(),
            request(
                List.of(
                    new PlanningAssignmentRequest(
                        ORDER_ID, 7L, tomorrow, DRIVER_ID, "Водитель 1", List.of(UNIT_ONE)))));

    assertThat(response.applied()).isEmpty();
    assertThat(response.rejected()).singleElement().satisfies(rejected ->
        assertThat(rejected.code()).isEqualTo("PLANNING_DATE_LOCKED"));
    verify(lifecycle, never()).prepareDocument(any(), any(), any(), any());
    verify(rentalOrders, never()).createRentalShipment(any(), any(), any(), any(), any(), any());
  }

  @Test
  void explicitSharedAssignmentPublishesTomorrowWithoutInventingDriverIdentity() {
    LocalDate tomorrow = LocalDate.now(MOSCOW).plusDays(1);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(tomorrow, tomorrow)));
    var unitReservation = reservation(UNIT_ONE);
    when(reads.readUnits(order)).thenReturn(List.of(unitReservation));
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
  }

  @Test
  void sharedAssignmentRejectsTodayBeforeCreatingShipment() {
    LocalDate today = LocalDate.now(MOSCOW);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(today, today)));

    var response =
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
                        List.of(UNIT_ONE)))));

    assertThat(response.applied()).isEmpty();
    assertThat(response.rejected()).singleElement().satisfies(rejected ->
        assertThat(rejected.code()).isEqualTo("SHARED_TASK_REQUIRES_FUTURE_DATE"));
    verify(rentalOrders, never()).createRentalShipment(any(), any(), any(), any(), any(), any());
  }

  @Test
  void retryReturnsPriorShipmentBeforeDateVersionAndAssignmentChecks() {
    LocalDate formerlyEligibleDate = LocalDate.now(MOSCOW).plusDays(1);
    var priorResult = documentResult(UUID.randomUUID());
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
                        List.of(UNIT_ONE)))));

    assertThat(response.rejected()).isEmpty();
    assertThat(response.applied()).singleElement().satisfies(applied -> {
      assertThat(applied.documentId()).isEqualTo(priorResult.response().id());
      assertThat(applied.replayed()).isTrue();
    });
    verify(orders, never()).findPlanningCandidateById(any());
    verify(lifecycle, never()).prepareDocument(any(), any(), any(), any());
  }

  @Test
  void oneOrderCanBeAppliedAsSeveralNonOverlappingCabinPartsWithStableKeys() {
    LocalDate scheduled = LocalDate.now(MOSCOW).plusDays(3);
    when(order.getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(scheduled, scheduled)));
    var firstReservation = reservation(UNIT_ONE);
    var secondReservation = reservation(UNIT_TWO);
    var thirdReservation = reservation(UNIT_THREE);
    when(reads.readUnits(order))
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
    when(documents
            .findAllByDocumentTypeAndWarehouseIdAndScheduledDateAndRequestedBySubjectIdOrderByCreatedAtAscIdAsc(
                eq(LogisticsDocumentType.SHIPMENT), eq(WAREHOUSE_ID), eq(scheduled), any()))
        .thenReturn(List.of(document));
    when(lines.findAllByDocumentIdIn(List.of(documentId))).thenReturn(List.of(line));
    when(driverTasks.findAllBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, List.of(documentId)))
        .thenReturn(List.of(task));

    var response = service.assignmentStatuses(WAREHOUSE_ID, scheduled);

    assertThat(response.assignments()).singleElement().satisfies(status -> {
      assertThat(status.orderId()).isEqualTo(ORDER_ID);
      assertThat(status.documentId()).isEqualTo(documentId);
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
    return new ApplyPlanningAssignmentsRequest(
        WAREHOUSE_ID, UUID.fromString("10000000-0000-0000-0000-000000000010"), 1L, assignments);
  }

  private static LogisticsDependencyGateway.OrderUnitReservation reservation(UUID unitId) {
    LogisticsDependencyGateway.OrderUnitReservation reservation =
        mock(LogisticsDependencyGateway.OrderUnitReservation.class);
    when(reservation.unitId()).thenReturn(unitId);
    return reservation;
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
