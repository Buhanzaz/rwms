package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "warehouse-client"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthWarehouseServiceClientIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Test
    void activatedRevisionTwoClientMintsOnlyWarehouseReadServiceToken() throws Exception {
        String body = mvc.perform(post("/oauth2/token")
                        .with(httpBasic("auth-service", "auth-service-test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("scope", "warehouse.read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("warehouse.read"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        SignedJWT token = SignedJWT.parse(JsonPath.read(body, "$.access_token"));
        var claims = token.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("auth-service");
        assertThat(claims.getStringClaim("client_id")).isEqualTo("auth-service");
        assertThat(claims.getStringClaim("principal_type")).isEqualTo("SERVICE");
        assertThat(claims.getAudience()).containsExactly("rwms-services");
        assertThat(claims.getStringListClaim("scope")).containsExactly("warehouse.read");
        assertThat(claims.getClaims())
                .doesNotContainKeys("global_role", "preferred_username", "worker_id", "warehouse_id", "warehouse_access");

        mvc.perform(post("/oauth2/token")
                        .with(httpBasic("auth-service", "auth-service-test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("scope", "warehouse.read rwms.read"))
                .andExpect(status().isBadRequest());
    }
}
