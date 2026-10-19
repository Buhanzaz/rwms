package dev.buhanzaz.rwms.logistics.order.recovery;

import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.State;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Exposes fixed-cardinality counts for automatic and operator-reviewed rental-order mutation
 * recovery. Database errors surface as NaN instead of a fabricated empty queue.
 */
@Component
public final class RentalOrderMutationRecoveryMetrics {

  /** Registers read-only pending and quarantined command gauges. */
  public RentalOrderMutationRecoveryMetrics(
      MeterRegistry registry, RentalOrderMutationCommandRepository commands) {
    register(
        registry,
        commands,
        State.PENDING,
        "rwms.logistics.rental_order_mutation.recovery.pending",
        "Rental-order cancel/remove commands retained for automatic recovery");
    register(
        registry,
        commands,
        State.QUARANTINED,
        "rwms.logistics.rental_order_mutation.recovery.quarantined",
        "Rental-order cancel/remove commands requiring reviewed recovery");
  }

  private static void register(
      MeterRegistry registry,
      RentalOrderMutationCommandRepository commands,
      State state,
      String name,
      String description) {
    Gauge.builder(name, commands, repository -> count(repository, state))
        .description(description)
        .register(registry);
  }

  private static double count(
      RentalOrderMutationCommandRepository commands, State state) {
    try {
      return commands.countByState(state);
    } catch (DataAccessException exception) {
      return Double.NaN;
    }
  }
}
