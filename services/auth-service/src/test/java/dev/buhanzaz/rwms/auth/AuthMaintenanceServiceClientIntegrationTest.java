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
@ActiveProfiles({"test", "maintenance-client"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthMaintenanceServiceClientIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Test
    void mintsSeparateExactScopeServiceTokensAndRejectsCombinedOrImplicitScopes() throws Exception {
        assertExactToken("asset.maintenance");
        assertExactToken("task-board.task-sync");

        tokenRequest("asset.maintenance task-board.task-sync")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
        tokenRequest(null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
        tokenRequest("asset.internal")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    private void assertExactToken(String scope) throws Exception {
        String body = tokenRequest(scope)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value(scope))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var claims = SignedJWT.parse(JsonPath.read(body, "$.access_token")).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("maintenance-service");
        assertThat(claims.getStringClaim("client_id")).isEqualTo("maintenance-service");
        assertThat(claims.getStringClaim("principal_type")).isEqualTo("SERVICE");
        assertThat(claims.getAudience()).containsExactly("rwms-services");
        assertThat(claims.getStringListClaim("scope")).containsExactly(scope);
        assertThat(claims.getClaims())
                .doesNotContainKeys("global_role", "preferred_username", "warehouse_access");
    }

    private org.springframework.test.web.servlet.ResultActions tokenRequest(String scope) throws Exception {
        var request = post("/oauth2/token")
                .with(httpBasic("maintenance-service", "maintenance-test-secret"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials");
        if (scope != null) {
            request.param("scope", scope);
        }
        return mvc.perform(request);
    }
}
