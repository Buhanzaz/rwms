package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateShipmentRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateTransferRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferLineRequest;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.MediaOwnerProofProcessor;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
      "rwms.logistics.owner-proof.relay-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaOwnerProofWorkflowIntegrationTest {
  private static final UUID ORIGIN = UUID.fromString("00000000-0000-0000-0000-000000001101");
  private static final UUID DESTINATION =
      UUID.fromString("00000000-0000-0000-0000-000000001102");
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000001103");
  private static final UUID CORRELATION =
      UUID.fromString("00000000-0000-0000-0000-000000001104");
  private static final UUID RETURN_ASSET =
      UUID.fromString("00000000-0000-0000-0000-000000001105");
  private static final UUID TRANSFER_ASSET =
      UUID.fromString("00000000-0000-0000-0000-000000001106");
  private static final UUID SHIPMENT_ASSET =
      UUID.fromString("00000000-0000-0000-0000-000000001107");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired LogisticsDocumentService documents;
  @Autowired MediaOwnerProofProcessor processor;
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
  void registersReturnAtReceivingWarehouseAndTransferOnlyAtDestination() {
    echoProofs();
    var returnDocument =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateReturnRequest(
                ORIGIN, List.of(new ReturnLineRequest(RETURN_ASSET, 4, "Tenant"))));
    var transferDocument =
        documents.createTransfer(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateTransferRequest(
                ORIGIN,
                DESTINATION,
                null,
                futureTaskDate(),
                List.of(new TransferLineRequest(TRANSFER_ASSET, 8)),
                List.of()));
    var shipmentDocument =
        documents.createShipment(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateShipmentRequest(
                ORIGIN,
                "Party",
                "Driver",
                List.of(new ShipmentLineRequest(SHIPMENT_ASSET, 2))));

    assertThat(processor.processUntilIdle(returnDocument.response().id())).isOne();
    assertThat(processor.processUntilIdle(transferDocument.response().id())).isOne();
    assertThat(processor.processUntilIdle(shipmentDocument.response().id())).isZero();

    verify(dependencies)
        .upsertMediaOwnerProof(
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN),
            eq(returnDocument.response().id()),
            eq(returnDocument.response().lines().getFirst().id()),
            eq(ORIGIN),
            eq(0L),
            eq(0L),
            any(UUID.class),
            eq(true));
    verify(dependencies)
        .upsertMediaOwnerProof(
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER),
            eq(transferDocument.response().id()),
            eq(transferDocument.response().lines().getFirst().id()),
            eq(DESTINATION),
            eq(0L),
            eq(0L),
            any(UUID.class),
            eq(true));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_external_attempt "
                    + "where operation_type like '%MEDIA_OWNER_PROOF%' and result='CONFIRMED'",
                Long.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_external_attempt attempt "
                    + "join logistics_document document on document.id=attempt.document_id "
                    + "where document.document_type='SHIPMENT' "
                    + "and attempt.operation_type like '%MEDIA_OWNER_PROOF%'",
                Long.class))
        .isZero();
  }

  @Test
  void retriesAnUnknownOutcomeWithTheSameProofEventAndThenStopsReplaying() {
    var created =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateReturnRequest(
                ORIGIN, List.of(new ReturnLineRequest(RETURN_ASSET, 4, "Tenant"))));
    UUID documentId = created.response().id();
    UUID proofEventId =
        jdbc.queryForObject(
            "select operation_id from logistics_external_attempt "
                + "where operation_type='RETURN_MEDIA_OWNER_PROOF_REGISTER'",
            UUID.class);
    when(dependencies.upsertMediaOwnerProof(
            any(), any(), any(), any(), anyLong(), anyLong(), any(), anyBoolean()))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.TRANSIENT, "media unavailable"))
        .thenAnswer(MediaOwnerProofWorkflowIntegrationTest::echoProof);

    assertThat(processor.processUntilIdle(documentId)).isOne();
    assertThat(
            jdbc.queryForObject(
                "select result || ':' || retry_count from logistics_external_attempt "
                    + "where operation_id=?",
                String.class,
                proofEventId))
        .isEqualTo("RETRY:1");
    jdbc.update(
        "update logistics_external_attempt set next_attempt_at=clock_timestamp() where operation_id=?",
        proofEventId);

    assertThat(processor.processUntilIdle(documentId)).isOne();
    assertThat(processor.processUntilIdle(documentId)).isZero();

    ArgumentCaptor<UUID> proofEvents = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(2))
        .upsertMediaOwnerProof(
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN),
            eq(documentId),
            eq(created.response().lines().getFirst().id()),
            eq(ORIGIN),
            eq(0L),
            eq(0L),
            proofEvents.capture(),
            eq(true));
    assertThat(proofEvents.getAllValues()).containsOnly(proofEventId);
    assertThat(
            jdbc.queryForObject(
                "select result from logistics_external_attempt where operation_id=?",
                String.class,
                proofEventId))
        .isEqualTo("CONFIRMED");
  }

  @Test
  void deactivatesTransferProofContiguouslyWhenDraftCancellationIsReplayed() {
    echoProofs();
    var created =
        documents.createTransfer(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateTransferRequest(
                ORIGIN,
                DESTINATION,
                null,
                futureTaskDate(),
                List.of(new TransferLineRequest(TRANSFER_ASSET, 8)),
                List.of()));
    UUID documentId = created.response().id();
    UUID lineId = created.response().lines().getFirst().id();

    UUID cancellationKey = UUID.randomUUID();
    var cancelled =
        documents.cancelTransfer(
            SUBJECT, cancellationKey, CORRELATION, documentId, created.response().version());
    var replayed =
        documents.cancelTransfer(
            SUBJECT, cancellationKey, CORRELATION, documentId, created.response().version());
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(cancelled.response());
    assertThat(processor.processUntilIdle(documentId)).isEqualTo(2);

    ArgumentCaptor<Long> revisions = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<Long> versions = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<Boolean> active = ArgumentCaptor.forClass(Boolean.class);
    verify(dependencies, times(2))
        .upsertMediaOwnerProof(
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER),
            eq(documentId),
            eq(lineId),
            eq(DESTINATION),
            revisions.capture(),
            versions.capture(),
            any(UUID.class),
            active.capture());
    assertThat(revisions.getAllValues()).containsExactly(0L, 1L);
    assertThat(versions.getAllValues()).containsExactly(0L, 1L);
    assertThat(active.getAllValues()).containsExactly(true, false);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_external_attempt "
                    + "where operation_type='TRANSFER_MEDIA_OWNER_PROOF_DEACTIVATE' "
                    + "and result='CONFIRMED'",
                Long.class))
        .isOne();
  }

  @Test
  void recordsAVisibleReconciliationForPermanentOwnerProofRejection() {
    var created =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateReturnRequest(
                ORIGIN, List.of(new ReturnLineRequest(RETURN_ASSET, 4, "Tenant"))));
    when(dependencies.upsertMediaOwnerProof(
            any(), any(), any(), any(), anyLong(), anyLong(), any(), anyBoolean()))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
                "proof rejected"));

    assertThat(processor.processUntilIdle(created.response().id())).isOne();

    assertThat(
            jdbc.queryForObject(
                "select result from logistics_external_attempt "
                    + "where operation_type='RETURN_MEDIA_OWNER_PROOF_REGISTER'",
                String.class))
        .isEqualTo("RECONCILIATION_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_reconciliation "
                    + "where reason_code='MEDIA_OWNER_PROOF_REJECTED' and state='OPEN'",
                Long.class))
        .isOne();
  }

  private void echoProofs() {
    when(dependencies.upsertMediaOwnerProof(
            any(), any(), any(), any(), anyLong(), anyLong(), any(), anyBoolean()))
        .thenAnswer(MediaOwnerProofWorkflowIntegrationTest::echoProof);
  }

  private static LocalDate futureTaskDate() {
    return LocalDate.now().plusDays(1);
  }

  private static LogisticsDependencyGateway.MediaOwnerProof echoProof(
      org.mockito.invocation.InvocationOnMock invocation) {
    return new LogisticsDependencyGateway.MediaOwnerProof(
        invocation.getArgument(0),
        invocation.getArgument(1),
        invocation.getArgument(2),
        invocation.getArgument(3),
        invocation.getArgument(4),
        invocation.getArgument(5),
        invocation.getArgument(6),
        invocation.getArgument(7));
  }
}
