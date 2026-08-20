package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.InventoryAssetOutcomeReceipt;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persists immutable outcome responses and serializes each idempotency key. */
public interface InventoryAssetOutcomeReceiptRepository
    extends JpaRepository<InventoryAssetOutcomeReceipt, UUID> {
  /** Serializes one idempotency key until the caller's command transaction completes. */
  @Query(
      value = "select 1 from pg_advisory_xact_lock(hashtextextended(cast(:lockKey as text), 0))",
      nativeQuery = true)
  Integer acquireTransactionLock(@Param("lockKey") String lockKey);
}
