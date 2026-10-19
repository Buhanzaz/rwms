package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.EstimateLine;
import dev.buhanzaz.rwms.maintenance.domain.EstimatePlanStage;
import dev.buhanzaz.rwms.maintenance.domain.EstimateRevision;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.repository.EstimateLineRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimatePlanStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimateRevisionRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
    return estimateResponses(List.of(value)).getFirst();
  }

  /** Hydrates the bounded page with four bulk reads instead of one query per revision and aggregate. */
  protected List<EstimateResponse> estimateResponses(Collection<MaintenanceEstimate> values) {
    if (values.isEmpty()) return List.of();
    List<UUID> estimateIds = values.stream().map(MaintenanceEstimate::getId).toList();
    Map<UUID, List<EstimateRevision>> revisionsByEstimate =
        estimateRevisions
            .findAllByEstimateIdInOrderByEstimateIdAscRevisionAsc(estimateIds)
            .stream()
            .collect(Collectors.groupingBy(EstimateRevision::getEstimateId));
    Map<RevisionKey, List<EstimateLine>> linesByRevision =
        estimateLines
            .findAllByEstimateIdInOrderByEstimateIdAscEstimateRevisionAscLineNoAsc(estimateIds)
            .stream()
            .collect(
                Collectors.groupingBy(
                    line -> new RevisionKey(line.getEstimateId(), line.getEstimateRevision())));
    Map<RevisionKey, List<EstimatePlanStage>> plansByRevision =
        estimatePlans
            .findAllByEstimateIdInOrderByEstimateIdAscEstimateRevisionAscStageNoAsc(estimateIds)
            .stream()
            .collect(
                Collectors.groupingBy(
                    stage -> new RevisionKey(stage.getEstimateId(), stage.getEstimateRevision())));
    Map<UUID, List<MediaReferenceInput>> mediaByEstimate =
        mediaSupport.mediaByAggregateIds("ESTIMATE", estimateIds);
    return values.stream()
        .map(
            value ->
                estimateResponse(
                    value, revisionsByEstimate, linesByRevision, plansByRevision, mediaByEstimate))
        .toList();
  }

  private EstimateResponse estimateResponse(
      MaintenanceEstimate value,
      Map<UUID, List<EstimateRevision>> revisionsByEstimate,
      Map<RevisionKey, List<EstimateLine>> linesByRevision,
      Map<RevisionKey, List<EstimatePlanStage>> plansByRevision,
      Map<UUID, List<MediaReferenceInput>> mediaByEstimate) {
    List<EstimateRevisionResponse> revisions = revisionsByEstimate
        .getOrDefault(value.getId(), List.of()).stream()
        .map(revision -> {
          RevisionKey key = new RevisionKey(value.getId(), revision.getRevision());
          return new EstimateRevisionResponse(
              revision.getRevision(), revision.getDispatchDate(), revision.getSourceParty(),
              lineResponses(linesByRevision.getOrDefault(key, List.of())),
              planResponses(plansByRevision.getOrDefault(key, List.of())),
              commandSupport.money(revision.getTotalMinor()), revision.getAmendmentReason(),
              revision.getRecordedAt(), revision.isForceCapitalRepair());
        })
        .toList();
    List<MediaReferenceInput> aggregateMedia =
        mediaByEstimate.getOrDefault(value.getId(), List.of());
    return new EstimateResponse(
        value.getId(), value.getWarehouseId(), value.getRentalItemId(), value.getVersion(),
        value.getState(), value.getRevision(), revisions, value.getRepairId(), aggregateMedia,
        mediaSupport.effectiveCoverMediaId(value.getCoverMediaId(), aggregateMedia),
        value.getCreatedAt(), value.getCompletedAt(), commandSupport.actor(value.getActorRef()),
        value.isForceCapitalRepair());
  }

  private record RevisionKey(UUID estimateId, int revision) {}

  private List<EstimateLineResponse> lineResponses(List<EstimateLine> lines) {
    return lines.stream().map(this::lineResponse).toList();
  }

  private EstimateLineResponse lineResponse(EstimateLine line) {
    return new EstimateLineResponse(
        line.getId(), line.getCatalogSnapshot() == null ? null
            : commandSupport.read(line.getCatalogSnapshot(), CatalogNodeSnapshot.class),
        EstimateLineType.valueOf(line.getLineType()), line.getTitle(), line.getUnit(),
        commandSupport.quantity(line.getQuantity()), commandSupport.money(line.getUnitPriceMinor()),
        commandSupport.money(line.getQuantity().multiply(BigDecimal.valueOf(line.getUnitPriceMinor()))),
        requiredStoredDuration(line.getLineType(), line.getDurationMinutes()), line.getComment(),
        commandSupport.readList(line.getMediaReferences(), MediaReferenceInput.class));
  }

  protected List<EstimateLineResponse> lineResponses(UUID estimateId, int revision) {
    return lineResponses(
        estimateLines.findAllByEstimateIdAndEstimateRevisionOrderByLineNo(estimateId, revision));
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

  private List<PlanStageInput> planResponses(List<EstimatePlanStage> plans) {
    return plans.stream().map(this::planResponse).toList();
  }

  private PlanStageInput planResponse(EstimatePlanStage stage) {
    return new PlanStageInput(
        stage.getId(), stage.getStageKind(), stage.getStageNo(),
        new RoutingSnapshot(
            stage.getRoutingQueueId(), stage.getRoutingQueueName(), stage.getRoutingQueueType()),
        commandSupport.readList(stage.getIncludedLineIds(), UUID.class),
        stage.getPrimaryLineId(), stage.getGroupComment(), stage.getTaskDeadline());
  }

  protected List<PlanStageInput> planResponses(UUID estimateId, int revision) {
    return planResponses(
        estimatePlans.findAllByEstimateIdAndEstimateRevisionOrderByStageNo(estimateId, revision));
  }
}
