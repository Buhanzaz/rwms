package dev.buhanzaz.rwms.logistics.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerPaymentExpiryService;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationCodec;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.Operation;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationCommandRepository;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderMutationLocalStore.RecoveryFailure;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RentalOrderMutationLocalStoreTest {

  @Test
  void eighthTransientFailureReachesTerminalQuarantine() {
    FailureFixture fixture = failureFixture(7);

    RecoveryFailure failure =
        fixture.store.fail(fixture.commandId, fixture.leaseToken, "TRANSIENT", false);

    assertThat(failure.attemptCount()).isEqualTo(8);
    assertThat(failure.quarantined()).isTrue();
    assertThat(failure.nextAttemptAt()).isNull();
    verify(fixture.command)
        .fail(fixture.leaseToken, "TRANSIENT", fixture.now, null, true);
  }

  @Test
  void firstNonRetryableFailureReachesTerminalQuarantine() {
    FailureFixture fixture = failureFixture(0);

    RecoveryFailure failure =
        fixture.store.fail(fixture.commandId, fixture.leaseToken, "REJECTED", true);

    assertThat(failure.attemptCount()).isEqualTo(1);
    assertThat(failure.quarantined()).isTrue();
    verify(fixture.command)
        .fail(fixture.leaseToken, "REJECTED", fixture.now, null, true);
  }

  @Test
  void transientFailureUsesBoundedExponentialRetryBeforeBoundary() {
    FailureFixture fixture = failureFixture(0);

    RecoveryFailure failure =
        fixture.store.fail(fixture.commandId, fixture.leaseToken, "TRANSIENT", false);

    assertThat(failure.attemptCount()).isEqualTo(1);
    assertThat(failure.quarantined()).isFalse();
    assertThat(failure.nextAttemptAt()).isEqualTo(fixture.now.plusSeconds(2));
    verify(fixture.command)
        .fail(
            fixture.leaseToken,
            "TRANSIENT",
            fixture.now,
            fixture.now.plusSeconds(2),
            false);
  }

  private static FailureFixture failureFixture(int attemptCount) {
    UUID commandId = UUID.randomUUID();
    UUID leaseToken = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    OffsetDateTime now =
        OffsetDateTime.of(2026, 8, 31, 12, 0, 0, 123_456_000, ZoneOffset.UTC);
    RentalOrderMutationCommandRepository commands =
        mock(RentalOrderMutationCommandRepository.class);
    RentalOrderMutationCommand command = mock(RentalOrderMutationCommand.class);
    RentalOrder order = mock(RentalOrder.class);
    when(commands.findForUpdate(commandId)).thenReturn(Optional.of(command));
    when(commands.currentDatabaseTimestamp()).thenReturn(now.toInstant());
    when(command.hasLiveLease(leaseToken, now)).thenReturn(true);
    when(command.getAttemptCount()).thenReturn(attemptCount);
    when(command.getId()).thenReturn(commandId);
    when(command.getOrder()).thenReturn(order);
    when(order.getId()).thenReturn(orderId);
    when(command.getOperation()).thenReturn(Operation.CANCEL_ORDER);
    RentalOrderMutationLocalStore store =
        new RentalOrderMutationLocalStore(
            commands,
            mock(RentalOrderCommandStore.class),
            mock(RentalOrderReservationService.class),
            mock(RentalOrderMutationCodec.class),
            mock(RentalOrderRepository.class),
            mock(CustomerPaymentExpiryService.class));
    return new FailureFixture(commandId, leaseToken, now, command, store);
  }

  /** Mocks one live leased command at a fixed database timestamp. */
  private record FailureFixture(
      UUID commandId,
      UUID leaseToken,
      OffsetDateTime now,
      RentalOrderMutationCommand command,
      RentalOrderMutationLocalStore store) {}
}
