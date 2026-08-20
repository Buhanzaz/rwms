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
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

  /**
   * Copies one finding revision's exact media set to its immediately succeeding non-media
   * revision. An already identical target is idempotent, while any different target set fails the
   * surrounding transaction instead of mixing evidence from two revisions.
   */
  void carryForwardMediaReferences(
      UUID findingId, long sourceRevision, long targetRevision) {
    if (findingId == null || sourceRevision < 0 || targetRevision < 0) {
      throw new IllegalArgumentException("Finding media revision identity is invalid");
    }
    if (sourceRevision == targetRevision) {
      return;
    }
    if (targetRevision != Math.addExact(sourceRevision, 1)) {
      throw new IllegalArgumentException(
          "Finding media may only be carried to the immediately succeeding revision");
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Finding media carry-forward requires a transaction");
    }
    List<FindingMediaReference> source = findingMediaReferences(findingId, sourceRevision);
    if (source.isEmpty()) {
      return;
    }
    List<FindingMediaReference> target = findingMediaReferences(findingId, targetRevision);
    if (!target.isEmpty()) {
      if (sameMediaSet(source, target)) {
        return;
      }
      throw new IllegalStateException(
          "Target finding revision already contains different media evidence");
    }
    mediaReferences.saveAllAndFlush(
        source.stream()
            .map(
                reference ->
                    new FindingMediaReference(
                        findingId,
                        targetRevision,
                        reference.getMediaId(),
                        reference.getGeneration(),
                        reference.getMediaKind()))
            .toList());
  }

  private boolean sameMediaSet(
      List<FindingMediaReference> source, List<FindingMediaReference> target) {
    if (source.size() != target.size()) {
      return false;
    }
    for (int index = 0; index < source.size(); index++) {
      FindingMediaReference sourceReference = source.get(index);
      FindingMediaReference targetReference = target.get(index);
      if (!sourceReference.getMediaId().equals(targetReference.getMediaId())
          || sourceReference.getGeneration() != targetReference.getGeneration()
          || !sourceReference.getMediaKind().equals(targetReference.getMediaKind())) {
        return false;
      }
    }
    return true;
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
