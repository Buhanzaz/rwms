package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcomeTarget;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Verifies source-owned task readback recovery for authoritative inventory supersession. */
class InventoryAuthoritativeOutcomeRemoteGatewayTest {

  @Test
  void treatsAnAbsentStableExternalTaskAsTerminalNoTaskTruth() {
    MaintenanceDependencyGateway dependencies = mock(MaintenanceDependencyGateway.class);
    InventoryAuthoritativeOutcomeTarget target = mock(InventoryAuthoritativeOutcomeTarget.class);
    UUID externalTaskId = UUID.randomUUID();
    when(target.getTaskExternalId()).thenReturn(externalTaskId);
    when(dependencies.getTask(externalTaskId))
        .thenThrow(
            new MaintenanceDependencyException(
                HttpStatus.NOT_FOUND, "Source-owned task does not exist"));
    InventoryAuthoritativeOutcomeRemoteGateway gateway =
        new InventoryAuthoritativeOutcomeRemoteGateway(dependencies);

    InventoryAuthoritativeOutcomeRemoteGateway.TaskCancellation truth = gateway.task(target);

    assertThat(truth.outcome()).isEqualTo("NOT_FOUND");
    assertThat(truth.taskVersion()).isZero();
    assertThat(InventoryAuthoritativeOutcomeRemoteGateway.terminalTask(truth.outcome())).isTrue();
  }
}
