package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.CatalogLink;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.repository.CatalogLinkRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves frozen repair-stage line requirements without consulting the mutable active catalog. */
@Service
public class MaintenanceTaskRequirementsResolver {
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository stages;
  private final CatalogLinkRepository links;
  private final MaintenanceCommandSupport commands;

  public MaintenanceTaskRequirementsResolver(MaintenanceRepairRepository repairs, RepairStageRepository stages,
      CatalogLinkRepository links, MaintenanceCommandSupport commands) {
    this.repairs = repairs;
    this.stages = stages;
    this.links = links;
    this.commands = commands;
  }

  @Transactional(readOnly = true)
  public RepairTaskRequirementsResponse resolve(UUID repairId, UUID warehouseId) {
    MaintenanceRepair repair = repairs.findByIdAndWarehouseId(repairId, warehouseId)
        .orElseThrow(() -> new MaintenanceDependencyException(HttpStatus.NOT_FOUND, "Repair is not in warehouse"));
    Map<UUID, Item> items = new LinkedHashMap<>();
    for (RepairStage stage : stages.findAllByRepairIdOrderByStageNo(repair.getId())) {
      UUID entryId = stage.getExternalQueueEntryId();
      add(items, commands.readList(stage.getWorkLines(), EstimateLineResponse.class), entryId);
      add(items, commands.readList(stage.getMaterialLines(), EstimateLineResponse.class), entryId);
    }
    Map<UUID, Set<UUID>> adjacent = graph(items.values());
    return new RepairTaskRequirementsResponse(repair.getId(), warehouseId,
        items.values().stream().sorted(Comparator.comparing(item -> item.id.toString()))
            .map(item -> item.response(adjacent.getOrDefault(item.id, Set.of()))).toList());
  }

  private void add(Map<UUID, Item> items, List<EstimateLineResponse> lines, UUID entryId) {
    for (EstimateLineResponse line : lines) {
      Item item = items.computeIfAbsent(line.id(), ignored -> new Item(line));
      if (entryId != null) item.entryIds.add(entryId);
    }
  }

  private Map<UUID, Set<UUID>> graph(Collection<Item> values) {
    Map<UUID, List<Item>> byVersion = new HashMap<>();
    for (Item item : values) if (item.version != null && item.nodeId != null) byVersion.computeIfAbsent(item.version, ignored -> new ArrayList<>()).add(item);
    Map<UUID, Set<UUID>> result = new HashMap<>();
    for (Map.Entry<UUID, List<Item>> group : byVersion.entrySet()) {
      Map<UUID, Set<UUID>> adjacency = new HashMap<>();
      for (CatalogLink link : links.findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(group.getKey())) {
        if (!"DEPENDENCY".equals(link.getLinkType())) continue;
        adjacency.computeIfAbsent(link.getSourceNodeId(), ignored -> new HashSet<>()).add(link.getTargetNodeId());
        adjacency.computeIfAbsent(link.getTargetNodeId(), ignored -> new HashSet<>()).add(link.getSourceNodeId());
      }
      for (Item item : group.getValue()) {
        Set<UUID> reachable = reachable(item.nodeId, adjacency);
        Set<UUID> linked = new HashSet<>();
        for (Item candidate : group.getValue()) if (!candidate.id.equals(item.id) && reachable.contains(candidate.nodeId)) linked.add(candidate.id);
        result.put(item.id, linked);
      }
    }
    return result;
  }

  private static Set<UUID> reachable(UUID start, Map<UUID, Set<UUID>> adjacency) {
    Set<UUID> visited = new HashSet<>();
    ArrayDeque<UUID> pending = new ArrayDeque<>();
    pending.add(start);
    while (!pending.isEmpty()) {
      UUID current = pending.removeFirst();
      if (!visited.add(current)) continue;
      pending.addAll(adjacency.getOrDefault(current, Set.of()));
    }
    return visited;
  }

  private static final class Item {
    private final UUID id; private final EstimateLineType kind; private final String name; private final UUID version; private final UUID nodeId;
    private final long seconds; private final Set<UUID> entryIds = new HashSet<>();
    Item(EstimateLineResponse line) {
      id = line.id(); kind = line.lineType(); name = line.description();
      version = line.catalogSnapshot() == null ? null : line.catalogSnapshot().catalogVersionId();
      nodeId = line.catalogSnapshot() == null ? null : line.catalogSnapshot().nodeId();
      seconds = kind == EstimateLineType.WORK ? BigDecimal.valueOf(line.normativeMinutes()).multiply(new BigDecimal(line.quantity())).multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.CEILING).longValueExact() : 0;
    }
    RepairTaskRequirementItem response(Set<UUID> linked) {
      return new RepairTaskRequirementItem(id, kind, name, version, nodeId, seconds,
          linked.stream().sorted().toList(), entryIds.stream().sorted().toList());
    }
  }
}
