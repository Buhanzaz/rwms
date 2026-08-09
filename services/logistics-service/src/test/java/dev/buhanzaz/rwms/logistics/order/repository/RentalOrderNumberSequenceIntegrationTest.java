package dev.buhanzaz.rwms.logistics.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Proves the provider-neutral JPQL function invocation reaches the PostgreSQL order sequence. */
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
class RentalOrderNumberSequenceIntegrationTest {
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired RentalOrderRepository orders;

  @MockitoBean LogisticsDependencyGateway dependencies;

  @Test
  void nextOrderNumberUsesThePostgreSqlSequenceThroughJpqlFunction() {
    long first = orders.nextOrderNumber();
    long second = orders.nextOrderNumber();

    assertThat(second).isEqualTo(first + 1);
  }
}
