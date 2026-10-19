package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.buhanzaz.rwms.auth.api.UpdateUserRequest;
import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import dev.buhanzaz.rwms.auth.service.UserAdministrationService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies customer registration, event persistence, and the isolated native OAuth boundary against
 * the real auth PostgreSQL schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CustomerRegistrationIntegrationTest {

    private static final String CUSTOMER_CLIENT_ID = "rwms-customer-android";
    private static final String CUSTOMER_REDIRECT_URI = "https://localhost/auth/customer/callback";
    private static final String CUSTOMER_SCOPE = "openid profile offline_access customer.rental";
    private static final String CUSTOMER_PASSWORD = "customer-password";
    private static final String CODE_VERIFIER =
            "rwms-customer-code-verifier-000000000000000000000000000000000000";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthSubjectRepository subjects;

    @MockitoSpyBean
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RegisteredClientRepository clients;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    UserAdministrationService users;

    @Autowired
    JWKSource<SecurityContext> jwkSource;

    @Test
    void registrationRequiresCsrfAndAtomicallyCreatesOnlyCustomerAuthorizationState() throws Exception {
        String body = registrationBody(" customer.alpha ", CUSTOMER_PASSWORD, CUSTOMER_PASSWORD);
        mvc.perform(post("/api/customer/v1/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        registerWithCsrf(
                        registrationBody("customer.mismatch", CUSTOMER_PASSWORD, "different-password"),
                        status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Пароли не совпадают"));
        registerWithCsrf(
                        registrationBody("bad user", CUSTOMER_PASSWORD, CUSTOMER_PASSWORD),
                        status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        registerWithCsrf(
                        registrationBody("short.password", "short", "short"),
                        status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        registerWithCsrf(body, status().isCreated())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.subjectId").isNotEmpty())
                .andExpect(jsonPath("$.username").value("customer.alpha"));
        registerWithCsrf(
                        registrationBody("CUSTOMER.ALPHA", CUSTOMER_PASSWORD, CUSTOMER_PASSWORD),
                        status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Логин уже используется"));

        var subject = subjects.findByUsernameIgnoreCase("customer.alpha").orElseThrow();
        assertThat(subject.getPrincipalType()).isEqualTo(PrincipalType.USER);
        assertThat(subject.getGlobalRole()).isEqualTo(UserGlobalRole.CUSTOMER);
        assertThat(subject.isActive()).isTrue();
        assertThat(subject.isMobileAppAccess()).isFalse();
        assertThat(subject.isRentalAccess()).isFalse();
        assertThat(passwordEncoder.matches(CUSTOMER_PASSWORD, subject.getPasswordHash())).isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from user_warehouse_access where user_id = ?",
                        Integer.class,
                        subject.getId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from domain_event where aggregate_type = 'USER_AUTHORIZATION' "
                                + "and aggregate_id = ? and event_type = 'auth.user-authorization.created.v1' "
                                + "and actor_ref is null",
                        Integer.class,
                        subject.getId().toString()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox_event where aggregate_type = 'USER_AUTHORIZATION' "
                                + "and aggregate_id = ? and event_type = 'auth.user-authorization.created.v1'",
                        Integer.class,
                        subject.getId().toString()))
                .isEqualTo(1);
    }

    @Test
    void customerClientUsesExactPkceContractAndCannotCrossUserClientBoundary() throws Exception {
        registerWithCsrf(
                registrationBody("customer.oauth", CUSTOMER_PASSWORD, CUSTOMER_PASSWORD),
                status().isCreated());

        var customerClient = clients.findByClientId(CUSTOMER_CLIENT_ID);
        assertThat(customerClient).isNotNull();
        assertThat(customerClient.getClientAuthenticationMethods())
                .extracting(Object::toString)
                .containsExactly("none");
        assertThat(customerClient.getAuthorizationGrantTypes())
                .extracting(AuthorizationGrantType::getValue)
                .containsExactlyInAnyOrder("authorization_code", "refresh_token");
        assertThat(customerClient.getScopes())
                .containsExactlyInAnyOrder("openid", "profile", "offline_access", "customer.rental");
        assertThat(customerClient.getRedirectUris()).containsExactly(CUSTOMER_REDIRECT_URI);
        assertThat(customerClient.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(customerClient.getTokenSettings().getAccessTokenTimeToLive())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(customerClient.getTokenSettings().getRefreshTokenTimeToLive())
                .isEqualTo(Duration.ofDays(30));
        assertThat(customerClient.getTokenSettings().isReuseRefreshTokens()).isFalse();

        String customerTokenBody = authorize(
                "customer.oauth",
                CUSTOMER_PASSWORD,
                CUSTOMER_CLIENT_ID,
                CUSTOMER_REDIRECT_URI,
                CUSTOMER_SCOPE,
                status().isOk());
        var customerJwt = jwtDecoder.decode(JsonPath.read(customerTokenBody, "$.access_token"));
        assertThat(customerJwt.getClaimAsString("global_role")).isEqualTo("CUSTOMER");
        assertThat(customerJwt.getClaimAsString("client_id")).isEqualTo(CUSTOMER_CLIENT_ID);
        assertThat(customerJwt.getAudience()).containsExactly("rwms-services");
        assertThat(customerJwt.getClaimAsStringList("scope"))
                .contains("customer.rental")
                .doesNotContain("rwms.read", "rwms.write", "warehouse.read");
        String initialRefreshToken = JsonPath.read(customerTokenBody, "$.refresh_token");
        String refreshedBody = refreshCustomer(initialRefreshToken, status().isOk());
        assertThat(jwtDecoder.decode(JsonPath.read(refreshedBody, "$.access_token"))
                        .getClaimAsString("client_id"))
                .isEqualTo(CUSTOMER_CLIENT_ID);

        authorize(
                "admin",
                "admin",
                CUSTOMER_CLIENT_ID,
                CUSTOMER_REDIRECT_URI,
                CUSTOMER_SCOPE,
                status().isBadRequest());
        authorize(
                "customer.oauth",
                CUSTOMER_PASSWORD,
                "rwms-panel",
                "http://localhost:8080/auth/callback",
                "openid profile offline_access rwms.read",
                status().isBadRequest());

        MockHttpSession session = login("customer.oauth", CUSTOMER_PASSWORD);
        String location = mvc.perform(get("/oauth2/authorize")
                        .session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", CUSTOMER_CLIENT_ID)
                        .queryParam("redirect_uri", CUSTOMER_REDIRECT_URI)
                        .queryParam("scope", CUSTOMER_SCOPE)
                        .queryParam("state", "customer-plain-pkce")
                        .queryParam("code_challenge", CODE_VERIFIER)
                        .queryParam("code_challenge_method", "plain"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getHeader("Location");
        assertThat(location)
                .startsWith(CUSTOMER_REDIRECT_URI)
                .contains("error=invalid_request")
                .contains("state=customer-plain-pkce");

        var customer = subjects.findByUsernameIgnoreCase("customer.oauth").orElseThrow();
        users.update(
                customer.getId(),
                new UpdateUserRequest(
                        customer.getVersion(),
                        customer.getUsername(),
                        null,
                        null,
                        null,
                        null,
                        true,
                        UserGlobalRole.VIEWER),
                UsernamePasswordAuthenticationToken.authenticated("admin", "", List.of()));
        String rotatedRefreshToken = JsonPath.read(refreshedBody, "$.refresh_token");
        refreshCustomer(rotatedRefreshToken, status().isBadRequest());
    }

    @Test
    void canonicalPasswordLengthsAreAcceptedAndOverlimitsAreRejectedAtHttpValidation()
            throws Exception {
        String customerMaximum = "я".repeat(128);
        registerWithCsrf(
                        registrationBody("customer.max.password", customerMaximum, customerMaximum),
                        status().isCreated())
                .andExpect(jsonPath("$.username").value("customer.max.password"));
        assertThat(credentialHash("customer.max.password"))
                .startsWith("{pbkdf2@SpringSecurity_v5_8}");

        clearInvocations(passwordEncoder);
        String customerOverlimit = "я".repeat(129);
        registerWithCsrf(
                registrationBody(
                        "customer.overlimit.password", customerOverlimit, customerOverlimit),
                status().isBadRequest());
        verify(passwordEncoder, never()).encode(anyString());
        assertThat(subjects.findByUsernameIgnoreCase("customer.overlimit.password")).isEmpty();

        String adminCreateMaximum = "ю".repeat(200);
        String createResponse = mvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + signedAdminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adminCreateBody("admin.password.target", adminCreateMaximum)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID targetId = UUID.fromString(JsonPath.read(createResponse, "$.id"));
        int targetVersion = JsonPath.read(createResponse, "$.version");
        assertThat(credentialHash("admin.password.target"))
                .startsWith("{pbkdf2@SpringSecurity_v5_8}");

        clearInvocations(passwordEncoder);
        String adminCreateOverlimit = "ю".repeat(201);
        mvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + signedAdminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adminCreateBody(
                                "admin.password.overlimit", adminCreateOverlimit)))
                .andExpect(status().isBadRequest());
        verify(passwordEncoder, never()).encode(anyString());
        assertThat(subjects.findByUsernameIgnoreCase("admin.password.overlimit")).isEmpty();

        String adminMaximum = "ш".repeat(200);
        mvc.perform(put("/api/admin/users/{id}/password", targetId)
                        .header("Authorization", "Bearer " + signedAdminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordResetBody(adminMaximum, targetVersion)))
                .andExpect(status().isNoContent());
        var resetSubject = subjects.findById(targetId).orElseThrow();
        String resetHash = credentialHash("admin.password.target");
        assertThat(resetHash).startsWith("{pbkdf2@SpringSecurity_v5_8}");

        clearInvocations(passwordEncoder);
        String adminOverlimit = "ш".repeat(201);
        mvc.perform(put("/api/admin/users/{id}/password", targetId)
                        .header("Authorization", "Bearer " + signedAdminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordResetBody(adminOverlimit, resetSubject.getVersion())))
                .andExpect(status().isBadRequest());
        verify(passwordEncoder, never()).encode(anyString());
        assertThat(subjects.findById(targetId).orElseThrow().getVersion())
                .isEqualTo(resetSubject.getVersion());
        assertThat(credentialHash("admin.password.target")).isEqualTo(resetHash);
    }

    @Test
    void workerConfigureAndResetHonorPasswordMaximumBeforeEncoding() throws Exception {
        String workerId = "password-boundary-worker";
        String workerMaximum = "ш".repeat(200);
        mvc.perform(put("/api/internal/worker-credentials/{workerId}", workerId)
                        .header("Authorization", "Bearer " + signedWorkerServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerCredentialBody("worker.password.boundary", workerMaximum)))
                .andExpect(status().isOk());

        String resetMaximum = "щ".repeat(200);
        mvc.perform(post("/api/internal/worker-credentials/{workerId}/reset", workerId)
                        .header("Authorization", "Bearer " + signedWorkerServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordResetBody(resetMaximum)))
                .andExpect(status().isNoContent());
        var resetWorker = subjects.findByUsernameIgnoreCase("worker.password.boundary")
                .orElseThrow();
        String resetHash = credentialHash("worker.password.boundary");
        assertThat(resetHash).startsWith("{pbkdf2@SpringSecurity_v5_8}");

        clearInvocations(passwordEncoder);
        String overlimit = "ш".repeat(201);
        mvc.perform(put("/api/internal/worker-credentials/{workerId}", "overlimit-worker")
                        .header("Authorization", "Bearer " + signedWorkerServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerCredentialBody("worker.password.overlimit", overlimit)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(passwordEncoder);
        assertThat(subjects.findByUsernameIgnoreCase("worker.password.overlimit")).isEmpty();

        clearInvocations(passwordEncoder);
        mvc.perform(post("/api/internal/worker-credentials/{workerId}/reset", workerId)
                        .header("Authorization", "Bearer " + signedWorkerServiceJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordResetBody(overlimit)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(passwordEncoder);
        assertThat(subjects.findById(resetWorker.getId()).orElseThrow().getVersion())
                .isEqualTo(resetWorker.getVersion());
        assertThat(credentialHash("worker.password.boundary")).isEqualTo(resetHash);
    }

    @Test
    void legacyBcryptUserCanLoginAndChangeToPbkdf2Password() throws Exception {
        String username = "customer.legacy.bcrypt";
        String oldPassword = "legacy-bcrypt-password";
        registerWithCsrf(
                registrationBody(username, oldPassword, oldPassword), status().isCreated());
        var subject = subjects.findByUsernameIgnoreCase(username).orElseThrow();
        String bcryptHash = "{bcrypt}" + new BCryptPasswordEncoder().encode(oldPassword);
        jdbc.update(
                "update auth_subject_credential set password_hash=? where subject_id=?",
                bcryptHash,
                subject.getId());

        login(username, oldPassword);

        String newPassword = "я".repeat(37);
        mvc.perform(put("/api/admin/users/{id}/password", subject.getId())
                        .header("Authorization", "Bearer " + signedAdminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"%s","expectedVersion":%d}
                                """.formatted(newPassword, subject.getVersion())))
                .andExpect(status().isNoContent());

        assertThat(credentialHash(username)).startsWith("{pbkdf2@SpringSecurity_v5_8}");
        mvc.perform(formLogin().user(username).password(oldPassword))
                .andExpect(unauthenticated());
        login(username, newPassword);
    }

    private org.springframework.test.web.servlet.ResultActions registerWithCsrf(
            String body, ResultMatcher expectedStatus) throws Exception {
        var bootstrap = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(MockMvcResultMatchers.cookie().exists("XSRF-TOKEN"))
                .andReturn();
        Cookie cookie = bootstrap.getResponse().getCookie("XSRF-TOKEN");
        String token = JsonPath.read(bootstrap.getResponse().getContentAsString(), "$.token");
        return mvc.perform(post("/api/customer/v1/registrations")
                        .cookie(cookie)
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(expectedStatus);
    }

    private String authorize(
            String username,
            String password,
            String clientId,
            String redirectUri,
            String scope,
            ResultMatcher expectedStatus)
            throws Exception {
        MockHttpSession session = login(username, password);
        String challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256")
                        .digest(CODE_VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        String location = mvc.perform(get("/oauth2/authorize")
                        .session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", clientId)
                        .queryParam("redirect_uri", redirectUri)
                        .queryParam("scope", scope)
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
        var tokenResult = mvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("client_id", clientId)
                        .param("code", code)
                        .param("redirect_uri", redirectUri)
                        .param("code_verifier", CODE_VERIFIER))
                .andExpect(expectedStatus)
                .andReturn();
        if (tokenResult.getResponse().getStatus() != 200) {
            assertThat(JsonPath.<String>read(
                            tokenResult.getResponse().getContentAsString(), "$.error"))
                    .isEqualTo("access_denied");
        }
        return tokenResult.getResponse().getContentAsString();
    }

    private MockHttpSession login(String username, String password) throws Exception {
        return (MockHttpSession) mvc.perform(formLogin().user(username).password(password))
                .andExpect(authenticated())
                .andReturn()
                .getRequest()
                .getSession(false);
    }

    private String refreshCustomer(String refreshToken, ResultMatcher expectedStatus) throws Exception {
        var result = mvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("client_id", CUSTOMER_CLIENT_ID)
                        .param("refresh_token", refreshToken))
                .andExpect(expectedStatus)
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.error"))
                    .isEqualTo("access_denied");
        }
        return result.getResponse().getContentAsString();
    }

    private String registrationBody(String username, String password, String confirmation) {
        return """
                {
                  "username":"%s",
                  "password":"%s",
                  "passwordConfirmation":"%s"
                }
                """.formatted(username, password, confirmation);
    }

    private String adminCreateBody(String username, String password) {
        return """
                {
                  "username":"%s",
                  "password":"%s",
                  "globalRole":"VIEWER"
                }
                """.formatted(username, password);
    }

    private String workerCredentialBody(String username, String password) {
        return """
                {
                  "warehouseId":"00000000-0000-0000-0000-000000000001",
                  "appLogin":"%s",
                  "password":"%s"
                }
                """.formatted(username, password);
    }

    private String passwordResetBody(String password) {
        return "{\"password\":\"" + password + "\"}";
    }

    private String passwordResetBody(String password, int expectedVersion) {
        return """
                {"password":"%s","expectedVersion":%d}
                """.formatted(password, expectedVersion);
    }

    private String credentialHash(String username) {
        UUID subjectId = subjects.findByUsernameIgnoreCase(username).orElseThrow().getId();
        return jdbc.queryForObject(
                "select password_hash from auth_subject_credential where subject_id=?",
                String.class,
                subjectId);
    }

    private String signedAdminJwt() throws Exception {
        return signedJwt(
                "admin", "USER", "SYSTEM_ADMIN", "rwms-admin-web", "admin.manage");
    }

    private String signedWorkerServiceJwt() throws Exception {
        return signedJwt(
                "task-board-service",
                "SERVICE",
                null,
                "task-board-service",
                "worker-credentials.manage");
    }

    private String signedJwt(
            String username,
            String principalType,
            String globalRole,
            String clientId,
            String scope)
            throws Exception {
        RSAKey signingKey = (RSAKey) jwkSource
                .get(new JWKSelector(new JWKMatcher.Builder().privateOnly(true).build()), null)
                .stream()
                .findFirst()
                .orElseThrow();
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:9000")
                .subject(username)
                .claim("preferred_username", username)
                .claim("principal_type", principalType)
                .claim("global_role", globalRole)
                .claim("client_id", clientId)
                .claim("scope", scope)
                .audience("rwms-services")
                .issueTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(now.plusSeconds(60)))
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(signingKey.toPrivateKey()));
        return jwt.serialize();
    }
}
