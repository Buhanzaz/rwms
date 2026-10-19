package dev.buhanzaz.rwms.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves logistics recovery owns only its dedicated trigger scheduler while preserving Boot's
 * conventional scheduler for unrelated jobs. It also verifies shutdown rejection defers an
 * already-claimed durable row instead of leaving a new lease stranded in the database.
 */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.return-registration.relay-enabled=true",
      "rwms.logistics.return-registration.relay-delay=PT24H",
      "rwms.logistics.return-registration.relay-initial-delay=PT24H",
      "rwms.logistics.return-completion.relay-enabled=false",
      "rwms.logistics.shipment.relay-enabled=false",
      "rwms.logistics.transfer.relay-enabled=false",
      "rwms.logistics.owner-proof.relay-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LogisticsExternalAttemptSchedulerConfigurationIntegrationTest {
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired ApplicationContext context;
  @Autowired JdbcTemplate jdbc;
  @Autowired ReturnRegistrationRelay returnRegistrationRelay;
  @Autowired LogisticsExternalAttemptRelayExecutor relayExecutor;

  @Autowired
  @Qualifier("taskScheduler")
  TaskScheduler defaultScheduler;

  @Autowired
  @Qualifier("logisticsExternalAttemptTriggerScheduler")
  TaskScheduler logisticsTriggerScheduler;

  @Autowired
  @Qualifier("logisticsExternalAttemptWorkerExecutor")
  ThreadPoolTaskExecutor workerExecutor;

  @BeforeEach
  void clearDurableRows() {
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
  }

  @Test
  void preservesTheDefaultSchedulerAndPinsEveryAttemptRelayToTheDedicatedTrigger() {
    assertThat(context.getBeanNamesForType(TaskScheduler.class))
        .contains("taskScheduler", "logisticsExternalAttemptTriggerScheduler");
    assertThat(defaultScheduler).isNotSameAs(logisticsTriggerScheduler);
    assertThat(workerExecutor).isNotSameAs(defaultScheduler).isNotSameAs(logisticsTriggerScheduler);

    for (Class<?> relay :
        List.of(
            ReturnRegistrationRelay.class,
            ReturnCompletionRelay.class,
            ShipmentRelay.class,
            TransferRelay.class,
            MediaOwnerProofRelay.class)) {
      List<Scheduled> schedules =
          Arrays.stream(relay.getDeclaredMethods())
              .map(method -> method.getAnnotation(Scheduled.class))
              .filter(scheduled -> scheduled != null)
              .toList();
      assertThat(schedules).hasSize(1);
      assertThat(schedules.getFirst().scheduler())
          .isEqualTo("logisticsExternalAttemptTriggerScheduler");
    }
  }

  @Test
  void shutdownRejectionDefersTheClaimAndClearsItsLease() {
    UUID documentId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,requested_by_subject_id,correlation_id,
          created_at,updated_at)
        values (?,0,'RETURN','REGISTERING',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        documentId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID());
    jdbc.update(
        """
        insert into logistics_external_attempt(
          id,document_id,line_id,operation_id,target_service,operation_type,request_sha256,
          result,retry_count,next_attempt_at,correlation_id,created_at)
        values (?,?,null,?,'ASSET','RETURN_WAREHOUSE_IDENTITY',
          'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
          'PENDING',0,clock_timestamp(),?,clock_timestamp())
        """,
        attemptId,
        documentId,
        UUID.randomUUID(),
        UUID.randomUUID());

    relayExecutor.stopAccepting();
    returnRegistrationRelay.relayDueAttempts();

    assertThat(
            jdbc.queryForObject(
                """
                select lease_token is null
                  and lease_expires_at is null
                  and next_attempt_at > clock_timestamp()
                from logistics_external_attempt
                where id=?
                """,
                Boolean.class,
                attemptId))
        .isTrue();
  }
}
