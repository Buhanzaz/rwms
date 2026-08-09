package dev.buhanzaz.rwms.maintenance.disposition.application;

import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionDecision;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceNotFoundException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Owns repair-chain identity, consistency validation, and deterministic local row locking. */
@Service
final class PropertyDispositionRepairChain {
  private final MaintenanceRepairRepository repairs;

  PropertyDispositionRepairChain(MaintenanceRepairRepository repairs) {
    this.repairs = repairs;
  }

  MaintenanceRepair requireByIdAndWarehouseId(UUID repairId, UUID warehouseId) {
    return repairs.findByIdAndWarehouseId(repairId, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
  }

  MaintenanceRepair requireById(UUID repairId) {
    return repairs.findById(repairId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Root repair not found"));
  }

  List<MaintenanceRepair> repairChain(UUID rootRepairId) {
    if (rootRepairId == null) {
      return List.of();
    }
    return repairs.findRepairChain(rootRepairId);
  }

  Map<UUID, List<MaintenanceRepair>> repairChains(
      Collection<PropertyDispositionDecision> values) {
    Set<UUID> roots = values.stream()
        .map(PropertyDispositionDecision::getRootRepairId)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toSet());
    if (roots.isEmpty()) {
      return Map.of();
    }
    return repairs.findAllForDispositionRoots(roots).stream()
        .collect(
            Collectors.groupingBy(
                PropertyDispositionRepairChain::rootId,
                LinkedHashMap::new,
                Collectors.collectingAndThen(Collectors.toList(), List::copyOf)));
  }

  List<MaintenanceRepair> lockRepairChain(
      List<MaintenanceRepair> initial, Map<MaintenanceEventStore.StreamRef, Long> versions) {
    if (initial.isEmpty()) {
      return List.of();
    }
    Map<UUID, MaintenanceRepair> locked = repairs.findAllByIdForUpdate(
            initial.stream().map(MaintenanceRepair::getId).toList())
        .stream()
        .collect(Collectors.toMap(MaintenanceRepair::getId, value -> value));
    if (locked.size() != initial.size()) {
      throw conflict("Repair chain changed while property disposition was processing");
    }
    return initial.stream()
        .map(
            value -> {
              MaintenanceRepair repair = locked.get(value.getId());
              assertStreamParity(repair, versions.get(stream(repair.getId())));
              return repair;
            })
        .toList();
  }

  void validateCanPropose(
      MaintenanceRepair requested, MaintenanceRepair root, List<MaintenanceRepair> chain) {
    if (requested.getAcceptanceState() == RepairAcceptanceState.ACCEPTED
        || requested.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) {
      throw conflict("Repair already has a terminal acceptance decision");
    }
    if (!requested.getWarehouseId().equals(root.getWarehouseId())
        || !requested.getRentalItemId().equals(root.getRentalItemId())
        || chain.stream().anyMatch(
            value -> !root.getWarehouseId().equals(value.getWarehouseId())
                || !root.getRentalItemId().equals(value.getRentalItemId()))) {
      throw conflict("Repair chain is not bound to one warehouse and cabin");
    }
    if (root.getLeaseId() == null
        || root.getLeaseVersion() == null
        || root.getFencingToken() == null
        || !"ACTIVE".equals(root.getLeaseReconciliationState())) {
      throw conflict("Repair root does not have an active asset lease proof");
    }
  }

  static UUID rootId(MaintenanceRepair repair) {
    return repair.getRootRepairId() == null ? repair.getId() : repair.getRootRepairId();
  }

  static MaintenanceEventStore.StreamRef stream(UUID id) {
    return new MaintenanceEventStore.StreamRef(MaintenanceAggregateType.REPAIR, id);
  }

  private static void assertStreamParity(MaintenanceRepair repair, Long eventVersion) {
    if (eventVersion == null || repair.getVersion() != eventVersion) {
      throw conflict("Repair event stream is out of sync with local state");
    }
  }

  private static MaintenanceConflictException conflict(String message) {
    return new MaintenanceConflictException("MAINTENANCE_STATE_CONFLICT", message);
  }
}
