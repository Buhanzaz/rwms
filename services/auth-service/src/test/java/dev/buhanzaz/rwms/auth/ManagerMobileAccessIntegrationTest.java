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
import dev.buhanzaz.rwms.auth.api.UpdateUserRequest;
import dev.buhanzaz.rwms.auth.api.WarehouseAccessRequest;
import dev.buhanzaz.rwms.auth.api.WorkerCredentialRequest;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.domain.WarehouseAccessLevel;
import dev.buhanzaz.rwms.auth.service.UserAdministrationService;
import dev.buhanzaz.rwms.auth.service.WorkerCredentialService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
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
class ManagerMobileAccessIntegrationTest {

    private static final String MANAGER_CLIENT_ID = "rwms-manager-android";
    private static final String MANAGER_REDIRECT_URI = "http://localhost:8080/auth/manager/callback";
    private static final String MANAGER_SCOPE =
            "openid profile offline_access rwms.read rwms.write warehouse.read";
    private static final String MANAGER_CODE_VERIFIER =
            "rwms-manager-code-verifier-000000000000000000000000000000000000000";
    private static final String USER_PASSWORD = "manager-mobile-password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAdministrationService users;

    @Autowired
    WorkerCredentialService workerCredentials;

    @Autowired
    JwtDecoder jwtDecoder;

    @ParameterizedTest
    @EnumSource(
            value = UserGlobalRole.class,
            names = {"SYSTEM_ADMIN", "WMS_ADMIN", "WAREHOUSE_MANAGER"})
    void managerAndroidIssuesTokensOnlyToEligibleMobileUsers(UserGlobalRole role) throws Exception {
        AdminUserResponse user = createUser("manager.allowed." + role.name().toLowerCase(), role, true);

        OAuthTokens tokens = authorizeManager(user.username(), USER_PASSWORD, status().isOk());

        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(jwtDecoder.decode(tokens.accessToken()).getClaimAsString("principal_type"))
                .isEqualTo("USER");
        assertThat(jwtDecoder.decode(tokens.accessToken()).getClaimAsString("global_role"))
                .isEqualTo(role.name());
    }

    @ParameterizedTest
    @MethodSource("rolesOutsideManagerMobilePolicy")
    void managerAndroidRejectsUserRolesOutsideMobilePolicy(UserGlobalRole role) throws Exception {
        AdminUserResponse user = createUser("manager.denied." + role.name().toLowerCase(), role, false);

        authorizeManager(user.username(), USER_PASSWORD, status().isBadRequest());
    }

    @Test
    void managerAndroidRejectsWorkerPrincipal() throws Exception {
        String username = "manager.denied.worker";
        workerCredentials.configure(
                UUID.randomUUID().toString(),
                new WorkerCredentialRequest("spb", username, USER_PASSWORD));

        authorizeManager(username, USER_PASSWORD, status().isBadRequest());
    }

    @Test
    void managerAndroidRejectsEligibleUserWithoutMobileAccess() throws Exception {
        AdminUserResponse user = createUser(
                "manager.denied.mobile-flag", UserGlobalRole.WAREHOUSE_MANAGER, false);

        authorizeManager(user.username(), USER_PASSWORD, status().isBadRequest());
    }

    @Test
    void removingMobileAccessRevokesExistingManagerRefreshToken() throws Exception {
        AdminUserResponse user = createUser(
                "manager.revoked.mobile-flag", UserGlobalRole.WAREHOUSE_MANAGER, true);
        OAuthTokens initial = authorizeManager(user.username(), USER_PASSWORD, status().isOk());

        AdminUserResponse updated = users.update(
                user.id(),
                new UpdateUserRequest(
                        user.version(),
                        user.username(),
                        user.firstName(),
                        user.lastName(),
                        user.email(),
                        user.timeZoneId(),
                        user.active(),
                        user.globalRole(),
                        false),
                adminAuthentication());

        assertThat(updated.mobileAppAccess()).isFalse();
        refreshManager(initial.refreshToken(), status().isBadRequest());
    }

