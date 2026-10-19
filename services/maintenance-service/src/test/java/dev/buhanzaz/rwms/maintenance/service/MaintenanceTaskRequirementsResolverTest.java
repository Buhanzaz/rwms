package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.buhanzaz.rwms.maintenance.domain.*;
import dev.buhanzaz.rwms.maintenance.repository.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class MaintenanceTaskRequirementsResolverTest {
  private final UUID repairId = UUID.randomUUID();
  private final UUID warehouseId = UUID.randomUUID();
  private final MaintenanceRepairRepository repairs = mock(MaintenanceRepairRepository.class);
  private final RepairStageRepository stages = mock(RepairStageRepository.class);
  private final CatalogLinkRepository links = mock(CatalogLinkRepository.class);
  private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
  private final MaintenanceTaskRequirementsResolver resolver = new MaintenanceTaskRequirementsResolver(
      repairs, stages, links, new MaintenanceCommandSupport(null, null, json, mock(PlatformTransactionManager.class)));

  @Test
  void followsFrozenDependenciesBothWaysWithoutFollowingNavigationPathsOrOtherVersions() {
    UUID version = UUID.randomUUID(), newer = UUID.randomUUID();
    UUID workNode = UUID.randomUUID(), materialNode = UUID.randomUUID();
    UUID location = UUID.randomUUID(), option = UUID.randomUUID();
    var work = line(version, workNode, EstimateLineType.WORK, "Монтаж · Стена", "1.125", 3);
    var material = line(version, materialNode, EstimateLineType.MATERIAL, "Панель · Стена", "1", 0);
    var sibling = line(version, UUID.randomUUID(), EstimateLineType.MATERIAL, "Несвязанная панель", "1", 0);
    var newerLine = line(newer, materialNode, EstimateLineType.MATERIAL, "Другая редакция", "1", 0);
    UUID entry = UUID.randomUUID();
    source(List.of(stage(entry, List.of(work), List.of(material, sibling, newerLine))));
    when(links.findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(version)).thenReturn(List.of(
        link(version, workNode, location, "DEPENDENCY"), link(version, location, option, "DEPENDENCY"),
        link(version, option, materialNode, "DEPENDENCY"), link(version, option, location, "FOLLOW_UP"),
        link(version, location, sibling.catalogSnapshot().nodeId(), "FOLLOW_UP")));
    when(links.findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(newer)).thenReturn(List.of());

    var result = resolver.resolve(repairId, warehouseId);
    assertThat(result.items()).hasSize(4);
    var resolvedWork = result.items().stream().filter(item -> item.itemId().equals(work.id())).findFirst().orElseThrow();
    assertThat(resolvedWork.name()).isEqualTo("Монтаж · Стена");
    assertThat(resolvedWork.plannedWorkSeconds()).isEqualTo(203);
    assertThat(resolvedWork.linkedItemIds()).containsExactly(material.id());
    assertThat(resolvedWork.entryIds()).containsExactly(entry);
    assertThat(result.items()).filteredOn(item -> item.itemId().equals(material.id())).singleElement()
        .satisfies(item -> assertThat(item.linkedItemIds()).containsExactly(work.id()));
    assertThat(result.items()).filteredOn(item -> item.itemId().equals(sibling.id()) || item.itemId().equals(newerLine.id()))
        .allSatisfy(item -> assertThat(item.linkedItemIds()).isEmpty());
    verify(links).findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(version);
    verify(links).findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(newer);
    verifyNoMoreInteractions(links);
  }

  @Test
  void customLinesRemainIndependentAndRepeatedLineKeepsAllEntryIdentities() {
    var work = line(null, null, EstimateLineType.WORK, "Произвольная работа", "0.001", 1);
    var material = line(null, null, EstimateLineType.MATERIAL, "Произвольный материал", "1", 0);
    UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    source(List.of(stage(first, List.of(work), List.of(material)), stage(second, List.of(work), List.of())));
    var result = resolver.resolve(repairId, warehouseId);
    assertThat(result.items()).hasSize(2).allSatisfy(item -> {
      assertThat(item.catalogNodeId()).isNull();
      assertThat(item.linkedItemIds()).isEmpty();
    });
    assertThat(result.items()).filteredOn(item -> item.itemId().equals(work.id())).singleElement().satisfies(item -> {
      assertThat(item.plannedWorkSeconds()).isEqualTo(1);
      assertThat(item.entryIds()).containsExactlyInAnyOrder(first, second);
    });
    verifyNoInteractions(links);
  }

  @Test
  void wrongWarehouseCannotResolveRepairOrReadItsCatalog() {
    when(repairs.findByIdAndWarehouseId(repairId, warehouseId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> resolver.resolve(repairId, warehouseId)).isInstanceOf(MaintenanceDependencyException.class);
    verifyNoInteractions(stages, links);
  }

  private void source(List<RepairStage> values) {
    MaintenanceRepair repair = mock(MaintenanceRepair.class);
    when(repair.getId()).thenReturn(repairId);
    when(repairs.findByIdAndWarehouseId(repairId, warehouseId)).thenReturn(Optional.of(repair));
    when(stages.findAllByRepairIdOrderByStageNo(repairId)).thenReturn(values);
  }

  private RepairStage stage(UUID entryId, List<EstimateLineResponse> works, List<EstimateLineResponse> materials) {
    RepairStage stage = mock(RepairStage.class);
    when(stage.getExternalQueueEntryId()).thenReturn(entryId);
    when(stage.getWorkLines()).thenReturn(json.writeValueAsString(works));
    when(stage.getMaterialLines()).thenReturn(json.writeValueAsString(materials));
    return stage;
  }

  private EstimateLineResponse line(UUID version, UUID node, EstimateLineType kind,
      String name, String quantity, int minutes) {
    CatalogNodeSnapshot catalog = version == null ? null : new CatalogNodeSnapshot(version, node,
        CatalogNodeType.valueOf(kind.name()), name, "шт", "0.00", minutes, null, null, false, null);
    return new EstimateLineResponse(UUID.randomUUID(), catalog, kind, name, "шт", quantity,
        "0.00", "0.00", minutes, null, List.of());
  }

  private CatalogLink link(UUID version, UUID source, UUID target, String kind) {
    return new CatalogLink(UUID.randomUUID(), version, source, target, kind, null, null, 0);
  }
}
