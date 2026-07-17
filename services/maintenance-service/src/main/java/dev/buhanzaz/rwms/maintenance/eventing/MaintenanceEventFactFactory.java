package dev.buhanzaz.rwms.maintenance.eventing;

import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.CatalogVersionFact;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.CompletionKind;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.DeliveryState;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.EstimateFact;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.GenerationState;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.MaintenanceIntegrationFact;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.RepairFact;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.RepairStageFact;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventPayloads.TaskSyncFact;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceChecksum;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Converts current JPA projections into their exact, text-free integration facts. */
@Component
public class MaintenanceEventFactFactory {
  private final ObjectMapper mapper;
  private final MaintenanceEventPayloadPolicy policy;

  public MaintenanceEventFactFactory(ObjectMapper mapper, MaintenanceEventPayloadPolicy policy) {
    this.mapper = mapper;
    this.policy = policy;
  }

  public CatalogVersionFact catalogVersion(CatalogVersion value) {
    return new CatalogVersionFact(
        value.getId(),
        value.getWarehouseId(),
        value.getState(),
        value.getSourceSha256(),
        value.getNodeCount(),
        value.getLinkCount(),
        MaintenanceChecksum.sha256(value.getValidationReport().getBytes(StandardCharsets.UTF_8)));
  }

  public EstimateFact estimate(MaintenanceEstimate value, int lineCount) {
    CompletionKind completionKind = value.getState() == EstimateState.DRAFT
        ? CompletionKind.NOT_COMPLETED
        : lineCount == 0 ? CompletionKind.EMPTY : CompletionKind.NON_EMPTY;
    return new EstimateFact(
        value.getId(),
        value.getWarehouseId(),
        value.getRentalItemId(),
        value.getState(),
        value.getRevision(),
        value.getDispatchDate(),
        lineCount,
        completionKind,
        value.getRepairId());
  }

  public RepairFact repair(MaintenanceRepair value, List<RepairStage> stages) {
    List<RepairStageFact> stageFacts = stages.stream()
        .map(stage -> new RepairStageFact(
            stage.getId(),
            stage.getStageKind(),
            stage.getStageNo(),
            stage.getState(),
            stage.getRoutingQueueId(),
            new TaskSyncFact(
                value.getExternalTaskId(),
                stage.getTaskBoardVersion(),
                GenerationState.valueOf(stage.getTaskGenerationState()),
                DeliveryState.valueOf(stage.getDeliveryState()))))
        .toList();
    return new RepairFact(
        value.getId(),
        value.getRootRepairId() == null ? value.getId() : value.getRootRepairId(),
        value.getSourceRepairId(),
        value.getEstimateId(),
        value.getWarehouseId(),
        value.getRentalItemId(),
        value.getOrigin(),
        value.getKind(),
        value.getExecutionState(),
        value.getAcceptanceState(),
        value.getDispatchDate(),
        stageFacts);
  }

  public Map<String, Object> catalogPayload(
      MaintenanceEventType eventType, CatalogVersion value) {
    return payload(eventType, MaintenanceAggregateType.CATALOG_VERSION, value.getId(), catalogVersion(value));
  }

  public Map<String, Object> estimatePayload(
      MaintenanceEventType eventType, MaintenanceEstimate value, int lineCount) {
    return payload(eventType, MaintenanceAggregateType.ESTIMATE, value.getId(), estimate(value, lineCount));
  }

  public Map<String, Object> repairPayload(
      MaintenanceEventType eventType, MaintenanceRepair value, List<RepairStage> stages) {
    return payload(eventType, MaintenanceAggregateType.REPAIR, value.getId(), repair(value, stages));
  }

  private Map<String, Object> payload(
      MaintenanceEventType eventType,
      MaintenanceAggregateType aggregateType,
      java.util.UUID aggregateId,
      MaintenanceIntegrationFact fact) {
    JsonNode node = policy.validateAndConvert(eventType, aggregateType, aggregateId, fact);
    return mapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
  }
}
