package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.DriverShiftApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.taskboard.domain.DriverShiftRouteOperationKind;
import dev.buhanzaz.rwms.taskboard.domain.EndVehicleCondition;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.InspectionItemState;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.QueueDefinition;
import dev.buhanzaz.rwms.taskboard.domain.ReturnConfirmationType;
import dev.buhanzaz.rwms.taskboard.domain.ShiftPhotoRole;
import dev.buhanzaz.rwms.taskboard.domain.ShiftPhotoState;
import dev.buhanzaz.rwms.taskboard.domain.TaskAssignment;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.domain.VehicleConfigurationType;
import dev.buhanzaz.rwms.taskboard.domain.Worker;
import dev.buhanzaz.rwms.taskboard.domain.WorkerOperationalAssignment;
import dev.buhanzaz.rwms.taskboard.domain.WorkerOperationalAssignmentMode;
import dev.buhanzaz.rwms.taskboard.domain.WorkerOperationalAssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.repository.BoardTaskRepository;
import dev.buhanzaz.rwms.taskboard.repository.DriverShiftPhotoRepository;
import dev.buhanzaz.rwms.taskboard.repository.DriverShiftRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueDefinitionRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.VehicleDefectRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerOperationalAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueRepository;
import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.DriverShiftService;
import dev.buhanzaz.rwms.taskboard.service.DriverWeatherProvider;
import dev.buhanzaz.rwms.taskboard.service.NotFoundException;
import dev.buhanzaz.rwms.taskboard.service.WarehouseIdentityGateway;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** PostgreSQL coverage for replay, resume, guards and the complete daily-shift lifecycle. */
@SpringBootTest(properties = "rwms.driver-shift.enabled=true")
@ActiveProfiles("test")
@Import(DriverShiftServiceIntegrationTest.FixedClockConfiguration.class)
class DriverShiftServiceIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");
  private static final UUID REPRESENTATIVE_WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000804");
  private static final UUID DRIVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000802");
  private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000803");
  private static final UUID TRANSFER_A_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000806");
  private static final UUID TRANSFER_B_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000807");
  private static final LocalDate WORK_DATE = LocalDate.of(2026, 8, 30);

  @Autowired DriverShiftService shifts;
  @Autowired WorkerRepository workers;
  @Autowired DriverShiftRepository shiftRepository;
  @Autowired VehicleDefectRepository defects;
  @Autowired DriverShiftPhotoRepository photos;
  @Autowired BoardTaskRepository tasks;
  @Autowired QueueEntryRepository entries;
  @Autowired QueueDefinitionRepository queueDefinitions;
  @Autowired TaskAssignmentRepository assignments;
  @Autowired WorkerOperationalAssignmentRepository operationalAssignments;
  @Autowired WorkQueueRepository queues;
  @Autowired JdbcTemplate jdbc;

  @MockitoBean WarehouseIdentityGateway warehouses;
  @MockitoBean DriverWeatherProvider weather;

  @BeforeEach
  void setUp() {
    cleanTaskBoardFixtures(jdbc);
    reset(warehouses, weather);
    when(warehouses.identity(WAREHOUSE_ID))
        .thenReturn(
            new WarehouseIdentityGateway.WarehouseIdentity(
                WAREHOUSE_ID,
                7,
                true,
                "Склад Санкт-Петербург",
                "Санкт-Петербург",
                "Складская улица, 1",
                BigDecimal.valueOf(59.94),
                BigDecimal.valueOf(30.32),
                "Europe/Moscow"));
    when(warehouses.identity(REPRESENTATIVE_WAREHOUSE_ID))
        .thenReturn(
            new WarehouseIdentityGateway.WarehouseIdentity(
                REPRESENTATIVE_WAREHOUSE_ID,
                3,
                true,
                "Склад Великий Новгород",
                "Великий Новгород",
                "Большая Санкт-Петербургская улица, 1",
                BigDecimal.valueOf(58.52),
                BigDecimal.valueOf(31.27),
                "Asia/Novosibirsk"));
    when(weather.briefing(any(), any()))
        .thenReturn(
            new DailyWeatherBriefing(
                false,
                "Данные MET Norway",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of()));
    Worker worker = new Worker();
    worker.assignReviewedId(DRIVER_ID);
    worker.setWarehouseId(WAREHOUSE_ID);
    worker.setDisplayName("Александр Иванов");
    worker.setActive(true);
    workers.saveAndFlush(worker);
  }

  @Test
  void concurrentTodayRequestsCreateOneShiftAndBothReturnTheSameAggregate() throws Exception {
    UUID sourceShiftId = UUID.randomUUID();
    PutDriverShiftPlanRequest plan = planRequest();
    DriverShiftPlanResponse created =
        shifts.putPlan(sourceShiftId, UUID.randomUUID().toString(), plan);
    DriverShiftPlanResponse replayed =
        shifts.putPlan(sourceShiftId, UUID.randomUUID().toString(), plan);
    assertThat(created.result()).isEqualTo(PlanApplyResult.CREATED);
    assertThat(replayed.result()).isEqualTo(PlanApplyResult.REPLAYED);
    assertThat(jdbc.queryForObject("select count(*) from driver_shift_plan", Integer.class))
        .isEqualTo(1);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Callable<TodayShiftResponse> request =
          () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS))
              throw new IllegalStateException("Concurrent start barrier timed out");
            return shifts.today(DRIVER_ID, WAREHOUSE_ID);
          };
      Future<TodayShiftResponse> first = executor.submit(request);
      Future<TodayShiftResponse> second = executor.submit(request);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      TodayShiftResponse firstResult = first.get(30, TimeUnit.SECONDS);
      TodayShiftResponse secondResult = second.get(30, TimeUnit.SECONDS);

      assertThat(secondResult.shift().id()).isEqualTo(firstResult.shift().id());
      assertThat(shiftRepository.count()).isOne();
      assertThat(jdbc.queryForObject("select count(*) from vehicle_inspection", Integer.class))
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void replacesAndThenFreezesOneOrderedCrossWarehouseRouteSnapshot() {
    UUID sourceShiftId = UUID.randomUUID();
    UUID sourcePlanId = UUID.randomUUID();
    PutDriverShiftPlanRequest first =
        crossWarehousePlanRequest(sourcePlanId, 1, "Невский проспект, 1");

    DriverShiftPlanResponse created =
        shifts.putPlan(sourceShiftId, UUID.randomUUID().toString(), first);
    DriverShiftPlanResponse replayed =
        shifts.putPlan(sourceShiftId, UUID.randomUUID().toString(), first);
    PutDriverShiftPlanRequest replacement =
        crossWarehousePlanRequest(sourcePlanId, 2, "Невский проспект, 2");
    DriverShiftPlanResponse replaced =
        shifts.putPlan(sourceShiftId, UUID.randomUUID().toString(), replacement);

    assertThat(created.result()).isEqualTo(PlanApplyResult.CREATED);
    assertThat(replayed.result()).isEqualTo(PlanApplyResult.REPLAYED);
    assertThat(replaced.result()).isEqualTo(PlanApplyResult.REPLACED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from driver_shift_route_operation", Integer.class))
        .isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "select cabin_capacity from driver_shift_plan", Integer.class))
        .isNull();

    TodayShiftResponse today = shifts.today(DRIVER_ID, WAREHOUSE_ID);

    assertThat(today.operations())
        .extracting(RouteOperationView::sequence)
        .containsExactly(1, 2, 3, 4, 5, 6);
    assertThat(today.operations())
        .extracting(RouteOperationView::kind)
        .containsExactly(
            DriverShiftRouteOperationKind.ORIGIN_START,
            DriverShiftRouteOperationKind.INBOUND_POSITIONING,
            DriverShiftRouteOperationKind.DEPOT_LOAD,
            DriverShiftRouteOperationKind.DELIVERY,
            DriverShiftRouteOperationKind.DEPOT_RETURN,
            DriverShiftRouteOperationKind.RETURN_POSITIONING);
    assertThat(today.operations().get(1).plannedDeparture())
        .isEqualTo(OffsetDateTime.parse("2026-08-30T07:00:00Z"));
    assertThat(today.operations().get(1).plannedArrival())
        .isEqualTo(OffsetDateTime.parse("2026-08-30T08:00:00Z"));
    assertThat(today.operations().get(3).locationLabel()).isEqualTo("Невский проспект, 2");
    assertThat(today.operations().getLast().warehouseId()).isEqualTo(WAREHOUSE_ID);
    assertThat(today.vehicle().cabinCapacity()).isNull();

    PutDriverShiftPlanRequest frozenReplacement =
        crossWarehousePlanRequest(sourcePlanId, 3, "Невский проспект, 3");
    assertThatThrownBy(
            () ->
                shifts.putPlan(
                    sourceShiftId, UUID.randomUUID().toString(), frozenReplacement))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("frozen");
  }

  @Test
  void persistsCapacityAndBalancedTransferCargoAcrossTheCrossWarehouseRoute() {
    PutDriverShiftPlanRequest request = transferCrossWarehousePlanRequest(2);

    DriverShiftPlanResponse created =
        shifts.putPlan(UUID.randomUUID(), UUID.randomUUID().toString(), request);
    TodayShiftResponse today = shifts.today(DRIVER_ID, WAREHOUSE_ID);

    assertThat(created.result()).isEqualTo(PlanApplyResult.CREATED);
    assertThat(today.vehicle().cabinCapacity()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select cabin_capacity from driver_shift_plan", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForList(
                "select source_transfer_id from driver_shift_route_operation "
                    + "where source_transfer_id is not null order by operation_sequence",
                UUID.class))
        .containsExactly(TRANSFER_A_ID, TRANSFER_B_ID, TRANSFER_A_ID, TRANSFER_B_ID);
    assertThat(today.operations())
        .extracting(RouteOperationView::kind)
        .containsExactly(
            DriverShiftRouteOperationKind.ORIGIN_START,
            DriverShiftRouteOperationKind.TRANSFER_LOAD,
            DriverShiftRouteOperationKind.TRANSFER_LOAD,
            DriverShiftRouteOperationKind.INBOUND_POSITIONING,
            DriverShiftRouteOperationKind.TRANSFER_UNLOAD,
            DriverShiftRouteOperationKind.TRANSFER_UNLOAD,
            DriverShiftRouteOperationKind.DEPOT_LOAD,
            DriverShiftRouteOperationKind.DELIVERY,
            DriverShiftRouteOperationKind.DEPOT_RETURN,
            DriverShiftRouteOperationKind.RETURN_POSITIONING);
    assertThat(today.operations())
        .extracting(RouteOperationView::sourceTransferId)
        .containsExactly(
            null,
            TRANSFER_A_ID,
            TRANSFER_B_ID,
            null,
            TRANSFER_A_ID,
            TRANSFER_B_ID,
            null,
            null,
            null,
            null);
  }

  @Test
  void persistsFurnitureOnlyTransferActionsWithoutChangingTheCabinLoad() {
    PutDriverShiftPlanRequest valid = transferCrossWarehousePlanRequest(2);
    List<RouteOperationView> operations = new java.util.ArrayList<>(valid.operations());
    operations.set(2, withLoad(operations.get(2), 1, 1));
    operations.set(3, withLoad(operations.get(3), 1, 1));
    operations.set(4, withLoad(operations.get(4), 1, 0));
    operations.set(5, withLoad(operations.get(5), 0, 0));
    PutDriverShiftPlanRequest furnitureTransfer =
        new PutDriverShiftPlanRequest(
            valid.sourcePlanId(),
            valid.sourcePlanVersion(),
            valid.warehouseId(),
            valid.driverId(),
            valid.driverName(),
            valid.workDate(),
            valid.vehicle(),
            valid.trailer(),
            valid.tripCount(),
            valid.routeDistanceMeters(),
            operations);

    shifts.putPlan(UUID.randomUUID(), UUID.randomUUID().toString(), furnitureTransfer);

    assertThat(shifts.today(DRIVER_ID, WAREHOUSE_ID).operations())
        .filteredOn(operation -> TRANSFER_B_ID.equals(operation.sourceTransferId()))
        .extracting(RouteOperationView::loadBefore, RouteOperationView::loadAfter)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1, 1),
            org.assertj.core.groups.Tuple.tuple(0, 0));
  }

  @Test
  void rejectsTransferCargoWithoutCapacityOrAboveTheVehicleCapacity() {
    assertThatThrownBy(
            () ->
                shifts.putPlan(
                    UUID.randomUUID(),
                    UUID.randomUUID().toString(),
                    transferCrossWarehousePlanRequest(null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("require vehicle cabin capacity");

    assertThatThrownBy(
            () ->
                shifts.putPlan(
                    UUID.randomUUID(),
                    UUID.randomUUID().toString(),
                    transferCrossWarehousePlanRequest(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds vehicle cabin capacity");
    assertThat(jdbc.queryForObject("select count(*) from driver_shift_plan", Integer.class))
        .isZero();
  }

  @Test
  void rejectsChangedTransferIdentityWrongEndpointAndUnrelatedIdentity() {
    PutDriverShiftPlanRequest valid = transferCrossWarehousePlanRequest(2);
    List<RouteOperationView> changedTransfer = new java.util.ArrayList<>(valid.operations());
    RouteOperationView unloadB = changedTransfer.get(5);
    changedTransfer.set(
        5,
        new RouteOperationView(
            unloadB.sequence(),
            unloadB.kind(),
            unloadB.warehouseId(),
            unloadB.sourceTaskId(),
            UUID.randomUUID(),
            unloadB.locationLabel(),
            unloadB.plannedArrival(),
            unloadB.plannedDeparture(),
            unloadB.loadBefore(),
            unloadB.loadAfter()));
    assertThatThrownBy(
            () ->
                shifts.putPlan(
                    UUID.randomUUID(),
                    UUID.randomUUID().toString(),
                    copyWithOperations(valid, changedTransfer)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("balanced destination unload");

    List<RouteOperationView> wrongEndpoint = new java.util.ArrayList<>(valid.operations());
    RouteOperationView loadA = wrongEndpoint.get(1);
    wrongEndpoint.set(
        1,
        new RouteOperationView(
            loadA.sequence(),
            loadA.kind(),
            REPRESENTATIVE_WAREHOUSE_ID,
            loadA.sourceTaskId(),
            loadA.sourceTransferId(),
            loadA.locationLabel(),
            loadA.plannedArrival(),
            loadA.plannedDeparture(),
            loadA.loadBefore(),
            loadA.loadAfter()));
    assertThatThrownBy(
            () ->
                shifts.putPlan(
                    UUID.randomUUID(),
                    UUID.randomUUID().toString(),
                    copyWithOperations(valid, wrongEndpoint)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("origin warehouse");

    List<RouteOperationView> unrelatedIdentity = new java.util.ArrayList<>(valid.operations());
    RouteOperationView delivery = unrelatedIdentity.get(7);
    unrelatedIdentity.set(
        7,
        new RouteOperationView(
            delivery.sequence(),
            delivery.kind(),
            delivery.warehouseId(),
            delivery.sourceTaskId(),
            TRANSFER_A_ID,
            delivery.locationLabel(),
            delivery.plannedArrival(),
            delivery.plannedDeparture(),
            delivery.loadBefore(),
            delivery.loadAfter()));
    assertThatThrownBy(
            () ->
                shifts.putPlan(
                    UUID.randomUUID(),
                    UUID.randomUUID().toString(),
                    copyWithOperations(valid, unrelatedIdentity)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("identity or load chain");
  }

  @Test
  void rejectsUnbalancedTransferDeltaAndUnloadAfterServiceOperations() {
    PutDriverShiftPlanRequest valid = transferCrossWarehousePlanRequest(2);

    assertThatThrownBy(
            () ->
                shifts.putPlan(
                    UUID.randomUUID(),
                    UUID.randomUUID().toString(),
                    copyWithOperations(valid, unbalancedTransferOperations())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("balanced destination unload");

    assertThatThrownBy(
            () ->
                shifts.putPlan(
                    UUID.randomUUID(),
                    UUID.randomUUID().toString(),
                    copyWithOperations(valid, misplacedTransferUnloadOperations())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("immediately follow inbound");
  }

  @Test
  void activeOperationalAssignmentUsesDestinationShiftWithHomePrincipalAndResumesItAfterReturn() {
    WorkerOperationalAssignment assignment = activeTemporaryAssignment();
    shifts.putPlan(
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        planRequest(REPRESENTATIVE_WAREHOUSE_ID));

    TodayShiftResponse destinationShift = shifts.today(DRIVER_ID, WAREHOUSE_ID);

    assertThat(destinationShift.shift().warehouseId()).isEqualTo(REPRESENTATIVE_WAREHOUSE_ID);
    assertThat(destinationShift.shift().timeZone()).isEqualTo("Asia/Novosibirsk");
    assertThat(destinationShift.warehouse().id()).isEqualTo(REPRESENTATIVE_WAREHOUSE_ID);
    assertThat(workers.findById(DRIVER_ID).orElseThrow().getWarehouseId()).isEqualTo(WAREHOUSE_ID);

    UUID briefingOperation = UUID.randomUUID();
    ShiftTransitionRequest briefingRequest =
        new ShiftTransitionRequest(briefingOperation, destinationShift.shift().version());
    TodayShiftResponse afterBriefing =
        shifts.markBriefing(
            DRIVER_ID,
            WAREHOUSE_ID,
            destinationShift.shift().id(),
            briefingOperation.toString(),
            briefingRequest);

    assignment.transitionTo(
        WorkerOperationalAssignmentStatus.COMPLETED,
        OffsetDateTime.parse("2026-08-30T09:01:00Z"),
        "integration-test");
    operationalAssignments.saveAndFlush(assignment);

    TodayShiftResponse resumed = shifts.today(DRIVER_ID, WAREHOUSE_ID);
    TodayShiftResponse replayed =
        shifts.markBriefing(
            DRIVER_ID,
            WAREHOUSE_ID,
            destinationShift.shift().id(),
            briefingOperation.toString(),
            briefingRequest);

    assertThat(resumed.shift().id()).isEqualTo(destinationShift.shift().id());
    assertThat(resumed.shift().warehouseId()).isEqualTo(REPRESENTATIVE_WAREHOUSE_ID);
    assertThat(replayed.shift().version()).isEqualTo(afterBriefing.shift().version());
    assertThat(replayed.warehouse().id()).isEqualTo(REPRESENTATIVE_WAREHOUSE_ID);
    UUID foreignContextOperation = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                shifts.markBriefing(
                    DRIVER_ID,
                    REPRESENTATIVE_WAREHOUSE_ID,
                    destinationShift.shift().id(),
                    foreignContextOperation.toString(),
                    new ShiftTransitionRequest(
                        foreignContextOperation, destinationShift.shift().version())))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void plannedAndInTransitAssignmentsCannotExposeOrCreateDestinationShift() {
    Worker worker = workers.findById(DRIVER_ID).orElseThrow();
    WorkerOperationalAssignment assignment =
        WorkerOperationalAssignment.planned(
            worker,
            UUID.randomUUID(),
            WAREHOUSE_ID,
            REPRESENTATIVE_WAREHOUSE_ID,
            WorkerOperationalAssignmentMode.TEMPORARY,
            OffsetDateTime.parse("2026-08-30T08:00:00Z"),
            OffsetDateTime.parse("2026-08-30T10:00:00Z"),
            OffsetDateTime.parse("2026-08-30T18:00:00Z"),
            OffsetDateTime.parse("2026-08-30T07:00:00Z"),
            "integration-test");
    assignment.assignReviewedId(UUID.randomUUID());
    operationalAssignments.saveAndFlush(assignment);
    shifts.putPlan(
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        planRequest(REPRESENTATIVE_WAREHOUSE_ID));

    TodayShiftResponse planned = shifts.today(DRIVER_ID, WAREHOUSE_ID);
    assignment.transitionTo(
        WorkerOperationalAssignmentStatus.IN_TRANSIT,
        OffsetDateTime.parse("2026-08-30T08:30:00Z"),
        "integration-test");
    operationalAssignments.saveAndFlush(assignment);
    TodayShiftResponse inTransit = shifts.today(DRIVER_ID, WAREHOUSE_ID);

    assertThat(planned.nextRequiredAction()).isEqualTo(NextRequiredAction.SHIFT_NOT_AVAILABLE);
    assertThat(planned.shift()).isNull();
    assertThat(inTransit.nextRequiredAction()).isEqualTo(NextRequiredAction.SHIFT_NOT_AVAILABLE);
    assertThat(inTransit.shift()).isNull();
    assertThat(shiftRepository.count()).isZero();
  }

  @Test
  void creationAcknowledgementsAndInspectionProgressAreReplaySafeAndBlockingDefectStopsStart() {
    TodayShiftResponse first = createPreparedShift();
    TodayShiftResponse repeated = shifts.today(DRIVER_ID, WAREHOUSE_ID);
    assertThat(repeated.shift().id()).isEqualTo(first.shift().id());
    assertThat(shiftRepository.count()).isOne();

    UUID briefingOperation = UUID.randomUUID();
    TodayShiftResponse medical =
        shifts.markBriefing(
            DRIVER_ID,
            WAREHOUSE_ID,
            first.shift().id(),
            briefingOperation.toString(),
            new ShiftTransitionRequest(briefingOperation, first.shift().version()));
    TodayShiftResponse briefingReplay =
        shifts.markBriefing(
            DRIVER_ID,
            WAREHOUSE_ID,
            first.shift().id(),
            briefingOperation.toString(),
            new ShiftTransitionRequest(briefingOperation, first.shift().version()));
    assertThat(briefingReplay.shift().briefingSeenAt()).isEqualTo(medical.shift().briefingSeenAt());

    UUID medicalOperation = UUID.randomUUID();
    TodayShiftResponse inspection =
        shifts.confirmMedical(
            DRIVER_ID,
            WAREHOUSE_ID,
            first.shift().id(),
            medicalOperation.toString(),
            new ConfirmMedicalCheckRequest(
                medicalOperation,
                medical.shift().version(),
                OffsetDateTime.parse("2026-08-30T12:01:00Z")));
    TodayShiftResponse medicalReplay =
        shifts.confirmMedical(
            DRIVER_ID,
            WAREHOUSE_ID,
            first.shift().id(),
            medicalOperation.toString(),
            new ConfirmMedicalCheckRequest(
                medicalOperation,
                medical.shift().version(),
                OffsetDateTime.parse("2026-08-30T12:01:00Z")));
    assertThat(medicalReplay.shift().medicalCheck().completedAt())
        .isEqualTo(inspection.shift().medicalCheck().completedAt());

    InspectionItemView item = inspection.inspection().items().getFirst();
    UUID defectId = UUID.randomUUID();
    UUID itemOperation = UUID.randomUUID();
    UpdateInspectionItemRequest defectRequest =
        new UpdateInspectionItemRequest(
            itemOperation,
            inspection.shift().version(),
            item.version(),
            InspectionItemState.DEFECT,
            defectId,
            "Повреждён тормозной шланг");
    TodayShiftResponse answered =
        shifts.updateInspectionItem(
            DRIVER_ID,
            WAREHOUSE_ID,
            first.shift().id(),
            item.id(),
            itemOperation.toString(),
            defectRequest);
    TodayShiftResponse itemReplay =
        shifts.updateInspectionItem(
            DRIVER_ID,
            WAREHOUSE_ID,
            first.shift().id(),
            item.id(),
            itemOperation.toString(),
            defectRequest);
    assertThat(answered.shift().version()).isEqualTo(inspection.shift().version() + 1);
    assertThat(itemReplay.shift().version()).isEqualTo(answered.shift().version());

    InspectionItemView answeredItem = answered.inspection().items().getFirst();
    UUID noOpOperation = UUID.randomUUID();
    TodayShiftResponse sameContentNoOp =
        shifts.updateInspectionItem(
            DRIVER_ID,
            WAREHOUSE_ID,
            first.shift().id(),
            item.id(),
            noOpOperation.toString(),
            new UpdateInspectionItemRequest(
                noOpOperation,
                answered.shift().version(),
                answeredItem.version(),
                InspectionItemState.DEFECT,
                defectId,
                "Повреждён тормозной шланг"));
    assertThat(sameContentNoOp.shift().version()).isEqualTo(answered.shift().version());
    assertThat(sameContentNoOp.inspection().items().getFirst().version())
        .isEqualTo(answeredItem.version());

    UUID inspectionPhotoId = UUID.randomUUID();
    TodayShiftResponse inspectionPhoto =
        shifts.reservePhoto(
            DRIVER_ID,
            WAREHOUSE_ID,
            first.shift().id(),
            inspectionPhotoId.toString(),
            photoRequest(
                inspectionPhotoId,
                sameContentNoOp.shift().version(),
                inspectionPhotoId,
                ShiftPhotoRole.INSPECTION_DEFECT,
                defectId,
                item.id()));
    assertThat(inspectionPhoto.shift().version())
        .isEqualTo(sameContentNoOp.shift().version() + 1);

    InspectionItemView secondItem = inspectionPhoto.inspection().items().get(1);
    UUID staleOperation = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                shifts.updateInspectionItem(
                    DRIVER_ID,
                    WAREHOUSE_ID,
                    first.shift().id(),
                    secondItem.id(),
                    staleOperation.toString(),
                    new UpdateInspectionItemRequest(
                        staleOperation,
                        inspection.shift().version(),
                        secondItem.version(),
                        InspectionItemState.OK,
                        null,
                        null)))
        .isInstanceOf(dev.buhanzaz.rwms.taskboard.service.StaleVersionException.class);

    UUID secondOperation = UUID.randomUUID();
    TodayShiftResponse secondAnswered =
        shifts.updateInspectionItem(
            DRIVER_ID,
            WAREHOUSE_ID,
            first.shift().id(),
            secondItem.id(),
                secondOperation.toString(),
                new UpdateInspectionItemRequest(
                    secondOperation,
                    inspectionPhoto.shift().version(),
                    secondItem.version(),
                InspectionItemState.OK,
                null,
                null));
    assertThat(secondAnswered.shift().version())
        .isEqualTo(inspectionPhoto.shift().version() + 1);

    TodayShiftResponse resumed = shifts.today(DRIVER_ID, WAREHOUSE_ID);
    assertThat(resumed.shift().version()).isEqualTo(secondAnswered.shift().version());
    assertThat(resumed.inspection().checkedRequired()).isEqualTo(2);
    assertThat(resumed.inspection().blockingDefectCount()).isOne();
    assertThat(defects.findById(defectId)).isPresent();
    assertThat(answered.inspection().items().getFirst().state())
        .isEqualTo(InspectionItemState.DEFECT);
    assertThat(itemReplay.inspection().checkedRequired()).isOne();

    TodayShiftResponse fullyChecked = resumed;
    for (InspectionItemView remaining : resumed.inspection().items().stream().skip(2).toList()) {
      UUID operationId = UUID.randomUUID();
      fullyChecked =
          shifts.updateInspectionItem(
              DRIVER_ID,
              WAREHOUSE_ID,
              first.shift().id(),
              remaining.id(),
              operationId.toString(),
              new UpdateInspectionItemRequest(
                  operationId,
                  fullyChecked.shift().version(),
                  remaining.version(),
                  InspectionItemState.OK,
                  null,
                  null));
    }
    assertThat(fullyChecked.inspection().checkedRequired())
        .isEqualTo(fullyChecked.inspection().totalRequired());

    UUID completeOperation = UUID.randomUUID();
    long fullyCheckedShiftVersion = fullyChecked.shift().version();
    assertThatThrownBy(
            () ->
                shifts.completeInspection(
                    DRIVER_ID,
                    WAREHOUSE_ID,
                    first.shift().id(),
                    completeOperation.toString(),
                    new ShiftTransitionRequest(completeOperation, fullyCheckedShiftVersion)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("blocking vehicle defect");
  }

  @Test
  void finalTaskOpensClosingAndReturnOdometerFuelAndCloseGuardsRemainIdempotent() {
    TodayShiftResponse briefing = createPreparedShift();
    TodayShiftResponse inspection = acknowledgePreparation(briefing);

    UUID prematureStart = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                shifts.start(
                    DRIVER_ID,
                    WAREHOUSE_ID,
                    briefing.shift().id(),
                    prematureStart.toString(),
                    new ShiftTransitionRequest(prematureStart, inspection.shift().version())))
        .isInstanceOf(ConflictException.class);

    TodayShiftResponse progress = inspection;
    for (InspectionItemView item : inspection.inspection().items()) {
      UUID operationId = UUID.randomUUID();
      progress =
          shifts.updateInspectionItem(
              DRIVER_ID,
              WAREHOUSE_ID,
              briefing.shift().id(),
              item.id(),
              operationId.toString(),
              new UpdateInspectionItemRequest(
                  operationId,
                  progress.shift().version(),
                  item.version(),
                  InspectionItemState.OK,
                  null,
                  null));
    }
    assertThat(progress.inspection().checkedRequired())
        .isEqualTo(progress.inspection().totalRequired());

    UUID inspectionOperation = UUID.randomUUID();
    TodayShiftResponse ready =
        shifts.completeInspection(
            DRIVER_ID,
            WAREHOUSE_ID,
            briefing.shift().id(),
            inspectionOperation.toString(),
            new ShiftTransitionRequest(inspectionOperation, progress.shift().version()));
    UUID startOperation = UUID.randomUUID();
    TodayShiftResponse active =
        shifts.start(
            DRIVER_ID,
            WAREHOUSE_ID,
            briefing.shift().id(),
            startOperation.toString(),
            new ShiftTransitionRequest(startOperation, ready.shift().version()));
    assertThat(active.nextRequiredAction()).isEqualTo(NextRequiredAction.SHOW_TASKS);

    BoardTask task = sharedDriverTask(REPRESENTATIVE_WAREHOUSE_ID);
    task = tasks.saveAndFlush(task);
    Worker foreignDriver = new Worker();
    foreignDriver.assignReviewedId(UUID.randomUUID());
    foreignDriver.setWarehouseId(WAREHOUSE_ID);
    foreignDriver.setDisplayName("Другой водитель");
    foreignDriver.setActive(true);
    foreignDriver = workers.saveAndFlush(foreignDriver);
    BoardTask foreignTask =
        assignedTask(REPRESENTATIVE_WAREHOUSE_ID, foreignDriver.getId());
    tasks.saveAndFlush(foreignTask);
    TodayShiftResponse unassignedSharedTask = shifts.today(DRIVER_ID, WAREHOUSE_ID);
    assertThat(unassignedSharedTask.taskSummary().totalCount()).isZero();
    assertThat(unassignedSharedTask.taskSummary().canStartClosing()).isFalse();

    QueueEntry entry = new QueueEntry();
    entry.setTask(task);
    entry.setQueue(assignmentQueue(REPRESENTATIVE_WAREHOUSE_ID));
    entry.setRouteIndex(0);
    entry.setQueuePosition(1);
    entry.setEntryType(EntryType.REAL);
    entry = entries.saveAndFlush(entry);
    TaskAssignment assignment = new TaskAssignment();
    assignment.setQueueEntry(entry);
    assignment.setWorker(workers.findById(DRIVER_ID).orElseThrow());
    assignment.setWorkerNameSnapshot("Александр Иванов");
    assignment.setStatus(AssignmentStatus.PAUSED);
    assignment.setAssignedAt(OffsetDateTime.parse("2026-08-30T08:00:00+03:00"));
    assignment.setPausedAt(OffsetDateTime.parse("2026-08-30T08:30:00+03:00"));
    assignments.saveAndFlush(assignment);

    BoardTask plannedDoneTask = assignedTask(REPRESENTATIVE_WAREHOUSE_ID, DRIVER_ID);
    plannedDoneTask.setStatus(TaskStatus.DONE);
    plannedDoneTask.setDoneAt(OffsetDateTime.parse("2026-08-30T18:00:00+03:00"));
    tasks.saveAndFlush(plannedDoneTask);
    TodayShiftResponse assignedWork = shifts.today(DRIVER_ID, WAREHOUSE_ID);
    assertThat(assignedWork.taskSummary().totalCount()).isEqualTo(2);
    assertThat(assignedWork.taskSummary().activeCount()).isOne();
    assertThat(assignedWork.taskSummary().completedCount()).isOne();
    assertThat(assignedWork.taskSummary().canStartClosing()).isFalse();
    UUID earlyClosing = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                shifts.startClosing(
                    DRIVER_ID,
                    WAREHOUSE_ID,
                    active.shift().id(),
                    earlyClosing.toString(),
                    new ShiftTransitionRequest(earlyClosing, assignedWork.shift().version())))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("not DONE");

    task.setStatus(TaskStatus.DONE);
    task.setDoneAt(OffsetDateTime.parse("2026-08-30T19:00:00+03:00"));
    tasks.saveAndFlush(task);
    TodayShiftResponse closingAvailable = shifts.today(DRIVER_ID, WAREHOUSE_ID);
    assertThat(closingAvailable.nextRequiredAction())
        .isEqualTo(NextRequiredAction.START_SHIFT_CLOSING);
    assertThat(closingAvailable.taskSummary().canStartClosing()).isTrue();

    UUID closingOperation = UUID.randomUUID();
    TodayShiftResponse returning =
        shifts.startClosing(
            DRIVER_ID,
            WAREHOUSE_ID,
            active.shift().id(),
            closingOperation.toString(),
            new ShiftTransitionRequest(closingOperation, closingAvailable.shift().version()));
    UUID prematureClose = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                shifts.close(
                    DRIVER_ID,
                    WAREHOUSE_ID,
                    active.shift().id(),
                    prematureClose.toString(),
                    new ShiftTransitionRequest(prematureClose, returning.shift().version())))
        .isInstanceOf(ConflictException.class);

    UUID returnOperation = UUID.randomUUID();
    TodayShiftResponse reportRequired =
        shifts.confirmReturn(
            DRIVER_ID,
            WAREHOUSE_ID,
            active.shift().id(),
            returnOperation.toString(),
            new ReturnToWarehouseRequest(
                returnOperation, returning.shift().version(), ReturnConfirmationType.MANUAL));
    assertThat(reportRequired.shift().returnedToWarehouseAt()).isNotNull();

    UUID lowOdometer = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                shifts.submitClosingReport(
                    DRIVER_ID,
                    WAREHOUSE_ID,
                    active.shift().id(),
                    lowOdometer.toString(),
                    new SubmitClosingReportRequest(
                        lowOdometer,
                        reportRequired.shift().version(),
                        EndVehicleCondition.NO_NEW_DEFECTS,
                        9_999,
                        50,
                        false,
                        null,
                        null)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("cannot be lower");

    UUID suspicious = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                shifts.submitClosingReport(
                    DRIVER_ID,
                    WAREHOUSE_ID,
                    active.shift().id(),
                    suspicious.toString(),
                    new SubmitClosingReportRequest(
                        suspicious,
                        reportRequired.shift().version(),
                        EndVehicleCondition.NO_NEW_DEFECTS,
                        12_000,
                        50,
                        false,
                        null,
                        null)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("ODOMETER_CONFIRMATION_REQUIRED");

    UUID reportOperation = UUID.randomUUID();
    TodayShiftResponse closeReady =
        shifts.submitClosingReport(
            DRIVER_ID,
            WAREHOUSE_ID,
            active.shift().id(),
            reportOperation.toString(),
            new SubmitClosingReportRequest(
                reportOperation,
                reportRequired.shift().version(),
                EndVehicleCondition.NO_NEW_DEFECTS,
                10_214,
                63,
                false,
                null,
                null));
    assertThat(closeReady.closingReport().odometerDistance()).isEqualTo(214);

    UUID closeOperation = UUID.randomUUID();
    ShiftTransitionRequest closeRequest =
        new ShiftTransitionRequest(closeOperation, closeReady.shift().version());
    TodayShiftResponse closed =
        shifts.close(
            DRIVER_ID, WAREHOUSE_ID, active.shift().id(), closeOperation.toString(), closeRequest);
    TodayShiftResponse replayedClose =
        shifts.close(
            DRIVER_ID, WAREHOUSE_ID, active.shift().id(), closeOperation.toString(), closeRequest);
    TodayShiftResponse restarted = shifts.today(DRIVER_ID, WAREHOUSE_ID);
    assertThat(closed.nextRequiredAction()).isEqualTo(NextRequiredAction.SHIFT_CLOSED);
    assertThat(replayedClose.shift().closedAt()).isEqualTo(closed.shift().closedAt());
    assertThat(restarted.shift().id()).isEqualTo(closed.shift().id());
    assertThat(restarted.nextRequiredAction()).isEqualTo(NextRequiredAction.SHIFT_CLOSED);
  }

  @Test
  void photoVersionsRoleStatesAndReadyClosingDefectGateAreAuthoritative() {
    TodayShiftResponse active = startActiveShift();
    TodayShiftResponse reportRequired = advanceToEndVehicleCheck(active);

    UUID earlyEndShiftPhoto = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                shifts.reservePhoto(
                    DRIVER_ID,
                    WAREHOUSE_ID,
                    active.shift().id(),
                    earlyEndShiftPhoto.toString(),
                    photoRequest(
                        earlyEndShiftPhoto,
                        reportRequired.shift().version(),
                        earlyEndShiftPhoto,
                        ShiftPhotoRole.END_SHIFT_DEFECT,
                        UUID.randomUUID(),
                        null)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("not accepted");

    UUID overviewId = UUID.randomUUID();
    ReserveShiftPhotoRequest overviewRequest =
        photoRequest(
            overviewId,
            reportRequired.shift().version(),
            overviewId,
            ShiftPhotoRole.VEHICLE_OVERVIEW,
            null,
            null);
    TodayShiftResponse overview =
        shifts.reservePhoto(
            DRIVER_ID,
            WAREHOUSE_ID,
            active.shift().id(),
            overviewId.toString(),
            overviewRequest);
    assertThat(overview.shift().version()).isEqualTo(reportRequired.shift().version() + 1);
    assertThat(overview.nextRequiredAction())
        .isEqualTo(NextRequiredAction.COMPLETE_END_OF_SHIFT_REPORT);

    TodayShiftResponse overviewReplay =
        shifts.reservePhoto(
            DRIVER_ID,
            WAREHOUSE_ID,
            active.shift().id(),
            overviewId.toString(),
            overviewRequest);
    assertThat(overviewReplay.shift().version()).isEqualTo(overview.shift().version());
    UUID overviewNoOp = UUID.randomUUID();
    TodayShiftResponse sameOverviewNoOp =
        shifts.reservePhoto(
            DRIVER_ID,
            WAREHOUSE_ID,
            active.shift().id(),
            overviewNoOp.toString(),
            photoRequest(
                overviewNoOp,
                overview.shift().version(),
                overviewId,
                ShiftPhotoRole.VEHICLE_OVERVIEW,
                null,
                null));
    assertThat(sameOverviewNoOp.shift().version()).isEqualTo(overview.shift().version());

    UUID staleReport = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                shifts.submitClosingReport(
                    DRIVER_ID,
                    WAREHOUSE_ID,
                    active.shift().id(),
                    staleReport.toString(),
                    new SubmitClosingReportRequest(
                        staleReport,
                        reportRequired.shift().version(),
                        EndVehicleCondition.NO_NEW_DEFECTS,
                        10_214,
                        63,
                        false,
                        null,
                        null)))
        .isInstanceOf(dev.buhanzaz.rwms.taskboard.service.StaleVersionException.class);

    UUID defectId = UUID.randomUUID();
    UUID reportOperation = UUID.randomUUID();
    TodayShiftResponse closeReady =
        shifts.submitClosingReport(
            DRIVER_ID,
            WAREHOUSE_ID,
            active.shift().id(),
            reportOperation.toString(),
            new SubmitClosingReportRequest(
                reportOperation,
                sameOverviewNoOp.shift().version(),
                EndVehicleCondition.DEFECT_REPORTED,
                10_214,
                63,
                false,
                defectId,
                "Новая трещина на бампере"));
    assertThat(closeReady.nextRequiredAction()).isEqualTo(NextRequiredAction.CLOSE_SHIFT);
    assertThat(closeReady.closingReport().defectId()).isEqualTo(defectId);

    UUID noDefectPhotoClose = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                shifts.close(
                    DRIVER_ID,
                    WAREHOUSE_ID,
                    active.shift().id(),
                    noDefectPhotoClose.toString(),
                    new ShiftTransitionRequest(
                        noDefectPhotoClose, closeReady.shift().version())))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("READY end-shift defect photo");

    UUID evidenceId = UUID.randomUUID();
    ReserveShiftPhotoRequest endShiftPhotoRequest =
        photoRequest(
            evidenceId,
            closeReady.shift().version(),
            evidenceId,
            ShiftPhotoRole.END_SHIFT_DEFECT,
            defectId,
            null);
    TodayShiftResponse reserved =
        shifts.reservePhoto(
            DRIVER_ID,
            WAREHOUSE_ID,
            active.shift().id(),
            evidenceId.toString(),
            endShiftPhotoRequest);
    assertThat(reserved.shift().version()).isEqualTo(closeReady.shift().version() + 1);
    assertThat(reserved.photos())
        .filteredOn(photo -> photo.evidenceId().equals(evidenceId))
        .singleElement()
        .satisfies(photo -> assertThat(photo.state()).isEqualTo(ShiftPhotoState.RESERVED));

    UUID reservedClose = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                shifts.close(
                    DRIVER_ID,
                    WAREHOUSE_ID,
                    active.shift().id(),
                    reservedClose.toString(),
                    new ShiftTransitionRequest(reservedClose, reserved.shift().version())))
        .isInstanceOf(ConflictException.class);

    var photo = photos.findByEvidenceId(evidenceId).orElseThrow();
    photo.applyMedia(ShiftPhotoState.PROCESSING, null, null, null);
    photos.saveAndFlush(photo);
    UUID processingClose = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                shifts.close(
                    DRIVER_ID,
                    WAREHOUSE_ID,
                    active.shift().id(),
                    processingClose.toString(),
                    new ShiftTransitionRequest(processingClose, reserved.shift().version())))
        .isInstanceOf(ConflictException.class);

    photo = photos.findByEvidenceId(evidenceId).orElseThrow();
    photo.applyMedia(ShiftPhotoState.READY, UUID.randomUUID(), 1L, null);
    photos.saveAndFlush(photo);
    UUID closeOperation = UUID.randomUUID();
    TodayShiftResponse closed =
        shifts.close(
            DRIVER_ID,
            WAREHOUSE_ID,
            active.shift().id(),
            closeOperation.toString(),
            new ShiftTransitionRequest(closeOperation, reserved.shift().version()));
    assertThat(closed.nextRequiredAction()).isEqualTo(NextRequiredAction.SHIFT_CLOSED);
  }

  private TodayShiftResponse createPreparedShift() {
    UUID sourceShiftId = UUID.randomUUID();
    shifts.putPlan(sourceShiftId, UUID.randomUUID().toString(), planRequest());
    TodayShiftResponse response = shifts.today(DRIVER_ID, WAREHOUSE_ID);
    assertThat(response.nextRequiredAction()).isEqualTo(NextRequiredAction.SHOW_DAILY_BRIEFING);
    assertThat(response.suspiciousOdometerJumpKm()).isEqualTo(1_500);
    assertThat(response.taskSummary().routeDistanceMeters()).isEqualTo(214_000L);
    assertThat(response.briefing().weather().available()).isFalse();
    return response;
  }

  private TodayShiftResponse acknowledgePreparation(TodayShiftResponse briefing) {
    UUID briefingOperation = UUID.randomUUID();
    TodayShiftResponse medical =
        shifts.markBriefing(
            DRIVER_ID,
            WAREHOUSE_ID,
            briefing.shift().id(),
            briefingOperation.toString(),
            new ShiftTransitionRequest(briefingOperation, briefing.shift().version()));
    UUID medicalOperation = UUID.randomUUID();
    return shifts.confirmMedical(
        DRIVER_ID,
        WAREHOUSE_ID,
        briefing.shift().id(),
        medicalOperation.toString(),
        new ConfirmMedicalCheckRequest(medicalOperation, medical.shift().version(), null));
  }

  private TodayShiftResponse startActiveShift() {
    TodayShiftResponse briefing = createPreparedShift();
    TodayShiftResponse progress = acknowledgePreparation(briefing);
    for (InspectionItemView item : progress.inspection().items()) {
      UUID operationId = UUID.randomUUID();
      progress =
          shifts.updateInspectionItem(
              DRIVER_ID,
              WAREHOUSE_ID,
              briefing.shift().id(),
              item.id(),
              operationId.toString(),
              new UpdateInspectionItemRequest(
                  operationId,
                  progress.shift().version(),
                  item.version(),
                  InspectionItemState.OK,
                  null,
                  null));
    }
    UUID inspectionOperation = UUID.randomUUID();
    TodayShiftResponse ready =
        shifts.completeInspection(
            DRIVER_ID,
            WAREHOUSE_ID,
            briefing.shift().id(),
            inspectionOperation.toString(),
            new ShiftTransitionRequest(inspectionOperation, progress.shift().version()));
    UUID startOperation = UUID.randomUUID();
    return shifts.start(
        DRIVER_ID,
        WAREHOUSE_ID,
        briefing.shift().id(),
        startOperation.toString(),
        new ShiftTransitionRequest(startOperation, ready.shift().version()));
  }

  private TodayShiftResponse advanceToEndVehicleCheck(TodayShiftResponse active) {
    BoardTask completed = assignedTask();
    completed.setStatus(TaskStatus.DONE);
    completed.setDoneAt(OffsetDateTime.parse("2026-08-30T19:00:00+03:00"));
    tasks.saveAndFlush(completed);
    TodayShiftResponse closingAvailable = shifts.today(DRIVER_ID, WAREHOUSE_ID);
    UUID closingOperation = UUID.randomUUID();
    TodayShiftResponse returning =
        shifts.startClosing(
            DRIVER_ID,
            WAREHOUSE_ID,
            active.shift().id(),
            closingOperation.toString(),
            new ShiftTransitionRequest(closingOperation, closingAvailable.shift().version()));
    UUID returnOperation = UUID.randomUUID();
    return shifts.confirmReturn(
        DRIVER_ID,
        WAREHOUSE_ID,
        active.shift().id(),
        returnOperation.toString(),
        new ReturnToWarehouseRequest(
            returnOperation, returning.shift().version(), ReturnConfirmationType.MANUAL));
  }

  private ReserveShiftPhotoRequest photoRequest(
      UUID operationId,
      long expectedVersion,
      UUID evidenceId,
      ShiftPhotoRole role,
      UUID defectId,
      UUID inspectionItemId) {
    return new ReserveShiftPhotoRequest(
        operationId,
        expectedVersion,
        evidenceId,
        evidenceId,
        role,
        defectId,
        inspectionItemId,
        OffsetDateTime.parse("2026-08-30T17:00:00Z"),
        "image/jpeg",
        1_024,
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
  }

  private PutDriverShiftPlanRequest planRequest() {
    return planRequest(WAREHOUSE_ID);
  }

  private PutDriverShiftPlanRequest planRequest(UUID warehouseId) {
    return new PutDriverShiftPlanRequest(
        UUID.randomUUID(),
        1,
        warehouseId,
        DRIVER_ID,
        "Александр Иванов",
        WORK_DATE,
        new PlannedVehicle(
            VEHICLE_ID,
            "MAN TGS",
            "А123АА78",
            "TRUCK",
            "MAN",
            "TGS",
            VehicleConfigurationType.TRUCK,
            10_000L),
        null,
        1,
        214_000L);
  }

  private PutDriverShiftPlanRequest crossWarehousePlanRequest(
      UUID sourcePlanId, long sourcePlanVersion, String customerLabel) {
    PutDriverShiftPlanRequest local = planRequest(WAREHOUSE_ID);
    UUID sourceTaskId = UUID.fromString("00000000-0000-0000-0000-000000000805");
    return new PutDriverShiftPlanRequest(
        sourcePlanId,
        sourcePlanVersion,
        local.warehouseId(),
        local.driverId(),
        local.driverName(),
        local.workDate(),
        local.vehicle(),
        local.trailer(),
        local.tripCount(),
        local.routeDistanceMeters(),
        List.of(
            routeOperation(
                1,
                DriverShiftRouteOperationKind.ORIGIN_START,
                WAREHOUSE_ID,
                null,
                "Склад Санкт-Петербург",
                "2026-08-30T07:00:00Z",
                "2026-08-30T07:00:00Z",
                0,
                0),
            routeOperation(
                2,
                DriverShiftRouteOperationKind.INBOUND_POSITIONING,
                REPRESENTATIVE_WAREHOUSE_ID,
                null,
                "Склад Великий Новгород",
                "2026-08-30T08:00:00Z",
                "2026-08-30T07:00:00Z",
                0,
                0),
            routeOperation(
                3,
                DriverShiftRouteOperationKind.DEPOT_LOAD,
                REPRESENTATIVE_WAREHOUSE_ID,
                null,
                "Склад Великий Новгород",
                "2026-08-30T08:00:00Z",
                "2026-08-30T08:15:00Z",
                0,
                1),
            routeOperation(
                4,
                DriverShiftRouteOperationKind.DELIVERY,
                null,
                sourceTaskId,
                customerLabel,
                "2026-08-30T08:30:00Z",
                "2026-08-30T08:45:00Z",
                1,
                0),
            routeOperation(
                5,
                DriverShiftRouteOperationKind.DEPOT_RETURN,
                REPRESENTATIVE_WAREHOUSE_ID,
                null,
                "Склад Великий Новгород",
                "2026-08-30T09:00:00Z",
                "2026-08-30T09:00:00Z",
                0,
                0),
            routeOperation(
                6,
                DriverShiftRouteOperationKind.RETURN_POSITIONING,
                WAREHOUSE_ID,
                null,
                "Склад Санкт-Петербург",
                "2026-08-30T10:00:00Z",
                "2026-08-30T09:00:00Z",
                0,
                0)));
  }

  private PutDriverShiftPlanRequest transferCrossWarehousePlanRequest(Integer cabinCapacity) {
    PutDriverShiftPlanRequest local = planRequest(WAREHOUSE_ID);
    PlannedVehicle sourceVehicle = local.vehicle();
    PlannedVehicle transferVehicle =
        new PlannedVehicle(
            sourceVehicle.id(),
            sourceVehicle.name(),
            sourceVehicle.registrationNumber(),
            sourceVehicle.vehicleType(),
            sourceVehicle.manufacturer(),
            sourceVehicle.model(),
            sourceVehicle.configurationType(),
            cabinCapacity,
            sourceVehicle.startOdometer());
    UUID sourceTaskId = UUID.fromString("00000000-0000-0000-0000-000000000805");
    return new PutDriverShiftPlanRequest(
        UUID.randomUUID(),
        1,
        local.warehouseId(),
        local.driverId(),
        local.driverName(),
        local.workDate(),
        transferVehicle,
        local.trailer(),
        local.tripCount(),
        local.routeDistanceMeters(),
        List.of(
            routeOperation(
                1,
                DriverShiftRouteOperationKind.ORIGIN_START,
                WAREHOUSE_ID,
                null,
                null,
                "Склад Санкт-Петербург",
                "2026-08-30T07:00:00Z",
                "2026-08-30T07:00:00Z",
                0,
                0),
            routeOperation(
                2,
                DriverShiftRouteOperationKind.TRANSFER_LOAD,
                WAREHOUSE_ID,
                null,
                TRANSFER_A_ID,
                "Передача A: погрузка",
                "2026-08-30T07:00:00Z",
                "2026-08-30T07:05:00Z",
                0,
                1),
            routeOperation(
                3,
                DriverShiftRouteOperationKind.TRANSFER_LOAD,
                WAREHOUSE_ID,
                null,
                TRANSFER_B_ID,
                "Передача B: погрузка",
                "2026-08-30T07:05:00Z",
                "2026-08-30T07:10:00Z",
                1,
                2),
            routeOperation(
                4,
                DriverShiftRouteOperationKind.INBOUND_POSITIONING,
                REPRESENTATIVE_WAREHOUSE_ID,
                null,
                null,
                "Склад Великий Новгород",
                "2026-08-30T08:00:00Z",
                "2026-08-30T07:10:00Z",
                2,
                2),
            routeOperation(
                5,
                DriverShiftRouteOperationKind.TRANSFER_UNLOAD,
                REPRESENTATIVE_WAREHOUSE_ID,
                null,
                TRANSFER_A_ID,
                "Передача A: разгрузка",
                "2026-08-30T08:00:00Z",
                "2026-08-30T08:05:00Z",
                2,
                1),
            routeOperation(
                6,
                DriverShiftRouteOperationKind.TRANSFER_UNLOAD,
                REPRESENTATIVE_WAREHOUSE_ID,
                null,
                TRANSFER_B_ID,
                "Передача B: разгрузка",
                "2026-08-30T08:05:00Z",
                "2026-08-30T08:10:00Z",
                1,
                0),
            routeOperation(
                7,
                DriverShiftRouteOperationKind.DEPOT_LOAD,
                REPRESENTATIVE_WAREHOUSE_ID,
                null,
                null,
                "Склад Великий Новгород",
                "2026-08-30T08:10:00Z",
                "2026-08-30T08:15:00Z",
                0,
                1),
            routeOperation(
                8,
                DriverShiftRouteOperationKind.DELIVERY,
                null,
                sourceTaskId,
                null,
                "Невский проспект, 1",
                "2026-08-30T08:30:00Z",
                "2026-08-30T08:45:00Z",
                1,
                0),
            routeOperation(
                9,
                DriverShiftRouteOperationKind.DEPOT_RETURN,
                REPRESENTATIVE_WAREHOUSE_ID,
                null,
                null,
                "Склад Великий Новгород",
                "2026-08-30T09:00:00Z",
                "2026-08-30T09:00:00Z",
                0,
                0),
            routeOperation(
                10,
                DriverShiftRouteOperationKind.RETURN_POSITIONING,
                WAREHOUSE_ID,
                null,
                null,
                "Склад Санкт-Петербург",
                "2026-08-30T10:00:00Z",
                "2026-08-30T09:00:00Z",
                0,
                0)));
  }

  private static RouteOperationView withLoad(
      RouteOperationView operation, int loadBefore, int loadAfter) {
    return new RouteOperationView(
        operation.sequence(),
        operation.kind(),
        operation.warehouseId(),
        operation.sourceTaskId(),
        operation.sourceTransferId(),
        operation.locationLabel(),
        operation.plannedArrival(),
        operation.plannedDeparture(),
        loadBefore,
        loadAfter);
  }

  private PutDriverShiftPlanRequest copyWithOperations(
      PutDriverShiftPlanRequest source, List<RouteOperationView> operations) {
    return new PutDriverShiftPlanRequest(
        source.sourcePlanId(),
        source.sourcePlanVersion(),
        source.warehouseId(),
        source.driverId(),
        source.driverName(),
        source.workDate(),
        source.vehicle(),
        source.trailer(),
        source.tripCount(),
        source.routeDistanceMeters(),
        operations);
  }

  private List<RouteOperationView> unbalancedTransferOperations() {
    return List.of(
        routeOperation(
            1,
            DriverShiftRouteOperationKind.ORIGIN_START,
            WAREHOUSE_ID,
            null,
            null,
            "Склад Санкт-Петербург",
            "2026-08-30T07:00:00Z",
            "2026-08-30T07:00:00Z",
            0,
            0),
        routeOperation(
            2,
            DriverShiftRouteOperationKind.TRANSFER_LOAD,
            WAREHOUSE_ID,
            null,
            TRANSFER_A_ID,
            "Передача A: погрузка",
            "2026-08-30T07:00:00Z",
            "2026-08-30T07:10:00Z",
            0,
            2),
        routeOperation(
            3,
            DriverShiftRouteOperationKind.INBOUND_POSITIONING,
            REPRESENTATIVE_WAREHOUSE_ID,
            null,
            null,
            "Склад Великий Новгород",
            "2026-08-30T08:00:00Z",
            "2026-08-30T07:10:00Z",
            2,
            2),
        routeOperation(
            4,
            DriverShiftRouteOperationKind.TRANSFER_UNLOAD,
            REPRESENTATIVE_WAREHOUSE_ID,
            null,
            TRANSFER_A_ID,
            "Передача A: частичная разгрузка",
            "2026-08-30T08:00:00Z",
            "2026-08-30T08:05:00Z",
            2,
            1),
        routeOperation(
            5,
            DriverShiftRouteOperationKind.DEPOT_UNLOAD,
            REPRESENTATIVE_WAREHOUSE_ID,
            null,
            null,
            "Склад Великий Новгород",
            "2026-08-30T08:05:00Z",
            "2026-08-30T08:10:00Z",
            1,
            0),
        routeOperation(
            6,
            DriverShiftRouteOperationKind.RETURN_POSITIONING,
            WAREHOUSE_ID,
            null,
            null,
            "Склад Санкт-Петербург",
            "2026-08-30T09:00:00Z",
            "2026-08-30T08:10:00Z",
            0,
            0));
  }

  private List<RouteOperationView> misplacedTransferUnloadOperations() {
    return List.of(
        routeOperation(
            1,
            DriverShiftRouteOperationKind.ORIGIN_START,
            WAREHOUSE_ID,
            null,
            null,
            "Склад Санкт-Петербург",
            "2026-08-30T07:00:00Z",
            "2026-08-30T07:00:00Z",
            0,
            0),
        routeOperation(
            2,
            DriverShiftRouteOperationKind.INBOUND_POSITIONING,
            REPRESENTATIVE_WAREHOUSE_ID,
            null,
            null,
            "Склад Великий Новгород",
            "2026-08-30T08:00:00Z",
            "2026-08-30T07:00:00Z",
            0,
            0),
        routeOperation(
            3,
            DriverShiftRouteOperationKind.DEPOT_LOAD,
            REPRESENTATIVE_WAREHOUSE_ID,
            null,
            null,
            "Склад Великий Новгород",
            "2026-08-30T08:00:00Z",
            "2026-08-30T08:05:00Z",
            0,
            1),
        routeOperation(
            4,
            DriverShiftRouteOperationKind.TRANSFER_UNLOAD,
            REPRESENTATIVE_WAREHOUSE_ID,
            null,
            TRANSFER_A_ID,
            "Несвоевременная разгрузка",
            "2026-08-30T08:05:00Z",
            "2026-08-30T08:10:00Z",
            1,
            0),
        routeOperation(
            5,
            DriverShiftRouteOperationKind.RETURN_POSITIONING,
            WAREHOUSE_ID,
            null,
            null,
            "Склад Санкт-Петербург",
            "2026-08-30T09:00:00Z",
            "2026-08-30T08:10:00Z",
            0,
            0));
  }

  private RouteOperationView routeOperation(
      int sequence,
      DriverShiftRouteOperationKind kind,
      UUID warehouseId,
      UUID sourceTaskId,
      String locationLabel,
      String plannedArrival,
      String plannedDeparture,
      int loadBefore,
      int loadAfter) {
    return routeOperation(
        sequence,
        kind,
        warehouseId,
        sourceTaskId,
        null,
        locationLabel,
        plannedArrival,
        plannedDeparture,
        loadBefore,
        loadAfter);
  }

  private RouteOperationView routeOperation(
      int sequence,
      DriverShiftRouteOperationKind kind,
      UUID warehouseId,
      UUID sourceTaskId,
      UUID sourceTransferId,
      String locationLabel,
      String plannedArrival,
      String plannedDeparture,
      int loadBefore,
      int loadAfter) {
    return new RouteOperationView(
        sequence,
        kind,
        warehouseId,
        sourceTaskId,
        sourceTransferId,
        locationLabel,
        OffsetDateTime.parse(plannedArrival),
        OffsetDateTime.parse(plannedDeparture),
        loadBefore,
        loadAfter);
  }

  private WorkerOperationalAssignment activeTemporaryAssignment() {
    Worker worker = workers.findById(DRIVER_ID).orElseThrow();
    WorkerOperationalAssignment assignment =
        WorkerOperationalAssignment.planned(
            worker,
            UUID.randomUUID(),
            WAREHOUSE_ID,
            REPRESENTATIVE_WAREHOUSE_ID,
            WorkerOperationalAssignmentMode.TEMPORARY,
            OffsetDateTime.parse("2026-08-30T07:30:00Z"),
            OffsetDateTime.parse("2026-08-30T08:00:00Z"),
            OffsetDateTime.parse("2026-08-30T18:00:00Z"),
            OffsetDateTime.parse("2026-08-30T07:00:00Z"),
            "integration-test");
    assignment.assignReviewedId(UUID.randomUUID());
    assignment.transitionTo(
        WorkerOperationalAssignmentStatus.IN_TRANSIT,
        OffsetDateTime.parse("2026-08-30T07:30:00Z"),
        "integration-test");
    assignment.transitionTo(
        WorkerOperationalAssignmentStatus.ACTIVE,
        OffsetDateTime.parse("2026-08-30T08:00:00Z"),
        "integration-test");
    return operationalAssignments.saveAndFlush(assignment);
  }

  private BoardTask assignedTask() {
    return assignedTask(WAREHOUSE_ID, DRIVER_ID);
  }

  private BoardTask assignedTask(UUID warehouseId, UUID driverId) {
    BoardTask task = new BoardTask();
    task.assignReviewedId(UUID.randomUUID());
    task.setWarehouseId(warehouseId);
    task.setExternalTaskId(UUID.randomUUID());
    task.setTitle("Ходка Driver Up");
    task.setScheduledDate(WORK_DATE);
    task.setDriverAudienceMode(DriverTaskAudienceMode.ASSIGNED_DRIVER);
    task.setPlannedDriverWorkerId(driverId);
    task.setPlannedDriverNameSnapshot(
        DRIVER_ID.equals(driverId) ? "Александр Иванов" : "Другой водитель");
    return task;
  }

  private BoardTask sharedDriverTask(UUID warehouseId) {
    BoardTask task = new BoardTask();
    task.assignReviewedId(UUID.randomUUID());
    task.setWarehouseId(warehouseId);
    task.setExternalTaskId(UUID.randomUUID());
    task.setTitle("Общая ходка склада");
    task.setScheduledDate(WORK_DATE);
    task.setDriverAudienceMode(DriverTaskAudienceMode.WAREHOUSE_DRIVERS);
    return task;
  }

  private WorkQueue assignmentQueue(UUID warehouseId) {
    QueueDefinition definition = new QueueDefinition();
    definition.setName("Driver Shift integration queue " + UUID.randomUUID());
    definition = queueDefinitions.saveAndFlush(definition);
    WorkQueue queue = new WorkQueue();
    queue.setWarehouseId(warehouseId);
    queue.setDefinition(definition);
    return queues.saveAndFlush(queue);
  }

  /** Supplies deterministic server time to the state-machine integration tests. */
  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {
    @Bean
    @Primary
    Clock driverShiftTestClock() {
      return Clock.fixed(Instant.parse("2026-08-30T09:00:00Z"), ZoneOffset.UTC);
    }
  }
}
