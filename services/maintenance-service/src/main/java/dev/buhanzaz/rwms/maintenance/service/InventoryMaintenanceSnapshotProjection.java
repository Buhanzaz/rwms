package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Builds read-only inventory repair facts from maintenance repairs and their frozen stage
 * evidence, including a semantic plan fingerprint independent of operational state changes.
 */
@Component
final class InventoryMaintenanceSnapshotProjection {
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final ObjectMapper mapper;

  InventoryMaintenanceSnapshotProjection(
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      ObjectMapper mapper) {
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.mapper = mapper;
  }

  InventoryRepairSnapshotsResponse repairSnapshots(InventoryRepairSnapshotRequest request) {
    List<UUID> assetIds = request.assetIds().stream().sorted().toList();
    List<MaintenanceRepair> selected =
        repairs.findAllByRentalItemIdInOrderByRentalItemIdAscIdAsc(assetIds).stream()
            .sorted(Comparator.comparing(MaintenanceRepair::getRentalItemId)
                .thenComparing(MaintenanceRepair::getId))
            .toList();
    List<UUID> repairIds = selected.stream().map(MaintenanceRepair::getId).toList();
    Map<UUID, List<RepairStage>> stagesByRepair = new LinkedHashMap<>();
    if (!repairIds.isEmpty()) {
      for (RepairStage stage :
          repairStages.findAllByRepairIdInOrderByRepairIdAscStageNoAscIdAsc(repairIds)) {
        stagesByRepair.computeIfAbsent(stage.getRepairId(), ignored -> new ArrayList<>()).add(stage);
      }
      stagesByRepair.values().forEach(stages -> stages.sort(
          Comparator.comparingInt(RepairStage::getStageNo).thenComparing(RepairStage::getId)));
    }

    Map<UUID, List<InventoryRepairFact>> repairsByAsset = new LinkedHashMap<>();
    for (MaintenanceRepair repair : selected) {
      List<RepairStage> stages = stagesByRepair.getOrDefault(repair.getId(), List.of());
      InventoryRepairFact fact = new InventoryRepairFact(
          repair.getId(),
          repair.getRootRepairId() == null ? repair.getId() : repair.getRootRepairId(),
          repair.getOrigin(),
          repair.getKind(),
          repair.getExecutionState(),
          repair.getAcceptanceState(),
          hash(inventoryPlanSnapshot(stages)));
      repairsByAsset
          .computeIfAbsent(repair.getRentalItemId(), ignored -> new ArrayList<>())
          .add(fact);
    }
    List<InventoryRepairSnapshot> assets = assetIds.stream()
        .map(assetId -> new InventoryRepairSnapshot(
            assetId, List.copyOf(repairsByAsset.getOrDefault(assetId, List.of()))))
        .toList();
    return new InventoryRepairSnapshotsResponse(assets);
  }

  private List<Map<String, Object>> inventoryPlanSnapshot(List<RepairStage> stages) {
    return stages.stream().map(stage -> {
      Map<String, Object> routing = new LinkedHashMap<>();
      routing.put("queueId", stage.getRoutingQueueId());
      routing.put("queueName", stage.getRoutingQueueName());
      routing.put("queueType", stage.getRoutingQueueType());

      Map<String, Object> value = new LinkedHashMap<>();
      value.put("kind", stage.getStageKind().name());
      value.put("order", stage.getStageNo());
      value.put("routing", routing);
      value.put("workLines", semanticJson(stage.getWorkLines()));
      value.put("materialLines", semanticJson(stage.getMaterialLines()));
      value.put("primaryLineId", stage.getPrimaryLineId());
      value.put("groupComment", stage.getGroupComment());
      return value;
    }).toList();
  }

  private Object semanticJson(String value) {
    try {
      return mapper.readValue(value, Object.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored repair plan content is invalid", exception);
    }
  }

  private String hash(Object value) {
    try {
      String canonical = mapper.writer()
          .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .writeValueAsString(value);
      return MaintenanceChecksum.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException(
          "Inventory maintenance value cannot be canonicalized", exception);
    }
  }
}
