package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.AcceptReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.MediaReferenceInput;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnAdditionalEquipmentRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnEstimateLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnMediaLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnPickupRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.StartReturnEstimatesRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.DesiredDeliveryWindow;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.repository.OrderClientRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.LogisticsExternalAttemptClaimService;
import dev.buhanzaz.rwms.logistics.service.ReturnCompletionProcessor;
import dev.buhanzaz.rwms.logistics.service.ReturnRegistrationProcessor;
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
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReturnCompletionSagaIntegrationTest {
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000701");
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000702");
  private static final UUID CORRELATION = UUID.fromString("00000000-0000-0000-0000-000000000703");
  private static final UUID ASSET = UUID.fromString("00000000-0000-0000-0000-000000000704");
  private static final UUID DRIVER_QUEUE_DEFINITION =
      UUID.fromString("00000000-0000-0000-0000-000000000705");
  private static final UUID DRIVER_QUEUE_CATEGORY =
      UUID.fromString("00000000-0000-0000-0000-000000000706");

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired LogisticsDocumentService documents;
  @Autowired LogisticsExternalAttemptClaimService claims;
  @Autowired ReturnRegistrationProcessor registration;
  @Autowired ReturnCompletionProcessor completion;
  @Autowired JdbcTemplate jdbc;
  @Autowired OrderClientRepository clients;
  @Autowired RentalOrderRepository orders;
  @Autowired LogisticsDocumentRepository logisticsDocuments;

  @MockitoBean LogisticsDependencyGateway dependencies;

  @BeforeEach
  void reset() {
    jdbc.execute(
        """
        truncate table
          driver_logistics_task,
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
    when(dependencies.readWarehouseDriverQueue(WAREHOUSE))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseDriverQueue(
                WAREHOUSE, DRIVER_QUEUE_DEFINITION, DRIVER_QUEUE_CATEGORY));
  }

  @Test
  void acceptsAnUndamagedReturnOnlyAfterMediaSettlementAndLeaseReleaseConfirm() {
    RegisteredReturn registered = registeredReturn();
    UUID mediaId = UUID.randomUUID();
    UUID additionalEquipmentId = UUID.randomUUID();
    UUID acceptanceKey = UUID.randomUUID();
    AcceptReturnRequest request =
        new AcceptReturnRequest(
            List.of(
                new ReturnMediaLineRequest(
                    registered.lineId(),
                    List.of(new MediaReferenceInput(mediaId, 2)),
                    true,
                    List.of(new ReturnAdditionalEquipmentRequest(additionalEquipmentId, 3L)))));
    when(dependencies.validateMediaReferences(
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN),
            eq(registered.documentId()),
            eq(registered.lineId()),
            eq(WAREHOUSE),
            any()))
        .thenReturn(
            new LogisticsDependencyGateway.MediaValidation(
                LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN,
                registered.documentId(),
                registered.lineId(),
                WAREHOUSE,
                List.of(new LogisticsDependencyGateway.MediaReference(mediaId, 2))));
    when(dependencies.settleReturn(
            any(),
            eq(ASSET),
            eq(8L),
            eq(registered.leaseId()),
            eq(11L),
            eq(registered.documentId()),
            eq(registered.lineId()),
            eq(false)))
        .thenReturn(snapshot(9, "FREE"));
    when(dependencies.releaseOperationLease(
            any(),
            eq(registered.leaseId()),
            eq(3L),
            eq(11L),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN),
            eq(registered.documentId()),
            eq(registered.lineId())))
        .thenReturn(releasedLease(registered.leaseId()));
    when(dependencies.receiveReturnEquipment(
            any(),
            eq(registered.documentId()),
            eq(registered.lineId()),
            eq(WAREHOUSE),
            eq(
                List.of(
                    new LogisticsDependencyGateway.ReturnEquipmentReceiptLine(
                        null, additionalEquipmentId, 3L, null, -1L, -1L)))))
        .thenReturn(
            new LogisticsDependencyGateway.ReturnEquipmentReceipt(
                registered.documentId(),
                registered.lineId(),
                WAREHOUSE,
                List.of(
                    new LogisticsDependencyGateway.ReturnEquipmentReceiptLine(
                        UUID.randomUUID(), additionalEquipmentId, 3L, UUID.randomUUID(), 0L, 3L))));

    LogisticsDocumentService.CreateResult started =
        documents.acceptUndamagedReturn(
            SUBJECT,
            acceptanceKey,
            CORRELATION,
            registered.documentId(),
            registered.version(),
            request);
    LogisticsDocumentService.CreateResult replayed =
        documents.acceptUndamagedReturn(
            SUBJECT,
            acceptanceKey,
            CORRELATION,
            registered.documentId(),
            registered.version(),
            request);

    assertThat(started.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(started.response().state()).isEqualTo(LogisticsDocumentState.ACCEPTING);

    LogisticsExternalAttemptTestClaims.drainReturnCompletion(claims, completion);

    assertThat(documents.get(registered.documentId(), LogisticsDocumentType.RETURN).state())
        .isEqualTo(LogisticsDocumentState.ACCEPTED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_media_reference where readiness='READY'",
                Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                """
                select return_additional_contents_snapshot->>'equipmentConfirmed'
                from logistics_document_line where id=?
                """,
                String.class,
                registered.lineId()))
        .isEqualTo("true");
    assertThat(
            jdbc.queryForObject(
                """
                select payload->>'resultCode' from domain_event
                where event_type='logistics.return.acceptance-started.v1'
                """,
                String.class))
        .isEqualTo("EQUIPMENT_COMPLETENESS_CONFIRMED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_guard where guard_state='RELEASED'", Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_task_reference where line_id=?",
                Long.class,
                registered.lineId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where event_type='logistics.return.accepted.v1'",
                Long.class))
        .isOne();
    verify(dependencies)
        .settleReturn(
            any(),
            eq(ASSET),
            eq(8L),
            eq(registered.leaseId()),
            eq(11L),
            eq(registered.documentId()),
            eq(registered.lineId()),
            eq(false));
    verify(dependencies)
        .receiveReturnEquipment(
            any(),
            eq(registered.documentId()),
            eq(registered.lineId()),
            eq(WAREHOUSE),
            eq(
                List.of(
                    new LogisticsDependencyGateway.ReturnEquipmentReceiptLine(
                        null, additionalEquipmentId, 3L, null, -1L, -1L))));
  }

  @Test
  void persistsAnImmutableEstimateSourceBeforeRequestingMaintenanceEstimates() {
    RegisteredReturn registered = registeredReturn();
    UUID mediaId = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    StartReturnEstimatesRequest request =
        new StartReturnEstimatesRequest(
            List.of(
                new ReturnEstimateLineRequest(
                    registered.lineId(), List.of(new MediaReferenceInput(mediaId, 4)))));
    when(dependencies.validateMediaReferences(
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN),
            eq(registered.documentId()),
            eq(registered.lineId()),
            eq(WAREHOUSE),
            any()))
        .thenReturn(
            new LogisticsDependencyGateway.MediaValidation(
                LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN,
                registered.documentId(),
                registered.lineId(),
                WAREHOUSE,
                List.of(new LogisticsDependencyGateway.MediaReference(mediaId, 4))));
    when(dependencies.settleReturn(
            any(), any(), anyLong(), any(), anyLong(), any(), any(), anyBoolean()))
        .thenReturn(snapshot(9, "WAITING_ESTIMATE_CONFIRMATION"));
    when(dependencies.upsertReturnEstimateSource(
            eq(registered.documentId()),
            eq(registered.lineId()),
            eq(WAREHOUSE),
            eq(ASSET),
            eq(8L),
            eq(LocalDate.parse("2026-07-01")),
            eq(List.of(new LogisticsDependencyGateway.MediaReference(mediaId, 4)))))
        .thenReturn(
            new LogisticsDependencyGateway.ReturnEstimateSource(
                registered.documentId(),
                registered.lineId(),
                0,
                WAREHOUSE,
                ASSET,
                8,
                estimateId,
                "a".repeat(64),
                OffsetDateTime.now(ZoneOffset.UTC)));
    when(dependencies.releaseOperationLease(
            any(),
            eq(registered.leaseId()),
            eq(3L),
            eq(11L),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN),
            eq(registered.documentId()),
            eq(registered.lineId())))
        .thenReturn(releasedLease(registered.leaseId()));

    LogisticsDocumentService.CreateResult started =
        documents.startReturnEstimates(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            registered.documentId(),
            registered.version(),
            request);

    assertThat(started.response().state()).isEqualTo(LogisticsDocumentState.ESTIMATE_PENDING);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_return_shortage_snapshot", Long.class))
        .isOne();

    LogisticsExternalAttemptTestClaims.drainReturnCompletion(claims, completion);

    assertThat(
            jdbc.queryForList(
                "select operation_type || ':' || result from logistics_external_attempt order by"
                    + " operation_type",
                String.class))
        .contains(
            "RETURN_MEDIA_VALIDATE:CONFIRMED",
            "RETURN_ASSET_SETTLE_ESTIMATE:CONFIRMED",
            "RETURN_MAINTENANCE_ESTIMATE_SOURCE_UPSERT:CONFIRMED",
            "RETURN_ASSET_LEASE_RELEASE:CONFIRMED");
    assertThat(documents.get(registered.documentId(), LogisticsDocumentType.RETURN).state())
        .isEqualTo(LogisticsDocumentState.ESTIMATE_REQUESTED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where"
                    + " event_type='logistics.return.estimate-requested.v1'",
                Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_guard where guard_state='RELEASED'", Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_media_reference where readiness='READY'",
                Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                """
                select payload->>'resultCode' from domain_event
                where event_type='logistics.return.estimate-started.v1'
                """,
                String.class))
        .isEqualTo("INSPECTION_MEDIA_SUBMITTED");
    verify(dependencies)
        .validateMediaReferences(
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN),
            eq(registered.documentId()),
            eq(registered.lineId()),
            eq(WAREHOUSE),
            eq(List.of(new LogisticsDependencyGateway.MediaReference(mediaId, 4))));
    verify(dependencies)
        .settleReturn(
            any(),
            eq(ASSET),
            eq(8L),
            eq(registered.leaseId()),
            eq(11L),
            eq(registered.documentId()),
            eq(registered.lineId()),
            eq(true));
    verify(dependencies)
        .upsertReturnEstimateSource(
            eq(registered.documentId()),
            eq(registered.lineId()),
            eq(WAREHOUSE),
            eq(ASSET),
            eq(8L),
            eq(LocalDate.parse("2026-07-01")),
            eq(List.of(new LogisticsDependencyGateway.MediaReference(mediaId, 4))));
  }

  @Test
  void rejectsAcceptanceWithoutExplicitEquipmentCompletenessConfirmation() {
    RegisteredReturn registered = registeredReturn();

    assertThatThrownBy(
            () ->
                documents.acceptUndamagedReturn(
                    SUBJECT,
                    UUID.randomUUID(),
                    CORRELATION,
                    registered.documentId(),
                    registered.version(),
                    new AcceptReturnRequest(
                        List.of(
                            new ReturnMediaLineRequest(
                                registered.lineId(),
                                List.of(new MediaReferenceInput(UUID.randomUUID(), 1)),
                                false,
                                List.of())))))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessage("Return acceptance lines are invalid");
  }

  @Test
  void rejectsEstimateWithoutInspectionPhotos() {
    RegisteredReturn registered = registeredReturn();

    assertThatThrownBy(
            () ->
                documents.startReturnEstimates(
                    SUBJECT,
                    UUID.randomUUID(),
                    CORRELATION,
                    registered.documentId(),
                    registered.version(),
                    new StartReturnEstimatesRequest(
                        List.of(new ReturnEstimateLineRequest(registered.lineId(), List.of())))))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessage("Return estimate lines are invalid");
  }

  @Test
  void closesTheFulfilledOrderAfterItsLinkedReturnIsInspected() {
    OrderClient client =
        clients.saveAndFlush(
            OrderClient.create(
                ClientType.LEGAL_ENTITY,
                "Tenant linked",
                "tenant linked",
                "+79990000001",
                "+79990000001",
                null,
                null,
                "Tenant contact",
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
            "ORD-888888",
            client,
            SUBJECT,
            "Dispatcher",
            SUBJECT,
            "Dispatcher",
            "RENTAL_MANAGER",
            "+79990000001",
            null,
            UUID.randomUUID(),
            "1".repeat(64));
    order.replaceClientDeliveryDetails(
        "Moscow, test address",
        new BigDecimal("55.750000"),
        new BigDecimal("37.620000"),
        List.of());
    order.replaceClientDesiredDeliveryWindow(
        DesiredDeliveryWindow.create(LocalDate.now(), LocalDate.now()));
    order.selectWarehouse(WAREHOUSE);
    order.saveForFulfillment();
    order.fulfill();
    order = orders.saveAndFlush(order);
    LogisticsDocument shipment =
        logisticsDocuments.saveAndFlush(
            LogisticsDocument.createRentalOrderShipment(
                WAREHOUSE,
                client.getId(),
                order.getId(),
                client.getDisplayName(),
                SUBJECT,
                CORRELATION));
    LogisticsDocument returnDocument =
        LogisticsDocument.createRentalOrderReturn(
            WAREHOUSE,
            client.getId(),
            order.getId(),
            shipment.getId(),
            client.getDisplayName(),
            SUBJECT,
            CORRELATION);
    returnDocument.scheduleReturn(
        "Driver linked", OffsetDateTime.now(ZoneOffset.UTC).toLocalDate());
    returnDocument.beginReturnRegistration();
    returnDocument.requireReturnInspection();
    returnDocument.beginReturnAcceptance();
    returnDocument.acceptReturn();
    returnDocument = logisticsDocuments.saveAndFlush(returnDocument);

    documents.closeRentalOrderReturn(returnDocument);
    documents.closeRentalOrderReturn(returnDocument);

    assertThat(
            jdbc.queryForObject(
                "select status from rental_order where id=?", String.class, order.getId()))
        .isEqualTo("CLOSED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_order_audit_event where order_id=? and"
                    + " event_type='ORDER_CLOSED'",
                Long.class,
                order.getId()))
        .isOne();
  }

  private RegisteredReturn registeredReturn() {
    LogisticsDocumentService.CreateResult created =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateReturnRequest(
                WAREHOUSE, List.of(new ReturnLineRequest(ASSET, 7, "Tenant A"))));
    UUID documentId = created.response().id();
    UUID lineId = created.response().lines().getFirst().id();
    UUID leaseId = UUID.randomUUID();
    when(dependencies.readWarehouseIdentity(WAREHOUSE))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(WAREHOUSE, 1, true, "Europe/Moscow"));
    when(dependencies.readRentalItemSnapshot(ASSET)).thenReturn(snapshot(7, "RENTED"));
    when(dependencies.acquireReturnLease(any(), eq(ASSET), eq(7L), eq(documentId), eq(lineId)))
        .thenReturn(activeLease(leaseId));
    when(dependencies.applyReturnIntake(
            any(), eq(ASSET), eq(7L), eq(leaseId), eq(11L), eq(documentId), eq(lineId)))
        .thenReturn(snapshot(8, "AFTER_RENT"));

    documents.registerReturn(
        SUBJECT,
        UUID.randomUUID(),
        CORRELATION,
        documentId,
        0,
        new ReturnPickupRequest("Driver snapshot", LocalDate.parse("2026-07-01")));
    LogisticsExternalAttemptTestClaims.drainReturnRegistration(claims, registration);
    long version = documents.get(documentId, LogisticsDocumentType.RETURN).version();
    assertThat(documents.get(documentId, LogisticsDocumentType.RETURN).state())
        .isEqualTo(LogisticsDocumentState.INSPECTION_REQUIRED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_task_reference where document_id=?",
                Long.class,
                documentId))
        .isZero();
    return new RegisteredReturn(documentId, lineId, leaseId, version);
  }

  private static LogisticsDependencyGateway.RentalItemSnapshot snapshot(
      long version, String status) {
    return new LogisticsDependencyGateway.RentalItemSnapshot(
        ASSET,
        version,
        WAREHOUSE,
        "БТ-704",
        status,
        List.of(new LogisticsDependencyGateway.EquipmentContent(UUID.randomUUID(), 2)));
  }

  private static LogisticsDependencyGateway.OperationLease activeLease(UUID leaseId) {
    return new LogisticsDependencyGateway.OperationLease(
        leaseId, 3, ASSET, 11, "ACTIVE", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
  }

  private static LogisticsDependencyGateway.OperationLease releasedLease(UUID leaseId) {
    return new LogisticsDependencyGateway.OperationLease(
        leaseId, 4, ASSET, 11, "RELEASED", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
  }

  private record RegisteredReturn(UUID documentId, UUID lineId, UUID leaseId, long version) {}
}
