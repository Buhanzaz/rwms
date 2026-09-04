package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;
import dev.buhanzaz.rwms.logistics.order.domain.OrderCommandReceipt;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationCodec;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.Operation;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.State;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.Step;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationCommandRepository;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.EquipmentReservations;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.Intent;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.ReleasedUnits;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns short local transactions for durable rental-order mutation preparation, leasing, receipts,
 * retry state and final completion. No method in this type invokes a remote dependency.
 */
@Service
@RequiredArgsConstructor
class RentalOrderMutationLocalStore {
  private static final int RECOVERY_BATCH_SIZE = 25;
  private static final int MAX_RECOVERY_ATTEMPTS = 8;
  private static final Duration RECOVERY_LEASE = Duration.ofMinutes(5);
  private static final long MAX_RECOVERY_DELAY_SECONDS = 300;
  private static final Set<State> OPEN_STATES = Set.of(State.PENDING, State.QUARANTINED);

  private final RentalOrderMutationCommandRepository commands;
  private final RentalOrderCommandStore orderStore;
  private final RentalOrderReservationService reservations;
  private final RentalOrderMutationCodec codec;

  /** Locks an order and fails closed when a recovery command is pending or quarantined. */
  @Transactional
  void requireNoOpenMutation(OrderActor actor, UUID orderId) {
    orderStore.lockedOrder(actor, orderId);
  }

  /** Locks an order for an already-owned recovery checkpoint. */
  @Transactional
  void requireNoOpenMutation(UUID orderId) {
    orderStore.lockedOrder(orderId);
  }

  /** Returns a completed or in-flight exact command, or {@code null} for a fresh key. */
  @Transactional
  MutationStart lookup(
      OrderActor actor, Operation operation, UUID idempotencyKey, String requestSha256) {
    return lookupLocked(actor, operation, idempotencyKey, requestSha256);
  }

  /**
   * Persists one immutable intent after repeating idempotency lookup and locking the order row.
   */
  @Transactional
  MutationStart prepare(
      OrderActor actor,
      Operation operation,
      UUID orderId,
      UUID targetUnitId,
      long expectedOrderVersion,
      UUID idempotencyKey,
      String requestSha256,
      List<LogisticsDependencyGateway.OrderUnitReservation> activeUnits) {
    MutationStart replay = lookupLocked(actor, operation, idempotencyKey, requestSha256);
    if (replay != null) return replay;

    RentalOrder order = orderStore.recoveryOrder(actor, orderId);
    if (commands.existsByOrder_IdAndStateIn(orderId, OPEN_STATES)) {
      throw RentalOrderProblems.conflict(
          "ORDER_MUTATION_PENDING", "Другая операция заказа ещё восстанавливается");
    }
    Intent intent =
        reservations.prepareMutationIntent(
            actor, operation, order, targetUnitId, expectedOrderVersion, activeUnits);
    OffsetDateTime timestamp = now();
    RentalOrderMutationCommand command =
        RentalOrderMutationCommand.start(
            order,
            operation,
            targetUnitId,
            expectedOrderVersion,
            actor.subjectId(),
            actor.role(),
            idempotencyKey,
            requestSha256,
            order.getWarehouseId(),
            stepKey(actor.subjectId(), operation, idempotencyKey, "release-units"),
            stepKey(
                actor.subjectId(),
                operation,
                idempotencyKey,
                "release-equipment"),
            codec.encode(intent),
            timestamp);
    commands.saveAndFlush(command);
    return start(command, false);
  }

  /** Claims one exact command if it is due and not leased by another instance. */
  @Transactional
  Optional<MutationClaim> claim(UUID commandId) {
    RentalOrderMutationCommand command =
        commands.findForUpdate(commandId).orElseThrow(RentalOrderProblems::notFound);
    OffsetDateTime timestamp = now();
    UUID leaseToken = UUID.randomUUID();
    if (!command.claim(leaseToken, timestamp, timestamp.plus(RECOVERY_LEASE))) {
      return Optional.empty();
    }
    commands.flush();
    return Optional.of(claim(command));
  }

