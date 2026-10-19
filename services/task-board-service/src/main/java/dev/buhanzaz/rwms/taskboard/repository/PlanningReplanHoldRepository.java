package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.PlanningReplanHold;
import dev.buhanzaz.rwms.taskboard.domain.PlanningReplanHoldState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Locking persistence boundary for cross-date source-plan execution holds. */
public interface PlanningReplanHoldRepository extends JpaRepository<PlanningReplanHold, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select hold from PlanningReplanHold hold where hold.id=:id")
  Optional<PlanningReplanHold> findForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select hold from PlanningReplanHold hold where hold.idempotencyKey=:idempotencyKey")
  Optional<PlanningReplanHold> findByIdempotencyKeyForUpdate(
      @Param("idempotencyKey") UUID idempotencyKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select hold from PlanningReplanHold hold where hold.sourcePlanId=:sourcePlanId and"
          + " hold.replacementPlanVersion=:replacementPlanVersion")
  Optional<PlanningReplanHold> findRevisionForUpdate(
      @Param("sourcePlanId") UUID sourcePlanId,
      @Param("replacementPlanVersion") long replacementPlanVersion);

  boolean existsBySourcePlanIdAndState(UUID sourcePlanId, PlanningReplanHoldState state);
}
