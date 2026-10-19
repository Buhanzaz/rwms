package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.DriverShiftPlan;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Locking persistence boundary for logistics-projected daily driver plans. */
public interface DriverShiftPlanRepository extends JpaRepository<DriverShiftPlan, UUID> {
  Optional<DriverShiftPlan> findByDriverIdAndWorkDateAndWithdrawnAtIsNull(
      UUID driverId, LocalDate workDate);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select plan from DriverShiftPlan plan where plan.sourceShiftId=:id")
  Optional<DriverShiftPlan> findBySourceShiftIdForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select plan from DriverShiftPlan plan where plan.driverId=:driverId and"
          + " plan.workDate=:workDate and plan.withdrawnAt is null")
  Optional<DriverShiftPlan> findByDriverIdAndWorkDateForUpdate(
      @Param("driverId") UUID driverId, @Param("workDate") LocalDate workDate);

  /** Locks the complete shift membership of one planner lineage. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select plan from DriverShiftPlan plan where plan.sourcePlanId=:sourcePlanId and"
          + " plan.withdrawnAt is null order by plan.sourceShiftId")
  List<DriverShiftPlan> findAllBySourcePlanIdForUpdate(@Param("sourcePlanId") UUID sourcePlanId);
}
