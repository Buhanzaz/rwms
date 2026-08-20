package dev.buhanzaz.rwms.logistics.inventory.repository;

import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryOutcomeReceipt;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Locked persistence boundary for permanent inventory outcome receipts. */
public interface InventoryOutcomeReceiptRepository
    extends JpaRepository<InventoryOutcomeReceipt, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select receipt from InventoryOutcomeReceipt receipt where receipt.idempotencyKey=:key")
  Optional<InventoryOutcomeReceipt> findForUpdateByIdempotencyKey(@Param("key") UUID key);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select receipt from InventoryOutcomeReceipt receipt where receipt.id=:id")
  Optional<InventoryOutcomeReceipt> findForUpdate(@Param("id") UUID id);
}
