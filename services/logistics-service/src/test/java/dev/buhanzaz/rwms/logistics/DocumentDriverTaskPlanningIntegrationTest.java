package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateShipmentRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateTransferRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnCapitalRepairLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnPickupRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferPlanRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferResourceRepositionRequest;
import dev.buhanzaz.rwms.logistics.domain.TransferResourceRepositionMode;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanState;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.service.DriverTaskService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

/** Proves that logistics document commands persist durable driver intents atomically. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.driver-queue.relay-enabled=false",
      "rwms.logistics.return-registration.relay-enabled=false",
      "rwms.logistics.shipment.relay-enabled=false",
      "rwms.logistics.transfer.relay-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentDriverTaskPlanningIntegrationTest {
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000a01");
  private static final UUID DESTINATION = UUID.fromString("00000000-0000-0000-0000-000000000a02");
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000a03");
  private static final UUID DRIVER = UUID.fromString("00000000-0000-0000-0000-000000000a04");
  private static final UUID SHIPMENT_ASSET =
      UUID.fromString("00000000-0000-0000-0000-000000000a05");
  private static final UUID RETURN_ASSET = UUID.fromString("00000000-0000-0000-0000-000000000a06");
  private static final UUID TRANSFER_ASSET =
      UUID.fromString("00000000-0000-0000-0000-000000000a07");
  private static final UUID QUEUE_DEFINITION =
      UUID.fromString("00000000-0000-0000-0000-000000000a08");
  private static final UUID SECOND_SHIPMENT_ASSET =
      UUID.fromString("00000000-0000-0000-0000-000000000a09");
  private static final UUID FIRST_REPAIR =
      UUID.fromString("00000000-0000-0000-0000-000000000a10");
  private static final UUID SECOND_REPAIR =
      UUID.fromString("00000000-0000-0000-0000-000000000a11");
  private static final UUID FIRST_REPAIR_ASSET =
      UUID.fromString("00000000-0000-0000-0000-000000000a12");
  private static final UUID SECOND_REPAIR_ASSET =
      UUID.fromString("00000000-0000-0000-0000-000000000a13");

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired LogisticsDocumentService documents;
  @Autowired LogisticsWarehouseLifecycle lifecycle;
  @Autowired DriverTaskService driverTaskService;
  @Autowired JdbcTemplate jdbc;

  @MockitoBean LogisticsDependencyGateway dependencies;

  @BeforeEach
  void reset() {
    jdbc.execute(
        """
        truncate table
          shipment_task_settings,
          driver_logistics_task,
          logistics_document,
          event_stream_head,
          domain_event,
          aggregate_snapshot,
          projection_checkpoint,
          outbox_event
        cascade
        """);
    org.mockito.Mockito.reset(dependencies);
    when(dependencies.readWarehouseDriverQueue(WAREHOUSE))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseDriverQueue(
                WAREHOUSE, QUEUE_DEFINITION, UUID.randomUUID()));
    when(dependencies.readWarehouseDriverQueue(DESTINATION))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseDriverQueue(
                DESTINATION, QUEUE_DEFINITION, UUID.randomUUID()));
    when(dependencies.readRentalItemSnapshot(SHIPMENT_ASSET))
        .thenReturn(snapshot(SHIPMENT_ASSET, "БТ-201"));
    when(dependencies.readRentalItemSnapshot(SECOND_SHIPMENT_ASSET))
        .thenReturn(snapshot(SECOND_SHIPMENT_ASSET, "БТ-204"));
    when(dependencies.readRentalItemSnapshot(RETURN_ASSET))
        .thenReturn(snapshot(RETURN_ASSET, "БТ-202"));
    when(dependencies.readRentalItemSnapshot(TRANSFER_ASSET))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                TRANSFER_ASSET, 9, WAREHOUSE, "БТ-203", "FREE", List.of()));
    when(dependencies.readWarehouseIdentity(WAREHOUSE))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(
                WAREHOUSE, 1, true, "Санкт-Петербург", null, "Europe/Moscow"));
    when(dependencies.readWarehouseIdentity(DESTINATION))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(
                DESTINATION, 1, true, "Великий Новгород", null, "Europe/Moscow"));
    when(dependencies.readCabinMediaSnapshots(WAREHOUSE, List.of(TRANSFER_ASSET)))
        .thenReturn(
            List.of(
                new LogisticsDependencyGateway.CabinMediaSnapshot(
                    TRANSFER_ASSET, 0, List.of())));
  }

  @Test
  void documentCommandsCreateAssignedUnassignedAndWarehouseSharedTasksIdempotently() {
    jdbc.update(
        """
        insert into shipment_task_settings(
          warehouse_id, version, max_cabins_per_shipment_task, updated_by_subject_id, updated_at)
        values (?, 0, 2, ?, clock_timestamp())
        """,
        WAREHOUSE,
        SUBJECT);
    UUID shipmentKey = UUID.randomUUID();
    UUID shipmentCorrelation = UUID.randomUUID();
    var shipment =
        documents.createShipment(
            SUBJECT,
            shipmentKey,
            shipmentCorrelation,
            new CreateShipmentRequest(
                WAREHOUSE,
                null,
                null,
                "ООО Клиент",
                "Иван Петров",
                DRIVER,
                List.of(
                    new ShipmentLineRequest(SHIPMENT_ASSET, 7),
                    new ShipmentLineRequest(SECOND_SHIPMENT_ASSET, 7))));
    var shipmentReplay =
        documents.createShipment(
            SUBJECT,
            shipmentKey,
            shipmentCorrelation,
            new CreateShipmentRequest(
                WAREHOUSE,
                null,
                null,
                "ООО Клиент",
                "Иван Петров",
                DRIVER,
                List.of(
                    new ShipmentLineRequest(SHIPMENT_ASSET, 7),
                    new ShipmentLineRequest(SECOND_SHIPMENT_ASSET, 7))));

    assertThat(shipmentReplay.replayed()).isTrue();
    assertThat(shipmentReplay.response().id()).isEqualTo(shipment.response().id());
    assertThat(shipment.response().driverWorkerId()).isEqualTo(DRIVER);

    UUID returnCorrelation = UUID.randomUUID();
    var returnDraft =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            returnCorrelation,
            new CreateReturnRequest(
                WAREHOUSE,
                null,
                "Водитель из старого документа",
                null,
                List.of(new ReturnLineRequest(RETURN_ASSET, 8, "ООО Клиент"))));
    documents.registerReturn(
        SUBJECT,
        UUID.randomUUID(),
        returnCorrelation,
        returnDraft.response().id(),
        returnDraft.response().version(),
        new ReturnPickupRequest(
            "Водитель из старого документа", null, LocalDate.now().plusDays(1)));

    var transfer =
        documents.createTransfer(
            SUBJECT,
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateTransferRequest(
                WAREHOUSE,
                DESTINATION,
                LocalDate.now().plusDays(2),
                List.of(new TransferLineRequest(TRANSFER_ASSET, 9)),
                List.of()));
    assertThat(transfer.response().driverWorkerId()).isNull();
    assertThat(transfer.response().driverSnapshot()).isNull();

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            select task_kind, driver_audience_mode, planned_driver_worker_id,
                   planned_driver_name_snapshot, unit_number, priority, source_type,
                   client_snapshot, movement_comment
            from driver_logistics_task
            order by task_kind
            """);
    assertThat(rows).hasSize(3);
    assertThat(row(rows, "SHIPMENT"))
        .containsEntry("driver_audience_mode", "ASSIGNED_DRIVER")
        .containsEntry("planned_driver_worker_id", DRIVER)
        .containsEntry("planned_driver_name_snapshot", "Иван Петров")
        .containsEntry("unit_number", "2 бытовки")
        .containsEntry("priority", 3)
        .containsEntry("source_type", "LOGISTICS_DOCUMENT")
        .containsEntry("client_snapshot", "ООО Клиент")
        .containsEntry("movement_comment", "Клиент: ООО Клиент. Бытовки: БТ-201, БТ-204");
    assertThat(row(rows, "RETURN"))
        .containsEntry("driver_audience_mode", "UNASSIGNED")
        .containsEntry("planned_driver_worker_id", null)
        .containsEntry("planned_driver_name_snapshot", null)
        .containsEntry("unit_number", "1 бытовка")
        .containsEntry("source_type", "LOGISTICS_DOCUMENT");
    assertThat(row(rows, "TRANSFER"))
        .containsEntry("driver_audience_mode", "WAREHOUSE_DRIVERS")
        .containsEntry("planned_driver_worker_id", null)
        .containsEntry("planned_driver_name_snapshot", null)
        .containsEntry("unit_number", "1 бытовка")
        .containsEntry("source_type", "LOGISTICS_DOCUMENT");
    assertThat(jdbc.queryForObject("select count(*) from driver_logistics_task_member", Long.class))
        .isEqualTo(4L);
  }

  @Test
  void genericShipmentRejectsMoreCabinsThanTheWarehouseTaskCapBeforeCreatingAnyDocument() {
    jdbc.update(
        """
        insert into shipment_task_settings(
          warehouse_id, version, max_cabins_per_shipment_task, updated_by_subject_id, updated_at)
        values (?, 0, 1, ?, clock_timestamp())
        """,
        WAREHOUSE,
        SUBJECT);

    assertThatThrownBy(
            () ->
                documents.createShipment(
                    SUBJECT,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new CreateShipmentRequest(
                        WAREHOUSE,
                        null,
                        null,
                        "ООО Клиент",
                        "Иван Петров",
                        DRIVER,
                        List.of(
                            new ShipmentLineRequest(SHIPMENT_ASSET, 7),
                            new ShipmentLineRequest(SECOND_SHIPMENT_ASSET, 7)))))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("нельзя выбрать больше 1 бытовок");

    assertThat(jdbc.queryForObject("select count(*) from logistics_document", Long.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from driver_logistics_task", Long.class))
        .isZero();
  }

  @Test
  void plannedOutboundCreatesOneIdempotentReverseCapitalRepairTransferForTheAssignedDriver() {
    jdbc.update(
        """
        insert into shipment_task_settings(
          warehouse_id, version, max_cabins_per_shipment_task, updated_by_subject_id, updated_at)
        values (?, 0, 2, ?, clock_timestamp())
        """,
        DESTINATION,
        SUBJECT);
    UUID firstPhoto = UUID.randomUUID();
    OffsetDateTime departure =
        OffsetDateTime.of(2026, 9, 14, 8, 30, 0, 0, ZoneOffset.ofHours(3));
    when(dependencies.readCapitalRepair(FIRST_REPAIR))
        .thenReturn(
            new LogisticsDependencyGateway.CapitalRepair(
                FIRST_REPAIR, FIRST_REPAIR_ASSET, DESTINATION, 2, null, 4));
    when(dependencies.readCapitalRepair(SECOND_REPAIR))
        .thenReturn(
            new LogisticsDependencyGateway.CapitalRepair(
                SECOND_REPAIR, SECOND_REPAIR_ASSET, DESTINATION, 3, null, 6));
    when(dependencies.readRentalItemSnapshot(FIRST_REPAIR_ASSET))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                FIRST_REPAIR_ASSET, 11, DESTINATION, "172", "REPAIR", List.of()));
    when(dependencies.readRentalItemSnapshot(SECOND_REPAIR_ASSET))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                SECOND_REPAIR_ASSET, 12, DESTINATION, "311", "CAPITAL_REPAIR", List.of()));
    when(dependencies.listWarehouseDrivers(WAREHOUSE, departure, false))
        .thenReturn(List.of(new LogisticsDependencyGateway.WarehouseDriverIdentity(DRIVER, "Петров")));
    when(
            dependencies.readCabinMediaSnapshots(
                DESTINATION, List.of(FIRST_REPAIR_ASSET, SECOND_REPAIR_ASSET)))
        .thenReturn(
            List.of(
                new LogisticsDependencyGateway.CabinMediaSnapshot(
                    FIRST_REPAIR_ASSET,
                    1,
                    List.of(
                        new LogisticsDependencyGateway.CabinMediaPhoto(
                            firstPhoto, 2, 0, List.of("thumbnail")))),
                new LogisticsDependencyGateway.CabinMediaSnapshot(
                    SECOND_REPAIR_ASSET, 0, List.of())));
    TransferResourceRepositionRequest none =
        new TransferResourceRepositionRequest(null, TransferResourceRepositionMode.NONE, null);
    TransferPlanRequest plan =
        new TransferPlanRequest(
            departure,
            departure.plusHours(4),
            "Забрать бытовки капитального ремонта",
            DRIVER,
            null,
            none,
            none,
            List.of(),
            List.of());
    CreateTransferRequest request =
        new CreateTransferRequest(
            WAREHOUSE,
            DESTINATION,
            LocalDate.of(2026, 9, 14),
            List.of(),
            List.of(),
            plan,
            List.of(
                new ReturnCapitalRepairLineRequest(FIRST_REPAIR, FIRST_REPAIR_ASSET, 11),
                new ReturnCapitalRepairLineRequest(SECOND_REPAIR, SECOND_REPAIR_ASSET, 12)));
    UUID key = UUID.randomUUID();
    UUID correlation = UUID.randomUUID();

    var created = documents.createTransfer(SUBJECT, key, correlation, request);
    var replay = documents.createTransfer(SUBJECT, key, correlation, request);

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response().id()).isEqualTo(created.response().id());
    assertThat(replay.response().linkedReturnTransferId())
        .isEqualTo(created.response().linkedReturnTransferId())
        .isNotNull();
    assertThat(jdbc.queryForObject("select count(*) from logistics_document", Long.class))
        .isEqualTo(2L);
    assertThat(
            jdbc.queryForList(
                """
                select line.asset_id
                from logistics_document_line line
                join logistics_document document on document.id=line.document_id
                where document.id=?
                order by line.line_number
                """,
                UUID.class,
                created.response().linkedReturnTransferId()))
        .containsExactly(FIRST_REPAIR_ASSET, SECOND_REPAIR_ASSET);
    Map<String, Object> reverseTask =
        jdbc.queryForMap(
            """
            select driver_audience_mode,planned_driver_worker_id,warehouse_id,worker_content_json
            from driver_logistics_task
            where source_id=?
            """,
            created.response().linkedReturnTransferId());
    assertThat(reverseTask)
        .containsEntry("driver_audience_mode", "ASSIGNED_DRIVER")
        .containsEntry("planned_driver_worker_id", DRIVER)
        .containsEntry("warehouse_id", DESTINATION);
    assertThat(reverseTask.get("worker_content_json").toString())
        .contains("172", "311", firstPhoto.toString(), "Великий Новгород", "Санкт-Петербург");
    assertThatThrownBy(
            () -> documents.createTransfer(SUBJECT, UUID.randomUUID(), UUID.randomUUID(), request))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("уже выбрана");
    assertThat(jdbc.queryForObject("select count(*) from logistics_document", Long.class))
        .isEqualTo(2L);
  }

  @Test
  void emptyRouteDraftIsPersistedWhileConfirmationRequiresCargoOrResourceIntent() {
    TransferResourceRepositionRequest none =
        new TransferResourceRepositionRequest(null, TransferResourceRepositionMode.NONE, null);
    OffsetDateTime departure =
        OffsetDateTime.of(2026, 9, 16, 8, 30, 0, 0, ZoneOffset.ofHours(3));
    CreateTransferRequest emptyRequest =
        new CreateTransferRequest(
            WAREHOUSE,
            DESTINATION,
            departure.toLocalDate(),
            List.of(),
            List.of(),
            new TransferPlanRequest(
                departure,
                departure.plusHours(4),
                null,
                null,
                null,
                none,
                none,
                List.of(),
                List.of()));
    var emptyDraft =
        documents.createTransfer(SUBJECT, UUID.randomUUID(), UUID.randomUUID(), emptyRequest);
    UUID emptyConfirmKey = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                documents.confirmTransferPlan(
                    SUBJECT,
                    emptyConfirmKey,
                    UUID.randomUUID(),
                    emptyDraft.response().id(),
                    emptyDraft.response().version(),
                    confirmationAdmission(emptyConfirmKey)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("planned cargo lines");

    CreateTransferRequest resourceRequest =
        new CreateTransferRequest(
            WAREHOUSE,
            DESTINATION,
            departure.toLocalDate(),
            List.of(),
            List.of(),
            new TransferPlanRequest(
                departure,
                departure.plusHours(4),
                null,
                DRIVER,
                null,
                none,
                none,
                List.of(),
                List.of()));
    var resourceDraft =
        documents.createTransfer(SUBJECT, UUID.randomUUID(), UUID.randomUUID(), resourceRequest);
    UUID resourceConfirmKey = UUID.randomUUID();
    var confirmed =
        documents.confirmTransferPlan(
            SUBJECT,
            resourceConfirmKey,
            UUID.randomUUID(),
            resourceDraft.response().id(),
            resourceDraft.response().version(),
            confirmationAdmission(resourceConfirmKey));

    assertThat(confirmed.response().state()).isEqualTo(TransferPlanState.CONFIRMED);
    assertThat(confirmed.response().totalCabinCount()).isZero();
  }

  @Test
  void activeCapitalToProductionTaskFencesTheSameCabinFromAReverseTransfer() {
    LocalDate scheduled = LocalDate.of(2026, 9, 17);
    when(dependencies.readCapitalRepair(FIRST_REPAIR))
        .thenReturn(
            new LogisticsDependencyGateway.CapitalRepair(
                FIRST_REPAIR, FIRST_REPAIR_ASSET, DESTINATION, 2, null, 4));
    when(dependencies.readRentalItemSnapshot(FIRST_REPAIR_ASSET))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                FIRST_REPAIR_ASSET, 11, DESTINATION, "172", "REPAIR", List.of()));
    when(dependencies.readCabinMediaSnapshots(DESTINATION, List.of(FIRST_REPAIR_ASSET)))
        .thenReturn(
            List.of(
                new LogisticsDependencyGateway.CabinMediaSnapshot(
                    FIRST_REPAIR_ASSET, 0, List.of())));
    UUID taskKey = UUID.randomUUID();
    driverTaskService.createCapitalMovement(
        SUBJECT,
        taskKey,
        DESTINATION,
        FIRST_REPAIR,
        DriverTaskPlanningMode.FIXED_DATE,
        scheduled,
        lifecycle.disabledTicket(
            SUBJECT,
            "CREATE_DRIVER_LOGISTICS_TASK",
            taskKey,
            List.of(
                new AdmissionRequirement(
                    DESTINATION, WarehouseOperationDirection.OUTGOING))));
    TransferResourceRepositionRequest none =
        new TransferResourceRepositionRequest(null, TransferResourceRepositionMode.NONE, null);
    OffsetDateTime departure =
        OffsetDateTime.of(2026, 9, 17, 8, 30, 0, 0, ZoneOffset.ofHours(3));
    CreateTransferRequest request =
        new CreateTransferRequest(
            WAREHOUSE,
            DESTINATION,
            scheduled,
            List.of(),
            List.of(),
            new TransferPlanRequest(
                departure,
                departure.plusHours(4),
                null,
                DRIVER,
                null,
                none,
                none,
                List.of(),
                List.of()),
            List.of(new ReturnCapitalRepairLineRequest(FIRST_REPAIR, FIRST_REPAIR_ASSET, 11)));

    assertThatThrownBy(
            () -> documents.createTransfer(SUBJECT, UUID.randomUUID(), UUID.randomUUID(), request))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("активное задание возврата");
    assertThat(jdbc.queryForObject("select count(*) from logistics_document", Long.class)).isZero();
  }

  private LogisticsWarehouseLifecycle.AdmissionTicket confirmationAdmission(UUID key) {
    return lifecycle.disabledTicket(
        SUBJECT,
        "CONFIRM_TRANSFER_PLAN",
        key,
        List.of(
            new AdmissionRequirement(WAREHOUSE, WarehouseOperationDirection.OUTGOING),
            new AdmissionRequirement(DESTINATION, WarehouseOperationDirection.INCOMING)));
  }

  private static Map<String, Object> row(List<Map<String, Object>> rows, String taskKind) {
    return rows.stream()
        .filter(row -> taskKind.equals(row.get("task_kind")))
        .findFirst()
        .orElseThrow();
  }

  private static LogisticsDependencyGateway.RentalItemSnapshot snapshot(
      UUID assetId, String unitNumber) {
    return new LogisticsDependencyGateway.RentalItemSnapshot(
        assetId, 7, WAREHOUSE, unitNumber, "FREE", List.of());
  }
}
