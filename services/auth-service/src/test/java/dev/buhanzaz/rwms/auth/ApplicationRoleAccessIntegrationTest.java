package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.buhanzaz.rwms.auth.api.AdminUserResponse;
import dev.buhanzaz.rwms.auth.api.CreateUserRequest;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.service.UserAdministrationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationRoleAccessIntegrationTest {

    private static final String PASSWORD = "application-role-password";
    private static final String CODE_VERIFIER =
            "application-role-code-verifier-000000000000000000000000000000000000";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAdministrationService users;

    @Autowired
    JwtDecoder jwtDecoder;

    @ParameterizedTest
    @MethodSource("rentalManagerClients")
    void rentalManagerReceivesOnlyDedicatedManagerTokens(
            String clientId, String redirectUri) throws Exception {
        AdminUserResponse manager = create("application.manager." + clientId, UserGlobalRole.RENTAL_MANAGER);

        OAuthTokens tokens = authorize(
                manager.username(),
                clientId,
                redirectUri,
                "openid profile offline_access rental.manage",
                status().isOk());

        var accessToken = jwtDecoder.decode(tokens.accessToken());
        assertThat(accessToken.getClaimAsString("global_role")).isEqualTo("RENTAL_MANAGER");
        assertThat(accessToken.getClaimAsString("client_id")).isEqualTo(clientId);
        assertThat(accessToken.getClaims()).doesNotContainKey("company_id");
        assertThat(accessToken.getClaimAsBoolean("rentalAccess")).isTrue();
        assertThat(accessToken.getClaimAsStringList("scope"))
                .contains("rental.manage")
                .doesNotContain("rwms.read", "rwms.write", "admin.manage");

        mvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void rentalManagerCannotExchangePanelOrAdministrationCodes() throws Exception {
        AdminUserResponse manager = create("application.manager.denied", UserGlobalRole.RENTAL_MANAGER);

        authorize(
                manager.username(),
                "rwms-panel",
                "http://localhost:8080/auth/callback",
                "openid profile offline_access rwms.read rwms.write warehouse.read",
                status().isBadRequest());
        authorize(
                manager.username(),
                "rwms-admin-web",
                "http://localhost:8080/admin/auth/callback",
                "openid profile offline_access admin.manage",
                status().isBadRequest());
    }

    @Test
    void administratorUsesAdminAndPanelClients() throws Exception {
        AdminUserResponse administrator = create("application.admin.allowed", UserGlobalRole.WMS_ADMIN);

        OAuthTokens tokens = authorize(
                administrator.username(),
                "rwms-admin-web",
                "http://localhost:8080/admin/auth/callback",
                "openid profile offline_access admin.manage",
                status().isOk());
        assertThat(jwtDecoder.decode(tokens.accessToken()).getClaimAsString("global_role"))
                .isEqualTo("WMS_ADMIN");
        mvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk());

        OAuthTokens panelTokens = authorize(
                administrator.username(),
                "rwms-panel",
                "http://localhost:8080/auth/callback",
                "openid profile offline_access rwms.read rwms.write warehouse.read",
                status().isOk());
        mvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + panelTokens.accessToken()))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("rentalEntitledStaffRoles")
    void rentalEntitledStaffCanUseTheDedicatedManagerApplication(UserGlobalRole role)
            throws Exception {
        AdminUserResponse staff = create("application.rental-entitled." + role.name(), role, true);

        OAuthTokens tokens = authorize(
                staff.username(),
                "rwms-rental-manager-web",
                "http://localhost:8080/manager/auth/callback",
                "openid profile offline_access rental.manage",
                status().isOk());

        var accessToken = jwtDecoder.decode(tokens.accessToken());
        assertThat(accessToken.getClaimAsString("global_role")).isEqualTo(role.name());
        assertThat(accessToken.getClaimAsBoolean("rentalAccess")).isTrue();
        assertThat(accessToken.getClaimAsStringList("scope"))
                .contains("rental.manage")
                .doesNotContain("rwms.read", "rwms.write", "admin.manage");
    }

    @Test
    void staffWithoutRentalAccessCannotUseTheDedicatedManagerApplication() throws Exception {
        AdminUserResponse viewer = create("application.rental-revoked", UserGlobalRole.VIEWER, false);

        authorize(
                viewer.username(),
                "rwms-rental-manager-web",
                "http://localhost:8080/manager/auth/callback",
                "openid profile offline_access rental.manage",
                status().isBadRequest());
    }

    @Test
    void clientParametersCannotOverrideAuthoritativeRoleClaims() throws Exception {
        AdminUserResponse manager = create("application.manager.claims", UserGlobalRole.RENTAL_MANAGER);
        Map<String, String> forgedClaims = Map.of("global_role", UserGlobalRole.SYSTEM_ADMIN.name());

        OAuthTokens tokens = authorize(
                manager.username(),
                "rwms-rental-manager-web",
                "http://localhost:8080/manager/auth/callback",
                "openid profile offline_access rental.manage",
                status().isOk(),
                forgedClaims);

        assertAuthoritativeClaims(tokens.accessToken(), manager, "rwms-rental-manager-web");
        assertAuthoritativeClaims(tokens.idToken(), manager, "rwms-rental-manager-web");
        assertAuthoritativeClaims(
                refresh("rwms-rental-manager-web", tokens.refreshToken(), forgedClaims),
                manager,
                "rwms-rental-manager-web");
    }

    private static Stream<Arguments> rentalManagerClients() {
        return Stream.of(
                Arguments.of(
                        "rwms-rental-manager-web",
                        "http://localhost:8080/manager/auth/callback"),
                Arguments.of(
                        "rwms-rental-manager-android",
                        "http://localhost:8080/auth/rental-manager/callback"));
    }

    private static Stream<UserGlobalRole> rentalEntitledStaffRoles() {
        return Stream.of(
                UserGlobalRole.SYSTEM_ADMIN,
                UserGlobalRole.WMS_ADMIN,
                UserGlobalRole.WAREHOUSE_MANAGER,
                UserGlobalRole.RENTAL_MANAGER,
                UserGlobalRole.VIEWER);
    }

    private AdminUserResponse create(String username, UserGlobalRole role) {
        return create(username, role, null);
    }

    private AdminUserResponse create(
            String username, UserGlobalRole role, Boolean rentalAccess) {
        return users.create(
                new CreateUserRequest(
                        username,
                        PASSWORD,
                        null,
                        null,
                        null,
                        null,
                        role,
                        true,
                        null,
                        rentalAccess,
                        List.of()),
                adminAuthentication());
    }

    private OAuthTokens authorize(
            String username,
            String clientId,
            String redirectUri,
            String scope,
            ResultMatcher expectedTokenStatus) throws Exception {
        return authorize(username, clientId, redirectUri, scope, expectedTokenStatus, Map.of());
    }

    private OAuthTokens authorize(
            String username,
            String clientId,
            String redirectUri,
            String scope,
            ResultMatcher expectedTokenStatus,
            Map<String, String> additionalParameters) throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(formLogin()
                        .user(username)
                        .password(PASSWORD))
                .andExpect(authenticated())
                .andReturn()
                .getRequest()
                .getSession(false);
        String challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256")
                        .digest(CODE_VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        var authorizationRequest = get("/oauth2/authorize")
                        .session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", clientId)
                        .queryParam("redirect_uri", redirectUri)
                        .queryParam("scope", scope)
                        .queryParam("state", UUID.randomUUID().toString())
                        .queryParam("nonce", UUID.randomUUID().toString())
                        .queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256");
        additionalParameters.forEach(authorizationRequest::queryParam);
        String location = mvc.perform(authorizationRequest)
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getHeader("Location");
        String code = UriComponentsBuilder.fromUriString(location)
                .build()
                .getQueryParams()
                .getFirst("code");
        assertThat(code).isNotBlank();

        var tokenRequest = post("/oauth2/token")
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("client_id", clientId)
                        .param("code", code)
                        .param("redirect_uri", redirectUri)
                        .param("code_verifier", CODE_VERIFIER);
        additionalParameters.forEach(tokenRequest::param);
        var result = mvc.perform(tokenRequest)
                .andExpect(expectedTokenStatus)
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.error"))
                    .isEqualTo("access_denied");
            return null;
        }
        return new OAuthTokens(
                JsonPath.read(result.getResponse().getContentAsString(), "$.access_token"),
                JsonPath.read(result.getResponse().getContentAsString(), "$.refresh_token"),
                JsonPath.read(result.getResponse().getContentAsString(), "$.id_token"));
    }

    private String refresh(String clientId, String refreshToken, Map<String, String> additionalParameters)
            throws Exception {
        var tokenRequest = post("/oauth2/token")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("client_id", clientId)
                .param("refresh_token", refreshToken);
        additionalParameters.forEach(tokenRequest::param);
        String body = mvc.perform(tokenRequest)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    private void assertAuthoritativeClaims(String token, AdminUserResponse manager, String clientId) {
        var claims = jwtDecoder.decode(token);
        assertThat(claims.getClaims()).doesNotContainKey("company_id");
        assertThat(claims.getClaimAsString("global_role")).isEqualTo("RENTAL_MANAGER");
        assertThat(claims.getClaimAsString("client_id")).isEqualTo(clientId);
    }

    private UsernamePasswordAuthenticationToken adminAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated("admin", "", List.of());
    }

    private record OAuthTokens(String accessToken, String refreshToken, String idToken) {}
}
