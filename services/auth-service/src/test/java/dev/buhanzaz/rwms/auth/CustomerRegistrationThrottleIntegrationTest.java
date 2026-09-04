package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies durable per-client and global registration budgets against PostgreSQL. */
@SpringBootTest(properties = {
    "rwms.auth.customer-registration-throttle.per-client-limit=2",
    "rwms.auth.customer-registration-throttle.per-client-window=PT10M",
    "rwms.auth.customer-registration-throttle.global-limit=3",
    "rwms.auth.customer-registration-throttle.global-window=PT1M",
    "rwms.auth.customer-registration-throttle.retention=P1D"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CustomerRegistrationThrottleIntegrationTest {

    private static final String PASSWORD = "registration-password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthSubjectRepository subjects;

    /** Starts every test with fresh counters while preserving the migrated schema. */
    @BeforeEach
    void resetCounters() {
        jdbc.update("delete from customer_registration_throttle");
    }

    @Test
    void rejectsThirdAttemptFromOneCanonicalClientBeforeCreatingAnotherSubject() throws Exception {
        register("throttle.client.one", "198.51.100.10").andExpect(status().isCreated());
        register("throttle.client.two", "198.51.100.10").andExpect(status().isCreated());

        register("throttle.client.blocked", "198.51.100.10")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")))
                .andExpect(jsonPath("$.code").value("CUSTOMER_REGISTRATION_RATE_LIMITED"))
                .andExpect(jsonPath("$.detail")
                        .value("Слишком много попыток регистрации. Повторите попытку позже"));

        assertThat(subjects.findByUsernameIgnoreCase("throttle.client.blocked")).isEmpty();
        assertThat(jdbc.queryForObject(
                        "select request_count from customer_registration_throttle where scope_key='CLIENT'",
                        Long.class))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                        "select request_count from customer_registration_throttle where scope_key='GLOBAL'",
                        Long.class))
                .isEqualTo(2L);
    }

    @Test
    void globalBudgetRejectsRotatingSourcesAndPersistsTheExhaustedWindow() throws Exception {
        register("throttle.global.one", "198.51.100.21").andExpect(status().isCreated());
        register("throttle.global.two", "198.51.100.22").andExpect(status().isCreated());
        register("throttle.global.three", "198.51.100.23").andExpect(status().isCreated());

        register("throttle.global.blocked", "198.51.100.24")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("CUSTOMER_REGISTRATION_RATE_LIMITED"));

        assertThat(subjects.findByUsernameIgnoreCase("throttle.global.blocked")).isEmpty();
        assertThat(jdbc.queryForObject(
                        "select request_count from customer_registration_throttle where scope_key='GLOBAL'",
                        Long.class))
                .isEqualTo(4L);
        assertThat(jdbc.queryForObject(
                        "select count(*) from customer_registration_throttle where scope_key='CLIENT'",
                        Integer.class))
                .isEqualTo(4);
    }

    private ResultActions register(String username, String remoteAddress) throws Exception {
        var bootstrap = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = bootstrap.getResponse().getCookie("XSRF-TOKEN");
        String token = JsonPath.read(bootstrap.getResponse().getContentAsString(), "$.token");
        String body = """
                {"username":"%s","password":"%s","passwordConfirmation":"%s"}
                """.formatted(username, PASSWORD, PASSWORD);
        return mvc.perform(post("/api/customer/v1/registrations")
                .with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                })
                .cookie(cookie)
                .header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