  /** Claims a bounded oldest-first page while concurrent instances skip locked commands. */
  @Transactional
  List<MutationClaim> claimDue() {
    OffsetDateTime timestamp = now();
    OffsetDateTime leaseUntil = timestamp.plus(RECOVERY_LEASE);
    List<MutationClaim> claims =
        commands.findDueForUpdate(timestamp, RECOVERY_BATCH_SIZE).stream()
            .map(
                command -> {
                  UUID leaseToken = UUID.randomUUID();
                  return command.claim(leaseToken, timestamp, leaseUntil) ? claim(command) : null;
                })
            .filter(Objects::nonNull)
            .toList();
    commands.flush();
    return claims;
  }

  /** Commits the unit-release receipt before any furniture effect is attempted. */
  @Transactional
  MutationClaim recordReleasedUnits(
      UUID commandId, UUID leaseToken, ReleasedUnits releasedUnits) {
    RentalOrderMutationCommand command = locked(commandId, leaseToken);
    command.recordReleasedUnits(leaseToken, codec.encode(releasedUnits), now());
    commands.flush();
    return claim(command);
  }

  /** Commits the furniture-release receipt before local order state is changed. */
  @Transactional
  MutationClaim recordEquipmentReceipt(
      UUID commandId, UUID leaseToken, EquipmentReservations equipmentReservations) {
    RentalOrderMutationCommand command = locked(commandId, leaseToken);
    command.recordEquipmentReceipt(leaseToken, codec.encode(equipmentReservations), now());
    commands.flush();
    return claim(command);
  }

  /**
   * Applies the order transition, completed idempotency receipt and command completion atomically,
   * returning the response mapped from the just-flushed aggregate.
   */
  @Transactional
  OrderDetailResponse complete(
      MutationClaim claim,
      List<LogisticsDependencyGateway.OrderUnitReservation> remainingActiveUnits) {
    RentalOrderMutationCommand command = locked(claim.commandId(), claim.leaseToken());
    Intent intent = codec.intent(command.getIntentJson());
    ReleasedUnits releasedUnits = codec.releasedUnits(command.getReleasedUnitsReceiptJson());
    EquipmentReservations equipmentReservations =
        command.isEquipmentReleaseRequired()
            ? codec.equipmentReservations(command.getEquipmentReceiptJson())
            : new EquipmentReservations(List.of());
    RentalOrder order =
        reservations.finalizeMutation(
            command, intent, releasedUnits, equipmentReservations, remainingActiveUnits);
    orderStore.remember(
        command.getActorSubjectId(),
        command.getOperation().name(),
        command.getIdempotencyKey(),
        command.getRequestSha256(),
        order);
    command.complete(claim.leaseToken(), now());
    commands.flush();
    return reservations.recoveryDetail(command, order, remainingActiveUnits);
  }

  /**
   * Persists bounded exponential backoff or terminal quarantine for one exact live lease.
   * Non-retryable dependency failures enter quarantine on their first recorded attempt.
   */
  @Transactional
  RecoveryFailure fail(
      UUID commandId, UUID leaseToken, String errorCode, boolean nonRetryable) {
    RentalOrderMutationCommand command = locked(commandId, leaseToken);
    OffsetDateTime timestamp = now();
    int nextAttemptNumber = Math.addExact(command.getAttemptCount(), 1);
    boolean quarantined = nonRetryable || nextAttemptNumber >= MAX_RECOVERY_ATTEMPTS;
    OffsetDateTime nextAttemptAt =
        quarantined
            ? null
            : timestamp.plusSeconds(recoveryDelaySeconds(nextAttemptNumber));
    command.fail(leaseToken, boundedErrorCode(errorCode), timestamp, nextAttemptAt, quarantined);
    commands.flush();
    return new RecoveryFailure(
        command.getId(),
        command.getOrder().getId(),
        command.getOperation(),
        nextAttemptNumber,
        nextAttemptAt,
        quarantined);
  }

  /** Returns the latest scalar lifecycle for a command after a lost claim race. */
  @Transactional(readOnly = true)
  MutationStart status(UUID commandId) {
    RentalOrderMutationCommand command =
        commands.findById(commandId).orElseThrow(RentalOrderProblems::notFound);
    return start(command, true);
  }

