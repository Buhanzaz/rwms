package dev.buhanzaz.rwms.logistics.planning.repository;

import dev.buhanzaz.rwms.logistics.planning.domain.PlanningAssignmentReplacementReceipt;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Locking persistence boundary for logistics planner-replacement receipts. */
public interface PlanningAssignmentReplacementReceiptRepository
    extends JpaRepository<PlanningAssignmentReplacementReceipt, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select receipt from PlanningAssignmentReplacementReceipt receipt where"
          + " receipt.idempotencyKey=:idempotencyKey")
  Optional<PlanningAssignmentReplacementReceipt> findForUpdate(
      @Param("idempotencyKey") UUID idempotencyKey);
}
