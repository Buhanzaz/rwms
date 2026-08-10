package dev.buhanzaz.rwms.logistics.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateShipmentRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentLineRequest;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
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
      "AUTH_ISSUER=http://issuer.invalid",
      "PANEL_ORIGIN=http://localhost:5173"
    })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LogisticsEventStoreReplayIntegrationTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000901");
  private static final UUID SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-000000000902");
  private static final UUID CORRELATION =
      UUID.fromString("00000000-0000-0000-0000-000000000903");
  private static final UUID DRIVER_QUEUE_DEFINITION =
      UUID.fromString("00000000-0000-0000-0000-000000000904");
  private static final UUID DRIVER_QUEUE_CATEGORY =
      UUID.fromString("00000000-0000-0000-0000-000000000905");

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired LogisticsDocumentService documents;
  @Autowired LogisticsReplayVerifier replay;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean LogisticsDependencyGateway dependencies;

  @BeforeEach
  void reset() {
    org.mockito.Mockito.reset(dependencies);
    when(dependencies.readWarehouseDriverQueue(WAREHOUSE))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseDriverQueue(
                WAREHOUSE, DRIVER_QUEUE_DEFINITION, DRIVER_QUEUE_CATEGORY));
    when(dependencies.readRentalItemSnapshot(any()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.RentalItemSnapshot(
                    invocation.getArgument(0),
                    7,
                    WAREHOUSE,
                    "БТ-QA",
                    "FREE",
                    List.of()));
    jdbc.execute(
        """
        truncate table
          driver_logistics_task,
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
  void deterministicallyReplaysSnapshotsAndMatchesTheLiveJpaProjection() {
    var createdReturn =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateReturnRequest(
                WAREHOUSE,
                List.of(new ReturnLineRequest(UUID.randomUUID(), 3, "Tenant snapshot"))));
    ShipmentLineRequest shipmentLine = new ShipmentLineRequest(UUID.randomUUID(), 4);
    documents.createShipment(
        SUBJECT,
        UUID.randomUUID(),
        CORRELATION,
        new CreateShipmentRequest(
            WAREHOUSE, "Party snapshot", "Driver snapshot", List.of(shipmentLine)));

    LogisticsReplayVerifier.ReplayParityResult first = replay.rebuildAndVerify();
    LogisticsReplayVerifier.ReplayParityResult repeated = replay.rebuildAndVerify();

    assertThat(first).isEqualTo(repeated);
    assertThat(first.aggregateCount()).isEqualTo(2);
    assertThat(first.eventCount()).isEqualTo(3);
    assertThat(first.canonicalChecksum()).matches("^[0-9a-f]{64}$");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from projection_checkpoint where projection_name=?",
                Integer.class,
                LogisticsReplayVerifier.SHADOW_PROJECTION))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from aggregate_snapshot where aggregate_id=?",
                Integer.class,
                createdReturn.response().id().toString()))
        .isOne();
  }

  @Test
  void rejectsLiveProjectionDriftWithoutRewritingTheDocument() {
    var created =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateReturnRequest(
                WAREHOUSE,
                List.of(new ReturnLineRequest(UUID.randomUUID(), 2, "Tenant snapshot"))));
    jdbc.update(
        "update logistics_document set state='CONFLICT' where id=?", created.response().id());

    assertThatThrownBy(replay::rebuildAndVerify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("live JPA projection parity mismatch");
    assertThat(
            jdbc.queryForObject(
                "select state from logistics_document where id=?",
                String.class,
                created.response().id()))
        .isEqualTo("CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from projection_checkpoint where projection_name=?",
                Integer.class,
                LogisticsReplayVerifier.SHADOW_PROJECTION))
        .isZero();
  }

  @Test
  void replayUsesOneRepeatableReadTransaction() throws Exception {
    Transactional transaction =
        LogisticsReplayVerifier.class
            .getMethod("rebuildAndVerify")
            .getAnnotation(Transactional.class);

    assertThat(transaction).isNotNull();
    assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
  }

  @Test
  void rejectsPiiAddedToOutboxActorEvenWhenEnvelopeChecksumIsRecomputed() {
    UUID documentId = createReturn();
    rewriteEnvelope(
        documentId,
        "jsonb_set(envelope_body, '{actorRef,email}', to_jsonb('leak@example.test'::text), true)");

    assertThatThrownBy(replay::rebuildAndVerify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("actorRef")
        .hasMessageContaining("non-canonical shape");
  }

  @Test
  void rejectsCorrelationDriftEvenWhenEnvelopeChecksumIsRecomputed() {
    UUID documentId = createReturn();
    UUID changedCorrelation = UUID.randomUUID();
    rewriteEnvelope(
        documentId,
        "jsonb_set(envelope_body, '{correlation,correlationId}', to_jsonb('"
            + changedCorrelation
            + "'::text), false)");

    assertThatThrownBy(replay::rebuildAndVerify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outbox parity mismatch");
  }

  @Test
  void rejectsUnsafeResultCodeInTheAuthoritativeEventPayload() {
    UUID documentId = createReturn();
    jdbc.update(
        """
        update domain_event
        set payload=jsonb_set(payload,'{resultCode}',to_jsonb('operator@example.test'::text),false),
            payload_sha256=encode(sha256(convert_to(
              jsonb_set(payload,'{resultCode}',to_jsonb('operator@example.test'::text),false)::text,
              'UTF8')),'hex')
        where aggregate_id=?
        """,
        documentId.toString());

    assertThatThrownBy(replay::rebuildAndVerify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("payload identity mismatch");
  }

  private UUID createReturn() {
    return documents
        .createReturn(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateReturnRequest(
                WAREHOUSE,
                List.of(new ReturnLineRequest(UUID.randomUUID(), 2, "Tenant snapshot"))))
        .response()
        .id();
  }

  private void rewriteEnvelope(UUID documentId, String expression) {
    jdbc.execute(
        """
        with changed as (
          select event_id, %s as body from outbox_event where aggregate_id='%s'
        )
        update outbox_event target
        set envelope_body=changed.body,
            envelope_sha256=encode(sha256(convert_to(changed.body::text,'UTF8')),'hex')
        from changed where target.event_id=changed.event_id
        """
            .formatted(expression, documentId));
  }
}
