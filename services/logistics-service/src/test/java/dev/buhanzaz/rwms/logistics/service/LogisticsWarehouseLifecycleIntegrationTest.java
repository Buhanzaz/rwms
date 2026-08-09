package dev.buhanzaz.rwms.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateShipmentRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateTransferRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferLineRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseLifecycleState;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationAdmission;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseTimeZone;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionEvidence;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionKind;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import java.time.Duration;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Exercises fail-closed warehouse admission, durable replay candidates, and operation-mark
 * readiness against the logistics PostgreSQL schema.
 */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.task.scheduling.enabled=false",
      "rwms.platform.kafka.enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LogisticsWarehouseLifecycleIntegrationTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-00000000a301");
  private static final UUID DESTINATION_WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-00000000a304");
  private static final UUID SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-00000000a302");
  private static final UUID ASSET =
      UUID.fromString("00000000-0000-0000-0000-00000000a303");
  private static final OffsetDateTime TIME_ZONE_EFFECTIVE_FROM =
      OffsetDateTime.parse("2020-01-01T00:00:00Z");
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MockMvc mvc;
  @Autowired LogisticsWarehouseLifecycle lifecycle;
  @Autowired LogisticsDocumentService documents;
  @Autowired LogisticsWarehouseLifecycleStore lifecycleStore;
  @Autowired LogisticsWarehouseOperationMarkStore operationMarks;
  @Autowired LogisticsDocumentRepository documentRepository;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired JdbcTemplate jdbc;

  @MockitoBean LogisticsDependencyGateway dependencies;

  @BeforeEach
  void clean() {
    reset(dependencies);
    jdbc.execute(
        """
        truncate table
          logistics_warehouse_readiness_fence,
          logistics_warehouse_admission_intent,
          warehouse_operation_mark_recovery_audit,
          warehouse_operation_mark_outbox,
          logistics_idempotency_record,
          logistics_document,
          event_stream_head,
          domain_event,
          aggregate_snapshot,
          projection_checkpoint,
          outbox_event
        cascade
        """);
  }

  @Test
  void disabledAdmissionReturnsServiceUnavailableBeforeAnyDurableWrite() throws Exception {
    when(dependencies.productionReady()).thenReturn(false);

    performCreate(UUID.randomUUID(), request(7))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("LOGISTICS_DEPENDENCY_UNAVAILABLE"));

    assertNoDurableCreate();
    verify(dependencies, never())
        .warehouseAdmission(any(UUID.class), any(WarehouseOperationDirection.class));
  }

  @Test
  void admissionTimeoutReturnsServiceUnavailableAndCancelsTheReservation() throws Exception {
    when(dependencies.productionReady()).thenReturn(true);
    when(dependencies.warehouseAdmission(WAREHOUSE, WarehouseOperationDirection.INCOMING))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.TRANSIENT,
                "warehouse admission timed out"));

    performCreate(UUID.randomUUID(), request(7))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("LOGISTICS_DEPENDENCY_UNAVAILABLE"));

    assertNoDurableCreate();
  }

  @Test
  void drainingAndInactiveWarehousesRejectTheFirstIncomingCreate() throws Exception {
    for (WarehouseLifecycleState state :
        List.of(WarehouseLifecycleState.DRAINING, WarehouseLifecycleState.INACTIVE)) {
      reset(dependencies);
      when(dependencies.productionReady()).thenReturn(true);
      when(dependencies.warehouseAdmission(WAREHOUSE, WarehouseOperationDirection.INCOMING))
          .thenReturn(
              new WarehouseOperationAdmission(
                  WAREHOUSE,
                  11,
                  state,
                  WarehouseOperationDirection.INCOMING,
                  false));

      performCreate(UUID.randomUUID(), request(7))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("LOGISTICS_CONFLICT"));

      assertNoDurableCreate();
    }
  }

  @Test
  void lostTimezoneResponseReusesExactVersionsAndCommittedReplayNeedsNoDependency()
      throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    CreateReturnRequest request = request(7);
    when(dependencies.productionReady()).thenReturn(true);
    when(dependencies.warehouseAdmission(WAREHOUSE, WarehouseOperationDirection.INCOMING))
        .thenReturn(
            new WarehouseOperationAdmission(
                WAREHOUSE,
                13,
                WarehouseLifecycleState.ACTIVE,
                WarehouseOperationDirection.INCOMING,
                true));
    when(dependencies.warehouseTimeZoneAt(eq(WAREHOUSE), any(OffsetDateTime.class)))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.TRANSIENT,
                "warehouse timezone timed out"))
        .thenReturn(new WarehouseTimeZone(WAREHOUSE, "Europe/Moscow", TIME_ZONE_EFFECTIVE_FROM));

    assertThatThrownBy(() -> prepare(idempotencyKey))
        .isInstanceOf(LogisticsDependencyException.class)
        .hasMessageContaining("timezone timed out");
    assertThat(
            jdbc.queryForObject(
                "select state from logistics_warehouse_admission_intent", String.class))
        .isEqualTo("ADMITTED");
    assertNoDocumentOrEvent();

    AdmissionTicket admitted = prepare(idempotencyKey);
    LogisticsDocumentService.CreateResult created =
        documents.createReturn(SUBJECT, idempotencyKey, UUID.randomUUID(), request, admitted);

    assertThat(created.replayed()).isFalse();
    assertThat(admitted.kind()).isEqualTo(AdmissionKind.REMOTE_ADMISSION);
    assertThat(admitted.admissionEvidence())
        .containsExactly(
            new AdmissionEvidence(
                WAREHOUSE, WarehouseOperationDirection.INCOMING, 13));
    assertThat(count("logistics_warehouse_admission_intent")).isZero();
    verify(dependencies)
        .warehouseAdmission(WAREHOUSE, WarehouseOperationDirection.INCOMING);
    assertThat(
            jdbc.queryForMap(
                """
                select admission_direction,admission_warehouse_version
                  from warehouse_operation_mark_outbox
                 where warehouse_id=? and operation_id=?
                """,
                WAREHOUSE,
                created.response().id()))
        .containsEntry("admission_direction", "INCOMING")
        .containsEntry("admission_warehouse_version", 13L);

    reset(dependencies);
    when(dependencies.productionReady()).thenReturn(false);
    performCreate(idempotencyKey, request)
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"));
    assertThat(count("logistics_document")).isOne();
    assertThat(count("domain_event")).isOne();
    assertThat(count("outbox_event")).isOne();
    assertThat(count("logistics_warehouse_admission_intent")).isZero();
    verify(dependencies, never())
        .warehouseAdmission(any(UUID.class), any(WarehouseOperationDirection.class));
    verify(dependencies, never())
        .warehouseTimeZoneAt(any(UUID.class), any(OffsetDateTime.class));

    AdmissionTicket replayAdmission = prepare(idempotencyKey);
    LogisticsDocumentService.CreateResult replayed =
        documents.createReturn(
            SUBJECT, idempotencyKey, UUID.randomUUID(), request, replayAdmission);

    assertThat(replayAdmission.kind())
        .isEqualTo(AdmissionKind.EVIDENCED_REPLAY_CANDIDATE);
    assertThat(replayAdmission.bypassed()).isTrue();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response().id()).isEqualTo(created.response().id());
    assertThat(count("logistics_document")).isOne();
    assertThat(count("domain_event")).isOne();
    assertThat(count("outbox_event")).isOne();
    assertThat(count("warehouse_operation_mark_outbox")).isOne();
    assertThat(count("logistics_warehouse_admission_intent")).isZero();

    LogisticsWarehouseLifecycleStore.ReadinessAttempt replayReadiness =
        lifecycleStore.beginReadiness(WAREHOUSE, 14);
    assertThat(replayReadiness.shouldConfirm()).isFalse();

    assertThatThrownBy(
            () ->
                documents.createReturn(
                    SUBJECT,
                    idempotencyKey,
                    UUID.randomUUID(),
                    request(8),
                    prepare(idempotencyKey)))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("Idempotency key");
    assertThat(count("logistics_document")).isOne();
  }

  @Test
  void legacyNullAdmissionEvidenceReturnsServiceUnavailableWithoutRemoteCalls()
      throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    stubAdmissionSuccess();
    AdmissionTicket admitted = prepare(idempotencyKey);
    LogisticsDocumentService.CreateResult created =
        documents.createReturn(
            SUBJECT, idempotencyKey, UUID.randomUUID(), request(7), admitted);
    jdbc.update(
        """
        update warehouse_operation_mark_outbox
           set admission_direction=null,admission_warehouse_version=null
         where warehouse_id=? and operation_id=?
        """,
        WAREHOUSE,
        created.response().id());

    reset(dependencies);
    when(dependencies.productionReady()).thenReturn(false);

    performCreate(idempotencyKey, request(7))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("LOGISTICS_DEPENDENCY_UNAVAILABLE"));
    assertThat(count("logistics_document")).isOne();
    assertThat(count("domain_event")).isOne();
    assertThat(count("outbox_event")).isOne();
    assertThat(count("warehouse_operation_mark_outbox")).isOne();
    assertThat(count("logistics_warehouse_admission_intent")).isZero();
    verify(dependencies, never())
        .warehouseAdmission(any(UUID.class), any(WarehouseOperationDirection.class));
  }

  @Test
  void testOnlyFixtureMarkRemainsNullAndCannotAuthorizePublicReplay() throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    when(dependencies.productionReady()).thenReturn(false);
    LogisticsDocumentService.CreateResult created =
        documents.createReturn(
            SUBJECT, idempotencyKey, UUID.randomUUID(), request(7));

    assertThat(
            jdbc.queryForMap(
                """
                select admission_direction,admission_warehouse_version
                  from warehouse_operation_mark_outbox
                 where warehouse_id=? and operation_id=?
                """,
                WAREHOUSE,
                created.response().id()))
        .containsEntry("admission_direction", null)
        .containsEntry("admission_warehouse_version", null);
    performCreate(idempotencyKey, request(7))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("LOGISTICS_DEPENDENCY_UNAVAILABLE"));
    assertThat(count("logistics_document")).isOne();
    assertThat(count("warehouse_operation_mark_outbox")).isOne();
    assertThat(count("logistics_warehouse_admission_intent")).isZero();
    verify(dependencies, never())
        .warehouseAdmission(any(UUID.class), any(WarehouseOperationDirection.class));
  }

  @Test
  void missingAdmissionMarkReturnsServiceUnavailableWithoutRemoteCalls() throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    stubAdmissionSuccess();
    LogisticsDocumentService.CreateResult created =
        documents.createReturn(
            SUBJECT,
            idempotencyKey,
            UUID.randomUUID(),
            request(7),
            prepare(idempotencyKey));
    jdbc.update(
        "delete from warehouse_operation_mark_outbox where warehouse_id=? and operation_id=?",
        WAREHOUSE,
        created.response().id());

    reset(dependencies);
    when(dependencies.productionReady()).thenReturn(false);

    performCreate(idempotencyKey, request(7))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("LOGISTICS_DEPENDENCY_UNAVAILABLE"));
    assertThat(count("logistics_document")).isOne();
    assertThat(count("warehouse_operation_mark_outbox")).isZero();
    verify(dependencies, never())
        .warehouseAdmission(any(UUID.class), any(WarehouseOperationDirection.class));
  }

  @Test
  void evidencedOrphanMarkWithoutItsDomainRowIsNotReplayProof() {
    jdbc.update(
        """
        insert into warehouse_operation_mark_outbox(
          operation_id,warehouse_id,occurred_at,admission_direction,
          admission_warehouse_version,state,attempt_count,next_attempt_at,created_at,updated_at)
        values (?, ?, clock_timestamp(),'OUTGOING',17,'PENDING',0,clock_timestamp(),
          clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        WAREHOUSE);
    when(dependencies.productionReady()).thenReturn(false);

    assertThatThrownBy(
            () ->
                lifecycle.prepareEquipmentMovement(
                    SUBJECT,
                    UUID.randomUUID(),
                    List.of(
                        new AdmissionRequirement(
                            WAREHOUSE, WarehouseOperationDirection.OUTGOING))))
        .isInstanceOf(LogisticsDependencyException.class)
        .hasMessageContaining("not ready");
    assertThat(count("warehouse_operation_mark_outbox")).isOne();
    assertThat(count("logistics_warehouse_admission_intent")).isZero();
    verify(dependencies, never())
        .warehouseAdmission(any(UUID.class), any(WarehouseOperationDirection.class));
  }

  @Test
  void directionMismatchedAdmissionMarkReturnsServiceUnavailableWithoutRemoteCalls()
      throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    stubAdmissionSuccess();
    LogisticsDocumentService.CreateResult created =
        documents.createReturn(
            SUBJECT,
            idempotencyKey,
            UUID.randomUUID(),
            request(7),
            prepare(idempotencyKey));
    jdbc.update(
        """
        update warehouse_operation_mark_outbox
           set admission_direction='OUTGOING'
         where warehouse_id=? and operation_id=?
        """,
        WAREHOUSE,
        created.response().id());

    reset(dependencies);
    when(dependencies.productionReady()).thenReturn(false);

    performCreate(idempotencyKey, request(7))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("LOGISTICS_DEPENDENCY_UNAVAILABLE"));
    assertThat(count("logistics_document")).isOne();
    verify(dependencies, never())
        .warehouseAdmission(any(UUID.class), any(WarehouseOperationDirection.class));
  }

  @Test
  void expiredDocumentIdempotencyIsNotAdmissionProof() throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    stubAdmissionSuccess();
    documents.createReturn(
        SUBJECT,
        idempotencyKey,
        UUID.randomUUID(),
        request(7),
        prepare(idempotencyKey));
    jdbc.update(
        """
        update logistics_idempotency_record
           set created_at=clock_timestamp() - interval '2 days',
               expires_at=clock_timestamp() - interval '1 day'
         where subject_id=? and operation_name='CREATE_RETURN' and idempotency_key=?
        """,
        SUBJECT,
        idempotencyKey);

    reset(dependencies);
    when(dependencies.productionReady()).thenReturn(false);

    performCreate(idempotencyKey, request(7))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("LOGISTICS_DEPENDENCY_UNAVAILABLE"));
    assertThat(count("logistics_document")).isOne();
    verify(dependencies, never())
        .warehouseAdmission(any(UUID.class), any(WarehouseOperationDirection.class));
  }

  @Test
  void exactShipmentReplaySucceedsWhileAdmissionDependencyIsUnavailable() throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    CreateShipmentRequest request = shipmentRequest();
    stubAdmission(
        WAREHOUSE, WarehouseOperationDirection.OUTGOING, 17);
    AdmissionTicket admission =
        lifecycle.prepareDocument(
            SUBJECT,
            "CREATE_SHIPMENT",
            idempotencyKey,
            List.of(
                new AdmissionRequirement(
                    WAREHOUSE, WarehouseOperationDirection.OUTGOING)));
    LogisticsDocumentService.CreateResult created =
        documents.createShipment(
            SUBJECT, idempotencyKey, UUID.randomUUID(), request, admission);

    reset(dependencies);
    when(dependencies.productionReady()).thenReturn(false);

    performShipment(idempotencyKey, request)
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"));
    assertThat(count("logistics_document")).isOne();
    assertThat(count("warehouse_operation_mark_outbox")).isOne();
    assertThat(
            jdbc.queryForObject(
                "select admission_warehouse_version from warehouse_operation_mark_outbox where operation_id=?",
                Long.class,
                created.response().id()))
        .isEqualTo(17);
    verify(dependencies, never())
        .warehouseAdmission(any(UUID.class), any(WarehouseOperationDirection.class));
  }

  @Test
  void exactTransferReplaySucceedsDownAndChangedPayloadOrWarehousesConflictWithoutMutation()
      throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    CreateTransferRequest request = transferRequest(7);
    stubTransferAdmission();
    AdmissionTicket admission = prepareTransfer(idempotencyKey);
    LogisticsDocumentService.CreateResult created =
        documents.createTransfer(
            SUBJECT, idempotencyKey, UUID.randomUUID(), request, admission);
    Map<String, Object> documentBefore =
        jdbc.queryForMap(
            """
            select warehouse_id,destination_warehouse_id,document_type,state,version
              from logistics_document where id=?
            """,
            created.response().id());
    List<Map<String, Object>> marksBefore =
        jdbc.queryForList(
            """
            select warehouse_id,admission_direction,admission_warehouse_version,state,attempt_count
              from warehouse_operation_mark_outbox
             where operation_id=? order by warehouse_id
            """,
            created.response().id());

    reset(dependencies);
    when(dependencies.productionReady()).thenReturn(false);

    performTransfer(idempotencyKey, request)
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"));
    performTransfer(idempotencyKey, transferRequest(8))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LOGISTICS_CONFLICT"));
    CreateTransferRequest swappedWarehouses =
        new CreateTransferRequest(
            request.destinationWarehouseId(),
            request.warehouseId(),
            request.driverSnapshot(),
            request.scheduledDate(),
            request.lines(),
            request.furnitureReplacements());
    performTransfer(idempotencyKey, swappedWarehouses)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LOGISTICS_CONFLICT"));
    assertThat(count("logistics_document")).isOne();
    assertThat(count("warehouse_operation_mark_outbox")).isEqualTo(2);
    assertThat(
            jdbc.queryForMap(
                """
                select warehouse_id,destination_warehouse_id,document_type,state,version
                  from logistics_document where id=?
                """,
                created.response().id()))
        .isEqualTo(documentBefore);
    assertThat(
            jdbc.queryForList(
                """
                select warehouse_id,admission_direction,admission_warehouse_version,state,attempt_count
                  from warehouse_operation_mark_outbox
                 where operation_id=? order by warehouse_id
                """,
                created.response().id()))
        .isEqualTo(marksBefore);
    verifyNoInteractions(dependencies);
  }

  @Test
  void subsetTransferAdmissionEvidenceReturnsServiceUnavailable() throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    CreateTransferRequest request = transferRequest(7);
    stubTransferAdmission();
    LogisticsDocumentService.CreateResult created =
        documents.createTransfer(
            SUBJECT,
            idempotencyKey,
            UUID.randomUUID(),
            request,
            prepareTransfer(idempotencyKey));
    jdbc.update(
        "delete from warehouse_operation_mark_outbox where operation_id=? and warehouse_id=?",
        created.response().id(),
        DESTINATION_WAREHOUSE);

    reset(dependencies);
    when(dependencies.productionReady()).thenReturn(false);

    performTransfer(idempotencyKey, request)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("LOGISTICS_DEPENDENCY_UNAVAILABLE"));
    assertThat(count("logistics_document")).isOne();
    assertThat(count("warehouse_operation_mark_outbox")).isOne();
    verify(dependencies, never())
        .warehouseAdmission(any(UUID.class), any(WarehouseOperationDirection.class));
  }

  @Test
  void supersetTransferAdmissionEvidenceReturnsServiceUnavailable() throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    CreateTransferRequest request = transferRequest(7);
    stubTransferAdmission();
    LogisticsDocumentService.CreateResult created =
        documents.createTransfer(
            SUBJECT,
            idempotencyKey,
            UUID.randomUUID(),
            request,
            prepareTransfer(idempotencyKey));
    jdbc.update(
        """
        insert into warehouse_operation_mark_outbox(
          operation_id,warehouse_id,occurred_at,admission_direction,
          admission_warehouse_version,state,attempt_count,next_attempt_at,created_at,updated_at)
        values (?, ?, clock_timestamp(),'INCOMING',29,'PENDING',0,clock_timestamp(),
          clock_timestamp(),clock_timestamp())
        """,
        created.response().id(),
        UUID.fromString("00000000-0000-0000-0000-00000000a305"));

    reset(dependencies);
    when(dependencies.productionReady()).thenReturn(false);

    performTransfer(idempotencyKey, request)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("LOGISTICS_DEPENDENCY_UNAVAILABLE"));
    assertThat(count("logistics_document")).isOne();
    assertThat(count("warehouse_operation_mark_outbox")).isEqualTo(3);
    verify(dependencies, never())
        .warehouseAdmission(any(UUID.class), any(WarehouseOperationDirection.class));
  }

  @Test
  void mismatchedTicketVersionCannotConsumeTheAdmittedIntent() {
    UUID idempotencyKey = UUID.randomUUID();
    stubAdmissionSuccess();
    AdmissionTicket admitted = prepare(idempotencyKey);
    AdmissionTicket changedVersion =
        AdmissionTicket.remote(
            admitted.operationId(),
            admitted.requirements(),
            admitted.occurredAt(),
            admitted.localDates(),
            List.of(
                new AdmissionEvidence(
                    WAREHOUSE, WarehouseOperationDirection.INCOMING, 12)));

    assertThatThrownBy(
            () ->
                documents.createReturn(
                    SUBJECT,
                    idempotencyKey,
                    UUID.randomUUID(),
                    request(7),
                    changedVersion))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("missing or expired");
    assertNoDocumentOrEvent();
    assertThat(count("warehouse_operation_mark_outbox")).isZero();
  }

  @Test
  void rolledBackOwnerTransactionLeavesNeitherDocumentNorAdmissionProof() {
    OffsetDateTime occurredAt = OffsetDateTime.now();
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> {
                      LogisticsDocument document =
                          documentRepository.saveAndFlush(
                              LogisticsDocument.createReturn(
                                  WAREHOUSE, SUBJECT, UUID.randomUUID()));
                      operationMarks.enqueue(
                          WAREHOUSE,
                          document.getId(),
                          occurredAt,
                          new AdmissionEvidence(
                              WAREHOUSE, WarehouseOperationDirection.INCOMING, 31));
                      throw new IllegalStateException("force owner rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("force owner rollback");

    assertThat(count("logistics_document")).isZero();
    assertThat(count("warehouse_operation_mark_outbox")).isZero();
  }

  @Test
  void confirmedOperationMarkRemainsPermanentAndTheLiveDocumentBlocksReadiness() {
    UUID idempotencyKey = UUID.randomUUID();
    stubAdmissionSuccess();
    AdmissionTicket admitted = prepare(idempotencyKey);
    LogisticsDocumentService.CreateResult created =
        documents.createReturn(
            SUBJECT, idempotencyKey, UUID.randomUUID(), request(7), admitted);

    LogisticsWarehouseOperationMarkStore.WorkItem work =
        operationMarks.claimNext(Duration.ofSeconds(30)).orElseThrow();
    operationMarks.confirmed(work);

    assertThat(work.operationId()).isEqualTo(created.response().id());
    assertThat(
            jdbc.queryForObject(
                "select state from warehouse_operation_mark_outbox where warehouse_id=? and operation_id=?",
                String.class,
                WAREHOUSE,
                created.response().id()))
        .isEqualTo("CONFIRMED");
    LogisticsWarehouseLifecycleStore.ReadinessAttempt readiness =
        lifecycleStore.beginReadiness(WAREHOUSE, 14);
    assertThat(readiness.shouldConfirm()).isFalse();
    assertThat(count("warehouse_operation_mark_outbox")).isOne();
  }

  private AdmissionTicket prepare(UUID idempotencyKey) {
    return lifecycle.prepareDocument(
        SUBJECT,
        "CREATE_RETURN",
        idempotencyKey,
        List.of(
            new AdmissionRequirement(
                WAREHOUSE, WarehouseOperationDirection.INCOMING)));
  }

  private AdmissionTicket prepareTransfer(UUID idempotencyKey) {
    return lifecycle.prepareDocument(
        SUBJECT,
        "CREATE_TRANSFER",
        idempotencyKey,
        List.of(
            new AdmissionRequirement(
                WAREHOUSE, WarehouseOperationDirection.OUTGOING),
            new AdmissionRequirement(
                DESTINATION_WAREHOUSE, WarehouseOperationDirection.INCOMING)));
  }

  private void stubAdmissionSuccess() {
    stubAdmission(WAREHOUSE, WarehouseOperationDirection.INCOMING, 13);
  }

  private void stubTransferAdmission() {
    stubAdmission(WAREHOUSE, WarehouseOperationDirection.OUTGOING, 19);
    stubAdmission(DESTINATION_WAREHOUSE, WarehouseOperationDirection.INCOMING, 23);
  }

  private void stubAdmission(
      UUID warehouseId, WarehouseOperationDirection direction, long version) {
    when(dependencies.productionReady()).thenReturn(true);
    when(dependencies.warehouseAdmission(warehouseId, direction))
        .thenReturn(
            new WarehouseOperationAdmission(
                warehouseId,
                version,
                WarehouseLifecycleState.ACTIVE,
                direction,
                true));
    when(dependencies.warehouseTimeZoneAt(eq(warehouseId), any(OffsetDateTime.class)))
        .thenReturn(new WarehouseTimeZone(warehouseId, "UTC", TIME_ZONE_EFFECTIVE_FROM));
  }

  private ResultActions performCreate(UUID idempotencyKey, CreateReturnRequest request)
      throws Exception {
    return mvc.perform(
        post("/api/logistics/v1/returns")
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "warehouseId": "%s",
                  "lines": [
                    {
                      "assetId": "%s",
                      "assetVersion": %d,
                      "tenantSnapshot": "Tenant A"
                    }
                  ]
                }
                """
                    .formatted(WAREHOUSE, ASSET, request.lines().getFirst().assetVersion()))
            .with(
                jwt()
                    .jwt(
                        token ->
                            token.subject(SUBJECT.toString())
                                .claim("principal_type", "USER")
                                .claim("scope", "rwms.write")
                                .claim(
                                    "warehouse_access",
                                    List.of(
                                        Map.of(
                                            "warehouseId",
                                            WAREHOUSE.toString(),
                                            "level",
                                            "EDIT"))))));
  }

  private ResultActions performShipment(
      UUID idempotencyKey, CreateShipmentRequest request) throws Exception {
    return mvc.perform(
        post("/api/logistics/v1/shipments")
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "warehouseId": "%s",
                  "partySnapshot": "%s",
                  "driverSnapshot": "%s",
                  "lines": [{"assetId":"%s","assetVersion":%d,"allocations":[]}]
                }
                """
                    .formatted(
                        request.warehouseId(),
                        request.partySnapshot(),
                        request.driverSnapshot(),
                        request.lines().getFirst().assetId(),
                        request.lines().getFirst().assetVersion()))
            .with(editor()));
  }

  private ResultActions performTransfer(
      UUID idempotencyKey, CreateTransferRequest request) throws Exception {
    return mvc.perform(
        post("/api/logistics/v1/transfers")
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "warehouseId": "%s",
                  "destinationWarehouseId": "%s",
                  "driverSnapshot": "%s",
                  "scheduledDate": "%s",
                  "lines": [{"assetId":"%s","assetVersion":%d}],
                  "furnitureReplacements": []
                }
                """
                    .formatted(
                        request.warehouseId(),
                        request.destinationWarehouseId(),
                        request.driverSnapshot(),
                        request.scheduledDate(),
                        request.lines().getFirst().assetId(),
                        request.lines().getFirst().assetVersion()))
            .with(
                jwt()
                    .jwt(
                        token ->
                            token.subject(SUBJECT.toString())
                                .claim("principal_type", "USER")
                                .claim("scope", "rwms.write")
                                .claim(
                                    "warehouse_access",
                                    List.of(
                                        Map.of(
                                            "warehouseId",
                                            WAREHOUSE.toString(),
                                            "level",
                                            "EDIT"),
                                        Map.of(
                                            "warehouseId",
                                            DESTINATION_WAREHOUSE.toString(),
                                            "level",
                                            "EDIT"))))));
  }

  private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
      editor() {
    return jwt()
        .jwt(
            token ->
                token.subject(SUBJECT.toString())
                    .claim("principal_type", "USER")
                    .claim("scope", "rwms.write")
                    .claim(
                        "warehouse_access",
                        List.of(
                            Map.of(
                                "warehouseId", WAREHOUSE.toString(), "level", "EDIT"))));
  }

  private static CreateReturnRequest request(long assetVersion) {
    return new CreateReturnRequest(
        WAREHOUSE,
        List.of(new ReturnLineRequest(ASSET, assetVersion, "Tenant A")));
  }

  private static CreateShipmentRequest shipmentRequest() {
    return new CreateShipmentRequest(
        WAREHOUSE,
        "Tenant A",
        "Driver A",
        List.of(new ShipmentLineRequest(ASSET, 7)));
  }

  private static CreateTransferRequest transferRequest(long assetVersion) {
    return new CreateTransferRequest(
        WAREHOUSE,
        DESTINATION_WAREHOUSE,
        "Driver A",
        LocalDate.now(ZoneOffset.UTC).plusDays(2),
        List.of(new TransferLineRequest(ASSET, assetVersion)),
        List.of());
  }

  private void assertNoDurableCreate() {
    assertNoDocumentOrEvent();
    assertThat(count("logistics_warehouse_admission_intent")).isZero();
    assertThat(count("warehouse_operation_mark_outbox")).isZero();
  }

  private void assertNoDocumentOrEvent() {
    assertThat(count("logistics_document")).isZero();
    assertThat(count("domain_event")).isZero();
    assertThat(count("outbox_event")).isZero();
  }

  private long count(String table) {
    Long value = jdbc.queryForObject("select count(*) from " + table, Long.class);
    return value == null ? 0 : value;
  }
}
