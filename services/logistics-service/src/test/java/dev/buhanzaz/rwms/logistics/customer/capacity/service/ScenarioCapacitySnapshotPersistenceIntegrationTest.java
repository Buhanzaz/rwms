package dev.buhanzaz.rwms.logistics.customer.capacity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.logistics.customer.capacity.repository.ScenarioCapacityCommandReceiptRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.ScenarioCapacityJobRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.ScenarioCapacitySnapshotRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacityJobRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReplacePlanningCapacitySnapshotRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
/**
 * Proves generation-fenced, immutable-receipt scenario replacement against the Flyway/JPA
 * PostgreSQL model, including an A-to-B-to-A source revision sequence.
 */
class ScenarioCapacitySnapshotPersistenceIntegrationTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000601");
  private static final UUID SOURCE_JOB =
      UUID.fromString("00000000-0000-0000-0000-000000000602");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired ScenarioCapacitySnapshotService service;
  @Autowired ScenarioCapacitySnapshotRepository snapshots;
  @Autowired ScenarioCapacityCommandReceiptRepository receipts;
  @Autowired ScenarioCapacityJobRepository jobs;
  @Autowired JdbcTemplate jdbc;

  @MockitoBean LogisticsDependencyGateway dependencies;

  @BeforeEach
  void resetCapacityProjection() {
    jdbc.execute(
        "truncate table customer_scenario_capacity_command_receipt, "
            + "customer_scenario_capacity_snapshot cascade");
  }

  @Test
  void replacesOneWarehouseSnapshotAndReusesAStableSourceJobIdentity() {
    var created =
        service.replace(
            UUID.fromString("00000000-0000-0000-0000-000000000603"),
            UUID.fromString("00000000-0000-0000-0000-000000000604"),
            request(1, "a".repeat(64), LocalTime.of(9, 0), 1));
    var replaced =
        service.replace(
            UUID.fromString("00000000-0000-0000-0000-000000000605"),
            UUID.fromString("00000000-0000-0000-0000-000000000606"),
            request(2, "b".repeat(64), LocalTime.of(12, 0), 2));
    var returned =
        service.replace(
            UUID.fromString("00000000-0000-0000-0000-000000000603"),
            UUID.fromString("00000000-0000-0000-0000-000000000609"),
            request(3, "a".repeat(64), LocalTime.of(15, 0), 3));
    var delayedReplay =
        service.replace(
            UUID.fromString("00000000-0000-0000-0000-000000000603"),
            UUID.fromString("00000000-0000-0000-0000-000000000604"),
            request(1, "a".repeat(64), LocalTime.of(9, 0), 1));

    assertThatThrownBy(
            () ->
                service.replace(
                    UUID.fromString("00000000-0000-0000-0000-000000000607"),
                    UUID.fromString("00000000-0000-0000-0000-000000000608"),
                    request(1, "c".repeat(64), LocalTime.of(15, 0), 1)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            exception ->
                assertThat(exception.code())
                    .isEqualTo("PLANNING_CAPACITY_GENERATION_STALE"));

    assertThat(created.replayed()).isFalse();
    assertThat(replaced.replayed()).isFalse();
    assertThat(returned.replayed()).isFalse();
    assertThat(delayedReplay.replayed()).isTrue();
    assertThat(delayedReplay.sourceRevision()).isEqualTo("a".repeat(64));
    assertThat(replaced.version()).isGreaterThan(created.version());
    assertThat(returned.version()).isGreaterThan(replaced.version());
    assertThat(replaced.sourceGeneration()).isEqualTo(2);
    assertThat(returned.sourceGeneration()).isEqualTo(3);
    assertThat(snapshots.count()).isEqualTo(1);
    assertThat(snapshots.findAll())
        .singleElement()
        .satisfies(snapshot -> assertThat(snapshot.getSourceRevision()).isEqualTo("a".repeat(64)));
    assertThat(receipts.count()).isEqualTo(3);
    assertThat(jobs.findCapacityWorkload(WAREHOUSE, LocalDate.of(2026, 8, 29)))
        .singleElement()
        .satisfies(
            job -> {
              assertThat(job.getSourceJobId()).isEqualTo(SOURCE_JOB);
              assertThat(job.getCabinCount()).isEqualTo(3);
              assertThat(job.getWindowStart()).isEqualTo(LocalTime.of(15, 0));
            });
  }

  private static ReplacePlanningCapacitySnapshotRequest request(
      long sourceGeneration, String revision, LocalTime windowStart, int cabinCount) {
    return new ReplacePlanningCapacitySnapshotRequest(
        WAREHOUSE,
        sourceGeneration,
        revision,
        List.of(
            new PlanningCapacityJobRequest(
                SOURCE_JOB,
                LocalDate.of(2026, 8, 29),
                BigDecimal.valueOf(55.75),
                BigDecimal.valueOf(37.61),
                cabinCount,
                windowStart,
                windowStart.plusHours(3),
                30)));
  }
}
