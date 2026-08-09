package dev.buhanzaz.rwms.maintenance.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the short local transaction and caller-transaction fence used by inventory maintenance
 * mutations.
 *
 * <p>Remote admission and routing checks deliberately run outside this boundary. The mutation
 * collaborators retry their local work after those checks so row locks are not held across a
 * remote call.
 */
@Component
final class InventoryMaintenanceTransactionBoundary {
  private final TransactionTemplate requiresNew;

  InventoryMaintenanceTransactionBoundary(PlatformTransactionManager transactionManager) {
    requiresNew = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    if (requiresNew != null) {
      requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
  }

  <T> T inNewTransaction(Supplier<T> action) {
    if (requiresNew == null) {
      throw new IllegalStateException(
          "Inventory mutation requires the Spring-managed transaction constructor");
    }
    T result = requiresNew.execute(status -> action.get());
    if (result == null) {
      throw new IllegalStateException("Inventory maintenance transaction returned no result");
    }
    return result;
  }

  /**
   * A caller-owned transaction can retain row locks while this boundary waits for a remote
   * lifecycle/routing answer. Refuse it rather than trying to suspend it with NOT_SUPPORTED.
   */
  void requireNoCallerTransaction(String operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Inventory maintenance cannot " + operation + " inside a caller transaction");
    }
  }
}
