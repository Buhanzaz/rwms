package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class AuthFlywayMigrationIntegrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop schema public cascade");
        jdbc.execute("create schema public");
    }

    @Test
    void cumulativeBaselineMigratesCleanDatabaseAndRepeatIsNoOp() {
        Flyway flyway = flyway(MIGRATION_LOCATION);

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        assertThat(jdbc.queryForList(
                        "select table_name from information_schema.tables "
                                + "where table_schema='public' order by table_name",
                        String.class))
                .containsExactly(
                        "aggregate_snapshot",
                        "auth_subject",
                        "auth_subject_credential",
                        "auth_subject_pii",
                        "consumer_aggregate_checkpoint",
                        "domain_event",
                        "event_stream_head",
                        "flyway_schema_history",
                        "inbox_message",
                        "oauth2_authorization",
                        "oauth2_authorization_consent",
                        "oauth2_registered_client",
                        "outbox_event",
                        "projection_checkpoint",
                        "replay_operation_audit",
                        "sanitized_dead_letter",
                        "user_warehouse_access",
                        "user_warehouse_access_note",
                        "version_gap_quarantine");
        assertThat(columnCounts()).containsAllEntriesOf(Map.of(
                "auth_subject", 17,
                "user_warehouse_access", 9,
                "oauth2_registered_client", 13,
                "oauth2_authorization", 33,
                "oauth2_authorization_consent", 3));
        assertThat(jdbc.queryForMap(
                        "select version, description, script, success from flyway_schema_history "
                                + "where version='2'"))
                .containsEntry("version", "2")
                .containsEntry("description", "auth schema")
                .containsEntry("script", "V2__auth_schema.sql")
                .containsEntry("success", true);
        assertThat(jdbc.queryForMap(
                        "select version, description, script, success from flyway_schema_history "
                                + "where version='3'"))
                .containsEntry("version", "3")
                .containsEntry("description", "auth event sourcing")
                .containsEntry("script", "V3__auth_event_sourcing.sql")
                .containsEntry("success", true);
        assertThat(jdbc.queryForMap(
                        "select version, description, script, success from flyway_schema_history "
                                + "where version='4'"))
                .containsEntry("version", "4")
                .containsEntry("description", "worker android oauth client")
                .containsEntry("script", "V4__worker_android_oauth_client.sql")
                .containsEntry("success", true);
        assertThat(jdbc.queryForMap(
                        "select version, description, script, success from flyway_schema_history "
                                + "where version='5'"))
                .containsEntry("version", "5")
                .containsEntry("description", "manager mobile app access")
                .containsEntry("script", "V5__manager_mobile_app_access.sql")
                .containsEntry("success", true);
        assertThat(jdbc.queryForMap(
                        "select version, description, script, success from flyway_schema_history "
                                + "where version='6'"))
                .containsEntry("version", "6")
                .containsEntry("description", "rental access")
                .containsEntry("script", "V6__rental_access.sql")
                .containsEntry("success", true);
        assertV3Schema();
        assertThat(jdbc.queryForObject("select count(*) from auth_subject", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from oauth2_registered_client", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from domain_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class)).isZero();
    }

    @Test
    void workerAndroidClientUpgradeRenamesClientAndRevokesExistingAuthorizations() {
        configuration(MIGRATION_LOCATION).target("3").load().migrate();
        var clients = new JdbcRegisteredClientRepository(jdbc);
        RegisteredClient legacy = RegisteredClient.withId("rwms-worker-id")
                .clientId("rwms-worker")
                .clientName("RWMS Worker")
                .clientAuthenticationMethod(
                        org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://example.test/auth/callback")
                .scope("worker.tasks")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder().build())
                .build();
        clients.save(legacy);
        var authorizations = new JdbcOAuth2AuthorizationService(jdbc, clients);
        Instant issuedAt = Instant.parse("2026-07-24T00:00:00Z");
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(legacy)
                .id("rwms-worker-authorization")
                .principalName("worker.legacy")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("worker.tasks"))
                .accessToken(new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "legacy-access-token",
                        issuedAt,
                        issuedAt.plusSeconds(300),
                        Set.of("worker.tasks")))
                .build();
        authorizations.save(authorization);
        var consents = new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
        consents.save(OAuth2AuthorizationConsent.withId(legacy.getId(), "worker.legacy")
                .authority(new SimpleGrantedAuthority("SCOPE_worker.tasks"))
                .build());

        assertThat(flyway(MIGRATION_LOCATION).migrate().migrationsExecuted).isEqualTo(3);

        assertThat(clients.findByClientId("rwms-worker")).isNull();
        assertThat(clients.findByClientId("rwms-worker-android")).isNotNull();
        assertThat(authorizations.findById(authorization.getId())).isNull();
        assertThat(consents.findById(legacy.getId(), "worker.legacy")).isNull();
    }

    @Test
    void managerMobileAccessBackfillEnablesEligibleUsersAndAdvancesEventTruth() {
        configuration(MIGRATION_LOCATION).target("2").load().migrate();
        UUID managerId = UUID.fromString("10000000-0000-0000-0000-000000000051");
        insertUser(managerId, "warehouse.manager");
        jdbc.update(
                "update auth_subject set global_role='WAREHOUSE_MANAGER' where id=?",
                managerId);

        assertThat(flyway(MIGRATION_LOCATION).migrate().migrationsExecuted).isEqualTo(4);

        assertThat(jdbc.queryForMap(
                        "select version, mobile_app_access from auth_subject where id=?",
                        managerId))
                .containsEntry("version", 1)
                .containsEntry("mobile_app_access", true);
        assertThat(jdbc.queryForMap(
                        "select current_version, last_event_id from event_stream_head "
                                + "where aggregate_type='USER_AUTHORIZATION' and aggregate_id=?",
                        managerId.toString()))
                .containsEntry("current_version", 1L);
        assertThat(jdbc.queryForObject(
                        "select count(*) from domain_event "
                                + "where aggregate_type='USER_AUTHORIZATION' and aggregate_id=?",
                        Integer.class,
                        managerId.toString()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select payload->>'mobileAppAccess' from domain_event "
                                + "where aggregate_type='USER_AUTHORIZATION' and aggregate_id=? "
                                + "order by aggregate_version desc limit 1",
                        String.class,
                        managerId.toString()))
                .isEqualTo("true");
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox_event "
                                + "where aggregate_type='USER_AUTHORIZATION' and aggregate_id=? "
                                + "and status='PENDING'",
                        Integer.class,
                        managerId.toString()))
                .isOne();
        assertThatThrownBy(() -> jdbc.update(
                        "update auth_subject set global_role='VIEWER' where id=?",
                        managerId))
                .hasMessageContaining("ck_auth_subject_mobile_app_access");
    }

    @Test
    void rentalAccessBackfillUsesRoleDefaultsForExistingUsers() {
        configuration(MIGRATION_LOCATION).target("5").load().migrate();
        UUID systemAdminId = UUID.fromString("10000000-0000-0000-0000-000000000061");
        UUID wmsAdminId = UUID.fromString("10000000-0000-0000-0000-000000000062");
        UUID rentalManagerId = UUID.fromString("10000000-0000-0000-0000-000000000063");
        UUID warehouseManagerId = UUID.fromString("10000000-0000-0000-0000-000000000064");
        UUID viewerId = UUID.fromString("10000000-0000-0000-0000-000000000065");
        insertUser(systemAdminId, "rental.system-admin");
        insertUser(wmsAdminId, "rental.wms-admin");
        insertUser(rentalManagerId, "rental.manager");
        insertUser(warehouseManagerId, "rental.warehouse-manager");
        insertUser(viewerId, "rental.viewer");
        jdbc.update("update auth_subject set global_role='SYSTEM_ADMIN', active=false where id=?", systemAdminId);
        jdbc.update("update auth_subject set global_role='WMS_ADMIN' where id=?", wmsAdminId);
        jdbc.update("update auth_subject set global_role='RENTAL_MANAGER' where id=?", rentalManagerId);
        jdbc.update("update auth_subject set global_role='WAREHOUSE_MANAGER' where id=?", warehouseManagerId);

        assertThat(flyway(MIGRATION_LOCATION).migrate().migrationsExecuted).isOne();

        assertThat(rentalAccess(systemAdminId)).isTrue();
        assertThat(rentalAccess(wmsAdminId)).isTrue();
        assertThat(rentalAccess(rentalManagerId)).isTrue();
        assertThat(rentalAccess(warehouseManagerId)).isFalse();
        assertThat(rentalAccess(viewerId)).isFalse();
    }

    @Test
    void changedAppliedBaselineFailsChecksumValidation(@TempDir Path directory) throws IOException {
        Path migration = directory.resolve("V2__auth_schema.sql");
        try (var source = requireResource("db/migration/V2__auth_schema.sql").openStream()) {
            Files.copy(source, migration);
        }
        String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
        flyway(location).migrate();

        Files.writeString(
                migration,
                Files.readString(migration).replace("username varchar(128)", "username varchar(127)"));

        assertThatThrownBy(() -> flyway(location).validate())
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void nonEmptyUnversionedSchemaIsNeverAdoptedAutomatically() throws Exception {
        apply("schema.sql");

        assertThatThrownBy(() -> flyway(MIGRATION_LOCATION).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty schema");
        assertThat(jdbc.queryForObject("select to_regclass('public.flyway_schema_history')", String.class))
                .isNull();
    }

    @Test
    void versionTwoPreflightRejectsLegacyWarehouseAliasBeforeBaseline() throws Exception {
        apply("schema.sql");
        createHistoricalMigrationEvidence();
        UUID userId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        insertUser(userId, "legacy.alias.user");
        insertAccess(
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                userId,
                "spb");

        applyExpectFailure("verify-version-2.sql");

        assertThat(jdbc.queryForObject("select to_regclass('public.flyway_schema_history')", String.class))
                .isNull();
    }

    @Test
    void versionTwoPreflightRejectsCatalogDriftWithTheSameColumnCount() throws Exception {
        apply("schema.sql");
        createHistoricalMigrationEvidence();
        jdbc.execute("alter table auth_subject alter column email type varchar(254)");

        applyExpectFailure("verify-version-2.sql");

        assertThat(jdbc.queryForObject(
                        "select character_maximum_length from information_schema.columns "
                                + "where table_schema='public' and table_name='auth_subject' and column_name='email'",
                        Integer.class))
                .isEqualTo(254);
        assertThat(jdbc.queryForObject("select to_regclass('public.flyway_schema_history')", String.class))
                .isNull();
    }

    @Test
    void versionTwoPreflightRejectsUnsupportedJpaEnumValues() throws Exception {
        apply("schema.sql");
        createHistoricalMigrationEvidence();
        UUID userId = UUID.fromString("10000000-0000-0000-0000-000000000031");
        insertUser(userId, "invalid.enum.user");
        jdbc.update("update auth_subject set global_role='UNKNOWN_ROLE' where id=?", userId);

        applyExpectFailure("verify-version-2.sql");

        jdbc.update("update auth_subject set global_role='VIEWER' where id=?", userId);
        insertAccess(
                UUID.fromString("20000000-0000-0000-0000-000000000031"),
                userId,
                "00000000-0000-0000-0000-000000000001");
        jdbc.update("update user_warehouse_access set access_level='UNKNOWN_ACCESS' where user_id=?", userId);
        applyExpectFailure("verify-version-2.sql");
    }

    @Test
    void versionTwoPreflightRejectsWarehouseGrantForWorkerSubject() throws Exception {
        apply("schema.sql");
        createHistoricalMigrationEvidence();
        UUID workerId = UUID.fromString("10000000-0000-0000-0000-000000000041");
        jdbc.update(
                "insert into auth_subject(id, version, principal_type, username, password_hash, "
                        + "external_worker_id, warehouse_id, active, created_at, updated_at) "
                        + "values (?, 0, 'WORKER', ?, '{noop}password', ?, ?, true, "
                        + "'2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
                workerId,
                "worker.with.grant",
                "external-worker-with-grant",
                "00000000-0000-0000-0000-000000000001");
        insertAccess(
                UUID.fromString("20000000-0000-0000-0000-000000000041"),
                workerId,
                "00000000-0000-0000-0000-000000000001");

        applyExpectFailure("verify-version-2.sql");
    }

    @Test
    void versionTwoPreflightRejectsMalformedHistoricalChecksumEvidence() throws Exception {
        apply("schema.sql");
        createHistoricalMigrationEvidence();
        jdbc.update("update rwms_schema_history set checksum=? where version='0002'", "c".repeat(64));

        applyExpectFailure("verify-version-2.sql");
    }

    @Test
    void verifiedVersionTwoDatabaseIsExplicitlyBaselinedWithoutChangingRows() throws Exception {
        apply("schema.sql");
        createHistoricalMigrationEvidence();
        seedCanonicalAuthRows();
        OAuthFixtures oauth = seedOAuthRows();
        Map<String, String> legacyBefore = retainedLegacyContentDigests();

        apply("verify-version-2.sql");
        assertThatThrownBy(() -> flyway(MIGRATION_LOCATION).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty schema");

        Flyway adopted = configuration(MIGRATION_LOCATION)
                .baselineVersion("2")
                .baselineDescription("Auth post-F1C schema")
                .load();
        adopted.baseline();
        assertThat(adopted.migrate().migrationsExecuted).isEqualTo(4);
        adopted.validate();
        assertThat(adopted.migrate().migrationsExecuted).isZero();

        assertThat(retainedLegacyContentDigests()).containsExactlyInAnyOrderEntriesOf(legacyBefore);
        assertThat(jdbc.queryForMap(
                        "select version, description, type, success from flyway_schema_history "
                                + "where version='2'"))
                .containsEntry("version", "2")
                .containsEntry("description", "Auth post-F1C schema")
                .containsEntry("type", "BASELINE")
                .containsEntry("success", true);
        assertThat(jdbc.queryForMap(
                        "select version, description, type, script, success from flyway_schema_history "
                                + "where version='3'"))
                .containsEntry("version", "3")
                .containsEntry("description", "auth event sourcing")
                .containsEntry("type", "SQL")
                .containsEntry("script", "V3__auth_event_sourcing.sql")
                .containsEntry("success", true);
        assertV3Schema();
        assertAdoptedV3State();
        assertThat(oauth.clients().findByClientId(oauth.client().getClientId())).isNotNull();
        assertThat(oauth.authorizations().findById(oauth.authorizationId())).isNotNull();
        assertThat(oauth.consents().findById(oauth.client().getId(), "admin")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from rwms_schema_history", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from databasechangelog", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from databasechangeloglock", Integer.class)).isEqualTo(1);
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

    private void createHistoricalMigrationEvidence() {
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
    }

    private void seedCanonicalAuthRows() {
        UUID userId = UUID.fromString("10000000-0000-0000-0000-000000000011");
        insertUser(userId, "migration.user");
        insertAccess(
                UUID.fromString("20000000-0000-0000-0000-000000000011"),
                userId,
                "00000000-0000-0000-0000-000000000001");
        jdbc.update(
                "insert into auth_subject(id, version, principal_type, username, password_hash, "
                        + "external_worker_id, warehouse_id, active, created_at, updated_at) "
                        + "values (?, 3, 'WORKER', ?, '{noop}password', ?, ?, true, "
                        + "'2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
                UUID.fromString("10000000-0000-0000-0000-000000000012"),
                "migration.worker",
                "worker-12",
                "00000000-0000-0000-0000-000000000002");
    }

    private void insertUser(UUID id, String username) {
        jdbc.update(
                "insert into auth_subject(id, version, principal_type, username, password_hash, "
                        + "global_role, active, created_at, updated_at) "
                        + "values (?, 0, 'USER', ?, '{noop}password', 'VIEWER', true, "
                        + "'2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
                id,
                username);
    }

    private void insertAccess(UUID id, UUID userId, String warehouseId) {
        jdbc.update(
                "insert into user_warehouse_access(id, version, user_id, warehouse_id, access_level, "
                        + "active, created_at, updated_at) values (?, 0, ?, ?, 'MANAGE', true, "
                        + "'2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
                id,
                userId,
                warehouseId);
    }

    private OAuthFixtures seedOAuthRows() {
        var clients = new JdbcRegisteredClientRepository(jdbc);
        RegisteredClient client = RegisteredClient.withId("rwms-panel-id")
                .clientId("rwms-panel")
                .clientName("RWMS Panel")
                .clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://example.test/callback")
                .scope("rwms.read")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder().build())
                .build();
        clients.save(client);

        var authorizations = new JdbcOAuth2AuthorizationService(jdbc, clients);
        Instant issuedAt = Instant.parse("2026-07-12T00:00:00Z");
        OAuth2AccessToken token = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "fixture-access-token",
                issuedAt,
                issuedAt.plusSeconds(300),
                Set.of("rwms.read"));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id("fixture-authorization")
                .principalName("admin")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("rwms.read"))
                .accessToken(token)
                .build();
        authorizations.save(authorization);

        var consents = new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
        consents.save(OAuth2AuthorizationConsent.withId(client.getId(), "admin")
                .authority(new SimpleGrantedAuthority("SCOPE_rwms.read"))
                .build());
        return new OAuthFixtures(clients, authorizations, consents, client, authorization.getId());
    }

    private Map<String, String> retainedLegacyContentDigests() {
        return Map.of(
                "user_warehouse_access", digest("user_warehouse_access"),
                "oauth2_registered_client", digest("oauth2_registered_client"),
                "oauth2_authorization", digest("oauth2_authorization"),
                "oauth2_authorization_consent", digest("oauth2_authorization_consent"),
                "rwms_schema_history", digest("rwms_schema_history"),
                "databasechangelog", digest("databasechangelog"),
                "databasechangeloglock", digest("databasechangeloglock"));
    }

    private void assertV3Schema() {
        assertThat(jdbc.queryForList(
                        """
                        select constraint_name
                          from information_schema.table_constraints
                         where constraint_schema='public'
                        """,
                        String.class))
                .contains(
                        "fk_auth_subject_pii_subject",
                        "ck_auth_subject_credential_status",
                        "uk_user_warehouse_access_note_revision",
                        "event_stream_head_pkey",
                        "uk_domain_event_stream_version",
                        "ck_domain_event_payload",
                        "ck_outbox_event_status",
                        "ck_consumer_checkpoint_blocked",
                        "ck_replay_operation_audit_terminal");
        assertThat(jdbc.queryForList(
                        """
                        select tgname from pg_trigger
                         where not tgisinternal and tgrelid in (
                             'public.domain_event'::regclass,
                             'public.outbox_event'::regclass,
                             'public.replay_operation_audit'::regclass)
                        """,
                        String.class))
                .contains(
                        "trg_domain_event_append_only",
                        "trg_domain_event_no_truncate",
                        "trg_outbox_event_domain_parity",
                        "trg_replay_operation_audit_lifecycle");
    }

    private void assertAdoptedV3State() {
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from auth_subject subject
                          join auth_subject_pii pii on pii.subject_id=subject.id
                         where pii.username=subject.username
                           and pii.first_name is not distinct from subject.first_name
                           and pii.last_name is not distinct from subject.last_name
                           and pii.email is not distinct from subject.email
                           and pii.time_zone_id is not distinct from subject.time_zone_id
                           and pii.external_worker_id is not distinct from subject.external_worker_id
                        """,
                        Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from auth_subject subject
                          join auth_subject_credential credential on credential.subject_id=subject.id
                         where credential.password_hash=subject.password_hash
                           and credential.credential_status=case
                               when subject.active then 'ACTIVE' else 'DISABLED' end
                        """,
                        Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from user_warehouse_access access
                          join user_warehouse_access_note note on note.access_id=access.id
                         where note.comment_text is not distinct from access.comment_text
                        """,
                        Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject(
                        """
                        select count(*)
                          from event_stream_head head
                          join domain_event event
                            on event.aggregate_type=head.aggregate_type
                           and event.aggregate_id=head.aggregate_id
                           and event.aggregate_version=head.current_version
                           and event.event_id=head.last_event_id
                          join projection_checkpoint checkpoint
                            on checkpoint.projection_name='auth-live-v1'
                           and checkpoint.aggregate_type=event.aggregate_type
                           and checkpoint.aggregate_id=event.aggregate_id
                           and checkpoint.aggregate_version=event.aggregate_version
                           and checkpoint.projection_sha256=event.payload_sha256
                        """,
                        Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select sum(aggregate_version) from domain_event where baseline",
                        Long.class))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                        "select coalesce(string_agg(payload::text, ''), '') from domain_event",
                        String.class))
                .doesNotContain("migration.user", "migration.worker", "worker-12", "{noop}password");
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox_event "
                                + "where event_type='auth.user-authorization.changed.v1' "
                                + "and status='PENDING'",
                        Integer.class))
                .isOne();
    }

    private String digest(String table) {
        return jdbc.queryForObject(
                "select md5(coalesce(string_agg(to_jsonb(row_value)::text, '|' "
                        + "order by to_jsonb(row_value)::text), '')) from "
                        + table
                        + " row_value",
                String.class);
    }

    private Map<String, Integer> columnCounts() {
        return jdbc.query(
                "select table_name, count(*) from information_schema.columns "
                        + "where table_schema='public' group by table_name",
                result -> {
                    var counts = new java.util.HashMap<String, Integer>();
                    while (result.next()) {
                        counts.put(result.getString(1), result.getInt(2));
                    }
                    return counts;
                });
    }

    private boolean rentalAccess(UUID subjectId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select rental_access from auth_subject where id=?", Boolean.class, subjectId));
    }

    private void apply(String resource) throws Exception {
        executeResource(resource, false);
    }

    private void applyExpectFailure(String resource) throws Exception {
        executeResource(resource, true);
    }

    private void executeResource(String resource, boolean failureExpected) throws Exception {
        String remote = "/tmp/" + resource.replace('/', '-');
        postgres.copyFileToContainer(resource(resource), remote);
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
        if (failureExpected) {
            assertThat(result.getExitCode())
                    .withFailMessage("%s unexpectedly succeeded:%n%s%n%s", resource, result.getStdout(), result.getStderr())
                    .isNotZero();
        } else {
            assertThat(result.getExitCode())
                    .withFailMessage("%s failed:%n%s%n%s", resource, result.getStdout(), result.getStderr())
                    .isZero();
        }
    }

    private MountableFile resource(String path) throws URISyntaxException {
        return MountableFile.forHostPath(Path.of(requireResource(path).toURI()));
    }

    private java.net.URL requireResource(String path) {
        var resource = AuthFlywayMigrationIntegrationTest.class.getClassLoader().getResource(path);
        if (resource == null) {
            throw new IllegalStateException("Missing auth migration test resource: " + path);
        }
        return resource;
    }

    private record OAuthFixtures(
            JdbcRegisteredClientRepository clients,
            JdbcOAuth2AuthorizationService authorizations,
            JdbcOAuth2AuthorizationConsentService consents,
            RegisteredClient client,
            String authorizationId) {
    }
}
