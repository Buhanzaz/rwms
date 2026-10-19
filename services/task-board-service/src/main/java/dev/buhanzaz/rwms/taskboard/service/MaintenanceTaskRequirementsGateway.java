package dev.buhanzaz.rwms.taskboard.service;

import java.util.List;
import java.util.UUID;

/** Reads maintenance-owned frozen requirement identities; never mutates a source repair. */
public interface MaintenanceTaskRequirementsGateway {
  Requirements read(UUID warehouseId, UUID repairId);

  record Requirement(UUID itemId, String kind, String name, UUID catalogVersionId,
      UUID catalogNodeId, long plannedWorkSeconds, List<UUID> linkedItemIds, List<UUID> entryIds) {}
  record Requirements(UUID repairId, UUID warehouseId, List<Requirement> items) {}
}
