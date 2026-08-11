package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.order.domain.OrderCommandReceipt;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.repository.OrderCommandReceiptRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Serializes logistics-local rental-order commands through the existing order-row and receipt
 * advisory locks. It returns only exact loaded aggregates or receipts and never exposes a general
 * repository API.
 */
@Service
@RequiredArgsConstructor
class RentalOrderCommandStore {
  private final RentalOrderRepository orders;
  private final OrderCommandReceiptRepository receipts;
  private final LogisticsTransactionLock transactionLock;

  RentalOrder requiredOrder(UUID orderId) {
    return orders.findWithClientById(orderId).orElseThrow(RentalOrderProblems::notFound);
  }

  RentalOrder lockedOrder(UUID orderId) {
    return orders.findForUpdate(orderId).orElseThrow(RentalOrderProblems::notFound);
  }

  void lockCreation(OrderActor actor, UUID scopedKey) {
    transactionLock.acquire(
        "rental-order:idempotency:" + actor.subjectId() + ":" + scopedKey);
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
        "rental-order:command:" + actor.subjectId() + ":" + operation + ":" + key);
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
    receipts.save(
        OrderCommandReceipt.complete(order, actor.subjectId(), operation, key, checksum));
  }
}
