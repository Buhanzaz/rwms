package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateShipmentRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.EquipmentAllocationRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentPlanRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.repository.OrderClientRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.ShipmentProcessor;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShipmentWorkflowSagaIntegrationTest {
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000801");
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000802");
  private static final UUID CORRELATION = UUID.fromString("00000000-0000-0000-0000-000000000803");
  private static final UUID ASSET = UUID.fromString("00000000-0000-0000-0000-000000000804");
  private static final UUID EQUIPMENT = UUID.fromString("00000000-0000-0000-0000-000000000805");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired LogisticsDocumentService documents;
  @Autowired ShipmentProcessor processor;
  @Autowired JdbcTemplate jdbc;
  @Autowired OrderClientRepository clients;
  @Autowired RentalOrderRepository orders;

  @MockitoBean LogisticsDependencyGateway dependencies;

  @BeforeEach
  void reset() {
    jdbc.execute(
        """
        truncate table
          logistics_document,
          rental_order_command_receipt,
          rental_order_audit_event,
          rental_order,
          order_client,
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
  void preparesAndConfirmsShipmentOnlyAfterTaskHoldAndFencedAssetEffects() {
    ShipmentLineRequest line =
        new ShipmentLineRequest(
            ASSET, 7, List.of(new EquipmentAllocationRequest(EQUIPMENT, 2, 4)));
    LogisticsDocumentService.CreateResult created =
        documents.createShipment(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateShipmentRequest(WAREHOUSE, "Party A", "Driver A", List.of(line)));
    UUID documentId = created.response().id();
    UUID lineId = created.response().lines().getFirst().id();
    UUID leaseId = UUID.randomUUID();
    UUID holdId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();

    when(dependencies.readRentalItemSnapshot(ASSET)).thenReturn(snapshot(7, "FREE"));
    when(dependencies.acquireOperationLease(
            any(),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(ASSET),
            eq(7L),
            eq(documentId),
            eq(lineId)))
        .thenReturn(activeLease(leaseId));
    when(dependencies.acquireEquipmentHold(
            any(), any(), any(), any(), any(), anyLong(), anyLong()))
        .thenReturn(activeHold(holdId));
    when(dependencies.registerPreparationTask(eq(WAREHOUSE), any(), eq(0), eq(null)))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.PreparationTask(
                    taskId,
                    1,
                    WAREHOUSE,
                    invocation.getArgument(1),
                    "ACTIVE",
                    null));

    assertThat(created.response().state()).isEqualTo(LogisticsDocumentState.PREPARING);

    processor.processUntilIdle(documentId);
    long awaitingVersion = documents.get(documentId, LogisticsDocumentType.SHIPMENT).version();
    assertThat(
            jdbc.queryForList(
                "select operation_type || ':' || result from logistics_external_attempt order by operation_type",
                String.class))
        .contains(
            "SHIPMENT_ASSET_SNAPSHOT:CONFIRMED",
            "SHIPMENT_ASSET_LEASE_ACQUIRE:CONFIRMED",
            "SHIPMENT_TASK_REGISTER:CONFIRMED");
    assertThat(documents.get(documentId, LogisticsDocumentType.SHIPMENT).state())
        .isEqualTo(LogisticsDocumentState.AWAITING_CONFIRMATION);

    when(dependencies.readPreparationTask(any()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.PreparationTask(
                    taskId,
                    2,
                    WAREHOUSE,
                    invocation.getArgument(0),
                    "DONE",
                    OffsetDateTime.now(ZoneOffset.UTC)));
    when(dependencies.commandEquipmentHold(
            any(),
            eq(LogisticsDependencyGateway.EquipmentHoldAction.COMMIT),
            eq(holdId),
            eq(1L),
            eq(documentId),
            eq(lineId)))
        .thenReturn(
            new LogisticsDependencyGateway.EquipmentHold(
                holdId,
                2,
                "COMMITTED",
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5),
                OffsetDateTime.now(ZoneOffset.UTC)));
    when(dependencies.applyFencedEffect(
            any(),
            eq(LogisticsDependencyGateway.AssetEffect.SHIPMENT_CONFIRM),
            eq(ASSET),
            eq(7L),
            eq(leaseId),
            eq(11L),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(documentId),
            eq(lineId),
            eq(null)))
        .thenReturn(snapshot(8, "RENTED"));
    when(dependencies.releaseOperationLease(
            any(),
            eq(leaseId),
            eq(3L),
            eq(11L),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(documentId),
            eq(lineId)))
        .thenReturn(releasedLease(leaseId));

    documents.confirmShipmentPreparation(
        SUBJECT, UUID.randomUUID(), CORRELATION, documentId, awaitingVersion);
    processor.processUntilIdle(documentId);

    assertThat(documents.get(documentId, LogisticsDocumentType.SHIPMENT).state())
        .isEqualTo(LogisticsDocumentState.SHIPPED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_equipment_hold_reference where hold_state='COMMITTED'", Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_guard where guard_state='RELEASED'", Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where event_type='logistics.shipment.preparation-confirmed.v1'",
                Long.class))
        .isOne();
    verify(dependencies)
        .commandEquipmentHold(
            any(),
            eq(LogisticsDependencyGateway.EquipmentHoldAction.COMMIT),
            eq(holdId),
            eq(1L),
            eq(documentId),
            eq(lineId));
    verify(dependencies)
        .acquireEquipmentHold(
            any(), eq(EQUIPMENT), eq(WAREHOUSE), eq(documentId), eq(lineId), eq(2L), eq(4L));
  }

  @Test
  void shippedSavedOrderBecomesFulfilledAndGetsOneDateLessReturn() {
    OrderClient client =
        clients.saveAndFlush(
            OrderClient.create(
                ClientType.LEGAL_ENTITY,
                "Party linked",
                "party linked",
                SUBJECT,
                UUID.randomUUID(),
                "0".repeat(64)));
    RentalOrder order =
        RentalOrder.create(
            "ORD-999999",
            client,
            SUBJECT,
            "Dispatcher",
            SUBJECT,
            "Dispatcher",
            "RENTAL_MANAGER",
            UUID.randomUUID(),
            "1".repeat(64));
    order.selectWarehouse(WAREHOUSE);
    order.saveForFulfillment();
    order = orders.saveAndFlush(order);
    LogisticsDependencyGateway.OrderUnitReservation reservation = reservation(order.getId());

    var created =
        documents.createRentalOrderShipmentDraft(
            SUBJECT, CORRELATION, order, List.of(reservation));
    UUID documentId = created.id();
    UUID lineId = created.lines().getFirst().id();
    UUID leaseId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();

    when(dependencies.readRentalItemSnapshot(ASSET)).thenReturn(snapshot(7, "FREE"));
    when(dependencies.acquireOperationLease(
            any(),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(ASSET),
            eq(7L),
            eq(documentId),
            eq(lineId),
            eq(order.getId())))
        .thenReturn(activeLease(leaseId));
    when(dependencies.registerPreparationTask(eq(WAREHOUSE), any(), eq(0), eq(null)))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.PreparationTask(
                    taskId,
                    1,
                    WAREHOUSE,
                    invocation.getArgument(1),
                    "ACTIVE",
                    null));

    documents.planShipment(
        SUBJECT,
        UUID.randomUUID(),
        CORRELATION,
        documentId,
        created.version(),
        new ShipmentPlanRequest(
            "Driver linked", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)));
    processor.processUntilIdle(documentId);
    long awaitingVersion = documents.get(documentId, LogisticsDocumentType.SHIPMENT).version();

    when(dependencies.readPreparationTask(any()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.PreparationTask(
                    taskId,
                    2,
                    WAREHOUSE,
                    invocation.getArgument(0),
                    "DONE",
                    OffsetDateTime.now(ZoneOffset.UTC)));
    when(dependencies.applyFencedEffect(
            any(),
            eq(LogisticsDependencyGateway.AssetEffect.SHIPMENT_CONFIRM),
            eq(ASSET),
            eq(7L),
            eq(leaseId),
            eq(11L),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(documentId),
            eq(lineId),
            eq(null)))
        .thenReturn(snapshot(8, "RENTED"));
    when(dependencies.releaseOperationLease(
            any(),
            eq(leaseId),
            eq(3L),
            eq(11L),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(documentId),
            eq(lineId)))
        .thenReturn(releasedLease(leaseId));

    documents.confirmShipmentPreparation(
        SUBJECT, UUID.randomUUID(), CORRELATION, documentId, awaitingVersion);
    processor.processUntilIdle(documentId);

    assertThat(
            jdbc.queryForObject(
                "select status from rental_order where id=?", String.class, order.getId()))
        .isEqualTo("FULFILLED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_order_audit_event where order_id=? and event_type='ORDER_FULFILLED'",
                Long.class,
                order.getId()))
        .isOne();
    assertThat(
            jdbc.queryForMap(
                "select state, driver_snapshot, scheduled_at, rental_order_id from logistics_document where document_type='RETURN' and rental_order_id=?",
                order.getId()))
        .containsEntry("state", "DRAFT")
        .containsEntry("rental_order_id", order.getId())
        .containsEntry("driver_snapshot", null)
        .containsEntry("scheduled_at", null);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document_line line join logistics_document document on document.id=line.document_id where document.document_type='RETURN' and document.rental_order_id=? and line.asset_id=?",
                Long.class,
                order.getId(),
                ASSET))
        .isOne();
  }

  private static LogisticsDependencyGateway.OrderUnitReservation reservation(UUID orderId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return new LogisticsDependencyGateway.OrderUnitReservation(
        UUID.randomUUID(),
        0,
        orderId,
        ASSET,
        WAREHOUSE,
        "ACTIVE",
        SUBJECT,
        "RENTAL_MANAGER",
        now,
        null,
        false,
        new LogisticsDependencyGateway.OrderRentalItem(
            ASSET,
            7,
            WAREHOUSE,
            "CAB-801",
            "FREE",
            "RENT",
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            now,
            now));
  }

  private static LogisticsDependencyGateway.RentalItemSnapshot snapshot(long version, String status) {
    return new LogisticsDependencyGateway.RentalItemSnapshot(
        ASSET,
        version,
        WAREHOUSE,
        status,
        List.of(new LogisticsDependencyGateway.EquipmentContent(EQUIPMENT, 2)));
  }

  private static LogisticsDependencyGateway.OperationLease activeLease(UUID leaseId) {
    return new LogisticsDependencyGateway.OperationLease(
        leaseId,
        3,
        ASSET,
        11,
        "ACTIVE",
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
  }

  private static LogisticsDependencyGateway.OperationLease releasedLease(UUID leaseId) {
    return new LogisticsDependencyGateway.OperationLease(
        leaseId,
        4,
        ASSET,
        11,
        "RELEASED",
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
  }

  private static LogisticsDependencyGateway.EquipmentHold activeHold(UUID holdId) {
    return new LogisticsDependencyGateway.EquipmentHold(
        holdId, 1, "ACTIVE", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5), null);
  }
}
