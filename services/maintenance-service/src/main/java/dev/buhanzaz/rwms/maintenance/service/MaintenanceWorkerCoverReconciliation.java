package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reasserts worker presentation snapshots for already registered, not-yet-started repair tasks.
 *
 * <p>The startup pass performs only owner-local reads and durable reconciliation inserts. Existing
 * task-board update orchestration performs every remote call later, outside the local transaction.
 * A versioned stable key makes the pass safe across restarts and multiple application instances.
 * Advancing that version schedules a fresh pre-start update without resuming or changing
 * quarantined work from an earlier presentation generation.
 */
@Service
public class MaintenanceWorkerCoverReconciliation {
  private static final Logger log =
      LoggerFactory.getLogger(MaintenanceWorkerCoverReconciliation.class);
  private static final int PAGE_SIZE = 100;
  private static final String RECONCILIATION_VERSION = "worker-presentation-v5";
  private static final String QUARANTINED_RECONCILIATION_CODE =
      "MAINTENANCE_RECONCILIATION_QUARANTINED";

  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceReconciliationStore reconciliations;
  private final TransactionTemplate transactions;

  public MaintenanceWorkerCoverReconciliation(
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceReconciliationStore reconciliations,
      PlatformTransactionManager transactionManager) {
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.reconciliations = reconciliations;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Enqueues one idempotent pre-start task update per eligible repair after application startup.
   * No remote dependency is contacted on the application-ready thread. An existing quarantined
   * stable update in the current generation remains quarantined for reviewed resume and is skipped
   * without failing startup. Older-generation work keeps its existing state and identity; every
   * other conflict still fails closed.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void enqueueWorkerPresentationSnapshots() {
    List<UUID> candidates = new ArrayList<>();
    int pageNumber = 0;
    boolean hasNext;
    do {
      int currentPage = pageNumber++;
      Page<MaintenanceRepair> page =
          transactions.execute(
              status ->
                  repairs.findAll(
                      PageRequest.of(
                          currentPage,
                          PAGE_SIZE,
                          Sort.by(Sort.Direction.ASC, "id"))));
      if (page == null) {
        throw new IllegalStateException("Maintenance repair page was not returned");
      }
      page.stream()
          .filter(MaintenanceWorkerCoverReconciliation::isCandidate)
          .map(MaintenanceRepair::getId)
          .forEach(candidates::add);
      hasNext = page.hasNext();
    } while (hasNext);

    int enqueued = 0;
    int quarantined = 0;
    for (UUID repairId : candidates) {
      try {
        Boolean accepted =
            transactions.execute(status -> enqueueIfStillEligible(repairId));
        if (Boolean.TRUE.equals(accepted)) {
          enqueued++;
        }
      } catch (MaintenanceConflictException conflict) {
        if (!QUARANTINED_RECONCILIATION_CODE.equals(conflict.code())) {
          throw conflict;
        }
        quarantined++;
      }
    }
    log.info(
        "Worker task snapshot reconciliation inspected {} candidates, accepted {}, and retained {} quarantined",
        candidates.size(),
        enqueued,
        quarantined);
  }

  private boolean enqueueIfStillEligible(UUID repairId) {
    MaintenanceRepair repair = repairs.findById(repairId).orElse(null);
    if (!isCandidate(repair)) return false;
    List<RepairStage> stages =
        repairStages.findAllByRepairIdOrderByStageNo(repairId);
    if (stages.isEmpty()
        || stages.stream()
            .anyMatch(
                stage ->
                    stage.getState() != RepairStageState.QUEUED
                        || !"GENERATED".equals(stage.getTaskGenerationState())
                        || stage.getExternalQueueEntryId() == null)) {
      return false;
    }
    reconciliations.enqueue(
        repairId,
        "TASK_BOARD",
        "REFRESH_WORKER_MEDIA",
        stableKey(repairId),
        Map.of("repairId", repairId.toString()));
    return true;
  }

  private static boolean isCandidate(MaintenanceRepair repair) {
    return repair != null
        && repair.getExecutionState() == RepairExecutionState.QUEUED
        && "GENERATED".equals(repair.getTaskGenerationState())
        && repair.getExternalTaskId() != null;
  }

  private static UUID stableKey(UUID repairId) {
    return UUID.nameUUIDFromBytes(
        (RECONCILIATION_VERSION + ":" + repairId).getBytes(StandardCharsets.UTF_8));
  }
}
