package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "rwms.platform.kafka.enabled=false",
    "rwms.maintenance.task-reconciliation.initial-delay=1h",
    "rwms.maintenance.task-reconciliation.delay=1h",
    "AUTH_ISSUER=http://auth.test",
    "PANEL_ORIGIN=http://panel.test"
})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MaintenanceMediaOwnerProofIntegrationTest {
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MaintenanceReconciliationStore reconciliations;
  @Autowired MaintenanceApplicationService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @MockitoBean MaintenanceDependencyGateway dependencies;

  private TransactionTemplate transactions;

  @BeforeEach
  void resetDatabase() {
    transactions = new TransactionTemplate(transactionManager);
    jdbc.execute("truncate table integration_reconciliation");
  }

  @Test
  void exactSourceReplayIsIdempotentWhileVersionsAndOwnersRemainIsolated() {
    UUID ownerId = UUID.randomUUID();
    UUID otherOwnerId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID sourceId = ownerId;

    List<MaintenanceReconciliationStore.MediaProofEnqueueResult> results =
        transactions.execute(status -> {
          var first = reconciliations.enqueueMediaOwnerProof(
              "MAINTENANCE_REPAIR", ownerId, warehouseId, sourceId, 0, true);
          var replay = reconciliations.enqueueMediaOwnerProof(
              "MAINTENANCE_REPAIR", ownerId, warehouseId, sourceId, 0, true);
          var next = reconciliations.enqueueMediaOwnerProof(
              "MAINTENANCE_REPAIR", ownerId, warehouseId, sourceId, 3, true);
          var isolated = reconciliations.enqueueMediaOwnerProof(
              "MAINTENANCE_REPAIR", otherOwnerId, warehouseId, otherOwnerId, 0, true);
          return List.of(first, replay, next, isolated);
        });

    assertThat(results).isNotNull();
    assertThat(results.get(0).replayed()).isFalse();
    assertThat(results.get(1).replayed()).isTrue();
    assertThat(results.get(1).reconciliationId()).isEqualTo(results.get(0).reconciliationId());
    assertThat(results.get(0).ownerRevision()).isZero();
    assertThat(results.get(0).aggregateVersion()).isZero();
    assertThat(results.get(2).ownerRevision()).isOne();
    assertThat(results.get(2).aggregateVersion()).isEqualTo(3);
    assertThat(results.get(3).ownerRevision()).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from integration_reconciliation where dependency_type='MEDIA'",
        Integer.class)).isEqualTo(3);

    assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
        reconciliations.enqueueMediaOwnerProof(
            "MAINTENANCE_REPAIR",
            ownerId,
            UUID.randomUUID(),
            sourceId,
            3,
            true)))
        .isInstanceOf(MaintenanceConflictException.class)
        .extracting(exception -> ((MaintenanceConflictException) exception).code())
        .isEqualTo("MAINTENANCE_STATE_CONFLICT");
  }

  @Test
  void existingAggregateStartsItsInitialOwnerProofAtZeroAndKeepsActualSourceVersions() {
    UUID ownerId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID nextSourceId = UUID.randomUUID();

    List<MaintenanceReconciliationStore.MediaProofEnqueueResult> results =
        transactions.execute(status -> {
          var initial = reconciliations.enqueueMediaOwnerProof(
              "MAINTENANCE_REPAIR", ownerId, warehouseId, ownerId, 7, true);
          var next = reconciliations.enqueueMediaOwnerProof(
              "MAINTENANCE_REPAIR", ownerId, warehouseId, ownerId, 8, true);
          var changedSource = reconciliations.enqueueMediaOwnerProof(
              "MAINTENANCE_REPAIR", ownerId, warehouseId, nextSourceId, 2, true);
          return List.of(initial, next, changedSource);
        });

    assertThat(results).isNotNull();
    assertThat(results)
        .extracting(
            MaintenanceReconciliationStore.MediaProofEnqueueResult::ownerRevision,
            MaintenanceReconciliationStore.MediaProofEnqueueResult::aggregateVersion)
        .containsExactly(tuple(0L, 0L), tuple(1L, 8L), tuple(2L, 9L));
    assertThat(jdbc.queryForList("""
        select media_owner_revision,media_aggregate_version,media_source_version,
          response_snapshot->>'aggregateVersion' as payload_aggregate_version
        from integration_reconciliation
        where dependency_type='MEDIA' and media_owner_id=?
        order by media_owner_revision
        """, ownerId))
        .containsExactly(
            Map.of(
                "media_owner_revision", 0L,
                "media_aggregate_version", 0L,
                "media_source_version", 7L,
                "payload_aggregate_version", "0"),
            Map.of(
                "media_owner_revision", 1L,
                "media_aggregate_version", 8L,
                "media_source_version", 8L,
                "payload_aggregate_version", "8"),
            Map.of(
                "media_owner_revision", 2L,
                "media_aggregate_version", 9L,
                "media_source_version", 2L,
                "payload_aggregate_version", "9"));
  }

  @Test
  void transientMediaOutageRetriesTheSameProofBeforeDeliveringTheNextRevision() {
    UUID ownerId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    transactions.executeWithoutResult(status -> {
      reconciliations.enqueueMediaOwnerProof(
          "MAINTENANCE_ACCEPTANCE", ownerId, warehouseId, ownerId, 4, true);
      reconciliations.enqueueMediaOwnerProof(
          "MAINTENANCE_ACCEPTANCE", ownerId, warehouseId, ownerId, 5, true);
    });
    when(dependencies.upsertMediaOwnerProof(any()))
        .thenThrow(new MaintenanceDependencyException(
            HttpStatus.SERVICE_UNAVAILABLE, "media unavailable"))
        .thenAnswer(invocation -> invocation.getArgument(0));

    assertThat(service.reconcileOneMediaOwnerProof()).isTrue();
    assertThat(jdbc.queryForList("""
        select media_owner_revision,state,attempt_count
        from integration_reconciliation
        where dependency_type='MEDIA'
        order by media_owner_revision
        """))
        .containsExactly(
            Map.of("media_owner_revision", 0L, "state", "RETRY_PENDING", "attempt_count", 1),
            Map.of("media_owner_revision", 1L, "state", "PENDING", "attempt_count", 0));
    assertThat(service.reconcileOneMediaOwnerProof()).isFalse();

    jdbc.update("""
        update integration_reconciliation set next_attempt_at=clock_timestamp()
        where dependency_type='MEDIA' and media_owner_revision=0
        """);
    assertThat(service.reconcileOneMediaOwnerProof()).isTrue();
    assertThat(service.reconcileOneMediaOwnerProof()).isTrue();
    assertThat(service.reconcileOneMediaOwnerProof()).isFalse();

    ArgumentCaptor<MaintenanceDependencyGateway.MediaOwnerProof> captor =
        ArgumentCaptor.forClass(MaintenanceDependencyGateway.MediaOwnerProof.class);
    verify(dependencies, times(3)).upsertMediaOwnerProof(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(MaintenanceDependencyGateway.MediaOwnerProof::ownerRevision)
        .containsExactly(0L, 0L, 1L);
    assertThat(captor.getAllValues().get(0).proofEventId())
        .isEqualTo(captor.getAllValues().get(1).proofEventId());
    assertThat(jdbc.queryForList("""
        select media_owner_revision,state,attempt_count
        from integration_reconciliation
        where dependency_type='MEDIA'
        order by media_owner_revision
        """))
        .allSatisfy(row -> assertThat(row.get("state")).isEqualTo("CONFIRMED"));
  }
}
