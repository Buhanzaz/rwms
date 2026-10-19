package dev.buhanzaz.rwms.auth.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.auth.api.WorkerCredentialRequest;
import dev.buhanzaz.rwms.auth.config.AuthSubjectBootstrap;
import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import dev.buhanzaz.rwms.auth.service.WorkerCredentialService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "rwms.platform.kafka.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthShadowReplayParityIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AuthProjectionWriter projectionWriter;
    @Autowired AuthEventFactFactory facts;
    @Autowired AuthEventStore eventStore;
    @Autowired AuthReplayVerifier replay;
    @Autowired AuthReplayCoordinator replayCoordinator;
    @Autowired AuthInboxProcessor inbox;
    @Autowired AuthSanitizedDltPublisher sanitizedDltPublisher;
    @Autowired AuthSubjectRepository subjects;
    @Autowired AuthSubjectProfileStore profiles;
    @Autowired WorkerCredentialService workerCredentials;
    @Autowired AuthSubjectBootstrap bootstrap;
    @Autowired ObjectMapper objectMapper;

    @Test
    void rebuildsAllBaselineAndRuntimeStreamsThenDetectsTamperAndVersionGap() {
        UUID baselineUser = createBaselineUser("baseline-user");
        UUID untouchedBaselineUser = createBaselineUser("untouched-baseline-user");
        UUID baselineWorker = createBaselineWorker("baseline-worker");
        appendUserAuthorization(baselineUser, UserGlobalRole.WMS_ADMIN);
        appendTerminalWorkerDelete(baselineWorker);
        assertThat(jdbc.queryForObject(
                        "select jsonb_typeof(envelope_body->'payload') from outbox_event where aggregate_id=?",
                        String.class,
                        baselineUser.toString()))
                .isEqualTo("object");
        inbox.process(envelope(baselineUser, 1), AuthAggregateType.USER_AUTHORIZATION);
        sanitizedDltPublisher.publish(
                AuthAggregateType.USER_AUTHORIZATION,
                "replay-must-not-change-transport-state".getBytes(StandardCharsets.UTF_8),
                "VALIDATION_REJECTED");
        TransportEvidence transportBefore = transportEvidence();

        UUID operationId = UUID.randomUUID();
        String reason = "F4A controlled cutover parity verification";
        AuthReplayVerifier.ReplayParityResult parity =
                replayCoordinator.rebuild(operationId, baselineUser, reason);
        AuthReplayVerifier.ReplayParityResult repeatedParity =
                replayCoordinator.rebuild(operationId, baselineUser, reason);
        Integer streamCount = jdbc.queryForObject("select count(*) from event_stream_head", Integer.class);
        assertThat(parity.aggregateCount()).isEqualTo(streamCount);
        assertThat(parity.canonicalChecksum()).matches("^[0-9a-f]{64}$");
        assertThat(repeatedParity).isEqualTo(parity);
        assertThat(transportEvidence()).isEqualTo(transportBefore);
        assertThat(jdbc.queryForObject(
                        "select actor_subject_id from replay_operation_audit where operation_id=?",
                        UUID.class,
                        operationId))
                .isEqualTo(baselineUser);
        assertThat(jdbc.queryForObject(
                        "select reason from replay_operation_audit where operation_id=?",
                        String.class,
                        operationId))
                .isEqualTo(reason);
        assertThat(jdbc.queryForObject(
                        "select status from replay_operation_audit where operation_id=?",
                        String.class,
                        operationId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from replay_operation_audit where operation_id=?",
                        Integer.class,
                        operationId))
                .isOne();
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from projection_checkpoint shadow
                         join event_stream_head head using (aggregate_type, aggregate_id)
                         join domain_event tail
                           on tail.aggregate_type=head.aggregate_type
                          and tail.aggregate_id=head.aggregate_id
                          and tail.aggregate_version=head.current_version
                          and tail.event_id=head.last_event_id
                        where shadow.projection_name='auth-replay-shadow-v1'
                          and shadow.aggregate_version=head.current_version
                          and shadow.projection_sha256=tail.payload_sha256
                        """,
                        Integer.class))
                .isEqualTo(streamCount);
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from projection_checkpoint shadow
                         join domain_event baseline
                           on baseline.aggregate_type=shadow.aggregate_type
                          and baseline.aggregate_id=shadow.aggregate_id
                          and baseline.aggregate_version=shadow.aggregate_version
                          and baseline.payload_sha256=shadow.projection_sha256
                        where shadow.projection_name='auth-replay-shadow-v1'
                          and shadow.aggregate_id=? and baseline.baseline
                        """,
                        Integer.class,
                        untouchedBaselineUser.toString()))
                .isEqualTo(1);
        assertThat(subjects.findById(baselineWorker)).isEmpty();
        assertThat(replay.verify(AuthAggregateType.WORKER_ACCESS, baselineWorker)
                        .liveProjectionPresent())
                .isFalse();

        appendUserAuthorization(baselineUser, UserGlobalRole.RENTAL_MANAGER);
        inbox.process(envelope(baselineUser, 2), AuthAggregateType.USER_AUTHORIZATION);
        assertThat(jdbc.queryForObject(
                        """
                        select last_aggregate_version from consumer_aggregate_checkpoint
                         where consumer_group='auth-shadow-v1' and aggregate_id=?
                        """,
                        Long.class,
                        baselineUser.toString()))
                .isEqualTo(2L);

        UUID tampered = createBaselineUser("tampered-user", "0".repeat(64));
        assertThatThrownBy(() -> replay.verify(AuthAggregateType.USER_AUTHORIZATION, tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checksum mismatch");
        UUID failedOperationId = UUID.randomUUID();
        assertThatThrownBy(() -> replayCoordinator.rebuild(
                        failedOperationId,
                        baselineUser,
                        "F4A controlled replay failure audit verification"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checksum mismatch");
        assertThat(jdbc.queryForObject(
                        "select status from replay_operation_audit where operation_id=?",
                        String.class,
                        failedOperationId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                        "select failure_code from replay_operation_audit where operation_id=?",
                        String.class,
                        failedOperationId))
                .isEqualTo("STREAM_VALIDATION_FAILED");
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from replay_operation_audit
                         where operation_id=? and after_checksum is null
                           and after_aggregate_count is null and after_version_sum is null
                        """,
                        Integer.class,
                        failedOperationId))
                .isOne();

        UUID missingOutboxUser = createBaselineUser("missing-outbox-user");
        appendUserAuthorization(missingOutboxUser, UserGlobalRole.WMS_ADMIN);
        UUID missingOutboxEventId = jdbc.queryForObject(
                """
                select event_id from domain_event
                 where aggregate_id=? and not baseline
                """,
                UUID.class,
                missingOutboxUser.toString());
        assertThat(jdbc.update(
                        "delete from outbox_event where event_id=?",
                        missingOutboxEventId))
                .isOne();
        UUID corruptOperationId = UUID.randomUUID();
        assertThatThrownBy(() -> replayCoordinator.rebuild(
                        corruptOperationId,
                        baselineUser,
                        "F4A missing canonical outbox corruption verification"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replay corruption")
                .hasMessageContaining("canonical outbox");
        assertThat(jdbc.queryForObject(
                        "select status from replay_operation_audit where operation_id=?",
                        String.class,
                        corruptOperationId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                        "select failure_code from replay_operation_audit where operation_id=?",
                        String.class,
                        corruptOperationId))
                .isEqualTo("STREAM_VALIDATION_FAILED");
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from projection_checkpoint
                         where projection_name='auth-replay-shadow-v1' and aggregate_id=?
                        """,
                        Integer.class,
                        missingOutboxUser.toString()))
                .isZero();

        UUID gapped = createBaselineUser("gapped-user");
        createVersionGap(gapped);
        assertThatThrownBy(() -> replay.verify(AuthAggregateType.USER_AUTHORIZATION, gapped))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version gap");
    }

    @Test
    void idempotentWorkerAndBootstrapCommandsDoNotAdvanceProjectionOrEventStreams() {
        String workerId = "worker-noop-" + UUID.randomUUID();
        String login = "worker.noop." + UUID.randomUUID();
        String password = "worker-noop-password";
        var request = new WorkerCredentialRequest(
                "00000000-0000-0000-0000-000000000001", login, password);

        workerCredentials.configure(workerId, request);
        UUID subjectId = profiles.findSubjectIdByExternalWorkerId(workerId).orElseThrow();
        MutationEvidence configured = mutationEvidence(subjectId);
        workerCredentials.configure(workerId, request);
        assertThat(mutationEvidence(subjectId)).isEqualTo(configured);

        workerCredentials.resetPassword(workerId, password);
        assertThat(mutationEvidence(subjectId)).isEqualTo(configured);

        workerCredentials.disable(workerId);
        MutationEvidence disabled = mutationEvidence(subjectId);
        workerCredentials.disable(workerId);
        assertThat(mutationEvidence(subjectId)).isEqualTo(disabled);
        workerCredentials.enable(workerId);
        MutationEvidence enabled = mutationEvidence(subjectId);
        workerCredentials.enable(workerId);
        assertThat(mutationEvidence(subjectId)).isEqualTo(enabled);

        UUID adminId = profiles.findSubjectIdByUsername("admin").orElseThrow();
        MutationEvidence adminBefore = mutationEvidence(adminId);
        bootstrap.run(new DefaultApplicationArguments(new String[0]));
        assertThat(mutationEvidence(adminId)).isEqualTo(adminBefore);

        jdbc.update(
                """
                update event_stream_head
                   set current_version=current_version+1, updated_at=clock_timestamp()
                 where aggregate_type='WORKER_ACCESS' and aggregate_id=?
                """,
                subjectId.toString());
        try {
            assertThatThrownBy(() -> workerCredentials.disable(workerId))
                    .isInstanceOf(OptimisticLockingFailureException.class)
                    .hasMessageContaining("projection is stale");
        } finally {
            jdbc.update(
                    """
                    update event_stream_head
                       set current_version=?, last_event_id=?, updated_at=clock_timestamp()
                     where aggregate_type='WORKER_ACCESS' and aggregate_id=?
                    """,
                    enabled.streamVersion(),
                    enabled.lastEventId(),
                    subjectId.toString());
        }
        assertThat(mutationEvidence(subjectId)).isEqualTo(enabled);
    }

    private UUID createBaselineUser(String prefix) {
        return createBaselineUser(prefix, null);
    }

    private UUID createBaselineUser(String prefix, String forcedChecksum) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            AuthSubject subject = projectionWriter.insertUser(
                    prefix + '-' + UUID.randomUUID(),
                    "{noop}test-password",
                    null,
                    null,
                    null,
                    "Europe/Moscow",
                    UserGlobalRole.VIEWER,
                    true);
            insertBaseline(
                    AuthAggregateType.USER_AUTHORIZATION,
                    subject,
                    facts.userAuthorization(subject),
                    forcedChecksum);
            return subject.getId();
        });
    }

    private UUID createBaselineWorker(String prefix) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            AuthSubject subject = projectionWriter.insertWorker(
                    prefix + '-' + UUID.randomUUID(),
                    "00000000-0000-0000-0000-000000000001",
                    prefix + '-' + UUID.randomUUID(),
                    "{noop}test-password");
            insertBaseline(
                    AuthAggregateType.WORKER_ACCESS,
                    subject,
                    facts.workerAccess(subject),
                    null);
            return subject.getId();
        });
    }

    private void insertBaseline(
            AuthAggregateType aggregateType,
            AuthSubject subject,
            Object payload,
            String forcedChecksum) {
        String payloadJson = canonical(write(payload));
        String payloadHash = forcedChecksum == null
                ? AuthEventStore.sha256(payloadJson.getBytes(StandardCharsets.UTF_8))
                : forcedChecksum;
        UUID eventId = UUID.nameUUIDFromBytes(
                (aggregateType.name() + ':' + subject.getId()).getBytes(StandardCharsets.UTF_8));
        String eventType = aggregateType == AuthAggregateType.USER_AUTHORIZATION
                ? "auth.user-authorization.baseline.v1"
                : "auth.worker-access.baseline.v1";
        jdbc.update(
                """
                insert into event_stream_head(
                    aggregate_type, aggregate_id, current_version, last_event_id, updated_at)
                values (?, ?, ?, ?, clock_timestamp())
                """,
                aggregateType.name(),
                subject.getId().toString(),
                subject.getVersion(),
                eventId);
        jdbc.update(
                """
                insert into domain_event(
                    event_id, aggregate_type, aggregate_id, aggregate_version,
                    event_type, event_version, occurred_at, recorded_at,
                    correlation_id, causation_id, actor_ref, payload, payload_sha256, baseline)
                values (?, ?, ?, ?, ?, 1, null, clock_timestamp(), ?, null, null, ?::jsonb, ?, true)
                """,
                eventId,
                aggregateType.name(),
                subject.getId().toString(),
                subject.getVersion(),
                eventType,
                UUID.nameUUIDFromBytes(("correlation:" + subject.getId()).getBytes(StandardCharsets.UTF_8)),
                payloadJson,
                payloadHash);
        jdbc.update(
                """
                insert into projection_checkpoint(
                    projection_name, aggregate_type, aggregate_id,
                    aggregate_version, projection_sha256, updated_at)
                values ('auth-live-v1', ?, ?, ?, ?, clock_timestamp())
                """,
                aggregateType.name(),
                subject.getId().toString(),
                subject.getVersion(),
                payloadHash);
    }

    private void appendUserAuthorization(UUID subjectId, UserGlobalRole role) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AuthSubject subject = subjects.findById(subjectId).orElseThrow();
            long version = eventStore.lockCurrentVersion(
                    AuthAggregateType.USER_AUTHORIZATION, subjectId);
            AuthSubject updated = projectionWriter.updateUser(
                    subject,
                    subject.getUsername(),
                    subject.getFirstName(),
                    subject.getLastName(),
                    subject.getEmail(),
                    subject.getTimeZoneId(),
                    role,
                    true,
                    false,
                    false);
            eventStore.append(
                    AuthAggregateType.USER_AUTHORIZATION,
                    subjectId,
                    version,
                    AuthEventTypes.USER_CHANGED,
                    facts.userAuthorization(updated),
                    null);
        });
    }

    private void appendTerminalWorkerDelete(UUID subjectId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AuthSubject subject = subjects.findById(subjectId).orElseThrow();
            long version = eventStore.lockCurrentVersion(AuthAggregateType.WORKER_ACCESS, subjectId);
            AuthSubject terminal = projectionWriter.prepareWorkerDeletion(subject);
            eventStore.append(
                    AuthAggregateType.WORKER_ACCESS,
                    subjectId,
                    version,
                    AuthEventTypes.WORKER_DELETED,
                    facts.workerAccess(terminal),
                    null);
            projectionWriter.deleteWorker(terminal);
        });
    }

    private void createVersionGap(UUID subjectId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AuthSubject subject = subjects.findById(subjectId).orElseThrow();
            AuthSubject versionOne = projectionWriter.updateUser(
                    subject,
                    subject.getUsername(),
                    subject.getFirstName(),
                    subject.getLastName(),
                    subject.getEmail(),
                    subject.getTimeZoneId(),
                    UserGlobalRole.WMS_ADMIN,
                    true,
                    false,
                    false);
            AuthSubject versionTwo = projectionWriter.updateUser(
                    versionOne,
                    versionOne.getUsername(),
                    versionOne.getFirstName(),
                    versionOne.getLastName(),
                    versionOne.getEmail(),
                    versionOne.getTimeZoneId(),
                    UserGlobalRole.RENTAL_MANAGER,
                    true,
                    false,
                    false);
            Object payload = facts.userAuthorization(versionTwo);
            String payloadJson = canonical(write(payload));
            String payloadHash = AuthEventStore.sha256(payloadJson.getBytes(StandardCharsets.UTF_8));
            UUID eventId = UUID.randomUUID();
            jdbc.update(
                    """
                    update event_stream_head
                       set current_version=2, last_event_id=?, updated_at=clock_timestamp()
                     where aggregate_type='USER_AUTHORIZATION' and aggregate_id=?
                    """,
                    eventId,
                    subjectId.toString());
            jdbc.update(
                    """
                    insert into domain_event(
                        event_id, aggregate_type, aggregate_id, aggregate_version,
                        event_type, event_version, occurred_at, recorded_at,
                        correlation_id, causation_id, actor_ref, payload, payload_sha256, baseline)
                    values (?, 'USER_AUTHORIZATION', ?, 2,
                            'auth.user-authorization.changed.v1', 1,
                            clock_timestamp(), clock_timestamp(), ?, null, null, ?::jsonb, ?, false)
                    """,
                    eventId,
                    subjectId.toString(),
                    UUID.randomUUID(),
                    payloadJson,
                    payloadHash);
        });
    }

    private byte[] envelope(UUID aggregateId, long version) {
        return jdbc.queryForObject(
                        "select envelope_body::text from outbox_event where aggregate_id=? and aggregate_version=?",
                        String.class,
                        aggregateId.toString(),
                        version)
                .getBytes(StandardCharsets.UTF_8);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Cannot serialize replay test payload", exception);
        }
    }

    private String canonical(String value) {
        return jdbc.queryForObject("select (?::jsonb)::text", String.class, value);
    }

    private TransportEvidence transportEvidence() {
        return new TransportEvidence(
                jsonRows("outbox_event", "event_id"),
                jsonRows("sanitized_dead_letter", "dlt_id"),
                jsonRows("inbox_message", "event_id"),
                jsonRows("consumer_aggregate_checkpoint", "consumer_group, aggregate_type, aggregate_id"),
                jdbc.queryForList(
                        """
                        select to_jsonb(row_value)::text
                          from projection_checkpoint row_value
                         where projection_name='auth-kafka-shadow-v1'
                         order by aggregate_type, aggregate_id
                        """,
                        String.class));
    }

    private MutationEvidence mutationEvidence(UUID subjectId) {
        AuthSubject subject = subjects.findById(subjectId).orElseThrow();
        return jdbc.queryForObject(
                """
                select head.current_version, head.last_event_id,
                       (select count(*) from domain_event event
                         where event.aggregate_id=head.aggregate_id) as event_count,
                       (select count(*) from outbox_event outbox
                         where outbox.aggregate_id=head.aggregate_id) as outbox_count
                  from event_stream_head head
                 where head.aggregate_id=?
                """,
                (result, row) -> new MutationEvidence(
                        subject.getVersion(),
                        result.getLong("current_version"),
                        result.getObject("last_event_id", UUID.class),
                        result.getInt("event_count"),
                        result.getInt("outbox_count")),
                subjectId.toString());
    }

    private List<String> jsonRows(String table, String orderBy) {
        return jdbc.queryForList(
                "select to_jsonb(row_value)::text from " + table + " row_value order by " + orderBy,
                String.class);
    }

    private record TransportEvidence(
            List<String> outbox,
            List<String> deadLetters,
            List<String> inbox,
            List<String> consumerCheckpoints,
            List<String> kafkaProjectionCheckpoints) {}

    private record MutationEvidence(
            int jpaVersion,
            long streamVersion,
            UUID lastEventId,
            int domainEventCount,
            int outboxCount) {}
}
