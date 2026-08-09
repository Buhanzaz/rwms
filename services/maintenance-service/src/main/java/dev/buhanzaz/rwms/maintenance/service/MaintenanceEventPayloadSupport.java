package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventFactFactory;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceProjectionSnapshotFactory;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Builds local, fact and projection payloads recorded with maintenance events. */
@Service
final class MaintenanceEventPayloadSupport {
  private final RepairStageRepository repairStages;
  private final MaintenanceEventFactFactory eventFacts;
  private final MaintenanceProjectionSnapshotFactory projectionSnapshots;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEstimateModelSupport estimateModelSupport;
  private final MaintenanceRepairModelSupport repairModelSupport;

  MaintenanceEventPayloadSupport(
      RepairStageRepository repairStages,
      MaintenanceEventFactFactory eventFacts,
      MaintenanceProjectionSnapshotFactory projectionSnapshots,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEstimateModelSupport estimateModelSupport,
      MaintenanceRepairModelSupport repairModelSupport) {
    this.repairStages = repairStages;
    this.eventFacts = eventFacts;
    this.projectionSnapshots = projectionSnapshots;
    this.commandSupport = commandSupport;
    this.estimateModelSupport = estimateModelSupport;
    this.repairModelSupport = repairModelSupport;
  }

  protected Map<String, Object> catalogLocal(CatalogVersion value) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("catalogVersionId", value.getId().toString());
    result.put("validationReport", commandSupport.jsonMap(value.getValidationReport()));
    return result;
  }

  protected Map<String, Object> catalogFact(
      MaintenanceEventType eventType, CatalogVersion value) {
    return eventFacts.catalogPayload(eventType, value);
  }

  protected Map<String, Object> catalogSnapshot(CatalogVersion value) {
    return projectionSnapshots.catalog(value);
  }

  protected Map<String, Object> estimateLocal(MaintenanceEstimate value) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("estimateId", value.getId().toString());
    if (value.getSourceParty() != null) result.put("sourceParty", value.getSourceParty());
    if (value.getComment() != null) result.put("comment", value.getComment());
    result.put(
        "lines",
        estimateModelSupport.currentLines(value).stream()
            .map(
                line -> {
                  Map<String, Object> lineState = new LinkedHashMap<>();
                  lineState.put("lineNo", line.getLineNo());
                  lineState.put("type", line.getLineType());
                  lineState.put("title", line.getTitle());
                  lineState.put("unit", line.getUnit());
                  lineState.put("quantity", line.getQuantity().toPlainString());
                  lineState.put("unitPriceMinor", line.getUnitPriceMinor());
                  return lineState;
                })
            .toList());
    return result;
  }

  protected Map<String, Object> estimateFact(
      MaintenanceEventType eventType, MaintenanceEstimate value) {
    return eventFacts.estimatePayload(eventType, value, estimateModelSupport.currentLines(value).size());
  }

  protected Map<String, Object> estimateSnapshot(MaintenanceEstimate value) {
    return projectionSnapshots.estimate(value);
  }

  protected Map<String, Object> repairLocal(MaintenanceRepair value) {
    return new LinkedHashMap<>(repairSnapshot(value));
  }

  protected Map<String, Object> repairFact(
      MaintenanceEventType eventType, MaintenanceRepair value) {
    return eventFacts.repairPayload(
        eventType, value, repairStages.findAllByRepairIdOrderByStageNo(value.getId()));
  }

  protected Map<String, Object> repairSnapshot(MaintenanceRepair value) {
    return projectionSnapshots.repair(value);
  }

  protected String ownerType(MaintenanceRepair repair) {
    MaintenanceRepair owner = repair.getRootRepairId() == null
        ? repair : repairModelSupport.requireRepair(repair.getRootRepairId());
    return owner.getEstimateId() == null ? "MAINTENANCE_REPAIR" : "MAINTENANCE_ESTIMATE";
  }

  protected String ownerId(MaintenanceRepair repair) {
    MaintenanceRepair owner = repair.getRootRepairId() == null
        ? repair : repairModelSupport.requireRepair(repair.getRootRepairId());
    if (owner.getEstimateId() != null) return owner.getEstimateId().toString();
    return owner.getId().toString();
  }

  protected Map<String, Object> decisionLocal(UUID repairId, String comment) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("repairId", repairId.toString());
    if (comment != null) result.put("comment", comment);
    return result;
  }
}
