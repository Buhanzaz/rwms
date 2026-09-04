package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.logistics.customer.repository.CustomerBookingMutationRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies native PostgreSQL timestamp and due-claim queries through both Spring Data proxies. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.task.scheduling.enabled=false",
      "rwms.platform.kafka.enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CustomerDatabaseTimestampRepositoryIntegrationTest {
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired CustomerRentalSessionRepository sessions;
  @Autowired CustomerBookingMutationRepository mutations;

  @MockitoBean LogisticsDependencyGateway dependencies;

  @Test
  @Transactional
  void nativeTimestampAndSqlLockedClaimQueriesExecuteThroughBothRepositoryProxies() {
    Instant earliestExpected = Instant.now().minusSeconds(5);

    Instant sessionTimestamp = sessions.currentDatabaseTimestamp();
    Instant mutationTimestamp = mutations.currentDatabaseTimestamp();

    Instant latestExpected = Instant.now().plusSeconds(5);
    assertThat(sessionTimestamp).isNotNull().isBetween(earliestExpected, latestExpected);
    assertThat(mutationTimestamp).isNotNull().isBetween(earliestExpected, latestExpected);

    OffsetDateTime dueAt = OffsetDateTime.ofInstant(latestExpected, ZoneOffset.UTC);
    assertThat(sessions.findDueCheckoutRecoveryForUpdate(dueAt, 1)).isEmpty();
    assertThat(mutations.findDueForUpdate(dueAt, 1)).isEmpty();
  }
}
