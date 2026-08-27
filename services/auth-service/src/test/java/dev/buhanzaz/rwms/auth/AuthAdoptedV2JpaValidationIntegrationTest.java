package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.auth.config.AuthSubjectBootstrap;
import dev.buhanzaz.rwms.auth.config.OAuthClientProvisioner;
import jakarta.persistence.EntityManagerFactory;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Proves that an adopted V2 schema upgrades without losing its pre-adoption business facts. */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthAdoptedV2JpaValidationIntegrationTest {

    private static final PostgreSQLContainer postgres = startPreparedVersionTwoDatabase();
    private static final Map<String, String> beforeStartup = retainedDigests(jdbc());

    @MockitoBean
    AuthSubjectBootstrap authSubjectBootstrap;

    @MockitoBean
    OAuthClientProvisioner oauthClientProvisioner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void bootStartsWithJpaValidationAndPreservesAdoptedVersionTwoFacts() {
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(retainedDigests(jdbc)).containsExactlyInAnyOrderEntriesOf(beforeStartup);
        assertThat(jdbc.queryForObject(
                        "select count(*) from flyway_schema_history where version='2' and type='BASELINE' and success",
                        Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject("select count(*) from auth_subject", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from user_warehouse_access", Integer.class)).isOne();
        assertThat(jdbc.queryForMap(
                        "select version, mobile_app_access, rental_access from auth_subject"))
                .containsEntry("version", 5)
                .containsEntry("mobile_app_access", false)
                .containsEntry("rental_access", false);
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    private static PostgreSQLContainer startPreparedVersionTwoDatabase() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:17-alpine");
        container.start();
        try {
            apply(container, "schema.sql");
            JdbcTemplate jdbc = jdbc(container);
            jdbc.execute("""
                    create table public.rwms_schema_history (
                        version varchar(64) primary key,
                        checksum varchar(64) not null check (checksum ~ '^[0-9a-f]{64}$'),
                        applied_at timestamptz not null default clock_timestamp(),
                        description varchar(255) not null
                    )
                    """);
            jdbc.update(
                    "insert into rwms_schema_history(version, checksum, description) values (?, ?, ?)",
                    "0001",
                    "33e3e80bcbcaefae473649b87cc8fac89525e6218c615329b09eeb443877aa49",
                    "Adopt auth schema");
            jdbc.update(
                    "insert into rwms_schema_history(version, checksum, description) values (?, ?, ?)",
                    "0002",
                    "e4fadeb39b7947632923548875f1b311de2fad7810854b9696e32a4bd180aeb3",
                    "Canonicalize warehouse identifiers");
            jdbc.execute("create table databasechangelog (id varchar(255) not null, author varchar(255) not null)");
            jdbc.update("insert into databasechangelog(id, author) values (?, ?)", "001-auth-subjects", "fixture");
            jdbc.execute("create table databasechangeloglock (id integer primary key, locked boolean not null)");
            jdbc.update("insert into databasechangeloglock(id, locked) values (1, false)");
            jdbc.update("""
                    insert into auth_subject(
                        id, version, principal_type, username, password_hash, global_role,
                        active, created_at, updated_at
                    ) values (
                        '10000000-0000-0000-0000-000000000101', 4, 'USER', 'restored.user',
                        '{noop}password', 'VIEWER', true,
                        '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z'
                    )
                    """);
            jdbc.update("""
                    insert into user_warehouse_access(
                        id, version, user_id, warehouse_id, access_level,
                        active, created_at, updated_at
                    ) values (
                        '20000000-0000-0000-0000-000000000101', 3,
                        '10000000-0000-0000-0000-000000000101',
                        '00000000-0000-0000-0000-000000000001', 'MANAGE', true,
                        '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z'
                    )
                    """);
            apply(container, "verify-version-2.sql");
            Flyway.configure()
                    .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(false)
                    .baselineVersion("2")
                    .baselineDescription("Auth post-F1C schema")
                    .validateOnMigrate(true)
                    .validateMigrationNaming(true)
                    .cleanDisabled(true)
                    .outOfOrder(false)
                    .load()
                    .baseline();
            return container;
        } catch (Exception exception) {
            container.stop();
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static JdbcTemplate jdbc() {
        return jdbc(postgres);
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer container) {
        return new JdbcTemplate(new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword()));
    }

    private static Map<String, String> retainedDigests(JdbcTemplate jdbc) {
        return Map.of(
                "auth_subject", digestQuery(
                        jdbc,
                        "select id, principal_type, username, password_hash, first_name, last_name, "
                                + "email, time_zone_id, global_role, external_worker_id, warehouse_id, "
                                + "active, created_at from auth_subject"),
                "user_warehouse_access", digest(jdbc, "user_warehouse_access"),
                "rwms_schema_history", digest(jdbc, "rwms_schema_history"),
                "databasechangelog", digest(jdbc, "databasechangelog"),
                "databasechangeloglock", digest(jdbc, "databasechangeloglock"));
    }

    private static String digest(JdbcTemplate jdbc, String table) {
        return digestQuery(jdbc, "select * from " + table);
    }

    private static String digestQuery(JdbcTemplate jdbc, String query) {
        return jdbc.queryForObject(
                "select md5(coalesce(string_agg(to_jsonb(row_value)::text, '|' "
                        + "order by to_jsonb(row_value)::text), '')) from ("
                        + query
                        + ") row_value",
                String.class);
    }

    private static void apply(PostgreSQLContainer container, String resource) throws Exception {
        String remote = "/tmp/" + resource.replace('/', '-');
        container.copyFileToContainer(resource(resource), remote);
        var result = container.execInContainer(
                "psql",
                "-X",
                "--single-transaction",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                container.getUsername(),
                "-d",
                container.getDatabaseName(),
                "-f",
                remote);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(
                    resource + " failed:\n" + result.getStdout() + "\n" + result.getStderr());
        }
    }

    private static MountableFile resource(String path) throws URISyntaxException {
        var resource = AuthAdoptedV2JpaValidationIntegrationTest.class.getClassLoader().getResource(path);
        if (resource == null) {
            throw new IllegalStateException("Missing adopted auth test resource: " + path);
        }
        return MountableFile.forHostPath(Path.of(resource.toURI()));
    }
}
