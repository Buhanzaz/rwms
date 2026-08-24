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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Proves the planner receives only a narrowly scoped service token from the real token endpoint. */
@SpringBootTest(properties = {
    "LOGISTICS_PLANNER_CLIENT_ENABLED=true",
    "LOGISTICS_PLANNER_CLIENT_SECRET=planner-test-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "planner-client"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthLogisticsPlannerClientIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Test
    void mintsOnlyTheExactPlanningServiceToken() throws Exception {
        String body = tokenRequest("logistics.planning")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("logistics.planning"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var claims = SignedJWT.parse(JsonPath.read(body, "$.access_token")).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("logistics-planner");
        assertThat(claims.getStringClaim("client_id")).isEqualTo("logistics-planner");
        assertThat(claims.getStringClaim("principal_type")).isEqualTo("SERVICE");
        assertThat(claims.getAudience()).containsExactly("rwms-services");
        assertThat(claims.getStringListClaim("scope")).containsExactly("logistics.planning");
        assertThat(claims.getClaims())
                .doesNotContainKeys(
                        "global_role",
                        "preferred_username",
                        "worker_id",
                        "warehouse_id",
                        "warehouse_access");
    }

    @Test
    void rejectsMissingCombinedForeignAndIdentityOverrideRequests() throws Exception {
        assertInvalidScope(tokenRequest(null));
        assertInvalidScope(tokenRequest("logistics.planning warehouse.read"));
        assertInvalidScope(tokenRequest("warehouse.logistics"));
        assertInvalidScope(tokenRequest("rwms.write"));
        assertInvalidRequest(tokenRequestBuilder("logistics.planning").param("principal_type", "USER"));
        assertInvalidRequest(tokenRequestBuilder("logistics.planning").param("sub", "logistics-service"));
        assertInvalidRequest(tokenRequestBuilder("logistics.planning").param("audience", "other-audience"));
    }

    @Test
    void acceptsExactOptionalOverridesWithoutUsingThemAsClaimAuthority() throws Exception {
        String body = mvc.perform(tokenRequestBuilder("logistics.planning")
                        .param("principal_type", "SERVICE")
                        .param("sub", "logistics-planner")
                        .param("subject", "logistics-planner")
                        .param("client_id", "logistics-planner")
                        .param("audience", "rwms-services")
                        .param("resource", "rwms-services"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var claims = SignedJWT.parse(JsonPath.read(body, "$.access_token")).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("logistics-planner");
        assertThat(claims.getAudience()).containsExactly("rwms-services");
    }

    private ResultActions tokenRequest(String scope) throws Exception {
        return mvc.perform(tokenRequestBuilder(scope));
    }

    private MockHttpServletRequestBuilder tokenRequestBuilder(String scope) {
        MockHttpServletRequestBuilder request = post("/oauth2/token")
                .with(httpBasic("logistics-planner", "planner-test-secret"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials");
        if (scope != null) {
            request.param("scope", scope);
        }
        return request;
    }

    private void assertInvalidScope(ResultActions request) throws Exception {
        request.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    private void assertInvalidRequest(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }
}
