package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateShipmentRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.EquipmentAllocationRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentFurnitureReadinessState;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentPlanRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.driver.service.DocumentDriverTaskPlanner;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.CreateOrderRentalShipmentRequest;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.DesiredDeliveryWindow;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderEquipmentRequirement;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import dev.buhanzaz.rwms.logistics.order.repository.OrderClientRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderEquipmentRequirementRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.LogisticsExternalAttemptClaimService;
import dev.buhanzaz.rwms.logistics.service.ShipmentFurnitureTaskService;
import dev.buhanzaz.rwms.logistics.service.ShipmentProcessor;
import java.math.BigDecimal;
import java.time.LocalDate;
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
  private static final UUID INVENTORY_SOURCE =
      UUID.fromString("00000000-0000-0000-0000-000000000806");

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired LogisticsDocumentService documents;
  @Autowired LogisticsExternalAttemptClaimService claims;
  @Autowired ShipmentProcessor processor;
  @Autowired JdbcTemplate jdbc;
  @Autowired OrderClientRepository clients;
  @Autowired RentalOrderRepository orders;
  @Autowired RentalOrderUnitTermRepository rentalTerms;
  @Autowired RentalOrderEquipmentRequirementRepository equipmentRequirements;
  @Autowired ShipmentFurnitureTaskService shipmentFurnitureTasks;

  @MockitoBean LogisticsDependencyGateway dependencies;
  @MockitoBean DocumentDriverTaskPlanner driverTaskPlanner;

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
    org.mockito.Mockito.reset(dependencies, driverTaskPlanner);
  }

  @Test
  void preparesAndConfirmsShipmentAfterHoldsAndFencedAssetEffects() {
    ShipmentLineRequest line =
        new ShipmentLineRequest(ASSET, 7, List.of(new EquipmentAllocationRequest(EQUIPMENT, 2, 4)));
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

    when(dependencies.readRentalItemSnapshot(ASSET)).thenReturn(snapshot(7, "FREE"));
    when(dependencies.acquireOperationLease(
            any(),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(ASSET),
            eq(7L),
            eq(documentId),
            eq(lineId)))
        .thenReturn(activeLease(leaseId));
    when(dependencies.acquireEquipmentHold(any(), any(), any(), any(), any(), anyLong(), anyLong()))
        .thenReturn(activeHold(holdId));

    assertThat(created.response().state()).isEqualTo(LogisticsDocumentState.PREPARING);

    LogisticsExternalAttemptTestClaims.drainShipment(claims, processor);
    long awaitingVersion = documents.get(documentId, LogisticsDocumentType.SHIPMENT).version();
    assertThat(
            jdbc.queryForList(
                "select operation_type || ':' || result from logistics_external_attempt order by"
                    + " operation_type",
                String.class))
        .contains("SHIPMENT_ASSET_SNAPSHOT:CONFIRMED", "SHIPMENT_ASSET_LEASE_ACQUIRE:CONFIRMED");
    assertThat(documents.get(documentId, LogisticsDocumentType.SHIPMENT).state())
        .isEqualTo(LogisticsDocumentState.AWAITING_CONFIRMATION);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_task_reference where document_id=?",
                Long.class,
                documentId))
        .isZero();
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
    LogisticsExternalAttemptTestClaims.drainShipment(claims, processor);

    assertThat(documents.get(documentId, LogisticsDocumentType.SHIPMENT).state())
        .isEqualTo(LogisticsDocumentState.SHIPPED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_equipment_hold_reference where"
                    + " hold_state='COMMITTED'",
                Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_guard where guard_state='RELEASED'", Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where"
                    + " event_type='logistics.shipment.preparation-confirmed.v1'",
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
  void shippedSavedOrderCanExplicitlyKeepItsPlannedDateAndGetsOneDateLessReturn() {
    OrderClient client =
        clients.saveAndFlush(
            OrderClient.create(
                ClientType.LEGAL_ENTITY,
                "Party linked",
                "party linked",
                "+79990000002",
                "+79990000002",
                null,
                null,
                "Party contact",
                SUBJECT,
                "Dispatcher",
                null,
                null,
                List.of(),
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
            "+79990000002",
            null,
            UUID.randomUUID(),
            "1".repeat(64));
    order.replaceClientDeliveryDetails(
        "Moscow, linked address",
        new BigDecimal("55.750000"),
        new BigDecimal("37.620000"),
        List.of());
    order.replaceClientDesiredDeliveryWindows(
        List.of(
            DesiredDeliveryWindow.create(
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(1))));
    order.selectWarehouse(WAREHOUSE);
    order.saveForFulfillment();
    order = orders.saveAndFlush(order);
    rentalTerms.saveAndFlush(RentalOrderUnitTerm.create(order, ASSET, 1));
    equipmentRequirements.saveAndFlush(
        RentalOrderEquipmentRequirement.create(order, ASSET, EQUIPMENT, "Стол", 2));
    LogisticsDependencyGateway.OrderUnitReservation reservation = reservation(order.getId());
    LocalDate plannedShipmentDate = LocalDate.now().plusDays(1);

    var created =
        documents
            .createRentalOrderShipment(
                SUBJECT,
                UUID.randomUUID(),
                CORRELATION,
                order,
                List.of(reservation),
                new CreateOrderRentalShipmentRequest(
                    order.getVersion(), "Driver linked", plannedShipmentDate, List.of(ASSET)),
                "a".repeat(64))
            .response();
    UUID documentId = created.id();
    UUID lineId = created.lines().getFirst().id();
    UUID leaseId = UUID.randomUUID();

    when(dependencies.readRentalItemSnapshot(ASSET)).thenReturn(snapshot(7, "BOOKED"));
    when(dependencies.acquireOperationLease(
            any(),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(ASSET),
            eq(7L),
            eq(documentId),
            eq(lineId),
            eq(order.getId())))
        .thenReturn(activeLease(leaseId));
    when(dependencies.readOrderUnits(order.getId())).thenReturn(List.of(reservation));
    when(dependencies.planOrderFurnitureMovements(
            eq(order.getId()),
            eq(WAREHOUSE),
            eq(ASSET),
            eq(null),
            eq(List.of(new LogisticsDependencyGateway.OrderEquipmentRequirement(EQUIPMENT, 2))),
            eq(
                List.of(
                    new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(
                        ASSET,
                        List.of(
                            new LogisticsDependencyGateway.OrderEquipmentRequirement(
                                EQUIPMENT, 2)))))))
        .thenReturn(
            new LogisticsDependencyGateway.OrderFurnitureMovementPlan(
                order.getId(), ASSET, "CAB-801", List.of()));

    documents.planShipment(
        SUBJECT,
        UUID.randomUUID(),
        CORRELATION,
        documentId,
        created.version(),
        new ShipmentPlanRequest("Driver linked", plannedShipmentDate));
    LogisticsExternalAttemptTestClaims.drainShipment(claims, processor);
    long awaitingVersion = documents.get(documentId, LogisticsDocumentType.SHIPMENT).version();

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
    when(dependencies.replaceOrderEquipmentReservations(
            any(),
            eq(order.getId()),
            eq(WAREHOUSE),
            eq(SUBJECT),
            eq("RENTAL_MANAGER"),
            eq(List.of())))
        .thenReturn(List.of());

    documents.confirmShipmentPreparation(
        SUBJECT, UUID.randomUUID(), CORRELATION, documentId, awaitingVersion, true);
    LogisticsExternalAttemptTestClaims.drainShipment(claims, processor);

    assertThat(
            jdbc.queryForObject(
                "select status from rental_order where id=?", String.class, order.getId()))
        .isEqualTo("FULFILLED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_order_audit_event where order_id=? and"
                    + " event_type='ORDER_FULFILLED'",
                Long.class,
                order.getId()))
        .isOne();
    assertThat(
            jdbc.queryForMap(
                """
                select state, driver_snapshot, scheduled_at, rental_order_id, rental_shipment_id
                from logistics_document
                where document_type='RETURN' and rental_order_id=?
                """,
                order.getId()))
        .containsEntry("state", "DRAFT")
        .containsEntry("rental_order_id", order.getId())
        .containsEntry("rental_shipment_id", documentId)
        .containsEntry("driver_snapshot", null)
        .containsEntry("scheduled_at", null);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document_line line join logistics_document document"
                    + " on document.id=line.document_id where document.document_type='RETURN' and"
                    + " document.rental_order_id=? and line.asset_id=?",
                Long.class,
                order.getId(),
                ASSET))
        .isOne();
    verify(dependencies)
        .replaceOrderEquipmentReservations(
            any(),
            eq(order.getId()),
            eq(WAREHOUSE),
            eq(SUBJECT),
            eq("RENTAL_MANAGER"),
            eq(List.of()));
  }

  @Test
  void directRegionalShipmentKeepsServiceWarehouseAndReplaysFrozenInventorySource() {
    OrderClient client =
        clients.saveAndFlush(
            OrderClient.create(
                ClientType.LEGAL_ENTITY,
                "Региональный клиент",
                "региональный клиент",
                "+79990000003",
                "+79990000003",
                null,
                null,
                "Контакт",
                SUBJECT,
                "Логист",
                null,
                null,
                List.of(),
                SUBJECT,
                UUID.randomUUID(),
                "3".repeat(64)));
    RentalOrder order =
        RentalOrder.create(
            "ORD-999998",
            client,
            SUBJECT,
            "Логист",
            SUBJECT,
            "Логист",
            "RENTAL_MANAGER",
            "+79990000003",
            null,
            UUID.randomUUID(),
            "4".repeat(64));
    order.replaceClientDeliveryDetails(
        "Региональный адрес",
        new BigDecimal("58.521000"),
        new BigDecimal("31.275000"),
        List.of());
    LocalDate shipmentDate = LocalDate.now();
    order.replaceClientDesiredDeliveryWindows(
        List.of(DesiredDeliveryWindow.create(shipmentDate, shipmentDate)));
    order.selectWarehouse(WAREHOUSE);
    order.saveForFulfillment();
    order = orders.saveAndFlush(order);
    rentalTerms.saveAndFlush(RentalOrderUnitTerm.create(order, ASSET, 1));
    LogisticsDependencyGateway.OrderUnitReservation sourceReservation =
        reservation(order.getId(), INVENTORY_SOURCE);
    CreateOrderRentalShipmentRequest request =
        new CreateOrderRentalShipmentRequest(
            order.getVersion(),
            "Водитель источника",
            null,
            shipmentDate,
            List.of(ASSET),
            false,
            INVENTORY_SOURCE);
    UUID commandKey = UUID.randomUUID();
    String checksum = "d".repeat(64);

    var created =
        documents.createRentalOrderShipment(
            SUBJECT,
            commandKey,
            CORRELATION,
            order,
            List.of(sourceReservation),
            request,
            checksum);
    var replay =
        documents.createRentalOrderShipment(
            SUBJECT,
            commandKey,
            CORRELATION,
            order,
            List.of(sourceReservation),
            request,
            checksum);

    assertThat(created.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response().id()).isEqualTo(created.response().id());
    assertThat(created.response().warehouseId()).isEqualTo(WAREHOUSE);
    assertThat(created.response().lines().getFirst().inventorySourceWarehouseId())
        .isEqualTo(INVENTORY_SOURCE);
    assertThat(
            jdbc.queryForObject(
                "select inventory_source_warehouse_id from logistics_document_line where id=?",
                UUID.class,
                created.response().lines().getFirst().id()))
        .isEqualTo(INVENTORY_SOURCE);
    UUID lineId = created.response().lines().getFirst().id();
    UUID leaseId = UUID.randomUUID();
    when(dependencies.readRentalItemSnapshot(ASSET))
        .thenReturn(snapshotAt(INVENTORY_SOURCE, 7, "BOOKED"));
    when(dependencies.acquireOperationLease(
            any(),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(ASSET),
            eq(7L),
            eq(created.response().id()),
            eq(lineId),
            eq(order.getId())))
        .thenReturn(activeLease(leaseId));

    documents.planShipment(
        SUBJECT,
        UUID.randomUUID(),
        CORRELATION,
        created.response().id(),
        created.response().version(),
        new ShipmentPlanRequest("Водитель источника", shipmentDate));
    LogisticsExternalAttemptTestClaims.drainShipment(claims, processor);

    var awaiting = documents.get(created.response().id(), LogisticsDocumentType.SHIPMENT);
    assertThat(awaiting.state())
        .isEqualTo(LogisticsDocumentState.AWAITING_CONFIRMATION);
    when(dependencies.applyFencedEffect(
            any(),
            eq(LogisticsDependencyGateway.AssetEffect.SHIPMENT_CONFIRM),
            eq(ASSET),
            eq(7L),
            eq(leaseId),
            eq(11L),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(created.response().id()),
            eq(lineId),
            eq(null)))
        .thenReturn(snapshotAt(INVENTORY_SOURCE, 8, "RENTED"));
    when(dependencies.releaseOperationLease(
            any(),
            eq(leaseId),
            eq(3L),
            eq(11L),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(created.response().id()),
            eq(lineId)))
        .thenReturn(releasedLease(leaseId));

    documents.confirmShipmentPreparation(
        SUBJECT,
        UUID.randomUUID(),
        CORRELATION,
        created.response().id(),
        awaiting.version());
    LogisticsExternalAttemptTestClaims.drainShipment(claims, processor);

    assertThat(documents.get(created.response().id(), LogisticsDocumentType.SHIPMENT).state())
        .isEqualTo(LogisticsDocumentState.SHIPPED);
    verify(driverTaskPlanner, times(2)).plan(any(), any());
  }

  @Test
  void savedOrderShipmentCreatesOneIdempotentFurnitureTaskFromTheAssetDelta() {
    OrderClient client =
        clients.saveAndFlush(
            OrderClient.create(
                ClientType.LEGAL_ENTITY,
                "Furniture party",
                "furniture party",
                "+79990000003",
                "+79990000003",
                null,
                null,
                "Furniture contact",
                SUBJECT,
                "Dispatcher",
                null,
                null,
                List.of(),
                SUBJECT,
                UUID.randomUUID(),
                "2".repeat(64)));
    RentalOrder order =
        RentalOrder.create(
            "ORD-998877",
            client,
            SUBJECT,
            "Dispatcher",
            SUBJECT,
            "Dispatcher",
            "RENTAL_MANAGER",
            "+79990000003",
            null,
            UUID.randomUUID(),
            "3".repeat(64));
    order.replaceClientDeliveryDetails(
        "Moscow, furniture address",
        new BigDecimal("55.750000"),
        new BigDecimal("37.620000"),
        List.of());
    order.replaceClientDesiredDeliveryWindows(
        List.of(DesiredDeliveryWindow.create(LocalDate.now(), LocalDate.now())));
    order.selectWarehouse(WAREHOUSE);
    order.saveForFulfillment();
    order = orders.saveAndFlush(order);
    rentalTerms.saveAndFlush(RentalOrderUnitTerm.create(order, ASSET, 1));
    equipmentRequirements.saveAndFlush(
        RentalOrderEquipmentRequirement.create(order, ASSET, EQUIPMENT, "Стол", 2));
    LogisticsDependencyGateway.OrderUnitReservation activeReservation = reservation(order.getId());

    var shipment =
        documents
            .createRentalOrderShipment(
                SUBJECT,
                UUID.randomUUID(),
                CORRELATION,
                order,
                List.of(activeReservation),
                new CreateOrderRentalShipmentRequest(
                    order.getVersion(), "Driver linked", LocalDate.now(), List.of(ASSET)),
                "b".repeat(64))
            .response();
    when(dependencies.readOrderUnits(order.getId())).thenReturn(List.of(activeReservation));
    LogisticsDependencyGateway.OrderFurnitureMovementPlan plan =
        new LogisticsDependencyGateway.OrderFurnitureMovementPlan(
            order.getId(),
            ASSET,
            "CAB-801",
            List.of(
                new LogisticsDependencyGateway.OrderFurnitureMovementPlanLine(
                    EQUIPMENT,
                    "Стол",
                    UUID.randomUUID(),
                    WAREHOUSE,
                    null,
                    "STOCK",
                    4,
                    WAREHOUSE,
                    ASSET,
                    "CABIN_NON_RENTED",
                    2)));
    when(dependencies.planOrderFurnitureMovements(
            eq(order.getId()),
            eq(WAREHOUSE),
            eq(ASSET),
            eq(null),
            eq(List.of(new LogisticsDependencyGateway.OrderEquipmentRequirement(EQUIPMENT, 2))),
            eq(
                List.of(
                    new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(
                        ASSET,
                        List.of(
                            new LogisticsDependencyGateway.OrderEquipmentRequirement(
                                EQUIPMENT, 2)))))))
        .thenReturn(plan);

    var created =
        shipmentFurnitureTasks.createForShipment(
            SUBJECT, UUID.randomUUID(), shipment.id(), shipment.version());

    assertThat(created.tasks())
        .singleElement()
        .satisfies(
            task -> {
              assertThat(task.rentalItemId()).isEqualTo(ASSET);
              assertThat(task.unitNumber()).isEqualTo("CAB-801");
              assertThat(task.taskId()).isNotNull();
              assertThat(task.lineCount()).isEqualTo(1);
            });
    assertThat(
            jdbc.queryForObject(
                "select count(*) from shipment_furniture_movement_task where document_id=?",
                Long.class,
                shipment.id()))
        .isOne();
    assertThat(jdbc.queryForObject("select count(*) from equipment_movement_task", Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select planned_duration_minutes from equipment_movement_task", Integer.class))
        .isEqualTo(60);

    assertThat(shipmentFurnitureTasks.readiness(shipment.id()).state())
        .isEqualTo(ShipmentFurnitureReadinessState.AWAITING_TASK_COMPLETION);
    assertThatThrownBy(
            () ->
                documents.planShipment(
                    SUBJECT,
                    UUID.randomUUID(),
                    CORRELATION,
                    shipment.id(),
                    shipment.version(),
                    new ShipmentPlanRequest("Driver A", LocalDate.now())))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("требуется закрыть задание");

    var replay =
        shipmentFurnitureTasks.createForShipment(
            SUBJECT, UUID.randomUUID(), shipment.id(), shipment.version());
    assertThat(replay.tasks()).singleElement().extracting(task -> task.taskId()).isNotNull();
    verify(dependencies, times(3))
        .planOrderFurnitureMovements(
            eq(order.getId()),
            eq(WAREHOUSE),
            eq(ASSET),
            eq(null),
            eq(List.of(new LogisticsDependencyGateway.OrderEquipmentRequirement(EQUIPMENT, 2))),
            eq(
                List.of(
                    new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(
                        ASSET,
                        List.of(
                            new LogisticsDependencyGateway.OrderEquipmentRequirement(
                                EQUIPMENT, 2))))));
  }

  @Test
  void rejectsBookedAssetWhenShipmentIsNotBoundToItsRentalOrder() {
    ShipmentLineRequest line = new ShipmentLineRequest(ASSET, 7, List.of());
    LogisticsDocumentService.CreateResult created =
        documents.createShipment(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateShipmentRequest(WAREHOUSE, "Party A", "Driver A", List.of(line)));

    when(dependencies.readRentalItemSnapshot(ASSET)).thenReturn(snapshot(7, "BOOKED"));

    LogisticsExternalAttemptTestClaims.drainShipment(claims, processor);

    assertThat(documents.get(created.response().id(), LogisticsDocumentType.SHIPMENT).state())
        .isEqualTo(LogisticsDocumentState.CONFLICT);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_external_attempt where"
                    + " result='PERMANENT_REJECTION'",
                Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_task_reference where document_id=?",
                Long.class,
                created.response().id()))
        .isZero();
  }

  private static LogisticsDependencyGateway.OrderUnitReservation reservation(UUID orderId) {
    return reservation(orderId, WAREHOUSE);
  }

  private static LogisticsDependencyGateway.OrderUnitReservation reservation(
      UUID orderId, UUID inventorySourceWarehouseId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return new LogisticsDependencyGateway.OrderUnitReservation(
        UUID.randomUUID(),
        0,
        orderId,
        ASSET,
        inventorySourceWarehouseId,
        "ACTIVE",
        SUBJECT,
        "RENTAL_MANAGER",
        now,
        null,
        false,
        new LogisticsDependencyGateway.OrderRentalItem(
            ASSET,
            7,
            inventorySourceWarehouseId,
            "CAB-801",
            "FREE",
            "RENT",
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(), now, now));
  }

  private static LogisticsDependencyGateway.RentalItemSnapshot snapshot(
      long version, String status) {
    return snapshotAt(WAREHOUSE, version, status);
  }

  private static LogisticsDependencyGateway.RentalItemSnapshot snapshotAt(
      UUID warehouseId, long version, String status) {
    return new LogisticsDependencyGateway.RentalItemSnapshot(
        ASSET,
        version,
        warehouseId,
        status,
        List.of(new LogisticsDependencyGateway.EquipmentContent(EQUIPMENT, 2)));
  }

  private static LogisticsDependencyGateway.OperationLease activeLease(UUID leaseId) {
    return new LogisticsDependencyGateway.OperationLease(
        leaseId, 3, ASSET, 11, "ACTIVE", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
  }

  private static LogisticsDependencyGateway.OperationLease releasedLease(UUID leaseId) {
    return new LogisticsDependencyGateway.OperationLease(
        leaseId, 4, ASSET, 11, "RELEASED", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
  }

  private static LogisticsDependencyGateway.EquipmentHold activeHold(UUID holdId) {
    return new LogisticsDependencyGateway.EquipmentHold(
        holdId, 1, "ACTIVE", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5), null);
  }
}
