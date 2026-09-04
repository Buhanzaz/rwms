package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationCodec;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.Operation;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.State;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.Step;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.EquipmentReservations;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.Intent;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.ReleasedUnits;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderMutationLocalStore.MutationClaim;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderMutationLocalStore.MutationStart;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderMutationLocalStore.RecoveryFailure;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Executes durable cancel/remove-unit steps outside local transactions and autonomously reclaims
 * unfinished commands. Each next remote effect starts only after the prior receipt transaction has
 * committed.
 */
@Service
@RequiredArgsConstructor
public class RentalOrderMutationRecoveryService {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(RentalOrderMutationRecoveryService.class);

  private final RentalOrderMutationLocalStore local;
  private final RentalOrderReservationService reservations;
  private final RentalOrderReadService reads;
  private final RentalOrderMutationCodec codec;
  private final LogisticsDependencyGateway dependencies;

  /** Submits or joins one replay-safe order cancellation. */
  RentalOrderCommandOutcome cancel(
      OrderActor actor, UUID orderId, long expectedVersion, UUID idempotencyKey) {
    String requestSha256 =
        OrderCommandChecksum.sha256(
            Operation.CANCEL_ORDER.name(),
            List.of(orderId.toString(), Long.toString(expectedVersion)));
    return execute(
        actor,
        Operation.CANCEL_ORDER,
        orderId,
        null,
        expectedVersion,
        idempotencyKey,
        requestSha256);
  }

  /** Submits or joins one replay-safe cabin removal. */
  RentalOrderCommandOutcome removeUnit(
      OrderActor actor,
      UUID orderId,
      UUID unitId,
      long expectedVersion,
      UUID idempotencyKey) {
    String requestSha256 =
        OrderCommandChecksum.sha256(
            Operation.REMOVE_UNIT.name(),
            List.of(orderId.toString(), unitId.toString(), Long.toString(expectedVersion)));
    return execute(
        actor,
        Operation.REMOVE_UNIT,
        orderId,
        unitId,
        expectedVersion,
        idempotencyKey,
        requestSha256);
  }

  /** Periodically claims unfinished commands across service instances using database leases. */
  @Scheduled(
      fixedDelayString = "${rwms.logistics.order-mutation-reconcile-delay:2s}",
      initialDelayString = "${rwms.logistics.order-mutation-reconcile-initial-delay:3s}")
  public void recoverPending() {
    for (MutationClaim claim : local.claimDue()) {
      process(claim, false);
    }
  }

  private RentalOrderCommandOutcome execute(
      OrderActor actor,
      Operation operation,
      UUID orderId,
      UUID targetUnitId,
      long expectedVersion,
      UUID idempotencyKey,
      String requestSha256) {
    MutationStart start = local.lookup(actor, operation, idempotencyKey, requestSha256);
    if (start != null && start.state() == State.COMPLETED) {
      return new RentalOrderCommandOutcome(reads.get(actor, start.orderId()), true);
    }
    if (start == null) {
      List<LogisticsDependencyGateway.OrderUnitReservation> activeUnits =
          reservations.readMutationUnits(actor, orderId);
      start =
          local.prepare(
              actor,
              operation,
              orderId,
              targetUnitId,
              expectedVersion,
              idempotencyKey,
              requestSha256,
              activeUnits);
      if (start.state() == State.COMPLETED) {
        return new RentalOrderCommandOutcome(reads.get(actor, start.orderId()), true);
      }
    }
    if (start.state() == State.QUARANTINED) throw reconciliationRequired();

    Optional<MutationClaim> claimed = local.claim(start.commandId());
    if (claimed.isEmpty()) {
      MutationStart current = local.status(start.commandId());
      if (current.state() == State.QUARANTINED) throw reconciliationRequired();
      throw pending();
    }
    OrderDetailResponse response = process(claimed.get(), true);
    if (response == null) throw pending();
    return new RentalOrderCommandOutcome(response, start.replayed());
  }

