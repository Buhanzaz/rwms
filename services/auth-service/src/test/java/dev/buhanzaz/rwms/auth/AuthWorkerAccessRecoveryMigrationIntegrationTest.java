package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class AuthWorkerAccessRecoveryMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private static final UUID WORKER = UUID.fromString("10000000-0000-0000-0000-000000000013");
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void prepare() {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("drop schema public cascade");
        jdbc.execute("create schema public");
        flyway("12").migrate();
    }

    @Test
    void recoversCurrentStateWithoutChangingIdentityPasswordOrInventingHistory() {
        insertWorker();
        Map<String, Object> before = workerState();

        assertThat(flyway("13").migrate().migrationsExecuted).isOne();

        assertThat(workerState()).isEqualTo(before);
        assertThat(jdbc.queryForMap("""
                select h.current_version, e.baseline, e.occurred_at,
                       e.payload->>'workerLink' as worker_link,
                       e.payload_sha256 = encode(sha256(convert_to(e.payload::text, 'UTF8')), 'hex') as valid_hash,
                       c.aggregate_version, c.projection_sha256 = e.payload_sha256 as valid_checkpoint
                from event_stream_head h
                join domain_event e on e.event_id = h.last_event_id
                join projection_checkpoint c on c.aggregate_id = h.aggregate_id
                where h.aggregate_id = ?
                """, WORKER.toString()))
                .containsEntry("current_version", 2L)
                .containsEntry("aggregate_version", 2L)
                .containsEntry("worker_link", WORKER.toString())
                .containsEntry("baseline", true)
                .containsEntry("occurred_at", null)
                .containsEntry("valid_hash", true)
                .containsEntry("valid_checkpoint", true);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class)).isZero();
        assertThat(flyway("13").migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select count(*) from domain_event", Integer.class)).isOne();
    }

    @Test
    void rejectsAnotherWorkerWithoutItsEventStreamAndRollsBack() {
        flyway("13").migrate();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> insertWorker()))
                .isInstanceOf(TransactionSystemException.class)
                .rootCause()
                .hasMessageContaining("Worker access projection requires a consistent event stream");

        assertThat(jdbc.queryForObject("select count(*) from auth_subject", Integer.class)).isZero();
    }

    @Test
    void rejectsProjectionOnlyVersionChangeButAllowsUnchangedProjection() {
        insertWorker();
        flyway("13").migrate();

        assertThatThrownBy(() -> jdbc.update("update auth_subject set version = version + 1 where id = ?", WORKER))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("select version from auth_subject where id = ?", Integer.class, WORKER))
                .isEqualTo(2);
        assertThat(jdbc.update("update auth_subject set updated_at = now() where id = ?", WORKER)).isOne();
    }

    @Test
    void refusesRecoveryWhenTheCredentialVaultIsIncomplete() {
        insertWorker();
        jdbc.update("delete from auth_subject_credential where subject_id = ?", WORKER);

        assertThatThrownBy(() -> flyway("13").migrate())
                .hasMessageContaining("Cannot recover inconsistent worker access state");
        assertThat(jdbc.queryForObject("select count(*) from event_stream_head", Integer.class)).isZero();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(target).load();
    }

    private Map<String, Object> workerState() {
        return jdbc.queryForMap("""
                select to_jsonb(s)::text as subject, to_jsonb(p)::text as profile, to_jsonb(c)::text as credential
                from auth_subject s join auth_subject_pii p on p.subject_id = s.id
                join auth_subject_credential c on c.subject_id = s.id where s.id = ?
                """, WORKER);
    }

    private void insertWorker() {
        jdbc.update("""
                insert into auth_subject(id, version, principal_type, username, password_hash,
                    external_worker_id, warehouse_id, active, created_at, updated_at)
                values (?, 2, 'WORKER', 'recovery-worker', 'test-password-hash',
                    'test-worker', '10000000-0000-0000-0000-000000000099', true, now(), now())
                """, WORKER);
        jdbc.update("""
                insert into auth_subject_pii(subject_id, username, external_worker_id,
                    profile_revision, profile_sha256, created_at, updated_at)
                values (?, 'recovery-worker', 'test-worker', gen_random_uuid(), repeat('a', 64), now(), now())
                """, WORKER);
        jdbc.update("""
                insert into auth_subject_credential(subject_id, password_hash, credential_status,
                    credential_revision, created_at, updated_at)
                values (?, 'test-password-hash', 'ACTIVE', gen_random_uuid(), now(), now())
                """, WORKER);
    }
}
