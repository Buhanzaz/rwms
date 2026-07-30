package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class AuthEventStoreMigrationIntegrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000071");
    private static final UUID WORKER_ID = UUID.fromString("10000000-0000-0000-0000-000000000072");
    private static final UUID ACCESS_ONE_ID = UUID.fromString("20000000-0000-0000-0000-000000000071");
    private static final UUID ACCESS_TWO_ID = UUID.fromString("20000000-0000-0000-0000-000000000072");
    private static final UUID ACCESS_THREE_ID = UUID.fromString("20000000-0000-0000-0000-000000000073");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        resetPublicSchema();
    }

    @Test
    void cleanInstallAppliesAllMigrationsAndRepeatIsNoOp() {
        Flyway flyway = flyway(MIGRATION_LOCATION);

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        assertThat(tables()).contains(
                "auth_subject_pii",
                "auth_subject_credential",
                "user_warehouse_access_note",
                "event_stream_head",
                "domain_event",
                "aggregate_snapshot",
                "projection_checkpoint",
                "outbox_event",
                "sanitized_dead_letter",
                "inbox_message",
                "consumer_aggregate_checkpoint",
                "version_gap_quarantine",
                "replay_operation_audit");
        assertThat(jdbc.queryForObject("select count(*) from domain_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class)).isZero();
        assertThat(jdbc.update(
                        "insert into consumer_aggregate_checkpoint(consumer_group, aggregate_type, aggregate_id, "
                                + "last_event_id, last_aggregate_version, blocked, updated_at) "
                                + "values ('projection', 'USER_AUTHORIZATION', 'new-stream', null, -1, false, now())"))
                .isOne();
        assertThat(jdbc.queryForMap(
                        "select version, description, script, success from flyway_schema_history where version='3'"))
                .containsEntry("version", "3")
                .containsEntry("description", "auth event sourcing")
                .containsEntry("script", "V3__auth_event_sourcing.sql")
                .containsEntry("success", true);
    }

    @Test
    void explicitVersionTwoBaselineMigratesWithoutChangingLegacyOrOAuthRows() throws Exception {
        applyVersionTwo();
        seedVersionTwoRows();
        Map<String, String> before = legacyDigests();

        Flyway adopted = configuration(MIGRATION_LOCATION)
                .baselineVersion("2")
                .baselineDescription("Auth post-F1C schema")
                .load();
        adopted.baseline();

        assertThat(adopted.migrate().migrationsExecuted).isEqualTo(4);
        adopted.validate();
        assertThat(adopted.migrate().migrationsExecuted).isZero();

        assertThat(legacyDigests()).containsExactlyInAnyOrderEntriesOf(before);
        assertThat(jdbc.queryForObject("select count(*) from event_stream_head", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from domain_event", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from projection_checkpoint where projection_name='auth-live-v1'",
                        Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForList(
                        "select aggregate_id from projection_checkpoint "
                                + "where projection_name='auth-live-v1' order by aggregate_id",
                        String.class))
                .containsExactly(USER_ID.toString(), WORKER_ID.toString());
        assertThat(jdbc.queryForObject(
                        "select count(*) from projection_checkpoint checkpoint join domain_event event "
                                + "on event.aggregate_type=checkpoint.aggregate_type "
                                + "and event.aggregate_id=checkpoint.aggregate_id "
                                + "and event.aggregate_version=checkpoint.aggregate_version "
                                + "and event.payload_sha256=checkpoint.projection_sha256 "
                                + "where checkpoint.projection_name='auth-live-v1' and event.baseline",
                        Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from projection_checkpoint where projection_name<>'auth-live-v1'",
                        Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from consumer_aggregate_checkpoint", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from domain_event where baseline and occurred_at is null",
                        Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select current_version from event_stream_head "
                                + "where aggregate_type='USER_AUTHORIZATION' and aggregate_id=?",
                        Long.class,
                        USER_ID.toString()))
                .isEqualTo(7L);
        assertThat(jdbc.queryForObject(
                        "select current_version from event_stream_head "
                                + "where aggregate_type='WORKER_ACCESS' and aggregate_id=?",
                        Long.class,
                        WORKER_ID.toString()))
                .isEqualTo(4L);
        assertThat(jdbc.queryForObject(
                        "select aggregate_version from projection_checkpoint "
                                + "where projection_name='auth-live-v1' and aggregate_type='USER_AUTHORIZATION' "
                                + "and aggregate_id=?",
                        Long.class,
                        USER_ID.toString()))
                .isEqualTo(7L);
        assertThat(jdbc.queryForObject(
                        "select aggregate_version from projection_checkpoint "
                                + "where projection_name='auth-live-v1' and aggregate_type='WORKER_ACCESS' "
                                + "and aggregate_id=?",
                        Long.class,
                        WORKER_ID.toString()))
                .isEqualTo(4L);
    }

    @Test
    void baselineIsDeterministicSanitizedAndCanRejoinProtectedAccessNotes() throws Exception {
        migrateSeededVersionTwo();

        List<Map<String, Object>> firstBaseline = deterministicBaselineProjection();
        assertThat(eventPayloadText())
                .doesNotContain(
                        "alice.canary",
                        "AliceSecret",
                        "Persona",
                        "alice.secret@example.test",
                        "Secret/Zone",
                        "{bcrypt}password-secret",
                        "legacy-worker-secret",
                        "grant-note-secret-one",
                        "grant-note-secret-two",
                        "profileHash",
                        "profile_sha256",
                        "passwordHash",
                        "commentText",
                        "externalWorkerId")
                .contains("noteRevision", "accessId");
        assertThat(rejoinedAccessNotes()).containsExactly(
                ACCESS_ONE_ID + "=grant-note-secret-one",
                ACCESS_TWO_ID + "=grant-note-secret-two",
                ACCESS_THREE_ID + "=<null>");
        assertVaultHashesMatchRuntimeCanonicalization();

        resetPublicSchema();
        migrateSeededVersionTwo();

        assertThat(deterministicBaselineProjection()).isEqualTo(firstBaseline);
        assertThat(jdbc.queryForObject(
                        "select count(*) from domain_event where not baseline or occurred_at is not null",
                        Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class)).isZero();
    }

    @Test
    void domainEventsAreAppendOnly() throws Exception {
        migrateSeededVersionTwo();
        UUID eventId = jdbc.queryForObject(
                "select event_id from domain_event where aggregate_id=?", UUID.class, USER_ID.toString());

        assertThatThrownBy(() -> jdbc.update("update domain_event set payload='{}'::jsonb where event_id=?", eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from domain_event where event_id=?", eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("truncate table domain_event cascade"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void aggregateSnapshotsUseRelativeThresholdVersionsAndCanonicalStateHash() throws Exception {
        migrateSeededVersionTwo();
        UUID aggregateId = UUID.fromString("10000000-0000-0000-0000-000000000081");
        UUID tailEventId = UUID.fromString("30000000-0000-0000-0000-000000000081");
        String state = "{\"subjectId\":\"" + aggregateId + "\",\"active\":true}";
        String stateHash = sha256(canonicalJson(state));
        jdbc.update(
                "insert into event_stream_head(aggregate_type, aggregate_id, current_version, "
                        + "last_event_id, updated_at) values ('USER_AUTHORIZATION', ?, 107, ?, now())",
                aggregateId.toString(),
                tailEventId);

        assertThat(jdbc.update(
                        "insert into aggregate_snapshot(aggregate_type, aggregate_id, aggregate_version, "
                                + "state, state_sha256, recorded_at) "
                                + "values ('USER_AUTHORIZATION', ?, 99, ?::jsonb, ?, now())",
                        aggregateId.toString(),
                        state,
                        stateHash))
                .isOne();
        assertThat(jdbc.update(
                        "insert into aggregate_snapshot(aggregate_type, aggregate_id, aggregate_version, "
                                + "state, state_sha256, recorded_at) "
                                + "values ('USER_AUTHORIZATION', ?, 107, ?::jsonb, ?, now())",
                        aggregateId.toString(),
                        state,
                        stateHash))
                .isOne();
        assertThatThrownBy(() -> jdbc.update(
                        "insert into aggregate_snapshot(aggregate_type, aggregate_id, aggregate_version, "
                                + "state, state_sha256, recorded_at) "
                                + "values ('USER_AUTHORIZATION', ?, -1, ?::jsonb, ?, now())",
                        aggregateId.toString(),
                        state,
                        stateHash))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into aggregate_snapshot(aggregate_type, aggregate_id, aggregate_version, "
                                + "state, state_sha256, recorded_at) "
                                + "values ('USER_AUTHORIZATION', ?, 99, ?::jsonb, ?, now())",
                        aggregateId.toString(),
                        state,
                        stateHash))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into aggregate_snapshot(aggregate_type, aggregate_id, aggregate_version, "
                                + "state, state_sha256, recorded_at) "
                                + "values ('USER_AUTHORIZATION', ?, 108, ?::jsonb, ?, now())",
                        aggregateId.toString(),
                        state,
                        "e".repeat(64)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void eventTypesAreBoundToTheirAggregateFamilyAndBaselineState() throws Exception {
        migrateSeededVersionTwo();

        assertThatThrownBy(() -> insertUserEvent(
                        "auth.worker-access.configured.v1", false, 1))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertUserEvent(
                        "auth.user-authorization.unknown.v1", false, 1))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertUserEvent(
                        "auth.user-authorization.baseline.v1", false, 1))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertUserEvent(
                        "auth.user-authorization.created.v1", true, 1))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertUserEvent(
                        "auth.user-authorization.created.v1", false, 2))
                .isInstanceOf(DataAccessException.class);

        assertThat(insertRuntimeUserEvent("auth.user-authorization.created.v1")).isNotNull();
    }

    @Test
    void outboxAcceptsOnlyRuntimeFactsWithMatchingDomainIdentityAndTopic() throws Exception {
        migrateSeededVersionTwo();
        UUID baselineEventId = jdbc.queryForObject(
                "select event_id from domain_event where aggregate_id=?", UUID.class, USER_ID.toString());
        UUID runtimeEventId = insertRuntimeUserEvent("auth.user-authorization.created.v1");

        assertThatThrownBy(() -> insertOutbox(
                        baselineEventId,
                        "auth.user-authorization.baseline.v1",
                        "rwms.auth.user-authorization.v1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertOutbox(
                        runtimeEventId,
                        "auth.worker-access.configured.v1",
                        "rwms.auth.worker-access.v1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertOutbox(
                        runtimeEventId,
                        "auth.user-authorization.changed.v1",
                        "rwms.auth.user-authorization.v1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertOutbox(
                        runtimeEventId,
                        "auth.user-authorization.created.v1",
                        "rwms.auth.worker-access.v1"))
                .isInstanceOf(DataAccessException.class);

        insertOutbox(
                runtimeEventId,
                "auth.user-authorization.created.v1",
                "rwms.auth.user-authorization.v1");
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox_event where event_id=?", Integer.class, runtimeEventId))
                .isOne();
    }

    @Test
    void outboxAllowsOnlyDeliveryStateUpdatesAndProtectsEnvelopeMetadata() throws Exception {
        migrateSeededVersionTwo();
        UUID eventId = insertRuntimeUserEvent("auth.user-authorization.created.v1");
        String envelope = envelopeFor(eventId);
        jdbc.update(
                "insert into outbox_event(event_id, aggregate_type, aggregate_id, aggregate_version, "
                        + "event_type, topic, envelope_body, envelope_sha256, status, attempt_count, "
                        + "next_attempt_at, created_at) "
                        + "select event_id, aggregate_type, aggregate_id, aggregate_version, event_type, "
                        + "'rwms.auth.user-authorization.v1', ?::jsonb, ?, 'PENDING', 0, now(), now() "
                        + "from domain_event where event_id=?",
                envelope,
                sha256(canonicalJson(envelope)),
                eventId);

        UUID leaseToken = UUID.fromString("30000000-0000-0000-0000-000000000071");
        assertThat(jdbc.update(
                        "update outbox_event set status='IN_FLIGHT', attempt_count=1, "
                                + "lease_owner='relay-one', lease_token=?, lease_until=now() + interval '1 minute' "
                                + "where event_id=?",
                        leaseToken,
                        eventId))
                .isOne();
        assertThat(jdbc.queryForMap(
                        "select status, attempt_count, lease_owner from outbox_event where event_id=?", eventId))
                .containsEntry("status", "IN_FLIGHT")
                .containsEntry("attempt_count", 1)
                .containsEntry("lease_owner", "relay-one");
        assertThat(jdbc.update(
                        "update outbox_event set last_error_code='BROKER_TIMEOUT' where event_id=?", eventId))
                .isOne();
        assertThatThrownBy(() -> jdbc.update(
                        "update outbox_event set last_error_code='secret detail' where event_id=?", eventId))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update(
                        "update outbox_event set envelope_body='{\"tampered\":true}'::jsonb where event_id=?",
                        eventId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void outboxRejectsEnvelopeWithCanonicalIdentityButDifferentDomainPayload() throws Exception {
        migrateSeededVersionTwo();
        UUID eventId = insertRuntimeUserEvent("auth.user-authorization.created.v1");
        String changedEnvelope = jdbc.queryForObject(
                "select jsonb_set(?::jsonb, '{payload,active}', 'false'::jsonb)::text",
                String.class,
                envelopeFor(eventId));

        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into outbox_event(
                            event_id, aggregate_type, aggregate_id, aggregate_version,
                            event_type, topic, envelope_body, envelope_sha256,
                            status, attempt_count, next_attempt_at, created_at)
                        select event_id, aggregate_type, aggregate_id, aggregate_version,
                               event_type, 'rwms.auth.user-authorization.v1', ?::jsonb, ?,
                               'PENDING', 0, now(), now()
                          from domain_event where event_id=?
                        """,
                        changedEnvelope,
                        sha256(canonicalJson(changedEnvelope)),
                        eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("does not match authoritative domain event");
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox_event where event_id=?", Integer.class, eventId))
                .isZero();
    }

    @Test
    void outboxRequiresCompleteCorrelationAndAcceptsCanonicalEnvelope() throws Exception {
        migrateSeededVersionTwo();
        UUID eventId = insertRuntimeUserEvent("auth.user-authorization.created.v1");
        String incompleteEnvelope = jdbc.queryForObject(
                "select (?::jsonb #- '{correlation,causationId}')::text",
                String.class,
                envelopeFor(eventId));

        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into outbox_event(
                            event_id, aggregate_type, aggregate_id, aggregate_version,
                            event_type, topic, envelope_body, envelope_sha256,
                            status, attempt_count, next_attempt_at, created_at)
                        select event_id, aggregate_type, aggregate_id, aggregate_version,
                               event_type, 'rwms.auth.user-authorization.v1', ?::jsonb, ?,
                               'PENDING', 0, now(), now()
                          from domain_event where event_id=?
                        """,
                        incompleteEnvelope,
                        sha256(canonicalJson(incompleteEnvelope)),
                        eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("does not match authoritative domain event");

        insertOutbox(
                eventId,
                "auth.user-authorization.created.v1",
                "rwms.auth.user-authorization.v1");
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox_event where event_id=?", Integer.class, eventId))
                .isOne();
    }

    @Test
    void sanitizedDeadLettersAllowOnlySafeMetadataAndDeliveryStateUpdates() throws Exception {
        migrateSeededVersionTwo();
        UUID dltId = UUID.fromString("30000000-0000-0000-0000-000000000072");
        String messageHash = "a".repeat(64);
        String safeBody = "{\"failureCode\":\"VALIDATION_REJECTED\","
                + "\"messageSha256\":\"" + messageHash + "\","
                + "\"recordedAt\":\"2026-07-13T10:00:00Z\"}";
        insertSanitizedDeadLetter(
                dltId,
                "rwms.auth.user-authorization.v1.auth-shadow-v1.dlt",
                "VALIDATION_REJECTED",
                messageHash,
                safeBody);

        UUID leaseToken = UUID.fromString("30000000-0000-0000-0000-000000000073");
        assertThat(jdbc.update(
                        "update sanitized_dead_letter set status='IN_FLIGHT', lease_owner='dlt-relay', "
                                + "lease_token=?, lease_until=now() + interval '1 minute' where dlt_id=?",
                        leaseToken,
                        dltId))
                .isOne();
        assertThat(jdbc.update(
                        "update sanitized_dead_letter set status='PENDING', attempt_count=1, "
                                + "next_attempt_at=now() + interval '1 second', lease_owner=null, "
                                + "lease_token=null, lease_until=null where dlt_id=?",
                        dltId))
                .isOne();

        assertThatThrownBy(() -> jdbc.update(
                        "update sanitized_dead_letter set safe_body='{}'::jsonb where dlt_id=?", dltId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("metadata is immutable");
        assertThatThrownBy(() -> jdbc.update(
                        "update sanitized_dead_letter set destination="
                                + "'rwms.auth.worker-access.v1.auth-shadow-v1.dlt' where dlt_id=?",
                        dltId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("metadata is immutable");

        String unsafeBody = "{\"failureCode\":\"VALIDATION_REJECTED\","
                + "\"messageSha256\":\"" + messageHash + "\","
                + "\"recordedAt\":\"2026-07-13T10:00:00Z\","
                + "\"rawPayload\":\"password=secret\"}";
        assertThatThrownBy(() -> insertSanitizedDeadLetter(
                        UUID.randomUUID(),
                        "rwms.auth.user-authorization.v1.auth-shadow-v1.dlt",
                        "VALIDATION_REJECTED",
                        messageHash,
                        unsafeBody))
                .isInstanceOf(DataAccessException.class);
        String disguisedRawBody = "{\"failureCode\":\"VALIDATION_REJECTED\","
                + "\"messageSha256\":\"" + messageHash + "\","
                + "\"recordedAt\":\"password=secret\"}";
        assertThatThrownBy(() -> insertSanitizedDeadLetter(
                        UUID.randomUUID(),
                        "rwms.auth.user-authorization.v1.auth-shadow-v1.dlt",
                        "VALIDATION_REJECTED",
                        messageHash,
                        disguisedRawBody))
                .isInstanceOf(DataAccessException.class);
        String dateWithoutTimeBody = "{\"failureCode\":\"VALIDATION_REJECTED\","
                + "\"messageSha256\":\"" + messageHash + "\","
                + "\"recordedAt\":\"2026-07-13\"}";
        assertThatThrownBy(() -> insertSanitizedDeadLetter(
                        UUID.randomUUID(),
                        "rwms.auth.user-authorization.v1.auth-shadow-v1.dlt",
                        "VALIDATION_REJECTED",
                        messageHash,
                        dateWithoutTimeBody))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertSanitizedDeadLetter(
                        UUID.randomUUID(),
                        "rwms.auth.user-authorization.v1.auth-shadow-v1.dlt",
                        "UNKNOWN_FAILURE",
                        messageHash,
                        safeBody))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertSanitizedDeadLetter(
                        UUID.randomUUID(),
                        "rwms.auth.user-authorization.v1.auth-shadow-v2.dlt",
                        "VALIDATION_REJECTED",
                        messageHash,
                        safeBody))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into sanitized_dead_letter(dlt_id, destination, message_sha256, failure_code, "
                                + "safe_body, body_sha256, status, attempt_count, next_attempt_at, created_at) "
                                + "values (?, 'rwms.auth.user-authorization.v1.auth-shadow-v1.dlt', ?, "
                                + "'VALIDATION_REJECTED', ?::jsonb, ?, 'PENDING', 0, now(), now())",
                        UUID.randomUUID(),
                        messageHash,
                        safeBody,
                        "f".repeat(64)))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.update(
                        "update sanitized_dead_letter set status='PUBLISHED', published_at=now() where dlt_id=?",
                        dltId))
                .isOne();
    }

    @Test
    void versionGapResolutionRequiresReasonAndOpaqueResolvingSubject() throws Exception {
        migrateSeededVersionTwo();
        UUID quarantineId = UUID.fromString("30000000-0000-0000-0000-000000000074");
        UUID receivedEventId = UUID.fromString("30000000-0000-0000-0000-000000000075");
        UUID resolvingSubjectId = UUID.fromString("30000000-0000-0000-0000-000000000076");

        jdbc.update(
                "insert into version_gap_quarantine(quarantine_id, consumer_group, aggregate_type, "
                        + "aggregate_id, expected_version, received_version, received_event_id, "
                        + "payload_sha256, reason_code, status, detected_at) "
                        + "values (?, 'auth-shadow-v1', 'USER_AUTHORIZATION', ?, 8, 9, ?, ?, "
                        + "'AGGREGATE_VERSION_GAP', 'OPEN', now())",
                quarantineId,
                USER_ID.toString(),
                receivedEventId,
                "b".repeat(64));

        assertThatThrownBy(() -> jdbc.update(
                        "update version_gap_quarantine set status='RESOLVED', resolved_at=now() "
                                + "where quarantine_id=?",
                        quarantineId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "update version_gap_quarantine set status='RESOLVED', resolved_at=now(), "
                                + "resolution_reason='   ', resolved_by_subject_id=? where quarantine_id=?",
                        resolvingSubjectId,
                        quarantineId))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.update(
                        "update version_gap_quarantine set status='RESOLVED', resolved_at=now(), "
                                + "resolution_reason='Verified replay parity', resolved_by_subject_id=? "
                                + "where quarantine_id=?",
                        resolvingSubjectId,
                        quarantineId))
                .isOne();
        assertThat(jdbc.queryForMap(
                        "select status, resolution_reason, resolved_by_subject_id "
                                + "from version_gap_quarantine where quarantine_id=?",
                        quarantineId))
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolution_reason", "Verified replay parity")
                .containsEntry("resolved_by_subject_id", resolvingSubjectId);
    }

    @Test
    void replayOperationAuditAllowsOneImmutableTerminalTransition() throws Exception {
        migrateSeededVersionTwo();
        UUID operationId = UUID.fromString("30000000-0000-0000-0000-000000000077");
        UUID actorId = UUID.fromString("30000000-0000-0000-0000-000000000078");
        String beforeChecksum = "c".repeat(64);
        String afterChecksum = "d".repeat(64);

        assertThat(jdbc.update(
                        "insert into replay_operation_audit(operation_id, actor_subject_id, reason, status, "
                                + "started_at, before_aggregate_count, before_version_sum, before_checksum) "
                                + "values (?, ?, 'Controlled shadow rebuild', 'STARTED', now(), 2, 11, ?)",
                        operationId,
                        actorId,
                        beforeChecksum))
                .isOne();

        assertThatThrownBy(() -> insertReplayOperation(UUID.randomUUID(), actorId, "   ", "STARTED"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertReplayOperation(
                        UUID.randomUUID(), actorId, "Invalid\nreason", "STARTED"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertReplayOperation(
                        UUID.randomUUID(), actorId, "Bypass lifecycle", "COMPLETED"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("must begin in STARTED status");
        assertThatThrownBy(() -> insertReplayOperation(
                        UUID.randomUUID(), actorId, "Unknown state", "UNKNOWN"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "update replay_operation_audit set reason='Tampered reason', status='COMPLETED', "
                                + "completed_at=now(), after_aggregate_count=2, after_version_sum=11, "
                                + "after_checksum=? where operation_id=?",
                        afterChecksum,
                        operationId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only after one terminal transition");
        assertThatThrownBy(() -> jdbc.update(
                        "update replay_operation_audit set status='COMPLETED', completed_at=now() "
                                + "where operation_id=?",
                        operationId))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.update(
                        "update replay_operation_audit set status='COMPLETED', completed_at=now(), "
                                + "after_aggregate_count=2, after_version_sum=11, after_checksum=? "
                                + "where operation_id=?",
                        afterChecksum,
                        operationId))
                .isOne();
        assertThat(jdbc.queryForMap(
                        "select status, before_aggregate_count, before_version_sum, before_checksum, "
                                + "after_aggregate_count, after_version_sum, after_checksum "
                                + "from replay_operation_audit where operation_id=?",
                        operationId))
                .containsEntry("status", "COMPLETED")
                .containsEntry("before_aggregate_count", 2)
                .containsEntry("before_version_sum", 11L)
                .containsEntry("before_checksum", beforeChecksum)
                .containsEntry("after_aggregate_count", 2)
                .containsEntry("after_version_sum", 11L)
                .containsEntry("after_checksum", afterChecksum);
        assertThatThrownBy(() -> jdbc.update(
                        "update replay_operation_audit set completed_at=now() where operation_id=?",
                        operationId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only after one terminal transition");
        assertThatThrownBy(() -> jdbc.update(
                        "delete from replay_operation_audit where operation_id=?", operationId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be removed");
        assertThatThrownBy(() -> jdbc.execute("truncate table replay_operation_audit"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be removed");
    }

    @Test
    void replayOperationAuditFailureRequiresSanitizedFailureCode() throws Exception {
        migrateSeededVersionTwo();
        UUID operationId = UUID.fromString("30000000-0000-0000-0000-000000000079");
        UUID actorId = UUID.fromString("30000000-0000-0000-0000-000000000080");
        insertReplayOperation(operationId, actorId, "Failed controlled replay", "STARTED");

        assertThatThrownBy(() -> jdbc.update(
                        "update replay_operation_audit set status='FAILED', completed_at=now() "
                                + "where operation_id=?",
                        operationId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "update replay_operation_audit set status='FAILED', completed_at=now(), "
                                + "failure_code='PASSWORD=secret' where operation_id=?",
                        operationId))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.update(
                        "update replay_operation_audit set status='FAILED', completed_at=now(), "
                                + "failure_code='PARITY_MISMATCH' where operation_id=?",
                        operationId))
                .isOne();
        assertThat(jdbc.queryForMap(
                        "select status, failure_code from replay_operation_audit where operation_id=?",
                        operationId))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_code", "PARITY_MISMATCH");
    }

    @Test
    void changedAppliedV3FailsChecksumValidation(@TempDir Path directory) throws IOException {
        Path versionTwo = copyMigration(directory, "V2__auth_schema.sql");
        Path versionThree = copyMigration(directory, "V3__auth_event_sourcing.sql");
        String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
        flyway(location).migrate();

        Files.writeString(
                versionThree,
                Files.readString(versionThree).replace(
                        "aggregate_type varchar(64) NOT NULL", "aggregate_type varchar(63) NOT NULL"));

        assertThat(versionTwo).exists();
        assertThatThrownBy(() -> flyway(location).validate())
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("checksum");
    }

    private void migrateSeededVersionTwo() throws Exception {
        applyVersionTwo();
        seedVersionTwoRows();
        Flyway adopted = configuration(MIGRATION_LOCATION).baselineVersion("2").load();
        adopted.baseline();
        assertThat(adopted.migrate().migrationsExecuted).isEqualTo(4);
    }

    private void seedVersionTwoRows() {
        jdbc.update(
                "insert into auth_subject(id, version, principal_type, username, password_hash, first_name, "
                        + "last_name, email, time_zone_id, global_role, active, created_at, updated_at) "
                        + "values (?, 7, 'USER', 'alice.canary', '{bcrypt}password-secret', 'AliceSecret', "
                        + "'Persona', 'alice.secret@example.test', 'Secret/Zone', 'WMS_ADMIN', true, "
                        + "'2026-07-01T00:00:00Z', '2026-07-02T00:00:00Z')",
                USER_ID);
        jdbc.update(
                "insert into user_warehouse_access(id, version, user_id, warehouse_id, access_level, "
                        + "comment_text, active, created_at, updated_at) "
                        + "values (?, 2, ?, '00000000-0000-0000-0000-000000000001', 'MANAGE', "
                        + "'grant-note-secret-one', true, '2026-07-01T00:00:00Z', '2026-07-02T00:00:00Z')",
                ACCESS_ONE_ID,
                USER_ID);
        jdbc.update(
                "insert into user_warehouse_access(id, version, user_id, warehouse_id, access_level, "
                        + "comment_text, active, created_at, updated_at) "
                        + "values (?, 3, ?, '00000000-0000-0000-0000-000000000002', 'VIEW', "
                        + "'grant-note-secret-two', false, '2026-07-01T00:00:00Z', '2026-07-03T00:00:00Z')",
                ACCESS_TWO_ID,
                USER_ID);
        jdbc.update(
                "insert into user_warehouse_access(id, version, user_id, warehouse_id, access_level, "
                        + "comment_text, active, created_at, updated_at) "
                        + "values (?, 4, ?, '00000000-0000-0000-0000-000000000003', 'EDIT', "
                        + "null, true, '2026-07-01T00:00:00Z', '2026-07-04T00:00:00Z')",
                ACCESS_THREE_ID,
                USER_ID);
        jdbc.update(
                "insert into auth_subject(id, version, principal_type, username, password_hash, "
                        + "external_worker_id, warehouse_id, active, created_at, updated_at) "
                        + "values (?, 4, 'WORKER', 'worker.canary', '{bcrypt}worker-password-secret', "
                        + "'legacy-worker-secret', '00000000-0000-0000-0000-000000000001', false, "
                        + "'2026-07-01T00:00:00Z', '2026-07-04T00:00:00Z')",
                WORKER_ID);
        jdbc.update(
                "insert into oauth2_registered_client(id, client_id, client_name, client_authentication_methods, "
                        + "authorization_grant_types, redirect_uris, post_logout_redirect_uris, scopes, "
                        + "client_settings, token_settings) values "
                        + "('client-row', 'rwms-panel', 'RWMS Panel', 'none', 'authorization_code', "
                        + "'https://example.test/callback', 'https://example.test/logout', 'openid,rwms.read', "
                        + "'{}', '{}')");
        jdbc.update(
                "insert into oauth2_authorization(id, registered_client_id, principal_name, "
                        + "authorization_grant_type, authorized_scopes, access_token_value) "
                        + "values ('authorization-row', 'client-row', 'alice.canary', "
                        + "'authorization_code', 'rwms.read', 'oauth-access-token-secret')");
        jdbc.update(
                "insert into oauth2_authorization_consent(registered_client_id, principal_name, authorities) "
                        + "values ('client-row', 'alice.canary', 'SCOPE_rwms.read')");
    }

    private UUID insertRuntimeUserEvent(String eventType) throws Exception {
        return insertUserEvent(eventType, false, 1);
    }

    private UUID insertUserEvent(String eventType, boolean baseline, int eventVersion) throws Exception {
        UUID eventId = UUID.randomUUID();
        String payload = "{\"subjectId\":\"" + USER_ID + "\","
                + "\"active\":true,\"globalRole\":\"WMS_ADMIN\","
                + "\"profileRevision\":\"40000000-0000-0000-0000-000000000071\","
                + "\"warehouseAccess\":[]}";
        jdbc.update(
                "insert into domain_event(event_id, aggregate_type, aggregate_id, aggregate_version, "
                        + "event_type, event_version, occurred_at, recorded_at, correlation_id, causation_id, "
                        + "actor_ref, payload, payload_sha256, baseline) "
                        + "values (?, 'USER_AUTHORIZATION', ?, 8, ?, ?, "
                        + "case when ? then null else now() end, now(), ?, null, null, ?::jsonb, ?, ?)",
                eventId,
                USER_ID.toString(),
                eventType,
                eventVersion,
                baseline,
                UUID.randomUUID(),
                payload,
                sha256(canonicalJson(payload)),
                baseline);
        return eventId;
    }

    private void insertOutbox(UUID eventId, String eventType, String topic) throws Exception {
        String envelope = envelopeFor(eventId);
        jdbc.update(
                "insert into outbox_event(event_id, aggregate_type, aggregate_id, aggregate_version, "
                        + "event_type, topic, envelope_body, envelope_sha256, status, attempt_count, "
                        + "next_attempt_at, created_at) "
                        + "select event_id, aggregate_type, aggregate_id, aggregate_version, ?, ?, "
                        + "?::jsonb, ?, 'PENDING', 0, now(), now() from domain_event where event_id=?",
                eventType,
                topic,
                envelope,
                sha256(canonicalJson(envelope)),
                eventId);
    }

    private String envelopeFor(UUID eventId) {
        return jdbc.queryForObject(
                """
                select jsonb_build_object(
                    'envelopeVersion', 2,
                    'eventId', event.event_id,
                    'eventType', event.event_type,
                    'eventVersion', event.event_version,
                    'occurredAt', event.occurred_at,
                    'recordedAt', event.recorded_at,
                    'producer', 'auth-service',
                    'aggregateType', event.aggregate_type,
                    'aggregateId', event.aggregate_id,
                    'aggregateVersion', event.aggregate_version,
                    'correlation', jsonb_build_object(
                        'correlationId', event.correlation_id,
                        'causationId', event.causation_id),
                    'actorRef', COALESCE(event.actor_ref, 'null'::jsonb),
                    'payload', event.payload)::text
                  from domain_event event
                 where event_id=?
                """,
                String.class,
                eventId);
    }

    private void insertSanitizedDeadLetter(
            UUID dltId, String destination, String failureCode, String messageHash, String safeBody)
            throws Exception {
        jdbc.update(
                "insert into sanitized_dead_letter(dlt_id, destination, message_sha256, failure_code, "
                        + "safe_body, body_sha256, status, attempt_count, next_attempt_at, created_at) "
                        + "values (?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, now(), now())",
                dltId,
                destination,
                messageHash,
                failureCode,
                safeBody,
                sha256(canonicalJson(safeBody)));
    }

    private void insertReplayOperation(UUID operationId, UUID actorId, String reason, String status) {
        jdbc.update(
                "insert into replay_operation_audit(operation_id, actor_subject_id, reason, status, "
                        + "started_at, before_aggregate_count, before_version_sum, before_checksum) "
                        + "values (?, ?, ?, ?, now(), 2, 11, ?)",
                operationId,
                actorId,
                reason,
                status,
                "c".repeat(64));
    }

    private String canonicalJson(String value) {
        return jdbc.queryForObject("select (?::jsonb)::text", String.class, value);
    }

    private List<Map<String, Object>> deterministicBaselineProjection() {
        return jdbc.queryForList(
                "select event_id::text, aggregate_type, aggregate_id, aggregate_version, event_type, "
                        + "correlation_id::text, payload::text, payload_sha256 "
                        + "from domain_event order by aggregate_type, aggregate_id");
    }

    private String eventPayloadText() {
        return jdbc.queryForObject(
                "select coalesce(string_agg(payload::text, ' ' order by aggregate_type, aggregate_id), '') "
                        + "from domain_event",
                String.class);
    }

    private List<String> rejoinedAccessNotes() {
        return jdbc.queryForList(
                "select grant_item->>'accessId' || '=' || coalesce(note.comment_text, '<null>') "
                        + "from domain_event event "
                        + "cross join lateral jsonb_array_elements(event.payload->'warehouseAccess') grant_item "
                        + "join user_warehouse_access_note note "
                        + "on note.note_revision=(grant_item->>'noteRevision')::uuid "
                        + "where event.aggregate_type='USER_AUTHORIZATION' "
                        + "order by grant_item->>'accessId'",
                String.class);
    }

    private void assertVaultHashesMatchRuntimeCanonicalization() throws Exception {
        assertThat(jdbc.queryForObject(
                        "select profile_sha256 from auth_subject_pii where subject_id=?", String.class, USER_ID))
                .isEqualTo(sha256(String.join(
                        "\u001f",
                        "alice.canary",
                        "AliceSecret",
                        "Persona",
                        "alice.secret@example.test",
                        "Secret/Zone",
                        "<null>")));
        assertThat(jdbc.queryForObject(
                        "select profile_sha256 from auth_subject_pii where subject_id=?", String.class, WORKER_ID))
                .isEqualTo(sha256(String.join(
                        "\u001f",
                        "worker.canary",
                        "<null>",
                        "<null>",
                        "<null>",
                        "<null>",
                        "legacy-worker-secret")));
        assertThat(jdbc.queryForObject(
                        "select note_sha256 from user_warehouse_access_note where access_id=?",
                        String.class,
                        ACCESS_THREE_ID))
                .isEqualTo(sha256("<null>"));
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, String> legacyDigests() {
        return Map.of(
                "auth_subject", digest("auth_subject"),
                "user_warehouse_access", digest("user_warehouse_access"),
                "oauth2_registered_client", digest("oauth2_registered_client"),
                "oauth2_authorization", digest("oauth2_authorization"),
                "oauth2_authorization_consent", digest("oauth2_authorization_consent"));
    }

    private String digest(String table) {
        return jdbc.queryForObject(
                "select md5(coalesce(string_agg(to_jsonb(row_value)::text, '|' "
                        + "order by to_jsonb(row_value)::text), '')) from "
                        + table
                        + " row_value",
                String.class);
    }

    private List<String> tables() {
        return jdbc.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema='public' order by table_name",
                String.class);
    }

    private Path copyMigration(Path directory, String migration) throws IOException {
        Path target = directory.resolve(migration);
        try (var source = requireResource("db/migration/" + migration).openStream()) {
            Files.copy(source, target);
        }
        return target;
    }

    private void applyVersionTwo() throws Exception {
        String remote = "/tmp/V2__auth_schema.sql";
        postgres.copyFileToContainer(resource("db/migration/V2__auth_schema.sql"), remote);
        var result = postgres.execInContainer(
                "psql",
                "-X",
                "--single-transaction",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                postgres.getUsername(),
                "-d",
                postgres.getDatabaseName(),
                "-f",
                remote);
        assertThat(result.getExitCode())
                .withFailMessage("V2 schema failed:%n%s%n%s", result.getStdout(), result.getStderr())
                .isZero();
    }

    private MountableFile resource(String path) throws URISyntaxException {
        return MountableFile.forHostPath(Path.of(requireResource(path).toURI()));
    }

    private java.net.URL requireResource(String path) {
        var resource = AuthEventStoreMigrationIntegrationTest.class.getClassLoader().getResource(path);
        if (resource == null) {
            throw new IllegalStateException("Missing auth migration test resource: " + path);
        }
        return resource;
    }

    private Flyway flyway(String location) {
        return configuration(location).load();
    }

    private FluentConfiguration configuration(String location) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(location)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .outOfOrder(false);
    }

    private void resetPublicSchema() {
        jdbc.execute("drop schema public cascade");
        jdbc.execute("create schema public");
    }
}
