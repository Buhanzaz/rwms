package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ArriveTransferLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CabinFurnitureRequirement;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateTransferRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.MediaReferenceInput;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferFurnitureReplacementRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferCabinAllocationRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferCabinGroupRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferFurniturePerCabinRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferLooseFurnitureRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferPlanRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferResourceRepositionRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.UpdateTransferPlanRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsLineState;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanState;
import dev.buhanzaz.rwms.logistics.domain.TransferReservationReadiness;
import dev.buhanzaz.rwms.logistics.domain.TransferResourceRepositionMode;
import dev.buhanzaz.rwms.logistics.driver.service.DocumentDriverTaskPlanner;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import dev.buhanzaz.rwms.logistics.driver.service.TransferDriverTaskContentService;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementLocationKind;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.LogisticsExternalAttemptClaimService;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import dev.buhanzaz.rwms.logistics.service.TransferProcessor;
import dev.buhanzaz.rwms.logistics.service.TransferPlanProcessor;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.return-registration.relay-enabled=false",
      "rwms.logistics.return-completion.relay-enabled=false",
      "rwms.logistics.shipment.relay-enabled=false",
      "rwms.logistics.transfer.relay-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransferWorkflowSagaIntegrationTest {
  private static final UUID ORIGIN = UUID.fromString("00000000-0000-0000-0000-000000000901");
  private static final UUID DESTINATION = UUID.fromString("00000000-0000-0000-0000-000000000902");
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000903");
  private static final UUID CORRELATION = UUID.fromString("00000000-0000-0000-0000-000000000904");
  private static final UUID ASSET = UUID.fromString("00000000-0000-0000-0000-000000000905");
  private static final UUID EQUIPMENT = UUID.fromString("00000000-0000-0000-0000-000000000906");
  private static final UUID ACTIVE_REPAIR =
      UUID.fromString("00000000-0000-0000-0000-000000000907");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired LogisticsDocumentService documents;
  @Autowired LogisticsExternalAttemptClaimService claims;
  @Autowired TransferProcessor processor;
  @Autowired TransferPlanProcessor transferPlanProcessor;
  @Autowired EquipmentMovementTaskService equipmentTasks;
  @Autowired LogisticsWarehouseLifecycle warehouseLifecycle;
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @MockitoBean LogisticsDependencyGateway dependencies;
  @MockitoBean DocumentDriverTaskPlanner driverTaskPlanner;
  @MockitoBean TransferDriverTaskContentService driverTaskContent;

  @BeforeEach
  void reset() {
    jdbc.execute(
        """
        truncate table
          logistics_document,
          event_stream_head,
          domain_event,
          aggregate_snapshot,
          projection_checkpoint,
          outbox_event
        cascade
        """);
    org.mockito.Mockito.reset(dependencies, driverTaskPlanner, driverTaskContent);
    when(driverTaskContent.build(
            any(LogisticsDocument.class), any(List.class)))
        .thenReturn(DriverTaskWorkerContent.empty());
  }

  @Test
  void reservesPlannedCargoAndResourcesThenCompensatesCancellationIdempotently() {
    LocalDate scheduledDate = OffsetDateTime.now(ZoneOffset.UTC).toLocalDate();
    OffsetDateTime departure = scheduledDate.atTime(8, 30).atOffset(ZoneOffset.UTC);
    OffsetDateTime arrival = departure.plusHours(4);
    UUID secondAsset = UUID.randomUUID();
    UUID rentalType = UUID.randomUUID();
    UUID finishing = UUID.randomUUID();
    UUID characteristic = UUID.randomUUID();
    UUID tripDriver = UUID.randomUUID();
    UUID repositionedDriver = UUID.randomUUID();
    UUID tripVehicle = UUID.randomUUID();
    UUID repositionedVehicle = UUID.randomUUID();
    TransferPlanRequest plan =
        new TransferPlanRequest(
            departure,
            arrival,
            "  Межскладской рейс  ",
            tripDriver,
            tripVehicle,
            new TransferResourceRepositionRequest(
                repositionedDriver,
                TransferResourceRepositionMode.TEMPORARY,
                arrival.plusDays(2)),
            new TransferResourceRepositionRequest(
                repositionedVehicle, TransferResourceRepositionMode.PERMANENT, null),
            List.of(
                new TransferCabinGroupRequest(
                    rentalType,
                    null,
                    finishing,
                    List.of(characteristic),
                    true,
                    2,
                    List.of(new TransferFurniturePerCabinRequest(EQUIPMENT, 4L)),
                    List.of(
                        new TransferCabinAllocationRequest(ASSET, 7L),
                        new TransferCabinAllocationRequest(secondAsset, 3L)))),
            List.of(new TransferLooseFurnitureRequest(EQUIPMENT, 3L)));

    var created =
        documents.createTransfer(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateTransferRequest(
                ORIGIN, DESTINATION, scheduledDate, List.of(), List.of(), plan));

    assertThat(created.response().lines()).isEmpty();
    var draft = documents.getTransferPlan(created.response().id());
    assertThat(draft.state()).isEqualTo(TransferPlanState.DRAFT);
    assertThat(draft.totalCabinCount()).isEqualTo(2);
    assertThat(draft.tripDriverId()).isEqualTo(tripDriver);
    assertThat(draft.driverReposition().resourceId()).isEqualTo(repositionedDriver);
    assertThat(draft.logisticsComment()).isEqualTo("Межскладской рейс");
    assertThat(draft.furnitureTotals())
        .singleElement()
        .satisfies(
            total -> {
              assertThat(total.cabinRequirementQuantity()).isEqualTo(8);
              assertThat(total.looseQuantity()).isEqualTo(3);
              assertThat(total.totalQuantity()).isEqualTo(11);
            });

    UUID updateKey = UUID.randomUUID();
    var updated =
        documents.updateTransferPlan(
            SUBJECT,
            updateKey,
            CORRELATION,
            created.response().id(),
            created.response().version(),
            new UpdateTransferPlanRequest(scheduledDate, plan),
            scheduledDate);
    var updateReplay =
        documents.updateTransferPlan(
            SUBJECT,
            updateKey,
            CORRELATION,
            created.response().id(),
            created.response().version(),
            new UpdateTransferPlanRequest(scheduledDate, plan),
            scheduledDate);

    assertThat(updated.response().documentVersion()).isGreaterThan(created.response().version());
    assertThat(updateReplay.replayed()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where event_type='logistics.transfer.plan-updated.v1'",
                Long.class))
        .isOne();

    when(dependencies.planCabinFurnitureMovements(eq(ORIGIN), eq(ASSET), any()))
        .thenReturn(
            new LogisticsDependencyGateway.CabinFurnitureMovementPlan(
                ASSET, "БЫТ-001", List.of()));
    when(dependencies.planCabinFurnitureMovements(eq(ORIGIN), eq(secondAsset), any()))
        .thenReturn(
            new LogisticsDependencyGateway.CabinFurnitureMovementPlan(
                secondAsset, "БЫТ-002", List.of()));
    UUID confirmKey = UUID.randomUUID();
    var admission =
        warehouseLifecycle.disabledTicket(
            SUBJECT,
            "CONFIRM_TRANSFER_PLAN",
            confirmKey,
            List.of(
                new AdmissionRequirement(ORIGIN, WarehouseOperationDirection.OUTGOING),
                new AdmissionRequirement(DESTINATION, WarehouseOperationDirection.INCOMING)));
    var confirmed =
        documents.confirmTransferPlan(
            SUBJECT,
            confirmKey,
            CORRELATION,
            created.response().id(),
            updated.response().documentVersion(),
            admission);
    var replay =
        documents.confirmTransferPlan(
            SUBJECT,
            confirmKey,
            CORRELATION,
            created.response().id(),
            updated.response().documentVersion(),
            admission);

    assertThat(confirmed.response().state()).isEqualTo(TransferPlanState.CONFIRMED);
    assertThat(confirmed.response().reservationReadiness())
        .isEqualTo(TransferReservationReadiness.RESERVING);
    assertThat(confirmed.response().readinessDetail())
        .isEqualTo("RESERVATION_IN_PROGRESS");
    assertThat(replay.replayed()).isTrue();
    var physical = documents.get(created.response().id(), LogisticsDocumentType.TRANSFER);
    assertThat(physical.lines())
        .extracting(line -> line.assetId())
        .containsExactly(ASSET, secondAsset);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where event_type='logistics.transfer.confirmed.v1'",
                Long.class))
        .isOne();
    assertThatThrownBy(
            () ->
                documents.departTransferLine(
                    SUBJECT,
                    UUID.randomUUID(),
                    CORRELATION,
                    physical.id(),
                    physical.lines().getFirst().id(),
                    physical.version(),
                    physical.lines().getFirst().version()))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("TRANSFER_RESERVATION_NOT_READY");
    verify(driverTaskPlanner, never())
        .plan(any(dev.buhanzaz.rwms.logistics.domain.LogisticsDocument.class), any(), any());

    UUID furnitureBalanceId = UUID.randomUUID();
    when(dependencies.reserveTransferUnits(any(), eq(physical.id()), eq(ORIGIN), any()))
        .thenAnswer(
            invocation -> {
              List<LogisticsDependencyGateway.TransferUnitReservationRequestLine> requests =
                  invocation.getArgument(3);
              return new LogisticsDependencyGateway.TransferUnitReservationReceipt(
                  physical.id(),
                  requests.stream()
                      .map(
                          item ->
                              new LogisticsDependencyGateway.TransferUnitReservationLineReceipt(
                                  UUID.randomUUID(),
                                  0,
                                  item.lineId(),
                                  item.rentalItemId(),
                                  item.expectedRentalItemVersion() + 1,
                                  "ACTIVE"))
                      .toList());
            });
    when(dependencies.readLogisticsEquipmentAvailability(ORIGIN))
        .thenReturn(
            List.of(
                new LogisticsDependencyGateway.EquipmentWarehouseAvailability(
                    EQUIPMENT,
                    "Кровать",
                    true,
                    20,
                    null,
                    20,
                    List.of(
                        new LogisticsDependencyGateway.EquipmentBalanceAvailability(
                            furnitureBalanceId,
                            4,
                            EQUIPMENT,
                            ORIGIN,
                            null,
                            "STOCK",
                            20,
                            0,
                            true,
                            20)))));
    when(dependencies.acquireEquipmentMovementReservation(
            any(),
            eq(physical.id()),
            any(),
            eq(EQUIPMENT),
            eq(ORIGIN),
            eq(null),
            eq("STOCK"),
            eq(4L),
            eq(3L),
            any(),
            eq(LogisticsDependencyGateway.EquipmentMovementPurpose.TRANSFER_REBALANCE)))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.EquipmentMovementReservation(
                    UUID.randomUUID(),
                    0,
                    "LOGISTICS_EQUIPMENT_MOVEMENT",
                    physical.id(),
                    invocation.getArgument(2),
                    EQUIPMENT,
                    "Кровать",
                    furnitureBalanceId,
                    ORIGIN,
                    null,
                    "STOCK",
                    3,
                    "ACTIVE",
                    invocation.getArgument(9),
                    null));
    when(dependencies.createWorkerOperationalAssignment(
            eq(physical.id()), any(), eq(ORIGIN), eq(DESTINATION), any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.WorkerOperationalAssignment(
                    UUID.randomUUID(),
                    0,
                    physical.id(),
                    invocation.getArgument(1),
                    ORIGIN,
                    ORIGIN,
                    DESTINATION,
                    invocation.getArgument(4),
                    "PLANNED",
                    invocation.getArgument(5),
                    invocation.getArgument(6),
                    invocation.getArgument(7)));
    when(dependencies.listWarehouseDrivers(ORIGIN, departure, false))
        .thenReturn(
            List.of(
                new LogisticsDependencyGateway.WarehouseDriverIdentity(
                    tripDriver, "Петров Алексей")));

    assertThat(
            LogisticsExternalAttemptTestClaims.drainTransferPlan(
                claims, transferPlanProcessor, jdbc))
        .isGreaterThanOrEqualTo(5);
    var reserved = documents.getTransferPlan(physical.id());
    assertThat(reserved.reservationReadiness())
        .isEqualTo(TransferReservationReadiness.RESERVED);
    assertThat(reserved.readinessDetail()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document_line where document_id=? and transfer_unit_reservation_state='ACTIVE'",
                Long.class,
                physical.id()))
        .isEqualTo(2);
    verify(driverTaskPlanner, times(1))
        .plan(any(dev.buhanzaz.rwms.logistics.domain.LogisticsDocument.class), any(), any());

    when(dependencies.releaseTransferUnits(any(), eq(physical.id()), any()))
        .thenAnswer(
            invocation -> {
              List<LogisticsDependencyGateway.TransferUnitReservationReleaseLine> requests =
                  invocation.getArgument(2);
              return new LogisticsDependencyGateway.TransferUnitReservationReceipt(
                  physical.id(),
                  requests.stream()
                      .map(
                          item ->
                              new LogisticsDependencyGateway.TransferUnitReservationLineReceipt(
                                  item.reservationId(),
                                  item.expectedReservationVersion() + 1,
                                  item.lineId(),
                                  item.rentalItemId(),
                                  20,
                                  "RELEASED"))
                      .toList());
            });
    when(dependencies.releaseEquipmentMovementReservation(
            any(), any(), anyLong(), eq(physical.id()), any()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.EquipmentMovementReservation(
                    invocation.getArgument(1),
                    ((Long) invocation.getArgument(2)) + 1,
                    "LOGISTICS_EQUIPMENT_MOVEMENT",
                    physical.id(),
                    invocation.getArgument(4),
                    EQUIPMENT,
                    "Кровать",
                    furnitureBalanceId,
                    ORIGIN,
                    null,
                    "STOCK",
                    3,
                    "RELEASED",
                    arrival.plusDays(1),
                    null));
    when(dependencies.transitionWorkerOperationalAssignment(any(), anyLong(), eq("CANCELLED")))
        .thenAnswer(
            invocation -> {
              UUID assignmentId = invocation.getArgument(0);
              return new LogisticsDependencyGateway.WorkerOperationalAssignment(
                  assignmentId,
                  ((Long) invocation.getArgument(1)) + 1,
                  physical.id(),
                  tripDriver,
                  ORIGIN,
                  ORIGIN,
                  DESTINATION,
                  "TRIP_ONLY",
                  "CANCELLED",
                  departure,
                  arrival,
                  arrival);
            });

    var cancellation =
        documents.cancelTransfer(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            physical.id(),
            documents.get(physical.id(), LogisticsDocumentType.TRANSFER).version());
    assertThat(cancellation.response().state()).isEqualTo(LogisticsDocumentState.CANCELLING);
    assertThat(
            LogisticsExternalAttemptTestClaims.drainTransferPlan(
                claims, transferPlanProcessor, jdbc))
        .isGreaterThanOrEqualTo(3);
    assertThat(documents.get(physical.id(), LogisticsDocumentType.TRANSFER).state())
        .isEqualTo(LogisticsDocumentState.CANCELLED);
    assertThat(documents.getTransferPlan(physical.id()).reservationReadiness())
        .isEqualTo(TransferReservationReadiness.RELEASED);
    assertThat(
            LogisticsExternalAttemptTestClaims.drainTransferPlan(
                claims, transferPlanProcessor, jdbc))
        .isZero();
  }

  @Test
  void departsAndArrivesWithExactMediaContentsAndStableReplays() {
    TransferFixture fixture = createTransfer();
    UUID departureKey = UUID.randomUUID();
    UUID leaseId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    stubDeparture(fixture, leaseId);

    LogisticsDocumentService.CreateResult departure =
        documents.departTransferLine(
            SUBJECT,
            departureKey,
            CORRELATION,
            fixture.documentId(),
            fixture.lineId(),
            fixture.documentVersion(),
            fixture.lineVersion());
    LogisticsDocumentService.CreateResult departureReplay =
        documents.departTransferLine(
            SUBJECT,
            departureKey,
            CORRELATION,
            fixture.documentId(),
            fixture.lineId(),
            fixture.documentVersion(),
            fixture.lineVersion());

    assertThat(departure.response().state()).isEqualTo(LogisticsDocumentState.DEPARTING);
    assertThat(departureReplay.replayed()).isTrue();
    LogisticsExternalAttemptTestClaims.drainTransfer(claims, processor, jdbc);

    var inTransit = documents.get(fixture.documentId(), LogisticsDocumentType.TRANSFER);
    assertThat(inTransit.state()).isEqualTo(LogisticsDocumentState.IN_TRANSIT);
    assertThat(inTransit.lines()).singleElement().extracting(line -> line.state()).isEqualTo(LogisticsLineState.DEPARTED);

    when(dependencies.readRentalItemSnapshot(ASSET)).thenReturn(snapshot(8, ORIGIN, "IN_TRANSFER", 2));
    when(dependencies.validateMediaReferences(
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER),
            eq(fixture.documentId()),
            eq(fixture.lineId()),
            eq(DESTINATION),
            any()))
        .thenReturn(
            new LogisticsDependencyGateway.MediaValidation(
                LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER,
                fixture.documentId(),
                fixture.lineId(),
                DESTINATION,
                List.of(new LogisticsDependencyGateway.MediaReference(mediaId, 3))));
    when(dependencies.applyFencedEffect(
            any(),
            eq(LogisticsDependencyGateway.AssetEffect.TRANSFER_ARRIVE),
            eq(ASSET),
            eq(8L),
            eq(leaseId),
            eq(17L),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER),
            eq(fixture.documentId()),
            eq(fixture.lineId()),
            eq(DESTINATION),
            eq("FREE")))
        .thenReturn(snapshot(9, DESTINATION, "FREE", 2));
    when(dependencies.releaseOperationLease(
            any(),
            eq(leaseId),
            eq(4L),
            eq(17L),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER),
            eq(fixture.documentId()),
            eq(fixture.lineId())))
        .thenReturn(releasedLease(leaseId));

    UUID arrivalKey = UUID.randomUUID();
    ArriveTransferLineRequest arrivalRequest =
        new ArriveTransferLineRequest(
            List.of(new MediaReferenceInput(mediaId, 3)), null);
    LogisticsDocumentService.CreateResult arrival =
        documents.arriveTransferLine(
            SUBJECT,
            arrivalKey,
            CORRELATION,
            fixture.documentId(),
            fixture.lineId(),
            inTransit.version(),
            inTransit.lines().getFirst().version(),
            arrivalRequest);
    LogisticsDocumentService.CreateResult arrivalReplay =
        documents.arriveTransferLine(
            SUBJECT,
            arrivalKey,
            CORRELATION,
            fixture.documentId(),
            fixture.lineId(),
            inTransit.version(),
            inTransit.lines().getFirst().version(),
            arrivalRequest);

    assertThat(arrival.response().state()).isEqualTo(LogisticsDocumentState.ARRIVING);
    assertThat(arrivalReplay.replayed()).isTrue();
    LogisticsExternalAttemptTestClaims.drainTransfer(claims, processor, jdbc);

    var completed = documents.get(fixture.documentId(), LogisticsDocumentType.TRANSFER);
    assertThat(completed.state()).isEqualTo(LogisticsDocumentState.COMPLETED);
    assertThat(completed.lines()).singleElement().extracting(line -> line.state()).isEqualTo(LogisticsLineState.ARRIVED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_media_reference where readiness='READY'", Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_guard where guard_state='RELEASED'", Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where event_type='logistics.transfer.completed.v1'", Long.class))
        .isOne();
    verify(dependencies)
        .applyFencedEffect(
            any(),
            eq(LogisticsDependencyGateway.AssetEffect.TRANSFER_DEPART),
            eq(ASSET),
            eq(7L),
            eq(leaseId),
            eq(17L),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER),
            eq(fixture.documentId()),
            eq(fixture.lineId()),
            eq(null),
            eq("FREE"));
  }

  @Test
  void carriesTheSameActiveRepairAndCompletesItBeforeMarkingTheLineArrived() {
    TransferFixture fixture = createTransfer();
    UUID leaseId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    stubActiveRepairDeparture(fixture, leaseId);

    documents.departTransferLine(
        SUBJECT,
        UUID.randomUUID(),
        CORRELATION,
        fixture.documentId(),
        fixture.lineId(),
        fixture.documentVersion(),
        fixture.lineVersion());
    LogisticsExternalAttemptTestClaims.drainTransfer(claims, processor, jdbc);
    var inTransit = documents.get(fixture.documentId(), LogisticsDocumentType.TRANSFER);
    assertThat(inTransit.state()).isEqualTo(LogisticsDocumentState.IN_TRANSIT);
    assertThat(
            jdbc.queryForObject(
                "select active_repair_id from logistics_document_line where id=?",
                UUID.class,
                fixture.lineId()))
        .isEqualTo(ACTIVE_REPAIR);

    when(dependencies.preflightTransferArrival(
            fixture.documentId(), fixture.lineId(), ASSET, ORIGIN, DESTINATION))
        .thenReturn(
            new LogisticsDependencyGateway.TransferRepairArrivalPreflight(
                ACTIVE_REPAIR, true, List.of()));
    when(dependencies.readRentalItemSnapshot(ASSET))
        .thenReturn(snapshot(8, ORIGIN, "IN_TRANSFER", 2));
    when(dependencies.validateMediaReferences(any(), any(), any(), any(), any()))
        .thenReturn(
            new LogisticsDependencyGateway.MediaValidation(
                LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER,
                fixture.documentId(),
                fixture.lineId(),
                DESTINATION,
                List.of(new LogisticsDependencyGateway.MediaReference(mediaId, 4))));
    when(dependencies.applyFencedEffect(
            any(),
            eq(LogisticsDependencyGateway.AssetEffect.TRANSFER_ARRIVE),
            eq(ASSET),
            eq(8L),
            eq(leaseId),
            eq(17L),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER),
            eq(fixture.documentId()),
            eq(fixture.lineId()),
            eq(DESTINATION),
            eq("REPAIR")))
        .thenReturn(snapshot(9, DESTINATION, "REPAIR", 2));
    when(dependencies.releaseOperationLease(
            any(),
            eq(leaseId),
            eq(4L),
            eq(17L),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER),
            eq(fixture.documentId()),
            eq(fixture.lineId())))
        .thenReturn(releasedLease(leaseId));
    when(dependencies.completeTransferArrival(
            any(),
            eq(fixture.documentId()),
            eq(fixture.lineId()),
            eq(ASSET),
            eq(9L),
            eq(ORIGIN),
            eq(DESTINATION),
            eq(2)))
        .thenAnswer(
            ignored -> {
              var beforeCompletion =
                  documents.get(fixture.documentId(), LogisticsDocumentType.TRANSFER);
              assertThat(beforeCompletion.state()).isEqualTo(LogisticsDocumentState.ARRIVING);
              assertThat(beforeCompletion.lines().getFirst().state())
                  .isEqualTo(LogisticsLineState.ARRIVING);
              verify(dependencies)
                  .releaseOperationLease(
                      any(),
                      eq(leaseId),
                      eq(4L),
                      eq(17L),
                      eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER),
                      eq(fixture.documentId()),
                      eq(fixture.lineId()));
              return new LogisticsDependencyGateway.TransferRepairArrivalCompletion(
                  ACTIVE_REPAIR, 12L, DESTINATION);
            });

    documents.arriveTransferLine(
        SUBJECT,
        UUID.randomUUID(),
        CORRELATION,
        fixture.documentId(),
        fixture.lineId(),
        inTransit.version(),
        inTransit.lines().getFirst().version(),
        new ArriveTransferLineRequest(
            List.of(new MediaReferenceInput(mediaId, 4)), 2));
    LogisticsExternalAttemptTestClaims.drainTransfer(claims, processor, jdbc);

    var completed = documents.get(fixture.documentId(), LogisticsDocumentType.TRANSFER);
    assertThat(completed.state()).isEqualTo(LogisticsDocumentState.COMPLETED);
    assertThat(completed.lines().getFirst().state()).isEqualTo(LogisticsLineState.ARRIVED);
    assertThat(
            jdbc.queryForMap(
                """
                select transfer_asset_status,
                       active_repair_id,
                       active_repair_version,
                       repair_continuation_priority,
                       maintenance_arrival_completed_at
                  from logistics_document_line
                 where id=?
                """,
                fixture.lineId()))
        .containsEntry("transfer_asset_status", "REPAIR")
        .containsEntry("active_repair_id", ACTIVE_REPAIR)
        .containsEntry("active_repair_version", 12L)
        .containsEntry("repair_continuation_priority", 2)
        .containsKey("maintenance_arrival_completed_at");
  }

  @Test
  void blocksArrivalBeforeMutationWhenTheTargetRepairQueueIsMissing() {
    TransferFixture fixture = createTransfer();
    UUID leaseId = UUID.randomUUID();
    stubActiveRepairDeparture(fixture, leaseId);
    documents.departTransferLine(
        SUBJECT,
        UUID.randomUUID(),
        CORRELATION,
        fixture.documentId(),
        fixture.lineId(),
        fixture.documentVersion(),
        fixture.lineVersion());
    LogisticsExternalAttemptTestClaims.drainTransfer(claims, processor, jdbc);
    var inTransit = documents.get(fixture.documentId(), LogisticsDocumentType.TRANSFER);
    UUID missingQueue = UUID.randomUUID();
    when(dependencies.preflightTransferArrival(
            fixture.documentId(), fixture.lineId(), ASSET, ORIGIN, DESTINATION))
        .thenReturn(
            new LogisticsDependencyGateway.TransferRepairArrivalPreflight(
                ACTIVE_REPAIR, true, List.of(missingQueue)));

    assertThatThrownBy(
            () ->
                documents.arriveTransferLine(
                    SUBJECT,
                    UUID.randomUUID(),
                    CORRELATION,
                    fixture.documentId(),
                    fixture.lineId(),
                    inTransit.version(),
                    inTransit.lines().getFirst().version(),
                    new ArriveTransferLineRequest(
                        List.of(new MediaReferenceInput(UUID.randomUUID(), 1)),
                        3)))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("missing queues");

    var unchanged = documents.get(fixture.documentId(), LogisticsDocumentType.TRANSFER);
    assertThat(unchanged.state()).isEqualTo(LogisticsDocumentState.IN_TRANSIT);
    assertThat(unchanged.lines().getFirst().state()).isEqualTo(LogisticsLineState.DEPARTED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_media_reference where line_id=?",
                Long.class,
                fixture.lineId()))
        .isZero();
  }

  @Test
  void exposesArrivalPreflightOnlyToAnEmployeeManagingBothWarehouses()
      throws Exception {
    TransferFixture fixture = createTransfer();
    UUID leaseId = UUID.randomUUID();
    stubActiveRepairDeparture(fixture, leaseId);
    documents.departTransferLine(
        SUBJECT,
        UUID.randomUUID(),
        CORRELATION,
        fixture.documentId(),
        fixture.lineId(),
        fixture.documentVersion(),
        fixture.lineVersion());
    LogisticsExternalAttemptTestClaims.drainTransfer(claims, processor, jdbc);
    var inTransit = documents.get(fixture.documentId(), LogisticsDocumentType.TRANSFER);
    when(dependencies.preflightTransferArrival(
            fixture.documentId(), fixture.lineId(), ASSET, ORIGIN, DESTINATION))
        .thenReturn(
            new LogisticsDependencyGateway.TransferRepairArrivalPreflight(
                ACTIVE_REPAIR, true, List.of()));

    mvc.perform(
            get(
                    "/api/logistics/v1/transfers/{documentId}/lines/{lineId}/arrival-preflight",
                    fixture.documentId(),
                    fixture.lineId())
                .queryParam("expectedVersion", Long.toString(inTransit.version()))
                .queryParam(
                    "expectedLineVersion",
                    Long.toString(inTransit.lines().getFirst().version()))
                .with(manageActor(ORIGIN, DESTINATION)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transferId").value(fixture.documentId().toString()))
        .andExpect(jsonPath("$.lineId").value(fixture.lineId().toString()))
        .andExpect(jsonPath("$.activeRepairId").value(ACTIVE_REPAIR.toString()))
        .andExpect(jsonPath("$.priorityRequired").value(true))
        .andExpect(jsonPath("$.movementToShipmentAvailable").doesNotExist())
        .andExpect(jsonPath("$.missingQueueDefinitionIds").isEmpty());

    mvc.perform(
            get(
                    "/api/logistics/v1/transfers/{documentId}/lines/{lineId}/arrival-preflight",
                    fixture.documentId(),
                    fixture.lineId())
                .queryParam("expectedVersion", Long.toString(inTransit.version()))
                .queryParam(
                    "expectedLineVersion",
                    Long.toString(inTransit.lines().getFirst().version()))
                .with(manageActor(ORIGIN)))
        .andExpect(status().isForbidden());
  }

  @Test
  void rejectsStaleLineVersionsAndDoesNotApplyArrivalWhenContentsMismatched() {
    TransferFixture fixture = createTransfer();
    UUID leaseId = UUID.randomUUID();
    stubDeparture(fixture, leaseId);

    assertThatThrownBy(
            () ->
                documents.departTransferLine(
                    SUBJECT,
                    UUID.randomUUID(),
                    CORRELATION,
                    fixture.documentId(),
                    fixture.lineId(),
                    fixture.documentVersion(),
                    fixture.lineVersion() + 1))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("line version");

    documents.departTransferLine(
        SUBJECT,
        UUID.randomUUID(),
        CORRELATION,
        fixture.documentId(),
        fixture.lineId(),
        fixture.documentVersion(),
        fixture.lineVersion());
    LogisticsExternalAttemptTestClaims.drainTransfer(claims, processor, jdbc);
    var inTransit = documents.get(fixture.documentId(), LogisticsDocumentType.TRANSFER);
    UUID mediaId = UUID.randomUUID();
    when(dependencies.readRentalItemSnapshot(ASSET)).thenReturn(snapshot(8, ORIGIN, "IN_TRANSFER", 3));
    when(dependencies.validateMediaReferences(any(), any(), any(), any(), any()))
        .thenReturn(
            new LogisticsDependencyGateway.MediaValidation(
                LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER,
                fixture.documentId(),
                fixture.lineId(),
                DESTINATION,
                List.of(new LogisticsDependencyGateway.MediaReference(mediaId, 1))));

    documents.arriveTransferLine(
        SUBJECT,
        UUID.randomUUID(),
        CORRELATION,
        fixture.documentId(),
        fixture.lineId(),
        inTransit.version(),
        inTransit.lines().getFirst().version(),
        new ArriveTransferLineRequest(
            List.of(new MediaReferenceInput(mediaId, 1)), null));
    LogisticsExternalAttemptTestClaims.drainTransfer(claims, processor, jdbc);

    assertThat(documents.get(fixture.documentId(), LogisticsDocumentType.TRANSFER).state())
        .isEqualTo(LogisticsDocumentState.CONFLICT);
    verify(dependencies, never())
        .applyFencedEffect(
            any(),
            eq(LogisticsDependencyGateway.AssetEffect.TRANSFER_ARRIVE),
            any(),
            anyLong(),
            any(),
            anyLong(),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  void cancelsDraftTransferWithoutCreatingPreparationTasks() {
    TransferFixture fixture = createTransfer();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_task_reference where document_id=?",
                Long.class,
                fixture.documentId()))
        .isZero();

    var cancelled =
        documents.cancelTransfer(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            fixture.documentId(),
            documents.get(fixture.documentId(), LogisticsDocumentType.TRANSFER).version());
    assertThat(cancelled.response().state()).isEqualTo(LogisticsDocumentState.CANCELLED);
    assertThat(cancelled.response().lines())
        .singleElement()
        .extracting(line -> line.state())
        .isEqualTo(LogisticsLineState.CANCELLED);
  }

  @Test
  void createsAndCancelsTheLinkedFurnitureTaskForTheSelectedCabin() {
    LocalDate scheduledDate = OffsetDateTime.now(ZoneOffset.UTC).toLocalDate();
    UUID sourceBalanceId = UUID.randomUUID();
    when(dependencies.planCabinFurnitureMovements(
            eq(ORIGIN), eq(ASSET), any()))
        .thenReturn(
            new LogisticsDependencyGateway.CabinFurnitureMovementPlan(
                ASSET,
                "БЫТ-001",
                List.of(
                    new LogisticsDependencyGateway.CabinFurnitureMovementPlanLine(
                        EQUIPMENT,
                        "Стол",
                        sourceBalanceId,
                        ORIGIN,
                        null,
                        "STOCK",
                        4,
                        ORIGIN,
                        ASSET,
                        "CABIN_NON_RENTED",
                        2))));
    var created =
        documents.createTransfer(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateTransferRequest(
                ORIGIN,
                DESTINATION,
                scheduledDate,
                List.of(new TransferLineRequest(ASSET, 7)),
                List.of(
                    new TransferFurnitureReplacementRequest(
                        ASSET, List.of(new CabinFurnitureRequirement(EQUIPMENT, 2L))))));

    assertThat(created.response().equipmentMovementTaskId()).isNull();
    UUID furnitureTaskId =
        jdbc.queryForObject(
            "select equipment_movement_task_id from transfer_furniture_movement_task where document_id=?",
            UUID.class,
            created.response().id());
    var furnitureTask = equipmentTasks.get(furnitureTaskId);
    assertThat(furnitureTask.warehouseId()).isEqualTo(ORIGIN);
    assertThat(furnitureTask.state()).isEqualTo(EquipmentMovementTaskState.RESERVING);
    assertThat(furnitureTask.lines())
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.sourceWarehouseId()).isEqualTo(ORIGIN);
              assertThat(line.sourceLocationKind()).isEqualTo(EquipmentMovementLocationKind.STOCK);
              assertThat(line.targetWarehouseId()).isEqualTo(ORIGIN);
              assertThat(line.targetRentalItemId()).isEqualTo(ASSET);
              assertThat(line.targetLocationKind())
                  .isEqualTo(EquipmentMovementLocationKind.CABIN_NON_RENTED);
              assertThat(line.quantity()).isEqualTo(2L);
            });

    var cancelled =
        documents.cancelTransfer(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            created.response().id(),
            created.response().version());
    assertThat(cancelled.response().state()).isEqualTo(LogisticsDocumentState.CANCELLED);
    assertThat(equipmentTasks.get(furnitureTask.id()).state())
        .isEqualTo(EquipmentMovementTaskState.CANCELLING);
  }

  @Test
  void exposesTransferFurnitureReadinessOnlyWithReadAccessToBothWarehouses() throws Exception {
    TransferFixture fixture = createTransfer();

    mvc.perform(
            get("/api/logistics/v1/transfers/{documentId}/furniture-readiness", fixture.documentId())
                .with(readActor(ORIGIN, DESTINATION)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transferId").value(fixture.documentId().toString()))
        .andExpect(jsonPath("$.transferVersion").value(fixture.documentVersion()))
        .andExpect(jsonPath("$.state").value("NOT_REQUIRED"))
        .andExpect(jsonPath("$.tasks").isEmpty());

    mvc.perform(
            get("/api/logistics/v1/transfers/{documentId}/furniture-readiness", fixture.documentId())
                .with(readActor(ORIGIN)))
        .andExpect(status().isForbidden());
  }

  private TransferFixture createTransfer() {
    LocalDate scheduledDate = OffsetDateTime.now(ZoneOffset.UTC).toLocalDate();
    LogisticsDocumentService.CreateResult created =
        documents.createTransfer(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateTransferRequest(
                ORIGIN,
                DESTINATION,
                scheduledDate,
                List.of(new TransferLineRequest(ASSET, 7)),
                List.of()));
    assertThat(created.response().scheduledDate()).isEqualTo(scheduledDate);
    return new TransferFixture(
        created.response().id(),
        created.response().version(),
        created.response().lines().getFirst().id(),
        created.response().lines().getFirst().version(),
        scheduledDate);
  }

  private void stubDeparture(TransferFixture fixture, UUID leaseId) {
    when(dependencies.readWarehouseIdentity(ORIGIN))
        .thenReturn(new LogisticsDependencyGateway.WarehouseIdentity(ORIGIN, 2, true, "Europe/Moscow"));
    when(dependencies.readWarehouseIdentity(DESTINATION))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(DESTINATION, 4, true, "Asia/Vladivostok"));
    when(dependencies.prepareTransferDeparture(
            any(),
            eq(fixture.documentId()),
            eq(fixture.lineId()),
            eq(ASSET),
            eq(ORIGIN),
            eq(DESTINATION)))
        .thenReturn(
            new LogisticsDependencyGateway.TransferRepairDeparture(
                null, null, "FREE"));
    when(dependencies.preflightTransferArrival(
            eq(fixture.documentId()),
            eq(fixture.lineId()),
            eq(ASSET),
            eq(ORIGIN),
            eq(DESTINATION)))
        .thenReturn(
            new LogisticsDependencyGateway.TransferRepairArrivalPreflight(
                null, false, List.of()));
    when(dependencies.readRentalItemSnapshot(ASSET)).thenReturn(snapshot(7, ORIGIN, "FREE", 2));
    when(dependencies.acquireOperationLease(
            any(),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER),
            eq(ASSET),
            eq(7L),
            eq(fixture.documentId()),
            eq(fixture.lineId())))
        .thenReturn(activeLease(leaseId));
    when(dependencies.applyFencedEffect(
            any(),
            any(),
            any(),
            anyLong(),
            any(),
            anyLong(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(snapshot(8, ORIGIN, "IN_TRANSFER", 2));
  }

  private void stubActiveRepairDeparture(TransferFixture fixture, UUID leaseId) {
    when(dependencies.readWarehouseIdentity(ORIGIN))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(
                ORIGIN, 2, true, "Europe/Moscow"));
    when(dependencies.readWarehouseIdentity(DESTINATION))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(
                DESTINATION, 4, true, "Asia/Vladivostok"));
    when(dependencies.prepareTransferDeparture(
            any(),
            eq(fixture.documentId()),
            eq(fixture.lineId()),
            eq(ASSET),
            eq(ORIGIN),
            eq(DESTINATION)))
        .thenReturn(
            new LogisticsDependencyGateway.TransferRepairDeparture(
                ACTIVE_REPAIR, 10L, "REPAIR"));
    when(dependencies.readRentalItemSnapshot(ASSET))
        .thenReturn(snapshot(7, ORIGIN, "CAPITAL_REPAIR", 2));
    when(dependencies.acquireOperationLease(
            any(),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER),
            eq(ASSET),
            eq(7L),
            eq(fixture.documentId()),
            eq(fixture.lineId())))
        .thenReturn(activeLease(leaseId));
    when(dependencies.applyFencedEffect(
            any(),
            eq(LogisticsDependencyGateway.AssetEffect.TRANSFER_DEPART),
            eq(ASSET),
            eq(7L),
            eq(leaseId),
            eq(17L),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER),
            eq(fixture.documentId()),
            eq(fixture.lineId()),
            eq(null),
            eq("REPAIR")))
        .thenReturn(snapshot(8, ORIGIN, "IN_TRANSFER", 2));
  }

  private static LogisticsDependencyGateway.RentalItemSnapshot snapshot(
      long version, UUID warehouseId, String status, long quantity) {
    return new LogisticsDependencyGateway.RentalItemSnapshot(
        ASSET,
        version,
        warehouseId,
        status,
        List.of(new LogisticsDependencyGateway.EquipmentContent(EQUIPMENT, quantity)));
  }

  private static LogisticsDependencyGateway.OperationLease activeLease(UUID leaseId) {
    return new LogisticsDependencyGateway.OperationLease(
        leaseId,
        4,
        ASSET,
        17,
        "ACTIVE",
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
  }

  private static LogisticsDependencyGateway.OperationLease releasedLease(UUID leaseId) {
    return new LogisticsDependencyGateway.OperationLease(
        leaseId,
        5,
        ASSET,
        17,
        "RELEASED",
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
  }

  private static JwtRequestPostProcessor readActor(UUID... warehouseIds) {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
        .jwt(
            value ->
                value
                    .subject(SUBJECT.toString())
                    .claim("principal_type", "USER")
                    .claim("scope", "rwms.read")
                    .claim(
                        "warehouse_access",
                        java.util.Arrays.stream(warehouseIds)
                            .map(
                                warehouseId ->
                                    java.util.Map.of(
                                        "warehouseId", warehouseId.toString(), "level", "VIEW"))
                            .toList()));
  }

  private static JwtRequestPostProcessor manageActor(UUID... warehouseIds) {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
        .jwt(
            value ->
                value
                    .subject(SUBJECT.toString())
                    .claim("principal_type", "USER")
                    .claim("scope", "rwms.read rwms.write")
                    .claim(
                        "warehouse_access",
                        java.util.Arrays.stream(warehouseIds)
                            .map(
                                warehouseId ->
                                    java.util.Map.of(
                                        "warehouseId",
                                        warehouseId.toString(),
                                        "level",
                                        "MANAGE"))
                            .toList()));
  }

  private record TransferFixture(
      UUID documentId,
      long documentVersion,
      UUID lineId,
      long lineVersion,
      LocalDate scheduledDate) {}
}