  private MutationStart lookupLocked(
      OrderActor actor, Operation operation, UUID idempotencyKey, String requestSha256) {
    OrderCommandReceipt completed =
        orderStore.replay(actor, operation.name(), idempotencyKey, requestSha256);
    if (completed != null) {
      return new MutationStart(null, completed.getOrder().getId(), State.COMPLETED, true);
    }
    RentalOrderMutationCommand existing =
        commands
            .findByActorSubjectIdAndOperationAndIdempotencyKey(
                actor.subjectId(), operation, idempotencyKey)
            .orElse(null);
    if (existing == null) return null;
    if (!existing.getRequestSha256().equals(requestSha256)) {
      throw RentalOrderProblems.conflict(
          "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key уже использован для другой команды");
    }
    return start(existing, true);
  }

  private RentalOrderMutationCommand locked(UUID commandId, UUID leaseToken) {
    RentalOrderMutationCommand command =
        commands.findForUpdate(commandId).orElseThrow(RentalOrderProblems::notFound);
    OffsetDateTime timestamp = now();
    if (!command.hasLiveLease(leaseToken, timestamp)) {
      throw new IllegalStateException("Rental-order mutation lease is stale");
    }
    return command;
  }

  private OffsetDateTime now() {
    Instant databaseTimestamp = commands.currentDatabaseTimestamp();
    return OffsetDateTime.ofInstant(databaseTimestamp, ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MICROS);
  }

  private static MutationStart start(RentalOrderMutationCommand command, boolean replayed) {
    return new MutationStart(
        command.getId(), command.getOrder().getId(), command.getState(), replayed);
  }

  private static MutationClaim claim(RentalOrderMutationCommand command) {
    return new MutationClaim(
        command.getId(),
        command.getOrder().getId(),
        command.getOperation(),
        command.getStep(),
        command.getTargetUnitId(),
        command.getWarehouseId(),
        command.getActorSubjectId(),
        command.getActorRole(),
        command.getReleaseUnitsIdempotencyKey(),
        command.getReleaseEquipmentIdempotencyKey(),
        command.getIntentJson(),
        command.getReleasedUnitsReceiptJson(),
        command.getEquipmentReceiptJson(),
        command.getLeaseToken());
  }

  private static UUID stepKey(
      UUID actorSubjectId,
      Operation operation,
      UUID idempotencyKey,
      String step) {
    return UUID.nameUUIDFromBytes(
        ("rental-order-mutation-v1:"
                + actorSubjectId
                + ":"
                + operation
                + ":"
                + idempotencyKey
                + ":"
                + step)
            .getBytes(StandardCharsets.UTF_8));
  }

  private static long recoveryDelaySeconds(int attemptNumber) {
    int shift = Math.max(0, Math.min(attemptNumber - 1, 20));
    return Math.min(MAX_RECOVERY_DELAY_SECONDS, 2L << shift);
  }

  private static String boundedErrorCode(String errorCode) {
    String normalized = errorCode == null ? "" : errorCode.trim();
    if (normalized.isEmpty()) return "ORDER_MUTATION_RECOVERY_FAILED";
    return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
  }

  /** Scalar result of exact command lookup or preparation. */
  record MutationStart(UUID commandId, UUID orderId, State state, boolean replayed) {}

  /** Immutable lease-fenced work processed strictly outside the claiming transaction. */
  record MutationClaim(
      UUID commandId,
      UUID orderId,
      Operation operation,
      Step step,
      UUID targetUnitId,
      UUID warehouseId,
      UUID actorSubjectId,
      String actorRole,
      UUID releaseUnitsIdempotencyKey,
      UUID releaseEquipmentIdempotencyKey,
      String intentJson,
      String releasedUnitsReceiptJson,
      String equipmentReceiptJson,
      UUID leaseToken) {}

  /** Persisted result of one failed recovery attempt. */
  record RecoveryFailure(
      UUID commandId,
      UUID orderId,
      Operation operation,
      int attemptCount,
      OffsetDateTime nextAttemptAt,
      boolean quarantined) {}
}
