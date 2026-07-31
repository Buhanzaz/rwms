package dev.buhanzaz.rwms.taskboard.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.domain.TaskLane;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.domain.WorkerClass;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.BoardTaskFact;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventPayloads.WorkerClassFact;
import dev.buhanzaz.rwms.taskboard.repository.WorkerClassRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
class TaskBoardEventingRuntimeIntegrationTest {
  private static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.start();
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired TaskBoardEventStore eventStore;
  @Autowired TaskBoardKafkaOutboxStore outboxStore;
  @Autowired TaskBoardInboxProcessor inboxProcessor;
  @Autowired TaskBoardKafkaCutoverRehearsal cutover;
  @Autowired TaskBoardReplayVerifier replayVerifier;
  @Autowired TaskBoardProjectionWriter projectionWriter;
  @Autowired TaskBoardEventSourcing eventSourcing;
  @Autowired WorkerClassRepository workerClasses;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", postgres::getJdbcUrl);
    properties.add("spring.datasource.username", postgres::getUsername);
    properties.add("spring.datasource.password", postgres::getPassword);
    properties.add("rwms.platform.kafka.enabled", () -> "false");
  }

  @Test
  void cutoverAndEventRuntimePreserveConcurrencyOrderingRecoveryAndReplayParity()
      throws Exception {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    verifyCutoverRehearsal(tx);
    verifyConcurrentCasAndAtomicRollback(tx);
    verifySnapshotThreshold(tx);
    verifyOutboxOrderRetryRecoveryAndInboxQuarantine(tx);
    verifyReplayAgainstLiveProjection(tx);
  }

  private void verifyCutoverRehearsal(TransactionTemplate tx) {
    assertThatThrownBy(
            () ->
                cutover.rehearse(
                    new TaskBoardKafkaCutoverRehearsal.Request(false, 100)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("write-freeze");

    UUID taskId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID externalTaskId = UUID.randomUUID();
    tx.executeWithoutResult(
        ignored ->
            eventStore.initialize(
                TaskBoardAggregateType.BOARD_TASK,
                taskId,
                0,
                TaskBoardEventTypes.BOARD_TASK_CREATED,
                new BoardTaskFact(
                    taskId,
                    warehouseId,
                    externalTaskId,
                    TaskStatus.ACTIVE,
                    LocalDate.of(2026, 7, 24),
                    TaskLane.SCHEDULED,
                    2,
                    true,
                    null,
                    null,
                    null,
                    false)));
    UUID kafkaEventId =
        jdbc.queryForObject(
            "select event_id from domain_event where aggregate_type='BOARD_TASK' and aggregate_id=?",
            UUID.class,
            taskId.toString());
    jdbc.update("delete from outbox_event where event_id=?", kafkaEventId);
    UUID legacyEventId = UUID.randomUUID();
    jdbc.update(
        """
        insert into task_board_outbox(
          event_id,event_type,event_version,routing_key,aggregate_type,aggregate_id,
          aggregate_version,envelope_body,envelope_sha256,correlation_id,
          occurred_at,created_at,status,attempt_count,next_attempt_at)
        values (?,'task-board.board-task.created',1,'task-board.board-task.created.v1',
          'BOARD_TASK',?,0,'{}',?, ?,clock_timestamp(),clock_timestamp(),'PENDING',0,clock_timestamp())
        """,
        legacyEventId,
        taskId.toString(),
        "a".repeat(64),
        UUID.randomUUID());

    var first =
        cutover.rehearse(new TaskBoardKafkaCutoverRehearsal.Request(true, 100));
    assertThat(first.legacyRetargeted()).isOne();
    assertThat(first.ready()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where event_id=?", Integer.class, kafkaEventId))
        .isOne();

    var repeated =
        cutover.rehearse(new TaskBoardKafkaCutoverRehearsal.Request(true, 100));
    assertThat(repeated.legacyAlreadyMirrored()).isZero();
    assertThat(repeated.legacyRetargeted()).isZero();
    assertThat(repeated.legacyUnresolved()).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where event_id=?", Integer.class, kafkaEventId))
        .isOne();

    jdbc.update(
        "update outbox_event set status='PUBLISHED',published_at=clock_timestamp() where event_id=?",
        kafkaEventId);
    jdbc.update(
        """
        insert into inbox_message(
          consumer_group,event_id,aggregate_type,aggregate_id,aggregate_version,
          payload_sha256,status,attempt_count,received_at,processed_at)
        select ?,event_id,aggregate_type,aggregate_id,aggregate_version,
               envelope_sha256,'PROCESSED',0,clock_timestamp(),clock_timestamp()
          from outbox_event where event_id=?
        """,
        TaskBoardAggregateType.CONSUMER_GROUP,
        kafkaEventId);
    jdbc.update(
        """
        insert into consumer_aggregate_checkpoint(
          consumer_group,aggregate_type,aggregate_id,last_event_id,last_aggregate_version,
          blocked,updated_at)
        values (?,'BOARD_TASK',?,?,0,false,clock_timestamp())
        """,
        TaskBoardAggregateType.CONSUMER_GROUP,
        taskId.toString(),
        kafkaEventId);

    var ready =
        cutover.rehearse(new TaskBoardKafkaCutoverRehearsal.Request(true, 100));
    assertThat(ready.ready()).isTrue();
    assertThat(ready.legacyUnpublished()).isOne();
    assertThat(ready.legacyUnresolved()).isZero();
    assertThat(ready.legacyUnmappable()).isZero();
    assertThat(
            jdbc.queryForObject(
                "select status from task_board_outbox where event_id=?",
                String.class,
                legacyEventId))
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from task_board_kafka_cutover_map where legacy_event_id=? and kafka_event_id=?",
                Integer.class,
                legacyEventId,
                kafkaEventId))
        .isOne();
  }

  private void verifyConcurrentCasAndAtomicRollback(TransactionTemplate tx) throws Exception {
    UUID concurrentId = UUID.randomUUID();
    WorkerClassFact concurrentPayload = workerClassFact(concurrentId);
    tx.executeWithoutResult(
        ignored ->
            eventStore.initialize(
                TaskBoardAggregateType.WORKER_CLASS,
                concurrentId,
                0,
                TaskBoardEventTypes.WORKER_CLASS_CREATED,
                concurrentPayload));

    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var attempts =
          List.of(
              executor.submit(() -> appendConcurrently(tx, start, concurrentId, concurrentPayload)),
              executor.submit(() -> appendConcurrently(tx, start, concurrentId, concurrentPayload)));
      start.countDown();
      assertThat(List.of(attempts.get(0).get(), attempts.get(1).get()))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(currentVersion(TaskBoardAggregateType.WORKER_CLASS, concurrentId)).isOne();

    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    tx.executeWithoutResult(
        ignored -> {
          eventStore.initialize(
              TaskBoardAggregateType.WORKER_CLASS,
              firstId,
              0,
              TaskBoardEventTypes.WORKER_CLASS_CREATED,
              workerClassFact(firstId));
          eventStore.initialize(
              TaskBoardAggregateType.WORKER_CLASS,
              secondId,
              0,
              TaskBoardEventTypes.WORKER_CLASS_CREATED,
              workerClassFact(secondId));
        });
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    ignored -> {
                      eventStore.lockStreams(
                          List.of(
                              new TaskBoardEventStore.StreamRef(
                                  TaskBoardAggregateType.WORKER_CLASS, firstId),
                              new TaskBoardEventStore.StreamRef(
                                  TaskBoardAggregateType.WORKER_CLASS, secondId)));
                      eventStore.append(
                          TaskBoardAggregateType.WORKER_CLASS,
                          firstId,
                          0,
                          TaskBoardEventTypes.WORKER_CLASS_CHANGED,
                          workerClassFact(firstId));
                      throw new IllegalStateException("force rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("force rollback");
    assertThat(currentVersion(TaskBoardAggregateType.WORKER_CLASS, firstId)).isZero();
    assertThat(currentVersion(TaskBoardAggregateType.WORKER_CLASS, secondId)).isZero();
  }

  private boolean appendConcurrently(
      TransactionTemplate tx,
      CountDownLatch start,
      UUID aggregateId,
      WorkerClassFact payload)
      throws InterruptedException {
    start.await();
    try {
      tx.executeWithoutResult(
          ignored ->
              eventStore.append(
                  TaskBoardAggregateType.WORKER_CLASS,
                  aggregateId,
                  0,
                  TaskBoardEventTypes.WORKER_CLASS_CHANGED,
                  payload));
      return true;
    } catch (OptimisticLockingFailureException exception) {
      return false;
    }
  }

  private void verifySnapshotThreshold(TransactionTemplate tx) {
    UUID aggregateId = UUID.randomUUID();
    WorkerClassFact payload = workerClassFact(aggregateId);
    tx.executeWithoutResult(
        ignored -> {
          eventStore.initialize(
              TaskBoardAggregateType.WORKER_CLASS,
              aggregateId,
              0,
              TaskBoardEventTypes.WORKER_CLASS_CREATED,
              payload);
          for (long version = 0; version < 99; version++) {
            eventStore.append(
                TaskBoardAggregateType.WORKER_CLASS,
                aggregateId,
                version,
                TaskBoardEventTypes.WORKER_CLASS_CHANGED,
                payload);
          }
        });
    assertThat(
            jdbc.queryForObject(
                "select count(*) from aggregate_snapshot where aggregate_type='WORKER_CLASS' and aggregate_id=?",
                Integer.class,
                aggregateId.toString()))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select aggregate_version from aggregate_snapshot where aggregate_type='WORKER_CLASS' and aggregate_id=?",
                Long.class,
                aggregateId.toString()))
        .isEqualTo(99L);
  }

  private void verifyOutboxOrderRetryRecoveryAndInboxQuarantine(TransactionTemplate tx) {
    jdbc.update(
        """
        update outbox_event set status='PUBLISHED',published_at=clock_timestamp(),
          lease_owner=null,lease_token=null,lease_until=null
         where status='PENDING'
        """);
    UUID orderedId = UUID.randomUUID();
    WorkerClassFact orderedPayload = workerClassFact(orderedId);
    tx.executeWithoutResult(
        ignored -> {
          eventStore.initialize(
              TaskBoardAggregateType.WORKER_CLASS,
              orderedId,
              0,
              TaskBoardEventTypes.WORKER_CLASS_CREATED,
              orderedPayload);
          eventStore.append(
              TaskBoardAggregateType.WORKER_CLASS,
              orderedId,
              0,
              TaskBoardEventTypes.WORKER_CLASS_CHANGED,
              orderedPayload);
        });
    UUID firstEvent = eventId(orderedId, 0);
    UUID secondEvent = eventId(orderedId, 1);
    for (int attempt = 1; attempt <= 4; attempt++) {
      var claim = outboxStore.claim("runtime-test", Duration.ofSeconds(30)).orElseThrow();
      assertThat(claim.eventId()).isEqualTo(firstEvent);
      outboxStore.transientFailure(claim);
      if (attempt < 4) {
        assertThat(outboxStore.claim("runtime-test", Duration.ofSeconds(30))).isEmpty();
        jdbc.update(
            "update outbox_event set next_attempt_at=clock_timestamp()-interval '1 second' where event_id=?",
            firstEvent);
      }
    }
    assertThat(status(firstEvent)).isEqualTo("DLT");
    assertThat(outboxStore.requeue(firstEvent, 4)).isTrue();
    var recovered = outboxStore.claim("runtime-test", Duration.ofSeconds(30)).orElseThrow();
    assertThat(recovered.eventId()).isEqualTo(firstEvent);
    assertThat(outboxStore.published(recovered.eventId(), recovered.leaseToken())).isTrue();
    var successor = outboxStore.claim("runtime-test", Duration.ofSeconds(30)).orElseThrow();
    assertThat(successor.eventId()).isEqualTo(secondEvent);
    assertThat(outboxStore.published(successor.eventId(), successor.leaseToken())).isTrue();

    byte[] firstEnvelope = envelope(firstEvent);
    inboxProcessor.process(firstEnvelope, TaskBoardAggregateType.WORKER_CLASS);
    inboxProcessor.process(firstEnvelope, TaskBoardAggregateType.WORKER_CLASS);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inbox_message where consumer_group=? and event_id=?",
                Integer.class,
                TaskBoardAggregateType.CONSUMER_GROUP,
                firstEvent))
        .isOne();

    UUID gapId = UUID.randomUUID();
    WorkerClassFact gapPayload = workerClassFact(gapId);
    tx.executeWithoutResult(
        ignored -> {
          eventStore.initialize(
              TaskBoardAggregateType.WORKER_CLASS,
              gapId,
              0,
              TaskBoardEventTypes.WORKER_CLASS_CREATED,
              gapPayload);
          eventStore.append(
              TaskBoardAggregateType.WORKER_CLASS,
              gapId,
              0,
              TaskBoardEventTypes.WORKER_CLASS_CHANGED,
              gapPayload);
          eventStore.append(
              TaskBoardAggregateType.WORKER_CLASS,
              gapId,
              1,
              TaskBoardEventTypes.WORKER_CLASS_CHANGED,
              gapPayload);
        });
    UUID gapEvent = eventId(gapId, 2);
    inboxProcessor.process(envelope(gapEvent), TaskBoardAggregateType.WORKER_CLASS);
    assertThat(
            jdbc.queryForObject(
                "select blocked from consumer_aggregate_checkpoint where consumer_group=? and aggregate_type='WORKER_CLASS' and aggregate_id=?",
                Boolean.class,
                TaskBoardAggregateType.CONSUMER_GROUP,
                gapId.toString()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from version_gap_quarantine where received_event_id=? and status='OPEN'",
                Integer.class,
                gapEvent))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select status from inbox_message where consumer_group=? and event_id=?",
                String.class,
                TaskBoardAggregateType.CONSUMER_GROUP,
                gapEvent))
        .isEqualTo("QUARANTINED");
  }

  private void verifyReplayAgainstLiveProjection(TransactionTemplate tx) {
    WorkerClass workerClass =
        tx.execute(
            ignored -> {
              var value = new WorkerClass();
              value.setName("Runtime replay projection");
              value.setSortOrder(500);
              value.setActive(true);
              value = projectionWriter.saveAndFlush(workerClasses, value);
              projectionWriter.refresh(value);
              eventSourcing.created(value);
              return value;
            });
    var replay =
        replayVerifier.verify(TaskBoardAggregateType.WORKER_CLASS, workerClass.getId());
    assertThat(replay.version()).isEqualTo(workerClass.getVersion());
    assertThat(replay.factCount()).isOne();
    assertThat(replay.payloadSha256()).hasSize(64);
  }

  private WorkerClassFact workerClassFact(UUID id) {
    return new WorkerClassFact(id, UUID.randomUUID(), 1, true, false);
  }

  private long currentVersion(TaskBoardAggregateType type, UUID id) {
    return jdbc.queryForObject(
        "select current_version from event_stream_head where aggregate_type=? and aggregate_id=?",
        Long.class,
        type.name(),
        id.toString());
  }

  private UUID eventId(UUID aggregateId, long version) {
    return jdbc.queryForObject(
        "select event_id from outbox_event where aggregate_type='WORKER_CLASS' and aggregate_id=? and aggregate_version=?",
        UUID.class,
        aggregateId.toString(),
        version);
  }

  private String status(UUID eventId) {
    return jdbc.queryForObject(
        "select status from outbox_event where event_id=?", String.class, eventId);
  }

  private byte[] envelope(UUID eventId) {
    String value =
        jdbc.queryForObject(
            "select envelope_body::text from outbox_event where event_id=?",
            String.class,
            eventId);
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  @AfterAll
  static void stopDatabase() {
    postgres.stop();
  }
}
