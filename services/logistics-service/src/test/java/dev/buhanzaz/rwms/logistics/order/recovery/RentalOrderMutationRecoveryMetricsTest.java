package dev.buhanzaz.rwms.logistics.order.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.State;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class RentalOrderMutationRecoveryMetricsTest {

  @Test
  void exposesPendingAndQuarantinedCountsWithoutIdentityLabels() {
    RentalOrderMutationCommandRepository commands =
        mock(RentalOrderMutationCommandRepository.class);
    when(commands.countByState(State.PENDING)).thenReturn(3L);
    when(commands.countByState(State.QUARANTINED)).thenReturn(2L);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new RentalOrderMutationRecoveryMetrics(registry, commands);

    assertThat(
            registry
                .get("rwms.logistics.rental_order_mutation.recovery.pending")
                .gauge()
                .value())
        .isEqualTo(3);
    assertThat(
            registry
                .get("rwms.logistics.rental_order_mutation.recovery.quarantined")
                .gauge()
                .value())
        .isEqualTo(2);
  }
}
