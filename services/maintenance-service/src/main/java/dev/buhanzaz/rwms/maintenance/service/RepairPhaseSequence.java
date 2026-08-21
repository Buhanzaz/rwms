package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.PlanStageInput;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Defines the mandatory phase sequence for every ordinary maintenance repair route.
 *
 * <p>Multiple work stages assigned to the same phase retain their submitted order. Unknown legacy
 * queues remain after the six supported phases so their data is preserved without allowing them to
 * change the executable repair sequence.
 */
final class RepairPhaseSequence {
  private static final int UNKNOWN_PHASE = 6;

  private RepairPhaseSequence() {}

  /** Returns a copy of a submitted plan in the canonical ordinary-repair phase order. */
  static List<PlanStageInput> canonicalPlan(List<PlanStageInput> stages) {
    return stages.stream()
        .sorted(
            Comparator.comparingInt(
                    (PlanStageInput stage) -> phaseRank(stage.routing().queueName()))
                .thenComparingInt(PlanStageInput::order))
        .toList();
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
