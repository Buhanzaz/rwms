package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "asset-client"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthAssetServiceClientDisabledIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    @Qualifier("jdbcRegisteredClientRepository")
    RegisteredClientRepository storedClients;

    @Autowired
    RegisteredClientRepository configuredClients;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Test
    void defaultConfigurationPersistsNoSecretAndRemainsFailClosed() throws Exception {
        var stored = storedClients.findByClientId("asset-service");
        assertThat(stored).isNotNull();
        assertThat(stored.getClientSecret()).isNull();
        assertThat(stored.getScopes())
                .containsExactlyInAnyOrder(
                        "warehouse.read",
                        "warehouse.timezone.read",
                        "warehouse.operation.mark",
                        "warehouse.lifecycle.read",
                        "warehouse.lifecycle.confirm",
                        "media.asset",
                        "media.asset-import");
        assertThat(configuredClients.findByClientId("asset-service")).isNull();
        assertThat(jdbc.queryForObject(
                        "select client_secret is null from oauth2_registered_client where client_id='asset-service'",
                        Boolean.class))
                .isTrue();

        mvc.perform(post("/oauth2/token")
                        .with(httpBasic("asset-service", "asset-test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("scope", "media.asset-import"))
                .andExpect(status().isUnauthorized());
    }
}
