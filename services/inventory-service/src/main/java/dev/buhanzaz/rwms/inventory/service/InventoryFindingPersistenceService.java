package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.domain.FindingMediaReference;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanLine;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanStage;
import dev.buhanzaz.rwms.inventory.domain.InventoryMediaFactProjection;
import dev.buhanzaz.rwms.inventory.domain.InventorySourceAttachment;
import dev.buhanzaz.rwms.inventory.repository.FindingMediaReferenceRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanLineRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanStageRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryMediaFactProjectionRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySourceAttachmentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Repository-only persistence collaborator for finding source, media and frozen-plan facts.
 *
 * <p>It deliberately has no authorization, workflow, event, remote-call or retry decisions.
 * Finding use cases keep those decisions and use these semantic persistence operations inside their
 * existing transaction boundaries.
 */
@Service
final class InventoryFindingPersistenceService {
  private final InventorySourceAttachmentRepository sourceAttachments;
  private final FindingMediaReferenceRepository mediaReferences;
  private final InventoryMediaFactProjectionRepository mediaFacts;
  private final FindingPlanSnapshotRepository planSnapshots;
  private final FindingPlanLineRepository planLines;
  private final FindingPlanStageRepository planStages;

  InventoryFindingPersistenceService(
      InventorySourceAttachmentRepository sourceAttachments,
      FindingMediaReferenceRepository mediaReferences,
      InventoryMediaFactProjectionRepository mediaFacts,
      FindingPlanSnapshotRepository planSnapshots,
      FindingPlanLineRepository planLines,
      FindingPlanStageRepository planStages) {
    this.sourceAttachments = sourceAttachments;
    this.mediaReferences = mediaReferences;
    this.mediaFacts = mediaFacts;
    this.planSnapshots = planSnapshots;
    this.planLines = planLines;
    this.planStages = planStages;
  }

  Optional<InventorySourceAttachment> findSourceAttachment(UUID inventoryId, UUID findingId) {
    return sourceAttachments.findByInventoryIdAndFindingId(inventoryId, findingId);
  }

  InventorySourceAttachment saveSourceAttachment(InventorySourceAttachment attachment) {
    return sourceAttachments.saveAndFlush(attachment);
  }

  List<FindingMediaReference> findingMediaReferences(UUID findingId, long findingRevision) {
    return mediaReferences.findAllByFindingIdAndFindingRevisionOrderByMediaIdAscGenerationAsc(
        findingId, findingRevision);
  }

  long mediaCount(UUID findingId, long findingRevision) {
    return mediaReferences.countByFindingIdAndFindingRevision(findingId, findingRevision);
  }

  FindingMediaReference saveMediaReference(FindingMediaReference reference) {
    return mediaReferences.save(reference);
  }

  Optional<InventoryMediaFactProjection> findLatestFindingMediaFact(
      UUID mediaId, UUID findingId, UUID warehouseId) {
    return mediaFacts
        .findFirstByMediaIdAndOwnerTypeAndOwnerIdAndWarehouseIdOrderByAggregateVersionDesc(
            mediaId, "INVENTORY_FINDING", findingId, warehouseId);
  }

  Optional<InventoryMediaFactProjection> findReadyFindingMediaFact(
      UUID mediaId, long generation, UUID findingId, UUID warehouseId) {
    return mediaFacts.findByMediaIdAndGenerationAndOwnerTypeAndOwnerIdAndWarehouseIdAndMediaStatus(
        mediaId, generation, "INVENTORY_FINDING", findingId, warehouseId, "READY");
  }

  Optional<FindingPlanSnapshot> findPlanSnapshot(UUID findingId, long findingRevision) {
    return planSnapshots.findByFindingIdAndFindingRevision(findingId, findingRevision);
  }

  FindingPlanSnapshot savePlanSnapshot(FindingPlanSnapshot snapshot) {
    return planSnapshots.saveAndFlush(snapshot);
  }

  FindingPlanLine savePlanLine(FindingPlanLine line) {
    return planLines.save(line);
  }

  FindingPlanStage savePlanStage(FindingPlanStage stage) {
    return planStages.save(stage);
  }
}
