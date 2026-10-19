package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.TaskSyncSource;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Resolves a task's exact source service, external identity, and idempotency fingerprint. */
public interface TaskSyncSourceRepository extends JpaRepository<TaskSyncSource, UUID> {
  boolean existsByBoardTaskIdAndSourceClientId(UUID boardTaskId, String sourceClientId);

  List<TaskSyncSource> findAllBySourceClientId(String sourceClientId);

  List<TaskSyncSource> findAllByBoardTaskIdIn(Collection<UUID> boardTaskIds);

  /** Locks the complete current membership for one planner lineage in stable identity order. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select source
      from TaskSyncSource source
      where source.sourceClientId = :sourceClientId
        and source.sourcePlanId = :sourcePlanId
        and source.plannerMembershipState = dev.buhanzaz.rwms.taskboard.domain.PlannerMembershipState.ACTIVE
      order by source.externalTaskId
      """)
  List<TaskSyncSource> findAllBySourcePlanIdForUpdate(
      @Param("sourceClientId") String sourceClientId,
      @Param("sourcePlanId") UUID sourcePlanId);
}
