package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.MediaReferenceInput;

import dev.buhanzaz.rwms.maintenance.domain.EstimateLine;
import dev.buhanzaz.rwms.maintenance.domain.EstimatePlanStage;
import dev.buhanzaz.rwms.maintenance.domain.EstimateRevision;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceMediaReference;
import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventFactFactory;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceProjectionSnapshotFactory;
import dev.buhanzaz.rwms.maintenance.repository.EstimateLineRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimatePlanStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimateRevisionRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceMediaReferenceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Materializes an AFTER_RENT inventory publication as its authoritative draft estimate and
 * records the corresponding maintenance events and media-owner proof.
 */
@Component
final class InventoryPublicationEstimateMaterialization {
  private static final UUID INVENTORY_ACTOR =
      UUID.nameUUIDFromBytes("inventory-service".getBytes(StandardCharsets.UTF_8));

  private final MaintenanceEstimateRepository estimates;
  private final EstimateLineRepository estimateLines;
  private final EstimatePlanStageRepository estimatePlans;
  private final EstimateRevisionRepository estimateRevisions;
  private final MaintenanceMediaReferenceRepository mediaReferences;
  private final MediaFactProjectionRepository mediaFacts;
  private final MaintenanceEventStore events;
  private final MaintenanceEventFactFactory eventFacts;
  private final MaintenanceProjectionSnapshotFactory projectionSnapshots;
  private final MaintenanceReconciliationStore reconciliations;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final ObjectMapper mapper;

  InventoryPublicationEstimateMaterialization(
      MaintenanceEstimateRepository estimates,
      EstimateLineRepository estimateLines,
      EstimatePlanStageRepository estimatePlans,
      EstimateRevisionRepository estimateRevisions,
      MaintenanceMediaReferenceRepository mediaReferences,
      MediaFactProjectionRepository mediaFacts,
      MaintenanceEventStore events,
      MaintenanceEventFactFactory eventFacts,
      MaintenanceProjectionSnapshotFactory projectionSnapshots,
      MaintenanceReconciliationStore reconciliations,
      WarehouseLifecycleOperations warehouseLifecycle,
      ObjectMapper mapper) {
    this.estimates = estimates;
    this.estimateLines = estimateLines;
    this.estimatePlans = estimatePlans;
    this.estimateRevisions = estimateRevisions;
    this.mediaReferences = mediaReferences;
    this.mediaFacts = mediaFacts;
    this.events = events;
    this.eventFacts = eventFacts;
    this.projectionSnapshots = projectionSnapshots;
    this.reconciliations = reconciliations;
    this.warehouseLifecycle = warehouseLifecycle;
    this.mapper = mapper;
  }

  InventoryPublicationCreatedTarget createEstimate(
      InventoryPublicationSourceId sourceId,
      UUID warehouseId,
      dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPublicationFindingInput finding,
      InventoryPublicationValidatedPlan publication,
      UUID incomingAdmissionWarehouseId,
      InventoryPublicationPlanMaterialization materialization) {
    if (!warehouseId.equals(incomingAdmissionWarehouseId)) {
      throw InventoryPublicationPlanValidation.RemotePreflightRequired.incoming(warehouseId);
    }
    dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.FrozenInventoryPlanSnapshot snapshot =
        publication.snapshot();
    MaintenanceEstimate draft = MaintenanceEstimate.create(
        warehouseId,
        finding.assetId(),
        finding.assetVersion(),
        snapshot.catalogVersionId(),
        finding.repairScheduledDate(),
        "Инвентаризация",
        null,
        inventoryActorJson());
    draft.selectInventoryPublication(
        finding.priority(), finding.movementToRepair(), finding.movementScheduledDate());
    draft.replaceCoverMediaId(snapshot.coverMediaId());
    MaintenanceEstimate estimate = estimates.saveAndFlush(draft);
    List<InventoryPublicationPublishedLine> lines = materialization.publicationLines(sourceId, snapshot);
    List<InventoryPublicationPublishedStage> stages =
        materialization.allocateStagesForEstimate(snapshot, lines);
    saveEstimatePlan(estimate, lines, stages);
    attachMedia(
        "ESTIMATE", "MAINTENANCE_ESTIMATE", estimate.getId(), warehouseId, publication.sourceMedia());
    Map<String, Object> state = projectionSnapshots.estimate(estimate);
    events.initialize(
        MaintenanceAggregateType.ESTIMATE,
        estimate.getId(),
        estimate.getVersion(),
        MaintenanceEventType.ESTIMATE_CREATED,
        state,
        eventFacts.estimatePayload(MaintenanceEventType.ESTIMATE_CREATED, estimate, lines.size()),
        state);
    reconciliations.enqueueMediaOwnerProof(
        "MAINTENANCE_ESTIMATE",
        estimate.getId(),
        warehouseId,
        estimate.getId(),
        estimate.getVersion(),
        true);
    warehouseLifecycle.recordOperation(
        estimate.getWarehouseId(), estimate.getId(), estimate.getCreatedAt());
    return new InventoryPublicationCreatedTarget(
        dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPublicationTargetKind.ESTIMATE,
        estimate.getId(),
        estimate.getId(),
        null);
  }

