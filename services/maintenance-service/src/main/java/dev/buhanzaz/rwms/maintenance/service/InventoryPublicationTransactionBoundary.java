package dev.buhanzaz.rwms.maintenance.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the publication workflow's short local transaction segments and caller-transaction fence.
 *
 * <p>Remote driver and routing calls deliberately happen outside this component's transactions.
 */
@Component
final class InventoryPublicationTransactionBoundary {
  private final TransactionTemplate requiresNew;

  InventoryPublicationTransactionBoundary(PlatformTransactionManager transactionManager) {
    this.requiresNew = new TransactionTemplate(transactionManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  <T> T inNewTransaction(Supplier<T> action) {
    T result = requiresNew.execute(status -> action.get());
    if (result == null) {
      throw new IllegalStateException("Inventory publication transaction returned no result");
    }
    return result;
  }

  void requireNoCallerTransaction(String operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Inventory publication cannot " + operation + " inside a caller transaction");
    }
  }
}
