package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateShipmentRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateTransferRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnPickupRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferLineRequest;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.time.LocalDate;
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
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000a01");
  private static final UUID DESTINATION =
      UUID.fromString("00000000-0000-0000-0000-000000000a02");
  private static final UUID SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-000000000a03");
  private static final UUID DRIVER =
      UUID.fromString("00000000-0000-0000-0000-000000000a04");
  private static final UUID SHIPMENT_ASSET =
      UUID.fromString("00000000-0000-0000-0000-000000000a05");
  private static final UUID RETURN_ASSET =
      UUID.fromString("00000000-0000-0000-0000-000000000a06");
  private static final UUID TRANSFER_ASSET =
      UUID.fromString("00000000-0000-0000-0000-000000000a07");
  private static final UUID QUEUE_DEFINITION =
      UUID.fromString("00000000-0000-0000-0000-000000000a08");
  private static final UUID SECOND_SHIPMENT_ASSET =
      UUID.fromString("00000000-0000-0000-0000-000000000a09");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired LogisticsDocumentService documents;
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
    when(dependencies.readRentalItemSnapshot(SHIPMENT_ASSET))
        .thenReturn(snapshot(SHIPMENT_ASSET, "БТ-201"));
    when(dependencies.readRentalItemSnapshot(SECOND_SHIPMENT_ASSET))
        .thenReturn(snapshot(SECOND_SHIPMENT_ASSET, "БТ-204"));
    when(dependencies.readRentalItemSnapshot(RETURN_ASSET))
        .thenReturn(snapshot(RETURN_ASSET, "БТ-202"));
    when(dependencies.readRentalItemSnapshot(TRANSFER_ASSET))
        .thenReturn(snapshot(TRANSFER_ASSET, "БТ-203"));
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
        .containsEntry("unit_number", "БТ-202");
    assertThat(row(rows, "TRANSFER"))
        .containsEntry("driver_audience_mode", "WAREHOUSE_DRIVERS")
        .containsEntry("planned_driver_worker_id", null)
        .containsEntry("planned_driver_name_snapshot", null)
        .containsEntry("unit_number", "БТ-203");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from driver_logistics_task_member", Long.class))
        .isEqualTo(2L);
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

  private static Map<String, Object> row(
      List<Map<String, Object>> rows, String taskKind) {
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
