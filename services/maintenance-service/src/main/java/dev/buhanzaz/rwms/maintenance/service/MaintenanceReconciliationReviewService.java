package dev.buhanzaz.rwms.maintenance.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal operator-reviewed recovery boundary; intentionally has no HTTP controller. */
@Service
public class MaintenanceReconciliationReviewService {
  private final MaintenanceReconciliationStore reconciliations;

  public MaintenanceReconciliationReviewService(
      MaintenanceReconciliationStore reconciliations) {
    this.reconciliations = reconciliations;
  }

  @Transactional
  public MaintenanceReconciliationStore.ResumeResult resumeQuarantined(
      UUID reconciliationId,
      long expectedReviewVersion,
      UUID reviewSubjectId,
      String reviewReason) {
    return reconciliations.resumeQuarantined(
        reconciliationId, expectedReviewVersion, reviewSubjectId, reviewReason);
  }
}
