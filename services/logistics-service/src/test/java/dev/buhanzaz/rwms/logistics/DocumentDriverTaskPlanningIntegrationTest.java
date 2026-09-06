package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.DesiredDeliveryWindow;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentSource;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderQuotedPrice;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import dev.buhanzaz.rwms.logistics.order.repository.OrderClientRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderPlanningIntegrationService;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ApplyPlanningAssignmentsRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningAssignmentRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftPlanRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftVehicleConfiguration;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverShiftVehicleRequest;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
  @Autowired RentalOrderPlanningIntegrationService planning;
  @Autowired RentalOrderRepository rentalOrders;
  @Autowired OrderClientRepository orderClients;
  @Autowired RentalOrderUnitTermRepository rentalTerms;
  @Autowired PlatformTransactionManager transactionManager;

  @MockitoBean LogisticsDependencyGateway dependencies;

  @BeforeEach
  void reset() {
    jdbc.execute(
        """
        truncate table
          order_client,
          logistics_warehouse_admission_intent,
          warehouse_operation_mark_outbox,
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

  @ParameterizedTest
  @CsvSource({"1, TRANSIENT", "2, TRANSIENT", "1, PERMANENT_REJECTION", "2, PERMANENT_REJECTION"})
  void failedShiftRegistrationLeavesNoShipmentEffectsAndTheSameBatchCanRetry(
      int failedRegistration, LogisticsDependencyException.FailureKind failureKind) {
    LocalDate firstDate = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(3);
    RentalOrder order = paidPlanningOrder(firstDate);
    UUID batchKey = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    ApplyPlanningAssignmentsRequest request =
        new ApplyPlanningAssignmentsRequest(
            WAREHOUSE,
            planId,
            1L,
            List.of(
                new PlanningAssignmentRequest(
                    order.getId(),
                    order.getVersion(),
                    firstDate,
                    DRIVER,
                    "Driver",
                    List.of(SHIPMENT_ASSET)),
                new PlanningAssignmentRequest(
                    order.getId(),
                    order.getVersion(),
                    firstDate.plusDays(1),
                    DRIVER,
                    "Driver",
                    List.of(SECOND_SHIPMENT_ASSET))),
            List.of(
                planningShift(planId, firstDate), planningShift(planId, firstDate.plusDays(1))));
    Map<String, Long> baselineEffects = planningEffects();
    // V37 records the fixture order's first warehouse operation before planner apply begins.
    assertThat(baselineEffects)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "logistics_document", 0L,
                "logistics_document_line", 0L,
                "driver_logistics_task", 0L,
                "logistics_idempotency_record", 0L,
                "logistics_warehouse_admission_intent", 0L,
                "warehouse_operation_mark_outbox", 1L,
                "logistics_external_attempt", 0L,
                "domain_event", 0L,
                "outbox_event", 0L));
    List<Map<String, Object>> originalTerms = planningTerms(order.getId());
    AtomicReference<Map<String, Long>> expectedEffects = new AtomicReference<>(baselineEffects);
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean fail = new AtomicBoolean(true);
    Map<UUID, UUID> registrationKeys = new LinkedHashMap<>();
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(planningEffects()).isEqualTo(expectedEffects.get());
              UUID key = invocation.getArgument(0);
              UUID shiftId = invocation.getArgument(1);
              UUID previousKey = registrationKeys.putIfAbsent(shiftId, key);
              if (previousKey != null) assertThat(key).isEqualTo(previousKey);
              if (calls.incrementAndGet() == failedRegistration && fail.get()) {
                throw new LogisticsDependencyException(failureKind, "registration failed");
              }
              return null;
            })
        .when(dependencies)
        .registerDriverShiftPlan(any(), any(), any());

    assertThatThrownBy(() -> planning.apply(batchKey, request))
        .isInstanceOfSatisfying(
            LogisticsDependencyException.class,
            failure -> assertThat(failure.kind()).isEqualTo(failureKind));
    assertThat(calls.get()).isEqualTo(failedRegistration);
    assertThat(planningEffects()).isEqualTo(baselineEffects);
    assertThat(planningTerms(order.getId())).isEqualTo(originalTerms);

    fail.set(false);
    var created = planning.apply(batchKey, request);
    assertThat(created.rejected()).isEmpty();
    assertThat(created.applied())
        .hasSize(2)
        .allSatisfy(part -> assertThat(part.replayed()).isFalse());
    Map<String, Long> committedEffects = planningEffects();
    assertThat(committedEffects)
        .containsEntry("logistics_document", 2L)
        .containsEntry("logistics_document_line", 2L)
        .containsEntry("driver_logistics_task", 2L)
        .containsEntry("logistics_idempotency_record", 2L);
    assertThat(planningTerms(order.getId()))
        .allSatisfy(term -> assertThat(term.get("rental_shipment_id")).isNotNull());

    // A later real order transition must not invalidate an already committed exact shipment retry.
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              RentalOrder cancelled = rentalOrders.findForUpdate(order.getId()).orElseThrow();
              cancelled.cancelSavedCustomerBooking();
              rentalOrders.saveAndFlush(cancelled);
            });
    expectedEffects.set(committedEffects);
    var replay = planning.apply(batchKey, request);
    assertThat(replay.rejected()).isEmpty();
    assertThat(replay.applied())
        .hasSize(2)
        .allSatisfy(part -> assertThat(part.replayed()).isTrue());
    assertThat(replay.applied())
        .extracting(part -> part.documentId())
        .containsExactlyElementsOf(
            created.applied().stream().map(part -> part.documentId()).toList());
    assertThat(replay.applied())
        .extracting(part -> part.externalTaskId())
        .containsExactlyElementsOf(
            created.applied().stream().map(part -> part.externalTaskId()).toList());
    assertThat(planningEffects()).isEqualTo(committedEffects);
    assertThat(registrationKeys).hasSize(2);
    assertThat(calls.get()).isEqualTo(failedRegistration + 4);
  }

  private RentalOrder paidPlanningOrder(LocalDate date) {
    OrderClient client =
        orderClients.saveAndFlush(
            OrderClient.create(
                ClientType.LEGAL_ENTITY,
                "Planning client",
                "planning client",
                "+79990000001",
                "+79990000001",
                null,
                null,
                "Contact",
                SUBJECT,
                "Manager",
                null,
                null,
                List.of(),
                SUBJECT,
                UUID.randomUUID(),
                "a".repeat(64)));
    RentalOrder order =
        RentalOrder.create(
            "ORD-990001",
            client,
            SUBJECT,
            "Manager",
            SUBJECT,
            "Manager",
            "RENTAL_MANAGER",
            "+79990000001",
            null,
            UUID.randomUUID(),
            "b".repeat(64));
    order.selectWarehouse(WAREHOUSE);
    order.replaceClientDeliveryDetails(
        "Delivery address", new BigDecimal("55.75"), new BigDecimal("37.62"), List.of());
    order.replaceClientDesiredDeliveryWindows(
        List.of(
            DesiredDeliveryWindow.create(date, date),
            DesiredDeliveryWindow.create(date.plusDays(1), date.plusDays(1))));
    order.saveForFulfillment();
    OffsetDateTime timestamp =
        jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    order.startPaymentReservation(timestamp);
    order.confirmPayment(RentalOrderPaymentSource.MANAGER_CONFIRMATION, SUBJECT, timestamp);
    RentalOrder saved = rentalOrders.saveAndFlush(order);
    rentalTerms.saveAllAndFlush(
        List.of(
            RentalOrderUnitTerm.create(
                saved, SHIPMENT_ASSET, 2, new RentalOrderQuotedPrice(0L, 1000L)),
            RentalOrderUnitTerm.create(
                saved, SECOND_SHIPMENT_ASSET, 2, new RentalOrderQuotedPrice(0L, 1000L))));
    when(dependencies.readOrderUnits(saved.getId()))
        .thenReturn(
            List.of(
                planningReservation(saved.getId(), SHIPMENT_ASSET),
                planningReservation(saved.getId(), SECOND_SHIPMENT_ASSET)));
    when(dependencies.productionReady()).thenReturn(true);
    when(dependencies.warehouseAdmission(WAREHOUSE, WarehouseOperationDirection.OUTGOING))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseOperationAdmission(
                WAREHOUSE,
                1,
                LogisticsDependencyGateway.WarehouseLifecycleState.ACTIVE,
                WarehouseOperationDirection.OUTGOING,
                true));
    when(dependencies.warehouseTimeZoneAt(eq(WAREHOUSE), any()))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseTimeZone(
                WAREHOUSE, "Europe/Moscow", timestamp.minusDays(1)));
    return saved;
  }

  private static LogisticsDependencyGateway.OrderUnitReservation planningReservation(
      UUID orderId, UUID unitId) {
    OffsetDateTime timestamp = OffsetDateTime.now();
    return new LogisticsDependencyGateway.OrderUnitReservation(
        UUID.randomUUID(),
        0,
        orderId,
        unitId,
        WAREHOUSE,
        "ACTIVE",
        SUBJECT,
        "RENTAL_MANAGER",
        timestamp,
        null,
        false,
        new LogisticsDependencyGateway.OrderRentalItem(
            unitId,
            7,
            WAREHOUSE,
            "CAB-" + unitId,
            "FREE",
            "RENT",
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            timestamp,
            timestamp));
  }

  private static PlanningDriverShiftPlanRequest planningShift(UUID planId, LocalDate date) {
    return new PlanningDriverShiftPlanRequest(
        UUID.randomUUID(),
        planId,
        1L,
        WAREHOUSE,
        DRIVER,
        "Driver",
        date,
        new PlanningDriverShiftVehicleRequest(
            UUID.randomUUID(),
            "MAN",
            "A123AA",
            "FLATBED_CRANE",
            "MAN",
            "TGS",
            PlanningDriverShiftVehicleConfiguration.TRUCK,
            null),
        null,
        1,
        1000L);
  }

  private List<Map<String, Object>> planningTerms(UUID orderId) {
    return jdbc.queryForList(
        "select * from rental_order_unit_term where order_id=? order by rental_item_id", orderId);
  }

  private Map<String, Long> planningEffects() {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String table :
        List.of(
            "logistics_document",
            "logistics_document_line",
            "driver_logistics_task",
            "logistics_idempotency_record",
            "logistics_warehouse_admission_intent",
            "warehouse_operation_mark_outbox",
            "logistics_external_attempt",
            "domain_event",
            "outbox_event")) {
      counts.put(table, jdbc.queryForObject("select count(*) from " + table, Long.class));
    }
    return counts;
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
