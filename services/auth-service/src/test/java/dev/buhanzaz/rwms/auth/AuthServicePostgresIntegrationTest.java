package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import dev.buhanzaz.rwms.auth.config.OAuthClientProvisioner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthServicePostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RegisteredClientRepository clients;

    @Autowired
    OAuth2AuthorizationService authorizations;

    @Autowired
    OAuth2AuthorizationConsentService consents;

    @Autowired
    OAuthClientProvisioner provisioner;

    @Test
    void reviewedBaselineJpaAndOAuthJdbcServicesWorkOnPostgres() {
        assertThat(jdbc.queryForObject("select current_setting('server_version_num')::int", Integer.class))
                .isGreaterThanOrEqualTo(170000);
        assertThat(jdbc.queryForObject(
                        "select count(*) from pg_indexes where indexname = 'uk_auth_subject_username_ci'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from auth_subject", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from oauth2_registered_client", Integer.class)).isEqualTo(8);
        assertThat(clients.findByClientId("rwms-driver-android"))
                .isNotNull()
                .satisfies(driver -> {
                    assertThat(driver.getScopes())
                            .contains("openid", "profile", "offline_access", "driver.tasks")
                            .doesNotContain("worker.tasks");
                    assertThat(driver.getClientSettings().isRequireProofKey()).isTrue();
                });
        assertThat(jdbc.queryForMap(
                        "select client_authentication_methods, authorization_grant_types, scopes, client_settings "
                                + "from oauth2_registered_client where client_id = ?",
                        "maintenance-service"))
                .satisfies(row -> {
                    assertThat(row.get("client_authentication_methods")).isEqualTo("client_secret_basic");
                    assertThat(row.get("authorization_grant_types")).isEqualTo("client_credentials");
                    assertThat(String.valueOf(row.get("scopes")))
                            .contains(
                                    "asset.maintenance",
                                    "task-board.task-sync",
                                    "queue-registry.write",
                                    "media.maintenance",
                                    "logistics.maintenance")
                            .doesNotContain("asset.internal", "rwms.write", "warehouse.read");
                    assertThat(String.valueOf(row.get("client_settings")))
                            .contains("\"rwms.client.enabled\":false");
                });

        var client = clients.findByClientId("task-board-service");
        Instant issuedAt = Instant.now();
        var accessToken = new OAuth2AccessToken(
                TokenType.BEARER,
                "postgres-integration-access-token",
                issuedAt,
                issuedAt.plusSeconds(60),
                Set.of("worker-credentials.manage"));
        var authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName("task-board-service")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .authorizedScopes(Set.of("worker-credentials.manage"))
                .accessToken(accessToken)
                .build();
        authorizations.save(authorization);
        assertThat(authorizations.findById(authorization.getId())).isNotNull();

        var consent = OAuth2AuthorizationConsent.withId(client.getId(), "task-board-service")
                .authority(new SimpleGrantedAuthority("SCOPE_worker-credentials.manage"))
                .build();
        consents.save(consent);
        assertThat(consents.findById(client.getId(), "task-board-service")).isNotNull();

        consents.remove(consent);
        authorizations.remove(authorization);
        assertThat(authorizations.findById(authorization.getId())).isNull();
    }

    @Test
    void identicalClientProvisioningIsByteStableAndPreservesAuthorizations() throws Exception {
        var client = clients.findByClientId("task-board-service");
        Instant issuedAt = Instant.now();
        var token = new OAuth2AccessToken(
                TokenType.BEARER,
                "idempotency-token",
                issuedAt,
                issuedAt.plusSeconds(60),
                Set.of("worker-credentials.manage"));
        var authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName("task-board-service")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .authorizedScopes(Set.of("worker-credentials.manage"))
                .accessToken(token)
                .build();
        authorizations.save(authorization);
        var before = jdbc.queryForMap(
                "select * from oauth2_registered_client where client_id = ?", "task-board-service");

        provisioner.run(null);

        var after = jdbc.queryForMap(
                "select * from oauth2_registered_client where client_id = ?", "task-board-service");
        assertThat(after).containsExactlyInAnyOrderEntriesOf(before);
        assertThat(authorizations.findById(authorization.getId())).isNotNull();
        authorizations.remove(authorization);
    }
}
