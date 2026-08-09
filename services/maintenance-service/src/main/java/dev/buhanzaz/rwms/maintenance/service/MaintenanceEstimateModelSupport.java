package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.EstimateLine;
import dev.buhanzaz.rwms.maintenance.domain.EstimatePlanStage;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.repository.EstimateLineRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimatePlanStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimateRevisionRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Loads and maps estimate aggregates and immutable estimate-line views for maintenance workflows. */
@Service
final class MaintenanceEstimateModelSupport {
  private final MaintenanceEstimateRepository estimates;
  private final EstimateLineRepository estimateLines;
  private final EstimatePlanStageRepository estimatePlans;
  private final EstimateRevisionRepository estimateRevisions;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceMediaSupport mediaSupport;

  MaintenanceEstimateModelSupport(
      MaintenanceEstimateRepository estimates,
      EstimateLineRepository estimateLines,
      EstimatePlanStageRepository estimatePlans,
      EstimateRevisionRepository estimateRevisions,
      MaintenanceCommandSupport commandSupport,
      MaintenanceMediaSupport mediaSupport) {
    this.estimates = estimates;
    this.estimateLines = estimateLines;
    this.estimatePlans = estimatePlans;
    this.estimateRevisions = estimateRevisions;
    this.commandSupport = commandSupport;
    this.mediaSupport = mediaSupport;
  }

  protected List<EstimateLine> currentLines(MaintenanceEstimate estimate) {
    return estimateLines.findAllByEstimateIdAndEstimateRevisionOrderByLineNo(
        estimate.getId(), estimate.getRevision());
  }

  protected List<EstimatePlanStage> currentPlan(MaintenanceEstimate estimate) {
    return estimatePlans.findAllByEstimateIdAndEstimateRevisionOrderByStageNo(
        estimate.getId(), estimate.getRevision());
  }

  protected MaintenanceEstimate requireEstimate(UUID id) {
    return estimates.findById(id).orElseThrow(() -> new MaintenanceNotFoundException("Estimate not found"));
  }

  protected EstimateResponse estimateResponse(MaintenanceEstimate value) {
    List<EstimateRevisionResponse> revisions = estimateRevisions
        .findAllByEstimateIdOrderByRevision(value.getId()).stream()
        .map(revision -> new EstimateRevisionResponse(
            revision.getRevision(), revision.getDispatchDate(), revision.getSourceParty(),
            lineResponses(value.getId(), revision.getRevision()),
            planResponses(value.getId(), revision.getRevision()), commandSupport.money(revision.getTotalMinor()),
            revision.getAmendmentReason(), revision.getRecordedAt()))
        .toList();
    List<MediaReferenceInput> aggregateMedia = mediaSupport.media("ESTIMATE", value.getId());
    return new EstimateResponse(
        value.getId(), value.getWarehouseId(), value.getRentalItemId(), value.getVersion(),
        value.getState(), value.getRevision(), revisions, value.getRepairId(),
        aggregateMedia, mediaSupport.effectiveCoverMediaId(value.getCoverMediaId(), aggregateMedia),
        value.getCreatedAt(), value.getCompletedAt(),
        commandSupport.actor(value.getActorRef()));
  }

  protected List<EstimateLineResponse> lineResponses(UUID estimateId, int revision) {
    return estimateLines.findAllByEstimateIdAndEstimateRevisionOrderByLineNo(estimateId, revision).stream()
        .map(line -> new EstimateLineResponse(
            line.getId(), line.getCatalogSnapshot() == null ? null
                : commandSupport.read(line.getCatalogSnapshot(), CatalogNodeSnapshot.class),
            EstimateLineType.valueOf(line.getLineType()),
            line.getTitle(), line.getUnit(), commandSupport.quantity(line.getQuantity()),
            commandSupport.money(line.getUnitPriceMinor()),
            commandSupport.money(line.getQuantity().multiply(BigDecimal.valueOf(line.getUnitPriceMinor()))),
            requiredStoredDuration(line.getLineType(), line.getDurationMinutes()),
            line.getComment(), commandSupport.readList(line.getMediaReferences(), MediaReferenceInput.class)))
        .toList();
  }

  protected static int requiredStoredDuration(String lineType, Integer duration) {
    if (duration == null
        || ("WORK".equals(lineType) && duration < 1)
        || ("MATERIAL".equals(lineType) && duration != 0)) {
      throw new IllegalStateException(
          "Stored estimate line duration violates the canonical invariant");
    }
    return duration;
  }

  protected List<PlanStageInput> planResponses(UUID estimateId, int revision) {
    return estimatePlans.findAllByEstimateIdAndEstimateRevisionOrderByStageNo(estimateId, revision).stream()
        .map(stage -> new PlanStageInput(
            stage.getId(), stage.getStageKind(), stage.getStageNo(),
            new RoutingSnapshot(stage.getRoutingQueueId(), stage.getRoutingQueueName(),
                stage.getRoutingQueueType()),
            commandSupport.readList(stage.getIncludedLineIds(), UUID.class),
            stage.getPrimaryLineId(),
            stage.getGroupComment(),
            stage.getTaskDeadline()))
        .toList();
  }
}