  private void saveEstimatePlan(
      MaintenanceEstimate estimate,
      List<InventoryPublicationPublishedLine> lines,
      List<InventoryPublicationPublishedStage> stages) {
    List<EstimateLine> storedLines = lines.stream()
        .map(
            line ->
                new EstimateLine(
                    line.response().id(),
                    estimate.getId(),
                    estimate.getRevision(),
                    line.sourceIndex(),
                    line.catalogNodeId(),
                    line.response().lineType().name(),
                    line.response().description(),
                    line.response().unit(),
                    line.quantity(),
                    line.unitPriceMinor(),
                    line.response().normativeMinutes(),
                    line.queueRef(),
                    line.catalogSnapshot() == null ? null : write(line.catalogSnapshot()),
                    line.response().comment(),
                    write(line.response().mediaReferences())))
        .toList();
    List<EstimatePlanStage> storedStages = stages.stream()
        .map(
            stage ->
                new EstimatePlanStage(
                    stage.stage().id(),
                    estimate.getId(),
                    estimate.getRevision(),
                    stage.stage().order(),
                    stage.stage().kind(),
                    stage.stage().routing().queueId(),
                    stage.stage().routing().queueName(),
                    stage.stage().routing().queueType(),
                    write(stage.lines().stream().map(line -> line.response().id()).toList()),
                    stage.primaryLineId(),
                    "",
                    null))
        .toList();
    estimateLines.saveAll(storedLines);
    estimatePlans.saveAll(storedStages);
    long totalMinor = storedLines.stream()
        .map(line -> line.getQuantity().multiply(BigDecimal.valueOf(line.getUnitPriceMinor())))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
    estimateRevisions.saveAndFlush(new EstimateRevision(
        estimate.getId(),
        estimate.getRevision(),
        estimate.getDispatchDate(),
        estimate.getSourceParty(),
        null,
        totalMinor,
        inventoryActorJson()));
  }

  private void attachMedia(
      String aggregateType,
      String ownerType,
      UUID aggregateId,
      UUID warehouseId,
      List<MediaReferenceInput> references) {
    if (references.isEmpty()) return;
    List<MaintenanceMediaReference> values = references.stream()
        .map(
            reference ->
                new MaintenanceMediaReference(
                    aggregateType,
                    aggregateId,
                    reference.mediaId(),
                    reference.generation(),
                    ownerType,
                    warehouseId,
                    mediaFacts.findById(reference.mediaId())
                        .map(MediaFactProjection::getSafeMetadata)
                        .orElse("{}")))
        .toList();
    mediaReferences.saveAll(values);
  }

  private String inventoryActorJson() {
    return write(Map.of("subjectId", INVENTORY_ACTOR.toString(), "principalType", "SERVICE"));
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory publication value cannot be serialized", exception);
    }
  }
}
