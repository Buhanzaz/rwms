package dev.buhanzaz.rwms.taskboard.domain;

import java.util.List;
import java.util.UUID;

/** Frozen source requirement with task-board-owned execution availability. */
public record TaskRequirement(
    UUID itemId, String kind, String name, UUID catalogVersionId, UUID catalogNodeId,
    long plannedWorkSeconds, List<UUID> linkedItemIds, List<UUID> entryIds, String state) {
  public TaskRequirement {
    linkedItemIds = List.copyOf(linkedItemIds);
    entryIds = List.copyOf(entryIds);
    if (itemId == null || name == null || name.isBlank()
        || !("WORK".equals(kind) || "MATERIAL".equals(kind))
        || plannedWorkSeconds < 0
        || !List.of("AVAILABLE", "MISSING", "RESTORED", "COMPLETED").contains(state)) {
      throw new IllegalArgumentException("Invalid task requirement snapshot");
    }
  }

  public TaskRequirement withState(String value) {
    return new TaskRequirement(itemId, kind, name, catalogVersionId, catalogNodeId,
        plannedWorkSeconds, linkedItemIds, entryIds, value);
  }
}
