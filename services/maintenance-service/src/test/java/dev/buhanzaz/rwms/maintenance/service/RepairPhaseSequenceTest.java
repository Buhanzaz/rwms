package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.PlanStageInput;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.RoutingSnapshot;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the maintenance-owned phase sequence used for persistence and task publication. */
class RepairPhaseSequenceTest {
  @Test
  void ordersEverySupportedPhaseAndPreservesWorkOrderInsideOnePhase() {
    List<PlanStageInput> submitted =
        List.of(
            stage(0, "Электрика"),
            stage(1, "Внутренние работы"),
            stage(2, "  СЭС   и санитария "),
            stage(3, "Внешние работы"),
            stage(4, "Внутренние работы"),
            stage(5, "Сварка"),
            stage(6, "Сантехника"),
            stage(7, "Очередь недоступна"));

    assertThat(RepairPhaseSequence.canonicalPlan(submitted))
        .extracting(stage -> stage.routing().queueName().trim().replaceAll("\\s+", " "))
        .containsExactly(
            "СЭС и санитария",
            "Сварка",
            "Внешние работы",
            "Внутренние работы",
            "Внутренние работы",
            "Электрика",
            "Сантехника",
            "Очередь недоступна");
    assertThat(RepairPhaseSequence.canonicalPlan(submitted))
        .extracting(PlanStageInput::order)
        .containsExactly(2, 5, 3, 1, 4, 0, 6, 7);
  }

  @Test
  void appliesTheSameSequenceToStoredRepairStagesBeforeTaskPublication() {
    UUID repairId = UUID.randomUUID();
    List<RepairStage> stored =
        List.of(
            repairStage(repairId, 0, "Электрика"),
            repairStage(repairId, 1, "Внутренние работы"),
            repairStage(repairId, 2, "Внешние работы"));

    assertThat(RepairPhaseSequence.canonicalStages(stored))
        .extracting(RepairStage::getRoutingQueueName)
        .containsExactly("Внешние работы", "Внутренние работы", "Электрика");
  }

  private static PlanStageInput stage(int order, String queueName) {
    return new PlanStageInput(
        UUID.randomUUID(),
        RepairStageKind.REPAIR_WORK,
        order,
        new RoutingSnapshot(UUID.randomUUID(), queueName, "REPAIR"),
        List.of(),
        null,
        "",
        null);
  }

  private static RepairStage repairStage(UUID repairId, int stageNo, String queueName) {
    return new RepairStage(
        UUID.randomUUID(),
        repairId,
        stageNo,
        RepairStageKind.REPAIR_WORK,
        UUID.randomUUID(),
        queueName,
        "REPAIR",
        null);
  }
}
