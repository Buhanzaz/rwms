package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.sql.init.mode=never"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "AUTH_F0_RESTORED_JDBC_URL", matches = ".+")
class AuthF0RestoredDatabaseIntegrationTest {

    @DynamicPropertySource
    static void restoredDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("AUTH_F0_RESTORED_JDBC_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("AUTH_F0_RESTORED_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("AUTH_F0_RESTORED_PASSWORD"));
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RegisteredClientRepository clients;

    @Autowired
    OAuth2AuthorizationService authorizations;

    @Test
    void restoredF0DatabaseValidatesAndEveryOAuthJdbcRowIsReadable() {
        assertThat(jdbc.queryForObject(
                        "select count(*) from flyway_schema_history where version='2' and success",
                        Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject("select count(*) from databasechangelog", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from oauth2_registered_client", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from oauth2_authorization", Integer.class)).isEqualTo(19);
        assertThat(jdbc.queryForList(
                        "select client_id from oauth2_registered_client order by client_id", String.class))
                .allSatisfy(clientId -> assertThat(clients.findByClientId(clientId)).isNotNull());
        assertThat(jdbc.queryForList("select id from oauth2_authorization order by id", String.class))
                .allSatisfy(id -> assertThat(authorizations.findById(id)).isNotNull());
    }
}
