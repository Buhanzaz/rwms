package dev.buhanzaz.rwms.logistics.service.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseOperationMarkStore;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the isolated warehouse adapters retain PostgreSQL admission idempotency, blocker reads,
 * mark conflict checks, and fenced claiming semantics.
 */
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
class LogisticsWarehousePersistenceIntegrationTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-00000000b801");
  private static final UUID OPERATION =
      UUID.fromString("00000000-0000-0000-0000-00000000b802");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired LogisticsWarehouseAdmissionPersistence admissions;
  @Autowired LogisticsWarehouseLifecycleBlockerReader blockers;
  @Autowired LogisticsWarehouseOperationMarkStore marks;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transactions;

  @MockitoBean LogisticsDependencyGateway dependencies;

  @BeforeEach
  void resetDatabase() {
    jdbc.execute(
        """
        truncate table
          logistics_warehouse_readiness_fence,
          logistics_warehouse_admission_intent,
          warehouse_operation_mark_recovery_audit,
          warehouse_operation_mark_outbox
        cascade
        """);
  }

  @Test
  void admissionInsertIsIdempotentAndTheSingleSnapshotBlockerSeesThePendingMark() {
    OffsetDateTime now = admissions.databaseNow();
    transactions.executeWithoutResult(
        status -> {
          assertThat(admissions.insertReservedIfAbsent(OPERATION, WAREHOUSE, "OUTGOING", now.plusMinutes(2), now))
              .isEqualTo(1);
          assertThat(admissions.insertReservedIfAbsent(OPERATION, WAREHOUSE, "OUTGOING", now.plusMinutes(2), now))
              .isZero();
          assertThat(admissions.admissionForUpdate(OPERATION, WAREHOUSE))
              .extracting(
                  LogisticsWarehouseAdmissionPersistence.AdmissionRow::direction,
                  LogisticsWarehouseAdmissionPersistence.AdmissionRow::state)
              .containsExactly("OUTGOING", "RESERVED");
          assertThat(admissions.admitReserved(OPERATION, WAREHOUSE, "OUTGOING", 7, now)).isEqualTo(1);
          admissions.deleteAdmission(OPERATION, WAREHOUSE);
        });

    assertThat(blockers.hasLocalBlockers(WAREHOUSE)).isFalse();

    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
    transactions.executeWithoutResult(status -> marks.enqueue(WAREHOUSE, OPERATION, occurredAt, null));
    transactions.executeWithoutResult(status -> marks.enqueue(WAREHOUSE, OPERATION, occurredAt, null));
    assertThat(blockers.hasLocalBlockers(WAREHOUSE)).isTrue();
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status -> marks.enqueue(WAREHOUSE, OPERATION, occurredAt.plusSeconds(1), null)))
        .isInstanceOf(LogisticsConflictException.class);

    LogisticsWarehouseOperationMarkStore.WorkItem claimed =
        marks.claimNext(Duration.ofSeconds(30)).orElseThrow();
    assertThat(claimed.operationId()).isEqualTo(OPERATION);
    assertThat(claimed.warehouseId()).isEqualTo(WAREHOUSE);
    marks.confirmed(claimed);
    assertThat(
            jdbc.queryForObject(
                "select state from warehouse_operation_mark_outbox where warehouse_id=? and operation_id=?",
                String.class,
                WAREHOUSE,
                OPERATION))
        .isEqualTo("CONFIRMED");
  }
}
