package dev.buhanzaz.rwms.maintenance.eventing;

import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.repository.CatalogLinkRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogNodeRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogVersionRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimateLineRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimatePlanStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimateRevisionRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceMediaReferenceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** One canonical, complete non-secret representation of every live JPA aggregate projection. */
@Component
public class MaintenanceProjectionSnapshotFactory {
  private final CatalogVersionRepository catalogs;
  private final CatalogNodeRepository nodes;
  private final CatalogLinkRepository links;
  private final MaintenanceEstimateRepository estimates;
  private final EstimateRevisionRepository revisions;
  private final EstimateLineRepository lines;
  private final EstimatePlanStageRepository plans;
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository stages;
  private final MaintenanceMediaReferenceRepository media;
  private final ObjectMapper mapper;

  public MaintenanceProjectionSnapshotFactory(
      CatalogVersionRepository catalogs,
      CatalogNodeRepository nodes,
      CatalogLinkRepository links,
      MaintenanceEstimateRepository estimates,
      EstimateRevisionRepository revisions,
      EstimateLineRepository lines,
      EstimatePlanStageRepository plans,
      MaintenanceRepairRepository repairs,
      RepairStageRepository stages,
      MaintenanceMediaReferenceRepository media,
      ObjectMapper mapper) {
    this.catalogs = catalogs;
    this.nodes = nodes;
    this.links = links;
    this.estimates = estimates;
    this.revisions = revisions;
    this.lines = lines;
    this.plans = plans;
    this.repairs = repairs;
    this.stages = stages;
    this.media = media;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> snapshot(MaintenanceAggregateType type, UUID id) {
    return switch (type) {
      case CATALOG_VERSION -> catalog(catalogs.findById(id).orElseThrow(() -> missing(type, id)));
      case ESTIMATE -> estimate(estimates.findById(id).orElseThrow(() -> missing(type, id)));
      case REPAIR -> repair(repairs.findById(id).orElseThrow(() -> missing(type, id)));
    };
  }

  @Transactional(readOnly = true)
  public Map<AggregateRef, Map<String, Object>> allSnapshots() {
    Map<AggregateRef, Map<String, Object>> result = new LinkedHashMap<>();
    catalogs.findAll().stream()
        .sorted(Comparator.comparing(value -> value.getId().toString()))
        .forEach(value -> put(result, MaintenanceAggregateType.CATALOG_VERSION, value.getId(), catalog(value)));
    estimates.findAll().stream()
        .sorted(Comparator.comparing(value -> value.getId().toString()))
        .forEach(value -> put(result, MaintenanceAggregateType.ESTIMATE, value.getId(), estimate(value)));
    repairs.findAll().stream()
        .sorted(Comparator.comparing(value -> value.getId().toString()))
        .forEach(value -> put(result, MaintenanceAggregateType.REPAIR, value.getId(), repair(value)));
    return Collections.unmodifiableMap(result);
  }

  public Map<String, Object> catalog(CatalogVersion value) {
    Map<String, Object> result = base(value.getId(), value.getVersion(), value.getWarehouseId());
    result.put("lifecycle", value.getState().name());
    result.put("sourceSha256", value.getSourceSha256());
    result.put("nodeCount", value.getNodeCount());
    result.put("linkCount", value.getLinkCount());
    result.put("validationReport", json(value.getValidationReport()));
    result.put("activatedAt", text(value.getActivatedAt()));
    result.put("createdAt", text(value.getCreatedAt()));
    result.put("updatedAt", text(value.getUpdatedAt()));
    result.put("nodes", nodes.findAllByCatalogVersionIdOrderByCode(value.getId()).stream().map(node -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", node.getId().toString());
      item.put("code", node.getCode());
      item.put("nodeType", node.getNodeType());
      item.put("name", node.getName());
      item.put("active", node.isActive());
      item.put("parentNodeId", text(node.getParentNodeId()));
      item.put("unit", node.getUnit());
      item.put("priceMinor", node.getPriceMinor());
      item.put("durationMinutes", node.getDurationMinutes());
      item.put("includeInEstimate", node.isIncludeInEstimate());
      item.put("commonItem", node.isCommonItem());
      item.put("showInMainMenu", node.isShowInMainMenu());
      item.put("canvasX", node.getCanvasX());
      item.put("canvasY", node.getCanvasY());
      if (node.getRoutingQueueId() == null) {
        item.put("routing", null);
      } else {
        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("queueId", node.getRoutingQueueId().toString());
        routing.put("queueCode", node.getRoutingQueueCode());
        routing.put("queueKind", node.getRoutingQueueKind());
        item.put("routing", routing);
      }
      item.put("references", jsonValue(node.getOpaqueReferences()));
      item.put("comment", node.getComment());
      return item;
    }).toList());
    result.put("links", links.findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(value.getId()).stream().map(link -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", link.getId().toString());
      item.put("sourceNodeId", link.getSourceNodeId().toString());
      item.put("targetNodeId", link.getTargetNodeId().toString());
      item.put("linkType", link.getLinkType());
      item.put("sourceAnchor", link.getSourceAnchor());
      item.put("targetAnchor", link.getTargetAnchor());
      item.put("sortOrder", link.getSortOrder());
      return item;
    }).toList());
    return immutable(result);
  }

  public Map<String, Object> estimate(MaintenanceEstimate value) {
    Map<String, Object> result = base(value.getId(), value.getVersion(), value.getWarehouseId());
    result.put("rentalItemId", value.getRentalItemId().toString());
    result.put("rentalItemVersionSnapshot", value.getRentalItemVersionSnapshot());
    result.put("catalogVersionId", value.getCatalogVersionId().toString());
    result.put("lifecycle", value.getState().name());
    result.put("currentRevision", value.getRevision());
    result.put("dispatchDate", text(value.getDispatchDate()));
    result.put("sourceParty", value.getSourceParty());
    result.put("comment", value.getComment());
    result.put("repairId", text(value.getRepairId()));
    result.put("completedAt", text(value.getCompletedAt()));
    result.put("actor", json(value.getActorRef()));
    result.put("createdAt", text(value.getCreatedAt()));
    result.put("updatedAt", text(value.getUpdatedAt()));
    result.put("revisions", revisions.findAllByEstimateIdOrderByRevision(value.getId()).stream()
        .map(revision -> {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("revision", revision.getRevision());
          item.put("dispatchDate", text(revision.getDispatchDate()));
          item.put("sourceParty", revision.getSourceParty());
          item.put("reason", revision.getAmendmentReason());
          item.put("totalMinor", revision.getTotalMinor());
          item.put("actor", json(revision.getActorRef()));
          item.put("recordedAt", text(revision.getRecordedAt()));
          item.put("lines", lines.findAllByEstimateIdAndEstimateRevisionOrderByLineNo(
              value.getId(), revision.getRevision()).stream().map(line -> {
                Map<String, Object> lineState = new LinkedHashMap<>();
                lineState.put("id", line.getId().toString());
                lineState.put("lineNo", line.getLineNo());
                lineState.put("catalogNodeId", text(line.getCatalogNodeId()));
                lineState.put("lineType", line.getLineType());
                lineState.put("title", line.getTitle());
                lineState.put("quantity", line.getQuantity().setScale(6).toPlainString());
                lineState.put("unitPriceMinor", line.getUnitPriceMinor());
                lineState.put("durationMinutes", line.getDurationMinutes());
                lineState.put("queueRef", line.getQueueRef());
                lineState.put("catalogSnapshot", line.getCatalogSnapshot() == null
                    ? null : json(line.getCatalogSnapshot()));
                lineState.put("comment", line.getComment());
                lineState.put("mediaReferences", jsonValue(line.getMediaReferences()));
                return lineState;
              }).toList());
          item.put("plan", plans.findAllByEstimateIdAndEstimateRevisionOrderByStageNo(
              value.getId(), revision.getRevision()).stream().map(stage -> {
                Map<String, Object> stageState = new LinkedHashMap<>();
                stageState.put("id", stage.getId().toString());
                stageState.put("stageNo", stage.getStageNo());
                stageState.put("kind", stage.getStageKind().name());
                stageState.put("routingQueueId", stage.getRoutingQueueId().toString());
                stageState.put("routingQueueCode", stage.getRoutingQueueCode());
                stageState.put("routingQueueKind", stage.getRoutingQueueKind());
                stageState.put("taskDeadline", text(stage.getTaskDeadline()));
                return stageState;
              }).toList());
          return item;
        }).toList());
    result.put("media", media("ESTIMATE", value.getId()));
    return immutable(result);
  }

  public Map<String, Object> repair(MaintenanceRepair value) {
    Map<String, Object> result = base(value.getId(), value.getVersion(), value.getWarehouseId());
    result.put("rootRepairId", text(value.getRootRepairId() == null ? value.getId() : value.getRootRepairId()));
    result.put("sourceRepairId", text(value.getSourceRepairId()));
    result.put("estimateId", text(value.getEstimateId()));
    result.put("rentalItemId", value.getRentalItemId().toString());
    result.put("rentalItemVersionSnapshot", value.getRentalItemVersionSnapshot());
    result.put("origin", value.getOrigin().name());
    result.put("kind", value.getKind().name());
    result.put("executionState", value.getExecutionState().name());
    result.put("acceptanceState", value.getAcceptanceState().name());
    result.put("dispatchDate", text(value.getDispatchDate()));
    result.put("sourceParty", value.getSourceParty());
    result.put("reworkReason", value.getReworkReason());
    result.put("decisionReason", value.getDecisionReason());
    result.put("decisionActor", value.getDecisionActorRef() == null
        ? null : json(value.getDecisionActorRef()));
    result.put("decisionRecordedAt", text(value.getDecisionRecordedAt()));
    result.put("actor", json(value.getActorRef()));
    result.put("externalTaskId", value.getExternalTaskId().toString());
    result.put("taskGenerationState", value.getTaskGenerationState());
    result.put("deliveryState", value.getDeliveryState());
    result.put("deliveryAttempts", value.getDeliveryAttempts());
    result.put("deliveryUpdatedAt", text(value.getDeliveryUpdatedAt()));
    result.put("taskBoardVersion", value.getTaskBoardVersion());
    result.put("reconciliationState", value.getReconciliationState());
    result.put("createdAt", text(value.getCreatedAt()));
    result.put("updatedAt", text(value.getUpdatedAt()));
    if (value.getLeaseId() == null) {
      result.put("lease", null);
    } else {
      Map<String, Object> lease = new LinkedHashMap<>();
      lease.put("leaseId", value.getLeaseId().toString());
      lease.put("leaseVersion", value.getLeaseVersion());
      lease.put("fencingToken", value.getFencingToken());
      lease.put("expiresAt", text(value.getLeaseExpiresAt()));
      lease.put("reconciliationState", value.getLeaseReconciliationState());
      result.put("lease", lease);
    }
    result.put("stages", stages.findAllByRepairIdOrderByStageNo(value.getId()).stream().map(stage -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", stage.getId().toString());
      item.put("stageNo", stage.getStageNo());
      item.put("kind", stage.getStageKind().name());
      item.put("state", stage.getState().name());
      item.put("routingQueueId", stage.getRoutingQueueId().toString());
      item.put("routingQueueCode", stage.getRoutingQueueCode());
      item.put("routingQueueKind", stage.getRoutingQueueKind());
      item.put("externalQueueEntryId", text(stage.getExternalQueueEntryId()));
      item.put("taskBoardVersion", stage.getTaskBoardVersion());
      item.put("taskGenerationState", stage.getTaskGenerationState());
      item.put("deliveryState", stage.getDeliveryState());
      item.put("deliveryAttempts", stage.getDeliveryAttempts());
      item.put("deliveryUpdatedAt", text(stage.getDeliveryUpdatedAt()));
      item.put("taskDeadline", text(stage.getTaskDeadline()));
      item.put("completedEventId", text(stage.getCompletedEventId()));
      item.put("completedAt", text(stage.getCompletedAt()));
      return item;
    }).toList());
    result.put("media", media("REPAIR", value.getId()));
    return immutable(result);
  }

  private java.util.List<Map<String, Object>> media(String type, UUID id) {
    return media.findAllByAggregateTypeAndAggregateIdOrderByMediaId(type, id).stream().map(value -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("mediaId", value.getMediaId().toString());
      item.put("generation", value.getGeneration());
      item.put("ownerType", value.getOwnerType());
      item.put("warehouseId", value.getWarehouseId().toString());
      item.put("safeMetadata", json(value.getSafeMetadata()));
      item.put("attachedAt", text(value.getAttachedAt()));
      return item;
    }).toList();
  }

  private static void put(
      Map<AggregateRef, Map<String, Object>> target,
      MaintenanceAggregateType type,
      UUID id,
      Map<String, Object> state) {
    if (target.put(new AggregateRef(type, id), state) != null) {
      throw new IllegalStateException("Duplicate maintenance live aggregate " + type + ":" + id);
    }
  }

  private static Map<String, Object> base(UUID id, long version, UUID warehouseId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id.toString());
    result.put("version", version);
    result.put("warehouseId", warehouseId.toString());
    return result;
  }

  private Map<String, Object> json(String value) {
    try {
      return mapper.readValue(value, new TypeReference<Map<String, Object>>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Maintenance projection contains invalid JSON", exception);
    }
  }

  private Object jsonValue(String value) {
    try {
      return mapper.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Maintenance projection contains invalid JSON", exception);
    }
  }

  private static String text(Object value) {
    if (value == null) return null;
    if (value instanceof OffsetDateTime dateTime) {
      return postgresTimestamp(dateTime).toString();
    }
    return value.toString();
  }

  private static OffsetDateTime postgresTimestamp(OffsetDateTime value) {
    OffsetDateTime utc = value.withOffsetSameInstant(ZoneOffset.UTC);
    int microseconds = (utc.getNano() + 500) / 1_000;
    OffsetDateTime seconds = utc.withNano(0);
    return microseconds == 1_000_000
        ? seconds.plusSeconds(1)
        : seconds.withNano(microseconds * 1_000);
  }

  private static Map<String, Object> immutable(Map<String, Object> value) {
    return Collections.unmodifiableMap(value);
  }

  private static IllegalStateException missing(MaintenanceAggregateType type, UUID id) {
    return new IllegalStateException("Maintenance live JPA projection is missing for " + type + ":" + id);
  }

  public record AggregateRef(MaintenanceAggregateType type, UUID id)
      implements Comparable<AggregateRef> {
    public AggregateRef {
      if (type == null || id == null) {
        throw new IllegalArgumentException("Maintenance aggregate reference is required");
      }
    }

    @Override
    public int compareTo(AggregateRef other) {
      int byType = type.name().compareTo(other.type.name());
      return byType == 0 ? id.toString().compareTo(other.id.toString()) : byType;
    }
  }
}
