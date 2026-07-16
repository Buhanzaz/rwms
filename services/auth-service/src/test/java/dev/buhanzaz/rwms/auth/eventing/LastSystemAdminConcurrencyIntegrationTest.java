package dev.buhanzaz.rwms.auth.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.auth.api.CreateUserRequest;
import dev.buhanzaz.rwms.auth.api.UpdateUserRequest;
import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import dev.buhanzaz.rwms.auth.service.UserAdministrationService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "rwms.platform.kafka.enabled=false")
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LastSystemAdminConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    UserAdministrationService users;

    @Autowired
    AuthSubjectRepository subjects;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void concurrentDisableCommandsLeaveExactlyOneActiveSystemAdmin() throws Exception {
        var adminOne = users.listUsers().stream()
                .filter(user -> user.globalRole() == UserGlobalRole.SYSTEM_ADMIN)
                .findFirst()
                .orElseThrow();
        var actorOne = authentication(adminOne.username());
        var adminTwo = users.create(
                new CreateUserRequest(
                        "admin.concurrent.two",
                        "password-123",
                        "Admin",
                        "Two",
                        null,
                        "Europe/Moscow",
                        UserGlobalRole.SYSTEM_ADMIN,
                        true,
                        List.of()),
                actorOne);
        var actorTwo = authentication(adminTwo.username());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Result> first = executor.submit(() -> disableAfter(ready, start, adminTwo, actorOne));
            Future<Result> second = executor.submit(() -> disableAfter(ready, start, adminOne, actorTwo));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(Result.SUCCESS, Result.CONFLICT);
        }

        assertThat(subjects.countByPrincipalTypeAndGlobalRoleAndActiveTrue(
                        PrincipalType.USER, UserGlobalRole.SYSTEM_ADMIN))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from auth_subject subject
                        join event_stream_head stream
                          on stream.aggregate_type='USER_AUTHORIZATION'
                         and stream.aggregate_id=subject.id::text
                         and stream.current_version=subject.version
                        where subject.id in (?, ?)
                        """,
                        Integer.class,
                        adminOne.id(),
                        adminTwo.id()))
                .isEqualTo(2);
    }

    private Result disableAfter(
            CountDownLatch ready,
            CountDownLatch start,
            dev.buhanzaz.rwms.auth.api.AdminUserResponse target,
            UsernamePasswordAuthenticationToken actor) throws InterruptedException {
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        try {
            users.update(
                    target.id(),
                    new UpdateUserRequest(
                            target.version(),
                            target.username(),
                            target.firstName(),
                            target.lastName(),
                            target.email(),
                            target.timeZoneId(),
                            false,
                            target.globalRole()),
                    actor);
            return Result.SUCCESS;
        } catch (ResponseStatusException exception) {
            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            return Result.CONFLICT;
        }
    }

    private UsernamePasswordAuthenticationToken authentication(String username) {
        return UsernamePasswordAuthenticationToken.authenticated(username, "", List.of());
    }

    private enum Result {
        SUCCESS,
        CONFLICT
    }
}
