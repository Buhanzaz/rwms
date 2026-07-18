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

@SpringBootTest(properties = {
    "LOGISTICS_CLIENT_ENABLED=true",
    "LOGISTICS_CLIENT_SECRET=logistics-test-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "logistics-client"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthLogisticsServiceClientIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Test
    void mintsOnlySeparateExactScopeServiceTokens() throws Exception {
        assertExactToken("warehouse.logistics");
        assertExactToken("asset.logistics");
        assertExactToken("task-board.logistics");
        assertExactToken("maintenance.logistics");
        assertExactToken("media.logistics");
    }

    @Test
    void rejectsOmittedCombinedForeignAndForbiddenScopes() throws Exception {
        assertInvalidScope(tokenRequest(null));
        assertInvalidScope(tokenRequest("warehouse.logistics asset.logistics"));
        assertInvalidScope(tokenRequest("rwms.write"));
        assertInvalidScope(tokenRequest("warehouse.read"));
        assertInvalidScope(tokenRequest("asset.internal"));
        assertInvalidScope(tokenRequest("asset.inventory"));
        assertInvalidScope(tokenRequest("task-board.task-sync"));
        assertInvalidScope(tokenRequest("maintenance.inventory"));
        assertInvalidScope(tokenRequest("media.write"));
    }

    @Test
    void rejectsUserAndWrongSubjectClientOrAudienceOverrides() throws Exception {
        assertInvalidRequest(tokenRequestBuilder("warehouse.logistics").param("principal_type", "USER"));
        assertInvalidRequest(tokenRequestBuilder("warehouse.logistics").param("sub", "other-service"));
        assertInvalidRequest(tokenRequestBuilder("asset.logistics").param("subject", "other-service"));
        assertRejected(tokenRequestBuilder("maintenance.logistics").param("client_id", "other-service"));
        assertInvalidRequest(tokenRequestBuilder("warehouse.logistics").param("audience", "other-audience"));
        assertInvalidRequest(tokenRequestBuilder("warehouse.logistics").param("resource", "other-audience"));
        assertRejected(tokenRequestBuilder("warehouse.logistics")
                .param("client_id", "logistics-service", "logistics-service"));

        assertRejected(post("/oauth2/token")
                .with(httpBasic("other-service", "wrong-secret"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials")
                .param("scope", "warehouse.logistics"));
    }

    @Test
    void acceptsExactOptionalOverridesWithoutTrustingThemForClaims() throws Exception {
        String body = mvc.perform(tokenRequestBuilder("asset.logistics")
                        .param("principal_type", "SERVICE")
                        .param("sub", "logistics-service")
                        .param("subject", "logistics-service")
                        .param("client_id", "logistics-service")
                        .param("audience", "rwms-services")
                        .param("resource", "rwms-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("asset.logistics"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var claims = SignedJWT.parse(JsonPath.read(body, "$.access_token")).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("logistics-service");
        assertThat(claims.getStringClaim("client_id")).isEqualTo("logistics-service");
        assertThat(claims.getStringClaim("principal_type")).isEqualTo("SERVICE");
        assertThat(claims.getAudience()).containsExactly("rwms-services");
    }

    private void assertExactToken(String scope) throws Exception {
        String body = tokenRequest(scope)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value(scope))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var claims = SignedJWT.parse(JsonPath.read(body, "$.access_token")).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("logistics-service");
        assertThat(claims.getStringClaim("client_id")).isEqualTo("logistics-service");
        assertThat(claims.getStringClaim("principal_type")).isEqualTo("SERVICE");
        assertThat(claims.getAudience()).containsExactly("rwms-services");
        assertThat(claims.getStringListClaim("scope")).containsExactly(scope);
        assertThat(claims.getClaims())
                .doesNotContainKeys(
                        "global_role",
                        "preferred_username",
                        "worker_id",
                        "warehouse_id",
                        "warehouse_access");
    }

    private ResultActions tokenRequest(String scope) throws Exception {
        return mvc.perform(tokenRequestBuilder(scope));
    }

    private MockHttpServletRequestBuilder tokenRequestBuilder(String scope) {
        MockHttpServletRequestBuilder request = post("/oauth2/token")
                .with(httpBasic("logistics-service", "logistics-test-secret"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials");
        if (scope != null) {
            request.param("scope", scope);
        }
        return request;
    }

    private void assertRejected(ResultActions request) throws Exception {
        request.andExpect(status().is4xxClientError()).andExpect(jsonPath("$.access_token").doesNotExist());
    }

    private void assertRejected(MockHttpServletRequestBuilder request) throws Exception {
        assertRejected(mvc.perform(request));
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
