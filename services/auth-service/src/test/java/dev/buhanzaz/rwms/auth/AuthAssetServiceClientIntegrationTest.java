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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
    "ASSET_WAREHOUSE_CLIENT_ENABLED=true",
    "ASSET_WAREHOUSE_CLIENT_SECRET=asset-test-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "asset-client"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthAssetServiceClientIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    @Qualifier("jdbcRegisteredClientRepository")
    RegisteredClientRepository storedClients;

    @Autowired
    MockMvc mvc;

    @Test
    void provisionsOnlyWarehouseReadAndMediaAssetImportScopes() {
        assertThat(storedClients.findByClientId("asset-service").getScopes())
                .containsExactlyInAnyOrder("warehouse.read", "media.asset-import");
    }

    @Test
    void mintsOnlySeparateExactScopeServiceTokens() throws Exception {
        assertExactToken("warehouse.read");
        assertExactToken("media.asset-import");
    }

    @Test
    void rejectsOmittedCombinedForeignAndUserScopes() throws Exception {
        assertInvalidScope(tokenRequest(null));
        assertInvalidScope(tokenRequest("warehouse.read media.asset-import"));
        assertInvalidScope(tokenRequest("rwms.write"));
        assertInvalidScope(tokenRequest("asset.inventory"));
        assertInvalidScope(tokenRequest("media.maintenance"));
        assertInvalidScope(tokenRequest("media.logistics"));
        assertInvalidScope(tokenRequest("foreign.scope"));
    }

    @Test
    void rejectsUserAndWrongSubjectClientOrAudienceOverrides() throws Exception {
        assertInvalidRequest(tokenRequestBuilder("media.asset-import").param("principal_type", "USER"));
        assertInvalidRequest(tokenRequestBuilder("media.asset-import").param("sub", "other-service"));
        assertInvalidRequest(tokenRequestBuilder("media.asset-import").param("subject", "other-service"));
        assertRejected(tokenRequestBuilder("media.asset-import").param("client_id", "other-service"));
        assertInvalidRequest(tokenRequestBuilder("media.asset-import").param("audience", "other-audience"));
        assertInvalidRequest(tokenRequestBuilder("media.asset-import").param("resource", "other-audience"));
        assertRejected(tokenRequestBuilder("media.asset-import")
                .param("client_id", "asset-service", "asset-service"));
    }

    @Test
    void acceptsExactOptionalOverridesWithoutTrustingThemForClaims() throws Exception {
        String body = mvc.perform(tokenRequestBuilder("media.asset-import")
                        .param("principal_type", "SERVICE")
                        .param("sub", "asset-service")
                        .param("subject", "asset-service")
                        .param("client_id", "asset-service")
                        .param("audience", "rwms-services")
                        .param("resource", "rwms-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("media.asset-import"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var claims = SignedJWT.parse(JsonPath.read(body, "$.access_token")).getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("http://localhost:9000");
        assertThat(claims.getSubject()).isEqualTo("asset-service");
        assertThat(claims.getStringClaim("client_id")).isEqualTo("asset-service");
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
        assertThat(claims.getIssuer()).isEqualTo("http://localhost:9000");
        assertThat(claims.getSubject()).isEqualTo("asset-service");
        assertThat(claims.getStringClaim("client_id")).isEqualTo("asset-service");
        assertThat(claims.getStringClaim("principal_type")).isEqualTo("SERVICE");
        assertThat(claims.getAudience()).containsExactly("rwms-services");
        assertThat(claims.getStringListClaim("scope")).containsExactly(scope);
        assertThat(claims.getClaims())
                .doesNotContainKeys(
                        "global_role",
                        "preferred_username",
                        "rentalAccess",
                        "worker_id",
                        "warehouse_id",
                        "warehouse_access");
    }

    private ResultActions tokenRequest(String scope) throws Exception {
        return mvc.perform(tokenRequestBuilder(scope));
    }

    private MockHttpServletRequestBuilder tokenRequestBuilder(String scope) {
        MockHttpServletRequestBuilder request = post("/oauth2/token")
                .with(httpBasic("asset-service", "asset-test-secret"))
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
