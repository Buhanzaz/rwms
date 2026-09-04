package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.PlanningReplanHoldState;
import dev.buhanzaz.rwms.taskboard.domain.TaskSyncSource;
import dev.buhanzaz.rwms.taskboard.repository.PlanningReplanHoldRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskSyncSourceRepository;
import org.springframework.stereotype.Component;

/** Rejects worker execution while a complete planner lineage is held for owner rescheduling. */
@Component
class PlanningReplanHoldFence {
  private final TaskSyncSourceRepository sources;
  private final PlanningReplanHoldRepository holds;

  PlanningReplanHoldFence(
      TaskSyncSourceRepository sources, PlanningReplanHoldRepository holds) {
    this.sources = sources;
    this.holds = holds;
  }

  /** Must be invoked only after the route entry lock pairs with PREPARE's entry locks. */
  void requireExecutionAllowed(BoardTask task) {
    TaskSyncSource source = sources.findById(task.getId()).orElse(null);
    if (source != null
        && source.getSourcePlanId() != null
        && holds.existsBySourcePlanIdAndState(
            source.getSourcePlanId(), PlanningReplanHoldState.PREPARED)) {
      throw new ConflictException("План временно заблокирован на время согласованного переноса");
    }
  }
}
