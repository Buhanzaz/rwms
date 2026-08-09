package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanLineRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryCompletionStatisticsRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryStatisticsLineRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryValidationItemRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryValidationSnapshotRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.time.OffsetDateTime;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Persisted validation, plan-line and statistic stores used to calculate frozen inventory totals.
 *
 * <p>The support has no command or remote-effect dependency; callers decide when a calculation is
 * previewed versus persisted as part of a terminal transaction.
 */
abstract class InventoryStatisticsWorkflowSupport extends InventoryTechnicalRuntimeSupport {
  protected final InventoryFindingRepository findings;
  protected final FindingPlanLineRepository planLines;
  protected final InventoryValidationSnapshotRepository validationSnapshots;
  protected final InventoryValidationItemRepository validationItems;
  protected final InventoryCompletionStatisticsRepository completionStatistics;
  protected final InventoryStatisticsLineRepository statisticsLines;

  protected InventoryStatisticsWorkflowSupport(
      InventoryFindingRepository findings,
      FindingPlanLineRepository planLines,
      InventoryValidationSnapshotRepository validationSnapshots,
      InventoryValidationItemRepository validationItems,
      InventoryCompletionStatisticsRepository completionStatistics,
      InventoryStatisticsLineRepository statisticsLines,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(mapper, canonicalJson, authorizer, transactionManager);
    this.findings = findings;
    this.planLines = planLines;
    this.validationSnapshots = validationSnapshots;
    this.validationItems = validationItems;
    this.completionStatistics = completionStatistics;
    this.statisticsLines = statisticsLines;
  }

  protected OffsetDateTime terminalAt(InventorySession session) {
    return session.getCompletedAt() != null ? session.getCompletedAt() : session.getCancelledAt();
  }
}
