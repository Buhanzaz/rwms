package dev.buhanzaz.rwms.asset.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs inventory snapshots in an independent repeatable-read, read-only transaction.
 *
 * <p>Capture, current-asset, and furniture-read callers share this technical boundary so a
 * snapshot never observes a mix of committed asset, catalog, balance, and reservation rows.
 */
@Service
final class InventoryAssetSnapshotTransaction {
  private final TransactionTemplate captureSnapshotTransaction;

  InventoryAssetSnapshotTransaction(PlatformTransactionManager transactionManager) {
    this.captureSnapshotTransaction = new TransactionTemplate(transactionManager);
    this.captureSnapshotTransaction.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.captureSnapshotTransaction.setIsolationLevel(
        TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.captureSnapshotTransaction.setReadOnly(true);
  }

  <T> T execute(Supplier<T> read) {
    return captureSnapshotTransaction.execute(ignored -> read.get());
  }
}
