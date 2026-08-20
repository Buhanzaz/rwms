package dev.buhanzaz.rwms.logistics.inventory.repository;

import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryOutcomeTaskAction;
import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryOutcomeTaskActionState;
import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryOutcomeTaskTargetType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for recoverable task and asset-lease supersession checkpoints. */
public interface InventoryOutcomeTaskActionRepository
    extends JpaRepository<InventoryOutcomeTaskAction, UUID> {
  List<InventoryOutcomeTaskAction> findAllByReceiptIdAndStateOrderByTargetTypeAscTargetIdAsc(
      UUID receiptId, InventoryOutcomeTaskActionState state);

  List<InventoryOutcomeTaskAction>
      findAllByReceiptIdAndTargetTypeAndStateOrderByTargetIdAsc(
          UUID receiptId,
          InventoryOutcomeTaskTargetType targetType,
          InventoryOutcomeTaskActionState state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select action from InventoryOutcomeTaskAction action where action.id=:id")
  java.util.Optional<InventoryOutcomeTaskAction> findForUpdate(@Param("id") UUID id);
}
