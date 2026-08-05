package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthTaskBoardServiceClientIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired MockMvc mvc;

    @Test
    void mintsOnlySeparateExactServiceTokens() throws Exception {
        for (String scope : List.of(
                "worker-credentials.manage",
                "warehouse.timezone.read",
                "warehouse.lifecycle.read",
                "warehouse.lifecycle.confirm")) {
            String body = mvc.perform(token(scope))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scope").value(scope))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            var claims = SignedJWT.parse(JsonPath.read(body, "$.access_token")).getJWTClaimsSet();
            assertThat(claims.getSubject()).isEqualTo("task-board-service");
            assertThat(claims.getStringClaim("client_id")).isEqualTo("task-board-service");
            assertThat(claims.getStringClaim("principal_type")).isEqualTo("SERVICE");
            assertThat(claims.getAudience()).containsExactly("rwms-services");
            assertThat(claims.getStringListClaim("scope")).containsExactly(scope);
            assertThat(claims.getClaims())
                    .doesNotContainKeys("global_role", "warehouse_access", "worker_id");
        }
    }

    @Test
    void rejectsOmittedCombinedForeignAndIdentityOverrides() throws Exception {
        mvc.perform(token(null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
        mvc.perform(token("warehouse.timezone.read warehouse.lifecycle.read"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
        mvc.perform(token("warehouse.operation.mark"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
        mvc.perform(token("asset.internal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
        mvc.perform(token("warehouse.timezone.read").param("sub", "other-service"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(token("warehouse.timezone.read").param("audience", "other-audience"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    private MockHttpServletRequestBuilder token(String scope) {
        MockHttpServletRequestBuilder request =
                post("/oauth2/token")
                        .with(httpBasic("task-board-service", "task-board-test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials");
        if (scope != null) request.param("scope", scope);
        return request;
    }
}
