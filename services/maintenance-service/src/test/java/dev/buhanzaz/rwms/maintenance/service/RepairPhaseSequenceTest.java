package dev.buhanzaz.rwms.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.PlanStageInput;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.RoutingSnapshot;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the maintenance-owned phase sequence used for persistence and task publication. */
class RepairPhaseSequenceTest {
  @Test
  void ordersEverySupportedPhaseAndReindexesIndependentQueues() {
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
        .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
  }

  @Test
  void coalescesOnePhysicalQueueWithoutDroppingLinesCommentsOrStableIdentity() {
    UUID queueId = UUID.randomUUID();
    UUID firstStageId = UUID.randomUUID();
    UUID firstWorkId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID secondWorkId = UUID.randomUUID();
    OffsetDateTime deadline = OffsetDateTime.parse("2026-08-25T09:00:00+03:00");
    List<PlanStageInput> submitted =
        List.of(
            stage(
                firstStageId,
                0,
                queueId,
                "Внутренние работы",
                List.of(firstWorkId, materialId),
                firstWorkId,
                "Первая группа",
                deadline),
            stage(
                UUID.randomUUID(),
                1,
                queueId,
                "Переименованный снимок той же очереди",
                List.of(secondWorkId, materialId),
                secondWorkId,
                "Вторая группа",
                deadline));

    List<PlanStageInput> canonical = RepairPhaseSequence.canonicalPlan(submitted);

    assertThat(canonical).hasSize(1);
    PlanStageInput stage = canonical.getFirst();
    assertThat(stage.id()).isEqualTo(firstStageId);
    assertThat(stage.routing().queueId()).isEqualTo(queueId);
    assertThat(stage.routing().queueName()).isEqualTo("Внутренние работы");
    assertThat(stage.order()).isZero();
    assertThat(stage.includedLineIds())
        .containsExactly(firstWorkId, materialId, secondWorkId, materialId);
    assertThat(stage.primaryLineId()).isEqualTo(firstWorkId);
    assertThat(stage.groupComment()).isEqualTo("Первая группа\n\nВторая группа");
    assertThat(stage.taskDeadline()).isEqualTo(deadline);
  }

  @Test
  void keepsSameNamedQueuesSeparateWhenTheirPhysicalIdsDiffer() {
    List<PlanStageInput> canonical =
        RepairPhaseSequence.canonicalPlan(
            List.of(stage(0, "Внутренние работы"), stage(1, "Внутренние работы")));

    assertThat(canonical).hasSize(2);
    assertThat(canonical)
        .extracting(stage -> stage.routing().queueId())
        .doesNotHaveDuplicates();
  }

  @Test
  void rejectsACombinedQueueCommentInsteadOfTruncatingIt() {
    UUID queueId = UUID.randomUUID();
    List<PlanStageInput> submitted =
        List.of(
            stage(
                UUID.randomUUID(),
                0,
                queueId,
                "Электрика",
                List.of(),
                null,
                "a".repeat(1000),
                null),
            stage(
                UUID.randomUUID(),
                1,
                queueId,
                "Электрика",
                List.of(),
                null,
                "b".repeat(1000),
                null));

    assertThatThrownBy(() -> RepairPhaseSequence.canonicalPlan(submitted))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("2000");
  }

  @Test
  void rejectsACombinedQueueLineListInsteadOfTruncatingIt() {
    UUID queueId = UUID.randomUUID();
    List<PlanStageInput> submitted =
        List.of(
            stage(
                UUID.randomUUID(),
                0,
                queueId,
                "Электрика",
                randomIds(1001),
                null,
                "",
                null),
            stage(
                UUID.randomUUID(),
                1,
                queueId,
                "Электрика",
                randomIds(1000),
                null,
                "",
                null));

    assertThatThrownBy(() -> RepairPhaseSequence.canonicalPlan(submitted))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("2000");
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

  @Test
  void countsPhysicalQueuesAsSubtasksInsteadOfCountingContainedWorks() {
    UUID repairId = UUID.randomUUID();
    UUID interiorQueueId = UUID.randomUUID();
    UUID electricalQueueId = UUID.randomUUID();
    RepairStage firstInterior =
        repairStage(repairId, 0, interiorQueueId, "Внутренние работы");
    RepairStage electrical =
        repairStage(repairId, 1, electricalQueueId, "Электрика");
    RepairStage secondInterior =
        repairStage(repairId, 2, interiorQueueId, "Внутренние работы");

    List<List<RepairStage>> groups =
        RepairPhaseSequence.canonicalStageGroups(
            List.of(firstInterior, electrical, secondInterior));

    assertThat(groups).hasSize(2);
    assertThat(groups.getFirst()).containsExactly(firstInterior, secondInterior);
    assertThat(groups.get(1)).containsExactly(electrical);
  }

  private static PlanStageInput stage(int order, String queueName) {
    return stage(
        UUID.randomUUID(),
        order,
        UUID.randomUUID(),
        queueName,
        List.of(),
        null,
        "",
        null);
  }

  private static PlanStageInput stage(
      UUID stageId,
      int order,
      UUID queueId,
      String queueName,
      List<UUID> lineIds,
      UUID primaryLineId,
      String comment,
      OffsetDateTime deadline) {
    return new PlanStageInput(
        stageId,
        RepairStageKind.REPAIR_WORK,
        order,
        new RoutingSnapshot(queueId, queueName, "REPAIR"),
        lineIds,
        primaryLineId,
        comment,
        deadline);
  }

  private static RepairStage repairStage(UUID repairId, int stageNo, String queueName) {
    return repairStage(repairId, stageNo, UUID.randomUUID(), queueName);
  }

  private static RepairStage repairStage(
      UUID repairId, int stageNo, UUID queueId, String queueName) {
    return new RepairStage(
        UUID.randomUUID(),
        repairId,
        stageNo,
        RepairStageKind.REPAIR_WORK,
        queueId,
        queueName,
        "REPAIR",
        null);
  }

  private static List<UUID> randomIds(int count) {
    return java.util.stream.IntStream.range(0, count)
        .mapToObj(ignored -> UUID.randomUUID())
        .toList();
  }
}
