package dev.buhanzaz.rwms.logistics.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationCodec;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.Operation;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.State;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.Step;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.Intent;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderMutationLocalStore.MutationClaim;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderMutationLocalStore.MutationStart;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderMutationLocalStore.RecoveryFailure;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RentalOrderMutationRecoveryServiceTest {

  @Test
  void completionRacingBetweenLookupAndPrepareReturnsTheStoredReplay() {
    Fixture fixture = fixture();
    String requestSha256 =
        OrderCommandChecksum.sha256(
            Operation.CANCEL_ORDER.name(),
            List.of(fixture.orderId.toString(), Long.toString(3)));
    OrderDetailResponse expected = mock(OrderDetailResponse.class);
    when(fixture.local.lookup(
            fixture.actor, Operation.CANCEL_ORDER, fixture.publicKey, requestSha256))
        .thenReturn(null);
    when(fixture.reservations.readMutationUnits(fixture.actor, fixture.orderId))
        .thenReturn(List.of());
    when(fixture.local.prepare(
            fixture.actor,
            Operation.CANCEL_ORDER,
            fixture.orderId,
            null,
            3,
            fixture.publicKey,
            requestSha256,
            List.of()))
        .thenReturn(new MutationStart(null, fixture.orderId, State.COMPLETED, true));
    when(fixture.reads.get(fixture.actor, fixture.orderId)).thenReturn(expected);

    RentalOrderCommandOutcome outcome =
        fixture.service.cancel(fixture.actor, fixture.orderId, 3, fixture.publicKey);

    assertThat(outcome.response()).isSameAs(expected);
    assertThat(outcome.replayed()).isTrue();
  }

  @Test
  void localFailureAfterClaimReturnsPendingInsteadOfLeakingInternalException() {
    Fixture fixture = fixture();
    IllegalStateException failure = new IllegalStateException("stored intent is corrupt");
    when(fixture.codec.intent(fixture.claim.intentJson())).thenThrow(failure);
    when(fixture.local.fail(
            fixture.commandId,
            fixture.leaseToken,
            "ORDER_MUTATION_LOCAL_RECONCILIATION_FAILED",
            false))
        .thenReturn(
            new RecoveryFailure(
                fixture.commandId, fixture.orderId, Operation.CANCEL_ORDER, 1, null, false));

    assertThatThrownBy(
            () -> fixture.service.cancel(fixture.actor, fixture.orderId, 3, fixture.publicKey))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("ORDER_MUTATION_PENDING"));
    verify(fixture.local)
        .fail(
            fixture.commandId,
            fixture.leaseToken,
            "ORDER_MUTATION_LOCAL_RECONCILIATION_FAILED",
            false);
  }

  @Test
  void localFailureAtTerminalBoundaryReturnsReconciliationRequired() {
    Fixture fixture = fixture();
    when(fixture.codec.intent(fixture.claim.intentJson()))
        .thenThrow(new IllegalStateException("stored receipt is corrupt"));
    when(fixture.local.fail(
            fixture.commandId,
            fixture.leaseToken,
            "ORDER_MUTATION_LOCAL_RECONCILIATION_FAILED",
            false))
        .thenReturn(
            new RecoveryFailure(
                fixture.commandId, fixture.orderId, Operation.CANCEL_ORDER, 8, null, true));

    assertThatThrownBy(
            () -> fixture.service.cancel(fixture.actor, fixture.orderId, 3, fixture.publicKey))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem ->
                assertThat(problem.code())
                    .isEqualTo("ORDER_MUTATION_RECONCILIATION_REQUIRED"));
  }

  @Test
  void permanentDependencyRejectionIsQuarantinedAndPreservesDomainException() {
    Fixture fixture = fixture();
    Intent intent = new Intent(List.of(), List.of());
    LogisticsDependencyException rejection =
        new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
            "UNIT_NOT_AVAILABLE",
            "rejected",
            null);
    when(fixture.codec.intent(fixture.claim.intentJson())).thenReturn(intent);
    when(fixture.dependencies.releaseAllOrderUnits(
            fixture.releaseUnitsKey,
            fixture.orderId,
            fixture.actor.subjectId(),
            fixture.actor.role()))
        .thenThrow(rejection);
    when(fixture.local.fail(
            fixture.commandId, fixture.leaseToken, "UNIT_NOT_AVAILABLE", true))
        .thenReturn(
            new RecoveryFailure(
                fixture.commandId, fixture.orderId, Operation.CANCEL_ORDER, 1, null, true));

    assertThatThrownBy(
            () -> fixture.service.cancel(fixture.actor, fixture.orderId, 3, fixture.publicKey))
        .isSameAs(rejection);
    verify(fixture.local)
        .fail(fixture.commandId, fixture.leaseToken, "UNIT_NOT_AVAILABLE", true);
  }

  private static Fixture fixture() {
    UUID commandId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID publicKey = UUID.randomUUID();
    UUID leaseToken = UUID.randomUUID();
    UUID releaseUnitsKey = UUID.randomUUID();
    OrderActor actor = mock(OrderActor.class);
    when(actor.subjectId()).thenReturn(UUID.randomUUID());
    when(actor.role()).thenReturn("RENTAL_MANAGER");
    RentalOrderMutationLocalStore local = mock(RentalOrderMutationLocalStore.class);
    RentalOrderReservationService reservations = mock(RentalOrderReservationService.class);
    RentalOrderReadService reads = mock(RentalOrderReadService.class);
    RentalOrderMutationCodec codec = mock(RentalOrderMutationCodec.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    RentalOrderMutationRecoveryService service =
        new RentalOrderMutationRecoveryService(local, reservations, reads, codec, dependencies);
    MutationClaim claim =
        new MutationClaim(
            commandId,
            orderId,
            Operation.CANCEL_ORDER,
            Step.RELEASE_UNITS,
            null,
            null,
            actor.subjectId(),
            actor.role(),
            releaseUnitsKey,
            UUID.randomUUID(),
            "{}",
            null,
            null,
            leaseToken);
    when(local.lookup(eq(actor), eq(Operation.CANCEL_ORDER), eq(publicKey), anyString()))
        .thenReturn(new MutationStart(commandId, orderId, State.PENDING, true));
    when(local.claim(commandId)).thenReturn(Optional.of(claim));
    return new Fixture(
        commandId,
        orderId,
        publicKey,
        leaseToken,
        releaseUnitsKey,
        actor,
        local,
        reservations,
        reads,
        codec,
        dependencies,
        service,
        claim);
  }

  /** Dependencies and stable identities for one already-persisted cancel claim. */
  private record Fixture(
      UUID commandId,
      UUID orderId,
      UUID publicKey,
      UUID leaseToken,
      UUID releaseUnitsKey,
      OrderActor actor,
      RentalOrderMutationLocalStore local,
      RentalOrderReservationService reservations,
      RentalOrderReadService reads,
      RentalOrderMutationCodec codec,
      LogisticsDependencyGateway dependencies,
      RentalOrderMutationRecoveryService service,
      MutationClaim claim) {}
}
