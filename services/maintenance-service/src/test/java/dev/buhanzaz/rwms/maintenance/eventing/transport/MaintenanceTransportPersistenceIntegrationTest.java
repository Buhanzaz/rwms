package dev.buhanzaz.rwms.maintenance.eventing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.maintenance.service.MaintenanceChecksum;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.task.scheduling.enabled=false",
      "rwms.platform.kafka.enabled=false",
      "rwms.maintenance.dependencies.enabled=false"
    })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MaintenanceTransportPersistenceIntegrationTest {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private static final Queue<MaintenanceInboundEffects.InboundEvent> EFFECTS =
      new ConcurrentLinkedQueue<>();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired MaintenanceInboundEnvelopeValidator validator;
  @Autowired MaintenanceInboundStagingStore staging;
  @Autowired MaintenanceInboxProcessor inbox;
  @Autowired MaintenanceGapRecoveryService gaps;
  @Autowired MaintenanceSanitizedDltPublisher deadLetterPublisher;
  @Autowired MaintenanceSanitizedDltStore deadLetters;
  @Autowired MaintenanceDltRecoveryService recovery;
  @Autowired MaintenanceKafkaOutboxStore outbox;

  @BeforeEach
  void resetTechnicalState() {
    jdbc.execute(
        """
        truncate table maintenance_inbound_correlation,inbox_message,version_gap_quarantine,
          consumer_aggregate_checkpoint,sanitized_dead_letter,maintenance_inbound_replay_message,
          outbox_event,aggregate_snapshot,projection_checkpoint,domain_event,event_stream_head cascade
        """);
    EFFECTS.clear();
  }

  @Test
  void duplicateGapCorrelationAndOperatorReplayRemainAtomicAndVersionOrdered() {
    UUID boardTaskId = UUID.randomUUID();
    UUID externalTaskId = UUID.randomUUID();
    var created = boardTask(boardTaskId, externalTaskId, 0, "task-board.board-task.created.v1");
    var completed = boardTask(boardTaskId, externalTaskId, 1, "task-board.board-task.completed.v1");
    var changed = boardTask(boardTaskId, externalTaskId, 2, "task-board.board-task.changed.v1");
    var cancelled = boardTask(boardTaskId, externalTaskId, 3, "task-board.board-task.cancelled.v1");

    stageAndProcess(created);
    stageAndProcess(completed);
    assertThat(inbox.process(completed)).isEqualTo(MaintenanceInboxProcessor.Outcome.DUPLICATE);

    staging.stage(cancelled);
    assertThat(inbox.process(cancelled)).isEqualTo(MaintenanceInboxProcessor.Outcome.VERSION_GAP);
    staging.markDlt(cancelled.eventId());
    deadLetterPublisher.publishHash(
        cancelled.rawMessageSha256(),
        "VERSION_GAP",
        cancelled.sourceTopic(),
        cancelled.eventId());
    staging.stage(changed);

    UUID quarantineId =
        jdbc.queryForObject(
            "select quarantine_id from version_gap_quarantine where received_event_id=?",
            UUID.class,
            cancelled.eventId());
    assertThat(quarantineId).isNotNull();
    assertThat(gaps.reconcile(quarantineId, List.of(changed.eventId()), UUID.randomUUID()))
        .isTrue();

    UUID dltId =
        jdbc.queryForObject(
            "select dlt_id from sanitized_dead_letter where source_event_id=?",
            UUID.class,
            cancelled.eventId());
    assertThat(dltId).isNotNull();
    assertThat(recovery.approveAndReplay(dltId, 0, UUID.randomUUID())).isTrue();

    assertThat(
            jdbc.queryForObject(
                """
                select last_aggregate_version from consumer_aggregate_checkpoint
                 where consumer_group=? and aggregate_type='BOARD_TASK' and aggregate_id=?
                """,
                Long.class,
                MaintenanceTransportTopics.CONSUMER_GROUP,
                boardTaskId.toString()))
        .isEqualTo(3L);
    assertThat(
            jdbc.queryForObject(
                """
                select blocked from consumer_aggregate_checkpoint
                 where consumer_group=? and aggregate_type='BOARD_TASK' and aggregate_id=?
                """,
                Boolean.class,
                MaintenanceTransportTopics.CONSUMER_GROUP,
                boardTaskId.toString()))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inbox_message where consumer_group=? and status='PROCESSED'",
                Integer.class,
                MaintenanceTransportTopics.CONSUMER_GROUP))
        .isEqualTo(4);
    assertThat(EFFECTS).containsExactly(changed.effectEvent());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from maintenance_inbound_correlation where board_task_id=?",
                Integer.class,
                boardTaskId))
        .isEqualTo(4);
  }

  @Test
  void mediaFactsRemainMonotonicWithoutRequiringContiguousDomainVersions() {
    UUID mediaId = UUID.randomUUID();
    var uploaded = mediaFact(mediaId, 2, "media.media.uploaded.v1", "PROCESSING");
    var ready = mediaFact(mediaId, 4, "media.media.ready.v1", "READY");
    var stale = mediaFact(mediaId, 3, "media.media.uploaded.v1", "PROCESSING");

    stageAndProcess(uploaded);
    stageAndProcess(ready);
    staging.stage(stale);
    assertThat(inbox.process(stale)).isEqualTo(MaintenanceInboxProcessor.Outcome.DUPLICATE);

    assertThat(
            jdbc.queryForObject(
                """
                select last_aggregate_version from consumer_aggregate_checkpoint
                 where consumer_group=? and aggregate_type='MEDIA' and aggregate_id=?
                """,
                Long.class,
                MaintenanceTransportTopics.CONSUMER_GROUP,
                mediaId.toString()))
        .isEqualTo(4L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from version_gap_quarantine where aggregate_id=?",
                Integer.class,
                mediaId.toString()))
        .isZero();
  }

  @Test
  void approvedDltRemainsApprovedWhileAggregateIsBlockedAndCanResumeLater() {
    UUID boardTaskId = UUID.randomUUID();
    UUID externalTaskId = UUID.randomUUID();
    var created = boardTask(boardTaskId, externalTaskId, 0, "task-board.board-task.created.v1");
    var completed = boardTask(boardTaskId, externalTaskId, 1, "task-board.board-task.completed.v1");
    stageAndProcess(created);
    jdbc.update(
        """
        update consumer_aggregate_checkpoint set blocked=true,quarantine_reason='OPERATOR_HOLD'
         where consumer_group=? and aggregate_type='BOARD_TASK' and aggregate_id=?
        """,
        MaintenanceTransportTopics.CONSUMER_GROUP,
        boardTaskId.toString());
    staging.stage(completed);
    assertThat(inbox.process(completed)).isEqualTo(MaintenanceInboxProcessor.Outcome.BLOCKED);
    staging.markDlt(completed.eventId());
    deadLetterPublisher.publishHash(
        completed.rawMessageSha256(),
        "PROCESSING_FAILED",
        completed.sourceTopic(),
        completed.eventId());
    UUID dltId =
        jdbc.queryForObject(
            "select dlt_id from sanitized_dead_letter where source_event_id=?",
            UUID.class,
            completed.eventId());

    assertThat(recovery.approveAndReplay(dltId, 0, UUID.randomUUID())).isFalse();
    assertThat(
            jdbc.queryForObject(
                "select replay_status from sanitized_dead_letter where dlt_id=?",
                String.class,
                dltId))
        .isEqualTo("APPROVED");
    assertThat(
            jdbc.queryForObject(
                "select state from maintenance_inbound_replay_message where event_id=?",
                String.class,
                completed.eventId()))
        .isEqualTo("REPLAY_APPROVED");

    jdbc.update(
        """
        update consumer_aggregate_checkpoint set blocked=false,quarantine_reason=null
         where consumer_group=? and aggregate_type='BOARD_TASK' and aggregate_id=?
        """,
        MaintenanceTransportTopics.CONSUMER_GROUP,
        boardTaskId.toString());
    assertThat(recovery.resumeApprovedReplay(dltId, 1)).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select replay_status from sanitized_dead_letter where dlt_id=?",
                String.class,
                dltId))
        .isEqualTo("REPLAYED");
  }

  @Test
  void sanitizedDltIsDeterministicLeaseFencedAndContainsNoOriginalBody() {
    byte[] rejected = "password=never-persist canary@example.test".getBytes(StandardCharsets.UTF_8);
    String hash = MaintenanceChecksum.sha256(rejected);
    deadLetterPublisher.publish(rejected, "VALIDATION_REJECTED", MaintenanceTransportTopics.MEDIA, null);
    deadLetterPublisher.publish(rejected, "VALIDATION_REJECTED", MaintenanceTransportTopics.MEDIA, null);

    assertThat(
            jdbc.queryForObject(
                "select count(*) from sanitized_dead_letter where message_sha256=?",
                Integer.class,
                hash))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select safe_body::text from sanitized_dead_letter where message_sha256=?",
                String.class,
                hash))
        .doesNotContain("password", "never-persist", "canary@example.test")
        .contains(hash, "VALIDATION_REJECTED");

    var first = deadLetters.claim("node-a", Duration.ofSeconds(30)).orElseThrow();
    jdbc.update(
        "update sanitized_dead_letter set lease_until=clock_timestamp()-interval '1 second' where dlt_id=?",
        first.dltId());
    var reclaimed = deadLetters.claim("node-b", Duration.ofSeconds(30)).orElseThrow();

    assertThat(reclaimed.leaseToken()).isNotEqualTo(first.leaseToken());
    assertThat(deadLetters.markPublished(first)).isFalse();
    assertThat(deadLetters.markPublished(reclaimed)).isTrue();
  }

  @Test
  void outboxClaimIsPredecessorOrderedAndAckMarkIsLeaseTokenFenced() {
    UUID aggregateId = UUID.randomUUID();
    UUID firstEventId = UUID.randomUUID();
    UUID secondEventId = UUID.randomUUID();
    seedOutboxStream(aggregateId, firstEventId, secondEventId);

    var first = outbox.claim("node-a", Duration.ofSeconds(30)).orElseThrow();
    assertThat(first.eventId()).isEqualTo(firstEventId);
    assertThat(outbox.claim("node-b", Duration.ofSeconds(30))).isEmpty();
    assertThat(outbox.markPublished(first.eventId(), first.leaseToken())).isTrue();

    var second = outbox.claim("node-a", Duration.ofSeconds(30)).orElseThrow();
    assertThat(second.eventId()).isEqualTo(secondEventId);
    jdbc.update(
        "update outbox_event set lease_until=clock_timestamp()-interval '1 second' where event_id=?",
        secondEventId);
    var reclaimed = outbox.claim("node-b", Duration.ofSeconds(30)).orElseThrow();

    assertThat(reclaimed.leaseToken()).isNotEqualTo(second.leaseToken());
    assertThat(outbox.markPublished(second.eventId(), second.leaseToken())).isFalse();
    assertThat(outbox.markPublished(reclaimed.eventId(), reclaimed.leaseToken())).isTrue();
  }

  @Test
  void reviewedOutboxRecoveryPreservesStreamOrderForDltAndQuarantine() {
    UUID aggregateId = UUID.randomUUID();
    UUID firstEventId = UUID.randomUUID();
    UUID secondEventId = UUID.randomUUID();
    seedOutboxStream(aggregateId, firstEventId, secondEventId);

    for (int attempt = 0; attempt < 4; attempt++) {
      var claim = outbox.claim("node-a", Duration.ofSeconds(30)).orElseThrow();
      assertThat(claim.eventId()).isEqualTo(firstEventId);
      outbox.transientFailure(claim);
      if (attempt < 3) {
        jdbc.update(
            "update outbox_event set next_attempt_at=clock_timestamp() where event_id=?",
            firstEventId);
      }
    }
    assertThat(outbox.claim("node-b", Duration.ofSeconds(30))).isEmpty();

    UUID firstReviewer = UUID.randomUUID();
    assertThat(outbox.resumeAfterReview(firstEventId, 0, firstReviewer, "broker recovered"))
        .isTrue();
    assertThat(outbox.resumeAfterReview(firstEventId, 0, UUID.randomUUID(), "stale review"))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select review_subject_id from outbox_event where event_id=?",
                UUID.class,
                firstEventId))
        .isEqualTo(firstReviewer);
    var recoveredFirst = outbox.claim("node-b", Duration.ofSeconds(30)).orElseThrow();
    assertThat(recoveredFirst.eventId()).isEqualTo(firstEventId);
    assertThat(outbox.markPublished(recoveredFirst.eventId(), recoveredFirst.leaseToken())).isTrue();

    var second = outbox.claim("node-b", Duration.ofSeconds(30)).orElseThrow();
    assertThat(second.eventId()).isEqualTo(secondEventId);
    outbox.validationFailure(second, "INVALID_ENVELOPE");
    assertThat(outbox.resumeAfterReview(secondEventId, 0, UUID.randomUUID(), "schema reviewed"))
        .isTrue();
    var recoveredSecond = outbox.claim("node-c", Duration.ofSeconds(30)).orElseThrow();
    assertThat(recoveredSecond.eventId()).isEqualTo(secondEventId);
    assertThat(outbox.markPublished(recoveredSecond.eventId(), recoveredSecond.leaseToken())).isTrue();
  }

  private void stageAndProcess(
      MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent event) {
    staging.stage(event);
    assertThat(inbox.process(event)).isEqualTo(MaintenanceInboxProcessor.Outcome.PROCESSED);
  }

  private MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent boardTask(
      UUID aggregateId, UUID externalTaskId, long version, String eventType) {
    byte[] raw =
        json(
            """
            {
              "envelopeVersion":2,"eventId":"%s","eventType":"%s","eventVersion":1,
              "occurredAt":"2026-07-17T00:00:00Z","recordedAt":"2026-07-17T00:00:00Z",
              "producer":"task-board-service","aggregateType":"BOARD_TASK","aggregateId":"%s",
              "aggregateVersion":%d,"correlation":{"correlationId":"%s","causationId":null},
              "actorRef":null,"payload":{"boardTaskId":"%s","warehouseId":"%s",
              "externalTaskId":"%s","status":"DONE","scheduledDate":"2026-07-17",
              "priority":3,"pinned":false,"plannedDurationMinutes":10,
              "deadlineAt":null,"doneAt":"2026-07-17T00:00:00Z","deleted":false}
            }
            """
                .formatted(
                    UUID.randomUUID(),
                    eventType,
                    aggregateId,
                    version,
                    UUID.randomUUID(),
                    aggregateId,
                    UUID.randomUUID(),
                    externalTaskId));
    return validator.validate(
        MaintenanceTransportTopics.BOARD_TASK,
        aggregateId.toString().getBytes(StandardCharsets.UTF_8),
        raw);
  }

  private MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent mediaFact(
      UUID aggregateId, long version, String eventType, String status) {
    byte[] raw =
        json(
            """
            {
              "envelopeVersion":2,"eventId":"%s","eventType":"%s","eventVersion":1,
              "occurredAt":null,"recordedAt":"2026-07-17T00:00:00Z",
              "producer":"media-service","aggregateType":"MEDIA","aggregateId":"%s",
              "aggregateVersion":%d,"correlation":{"correlationId":"%s","causationId":null},
              "actorRef":null,"payload":{"mediaId":"%s","ownerType":"MAINTENANCE_REPAIR",
              "ownerId":"%s","warehouseId":"%s","folderId":"%s","kind":"IMAGE",
              "status":"%s","generation":1,"rotationDegrees":0}
            }
            """
                .formatted(
                    UUID.randomUUID(),
                    eventType,
                    aggregateId,
                    version,
                    UUID.randomUUID(),
                    aggregateId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    status));
    return validator.validate(
        MaintenanceTransportTopics.MEDIA,
        aggregateId.toString().getBytes(StandardCharsets.UTF_8),
        raw);
  }

  private byte[] json(String value) {
    try {
      return mapper.writeValueAsBytes(mapper.readTree(value));
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private void seedOutboxStream(UUID aggregateId, UUID firstEventId, UUID secondEventId) {
    String hash = MaintenanceChecksum.sha256("{}".getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        """
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('CATALOG_VERSION',?,1,?,clock_timestamp())
        """,
        aggregateId.toString(),
        secondEventId);
    insertDomainAndOutbox(aggregateId, firstEventId, 0, hash);
    insertDomainAndOutbox(aggregateId, secondEventId, 1, hash);
  }

  private void insertDomainAndOutbox(
      UUID aggregateId, UUID eventId, long aggregateVersion, String hash) {
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,actor_ref,payload,payload_sha256,baseline)
        values (?,'CATALOG_VERSION',?,?,'maintenance.catalog-version.changed.v1',1,
          clock_timestamp(),clock_timestamp(),?,null,'{}'::jsonb,?,false)
        """,
        eventId,
        aggregateId.toString(),
        aggregateVersion,
        UUID.randomUUID(),
        hash);
    jdbc.update(
        """
        insert into outbox_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,envelope_body,
          envelope_sha256,status,attempt_count,next_attempt_at,created_at)
        values (?,'CATALOG_VERSION',?,?,'maintenance.catalog-version.changed.v1',
          'rwms.maintenance.catalog-version.v1','{}'::jsonb,?,'PENDING',0,clock_timestamp(),clock_timestamp())
        """,
        eventId,
        aggregateId.toString(),
        aggregateVersion,
        hash);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class EffectsConfiguration {
    @Bean
    @Primary
    MaintenanceInboundEffects testMaintenanceInboundEffects() {
      return (event, correlation) -> EFFECTS.add(event);
    }
  }
}
