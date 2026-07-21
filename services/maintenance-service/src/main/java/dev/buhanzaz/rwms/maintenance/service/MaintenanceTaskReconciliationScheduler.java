package dev.buhanzaz.rwms.maintenance.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceTaskReconciliationScheduler {
  private final MaintenanceApplicationService service;

  public MaintenanceTaskReconciliationScheduler(MaintenanceApplicationService service) {
    this.service = service;
  }

  @Scheduled(
      fixedDelayString = "${rwms.maintenance.task-reconciliation.delay:2s}",
      initialDelayString = "${rwms.maintenance.task-reconciliation.initial-delay:2s}")
  public void reconcile() {
    service.reconcileOneTask();
    service.reconcileOneMediaOwnerProof();
  }
}
