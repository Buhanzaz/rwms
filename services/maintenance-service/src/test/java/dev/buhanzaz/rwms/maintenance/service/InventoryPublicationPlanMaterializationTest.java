package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies queue-level stage allocation for immutable completed-inventory plans. */
class InventoryPublicationPlanMaterializationTest {
  @Test
  void allocatesEverySameQueueWorkAndMaterialIntoOneStableStage() {
    UUID catalogVersionId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    RoutingSnapshot routing = new RoutingSnapshot(queueId, "Внутренние работы", "REPAIR");
    UUID firstStageId = UUID.randomUUID();
    UUID firstWorkNodeId = UUID.randomUUID();
    UUID secondWorkNodeId = UUID.randomUUID();
    UUID materialNodeId = UUID.randomUUID();
    FrozenInventoryPlanSnapshot snapshot =
        new FrozenInventoryPlanSnapshot(
            catalogVersionId,
            InventoryPlanMode.AUTO,
            List.of(
                line(
                    catalogVersionId,
                    firstWorkNodeId,
                    InventoryPlanLineType.WORK,
                    "Замена ПВХ панели",
                    "15",
                    routing,
                    "Первая работа"),
                line(
                    catalogVersionId,
                    secondWorkNodeId,
                    InventoryPlanLineType.WORK,
                    "Влажная уборка",
                    "20",
                    routing,
                    "Вторая работа"),
                line(
                    catalogVersionId,
                    materialNodeId,
                    InventoryPlanLineType.MATERIAL,
                    "ПВХ белая панель",
                    "0",
                    routing,
                    null)),
            List.of(
                new InventoryPlanStageSnapshot(
                    firstStageId,
                    firstWorkNodeId,
                    "Замена ПВХ панели",
                    RepairStageKind.REPAIR_WORK,
                    0,
                    routing,
                    15),
                new InventoryPlanStageSnapshot(
                    UUID.randomUUID(),
                    secondWorkNodeId,
                    "Влажная уборка",
                    RepairStageKind.REPAIR_WORK,
                    1,
                    routing,
                    20)),
            false,
            List.of(),
            3,
            null);
    InventoryPublicationPlanMaterialization materialization =
        new InventoryPublicationPlanMaterialization(null, null);

    InventoryPublicationRepairPlan plan =
        materialization.fullRepairPlan(
            new InventoryPublicationSourceId(UUID.randomUUID(), 1, UUID.randomUUID()),
            snapshot,
            List.of());

    assertThat(plan.allocations()).hasSize(1);
    InventoryPublicationPublishedStage allocation = plan.allocations().getFirst();
    assertThat(allocation.stage().id()).isEqualTo(firstStageId);
    assertThat(allocation.stage().catalogNodeId()).isEqualTo(firstWorkNodeId);
    assertThat(allocation.stage().order()).isZero();
    assertThat(allocation.stage().routing().queueId()).isEqualTo(queueId);
    assertThat(allocation.workLines())
        .extracting(EstimateLineResponse::description)
        .containsExactly("Замена ПВХ панели", "Влажная уборка");
    assertThat(allocation.workLines())
        .extracting(EstimateLineResponse::comment)
        .containsExactly("Первая работа", "Вторая работа");
    assertThat(allocation.materialLines())
        .extracting(EstimateLineResponse::description)
        .containsExactly("ПВХ белая панель");
    assertThat(allocation.primaryLineId()).isEqualTo(allocation.workLines().getFirst().id());
  }

  private static InventoryPlanLineSnapshot line(
      UUID catalogVersionId,
      UUID catalogNodeId,
      InventoryPlanLineType type,
      String description,
      String normativeMinutes,
      RoutingSnapshot routing,
      String groupComment) {
    return new InventoryPlanLineSnapshot(
        InventoryPlanLineKind.CATALOG,
        catalogVersionId,
        catalogNodeId,
        description,
        type,
        description,
        description.toLowerCase(java.util.Locale.ROOT),
        type == InventoryPlanLineType.WORK ? "шт." : "м2",
        "1",
        0,
        normativeMinutes,
        routing,
        groupComment,
        List.of(),
        false,
        null);
  }
}
