package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.WorkerCredentialOperationCoordinator;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "rwms.task-board.credential-lock-max-connections=2")
@ActiveProfiles("test")
class WorkerCredentialOperationCoordinatorIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired WorkerCredentialOperationCoordinator coordinator;
  @Autowired JdbcTemplate jdbc;

  @Test
  void boundedPhysicalConnectionsRejectOverflowAndPermitReuseAfterClose() {
    var first = coordinator.tryAcquire(UUID.randomUUID());
    var second = coordinator.tryAcquire(UUID.randomUUID());
    try {
      assertThatThrownBy(() -> coordinator.tryAcquire(UUID.randomUUID()))
          .isInstanceOf(ConflictException.class);
      first.close();
      try (var reused = coordinator.tryAcquire(UUID.randomUUID())) {
        assertThat(reused.databaseNow()).isNotNull();
      }
    } finally {
      first.close();
      second.close();
    }
  }

  @Test
  void terminatedLockBackendCannotStrandLockOrConnectionPermit() {
    UUID workerId = UUID.randomUUID();
    var interrupted = coordinator.tryAcquire(workerId);
    Integer backendPid =
        jdbc.queryForObject(
            "select pid from pg_stat_activity where application_name = ?",
            Integer.class,
            WorkerCredentialOperationCoordinator.applicationName(workerId));
    assertThat(
            jdbc.queryForObject(
                "select pg_terminate_backend(?)", Boolean.class, backendPid))
        .isTrue();

    interrupted.close();

    try (var reacquired = coordinator.tryAcquire(workerId);
        var secondSlot = coordinator.tryAcquire(UUID.randomUUID())) {
      assertThat(reacquired.databaseNow()).isNotNull();
      assertThat(secondSlot.databaseNow()).isNotNull();
    }
  }
}
