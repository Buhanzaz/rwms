package dev.buhanzaz.rwms.taskboard.api;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Public requirement availability and manager recovery commands. */
public final class TaskRequirementApiModels {
  private TaskRequirementApiModels() {}

  public record Item(UUID itemId, String kind, String name, String state, List<UUID> linkedItemIds) {}
  public record MissingItem(UUID itemId, String kind, String name) {}
  public record TaskRequirements(UUID taskId, long taskVersion, boolean hasProblem,
      boolean incomplete, double completedWorkPercent, List<Item> items) {}
  public record ApplyToAllRequest(@NotNull UUID operationId) {}
  public record AppliedToAll(List<UUID> affectedTaskIds) {}
}
