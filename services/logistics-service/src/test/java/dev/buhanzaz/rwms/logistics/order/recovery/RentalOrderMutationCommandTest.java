package dev.buhanzaz.rwms.logistics.order.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.Operation;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RentalOrderMutationCommandTest {

  @Test
  void automaticExpiryStartsWithARecoverableReadBeforeAnyReleaseCanBeRecorded() {
    RentalOrder order = mock(RentalOrder.class);
    org.mockito.Mockito.when(order.getPaymentState()).thenReturn(RentalOrderPaymentState.EXPIRING);
    OffsetDateTime now = OffsetDateTime.parse("2026-09-05T14:00:00Z");
    RentalOrderMutationCommand command =
        RentalOrderMutationCommand.start(
            order,
            Operation.EXPIRE_UNPAID_ORDER,
            null,
            4,
            RentalOrderMutationCommand.AUTOMATIC_RELEASE_ACTOR_ID,
            "LOGISTICS_SERVICE",
            UUID.randomUUID(),
            "a".repeat(64),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            now);
    assertThat(command.getStep()).isEqualTo(RentalOrderMutationCommand.Step.READ_UNITS);
    assertThat(command.getIntentJson()).isNull();
    UUID token = UUID.randomUUID();
    assertThat(command.claim(token, now, now.plusMinutes(1))).isTrue();
    assertThatThrownBy(() -> command.recordReleasedUnits(token, "{}", now))
        .isInstanceOf(IllegalStateException.class);
    command.recordIntent(token, "{\"activeUnits\":[],\"remainingComposition\":[]}", now);
    assertThat(command.getStep()).isEqualTo(RentalOrderMutationCommand.Step.RELEASE_UNITS);
    assertThatThrownBy(() -> command.recordIntent(token, "{}", now))
        .isInstanceOf(IllegalStateException.class);
    command.recordReleasedUnits(token, "{}", now);
    assertThat(command.getStep()).isEqualTo(RentalOrderMutationCommand.Step.RELEASE_EQUIPMENT);
    command.recordEquipmentReceipt(token, "{}", now);
    command.complete(token, now);
    assertThat(command.getState()).isEqualTo(RentalOrderMutationCommand.State.COMPLETED);
  }

  @Test
  void automaticExpiryCannotImpersonateAManagerOrStartWithoutTheOrderFence() {
    RentalOrder order = mock(RentalOrder.class);
    OffsetDateTime now = OffsetDateTime.parse("2026-09-05T14:00:00Z");
    assertThatThrownBy(
            () ->
                RentalOrderMutationCommand.start(
                    order,
                    Operation.EXPIRE_UNPAID_ORDER,
                    null,
                    4,
                    UUID.randomUUID(),
                    "RENTAL_MANAGER",
                    UUID.randomUUID(),
                    "a".repeat(64),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    now))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RentalOrderMutationCommand.start(
                    order,
                    Operation.EXPIRE_UNPAID_ORDER,
                    null,
                    4,
                    RentalOrderMutationCommand.AUTOMATIC_RELEASE_ACTOR_ID,
                    "LOGISTICS_SERVICE",
                    UUID.randomUUID(),
                    "a".repeat(64),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    now))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void expiredLeaseCanBeReclaimedAndFencesPreviousWorkerReceipt() {
    OffsetDateTime now = OffsetDateTime.of(2026, 8, 31, 12, 0, 0, 0, ZoneOffset.UTC);
    RentalOrderMutationCommand command =
        RentalOrderMutationCommand.start(
            mock(RentalOrder.class),
            Operation.CANCEL_ORDER,
            null,
            4,
            UUID.randomUUID(),
            "RENTAL_MANAGER",
            UUID.randomUUID(),
            "a".repeat(64),
            null,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "{}",
            now);
    UUID firstWorker = UUID.randomUUID();
    UUID secondWorker = UUID.randomUUID();
    assertThat(command.claim(firstWorker, now, now.plusSeconds(5))).isTrue();
    assertThat(command.claim(secondWorker, now.plusSeconds(4), now.plusSeconds(9))).isFalse();
    assertThat(command.claim(secondWorker, now.plusSeconds(5), now.plusSeconds(10))).isTrue();

    assertThatThrownBy(
            () -> command.recordReleasedUnits(firstWorker, "{}", now.plusSeconds(6)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lease is stale");
    command.recordReleasedUnits(secondWorker, "{}", now.plusSeconds(6));
    assertThat(command.getStep()).isEqualTo(RentalOrderMutationCommand.Step.FINALIZE_LOCAL);
  }
}
