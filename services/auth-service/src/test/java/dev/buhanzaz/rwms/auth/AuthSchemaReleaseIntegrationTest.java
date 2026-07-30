package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class AuthSchemaReleaseIntegrationTest {

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
    void releaseCreatesAndVerifiesExactCleanSchemaWithoutLiquibaseHistory() throws Exception {
        apply("V0001__adopt-auth-schema/apply.sql");
        apply("V0001__adopt-auth-schema/verify.sql");
        apply("V0002__canonicalize-warehouse-identifiers/apply.sql");
        apply("V0002__canonicalize-warehouse-identifiers/verify.sql");
        apply("V0001__adopt-auth-schema/apply.sql");
        apply("V0001__adopt-auth-schema/verify.sql");
        apply("V0002__canonicalize-warehouse-identifiers/apply.sql");
        apply("V0002__canonicalize-warehouse-identifiers/verify.sql");

        assertThat(jdbc.queryForList(
                        "select table_name from information_schema.tables "
                                + "where table_schema='public' order by table_name",
                        String.class))
                .containsExactly(
                        "auth_subject",
                        "oauth2_authorization",
                        "oauth2_authorization_consent",
                        "oauth2_registered_client",
                        "user_warehouse_access");
        assertThat(columnCounts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "auth_subject", 15,
                "user_warehouse_access", 9,
                "oauth2_registered_client", 13,
                "oauth2_authorization", 33,
                "oauth2_authorization_consent", 3));
    }

    @Test
    void warehouseIdentifierReleaseCanonicalizesAliasesAndWorkersWithoutChangingRowIds() throws Exception {
        apply("V0001__adopt-auth-schema/apply.sql");
        UUID userId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID workerId = UUID.fromString("10000000-0000-0000-0000-000000000002");
        UUID accessId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        UUID uppercaseAccessId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        insertUser(userId, "migration.user");
        insertWorker(workerId, "migration.worker", "MsK", 4);
        insertAccess(accessId, userId, "SPB", 6, true);
        insertAccess(uppercaseAccessId, userId, "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", 2, true);
        OffsetDateTime oldAccessUpdatedAt = jdbc.queryForObject(
                "select updated_at from user_warehouse_access where id=?", OffsetDateTime.class, accessId);
        OffsetDateTime oldWorkerUpdatedAt = jdbc.queryForObject(
                "select updated_at from auth_subject where id=?", OffsetDateTime.class, workerId);

        apply("V0002__canonicalize-warehouse-identifiers/apply.sql");
        apply("V0002__canonicalize-warehouse-identifiers/verify.sql");

        assertThat(jdbc.queryForMap(
                        "select id, warehouse_id, version, updated_at from user_warehouse_access where id=?", accessId))
                .containsEntry("id", accessId)
                .containsEntry("warehouse_id", "00000000-0000-0000-0000-000000000001")
                .containsEntry("version", 7);
        assertThat(jdbc.queryForObject(
                        "select updated_at from user_warehouse_access where id=?", OffsetDateTime.class, accessId))
                .isAfter(oldAccessUpdatedAt);
        assertThat(jdbc.queryForMap(
                        "select warehouse_id, version from user_warehouse_access where id=?", uppercaseAccessId))
                .containsEntry("warehouse_id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
                .containsEntry("version", 3);
        assertThat(jdbc.queryForMap(
                        "select id, warehouse_id, version from auth_subject where id=?", workerId))
                .containsEntry("id", workerId)
                .containsEntry("warehouse_id", "00000000-0000-0000-0000-000000000002")
                .containsEntry("version", 5);
        assertThat(jdbc.queryForObject(
                        "select updated_at from auth_subject where id=?", OffsetDateTime.class, workerId))
                .isAfter(oldWorkerUpdatedAt);

        Map<String, String> once = authDomainDigests();
        apply("V0002__canonicalize-warehouse-identifiers/apply.sql");
        apply("V0002__canonicalize-warehouse-identifiers/verify.sql");
        assertThat(authDomainDigests()).containsExactlyInAnyOrderEntriesOf(once);
    }

    @Test
    void warehouseIdentifierReleaseAbortsAliasCanonicalCollisionBeforeAnyChange() throws Exception {
        apply("V0001__adopt-auth-schema/apply.sql");
        UUID userId = UUID.fromString("10000000-0000-0000-0000-000000000011");
        insertUser(userId, "collision.user");
        insertAccess(UUID.fromString("20000000-0000-0000-0000-000000000011"), userId, "spb", 0, true);
        insertAccess(
                UUID.fromString("20000000-0000-0000-0000-000000000012"),
                userId,
                "00000000-0000-0000-0000-000000000001",
                0,
                false);
        Map<String, String> before = authDomainDigests();

        applyExpectFailure("V0002__canonicalize-warehouse-identifiers/apply.sql");

        assertThat(authDomainDigests()).containsExactlyInAnyOrderEntriesOf(before);
    }

    @Test
    void warehouseIdentifierReleaseAbortsUnmappedOrWhitespaceIdentifierBeforeAnyChange() throws Exception {
        apply("V0001__adopt-auth-schema/apply.sql");
        UUID userId = UUID.fromString("10000000-0000-0000-0000-000000000021");
        insertUser(userId, "unmapped.user");
        insertAccess(UUID.fromString("20000000-0000-0000-0000-000000000021"), userId, " spb ", 0, true);
        Map<String, String> before = authDomainDigests();

        applyExpectFailure("V0002__canonicalize-warehouse-identifiers/apply.sql");

        assertThat(authDomainDigests()).containsExactlyInAnyOrderEntriesOf(before);
    }

    @Test
    void warehouseIdentifierReleaseAbortsUnknownWorkerIdentifierBeforeAnyChange() throws Exception {
        apply("V0001__adopt-auth-schema/apply.sql");
        insertWorker(
                UUID.fromString("10000000-0000-0000-0000-000000000022"),
                "unknown.worker",
                "warehouse-unknown",
                0);
        Map<String, String> before = authDomainDigests();

        applyExpectFailure("V0002__canonicalize-warehouse-identifiers/apply.sql");

        assertThat(authDomainDigests()).containsExactlyInAnyOrderEntriesOf(before);
    }

    @Test
    void warehouseIdentifierReleaseAbortsVersionOverflowBeforeAnyChange() throws Exception {
        apply("V0001__adopt-auth-schema/apply.sql");
        UUID userId = UUID.fromString("10000000-0000-0000-0000-000000000031");
        insertUser(userId, "overflow.user");
        insertAccess(
                UUID.fromString("20000000-0000-0000-0000-000000000031"),
                userId,
                "msk",
                Integer.MAX_VALUE,
                true);
        Map<String, String> before = authDomainDigests();

        applyExpectFailure("V0002__canonicalize-warehouse-identifiers/apply.sql");

        assertThat(authDomainDigests()).containsExactlyInAnyOrderEntriesOf(before);
    }

    @Test
    void releaseAdoptsPreviousSchemaWithoutChangingDataAndJdbcRowsRemainReadable() throws Exception {
        apply("schema.sql");
        createHistoricalLiquibaseEvidence();
        var clients = new JdbcRegisteredClientRepository(jdbc);
        List<RegisteredClient> seededClients = seedClients(clients);
        var authorizations = new JdbcOAuth2AuthorizationService(jdbc, clients);
        List<String> authorizationIds = seedAuthorizations(seededClients.getFirst(), authorizations);
        var consents = new JdbcOAuth2AuthorizationConsentService(jdbc, clients);

        Map<String, String> before = contentDigests();
        apply("V0001__adopt-auth-schema/apply.sql");
        apply("V0001__adopt-auth-schema/verify.sql");
        Map<String, String> after = contentDigests();

        assertThat(after).containsExactlyInAnyOrderEntriesOf(before);
        assertThat(seededClients).allSatisfy(client ->
                assertThat(clients.findByClientId(client.getClientId())).isNotNull());
        assertThat(authorizationIds).allSatisfy(id ->
                assertThat(authorizations.findById(id)).isNotNull());

        OAuth2AuthorizationConsent consent = OAuth2AuthorizationConsent
                .withId(seededClients.getFirst().getId(), "admin")
                .authority(new SimpleGrantedAuthority("SCOPE_rwms.read"))
                .build();
        consents.save(consent);
        assertThat(consents.findById(seededClients.getFirst().getId(), "admin")).isNotNull();
        consents.remove(consent);
    }

    private List<RegisteredClient> seedClients(JdbcRegisteredClientRepository repository) {
        List<RegisteredClient> clients = List.of(
                publicClient("rwms-panel", "rwms.read"),
                publicClient("rwms-worker-android", "worker.tasks"),
                serviceClient("task-board-service", "worker-credentials.manage"),
                serviceClient("auth-service", "warehouse.read"));
        clients.forEach(repository::save);
        return clients;
    }

    private RegisteredClient publicClient(String clientId, String scope) {
        return RegisteredClient.withId(clientId + "-id")
                .clientId(clientId)
                .clientName(clientId)
                .clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://example.test/callback")
                .scope(scope)
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder().build())
                .build();
    }

    private RegisteredClient serviceClient(String clientId, String scope) {
        return RegisteredClient.withId(clientId + "-id")
                .clientId(clientId)
                .clientSecret("{noop}fixture-secret-" + clientId)
                .clientName(clientId)
                .clientAuthenticationMethod(
                        org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(scope)
                .clientSettings(ClientSettings.builder().build())
                .tokenSettings(TokenSettings.builder().build())
                .build();
    }

    private List<String> seedAuthorizations(
            RegisteredClient client, JdbcOAuth2AuthorizationService authorizations) {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 19; index++) {
            Instant issuedAt = Instant.parse("2026-07-12T00:00:00Z").plusSeconds(index);
            OAuth2AccessToken token = new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    "fixture-access-token-" + index,
                    issuedAt,
                    issuedAt.plusSeconds(300),
                    Set.of("rwms.read"));
            OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                    .id("fixture-authorization-" + index)
                    .principalName("fixture-user-" + index)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizedScopes(Set.of("rwms.read"))
                    .attribute("fixture", "value-" + index)
                    .accessToken(token)
                    .build();
            authorizations.save(authorization);
            ids.add(authorization.getId());
        }
        return ids;
    }

    private void createHistoricalLiquibaseEvidence() {
        jdbc.execute("create table databasechangelog (id varchar(255) not null, author varchar(255) not null)");
        jdbc.update("insert into databasechangelog(id, author) values (?, ?)", "001-auth-subjects", "fixture");
        jdbc.execute("create table databasechangeloglock (id integer primary key, locked boolean not null)");
        jdbc.update("insert into databasechangeloglock(id, locked) values (1, false)");
    }

    private void insertUser(UUID id, String username) {
        jdbc.update(
                "insert into auth_subject(id, version, principal_type, username, password_hash, global_role, active, created_at, updated_at) "
                        + "values (?, 0, 'USER', ?, '{noop}password', 'VIEWER', true, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
                id,
                username);
    }

    private void insertWorker(UUID id, String username, String warehouseId, int version) {
        jdbc.update(
                "insert into auth_subject(id, version, principal_type, username, password_hash, external_worker_id, warehouse_id, active, created_at, updated_at) "
                        + "values (?, ?, 'WORKER', ?, '{noop}password', ?, ?, true, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
                id,
                version,
                username,
                "external-" + username,
                warehouseId);
    }

    private void insertAccess(
            UUID id, UUID userId, String warehouseId, int version, boolean active) {
        jdbc.update(
                "insert into user_warehouse_access(id, version, user_id, warehouse_id, access_level, active, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'MANAGE', ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
                id,
                version,
                userId,
                warehouseId,
                active);
    }

    private Map<String, String> authDomainDigests() {
        return Map.of(
                "auth_subject", digest("auth_subject"),
                "user_warehouse_access", digest("user_warehouse_access"));
    }

    private Map<String, String> contentDigests() {
        return Map.of(
                "auth_subject", digest("auth_subject"),
                "user_warehouse_access", digest("user_warehouse_access"),
                "oauth2_registered_client", digest("oauth2_registered_client"),
                "oauth2_authorization", digest("oauth2_authorization"),
                "oauth2_authorization_consent", digest("oauth2_authorization_consent"),
                "databasechangelog", digest("databasechangelog"),
                "databasechangeloglock", digest("databasechangeloglock"));
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

    private void apply(String resource) throws Exception {
        String remote = "/tmp/" + resource.replace('/', '-');
        postgres.copyFileToContainer(resource(resource), remote);
        org.testcontainers.containers.Container.ExecResult result = postgres.execInContainer(
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
                .withFailMessage("%s failed:%n%s%n%s", resource, result.getStdout(), result.getStderr())
                .isZero();
    }

    private void applyExpectFailure(String resource) throws Exception {
        String remote = "/tmp/expected-failure-" + resource.replace('/', '-');
        postgres.copyFileToContainer(resource(resource), remote);
        org.testcontainers.containers.Container.ExecResult result = postgres.execInContainer(
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
                .withFailMessage("%s unexpectedly succeeded:%n%s%n%s", resource, result.getStdout(), result.getStderr())
                .isNotZero();
    }

    private MountableFile resource(String path) throws URISyntaxException {
        var url = AuthSchemaReleaseIntegrationTest.class.getClassLoader().getResource(path);
        if (url == null) {
            throw new IllegalStateException("Missing schema test resource: " + path);
        }
        return MountableFile.forHostPath(java.nio.file.Path.of(url.toURI()));
    }
}
