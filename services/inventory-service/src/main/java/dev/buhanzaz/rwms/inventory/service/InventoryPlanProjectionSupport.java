package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.domain.FindingPlanLine;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanStage;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanLineRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanStageRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Private repository boundary for read-only frozen-plan projection input.
 *
 * <p>Consumers receive only grouped immutable plan data, never the individual repositories, so a
 * projection use case cannot turn this helper into a generic plan store.
 */
abstract class InventoryPlanProjectionSupport extends InventoryTechnicalRuntimeSupport {
  private final FindingPlanSnapshotRepository planSnapshots;
  private final FindingPlanLineRepository planLines;
  private final FindingPlanStageRepository planStages;

  protected InventoryPlanProjectionSupport(
      FindingPlanSnapshotRepository planSnapshots,
      FindingPlanLineRepository planLines,
      FindingPlanStageRepository planStages,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(mapper, canonicalJson, authorizer, transactionManager);
    this.planSnapshots = planSnapshots;
    this.planLines = planLines;
    this.planStages = planStages;
  }

  protected PlanProjectionData activePlanData(Set<UUID> findingIds) {
    if (findingIds.isEmpty()) {
      return new PlanProjectionData(Map.of(), Map.of(), Map.of());
    }
    Map<UUID, FindingPlanSnapshot> snapshots =
        planSnapshots.findActiveByFindingIds(findingIds).stream()
            .collect(
                Collectors.toMap(
                    FindingPlanSnapshot::getFindingId,
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    Map<UUID, List<FindingPlanLine>> lines =
        planLines.findActiveByFindingIds(findingIds).stream()
            .collect(
                Collectors.groupingBy(
                    FindingPlanLine::getFindingId, LinkedHashMap::new, Collectors.toList()));
    Map<UUID, List<FindingPlanStage>> stages =
        planStages.findActiveByFindingIds(findingIds).stream()
            .collect(
                Collectors.groupingBy(
                    FindingPlanStage::getFindingId, LinkedHashMap::new, Collectors.toList()));
    return new PlanProjectionData(snapshots, lines, stages);
  }

  /**
   * Batches the active snapshot, line and stage projections by finding for a single read pass.
   */
  protected record PlanProjectionData(
      Map<UUID, FindingPlanSnapshot> snapshots,
      Map<UUID, List<FindingPlanLine>> lines,
      Map<UUID, List<FindingPlanStage>> stages) {}
}