  private OrderDetailResponse process(MutationClaim initial, boolean propagate) {
    MutationClaim claim = initial;
    try {
      while (true) {
        Intent intent = codec.intent(claim.intentJson());
        if (claim.step() == Step.RELEASE_UNITS) {
          List<LogisticsDependencyGateway.OrderUnitReservation> raw =
              claim.operation() == Operation.CANCEL_ORDER
                  ? dependencies.releaseAllOrderUnits(
                      claim.releaseUnitsIdempotencyKey(),
                      claim.orderId(),
                      claim.actorSubjectId(),
                      claim.actorRole())
                  : Collections.singletonList(
                      dependencies.releaseOrderUnit(
                          claim.releaseUnitsIdempotencyKey(),
                          claim.orderId(),
                          claim.targetUnitId(),
                          claim.actorSubjectId(),
                          claim.actorRole()));
          ReleasedUnits receipt =
              reservations.validateReleasedUnits(
                  claim.operation(),
                  claim.orderId(),
                  claim.targetUnitId(),
                  intent,
                  raw);
          claim =
              local.recordReleasedUnits(claim.commandId(), claim.leaseToken(), receipt);
          continue;
        }
        if (claim.step() == Step.RELEASE_EQUIPMENT) {
          List<LogisticsDependencyGateway.OrderEquipmentReservation> raw =
              dependencies.replaceOrderEquipmentReservations(
                  claim.releaseEquipmentIdempotencyKey(),
                  claim.orderId(),
                  claim.warehouseId(),
                  claim.actorSubjectId(),
                  claim.actorRole(),
                  reservations.mutationComposition(intent));
          EquipmentReservations receipt =
              reservations.validateEquipmentReservations(intent, raw);
          claim =
              local.recordEquipmentReceipt(claim.commandId(), claim.leaseToken(), receipt);
          continue;
        }
        if (claim.step() == Step.FINALIZE_LOCAL) {
          List<LogisticsDependencyGateway.OrderUnitReservation> remaining =
              reservations.readRecoveryUnits(claim.orderId());
          OrderDetailResponse response = local.complete(claim, remaining);
          LOGGER.info(
              "Rental-order mutation recovery completed: commandId={}, orderId={}, operation={}",
              claim.commandId(),
              claim.orderId(),
              claim.operation());
          return response;
        }
        return null;
      }
    } catch (RuntimeException exception) {
      persistFailure(claim, exception, propagate);
      return null;
    }
  }

  private void persistFailure(
      MutationClaim claim, RuntimeException exception, boolean propagate) {
    String errorCode = safeErrorCode(exception);
    RecoveryFailure failure;
    try {
      failure =
          local.fail(
              claim.commandId(),
              claim.leaseToken(),
              errorCode,
              isNonRetryable(exception));
      LOGGER.warn(
          "Rental-order mutation recovery deferred: commandId={}, orderId={}, operation={}, code={}, attempt={}, quarantined={}",
          failure.commandId(),
          failure.orderId(),
          failure.operation(),
          errorCode,
          failure.attemptCount(),
          failure.quarantined(),
          exception);
    } catch (RuntimeException staleLease) {
      LOGGER.warn(
          "Rental-order mutation recovery lease could not be finalized: commandId={}, orderId={}, operation={}",
          claim.commandId(),
          claim.orderId(),
          claim.operation());
      if (propagate) throw pending();
      return;
    }
    if (!propagate) return;
    if (exception instanceof LogisticsDependencyException
        || exception instanceof OrderProblemException) {
      throw exception;
    }
    if (failure.quarantined()) throw reconciliationRequired();
    throw pending();
  }

  /**
   * A dependency's explicit rejection or configuration failure cannot improve by replaying the
   * same immutable command. Transient and locally detected failures retain the bounded retry path.
   */
  private static boolean isNonRetryable(RuntimeException exception) {
    return exception instanceof LogisticsDependencyException dependency
        && dependency.kind() != LogisticsDependencyException.FailureKind.TRANSIENT;
  }

  private static String safeErrorCode(RuntimeException exception) {
    if (exception instanceof OrderProblemException problem
        && problem.code() != null
        && !problem.code().isBlank()) {
      return problem.code();
    }
    if (exception instanceof LogisticsDependencyException dependency) {
      if (dependency.dependencyCode() != null && !dependency.dependencyCode().isBlank()) {
        return dependency.dependencyCode();
      }
      return switch (dependency.kind()) {
        case PERMANENT_REJECTION -> "ORDER_ASSET_COMMAND_REJECTED";
        case TRANSIENT -> "ORDER_ASSET_SERVICE_UNAVAILABLE";
        case CONFIGURATION -> "ORDER_ASSET_CONFIGURATION_INVALID";
      };
    }
    return "ORDER_MUTATION_LOCAL_RECONCILIATION_FAILED";
  }

  private static OrderProblemException pending() {
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "ORDER_MUTATION_PENDING",
        "Операция заказа ещё сверяется и продолжится автоматически");
  }

  private static OrderProblemException reconciliationRequired() {
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "ORDER_MUTATION_RECONCILIATION_REQUIRED",
        "Операция заказа требует проверки после исчерпания автоматических попыток");
  }
}
