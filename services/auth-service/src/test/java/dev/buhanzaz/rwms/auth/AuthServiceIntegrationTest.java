package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static dev.buhanzaz.rwms.auth.AuthOpenApiContractTest.matchesContract;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.jayway.jsonpath.JsonPath;
import dev.buhanzaz.rwms.auth.api.CreateUserRequest;
import dev.buhanzaz.rwms.auth.api.UpdateUserRequest;
import dev.buhanzaz.rwms.auth.api.WorkerCredentialRequest;
import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import dev.buhanzaz.rwms.auth.service.UserAdministrationService;
import dev.buhanzaz.rwms.auth.service.WorkerCredentialService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    int serverPort;

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthSubjectRepository subjects;

    @Autowired
    RegisteredClientRepository clients;

    @Autowired
    JdbcRegisteredClientRepository jdbcClients;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    UserAdministrationService users;

    @Autowired
    WorkerCredentialService workerCredentials;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    JWKSource<SecurityContext> jwkSource;

    @Test
    void bootstrapsAdminAndPkceClients() {
        var admin = subjects.findByUsernameIgnoreCase("admin").orElseThrow();
        assertThat(admin.getId()).isNotNull();
        assertThat(admin.getPrincipalType()).isEqualTo(PrincipalType.USER);
        assertThat(admin.getGlobalRole()).isEqualTo(UserGlobalRole.SYSTEM_ADMIN);

        var panel = clients.findByClientId("rwms-panel");
        assertThat(panel.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(panel.getClientAuthenticationMethods()).extracting(Object::toString).contains("none");
        assertThat(panel.getScopes()).contains("offline_access", "warehouse.read");
        assertThat(panel.getAuthorizationGrantTypes())
                .extracting(org.springframework.security.oauth2.core.AuthorizationGrantType::getValue)
                .containsExactlyInAnyOrder("authorization_code", "refresh_token");
        assertThat(panel.getTokenSettings().getAccessTokenTimeToLive())
                .isEqualTo(java.time.Duration.ofMinutes(5));
        assertThat(panel.getTokenSettings().getRefreshTokenTimeToLive())
                .isEqualTo(java.time.Duration.ofDays(30));
        assertThat(panel.getTokenSettings().isReuseRefreshTokens()).isFalse();
        var worker = clients.findByClientId("rwms-worker-android");
        assertThat(worker.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(worker.getScopes()).contains("openid", "profile", "offline_access", "worker.tasks");
        assertThat(worker.getRedirectUris()).containsExactly("http://localhost:8082/auth/worker/callback");
        var driver = clients.findByClientId("rwms-driver-android");
        assertThat(driver.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(driver.getScopes()).contains("openid", "profile", "offline_access", "driver.tasks");
        assertThat(driver.getRedirectUris()).containsExactly("http://localhost:8082/auth/driver/callback");
        assertThat(driver.getAuthorizationGrantTypes())
                .extracting(org.springframework.security.oauth2.core.AuthorizationGrantType::getValue)
                .containsExactlyInAnyOrder("authorization_code", "refresh_token");
        assertThat(worker.getAuthorizationGrantTypes())
                .extracting(org.springframework.security.oauth2.core.AuthorizationGrantType::getValue)
                .containsExactlyInAnyOrder("authorization_code", "refresh_token");
        assertThat(worker.getTokenSettings().getAccessTokenTimeToLive())
                .isEqualTo(java.time.Duration.ofMinutes(5));
        assertThat(worker.getTokenSettings().getRefreshTokenTimeToLive())
                .isEqualTo(java.time.Duration.ofDays(30));
        assertThat(worker.getTokenSettings().isReuseRefreshTokens()).isFalse();
        assertThat(passwordEncoder.matches(
                        "task-board-test-secret",
                        clients.findByClientId("task-board-service").getClientSecret()))
                .isTrue();
        assertThat(clients.findByClientId("auth-service")).isNull();
        var warehouseClient = jdbcClients.findByClientId("auth-service");
        assertThat(warehouseClient.getScopes()).containsExactly("warehouse.read");
        assertThat(warehouseClient.getAuthorizationGrantTypes())
                .extracting(org.springframework.security.oauth2.core.AuthorizationGrantType::getValue)
                .containsExactly("client_credentials");
        assertThat(warehouseClient.getClientAuthenticationMethods())
                .extracting(org.springframework.security.oauth2.core.ClientAuthenticationMethod::getValue)
                .containsExactly("client_secret_basic");
        assertThat(warehouseClient.getClientSecret()).isNull();
    }

    @Test
    void workerRefreshTokensRotateAndCredentialChangesRevokeAuthorizations() throws Exception {
        String workerId = UUID.randomUUID().toString();
        workerCredentials.configure(
                workerId,
                new WorkerCredentialRequest("spb", "worker.refresh", "worker-password"));

        OAuthTokens initial = authorizeWorker("worker.refresh", "worker-password");
        assertThat(initial.expiresIn()).isBetween(295, 300);
        assertThat(initial.refreshToken()).isNotBlank();
        var initialJwt = jwtDecoder.decode(initial.accessToken());
        assertThat(initialJwt.getClaimAsString("principal_type")).isEqualTo("WORKER");
        assertThat(initialJwt.getClaimAsString("worker_id")).isEqualTo(workerId);
        assertThat(initialJwt.getClaimAsString("warehouse_id"))
                .isEqualTo("00000000-0000-0000-0000-000000000001");

        OAuthTokens rotated = refreshWorker(initial.refreshToken(), status().isOk());
        assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(jwtDecoder.decode(rotated.accessToken()).getClaimAsString("worker_id"))
                .isEqualTo(workerId);

        refreshWorker(initial.refreshToken(), status().isBadRequest());

        workerCredentials.resetPassword(workerId, "rotated-password");
        refreshWorker(rotated.refreshToken(), status().isBadRequest());

        OAuthTokens afterReset = authorizeWorker("worker.refresh", "rotated-password");
        mvc.perform(post("/oauth2/revoke")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("client_id", "rwms-worker-android")
                        .param("token", afterReset.refreshToken())
                        .param("token_type_hint", "refresh_token"))
                .andExpect(status().isOk());
        refreshWorker(afterReset.refreshToken(), status().isBadRequest());

        OAuthTokens beforeDisable = authorizeWorker("worker.refresh", "rotated-password");
        workerCredentials.disable(workerId);
        refreshWorker(beforeDisable.refreshToken(), status().isBadRequest());

        workerCredentials.enable(workerId);
        OAuthTokens afterEnable = authorizeWorker("worker.refresh", "rotated-password");
        assertThat(afterEnable.refreshToken()).isNotBlank();
    }

    @Test
    void driverAuthorizationCodeIssuesWorkerIdentityWithDriverScopeOnly() throws Exception {
        String workerId = UUID.randomUUID().toString();
        workerCredentials.configure(
                workerId,
                new WorkerCredentialRequest("spb", "driver.oauth", "driver-password"));

        OAuthTokens tokens = authorizeWorkerClient(
                "driver.oauth",
                "driver-password",
                "rwms-driver-android",
                "http://localhost:8082/auth/driver/callback",
                "openid profile offline_access driver.tasks");
        var jwt = jwtDecoder.decode(tokens.accessToken());

        assertThat(jwt.getClaimAsString("principal_type")).isEqualTo("WORKER");
        assertThat(jwt.getClaimAsString("worker_id")).isEqualTo(workerId);
        assertThat(jwt.getClaimAsString("scope"))
                .contains("driver.tasks")
                .doesNotContain("worker.tasks");
    }

    @Test
    void driverCredentialReconfigurationReplacesLoginAndPassword() throws Exception {
        String workerId = UUID.randomUUID().toString();
        workerCredentials.configure(
                workerId,
                new WorkerCredentialRequest("spb", "driver.old", "driver-old-password"));
        OAuthTokens oldTokens = authorizeWorkerClient(
                "driver.old",
                "driver-old-password",
                "rwms-driver-android",
                "http://localhost:8082/auth/driver/callback",
                "openid profile offline_access driver.tasks");

        workerCredentials.configure(
                workerId,
                new WorkerCredentialRequest("spb", "driver.new", "driver-new-password"));

        refreshPublicClient(
                "rwms-driver-android", oldTokens.refreshToken(), status().isBadRequest());
        mvc.perform(formLogin().user("driver.old").password("driver-old-password"))
                .andExpect(unauthenticated());
        mvc.perform(formLogin().user("driver.new").password("driver-old-password"))
                .andExpect(unauthenticated());
        OAuthTokens tokens = authorizeWorkerClient(
                "driver.new",
                "driver-new-password",
                "rwms-driver-android",
                "http://localhost:8082/auth/driver/callback",
                "openid profile offline_access driver.tasks");

        var jwt = jwtDecoder.decode(tokens.accessToken());
        assertThat(jwt.getClaimAsString("worker_id")).isEqualTo(workerId);
        assertThat(jwt.getClaimAsString("scope"))
                .contains("driver.tasks")
                .doesNotContain("worker.tasks");
    }

    @Test
    void workerAuthorizationRequiresPkceS256() throws Exception {
        workerCredentials.configure(
                UUID.randomUUID().toString(),
                new WorkerCredentialRequest("spb", "worker.pkce", "worker-password"));
        MockHttpSession session = (MockHttpSession) mvc.perform(
                        formLogin().user("worker.pkce").password("worker-password"))
                .andExpect(authenticated())
                .andReturn()
                .getRequest()
                .getSession(false);

        String location = mvc.perform(get("/oauth2/authorize")
                        .session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "rwms-worker-android")
                        .queryParam("redirect_uri", "http://localhost:8082/auth/worker/callback")
                        .queryParam("scope", "openid profile offline_access worker.tasks")
                        .queryParam("state", "plain-pkce-state")
                        .queryParam(
                                "code_challenge",
                                "plain-code-verifier-000000000000000000000000000000")
                        .queryParam("code_challenge_method", "plain"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        assertThat(location)
                .startsWith("http://localhost:8082/auth/worker/callback")
                .contains("error=invalid_request")
                .contains("state=plain-pkce-state");
    }

    @Test
    void driverAuthorizationRequiresPkceS256() throws Exception {
        workerCredentials.configure(
                UUID.randomUUID().toString(),
                new WorkerCredentialRequest("spb", "driver.pkce", "driver-password"));
        MockHttpSession session = (MockHttpSession) mvc.perform(
                        formLogin().user("driver.pkce").password("driver-password"))
                .andExpect(authenticated())
                .andReturn()
                .getRequest()
                .getSession(false);

        String location = mvc.perform(get("/oauth2/authorize")
                        .session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "rwms-driver-android")
                        .queryParam("redirect_uri", "http://localhost:8082/auth/driver/callback")
                        .queryParam("scope", "openid profile offline_access driver.tasks")
                        .queryParam("state", "plain-driver-pkce-state")
                        .queryParam(
                                "code_challenge",
                                "plain-code-verifier-000000000000000000000000000000")
                        .queryParam("code_challenge_method", "plain"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        assertThat(location)
                .startsWith("http://localhost:8082/auth/driver/callback")
                .contains("error=invalid_request")
                .contains("state=plain-driver-pkce-state");
    }

    @Test
    void exposesOidcDiscoveryAndJwks() throws Exception {
        mvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://localhost:9000"));
        mvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"));
    }

    @Test
    void exposesOnlyInternalScrapeAndHealthProbeActuatorEndpointsWithoutAuthentication()
            throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/plain")));
        mvc.perform(get("/actuator/health/readiness")
                        .header("X-Forwarded-Prefix", "/auth"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus")
                        .header("X-Forwarded-Prefix", "/auth"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/auth/actuator/health/readiness")
                        .contextPath("/auth"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/auth/actuator/prometheus")
                        .contextPath("/auth"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/info")).andExpect(status().isForbidden());
    }

    @Test
    void eventingRecoveryRequiresSystemAdminRole() throws Exception {
        mvc.perform(post("/api/admin/eventing/outbox/{eventId}/requeue", UUID.randomUUID())
                        .with(adminAppJwt("wms.admin", "WMS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAttemptCount\":4}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/eventing/outbox/{eventId}/requeue", UUID.randomUUID())
                        .with(adminAppJwt("admin", "SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAttemptCount\":4}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/eventing/shadow/rebuild")
                        .with(adminAppJwt("wms.admin", "WMS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"" + UUID.randomUUID()
                                + "\",\"reason\":\"Unauthorized replay canary\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/eventing/shadow/rebuild")
                        .with(adminAppJwt("admin", "SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authorizationCodeTokensUseCanonicalSubjectAndOidcLogoutAcceptsIdTokenHint() throws Exception {
        AuthSubject admin = subjects.findByUsernameIgnoreCase("admin").orElseThrow();
        MockHttpSession session = (MockHttpSession) mvc.perform(formLogin().user("admin").password("admin"))
                .andExpect(authenticated())
                .andReturn().getRequest().getSession(false);
        String verifier = "rwms-logout-code-verifier-0000000000000000000000000000";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String authorizationLocation = mvc.perform(get("/oauth2/authorize")
                        .session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "rwms-panel")
                        .queryParam("redirect_uri", "http://localhost:8080/auth/callback")
                        .queryParam("scope", "openid profile")
                        .queryParam("state", "logout-flow-state")
                        .queryParam("nonce", "logout-flow-nonce")
                        .queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getHeader("Location");
        String code = UriComponentsBuilder.fromUriString(authorizationLocation)
                .build()
                .getQueryParams()
                .getFirst("code");

        String tokenBody = mvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("client_id", "rwms-panel")
                        .param("code", code)
                        .param("redirect_uri", "http://localhost:8080/auth/callback")
                        .param("code_verifier", verifier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id_token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(tokenBody, "$.access_token");
        String idToken = JsonPath.read(tokenBody, "$.id_token");
        var signedAccessToken = SignedJWT.parse(accessToken);
        var accessTokenClaims = signedAccessToken.getJWTClaimsSet();
        var idTokenClaims = SignedJWT.parse(idToken).getJWTClaimsSet();
        assertThat(signedAccessToken.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(accessTokenClaims.getSubject()).isEqualTo(admin.getId().toString());
        assertThat(accessTokenClaims.getStringClaim("preferred_username")).isEqualTo("admin");
        assertThat(accessTokenClaims.getClaims()).doesNotContainKey("company_id");
        assertThat(accessTokenClaims.getBooleanClaim("rentalAccess")).isTrue();
        assertThat(accessTokenClaims.getBooleanClaim("warehouse_access_all")).isTrue();
        assertThat(accessTokenClaims.getListClaim("warehouse_access")).isEmpty();
        assertThat(idTokenClaims.getSubject()).isEqualTo(admin.getId().toString());
        assertThat(idTokenClaims.getStringClaim("preferred_username")).isEqualTo("admin");
        assertThat(idTokenClaims.getClaims()).doesNotContainKey("company_id");
        assertThat(idTokenClaims.getBooleanClaim("rentalAccess")).isTrue();

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(admin.getId().toString()))
                .andExpect(jsonPath("$.companyId").doesNotExist())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.principalType").value("USER"))
                .andExpect(jsonPath("$.rentalAccess").value(true));
        mvc.perform(get("/api/users/actor-displays")
                        .header("Authorization", "Bearer " + accessToken)
                        .queryParam("subjectId", admin.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subjectId").value(admin.getId().toString()))
                .andExpect(jsonPath("$[0].principalType").value("USER"))
                .andExpect(jsonPath("$[0].globalRole").value("SYSTEM_ADMIN"))
                .andExpect(jsonPath("$[0].username").value("admin"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$[0].timeZoneId").doesNotExist());

        String logoutLocation = mvc.perform(get("/connect/logout")
                        .session(session)
                        .queryParam("id_token_hint", idToken)
                        .queryParam("post_logout_redirect_uri", "http://localhost:8080/")
                        .queryParam("state", "logout-complete-state"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getHeader("Location");
        assertThat(logoutLocation)
                .startsWith("http://localhost:8080/")
                .contains("state=logout-complete-state");

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + idToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actorDisplayBatchRequiresAuthenticationAndRejectsMoreThanOneHundredSubjects()
            throws Exception {
        String subjectId = subjects.findByUsernameIgnoreCase("admin").orElseThrow().getId().toString();
        mvc.perform(get("/api/users/actor-displays").queryParam("subjectId", subjectId))
                .andExpect(status().isUnauthorized());

        String[] tooManySubjectIds = IntStream.range(0, 101)
                .mapToObj(index -> UUID.randomUUID().toString())
                .toArray(String[]::new);
        mvc.perform(get("/api/users/actor-displays")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .queryParam("subjectId", tooManySubjectIds))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedPanelPkceContinuesAfterLoginAndIssuesRotatingRefreshToken()
            throws Exception {
        String verifier = "rwms-saved-request-code-verifier-000000000000000000000000";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var initialAuthorization = mvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "rwms-panel")
                        .queryParam("redirect_uri", "http://localhost:8080/auth/callback")
                        .queryParam("scope", "openid profile offline_access")
                        .queryParam("state", "saved-request-state")
                        .queryParam("nonce", "saved-request-nonce")
                        .queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(initialAuthorization.getResponse().getHeader("Location")).endsWith("/login");
        MockHttpSession session =
                (MockHttpSession) initialAuthorization.getRequest().getSession(false);
        assertThat(session).isNotNull();

        String continuation = mvc.perform(post("/login")
                        .session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", "admin"))
                .andExpect(authenticated())
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getHeader("Location");
        assertThat(continuation).contains("/oauth2/authorize");

        String callback = mvc.perform(get(java.net.URI.create(continuation)).session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getHeader("Location");
        var callbackParameters = UriComponentsBuilder.fromUriString(callback).build().getQueryParams();
        assertThat(callbackParameters.getFirst("state")).isEqualTo("saved-request-state");
        String code = callbackParameters.getFirst("code");
        assertThat(code).isNotBlank();

        String tokenBody = mvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("client_id", "rwms-panel")
                        .param("code", code)
                        .param("redirect_uri", "http://localhost:8080/auth/callback")
                        .param("code_verifier", verifier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        OAuthTokens initialTokens = oauthTokens(tokenBody);
        assertThat(initialTokens.expiresIn()).isBetween(295, 300);
        assertThat(jwtDecoder.decode(initialTokens.accessToken()).getClaimAsString("principal_type"))
                .isEqualTo("USER");
        OAuthTokens rotated =
                refreshPublicClient("rwms-panel", initialTokens.refreshToken(), status().isOk());
        assertThat(rotated.refreshToken()).isNotEqualTo(initialTokens.refreshToken());
        assertThat(jwtDecoder.decode(rotated.accessToken()).getSubject())
                .isEqualTo(subjects.findByUsernameIgnoreCase("admin").orElseThrow().getId().toString());
        refreshPublicClient("rwms-panel", initialTokens.refreshToken(), status().isBadRequest());

        String idToken = JsonPath.read(tokenBody, "$.id_token");
        assertThat(SignedJWT.parse(idToken).getJWTClaimsSet().getStringClaim("nonce"))
                .isEqualTo("saved-request-nonce");
    }

    @Test
    void workerAuthorizationUsesWorkerLoginSurfaceAndPreservesFailureState() throws Exception {
        String verifier = "rwms-worker-login-surface-verifier-0000000000000000000000";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var initial = mvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "rwms-worker-android")
                        .queryParam("redirect_uri", "http://localhost:8082/auth/worker/callback")
                        .queryParam("scope", "openid profile offline_access worker.tasks")
                        .queryParam("state", "worker-login-surface-state")
                        .queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) initial.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mvc.perform(get("/login").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?surface=worker"));
        mvc.perform(get("/login").session(session).queryParam("surface", "worker"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/login").session(session).queryParam("error", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?surface=worker&error"));
    }

    @Test
    void driverAuthorizationUsesWorkerCredentialLoginSurface() throws Exception {
        String verifier = "rwms-driver-login-surface-verifier-0000000000000000000000";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var initial = mvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "rwms-driver-android")
                        .queryParam("redirect_uri", "http://localhost:8082/auth/driver/callback")
                        .queryParam("scope", "openid profile offline_access driver.tasks")
                        .queryParam("state", "driver-login-surface-state")
                        .queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) initial.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mvc.perform(get("/login").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?surface=worker"));
    }

    @Test
    void authorizationEndpointRejectsUnregisteredRedirectAndMissingPkce() throws Exception {
        var invalidRedirect = mvc.perform(get("/oauth2/authorize")
                        .with(user("admin").roles("SYSTEM_ADMIN"))
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "rwms-panel")
                        .queryParam("redirect_uri", "https://attacker.example/callback")
                        .queryParam("scope", "openid profile")
                        .queryParam("state", "invalid-redirect-state"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse();
        assertThat(invalidRedirect.getHeader("Location")).isNull();

        var missingPkce = mvc.perform(get("/oauth2/authorize")
                        .with(user("admin").roles("SYSTEM_ADMIN"))
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "rwms-panel")
                        .queryParam("redirect_uri", "http://localhost:8080/auth/callback")
                        .queryParam("scope", "openid profile")
                        .queryParam("state", "missing-pkce-state"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse();
        assertThat(missingPkce.getHeader("Location"))
                .startsWith("http://localhost:8080/auth/callback")
                .contains("error=invalid_request");
    }

    @Test
    void resourceApiRejectsWrongAudienceAndExpiredJwt() throws Exception {
        mvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + signedJwt(List.of("another-service"),
                                Instant.now().plusSeconds(60))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + signedJwt(List.of("rwms-services"),
                                Instant.now().minusSeconds(60))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void csrfBootstrapCreatesCookieAndLoginPostKeepsCsrfProtection() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk());
        mvc.perform(get("/assets/background.webm"))
                .andExpect(status().isOk());
        mvc.perform(get("/assets/block-box-logo.svg"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty());

        mvc.perform(post("/login").param("username", "admin").param("password", "admin"))
                .andExpect(status().isForbidden());
        mvc.perform(formLogin().user("admin").password("admin"))
                .andExpect(authenticated());
    }

    @Test
    void apiRejectsAuthenticatedWebSessionAndAcceptsBearerPrincipal() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(formLogin().user("admin").password("admin"))
                .andReturn().getRequest().getSession(false);
        assertThat(session).isNotNull();

        mvc.perform(get("/api/users/me").session(session))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").with(jwt()
                        .jwt(token -> token.subject("admin"))
                        .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.warehouseAccessAll").value(true));
    }

    @Test
    void corsAllowsWorkerOriginAndRejectsUnknownOrigin() throws Exception {
        mvc.perform(options("/api/users/me")
                        .header("Origin", "http://localhost:8082")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8082"));
        mvc.perform(options("/api/users/me")
                        .header("Origin", "https://attacker.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void clientCredentialsJwtCanManageWorkerCredentialsWithoutReturningPassword() throws Exception {
        String tokenBody = mvc.perform(post("/oauth2/token")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic(
                                "task-board-service", "task-board-test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("scope", "worker-credentials.manage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(tokenBody, "$.access_token");

        mvc.perform(put("/api/internal/worker-credentials/worker-invalid")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":"warehouse-unknown","appLogin":"worker.invalid","password":"secret-invalid"}
                                """))
                .andExpect(status().isBadRequest());
        assertThat(subjects.findByExternalWorkerId("worker-invalid")).isEmpty();

        mvc.perform(put("/api/internal/worker-credentials/worker-101")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":"spb","appLogin":"worker.101","password":"secret-101"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value("worker-101"))
                .andExpect(jsonPath("$.warehouseId").value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(matchesContract("/api/internal/worker-credentials/{workerId}"));

        mvc.perform(post("/api/internal/worker-credentials/worker-101/disable")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent())
                .andExpect(matchesContract("/api/internal/worker-credentials/{workerId}/disable"));
        mvc.perform(get("/api/internal/worker-credentials/worker-101/status")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(matchesContract("/api/internal/worker-credentials/{workerId}/status"));
        mvc.perform(post("/api/internal/worker-credentials/worker-101/reset")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"rotated-secret\"}"))
                .andExpect(status().isNoContent())
                .andExpect(matchesContract("/api/internal/worker-credentials/{workerId}/reset"));
        assertThat(passwordEncoder.matches(
                        "rotated-secret",
                        subjects.findByExternalWorkerId("worker-101").orElseThrow().getPasswordHash()))
                .isTrue();
        mvc.perform(post("/api/internal/worker-credentials/worker-101/enable")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent())
                .andExpect(matchesContract("/api/internal/worker-credentials/{workerId}/enable"));
        mvc.perform(get("/api/internal/worker-credentials/worker-101/status")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appLogin").value("worker.101"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(matchesContract("/api/internal/worker-credentials/{workerId}/status"));
        mvc.perform(delete("/api/internal/worker-credentials/worker-101")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent())
                .andExpect(matchesContract("/api/internal/worker-credentials/{workerId}"));
    }

    @Test
    void clientCredentialsRejectWrongSecretAndUseOnlyRotatedSecret() throws Exception {
        mvc.perform(post("/oauth2/token")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic(
                                "task-board-service", "wrong-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("scope", "worker-credentials.manage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));

        var original = jdbcClients.findByClientId("task-board-service");
        var rotated = RegisteredClient.from(original)
                .clientSecret(passwordEncoder.encode("rotated-task-board-secret"))
                .build();
        jdbcClients.save(rotated);
        try {
            mvc.perform(post("/oauth2/token")
                            .with(SecurityMockMvcRequestPostProcessors.httpBasic(
                                    "task-board-service", "task-board-test-secret"))
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("grant_type", "client_credentials")
                            .param("scope", "worker-credentials.manage"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("invalid_client"));

            mvc.perform(post("/oauth2/token")
                            .with(SecurityMockMvcRequestPostProcessors.httpBasic(
                                    "task-board-service", "rotated-task-board-secret"))
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("grant_type", "client_credentials")
                            .param("scope", "worker-credentials.manage"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.access_token").isNotEmpty());
        } finally {
            jdbcClients.save(original);
        }
    }

    @Test
    void serviceTokenNeverInheritsCollidingUserRoleAndClientIdsAreReserved() throws Exception {
        mvc.perform(post("/api/admin/users")
                        .with(adminAppJwt("admin", "SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"rwms-panel",
                                  "password":"reserved-secret",
                                  "globalRole":"SYSTEM_ADMIN"
                                }
                                """))
                .andExpect(status().isConflict());

        var collision = new AuthSubject();
        collision.registerUser(
                "task-board-service",
                passwordEncoder.encode("collision-secret"),
                null,
                null,
                null,
                null,
                UserGlobalRole.SYSTEM_ADMIN,
                true);
        subjects.saveAndFlush(collision);
        try {
            String tokenBody = mvc.perform(post("/oauth2/token")
                            .with(SecurityMockMvcRequestPostProcessors.httpBasic(
                                    "task-board-service", "task-board-test-secret"))
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("grant_type", "client_credentials")
                            .param("scope", "worker-credentials.manage"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String accessToken = JsonPath.read(tokenBody, "$.access_token");
            var claims = SignedJWT.parse(accessToken).getJWTClaimsSet();
            assertThat(claims.getSubject()).isEqualTo("task-board-service");
            assertThat(claims.getStringClaim("principal_type")).isEqualTo("SERVICE");
            assertThat(claims.getStringClaim("client_id")).isEqualTo("task-board-service");
            assertThat(claims.getClaim("global_role")).isNull();

            mvc.perform(get("/api/admin/users")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/users/actor-displays")
                            .header("Authorization", "Bearer " + accessToken)
                            .queryParam("subjectId", collision.getId().toString()))
                    .andExpect(status().isForbidden());
        } finally {
            subjects.delete(collision);
        }
    }

    @Test
    void internalCredentialApiRequiresManagementScope() throws Exception {
        mvc.perform(get("/api/internal/worker-credentials/missing/status").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void disabledAuthServiceClientCannotObtainToken() throws Exception {
        mvc.perform(post("/oauth2/token")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic(
                                "auth-service", "any-unconfigured-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("scope", "warehouse.read"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerMeUsesEffectiveViewAccessAndAdminDeleteIsFailClosed() throws Exception {
        String createBody = mvc.perform(post("/api/admin/users")
                        .with(adminAppJwt("admin", "SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"viewer.one",
                                  "password":"viewer-secret",
                                  "globalRole":"VIEWER",
                                  "warehouseAccesses":[{"warehouseId":"spb","accessLevel":"MANAGE","active":true}]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String userId = JsonPath.read(createBody, "$.id");
        int version = JsonPath.read(createBody, "$.version");
        assertThat((String) JsonPath.read(createBody, "$.warehouseAccesses[0].warehouseId"))
                .isEqualTo("00000000-0000-0000-0000-000000000001");

        mvc.perform(put("/api/admin/users/{id}/warehouse-accesses", userId)
                        .with(adminAppJwt("admin", "SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion":%d,
                                  "accesses":[{"warehouseId":"spb","accessLevel":"MANAGE","active":true}]
                                }
                                """.formatted(version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version));

        mvc.perform(put("/api/admin/users/{id}", userId)
                        .with(adminAppJwt("admin", "SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion":%d,
                                  "username":"viewer.one",
                                  "firstName":"Viewer",
                                  "active":true,
                                  "globalRole":"VIEWER"
                                }
                                """.formatted(version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version + 1));

        mvc.perform(get("/api/users/me").with(jwt()
                        .jwt(token -> token.subject("viewer.one"))
                        .authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warehouseAccesses[0].level").value("VIEW"))
                .andExpect(jsonPath("$.warehouseAccesses[0].accessLevel").doesNotExist());

        mvc.perform(delete("/api/admin/users/{id}", userId)
                        .with(adminAppJwt("admin", "SYSTEM_ADMIN")))
                .andExpect(status().isConflict());
    }

    @Test
    void cannotDisableCurrentOrLastActiveSystemAdmin() throws Exception {
        AuthSubject admin = subjects.findByUsernameIgnoreCase("admin").orElseThrow();
        String adminId = admin.getId().toString();
        mvc.perform(put("/api/admin/users/{id}", adminId)
                        .with(adminAppJwt("admin", "SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion":%d,
                                  "username":"admin",
                                  "active":false,
                                  "globalRole":"SYSTEM_ADMIN"
                                }
                                """.formatted(admin.getVersion())))
                .andExpect(status().isConflict());
    }

    @Test
    void wmsAdminCannotCreateOrManageSystemAdmin() throws Exception {
        users.create(
                new CreateUserRequest(
                        "wms.admin.boundary",
                        "wms-admin-secret",
                        null,
                        null,
                        null,
                        null,
                        UserGlobalRole.WMS_ADMIN,
                        true,
                        List.of()),
                adminAuthentication());
        var wmsJwt = adminAppJwt("wms.admin.boundary", "WMS_ADMIN");

        mvc.perform(post("/api/admin/users")
                        .with(wmsJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"forbidden.system.admin",
                                  "password":"forbidden-secret",
                                  "globalRole":"SYSTEM_ADMIN"
                                }
                                """))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/admin/users")
                        .with(adminAppJwt("wms.admin.boundary", "WMS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"wms.created.viewer",
                                  "password":"viewer-from-wms",
                                  "globalRole":"VIEWER"
                                }
                                """))
                .andExpect(status().isCreated());

        AuthSubject systemAdmin = subjects.findByUsernameIgnoreCase("admin").orElseThrow();
        mvc.perform(put("/api/admin/users/{id}/password", systemAdmin.getId())
                        .with(adminAppJwt("wms.admin.boundary", "WMS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"new-admin-secret","expectedVersion":%d}
                                """.formatted(systemAdmin.getVersion())))
                .andExpect(status().isConflict());

        mvc.perform(put("/api/admin/users/{id}/warehouse-accesses", systemAdmin.getId())
                        .with(adminAppJwt("wms.admin.boundary", "WMS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":%d,"accesses":[]}
                                """.formatted(systemAdmin.getVersion())))
                .andExpect(status().isConflict());
    }

    @Test
    void disabledUserCannotAuthenticateWithFormLogin() throws Exception {
        AuthSubject disabled = subjects.findByUsernameIgnoreCase("disabled.user").orElse(null);
        if (disabled == null) {
            disabled = new AuthSubject();
            disabled.registerUser(
                    "disabled.user",
                    passwordEncoder.encode("disabled-secret"),
                    null,
                    null,
                    null,
                    null,
                    UserGlobalRole.VIEWER,
                    false);
        } else {
            disabled.changeUserProfile("disabled.user", null, null, null, null);
            disabled.changePasswordHash(passwordEncoder.encode("disabled-secret"));
            disabled.changeUserAuthorization(UserGlobalRole.VIEWER, false);
        }
        subjects.saveAndFlush(disabled);

        mvc.perform(formLogin().user("disabled.user").password("disabled-secret"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void existingSessionCannotMintTokenAfterUserIsDisabled() throws Exception {
        var subject = users.create(
                new CreateUserRequest(
                        "session.disabled",
                        "session-secret",
                        null,
                        null,
                        null,
                        null,
                        UserGlobalRole.VIEWER,
                        true,
                        List.of()),
                adminAuthentication());

        MockHttpSession session = (MockHttpSession) mvc.perform(
                        formLogin().user("session.disabled").password("session-secret"))
                .andExpect(authenticated())
                .andReturn().getRequest().getSession(false);
        users.update(
                subject.id(),
                new UpdateUserRequest(
                        subject.version(),
                        subject.username(),
                        subject.firstName(),
                        subject.lastName(),
                        subject.email(),
                        subject.timeZoneId(),
                        false,
                        subject.globalRole()),
                adminAuthentication());

        String verifier = "rwms-session-disabled-code-verifier-00000000000000000000";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String location = mvc.perform(get("/oauth2/authorize")
                        .session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "rwms-panel")
                        .queryParam("redirect_uri", "http://localhost:8080/auth/callback")
                        .queryParam("scope", "openid profile")
                        .queryParam("state", "disabled-session-state")
                        .queryParam("nonce", "disabled-session-nonce")
                        .queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getHeader("Location");
        String code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");
        assertThat(code).isNotBlank();

        mvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("client_id", "rwms-panel")
                        .param("code", code)
                        .param("redirect_uri", "http://localhost:8080/auth/callback")
                        .param("code_verifier", verifier))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("access_denied"));
    }

    private OAuthTokens authorizeWorker(String username, String password) throws Exception {
        return authorizeWorkerClient(
                username,
                password,
                "rwms-worker-android",
                "http://localhost:8082/auth/worker/callback",
                "openid profile offline_access worker.tasks");
    }

    private OAuthTokens authorizeWorkerClient(
            String username,
            String password,
            String clientId,
            String redirectUri,
            String scope)
            throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(
                        formLogin().user(username).password(password))
                .andExpect(authenticated())
                .andReturn()
                .getRequest()
                .getSession(false);
        String verifier = "rwms-worker-code-verifier-000000000000000000000000000000";
        String challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
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
        String body = mvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("client_id", clientId)
                        .param("code", code)
                        .param("redirect_uri", redirectUri)
                        .param("code_verifier", verifier))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return oauthTokens(body);
    }

    private OAuthTokens refreshWorker(String refreshToken, ResultMatcher expectedStatus)
            throws Exception {
        return refreshPublicClient("rwms-worker-android", refreshToken, expectedStatus);
    }

    private OAuthTokens refreshPublicClient(
            String clientId, String refreshToken, ResultMatcher expectedStatus)
            throws Exception {
        var result = mvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("client_id", clientId)
                        .param("refresh_token", refreshToken))
                .andExpect(expectedStatus)
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            assertThat(JsonPath.<String>read(
                            result.getResponse().getContentAsString(), "$.error"))
                    .isEqualTo("invalid_grant");
            return null;
        }
        return oauthTokens(result.getResponse().getContentAsString());
    }

    private OAuthTokens oauthTokens(String body) {
        return new OAuthTokens(
                JsonPath.read(body, "$.access_token"),
                JsonPath.read(body, "$.refresh_token"),
                ((Number) JsonPath.read(body, "$.expires_in")).intValue());
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("dev.buhanzaz.rwms.auth.AuthOpenApiContractTest#omittedOperations")
    void documentedOperationsRequireTheActualBearerAuthorizationBoundary(
            String method, String template, String body, int successStatus) throws Exception {
        String restrictedSubject = template.endsWith("actor-displays")
                ? users.create(new CreateUserRequest(
                        "contract.viewer." + UUID.randomUUID(), "test-password", null, null, null, null,
                        UserGlobalRole.VIEWER, true, List.of()), adminAuthentication()).username()
                : "unknown-r013-user";
        for (boolean authenticated : List.of(false, true)) {
            var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                    org.springframework.http.HttpMethod.valueOf(method),
                    AuthOpenApiContractTest.concretePath(template));
            if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
            if (template.endsWith("actor-displays")) {
                request.queryParam("subjectId", AuthOpenApiContractTest.ID.toString());
            }
            if (authenticated) request.with(jwt()
                    .jwt(token -> token.subject(restrictedSubject))
                    .authorities(new SimpleGrantedAuthority("ROLE_USER")));
            mvc.perform(request).andExpect(status().is(authenticated ? 403 : 401))
                    .andExpect(matchesContract(template));
        }
    }

    @Test
    void administrativePasswordAccessesAndActorResponsesMatchCanonicalHttpSchemas() throws Exception {
        var created = users.create(new CreateUserRequest(
                "contract.admin.target", "initial-password", null, null, null, null,
                UserGlobalRole.VIEWER, true, List.of()), adminAuthentication());
        String passwordBody = "{\"password\":\"replaced-password\",\"expectedVersion\":"
                + created.version() + "}";
        mvc.perform(put("/api/admin/users/{id}/password", created.id())
                        .with(adminAppJwt("admin", "SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(passwordBody))
                .andExpect(status().isNoContent())
                .andExpect(matchesContract("/api/admin/users/{id}/password"));
        mvc.perform(put("/api/admin/users/{id}/password", created.id())
                        .with(adminAppJwt("admin", "SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(passwordBody))
                .andExpect(status().isConflict())
                .andExpect(matchesContract("/api/admin/users/{id}/password"));
        int version = subjects.findById(created.id()).orElseThrow().getVersion();
        mvc.perform(put("/api/admin/users/{id}/warehouse-accesses", created.id())
                        .with(adminAppJwt("admin", "SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + version + ",\"accesses\":[]}"))
                .andExpect(status().isOk())
                .andExpect(matchesContract("/api/admin/users/{id}/warehouse-accesses"));
        mvc.perform(get("/api/users/actor-displays")
                        .with(adminAppJwt("admin", "SYSTEM_ADMIN"))
                        .queryParam("subjectId", created.id().toString(), UUID.randomUUID().toString(),
                                created.id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(matchesContract("/api/users/actor-displays"));
        mvc.perform(delete("/api/admin/users/{id}", created.id())
                        .with(adminAppJwt("admin", "SYSTEM_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(matchesContract("/api/admin/users/{id}"));
    }

    @Test
    void missingShadowCheckpointHasAnObservedHttpRecoveryFailure() throws Exception {
        RSAKey signingKey = (RSAKey) jwkSource
                .get(new JWKSelector(new JWKMatcher.Builder().privateOnly(true).build()), null)
                .getFirst();
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer("http://localhost:9000")
                .subject(subjects.findByUsernameIgnoreCase("admin").orElseThrow().getId().toString())
                .audience(List.of("rwms-services"))
                .issueTime(Date.from(now.minusSeconds(1))).expirationTime(Date.from(now.plusSeconds(60)))
                .claim("preferred_username", "admin").claim("principal_type", "USER")
                .claim("global_role", "SYSTEM_ADMIN").build();
        var token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(signingKey.toPrivateKey()));
        String missingAggregate = UUID.randomUUID().toString();
        String path = "/api/admin/eventing/shadow/USER_AUTHORIZATION/" + missingAggregate + "/reconcile";
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token.serialize())
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"expectedCheckpointVersion\":0,\"reason\":\"Missing checkpoint HTTP contract probe\"}"))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("R013 missing-checkpoint HTTP status=" + response.statusCode()
                    + " content-type=" + response.headers().firstValue("Content-Type").orElse("absent"));
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
            assertThat((Integer) JsonPath.read(response.body(), "$.status")).isEqualTo(500);
            assertThat((String) JsonPath.read(response.body(), "$.error")).isEqualTo("Internal Server Error");
            AuthOpenApiContractTest.assertHttpResponse(
                    "/api/admin/eventing/shadow/{aggregateType}/{aggregateId}/reconcile", "post",
                    response.statusCode(), response.headers().firstValue("Content-Type").orElseThrow(),
                    response.body());
            assertThat(response.body()).doesNotContain(token.serialize());
        }
    }

    private UsernamePasswordAuthenticationToken adminAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated("admin", "", List.of());
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminAppJwt(
            String subject, String role) {
        return jwt().jwt(token -> token.subject(subject))
                .authorities(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("ROLE_" + role),
                        new SimpleGrantedAuthority("CLIENT_rwms-admin-web"),
                        new SimpleGrantedAuthority("SCOPE_admin.manage"));
    }

    private String signedJwt(List<String> audience, Instant expiresAt) throws Exception {
        RSAKey signingKey = (RSAKey) jwkSource
                .get(new JWKSelector(new JWKMatcher.Builder().privateOnly(true).build()), null)
                .stream()
                .findFirst()
                .orElseThrow();
        Instant issuedAt = expiresAt.isAfter(Instant.now())
                ? Instant.now().minusSeconds(1)
                : expiresAt.minusSeconds(60);
        var claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:9000")
                .subject("admin")
                .audience(audience)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(signingKey.toPrivateKey()));
        return jwt.serialize();
    }

    private record OAuthTokens(String accessToken, String refreshToken, int expiresIn) {}
}
