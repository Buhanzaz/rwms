package dev.buhanzaz.rwms.auth.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.eventing.AuthEventPayloads.UserAuthorizationFact;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
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

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "rwms.platform.kafka.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthEventStoreIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    AuthEventStore events;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void appendUsesCasAndWritesDomainEventOutboxAndCanonicalProjectionCheckpointAtomically() {
        UUID subjectId = UUID.randomUUID();
        UUID profileRevision = UUID.randomUUID();
        var initial = fact(subjectId, profileRevision, true);
        var changed = fact(subjectId, profileRevision, false);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> events.initialize(
                AuthAggregateType.USER_AUTHORIZATION,
                subjectId,
                0,
                AuthEventTypes.USER_CREATED,
                initial,
                null));
        transaction.executeWithoutResult(status -> events.append(
                AuthAggregateType.USER_AUTHORIZATION,
                subjectId,
                0,
                AuthEventTypes.USER_CHANGED,
                changed,
                null));

        assertThat(jdbc.queryForObject(
                        "select current_version from event_stream_head where aggregate_id=?",
                        Long.class,
                        subjectId.toString()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "select count(*) from domain_event where aggregate_id=? and baseline=false",
                        Integer.class,
                        subjectId.toString()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox_event where aggregate_id=? and status='PENDING'",
                        Integer.class,
                        subjectId.toString()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        """
                        select projection_sha256 = encode(sha256(convert_to(payload::text, 'UTF8')), 'hex')
                          from projection_checkpoint checkpoint
                          join domain_event event
                            on event.aggregate_type = checkpoint.aggregate_type
                           and event.aggregate_id = checkpoint.aggregate_id
                           and event.aggregate_version = checkpoint.aggregate_version
                         where checkpoint.projection_name='auth-live-v1' and checkpoint.aggregate_id=?
                        """,
                        Boolean.class,
                        subjectId.toString()))
                .isTrue();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> events.append(
                        AuthAggregateType.USER_AUTHORIZATION,
                        subjectId,
                        0,
                        AuthEventTypes.USER_CHANGED,
                        changed,
                        null)))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(jdbc.queryForObject(
                        "select count(*) from domain_event where aggregate_id=?",
                        Integer.class,
                        subjectId.toString()))
                .isEqualTo(2);
    }

    @Test
    void concurrentAppendAllowsExactlyOneWinnerAndKeepsAllTransactionalArtifactsConsistent()
            throws Exception {
        UUID subjectId = UUID.randomUUID();
        UUID profileRevision = UUID.randomUUID();
        TransactionTemplate setup = new TransactionTemplate(transactionManager);
        setup.executeWithoutResult(status -> events.initialize(
                AuthAggregateType.USER_AUTHORIZATION,
                subjectId,
                0,
                AuthEventTypes.USER_CREATED,
                fact(subjectId, profileRevision, true),
                null));

        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> raceAppend(subjectId, profileRevision, barrier, true));
            var second = executor.submit(() -> raceAppend(subjectId, profileRevision, barrier, false));

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }

        assertThat(jdbc.queryForObject(
                        "select current_version from event_stream_head where aggregate_id=?",
                        Long.class,
                        subjectId.toString()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "select count(*) from domain_event where aggregate_id=?",
                        Integer.class,
                        subjectId.toString()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox_event where aggregate_id=?",
                        Integer.class,
                        subjectId.toString()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from projection_checkpoint
                         where projection_name='auth-live-v1' and aggregate_id=?
                           and aggregate_version=1
                        """,
                        Integer.class,
                        subjectId.toString()))
                .isEqualTo(1);
    }

    @Test
    void failedOuterTransactionLeavesNoStreamEventOrOutbox() {
        UUID subjectId = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    events.initialize(
                            AuthAggregateType.USER_AUTHORIZATION,
                            subjectId,
                            0,
                            AuthEventTypes.USER_CREATED,
                            fact(subjectId, UUID.randomUUID(), true),
                            null);
                    throw new IllegalStateException("rollback canary");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback canary");

        assertThat(jdbc.queryForObject(
                        "select count(*) from event_stream_head where aggregate_id=?",
                        Integer.class,
                        subjectId.toString()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from domain_event where aggregate_id=?",
                        Integer.class,
                        subjectId.toString()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox_event where aggregate_id=?",
                        Integer.class,
                        subjectId.toString()))
                .isZero();
    }

    @Test
    void multiStreamCommandRollsBackEveryStreamAndTransactionalArtifact() {
        UUID firstSubjectId = UUID.randomUUID();
        UUID secondSubjectId = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    events.initialize(
                            AuthAggregateType.USER_AUTHORIZATION,
                            firstSubjectId,
                            0,
                            AuthEventTypes.USER_CREATED,
                            fact(firstSubjectId, UUID.randomUUID(), true),
                            null);
                    events.initialize(
                            AuthAggregateType.USER_AUTHORIZATION,
                            secondSubjectId,
                            0,
                            AuthEventTypes.USER_CREATED,
                            fact(secondSubjectId, UUID.randomUUID(), true),
                            null);
                    throw new IllegalStateException("multi-stream rollback canary");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("multi-stream rollback canary");

        for (String table : List.of(
                "event_stream_head",
                "domain_event",
                "outbox_event",
                "projection_checkpoint",
                "aggregate_snapshot")) {
            assertThat(jdbc.queryForObject(
                            "select count(*) from " + table + " where aggregate_id in (?, ?)",
                            Integer.class,
                            firstSubjectId.toString(),
                            secondSubjectId.toString()))
                    .as("No %s rows survive a failed multi-stream command", table)
                    .isZero();
        }
    }

    @Test
    void newStreamSnapshotsItsHundredthFactAtVersionNinetyNine() {
        UUID subjectId = UUID.randomUUID();
        UUID profileRevision = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> events.initialize(
                AuthAggregateType.USER_AUTHORIZATION,
                subjectId,
                0,
                AuthEventTypes.USER_CREATED,
                fact(subjectId, profileRevision, true),
                null));

        appendFacts(subjectId, profileRevision, 0, 98);

        assertThat(jdbc.queryForObject(
                        "select count(*) from aggregate_snapshot where aggregate_id=?",
                        Integer.class,
                        subjectId.toString()))
                .isZero();
        transaction.executeWithoutResult(status -> events.append(
                AuthAggregateType.USER_AUTHORIZATION,
                subjectId,
                98,
                AuthEventTypes.USER_CHANGED,
                fact(subjectId, profileRevision, true),
                null));

        assertThat(jdbc.queryForObject(
                        "select count(*) from aggregate_snapshot where aggregate_id=?",
                        Integer.class,
                        subjectId.toString()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select aggregate_version from aggregate_snapshot where aggregate_id=?",
                        Long.class,
                        subjectId.toString()))
                .isEqualTo(99L);
    }

    @ParameterizedTest
    @ValueSource(longs = {7, 150})
    void migratedBaselineSnapshotsAfterEachHundredRuntimeFacts(long baselineVersion) {
        UUID subjectId = UUID.randomUUID();
        UUID profileRevision = UUID.randomUUID();
        insertBaseline(subjectId, profileRevision, baselineVersion);

        appendFacts(subjectId, profileRevision, baselineVersion, 99);
        assertThat(jdbc.queryForObject(
                        "select count(*) from aggregate_snapshot where aggregate_id=?",
                        Integer.class,
                        subjectId.toString()))
                .isZero();
        appendFacts(subjectId, profileRevision, baselineVersion + 99, 1);

        assertThat(jdbc.queryForList(
                        """
                        select aggregate_version from aggregate_snapshot
                         where aggregate_id=? order by aggregate_version
                        """,
                        Long.class,
                        subjectId.toString()))
                .containsExactly(baselineVersion + 100);

        appendFacts(subjectId, profileRevision, baselineVersion + 100, 99);
        assertThat(jdbc.queryForObject(
                        "select count(*) from aggregate_snapshot where aggregate_id=?",
                        Integer.class,
                        subjectId.toString()))
                .isOne();
        appendFacts(subjectId, profileRevision, baselineVersion + 199, 1);

        assertThat(jdbc.queryForList(
                        """
                        select aggregate_version from aggregate_snapshot
                         where aggregate_id=? order by aggregate_version
                        """,
                        Long.class,
                        subjectId.toString()))
                .containsExactly(baselineVersion + 100, baselineVersion + 200);
    }

    private void insertBaseline(UUID subjectId, UUID profileRevision, long baselineVersion) {
        UUID eventId = UUID.randomUUID();
        String payload = "{\"subjectId\":\"" + subjectId + "\","
                + "\"active\":true,\"globalRole\":\"VIEWER\","
                + "\"profileRevision\":\"" + profileRevision + "\","
                + "\"warehouseAccess\":[]}";
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update(
                    """
                    insert into event_stream_head(
                        aggregate_type, aggregate_id, current_version, last_event_id, updated_at)
                    values ('USER_AUTHORIZATION', ?, ?, ?, clock_timestamp())
                    """,
                    subjectId.toString(),
                    baselineVersion,
                    eventId);
            jdbc.update(
                    """
                    with canonical as (select ?::jsonb payload)
                    insert into domain_event(
                        event_id, aggregate_type, aggregate_id, aggregate_version,
                        event_type, event_version, occurred_at, recorded_at,
                        correlation_id, causation_id, actor_ref,
                        payload, payload_sha256, baseline)
                    select ?, 'USER_AUTHORIZATION', ?, ?,
                           'auth.user-authorization.baseline.v1', 1, null, clock_timestamp(),
                           ?, null, null, payload,
                           encode(sha256(convert_to(payload::text, 'UTF8')), 'hex'), true
                      from canonical
                    """,
                    payload,
                    eventId,
                    subjectId.toString(),
                    baselineVersion,
                    UUID.randomUUID());
        });
    }

    private void appendFacts(
            UUID subjectId, UUID profileRevision, long firstExpectedVersion, int count) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int index = 0; index < count; index++) {
                long expectedVersion = firstExpectedVersion + index;
                events.append(
                        AuthAggregateType.USER_AUTHORIZATION,
                        subjectId,
                        expectedVersion,
                        AuthEventTypes.USER_CHANGED,
                        fact(subjectId, profileRevision, expectedVersion % 2 == 0),
                        null);
            }
        });
    }

    private UserAuthorizationFact fact(UUID subjectId, UUID profileRevision, boolean active) {
        return new UserAuthorizationFact(
                subjectId,
                active,
                UserGlobalRole.VIEWER,
                profileRevision,
                List.of());
    }

    private boolean raceAppend(
            UUID subjectId,
            UUID profileRevision,
            CyclicBarrier barrier,
            boolean active) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                try {
                    barrier.await();
                } catch (Exception exception) {
                    throw new IllegalStateException("Concurrent append barrier failed", exception);
                }
                events.append(
                        AuthAggregateType.USER_AUTHORIZATION,
                        subjectId,
                        0,
                        AuthEventTypes.USER_CHANGED,
                        fact(subjectId, profileRevision, active),
                        null);
            });
            return true;
        } catch (OptimisticLockingFailureException exception) {
            return false;
        }
    }
}
