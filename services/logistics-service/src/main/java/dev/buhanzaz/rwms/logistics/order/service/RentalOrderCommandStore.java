package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutationOperation;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutationState;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerBookingMutationRepository;
import dev.buhanzaz.rwms.logistics.order.domain.OrderCommandReceipt;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.repository.OrderCommandReceiptRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.State;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationCommandRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Serializes logistics-local rental-order commands through the existing order-row and receipt
 * advisory locks. Mutable command admission also checks the customer cancellation checkpoint under
 * that same order-row lock, while the owning recovery path deliberately bypasses only this
 * admission check. The store returns only exact loaded aggregates or receipts and never exposes a
 * general repository API.
 */
@Service
@RequiredArgsConstructor
class RentalOrderCommandStore {
  private static final Set<State> OPEN_MUTATION_STATES = Set.of(State.PENDING, State.QUARANTINED);
  private static final Set<CustomerBookingMutationState> OPEN_CUSTOMER_CANCELLATION_STATES =
      Set.of(CustomerBookingMutationState.PENDING, CustomerBookingMutationState.QUARANTINED);

  private final RentalOrderRepository orders;
  private final OrderCommandReceiptRepository receipts;
  private final RentalOrderMutationCommandRepository mutationCommands;
  private final CustomerBookingMutationRepository customerBookingMutations;
  private final LogisticsTransactionLock transactionLock;

  RentalOrder requiredOrder(UUID orderId) {
    return orders.findWithClientById(orderId).orElseThrow(RentalOrderProblems::notFound);
  }

  RentalOrder lockedOrder(OrderActor actor, UUID orderId) {
    return requireNoOpenMutation(recoveryOrder(actor, orderId), orderId);
  }

  RentalOrder lockedOrder(UUID orderId) {
    return requireNoOpenMutation(recoveryOrder(orderId), orderId);
  }

  private RentalOrder requireNoOpenMutation(RentalOrder order, UUID orderId) {
    if (customerBookingMutations.existsByOrderIdAndOperationAndStateIn(
        orderId, CustomerBookingMutationOperation.CANCEL, OPEN_CUSTOMER_CANCELLATION_STATES)) {
      throw RentalOrderProblems.conflict(
          "CUSTOMER_BOOKING_CANCELLATION_PENDING",
          "Бронирование уже отменяется; новые операции заказа временно недоступны");
    }
    if (mutationCommands.existsByOrder_IdAndStateIn(orderId, OPEN_MUTATION_STATES)) {
      throw RentalOrderProblems.conflict(
          "ORDER_MUTATION_PENDING", "Операция заказа ещё восстанавливается");
    }
    return order;
  }

  RentalOrder recoveryOrder(OrderActor actor, UUID orderId) {
    RentalOrder order = recoveryOrder(orderId);
    if (actor == null) {
      throw RentalOrderProblems.notFound();
    }
    return order;
  }

  /** Locks an order only for the owning recovery preparation/finalization transaction. */
  RentalOrder recoveryOrder(UUID orderId) {
    return orders.findForUpdate(orderId).orElseThrow(RentalOrderProblems::notFound);
  }

  void lockCreation(OrderActor actor, UUID scopedKey) {
    transactionLock.acquire(
        "rental-order:idempotency:"
            + actor.subjectId()
            + ":"
            + scopedKey);
  }

  RentalOrder creationReplay(OrderActor actor, UUID scopedKey) {
    return orders
        .findByCreatedBySubjectIdAndCreationIdempotencyKey(actor.subjectId(), scopedKey)
        .orElse(null);
  }

  long nextOrderNumber() {
    return orders.nextOrderNumber();
  }

  RentalOrder persist(RentalOrder order) {
    return orders.saveAndFlush(order);
  }

  /**
   * Acquires the existing actor/operation/key advisory lock before looking up a completed receipt.
   */
  OrderCommandReceipt replay(
      OrderActor actor, String operation, UUID key, String checksum, String... compatibleChecksums) {
    if (actor == null || key == null) {
      throw new IllegalArgumentException("Order actor and Idempotency-Key are required");
    }
    transactionLock.acquire(
        "rental-order:command:"
            + actor.subjectId()
            + ":"
            + operation
            + ":"
            + key);
    OrderCommandReceipt receipt =
        receipts
            .findByActorSubjectIdAndOperationNameAndIdempotencyKey(
                actor.subjectId(), operation, key)
            .orElse(null);
    boolean compatible =
        receipt != null
            && java.util.Arrays.stream(compatibleChecksums)
                .filter(java.util.Objects::nonNull)
                .anyMatch(receipt::matches);
    if (receipt != null && !receipt.matches(checksum) && !compatible) {
      throw RentalOrderProblems.conflict(
          "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key уже использован для другой команды");
    }
    return receipt;
  }

  void remember(
      OrderActor actor, String operation, UUID key, String checksum, RentalOrder order) {
    remember(actor.subjectId(), operation, key, checksum, order);
  }

  /** Completes a recovered command for the actor that was authorized before remote effects. */
  void remember(
      UUID actorSubjectId, String operation, UUID key, String checksum, RentalOrder order) {
    receipts.save(
        OrderCommandReceipt.complete(order, actorSubjectId, operation, key, checksum));
  }
}
