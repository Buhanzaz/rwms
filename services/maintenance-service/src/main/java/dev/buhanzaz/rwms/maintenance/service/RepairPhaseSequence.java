package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPlanStageSnapshot;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.PlanStageInput;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Defines the mandatory phase sequence for every ordinary maintenance repair route.
 *
 * <p>An ordinary route contains at most one executable stage for each physical task-board queue
 * identity. Repeated submitted groups for one queue are coalesced without changing their stable
 * line order. Queues that merely share a display name remain independent. Unknown legacy queues
 * remain after the six supported phases so their data is preserved without allowing them to change
 * the executable repair sequence.
 */
final class RepairPhaseSequence {
  private static final int UNKNOWN_PHASE = 6;

  private RepairPhaseSequence() {}

  /**
   * Returns a canonical ordinary-repair plan with one stage per physical queue.
   *
   * <p>The first stage ID and routing snapshot survive. Included line IDs and nonblank comments are
   * appended in source order, the first submitted primary line and nonnull deadline survive, and
   * output orders are rewritten to a contiguous sequence. Duplicate line IDs are deliberately not
   * removed so normal plan validation still rejects an invalid double assignment.
   */
  static List<PlanStageInput> canonicalPlan(List<PlanStageInput> stages) {
    Map<UUID, List<PlanStageInput>> groups = new LinkedHashMap<>();
    stages.stream()
        .sorted(Comparator.comparingInt(PlanStageInput::order))
        .forEach(stage -> groups.computeIfAbsent(stage.routing().queueId(), ignored -> new ArrayList<>())
            .add(stage));
    List<PlanStageInput> merged = groups.values().stream()
        .map(RepairPhaseSequence::mergePlanStages)
        .sorted(
            Comparator.comparingInt(
                    (PlanStageInput stage) -> phaseRank(stage.routing().queueName()))
                .thenComparingInt(PlanStageInput::order))
        .toList();
    List<PlanStageInput> result = new ArrayList<>(merged.size());
    for (int index = 0; index < merged.size(); index++) {
      PlanStageInput stage = merged.get(index);
      result.add(new PlanStageInput(
          stage.id(),
          stage.kind(),
          index,
          stage.routing(),
          stage.includedLineIds(),
          stage.primaryLineId(),
          stage.groupComment(),
          stage.taskDeadline()));
    }
    return List.copyOf(result);
  }

  /**
   * Returns frozen inventory stages in canonical order with one stage per physical queue.
   *
   * <p>The first stage ID and catalog snapshot remain authoritative; later same-queue snapshots
   * contribute no additional executable stage because line allocation is queue-owned.
   */
  static List<InventoryPlanStageSnapshot> canonicalInventoryStages(
      List<InventoryPlanStageSnapshot> stages) {
    Map<UUID, InventoryPlanStageSnapshot> byQueue = new LinkedHashMap<>();
    stages.stream()
        .sorted(
            Comparator.comparingInt(InventoryPlanStageSnapshot::order)
                .thenComparing(InventoryPlanStageSnapshot::id))
        .forEach(stage -> byQueue.putIfAbsent(stage.routing().queueId(), stage));
    List<InventoryPlanStageSnapshot> merged = byQueue.values().stream()
        .sorted(
            Comparator.comparingInt(
                    (InventoryPlanStageSnapshot stage) ->
                        phaseRank(stage.routing().queueName()))
                .thenComparingInt(InventoryPlanStageSnapshot::order)
                .thenComparing(InventoryPlanStageSnapshot::id))
        .toList();
    List<InventoryPlanStageSnapshot> result = new ArrayList<>(merged.size());
    for (int index = 0; index < merged.size(); index++) {
      InventoryPlanStageSnapshot stage = merged.get(index);
      result.add(new InventoryPlanStageSnapshot(
          stage.id(),
          stage.catalogNodeId(),
          stage.catalogNodeName(),
          stage.kind(),
          index,
          stage.routing(),
          stage.normativeDurationMinutes()));
    }
    return List.copyOf(result);
  }

  /** Returns persisted repair stages in canonical phase and original within-phase order. */
  static List<RepairStage> canonicalStages(List<RepairStage> stages) {
    return stages.stream()
        .sorted(
            Comparator.comparingInt(
                    (RepairStage stage) -> phaseRank(stage.getRoutingQueueName()))
                .thenComparingInt(RepairStage::getStageNo))
        .toList();
  }

  /**
   * Groups persisted stages by physical queue identity and orders the resulting indivisible
   * subtasks canonically.
   *
   * <p>The lowest submitted stage number supplies the stable stage and routing snapshot for a
   * queue. Work and material rows remain in that stable source order for later presentation
   * merging. A shared display name never merges distinct physical queue IDs.
   */
  static List<List<RepairStage>> canonicalStageGroups(List<RepairStage> stages) {
    Map<UUID, List<RepairStage>> byQueue = new LinkedHashMap<>();
    stages.stream()
        .sorted(
            Comparator.comparingInt(RepairStage::getStageNo)
                .thenComparing(RepairStage::getId))
        .forEach(
            stage ->
                byQueue
                    .computeIfAbsent(stage.getRoutingQueueId(), ignored -> new ArrayList<>())
                    .add(stage));
    return byQueue.values().stream()
        .map(List::copyOf)
        .sorted(
            Comparator.comparingInt(
                    (List<RepairStage> group) ->
                        phaseRank(group.getFirst().getRoutingQueueName()))
                .thenComparingInt(group -> group.getFirst().getStageNo())
                .thenComparing(group -> group.getFirst().getId()))
        .toList();
  }

  private static PlanStageInput mergePlanStages(List<PlanStageInput> stages) {
    PlanStageInput first = stages.getFirst();
    List<UUID> lineIds = new ArrayList<>();
    List<String> comments = new ArrayList<>();
    UUID primaryLineId = null;
    OffsetDateTime deadline = null;
    for (PlanStageInput stage : stages) {
      lineIds.addAll(stage.includedLineIds());
      if (stage.groupComment() != null && !stage.groupComment().isBlank()) {
        comments.add(stage.groupComment());
      }
      if (primaryLineId == null && stage.primaryLineId() != null) {
        primaryLineId = stage.primaryLineId();
      }
      if (stage.taskDeadline() != null) {
        if (deadline != null && !deadline.equals(stage.taskDeadline())) {
          throw MaintenanceCommandSupport.invalid(
              "Repair plan stage deadlines must be absent or one identical timestamp");
        }
        deadline = stage.taskDeadline();
      }
    }
    if (lineIds.size() > 2000) {
      throw MaintenanceCommandSupport.invalid(
          "Combined repair queue stage must not contain more than 2000 line references");
    }
    String groupComment = String.join("\n\n", comments);
    if (groupComment.length() > 2000) {
      throw MaintenanceCommandSupport.invalid(
          "Combined repair queue comment must not exceed 2000 characters");
    }
    return new PlanStageInput(
        first.id(),
        first.kind(),
        first.order(),
        first.routing(),
        List.copyOf(lineIds),
        primaryLineId,
        groupComment,
        deadline);
  }

  private static int phaseRank(String queueName) {
    return switch (normalize(queueName)) {
      case "сэс и санитария" -> 0;
      case "сварка" -> 1;
      case "внешние работы" -> 2;
      case "внутренние работы" -> 3;
      case "электрика" -> 4;
      case "сантехника" -> 5;
      default -> UNKNOWN_PHASE;
    };
  }

  private static String normalize(String value) {
    return value == null
        ? ""
        : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }
}
