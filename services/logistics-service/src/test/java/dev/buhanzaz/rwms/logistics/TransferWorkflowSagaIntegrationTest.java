package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ArriveTransferLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CabinFurnitureRequirement;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateTransferRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.MediaReferenceInput;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferFurnitureReplacementRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferLineRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsLineState;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementLocationKind;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.TransferProcessor;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransferWorkflowSagaIntegrationTest {
  private static final UUID ORIGIN = UUID.fromString("00000000-0000-0000-0000-000000000901");
  private static final UUID DESTINATION = UUID.fromString("00000000-0000-0000-0000-000000000902");
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000903");
  private static final UUID CORRELATION = UUID.fromString("00000000-0000-0000-0000-000000000904");
  private static final UUID ASSET = UUID.fromString("00000000-0000-0000-0000-000000000905");
  private static final UUID EQUIPMENT = UUID.fromString("00000000-0000-0000-0000-000000000906");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired LogisticsDocumentService documents;
  @Autowired TransferProcessor processor;
  @Autowired EquipmentMovementTaskService equipmentTasks;
  @Autowired JdbcTemplate jdbc;

  @MockitoBean LogisticsDependencyGateway dependencies;

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
    org.mockito.Mockito.reset(dependencies);
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
    processor.processUntilIdle(fixture.documentId());

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
            eq(DESTINATION)))
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
        new ArriveTransferLineRequest(List.of(new MediaReferenceInput(mediaId, 3)));
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
    processor.processUntilIdle(fixture.documentId());

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
            eq(null));
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
    processor.processUntilIdle(fixture.documentId());
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
        new ArriveTransferLineRequest(List.of(new MediaReferenceInput(mediaId, 1))));
    processor.processUntilIdle(fixture.documentId());

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
            any());
  }

  @Test
  void cancelsRegisteredPreparationTasksBeforeClosingTheTransfer() {
    TransferFixture fixture = createTransfer();
    UUID taskId = UUID.randomUUID();
    when(dependencies.registerPreparationTask(any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.PreparationTask(
                    taskId,
                    1,
                    ORIGIN,
                    invocation.getArgument(1),
                    "ACTIVE",
                    null));
    jdbc.update(
        "update logistics_external_attempt set next_attempt_at=clock_timestamp() "
            + "where document_id=? and operation_type='TRANSFER_TASK_REGISTER'",
        fixture.documentId());
    assertThat(processor.processUntilIdle(fixture.documentId())).isOne();
    assertThat(
            jdbc.queryForObject(
                "select task_state from logistics_task_reference where line_id=?",
                String.class,
                fixture.lineId()))
        .isEqualTo("REGISTERED");
    verify(dependencies)
        .registerPreparationTask(eq(ORIGIN), any(), eq(Integer.valueOf(0)), isNull());

    when(dependencies.cancelPreparationTask(any(), eq(1L)))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.PreparationTask(
                    taskId,
                    2,
                    ORIGIN,
                    invocation.getArgument(0),
                    "CANCELLED",
                    null));

    var cancelling =
        documents.cancelTransfer(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            fixture.documentId(),
            documents.get(fixture.documentId(), LogisticsDocumentType.TRANSFER).version());
    assertThat(cancelling.response().state()).isEqualTo(LogisticsDocumentState.CANCELLING);

    processor.processUntilIdle(fixture.documentId());

    var cancelled = documents.get(fixture.documentId(), LogisticsDocumentType.TRANSFER);
    assertThat(cancelled.state()).isEqualTo(LogisticsDocumentState.CANCELLED);
    assertThat(cancelled.lines()).singleElement().extracting(line -> line.state()).isEqualTo(LogisticsLineState.CANCELLED);
    verify(dependencies).cancelPreparationTask(any(), eq(1L));
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
                        "FURN-001",
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
                "Driver A",
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
                null,
                scheduledDate,
                List.of(new TransferLineRequest(ASSET, 7)),
                List.of()));
    assertThat(created.response().scheduledDate()).isEqualTo(scheduledDate);
    assertThat(created.response().scheduledAt()).isNull();
    return new TransferFixture(
        created.response().id(),
        created.response().version(),
        created.response().lines().getFirst().id(),
        created.response().lines().getFirst().version());
  }

  private void stubDeparture(TransferFixture fixture, UUID leaseId) {
    when(dependencies.readWarehouseIdentity(ORIGIN))
        .thenReturn(new LogisticsDependencyGateway.WarehouseIdentity(ORIGIN, 2, true, "Europe/Moscow"));
    when(dependencies.readWarehouseIdentity(DESTINATION))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(DESTINATION, 4, true, "Asia/Vladivostok"));
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
            any()))
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

  private record TransferFixture(
      UUID documentId,
      long documentVersion,
      UUID lineId,
      long lineVersion) {}
}