    @Test
    void managerAndroidRefreshesUserWithWarehouseAccess() throws Exception {
        AdminUserResponse user = createUser(
                "manager.warehouse-refresh",
                UserGlobalRole.WAREHOUSE_MANAGER,
                true,
                List.of(new WarehouseAccessRequest("spb", WarehouseAccessLevel.MANAGE, null, true)));

        OAuthTokens initial = authorizeManager(user.username(), USER_PASSWORD, status().isOk());
        OAuthTokens refreshed = refreshManager(initial.refreshToken(), status().isOk());

        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(refreshed.refreshToken()).isNotBlank();
    }

    private static Stream<Arguments> rolesOutsideManagerMobilePolicy() {
        return Stream.of(
                Arguments.of(UserGlobalRole.RENTAL_MANAGER),
                Arguments.of(UserGlobalRole.CUSTOMER),
                Arguments.of(UserGlobalRole.VIEWER));
    }

    private AdminUserResponse createUser(String username, UserGlobalRole role, boolean mobileAppAccess) {
        return createUser(username, role, mobileAppAccess, List.of());
    }

    private AdminUserResponse createUser(
            String username,
            UserGlobalRole role,
            boolean mobileAppAccess,
            List<WarehouseAccessRequest> warehouseAccesses) {
        return users.create(
                new CreateUserRequest(
                        username,
                        USER_PASSWORD,
                        null,
                        null,
                        null,
                        null,
                        role,
                        true,
                        mobileAppAccess,
                        warehouseAccesses),
                adminAuthentication());
    }

    private OAuthTokens authorizeManager(String username, String password, ResultMatcher expectedStatus)
            throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(formLogin().user(username).password(password))
                .andExpect(authenticated())
                .andReturn()
                .getRequest()
                .getSession(false);
        String challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256")
                        .digest(MANAGER_CODE_VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        String location = mvc.perform(get("/oauth2/authorize")
                        .session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", MANAGER_CLIENT_ID)
                        .queryParam("redirect_uri", MANAGER_REDIRECT_URI)
                        .queryParam("scope", MANAGER_SCOPE)
                        .queryParam("state", UUID.randomUUID().toString())
                        .queryParam("nonce", UUID.randomUUID().toString())
                        .queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getHeader("Location");
        String code = UriComponentsBuilder.fromUriString(location)
                .build()
                .getQueryParams()
                .getFirst("code");
        assertThat(code).isNotBlank();

        var result = mvc.perform(post("/oauth2/token")
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("client_id", MANAGER_CLIENT_ID)
                        .param("code", code)
                        .param("redirect_uri", MANAGER_REDIRECT_URI)
                        .param("code_verifier", MANAGER_CODE_VERIFIER))
                .andExpect(expectedStatus)
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.error"))
                    .isEqualTo("access_denied");
            return null;
        }
        return oauthTokens(result.getResponse().getContentAsString());
    }

    private OAuthTokens refreshManager(String refreshToken, ResultMatcher expectedStatus) throws Exception {
        var result = mvc.perform(post("/oauth2/token")
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("client_id", MANAGER_CLIENT_ID)
                        .param("refresh_token", refreshToken))
                .andExpect(expectedStatus)
                .andReturn();
        if (result.getResponse().getStatus() == 200) {
            return oauthTokens(result.getResponse().getContentAsString());
        }
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.error"))
                .isEqualTo("invalid_grant");
        return null;
    }

    private OAuthTokens oauthTokens(String body) {
        return new OAuthTokens(
                JsonPath.read(body, "$.access_token"),
                JsonPath.read(body, "$.refresh_token"));
    }

    private UsernamePasswordAuthenticationToken adminAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated("admin", "", List.of());
    }

    private record OAuthTokens(String accessToken, String refreshToken) {}
}
