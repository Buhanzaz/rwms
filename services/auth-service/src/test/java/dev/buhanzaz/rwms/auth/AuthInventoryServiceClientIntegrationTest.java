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
    "INVENTORY_CLIENT_ENABLED=true",
    "INVENTORY_CLIENT_SECRET=inventory-test-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "inventory-client"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthInventoryServiceClientIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Test
    void mintsOnlySeparateExactScopeServiceTokens() throws Exception {
        assertExactToken("warehouse.read");
        assertExactToken("asset.inventory");
        assertExactToken("maintenance.inventory");
    }

    @Test
    void rejectsOmittedCombinedForeignAndForbiddenScopes() throws Exception {
        assertInvalidScope(tokenRequest(null));
        assertInvalidScope(tokenRequest("warehouse.read asset.inventory"));
        assertInvalidScope(tokenRequest("rwms.write"));
        assertInvalidScope(tokenRequest("asset.internal"));
        assertInvalidScope(tokenRequest("task-board.task-sync"));
        assertInvalidScope(tokenRequest("media.write"));
        assertInvalidScope(tokenRequest("logistics.write"));
    }

    @Test
    void rejectsUserAndWrongSubjectClientOrAudienceOverrides() throws Exception {
        assertInvalidRequest(tokenRequestBuilder("warehouse.read").param("principal_type", "USER"));
        assertInvalidRequest(tokenRequestBuilder("warehouse.read").param("sub", "other-service"));
        assertInvalidRequest(tokenRequestBuilder("asset.inventory").param("subject", "other-service"));
        assertRejected(tokenRequestBuilder("maintenance.inventory").param("client_id", "other-service"));
        assertInvalidRequest(tokenRequestBuilder("warehouse.read").param("audience", "other-audience"));
        assertInvalidRequest(tokenRequestBuilder("warehouse.read").param("resource", "other-audience"));
        assertRejected(tokenRequestBuilder("warehouse.read")
                .param("client_id", "inventory-service", "inventory-service"));

        assertRejected(post("/oauth2/token")
                .with(httpBasic("other-service", "wrong-secret"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials")
                .param("scope", "warehouse.read"));
    }

    @Test
    void acceptsExactOptionalOverridesWithoutTrustingThemForClaims() throws Exception {
        String body = mvc.perform(tokenRequestBuilder("asset.inventory")
                        .param("principal_type", "SERVICE")
                        .param("sub", "inventory-service")
                        .param("subject", "inventory-service")
                        .param("client_id", "inventory-service")
                        .param("audience", "rwms-services")
                        .param("resource", "rwms-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("asset.inventory"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var claims = SignedJWT.parse(JsonPath.read(body, "$.access_token")).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("inventory-service");
        assertThat(claims.getStringClaim("client_id")).isEqualTo("inventory-service");
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
        assertThat(claims.getSubject()).isEqualTo("inventory-service");
        assertThat(claims.getStringClaim("client_id")).isEqualTo("inventory-service");
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
                .with(httpBasic("inventory-service", "inventory-test-secret"))
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
