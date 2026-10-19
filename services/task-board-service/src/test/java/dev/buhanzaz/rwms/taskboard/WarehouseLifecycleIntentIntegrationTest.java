package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardWarehouseLifecycleFence;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleFence.AdmissionPermit;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.OperationDirection;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.ReadinessWork;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleIntentStore;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleIntentStore.ReadinessReservation;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class WarehouseLifecycleIntentIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired TaskBoardWarehouseLifecycleFence fence;
  @Autowired WarehouseLifecycleIntentStore intents;
  @Autowired TestWarehouseLifecycleGateway warehouseLifecycle;

  @BeforeEach
  void setUp() {
    cleanTaskBoardFixtures(jdbc);
    warehouseLifecycle.reset();
  }

  @Test
  void admittedMutationPermitBlocksReadinessUntilItsLocalMutationCommits() {
    UUID warehouseId = UUID.randomUUID();
    AdmissionPermit permit = fence.acquireAdmission(warehouseId, OperationDirection.INCOMING);

    assertThat(intentCount()).isOne();
    assertThat(fence.confirmReadinessIfNoLiveWork(work(warehouseId))).isFalse();
    assertThat(warehouseLifecycle.confirmations()).isEmpty();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> fence.terminalizeAdmission(permit));

    assertThat(intentCount()).isZero();
    assertThat(fence.confirmReadinessIfNoLiveWork(work(warehouseId))).isTrue();
    assertThat(warehouseLifecycle.confirmations()).hasSize(1);
    assertThat(warehouseLifecycle.confirmationTransactionStates()).containsOnly(false);
  }

  @Test
  void rollbackOfLocalMutationCleansAdmittedPermitInANewTransaction() {
    UUID warehouseId = UUID.randomUUID();
    AdmissionPermit permit = fence.acquireAdmission(warehouseId, OperationDirection.INCOMING);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              fence.terminalizeAdmission(permit);
              status.setRollbackOnly();
            });

    assertThat(intentCount()).isZero();
  }

  @Test
  void expiredAdmissionCannotBeConsumedByTheLocalTaskMutation() {
    UUID warehouseId = UUID.randomUUID();
    AdmissionPermit permit = fence.acquireAdmission(warehouseId, OperationDirection.INCOMING);
    expire(permit);

    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> fence.terminalizeAdmission(permit)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("истекла");

    assertThat(intentCount()).isZero();
    AdmissionPermit retry = fence.acquireAdmission(warehouseId, OperationDirection.INCOMING);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> fence.terminalizeAdmission(retry));
  }

  @Test
  void readinessRemovesExpiredIntentUnderItsWarehouseLock() {
    UUID warehouseId = UUID.randomUUID();
    AdmissionPermit permit = fence.acquireAdmission(warehouseId, OperationDirection.INCOMING);
    expire(permit);

    assertThat(fence.confirmReadinessIfNoLiveWork(work(warehouseId))).isTrue();
    assertThat(intentCount()).isZero();
  }

  @Test
  void committedReadinessBarrierRejectsAConcurrentAdmissionUntilTheRemoteResultIsKnown() {
    UUID warehouseId = UUID.randomUUID();
    ReadinessReservation readiness = intents.reserveReadinessIfClear(work(warehouseId));
    assertThat(readiness).isNotNull();

    assertThatThrownBy(() -> fence.acquireAdmission(warehouseId, OperationDirection.INCOMING))
        .isInstanceOf(ConflictException.class);
    assertThat(warehouseLifecycle.admissions()).isEmpty();

    intents.abandonReadiness(readiness);
    assertThat(fence.acquireAdmission(warehouseId, OperationDirection.INCOMING)).isNotNull();
  }

  private int intentCount() {
    return jdbc.queryForObject(
        "select count(*) from task_board_warehouse_lifecycle_intent", Integer.class);
  }

  private void expire(AdmissionPermit permit) {
    jdbc.update(
        """
        update task_board_warehouse_lifecycle_intent
           set expires_at = clock_timestamp() - interval '1 second'
         where operation_id = ?
        """,
        permit.operationId());
  }

  private static ReadinessWork work(UUID warehouseId) {
    return new ReadinessWork(warehouseId, 1L, "DRAINING");
  }
}
