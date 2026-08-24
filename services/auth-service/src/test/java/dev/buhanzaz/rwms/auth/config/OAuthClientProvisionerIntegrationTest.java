package dev.buhanzaz.rwms.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class OAuthClientProvisionerIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private JdbcRegisteredClientRepository repository;
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private TransactionTemplate transactions;

    @BeforeEach
    void resetSchema() {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop schema public cascade");
        jdbc.execute("create schema public");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .outOfOrder(false)
                .load()
                .migrate();
        repository = new JdbcRegisteredClientRepository(jdbc);
        passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void identicalReconcileIsByteStableAndRotationPreservesAuthorizationUntilExplicitRevocation() throws Exception {
        OAuthClientProperties.Client revisionOne = serviceClient(true, 1, "secret-one", false);
        provisioner(revisionOne).run(null);
        var client = repository.findByClientId("fixture-service");
        var authorizations = new JdbcOAuth2AuthorizationService(jdbc, repository);
        Instant issuedAt = Instant.parse("2026-07-12T00:00:00Z");
        var token = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "fixture-token",
                issuedAt,
                issuedAt.plusSeconds(300),
                Set.of("fixture.manage"));
        var authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName("fixture-service")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .authorizedScopes(Set.of("fixture.manage"))
                .accessToken(token)
                .build();
        authorizations.save(authorization);
        var before = jdbc.queryForMap("select * from oauth2_registered_client where client_id='fixture-service'");

        provisioner(revisionOne).run(null);
        assertThat(jdbc.queryForMap("select * from oauth2_registered_client where client_id='fixture-service'"))
                .containsExactlyInAnyOrderEntriesOf(before);

        provisioner(serviceClient(true, 2, "secret-two", false)).run(null);
        var rotated = repository.findByClientId("fixture-service");
        assertThat(rotated.getId()).isEqualTo(client.getId());
        assertThat(rotated.getClientIdIssuedAt()).isEqualTo(client.getClientIdIssuedAt());
        assertThat(passwordEncoder.matches("secret-two", rotated.getClientSecret())).isTrue();
        assertThat(authorizations.findById(authorization.getId())).isNotNull();

        assertThatThrownBy(() -> provisioner(serviceClient(true, 2, "secret-two", true)).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("new client revision");
        provisioner(serviceClient(true, 3, "secret-two", true)).run(null);
        assertThat(authorizations.findById(authorization.getId())).isNull();
        provisioner(serviceClient(true, 3, "secret-two", true)).run(null);
        assertThatThrownBy(() -> provisioner(serviceClient(true, 2, "secret-two", false)).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rollback");
    }

    @Test
    void nonRefreshClientKeepsThePreRefreshSupportFingerprint() throws Exception {
        OAuthClientProperties.Client client = serviceClient(true, 1, "secret-one", false);

        provisioner(client).run(null);

        Object storedFingerprint = repository.findByClientId(client.clientId())
                .getClientSettings()
                .getSetting(OAuthClientProvisioner.FINGERPRINT_SETTING);
        assertThat(storedFingerprint).isEqualTo(legacyFingerprint(client));
        provisioner(client).run(null);
    }

    @Test
    void disabledClientKeepsIdentityAndAuditRowsButIsHiddenFailClosed() throws Exception {
        provisioner(serviceClient(true, 1, "secret-one", false)).run(null);
        var enabled = repository.findByClientId("fixture-service");
        var disabledConfig = serviceClient(false, 2, null, false);

        provisioner(disabledConfig).run(null);

        var stored = repository.findByClientId("fixture-service");
        assertThat(stored.getId()).isEqualTo(enabled.getId());
        var guarded = new ConfiguredRegisteredClientRepository(
                repository, new OAuthClientProperties(List.of(disabledConfig)));
        assertThat(guarded.findByClientId("fixture-service")).isNull();
    }

    @Test
    void missingSecretAndAudienceFailBeforeAnyMutation() {
        OAuthClientProperties.Client missingAudience = new OAuthClientProperties.Client(
                "fixture-service", "Fixture", true, 1,
                Set.of("client_secret_basic"), Set.of("client_credentials"), Set.of(), Set.of(),
                Set.of("fixture.manage"), false, Set.of(), Set.of(), Set.of(), Duration.ofMinutes(5),
                Duration.ofHours(1), true,
                "FIXTURE_SECRET", null, false);
        assertThatThrownBy(() -> provisioner(missingAudience).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audiences");
        assertThat(jdbc.queryForObject("select count(*) from oauth2_registered_client", Integer.class)).isZero();

        OAuthClientProperties.Client missingSecret = serviceClient(true, 1, null, false);
        assertThatThrownBy(() -> productionProvisioner(missingSecret).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret environment");
        assertThat(jdbc.queryForObject("select count(*) from oauth2_registered_client", Integer.class)).isZero();
    }

    @Test
    void inventoryClientRejectsContractDriftAndMissingExternalSecretBeforeMutation() {
        List<OAuthClientProperties.Client> invalidConfigurations = List.of(
                inventoryClient(
                        Set.of("none"),
                        Set.of("client_credentials"),
                        Set.of(),
                        Set.of(),
                        OAuthClientProperties.INVENTORY_SCOPES,
                        false,
                        Set.of(),
                        Set.of("rwms-services"),
                        Set.of(),
                        "INVENTORY_CLIENT_SECRET",
                        null),
                inventoryClient(
                        Set.of("client_secret_basic"),
                        Set.of("authorization_code"),
                        Set.of(),
                        Set.of(),
                        OAuthClientProperties.INVENTORY_SCOPES,
                        false,
                        Set.of(),
                        Set.of("rwms-services"),
                        Set.of(),
                        "INVENTORY_CLIENT_SECRET",
                        null),
                inventoryClient(
                        Set.of("client_secret_basic"),
                        Set.of("client_credentials"),
                        Set.of(),
                        Set.of(),
                        Set.of("warehouse.read", "asset.inventory"),
                        false,
                        Set.of(),
                        Set.of("rwms-services"),
                        Set.of(),
                        "INVENTORY_CLIENT_SECRET",
                        null),
                inventoryClient(
                        Set.of("client_secret_basic"),
                        Set.of("client_credentials"),
                        Set.of(),
                        Set.of(),
                        OAuthClientProperties.INVENTORY_SCOPES,
                        false,
                        Set.of(),
                        Set.of("other-audience"),
                        Set.of(),
                        "INVENTORY_CLIENT_SECRET",
                        null),
                inventoryClient(
                        Set.of("client_secret_basic"),
                        Set.of("client_credentials"),
                        Set.of("https://inventory.example.test/callback"),
                        Set.of(),
                        OAuthClientProperties.INVENTORY_SCOPES,
                        false,
                        Set.of(),
                        Set.of("rwms-services"),
                        Set.of(),
                        "INVENTORY_CLIENT_SECRET",
                        null),
                inventoryClient(
                        Set.of("client_secret_basic"),
                        Set.of("client_credentials"),
                        Set.of(),
                        Set.of("https://inventory.example.test/logout"),
                        OAuthClientProperties.INVENTORY_SCOPES,
                        false,
                        Set.of(),
                        Set.of("rwms-services"),
                        Set.of(),
                        "INVENTORY_CLIENT_SECRET",
                        null),
                inventoryClient(
                        Set.of("client_secret_basic"),
                        Set.of("client_credentials"),
                        Set.of(),
                        Set.of(),
                        OAuthClientProperties.INVENTORY_SCOPES,
                        false,
                        Set.of(PrincipalType.USER),
                        Set.of("rwms-services"),
                        Set.of(),
                        "INVENTORY_CLIENT_SECRET",
                        null),
                inventoryClient(
                        Set.of("client_secret_basic"),
                        Set.of("client_credentials"),
                        Set.of(),
                        Set.of(),
                        OAuthClientProperties.INVENTORY_SCOPES,
                        false,
                        Set.of(),
                        Set.of("rwms-services"),
                        Set.of("https://inventory.example.test"),
                        "INVENTORY_CLIENT_SECRET",
                        null),
                inventoryClient(
                        Set.of("client_secret_basic"),
                        Set.of("client_credentials"),
                        Set.of(),
                        Set.of(),
                        OAuthClientProperties.INVENTORY_SCOPES,
                        false,
                        Set.of(),
                        Set.of("rwms-services"),
                        Set.of(),
                        "OTHER_CLIENT_SECRET",
                        null),
                inventoryClient(
                        Set.of("client_secret_basic"),
                        Set.of("client_credentials"),
                        Set.of(),
                        Set.of(),
                        OAuthClientProperties.INVENTORY_SCOPES,
                        false,
                        Set.of(),
                        Set.of("rwms-services"),
                        Set.of(),
                        "INVENTORY_CLIENT_SECRET",
                        "repository-secret"));

        assertThat(invalidConfigurations.getLast().toString())
                .contains("clientId=inventory-service", "enabled=true", "revision=1")
                .doesNotContain("repository-secret", "INVENTORY_CLIENT_SECRET");
        invalidConfigurations.forEach(configuration -> assertThatThrownBy(() -> provisioner(configuration).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inventory-service OAuth client"));
        assertThat(jdbc.queryForObject("select count(*) from oauth2_registered_client", Integer.class)).isZero();

        assertThatThrownBy(() -> provisioner(inventoryClient(true)).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret environment")
                .hasMessageContaining("inventory-service");
        assertThat(jdbc.queryForObject("select count(*) from oauth2_registered_client", Integer.class)).isZero();
    }

    @Test
    void assetClientRejectsScopeAudienceAndSecretDriftBeforeMutation() {
        List<OAuthClientProperties.Client> invalidConfigurations = List.of(
                assetClient(
                        Set.of("warehouse.read"),
                        Set.of(OAuthClientProperties.ASSET_AUDIENCE),
                        OAuthClientProperties.ASSET_SECRET_ENVIRONMENT,
                        null),
                assetClient(
                        Set.of("warehouse.read", "media.asset-import", "asset.inventory"),
                        Set.of(OAuthClientProperties.ASSET_AUDIENCE),
                        OAuthClientProperties.ASSET_SECRET_ENVIRONMENT,
                        null),
                assetClient(
                        OAuthClientProperties.ASSET_SCOPES,
                        Set.of("other-audience"),
                        OAuthClientProperties.ASSET_SECRET_ENVIRONMENT,
                        null),
                assetClient(
                        OAuthClientProperties.ASSET_SCOPES,
                        Set.of(OAuthClientProperties.ASSET_AUDIENCE),
                        "OTHER_CLIENT_SECRET",
                        null),
                assetClient(
                        OAuthClientProperties.ASSET_SCOPES,
                        Set.of(OAuthClientProperties.ASSET_AUDIENCE),
                        OAuthClientProperties.ASSET_SECRET_ENVIRONMENT,
                        "repository-secret"));

        invalidConfigurations.forEach(configuration -> assertThatThrownBy(() -> provisioner(configuration).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("asset-service OAuth client"));
        assertThat(jdbc.queryForObject("select count(*) from oauth2_registered_client", Integer.class)).isZero();

        assertThatThrownBy(() -> provisioner(assetClient(true)).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret environment")
                .hasMessageContaining("asset-service");
        assertThat(jdbc.queryForObject("select count(*) from oauth2_registered_client", Integer.class)).isZero();
    }

    @Test
    void logisticsClientRejectsScopeAudienceAndSecretDriftBeforeMutation() {
        List<OAuthClientProperties.Client> invalidConfigurations = List.of(
                logisticsClient(
                        Set.of("warehouse.logistics", "asset.logistics"),
                        Set.of(OAuthClientProperties.LOGISTICS_AUDIENCE),
                        OAuthClientProperties.LOGISTICS_SECRET_ENVIRONMENT,
                        null),
                logisticsClient(
                        Set.of(
                                "warehouse.logistics",
                                "asset.logistics",
                                "task-board.logistics",
                                "maintenance.logistics",
                                "media.logistics",
                                "asset.internal"),
                        Set.of(OAuthClientProperties.LOGISTICS_AUDIENCE),
                        OAuthClientProperties.LOGISTICS_SECRET_ENVIRONMENT,
                        null),
                logisticsClient(
                        OAuthClientProperties.LOGISTICS_SCOPES,
                        Set.of("other-audience"),
                        OAuthClientProperties.LOGISTICS_SECRET_ENVIRONMENT,
                        null),
                logisticsClient(
                        OAuthClientProperties.LOGISTICS_SCOPES,
                        Set.of(OAuthClientProperties.LOGISTICS_AUDIENCE),
                        "OTHER_CLIENT_SECRET",
                        null),
                logisticsClient(
                        OAuthClientProperties.LOGISTICS_SCOPES,
                        Set.of(OAuthClientProperties.LOGISTICS_AUDIENCE),
                        OAuthClientProperties.LOGISTICS_SECRET_ENVIRONMENT,
                        "repository-secret"));

        invalidConfigurations.forEach(configuration -> assertThatThrownBy(() -> provisioner(configuration).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("logistics-service OAuth client"));
        assertThat(jdbc.queryForObject("select count(*) from oauth2_registered_client", Integer.class)).isZero();

        assertThatThrownBy(() -> provisioner(logisticsClient(true)).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret environment")
                .hasMessageContaining("logistics-service");
        assertThat(jdbc.queryForObject("select count(*) from oauth2_registered_client", Integer.class)).isZero();
    }

    @Test
    void logisticsPlannerRejectsEveryPrivilegeOrSecretDriftBeforeMutation() {
        List<OAuthClientProperties.Client> invalidConfigurations = List.of(
                logisticsPlannerClient(
                        Set.of("logistics.planning", "warehouse.read"),
                        Set.of(OAuthClientProperties.LOGISTICS_PLANNER_AUDIENCE),
                        OAuthClientProperties.LOGISTICS_PLANNER_SECRET_ENVIRONMENT,
                        null),
                logisticsPlannerClient(
                        OAuthClientProperties.LOGISTICS_PLANNER_SCOPES,
                        Set.of("other-audience"),
                        OAuthClientProperties.LOGISTICS_PLANNER_SECRET_ENVIRONMENT,
                        null),
                logisticsPlannerClient(
                        OAuthClientProperties.LOGISTICS_PLANNER_SCOPES,
                        Set.of(OAuthClientProperties.LOGISTICS_PLANNER_AUDIENCE),
                        "OTHER_CLIENT_SECRET",
                        null),
                logisticsPlannerClient(
                        OAuthClientProperties.LOGISTICS_PLANNER_SCOPES,
                        Set.of(OAuthClientProperties.LOGISTICS_PLANNER_AUDIENCE),
                        OAuthClientProperties.LOGISTICS_PLANNER_SECRET_ENVIRONMENT,
                        "repository-secret"));

        invalidConfigurations.forEach(configuration -> assertThatThrownBy(() -> provisioner(configuration).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("logistics-planner OAuth client"));
        assertThat(jdbc.queryForObject("select count(*) from oauth2_registered_client", Integer.class)).isZero();

        provisioner(logisticsPlannerClient(false)).run(null);
        var disabled = repository.findByClientId(OAuthClientProperties.LOGISTICS_PLANNER_CLIENT_ID);
        assertThat(disabled).isNotNull();
        assertThat(disabled.getClientSecret()).isNull();
        assertThat(disabled.getScopes()).containsExactly("logistics.planning");
    }

    @Test
    void sameRevisionRejectsConfigurationAndSecretDrift() throws Exception {
        provisioner(serviceClient(true, 1, "secret-one", false)).run(null);

        OAuthClientProperties.Client changedConfiguration = serviceClient(
                "fixture-service", "Changed fixture name", true, 1, "secret-one", false);
        assertThatThrownBy(() -> provisioner(changedConfiguration).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configuration changed without revision increment");

        assertThatThrownBy(() -> provisioner(serviceClient(true, 1, "secret-two", false)).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret changed without revision increment");
    }

    @Test
    void enablingDisabledClientRequiresRevisionIncrement() throws Exception {
        provisioner(serviceClient(false, 1, null, false)).run(null);

        assertThatThrownBy(() -> provisioner(serviceClient(true, 1, "secret-one", false)).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configuration changed without revision increment");

        provisioner(serviceClient(true, 2, "secret-one", false)).run(null);
        assertThat(repository.findByClientId("fixture-service")).isNotNull();
    }

    @Test
    void omittedManagedClientRequiresExplicitDisable() throws Exception {
        provisioner(serviceClient(true, 1, "secret-one", false)).run(null);
        OAuthClientProperties.Client replacement = serviceClient(
                "replacement-service", "Replacement Service", true, 1, "replacement-secret", false);

        assertThatThrownBy(() -> provisioner(replacement).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configure enabled=false explicitly")
                .hasMessageContaining("fixture-service");

        assertThat(repository.findByClientId("replacement-service")).isNull();
        assertThat(repository.findByClientId("fixture-service")).isNotNull();
    }

    @Test
    void brandNewClientCannotRequestAuthorizationRevocation() {
        OAuthClientProvisioner provisioner = provisioner(serviceClient(true, 1, "secret-one", true));

        assertThatThrownBy(() -> provisioner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revocation requires an existing client");
        assertThatThrownBy(() -> provisioner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revocation requires an existing client");

        assertThat(repository.findByClientId("fixture-service")).isNull();
    }

    @Test
    void postgresAdvisoryLockSerializesConcurrentFirstProvisioning() throws Exception {
        jdbc.execute("create function slow_oauth_client_insert() returns trigger language plpgsql as $$ "
                + "begin perform pg_sleep(0.5); return new; end $$");
        jdbc.execute("create trigger slow_oauth_client_insert before insert on oauth2_registered_client "
                + "for each row execute function slow_oauth_client_insert()");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                provisioner(serviceClient(true, 1, "secret-one", false)).run(null);
                return null;
            });
            var second = executor.submit(() -> {
                start.await();
                provisioner(serviceClient(true, 1, "secret-one", false)).run(null);
                return null;
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from oauth2_registered_client where client_id='fixture-service'",
                        Integer.class))
                .isEqualTo(1);
    }

    private OAuthClientProvisioner provisioner(OAuthClientProperties.Client client) {
        return new OAuthClientProvisioner(
                repository,
                passwordEncoder,
                jdbc,
                transactions,
                new MockEnvironment(),
                authProperties(true),
                new OAuthClientProperties(List.of(client)));
    }

    private OAuthClientProvisioner productionProvisioner(OAuthClientProperties.Client client) {
        return new OAuthClientProvisioner(
                repository,
                passwordEncoder,
                jdbc,
                transactions,
                new MockEnvironment(),
                authProperties(false),
                new OAuthClientProperties(List.of(client)));
    }

    private AuthProperties authProperties(boolean devDefaults) {
        return new AuthProperties(
                devDefaults ? "http://localhost:9000" : "https://auth.example.test/auth",
                devDefaults ? "http://localhost:8080" : "https://panel.example.test",
                devDefaults ? "http://localhost:8080/auth/callback" : "https://panel.example.test/auth/callback",
                devDefaults ? "http://localhost:8080/" : "https://panel.example.test/",
                devDefaults ? "http://localhost:8082" : "https://worker.example.test",
                devDefaults ? "http://localhost:8082/auth/callback" : "https://worker.example.test/auth/callback",
                devDefaults ? "http://localhost:8082/" : "https://worker.example.test/",
                devDefaults ? "http://localhost:8083" : "https://driver.example.test",
                devDefaults ? "http://localhost:8083/auth/callback" : "https://driver.example.test/auth/callback",
                devDefaults ? "http://localhost:8083/" : "https://driver.example.test/",
                devDefaults,
                "admin",
                devDefaults ? "admin" : "long-production-password",
                "",
                "",
                "rwms-auth-test");
    }

    private OAuthClientProperties.Client serviceClient(
            boolean enabled, long revision, String developmentSecret, boolean revoke) {
        return serviceClient(
                "fixture-service", "Fixture Service", enabled, revision, developmentSecret, revoke);
    }

    private OAuthClientProperties.Client serviceClient(
            String clientId,
            String clientName,
            boolean enabled,
            long revision,
            String developmentSecret,
            boolean revoke) {
        return new OAuthClientProperties.Client(
                clientId,
                clientName,
                enabled,
                revision,
                Set.of("client_secret_basic"),
                Set.of("client_credentials"),
                Set.of(),
                Set.of(),
                Set.of("fixture.manage"),
                false,
                Set.of(),
                Set.of("rwms-services"),
                Set.of(),
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                true,
                "FIXTURE_SECRET",
                developmentSecret,
                revoke);
    }

    private OAuthClientProperties.Client inventoryClient(boolean enabled) {
        return new OAuthClientProperties.Client(
                "inventory-service",
                "Inventory Service Downstream Client",
                enabled,
                1,
                Set.of("client_secret_basic"),
                Set.of("client_credentials"),
                Set.of(),
                Set.of(),
                OAuthClientProperties.INVENTORY_SCOPES,
                false,
                Set.of(),
                Set.of(OAuthClientProperties.INVENTORY_AUDIENCE),
                Set.of(),
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                true,
                OAuthClientProperties.INVENTORY_SECRET_ENVIRONMENT,
                null,
                false);
    }

    private OAuthClientProperties.Client inventoryClient(
            Set<String> authenticationMethods,
            Set<String> grantTypes,
            Set<String> redirectUris,
            Set<String> postLogoutRedirectUris,
            Set<String> scopes,
            boolean requireProofKey,
            Set<PrincipalType> allowedPrincipalTypes,
            Set<String> audiences,
            Set<String> allowedOrigins,
            String secretEnvironment,
            String developmentSecret) {
        return new OAuthClientProperties.Client(
                "inventory-service",
                "Inventory Service Downstream Client",
                true,
                1,
                authenticationMethods,
                grantTypes,
                redirectUris,
                postLogoutRedirectUris,
                scopes,
                requireProofKey,
                allowedPrincipalTypes,
                audiences,
                allowedOrigins,
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                true,
                secretEnvironment,
                developmentSecret,
                false);
    }

    private OAuthClientProperties.Client assetClient(boolean enabled) {
        return assetClient(
                enabled,
                OAuthClientProperties.ASSET_SCOPES,
                Set.of(OAuthClientProperties.ASSET_AUDIENCE),
                OAuthClientProperties.ASSET_SECRET_ENVIRONMENT,
                null);
    }

    private OAuthClientProperties.Client assetClient(
            Set<String> scopes,
            Set<String> audiences,
            String secretEnvironment,
            String developmentSecret) {
        return assetClient(true, scopes, audiences, secretEnvironment, developmentSecret);
    }

    private OAuthClientProperties.Client assetClient(
            boolean enabled,
            Set<String> scopes,
            Set<String> audiences,
            String secretEnvironment,
            String developmentSecret) {
        return new OAuthClientProperties.Client(
                OAuthClientProperties.ASSET_CLIENT_ID,
                "Asset Service Warehouse Registry Client",
                enabled,
                2,
                Set.of("client_secret_basic"),
                Set.of("client_credentials"),
                Set.of(),
                Set.of(),
                scopes,
                false,
                Set.of(),
                audiences,
                Set.of(),
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                true,
                secretEnvironment,
                developmentSecret,
                false);
    }

    private OAuthClientProperties.Client logisticsClient(boolean enabled) {
        return logisticsClient(
                enabled,
                OAuthClientProperties.LOGISTICS_SCOPES,
                Set.of(OAuthClientProperties.LOGISTICS_AUDIENCE),
                OAuthClientProperties.LOGISTICS_SECRET_ENVIRONMENT,
                null);
    }

    private OAuthClientProperties.Client logisticsClient(
            Set<String> scopes,
            Set<String> audiences,
            String secretEnvironment,
            String developmentSecret) {
        return logisticsClient(true, scopes, audiences, secretEnvironment, developmentSecret);
    }

    private OAuthClientProperties.Client logisticsClient(
            boolean enabled,
            Set<String> scopes,
            Set<String> audiences,
            String secretEnvironment,
            String developmentSecret) {
        return new OAuthClientProperties.Client(
                OAuthClientProperties.LOGISTICS_CLIENT_ID,
                "Logistics Service Downstream Client",
                enabled,
                1,
                Set.of("client_secret_basic"),
                Set.of("client_credentials"),
                Set.of(),
                Set.of(),
                scopes,
                false,
                Set.of(),
                audiences,
                Set.of(),
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                true,
                secretEnvironment,
                developmentSecret,
                false);
    }

    private OAuthClientProperties.Client logisticsPlannerClient(boolean enabled) {
        return logisticsPlannerClient(
                enabled,
                OAuthClientProperties.LOGISTICS_PLANNER_SCOPES,
                Set.of(OAuthClientProperties.LOGISTICS_PLANNER_AUDIENCE),
                OAuthClientProperties.LOGISTICS_PLANNER_SECRET_ENVIRONMENT,
                null);
    }

    private OAuthClientProperties.Client logisticsPlannerClient(
            Set<String> scopes,
            Set<String> audiences,
            String secretEnvironment,
            String developmentSecret) {
        return logisticsPlannerClient(true, scopes, audiences, secretEnvironment, developmentSecret);
    }

    private OAuthClientProperties.Client logisticsPlannerClient(
            boolean enabled,
            Set<String> scopes,
            Set<String> audiences,
            String secretEnvironment,
            String developmentSecret) {
        return new OAuthClientProperties.Client(
                OAuthClientProperties.LOGISTICS_PLANNER_CLIENT_ID,
                "Logistics Planner Integration Client",
                enabled,
                1,
                Set.of("client_secret_basic"),
                Set.of("client_credentials"),
                Set.of(),
                Set.of(),
                scopes,
                false,
                Set.of(),
                audiences,
                Set.of(),
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                true,
                secretEnvironment,
                developmentSecret,
                false);
    }

    private String legacyFingerprint(OAuthClientProperties.Client client) throws Exception {
        String canonical = String.join(
                "|",
                client.clientId(),
                client.clientName(),
                Boolean.toString(client.enabled()),
                sorted(client.authenticationMethods()),
                sorted(client.grantTypes()),
                sorted(client.redirectUris()),
                sorted(client.postLogoutRedirectUris()),
                sorted(client.scopes()),
                sorted(client.allowedPrincipalTypes().stream()
                        .map(Enum::name)
                        .collect(java.util.stream.Collectors.toSet())),
                sorted(client.audiences()),
                sorted(client.allowedOrigins()),
                Boolean.toString(client.requireProofKey()),
                client.accessTokenTtl().toString(),
                Objects.toString(client.secretEnvironment(), ""));
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private String sorted(Set<String> values) {
        return values.stream().sorted(Comparator.naturalOrder()).collect(java.util.stream.Collectors.joining(","));
    }
}
