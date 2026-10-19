package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.domain.CatalogNode;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Verifies queue canonicalization in the active inventory freeze and upsert materialization path. */
class InventoryMaintenancePlanValidationTest {
  @Test
  void freezesAndMaterializesOneStageForSameQueueWorksAndMaterial() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID catalogVersionId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    CatalogNode firstWork = workNode(catalogVersionId, queueId, "Замена ПВХ панели", 15);
    CatalogNode secondWork = workNode(catalogVersionId, queueId, "Влажная уборка", 20);
    CatalogNode material = materialNode(catalogVersionId, queueId, "ПВХ белая панель");
    Map<UUID, CatalogNode> nodes = new LinkedHashMap<>();
    nodes.put(firstWork.getId(), firstWork);
    nodes.put(secondWork.getId(), secondWork);
    nodes.put(material.getId(), material);
    CatalogVersion catalog = mock(CatalogVersion.class);
    when(catalog.getId()).thenReturn(catalogVersionId);
    FreezeInventoryPlanRequest request =
        new FreezeInventoryPlanRequest(
            warehouseId,
            inventoryId,
            findingId,
            1L,
            InventoryPlanMode.AUTO,
            List.of(catalogLine(firstWork.getId(), "Первая работа"),
                catalogLine(secondWork.getId(), "Вторая работа"),
                catalogLine(material.getId(), null)),
            List.of(),
            List.of(),
            3,
            null);
    ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    InventoryMaintenancePlanValidation validation =
        new InventoryMaintenancePlanValidation(null, null, mapper);

    InventoryMaintenancePlanValidation.RemotePreflightRequired preflight =
        assertThrows(
            InventoryMaintenancePlanValidation.RemotePreflightRequired.class,
            () -> validation.freezeSnapshot(request, catalog, nodes, Map.of(), List.of()));

    assertThat(preflight.stages()).hasSize(1);
    FrozenInventoryPlanSnapshot frozen =
        validation.freezeSnapshot(request, catalog, nodes, Map.of(), preflight.stages());
    assertThat(frozen.stages()).hasSize(1);
    InventoryPlanStageSnapshot firstStage = frozen.stages().getFirst();
    assertThat(firstStage.catalogNodeId()).isEqualTo(firstWork.getId());
    assertThat(firstStage.routing().queueId()).isEqualTo(queueId);
    assertThat(firstStage.order()).isZero();

    InventoryPlanStageSnapshot legacyDuplicate =
        new InventoryPlanStageSnapshot(
            UUID.randomUUID(),
            secondWork.getId(),
            secondWork.getName(),
            RepairStageKind.REPAIR_WORK,
            1,
            firstStage.routing(),
            secondWork.getDurationMinutes());
    FrozenInventoryPlanSnapshot legacyFrozen =
        new FrozenInventoryPlanSnapshot(
            frozen.catalogVersionId(),
            frozen.mode(),
            frozen.lines(),
            List.of(firstStage, legacyDuplicate),
            false,
            frozen.mediaReferences(),
            frozen.priority(),
            frozen.coverMediaId());

    List<RepairStage> stages =
        validation.inventoryRepairStages(
            UUID.randomUUID(), inventoryId, findingId, legacyFrozen);

    assertThat(stages).hasSize(1);
    RepairStage materialized = stages.getFirst();
    assertThat(materialized.getId()).isEqualTo(firstStage.id());
    assertThat(materialized.getRoutingQueueId()).isEqualTo(queueId);
    assertThat(materialized.getStageNo()).isZero();
    assertThat(mapper.readTree(materialized.getWorkLines()).size()).isEqualTo(2);
    assertThat(mapper.readTree(materialized.getMaterialLines()).size()).isEqualTo(1);
    assertThat(materialized.getWorkLines())
        .contains("Замена ПВХ панели", "Влажная уборка");
    assertThat(materialized.getMaterialLines()).contains("ПВХ белая панель");
  }

  private static InventoryPlanLineInput catalogLine(UUID catalogNodeId, String groupComment) {
    return new InventoryPlanLineInput(
        InventoryPlanLineKind.CATALOG,
        catalogNodeId,
        null,
        null,
        null,
        null,
        "1",
        null,
        null,
        groupComment,
        List.of());
  }

  private static CatalogNode workNode(
      UUID catalogVersionId,
      UUID queueId,
      String name,
      int durationMinutes) {
    return new CatalogNode(
        UUID.randomUUID(),
        catalogVersionId,
        "WORK",
        name,
        true,
        null,
        false,
        null,
        null,
        "шт.",
        0L,
        durationMinutes,
        true,
        false,
        false,
        null,
        null,
        queueId,
        "Внутренние работы",
        "REPAIR",
        null,
        null);
  }

  private static CatalogNode materialNode(
      UUID catalogVersionId, UUID queueId, String name) {
    return new CatalogNode(
        UUID.randomUUID(),
        catalogVersionId,
        "MATERIAL",
        name,
        true,
        null,
        false,
        null,
        null,
        "шт.",
        0L,
        0,
        true,
        false,
        false,
        null,
        null,
        queueId,
        "Внутренние работы",
        "REPAIR",
        null,
        null);
  }
}
