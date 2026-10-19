package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.auth.service.LoginAttemptThrottle;
import dev.buhanzaz.rwms.auth.service.LoginAttemptThrottle.Admission;
import dev.buhanzaz.rwms.auth.service.LoginAttemptThrottle.LoginAttemptThrottleUnavailableException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies durable login budgets, pre-hash rejection, and bounded settlement against PostgreSQL. */
@SpringBootTest(properties = {
    "rwms.auth.login-attempt-throttle.source-ingress.limit=4",
    "rwms.auth.login-attempt-throttle.source-ingress.window=PT1M",
    "rwms.auth.login-attempt-throttle.source-auth.limit=2",
    "rwms.auth.login-attempt-throttle.source-auth.window=PT10M",
    "rwms.auth.login-attempt-throttle.account-auth.limit=2",
    "rwms.auth.login-attempt-throttle.account-auth.window=PT15M",
    "rwms.auth.login-attempt-throttle.retention=P1D",
    "rwms.auth.login-attempt-throttle.cleanup-delay=PT1H",
    "rwms.auth.login-attempt-throttle.database-timeout=PT0.5S"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginAttemptThrottleIntegrationTest {
    private static final String SOURCE = "198.51.100.90";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LoginAttemptThrottle throttle;

    @Autowired
    DataSource dataSource;

    @MockitoSpyBean
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetBudgets() {
        jdbc.update("delete from login_attempt_budget");
        clearInvocations(passwordEncoder);
    }

    @AfterEach
    void restorePasswordEncoder() {
        org.mockito.Mockito.reset(passwordEncoder);
    }

    @Test
    void invalidCsrfDoesNotConsumeBudgetOrVerifyPassword() throws Exception {
        mvc.perform(post("/login")
                        .with(remoteAddress(SOURCE))
                        .param("username", "admin")
                        .param("password", "wrong"))
                .andExpect(status().isForbidden());

        assertThat(budgetRowCount()).isZero();
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void failedHashesReachLimitThenJsonRejectionHappensBeforeHashing() throws Exception {
        login(" admin ", "wrong-one", "198.51.100.91")
                .andExpect(redirectedUrl("/login?error"));
        login("ADMIN", "wrong-two", "198.51.100.92")
                .andExpect(redirectedUrl("/login?error"));

        login(
                        "AdMiN ",
                        "correct-but-blocked",
                        "198.51.100.93",
                        MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, Matchers.matchesPattern("[1-9][0-9]*")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("LOGIN_ATTEMPT_RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(Matchers.greaterThanOrEqualTo(1)));

        verify(passwordEncoder, times(2)).matches(any(), any());
        assertThat(countForScope("SOURCE_AUTH")).isEqualTo(2);
        assertThat(countForScope("ACCOUNT_AUTH")).isEqualTo(2);
    }

    @Test
    void contextPathAndEncodedLoginUseThePasswordFiltersAdmissionMatcher() throws Exception {
        contextLogin("admin", "wrong-one", "198.51.100.94")
                .andExpect(redirectedUrl("/auth/login?error"));
        mvc.perform(post(URI.create("/log%69n"))
                        .with(csrf())
                        .with(remoteAddress("198.51.100.95"))
                        .param("username", "admin")
                        .param("password", "wrong-two"))
                .andExpect(redirectedUrl("/login?error"));

        contextLogin("admin", "blocked", "198.51.100.96")
                .andExpect(status().isTooManyRequests());
        verify(passwordEncoder, times(2)).matches(any(), any());
        assertThat(countForScope("ACCOUNT_AUTH")).isEqualTo(2);
    }

    @Test
    void postgresCaseVariantsThatShareAnAccountAlsoShareItsBudget() throws Exception {
        assertThat(jdbc.queryForObject("select lower(?) = lower(?)", Boolean.class, "ΟΣ", "οσ"))
                .isTrue();

        login("ΟΣ", "wrong-one", "198.51.100.101").andExpect(status().is3xxRedirection());
        login("οσ", "wrong-two", "198.51.100.102").andExpect(status().is3xxRedirection());
        login(" ΟΣ ", "blocked", "198.51.100.103")
                .andExpect(status().isTooManyRequests());

        verify(passwordEncoder, times(2)).matches(any(), any());
        assertThat(countForScope("ACCOUNT_AUTH")).isEqualTo(2);
    }

    @Test
    void browserRejectionUsesStaticPageWithoutLosingHeaders() throws Exception {
        login("admin", "wrong-one", SOURCE).andExpect(status().is3xxRedirection());
        login("admin", "wrong-two", SOURCE).andExpect(status().is3xxRedirection());

        login("admin", "wildcard", SOURCE, MediaType.ALL_VALUE)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        login("admin", "html-disabled", SOURCE, "text/html;q=0, application/json")
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        String body = login("admin", "blocked", SOURCE, MediaType.TEXT_HTML_VALUE)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, Matchers.matchesPattern("[1-9][0-9]*")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(Matchers.containsString("href=\"login\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("__RETRY_AFTER_SECONDS__"))))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).containsPattern("Подождите [1-9][0-9]* сек\\.");
        URI publicLogin = URI.create("https://rwms.example/auth/login");
        assertThat(publicLogin.resolve(attribute(body, "href", "login")).getPath())
                .isEqualTo("/auth/login");
        assertThat(publicLogin.resolve(attribute(body, "src", "")).getPath())
                .startsWith("/auth/assets/");

        verify(passwordEncoder, times(2)).matches(any(), any());
    }

    @Test
    void successRefundsOnlyItsSourceAndAccountUnits() throws Exception {
        login("admin", "wrong", SOURCE).andExpect(status().is3xxRedirection());

        login("admin", "admin", SOURCE)
                .andExpect(status().is3xxRedirection())
                .andExpect(authenticated());

        assertThat(countForScope("SOURCE_INGRESS")).isEqualTo(2);
        assertThat(countForScope("SOURCE_AUTH")).isEqualTo(1);
        assertThat(countForScope("ACCOUNT_AUTH")).isEqualTo(1);
    }

    @Test
    void authenticationInfrastructureAbortRefundsAuthUnitsButRetainsIngress() throws Exception {
        doThrow(new InternalAuthenticationServiceException("synthetic test failure"))
                .when(passwordEncoder)
                .matches(any(), any());

        login("admin", "admin", SOURCE).andExpect(redirectedUrl("/login?error"));

        assertThat(countForScope("SOURCE_INGRESS")).isOne();
        assertThat(countForScope("SOURCE_AUTH")).isZero();
        assertThat(countForScope("ACCOUNT_AUTH")).isZero();
    }

    @Test
    void admissionDatabaseFailureReturns503BeforePasswordHashing() throws Exception {
        jdbc.execute("alter table login_attempt_budget rename to login_attempt_budget_unavailable_test");
        try {
            login("admin", "admin", SOURCE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.code").value("LOGIN_ATTEMPT_SERVICE_UNAVAILABLE"));
            verifyNoInteractions(passwordEncoder);
        } finally {
            jdbc.execute("alter table login_attempt_budget_unavailable_test rename to login_attempt_budget");
        }
    }

    @Test
    void settlementIsOneShotAndLateSettlementCannotChangeNewGeneration() {
        Admission first = throttle.requireAllowed(SOURCE, "admin");
        throttle.refund(first);
        throttle.refund(first);
        assertThat(countForScope("SOURCE_AUTH")).isZero();
        assertThat(countForScope("ACCOUNT_AUTH")).isZero();

        Admission late = throttle.requireAllowed(SOURCE, "admin");
        jdbc.update("""
                update login_attempt_budget
                   set window_started_at = clock_timestamp() - interval '2 hours',
                       expires_at = clock_timestamp() - interval '1 hour'
                """);
        Admission current = throttle.requireAllowed(SOURCE, "admin");

        throttle.refund(late);
        assertThat(countForScope("SOURCE_AUTH")).isOne();
        assertThat(countForScope("ACCOUNT_AUTH")).isOne();
        throttle.refund(current);
        assertThat(countForScope("SOURCE_AUTH")).isZero();
        assertThat(countForScope("ACCOUNT_AUTH")).isZero();
    }

    @Test
    void crashReservationExpiresAndCleanupRetainsOnlyRecentWindows() {
        throttle.requireAllowed(SOURCE, "admin");
        jdbc.update("""
                update login_attempt_budget
                   set window_started_at = clock_timestamp() - interval '3 days',
                       expires_at = clock_timestamp() - interval '2 days'
                 where scope_key = 'SOURCE_INGRESS'
                """);
        jdbc.update("""
                update login_attempt_budget
                   set window_started_at = clock_timestamp() - interval '2 hours',
                       expires_at = clock_timestamp() - interval '1 hour'
                 where scope_key = 'SOURCE_AUTH'
                """);

        throttle.deleteExpiredBudgets();

        assertThat(rowCountForScope("SOURCE_INGRESS")).isZero();
        assertThat(rowCountForScope("SOURCE_AUTH")).isOne();
        assertThat(rowCountForScope("ACCOUNT_AUTH")).isOne();
    }

    @Test
    void concurrentAdmissionsEnforceSharedSourceAndAccountBounds() throws Exception {
        assertThat(concurrentAllowed(List.of(
                        attempt(SOURCE, "peer.one"),
                        attempt(SOURCE, "peer.two"),
                        attempt(SOURCE, "peer.three"),
                        attempt(SOURCE, "peer.four"),
                        attempt(SOURCE, "peer.five"),
                        attempt(SOURCE, "peer.six"))))
                .isEqualTo(2);

        jdbc.update("delete from login_attempt_budget");
        assertThat(concurrentAllowed(List.of(
                        attempt("198.51.100.1", "shared.account"),
                        attempt("198.51.100.2", "shared.account"),
                        attempt("198.51.100.3", "shared.account"),
                        attempt("198.51.100.4", "shared.account"),
                        attempt("198.51.100.5", "shared.account"),
                        attempt("198.51.100.6", "shared.account"))))
                .isEqualTo(2);

        jdbc.update("delete from login_attempt_budget");
        assertThat(concurrentAllowed(List.of(
                        attempt("198.51.100.11", "account.one"),
                        attempt("198.51.100.12", "account.two"),
                        attempt("198.51.100.13", "account.three"),
                        attempt("198.51.100.14", "account.four"))))
                .isEqualTo(4);
    }

    @Test
    void freshDatabaseClockAfterLockWaitResetsWindowThatExpiredWhileWaiting() throws Exception {
        throttle.requireAllowed(SOURCE, "admin");
        jdbc.update("""
                update login_attempt_budget
                   set attempt_count = case scope_key
                           when 'SOURCE_INGRESS' then 4
                           else 2
                       end,
                       window_started_at = clock_timestamp() - interval '1 minute',
                       expires_at = clock_timestamp() + interval '150 milliseconds'
                """);

        try (Connection lock = dataSource.getConnection();
                var statement = lock.prepareStatement("""
                        select attempt_count
                          from login_attempt_budget
                         where scope_key = 'SOURCE_INGRESS'
                         for update
                        """)) {
            lock.setAutoCommit(false);
            statement.executeQuery();
            try (var executor = Executors.newSingleThreadExecutor()) {
                var future = executor.submit(() -> throttle.requireAllowed(SOURCE, "admin"));
                Thread.sleep(75);
                assertThat(future).isNotDone();
                Thread.sleep(125);
                lock.commit();

                assertThat(future.get(2, TimeUnit.SECONDS).allowed()).isTrue();
            }
        }

        assertThat(countForScope("SOURCE_INGRESS")).isOne();
        assertThat(countForScope("SOURCE_AUTH")).isOne();
        assertThat(countForScope("ACCOUNT_AUTH")).isOne();
    }

    @Test
    void databaseLockWaitIsBoundedByConfiguredTimeout() throws Exception {
        Admission reserved = throttle.requireAllowed(SOURCE, "admin");
        try (Connection lock = dataSource.getConnection();
                var statement = lock.prepareStatement("""
                        select attempt_count
                          from login_attempt_budget
                         where scope_key = 'SOURCE_INGRESS'
                         for update
                        """)) {
            lock.setAutoCommit(false);
            statement.executeQuery();

            long started = System.nanoTime();
            assertThatThrownBy(() -> throttle.requireAllowed(SOURCE, "admin"))
                    .isInstanceOf(LoginAttemptThrottleUnavailableException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .isLessThan(2_000);
            lock.rollback();
        }

        try (Connection lock = dataSource.getConnection();
                var statement = lock.prepareStatement("""
                        select attempt_count
                          from login_attempt_budget
                         where scope_key = 'SOURCE_AUTH'
                         for update
                        """)) {
            lock.setAutoCommit(false);
            statement.executeQuery();

            long started = System.nanoTime();
            throttle.refund(reserved);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .isLessThan(2_000);
            assertThat(countForScope("SOURCE_AUTH")).isOne();
            assertThat(countForScope("ACCOUNT_AUTH")).isOne();
            lock.rollback();
        }
    }

    @Test
    void savedWorkerAuthorizationSurvivesRejectionAndResumesAfterExpiry() throws Exception {
        String verifier = "rwms-login-throttle-worker-verifier-000000000000000000000";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var initial = mvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "rwms-worker-android")
                        .queryParam("redirect_uri", "http://localhost:8082/auth/worker/callback")
                        .queryParam("scope", "openid profile offline_access worker.tasks")
                        .queryParam("state", "throttled-worker-state")
                        .queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) initial.getRequest().getSession(false);
        assertThat(session).isNotNull();

        login("admin", "wrong-one", SOURCE, session).andExpect(status().is3xxRedirection());
        login("admin", "wrong-two", SOURCE, session).andExpect(status().is3xxRedirection());
        login("admin", "admin", SOURCE, MediaType.TEXT_HTML_VALUE, session)
                .andExpect(status().isTooManyRequests());

        jdbc.update("""
                update login_attempt_budget
                   set window_started_at = clock_timestamp() - interval '2 hours',
                       expires_at = clock_timestamp() - interval '1 hour'
                """);
        String resumed = login("admin", "admin", SOURCE, session)
                .andExpect(status().is3xxRedirection())
                .andExpect(authenticated())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.LOCATION);

        assertThat(resumed)
                .contains("/oauth2/authorize")
                .contains("client_id=rwms-worker-android")
                .contains("state=throttled-worker-state");
    }

    private Callable<Admission> attempt(String source, String account) {
        return () -> throttle.requireAllowed(source, account);
    }

    private int concurrentAllowed(List<Callable<Admission>> attempts) throws Exception {
        var ready = new CountDownLatch(attempts.size());
        var start = new CountDownLatch(1);
        List<Callable<Admission>> synchronizedAttempts = new ArrayList<>();
        for (Callable<Admission> attempt : attempts) {
            synchronizedAttempts.add(() -> {
                ready.countDown();
                start.await();
                return attempt.call();
            });
        }
        try (var executor = Executors.newFixedThreadPool(attempts.size())) {
            var futures = synchronizedAttempts.stream().map(executor::submit).toList();
            ready.await();
            start.countDown();
            int allowed = 0;
            for (var future : futures) {
                if (future.get().allowed()) {
                    allowed++;
                }
            }
            return allowed;
        }
    }

    private ResultActions login(String username, String password, String source) throws Exception {
        return login(username, password, source, (String) null);
    }

    private ResultActions login(String username, String password, String source, String accept)
            throws Exception {
        var request = post("/login")
                .with(csrf())
                .with(remoteAddress(source))
                .param("username", username)
                .param("password", password);
        if (accept != null) {
            request.header(HttpHeaders.ACCEPT, accept);
        }
        return mvc.perform(request);
    }

    private ResultActions login(
            String username, String password, String source, MockHttpSession session)
            throws Exception {
        return mvc.perform(post("/login")
                .with(csrf())
                .with(remoteAddress(source))
                .session(session)
                .param("username", username)
                .param("password", password));
    }

    private ResultActions login(
            String username,
            String password,
            String source,
            String accept,
            MockHttpSession session)
            throws Exception {
        return mvc.perform(post("/login")
                .with(csrf())
                .with(remoteAddress(source))
                .session(session)
                .header(HttpHeaders.ACCEPT, accept)
                .param("username", username)
                .param("password", password));
    }

    private ResultActions contextLogin(String username, String password, String source)
            throws Exception {
        return mvc.perform(post("/auth/login")
                .contextPath("/auth")
                .with(csrf())
                .with(remoteAddress(source))
                .param("username", username)
                .param("password", password));
    }

    private RequestPostProcessor remoteAddress(String source) {
        return request -> {
            request.setRemoteAddr(source);
            return request;
        };
    }

    private String attribute(String body, String name, String prefix) {
        var matcher = java.util.regex.Pattern.compile(name + "=\"([^\"]+)\"").matcher(body);
        while (matcher.find()) {
            if (matcher.group(1).startsWith(prefix)) {
                return matcher.group(1);
            }
        }
        throw new AssertionError("Missing relative " + name + " with prefix " + prefix);
    }

    private long countForScope(String scope) {
        Long value = jdbc.queryForObject(
                "select coalesce(sum(attempt_count), 0) from login_attempt_budget where scope_key=?",
                Long.class,
                scope);
        return value == null ? 0 : value;
    }

    private int rowCountForScope(String scope) {
        Integer value = jdbc.queryForObject(
                "select count(*) from login_attempt_budget where scope_key=?", Integer.class, scope);
        return value == null ? 0 : value;
    }

    private int budgetRowCount() {
        Integer value = jdbc.queryForObject("select count(*) from login_attempt_budget", Integer.class);
        return value == null ? 0 : value;
    }
}
