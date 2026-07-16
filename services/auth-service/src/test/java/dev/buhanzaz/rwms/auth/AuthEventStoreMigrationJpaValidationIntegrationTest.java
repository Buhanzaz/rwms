package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.auth.config.AuthSubjectBootstrap;
import dev.buhanzaz.rwms.auth.config.OAuthClientProvisioner;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthEventStoreMigrationJpaValidationIntegrationTest {

    private static final PostgreSQLContainer postgres = startDatabase();

    @MockitoBean
    AuthSubjectBootstrap authSubjectBootstrap;

    @MockitoBean
    OAuthClientProvisioner oauthClientProvisioner;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void cleanV3SchemaPassesJpaValidation() {
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from flyway_schema_history where version='3' and success",
                        Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject(
                        "select count(*) from information_schema.tables "
                                + "where table_schema='public' and table_name in "
                                + "('event_stream_head', 'domain_event', 'outbox_event', 'version_gap_quarantine')",
                        Integer.class))
                .isEqualTo(4);
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    private static PostgreSQLContainer startDatabase() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:17-alpine");
        container.start();
        return container;
    }
}
