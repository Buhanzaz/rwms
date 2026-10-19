package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies canonical queue grouping at the estimate/repair revision materialization boundary. */
class MaintenanceEstimateRevisionSupportTest {
  @Test
  void coalescesRepeatedQueueGroupsBeforeImplicitCatalogContentResolution() {
    UUID catalogVersionId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    RoutingSnapshot routing = new RoutingSnapshot(queueId, "Внутренние работы", "REPAIR");
    UUID firstStageId = UUID.randomUUID();
    EstimateLineResponse firstLine =
        line(catalogVersionId, UUID.randomUUID(), routing, EstimateLineType.WORK, "Пол");
    EstimateLineResponse secondLine =
        line(catalogVersionId, UUID.randomUUID(), routing, EstimateLineType.WORK, "Дверь");
    EstimateLineResponse materialLine =
        line(catalogVersionId, UUID.randomUUID(), routing, EstimateLineType.MATERIAL, "Линолеум");
    List<PlanStageInput> submitted =
        List.of(
            new PlanStageInput(
                firstStageId,
                RepairStageKind.REPAIR_WORK,
                0,
                routing,
                List.of(),
                null,
                "Пол",
                null),
            new PlanStageInput(
                UUID.randomUUID(),
                RepairStageKind.REPAIR_WORK,
                1,
                routing,
                List.of(),
                null,
                "Дверь",
                null));
    MaintenanceEstimateSupport estimateSupport =
        new MaintenanceEstimateSupport(
            null, null, null, null, null, null, null, null, null, null, null);
    MaintenanceEstimateRevisionSupport revisionSupport =
        new MaintenanceEstimateRevisionSupport(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            estimateSupport);

    List<PlanStageInput> resolved =
        revisionSupport.canonicalResolvedPlan(
            List.of(firstLine, secondLine, materialLine), submitted);

    assertThat(resolved).hasSize(1);
    PlanStageInput stage = resolved.getFirst();
    assertThat(stage.id()).isEqualTo(firstStageId);
    assertThat(stage.routing().queueId()).isEqualTo(queueId);
    assertThat(stage.includedLineIds())
        .containsExactly(firstLine.id(), secondLine.id(), materialLine.id());
    assertThat(stage.primaryLineId()).isEqualTo(firstLine.id());
    assertThat(stage.groupComment()).isEqualTo("Пол\n\nДверь");
  }

  private static EstimateLineResponse line(
      UUID catalogVersionId,
      UUID nodeId,
      RoutingSnapshot routing,
      EstimateLineType lineType,
      String description) {
    CatalogNodeType nodeType =
        lineType == EstimateLineType.WORK ? CatalogNodeType.WORK : CatalogNodeType.MATERIAL;
    int normativeMinutes = lineType == EstimateLineType.WORK ? 10 : 0;
    CatalogNodeSnapshot catalog =
        new CatalogNodeSnapshot(
            catalogVersionId,
            nodeId,
            nodeType,
            description,
            "шт.",
            "0.00",
            normativeMinutes,
            routing,
            null,
            false,
            null);
    return new EstimateLineResponse(
        UUID.randomUUID(),
        catalog,
        lineType,
        description,
        "шт.",
        "1",
        "0.00",
        "0.00",
        normativeMinutes,
        null,
        List.of());
  }
}
