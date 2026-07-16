package dev.buhanzaz.rwms.auth.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.zaxxer.hikari.HikariDataSource;
import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "rwms.platform.kafka.enabled=false",
    "spring.datasource.hikari.connection-timeout=2000",
    "spring.datasource.hikari.validation-timeout=1000"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthDatabaseOutageRecoveryIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withNetwork(org.testcontainers.containers.Network.SHARED)
            .withNetworkAliases("auth-outage-postgres");

    @Container
    static final ToxiproxyContainer toxiproxy = new ToxiproxyContainer(
                    DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
            .withNetwork(org.testcontainers.containers.Network.SHARED);

    static ToxiproxyContainer.ContainerProxy databaseProxy;

    @DynamicPropertySource
    static void databaseTimeouts(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:postgresql://" + toxiproxy.getHost() + ':'
                        + databaseProxy().getProxyPort() + "/test?connectTimeout=2&socketTimeout=2");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AuthProjectionWriter projectionWriter;
    @Autowired AuthEventFactFactory facts;
    @Autowired AuthEventStore eventStore;
    @Autowired AuthOutboxStore outbox;
    @Autowired AuthInboxProcessor inbox;
    @Autowired HikariDataSource dataSource;

    @Test
    @Timeout(55)
    void databaseCutCannotCommitInboxOrLeaseThenSameDurableRowsRecoverExactlyOnce() {
        markExistingOutboxPublished();
        UUID subjectId = createAggregate();
        byte[] envelope = envelope(subjectId);

        databaseProxy().setConnectionCut(true);
        dataSource.getHikariPoolMXBean().softEvictConnections();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                assertThatThrownBy(() -> outbox.claim("outage-instance", Duration.ofSeconds(30)))
                        .isInstanceOfAny(DataAccessException.class, RuntimeException.class);
            });
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                assertThatThrownBy(() -> inbox.process(
                                envelope, AuthAggregateType.USER_AUTHORIZATION))
                        .isInstanceOf(RuntimeException.class);
            });
        } finally {
            databaseProxy().setConnectionCut(false);
            dataSource.getHikariPoolMXBean().softEvictConnections();
        }

        awaitDatabaseRecovery();
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from outbox_event
                         where aggregate_id=? and status='PENDING'
                           and lease_owner is null and lease_token is null and lease_until is null
                        """,
                        Integer.class,
                        subjectId.toString()))
                .isOne();
        assertThat(jdbc.queryForObject(
                        "select count(*) from inbox_message where aggregate_id=?",
                        Integer.class,
                        subjectId.toString()))
                .isZero();
        AuthOutboxStore.Claim claim = outbox.claim("recovered-instance", Duration.ofSeconds(30))
                .orElseThrow();
        assertThat(claim.aggregateId()).isEqualTo(subjectId.toString());
        assertThat(outbox.published(claim.eventId(), claim.leaseToken())).isTrue();
        assertThat(outbox.claim("duplicate-instance", Duration.ofSeconds(30))).isEmpty();

        inbox.process(envelope, AuthAggregateType.USER_AUTHORIZATION);
        inbox.process(envelope, AuthAggregateType.USER_AUTHORIZATION);
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from inbox_message
                         where consumer_group='auth-shadow-v1' and aggregate_id=? and status='PROCESSED'
                        """,
                        Integer.class,
                        subjectId.toString()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select status from outbox_event where aggregate_id=?",
                        String.class,
                        subjectId.toString()))
                .isEqualTo("PUBLISHED");
    }

    private void markExistingOutboxPublished() {
        jdbc.update(
                """
                update outbox_event
                   set status='PUBLISHED', published_at=coalesce(published_at, clock_timestamp()),
                       lease_owner=null, lease_token=null, lease_until=null,
                       dlt_at=null, last_error_code=null
                 where status <> 'PUBLISHED'
                """);
    }

    private UUID createAggregate() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            AuthSubject subject = projectionWriter.insertUser(
                    "database-outage-" + UUID.randomUUID(),
                    "{noop}test-password",
                    null,
                    null,
                    null,
                    "Europe/Moscow",
                    UserGlobalRole.VIEWER,
                    true);
            eventStore.initialize(
                    AuthAggregateType.USER_AUTHORIZATION,
                    subject.getId(),
                    subject.getVersion(),
                    AuthEventTypes.USER_CREATED,
                    facts.userAuthorization(subject),
                    null);
            return subject.getId();
        });
    }

    private byte[] envelope(UUID subjectId) {
        String body = jdbc.queryForObject(
                "select envelope_body::text from outbox_event where aggregate_id=?",
                String.class,
                subjectId.toString());
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private void awaitDatabaseRecovery() {
        Instant deadline = Instant.now().plusSeconds(15);
        RuntimeException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for PostgreSQL recovery", exception);
            }
        }
        throw new IllegalStateException("PostgreSQL did not recover after network restoration", lastFailure);
    }

    private static synchronized ToxiproxyContainer.ContainerProxy databaseProxy() {
        if (databaseProxy == null) {
            databaseProxy = toxiproxy.getProxy(postgres, PostgreSQLContainer.POSTGRESQL_PORT);
        }
        return databaseProxy;
    }
}
