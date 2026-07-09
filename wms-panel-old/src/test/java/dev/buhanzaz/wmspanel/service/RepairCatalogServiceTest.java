package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogLink;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogLinkType;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNodeType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RepairCatalogServiceTest {

    @Test
    void loadsConfiguredMainMenuAndCaseInsensitiveLookupsFromDatabaseSnapshot() {
        RepairCatalogService service = new RepairCatalogService();
        service.replaceSnapshot(service.buildSnapshot(
                List.of(
                        node("DOORS_CATEGORY", "Двери", RepairEstimateCatalogNodeType.CATEGORY, null, true, 10, null),
                        node("CATEGORY_PLUMBING_AC", "Сантехника, кондиционер, монтажи", RepairEstimateCatalogNodeType.CATEGORY, null, false, null, null),
                        node("SUBCATEGORY_PLUMBING", "Сантехника", RepairEstimateCatalogNodeType.SUBCATEGORY, "CATEGORY_PLUMBING_AC", true, 20, "Сантехника"),
                        node("WORK_PIPE_REPLACEMENT", "Замена труб", RepairEstimateCatalogNodeType.WORK, "SUBCATEGORY_PLUMBING", false, null, null)
                ),
                List.of()));

        assertThat(service.mainMenuNodes())
                .extracting(RepairCatalogService.CatalogNode::title)
                .containsExactly("Двери", "Сантехника");

        assertThat(service.children("subcategory_plumbing"))
                .extracting(RepairCatalogService.CatalogNode::code)
                .containsExactly("WORK_PIPE_REPLACEMENT");

        assertThat(service.get("doors_category"))
                .extracting(RepairCatalogService.CatalogNode::code)
                .isEqualTo("DOORS_CATEGORY");
    }

    @Test
    void separatesWorkMaterialDependencyAndFollowUpLinks() {
        RepairCatalogService service = new RepairCatalogService();
        service.replaceSnapshot(service.buildSnapshot(
                List.of(
                        node("CATEGORY_A", "Категория", RepairEstimateCatalogNodeType.CATEGORY, null, true, 10, null),
                        node("GROUP_A", "Группа", RepairEstimateCatalogNodeType.SUBCATEGORY, "CATEGORY_A", false, null, null),
                        node("WORK_A", "Работа", RepairEstimateCatalogNodeType.WORK, "GROUP_A", false, null, null),
                        node("MATERIAL_A", "Материал", RepairEstimateCatalogNodeType.MATERIAL, null, false, null, null),
                        node("OPTIONAL_WORK", "Доп. работа", RepairEstimateCatalogNodeType.WORK, null, false, null, null)
                ),
                List.of(
                        link("WORK_A", "MATERIAL_A", RepairEstimateCatalogLinkType.DEPENDENCY),
                        link("CATEGORY_A", "OPTIONAL_WORK", RepairEstimateCatalogLinkType.DEPENDENCY),
                        link("MATERIAL_A", "OPTIONAL_WORK", RepairEstimateCatalogLinkType.FOLLOW_UP)
                )));

        assertThat(service.dependencyRelatedNodes("work_a"))
                .extracting(RepairCatalogService.CatalogNode::code)
                .containsExactly("MATERIAL_A");

        assertThat(service.dependencyRelatedNodes("material_a"))
                .extracting(RepairCatalogService.CatalogNode::code)
                .containsExactly("WORK_A");

        assertThat(service.dependencyNodes("category_a"))
                .extracting(RepairCatalogService.CatalogNode::code)
                .containsExactly("OPTIONAL_WORK");

        assertThat(service.followUpNodes("material_a"))
                .extracting(RepairCatalogService.CatalogNode::code)
                .containsExactly("OPTIONAL_WORK");
    }

    @Test
    void resolvesDependencyRelatedNodesInBothDirectionsForWorkMaterialPairs() {
        RepairCatalogService service = new RepairCatalogService();
        service.replaceSnapshot(service.buildSnapshot(
                List.of(
                        node("WORK_LOCK_REPLACE", "Замена замка", RepairEstimateCatalogNodeType.WORK, null, false, null, null),
                        node("MATERIAL_LOCK", "Замок", RepairEstimateCatalogNodeType.MATERIAL, null, false, null, null)
                ),
                List.of(link("WORK_LOCK_REPLACE", "MATERIAL_LOCK", RepairEstimateCatalogLinkType.DEPENDENCY))));

        assertThat(service.dependencyRelatedNodes("work_lock_replace"))
                .extracting(RepairCatalogService.CatalogNode::code)
                .containsExactly("MATERIAL_LOCK");

        assertThat(service.dependencyRelatedNodes("material_lock"))
                .extracting(RepairCatalogService.CatalogNode::code)
                .containsExactly("WORK_LOCK_REPLACE");
    }

    @Test
    void keepsLocationNodesInSnapshotChildren() {
        RepairCatalogService service = new RepairCatalogService();
        service.replaceSnapshot(service.buildSnapshot(
                List.of(
                        node("WORK_DVP", "Замена ДВП", RepairEstimateCatalogNodeType.WORK, null, false, null, null),
                        node("MATERIAL_DVP", "Лист ДВП", RepairEstimateCatalogNodeType.MATERIAL, "WORK_DVP", false, null, null),
                        node("LOCATION_CEILING", "на потолке", RepairEstimateCatalogNodeType.LOCATION, "MATERIAL_DVP", false, null, null)
                ),
                List.of()));

        assertThat(service.children("material_dvp"))
                .extracting(RepairCatalogService.CatalogNode::code, RepairCatalogService.CatalogNode::type)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "LOCATION_CEILING",
                        RepairCatalogService.CatalogNodeType.LOCATION));
    }

    @Test
    void detectsFurnitureByActiveRootCodeAndDescendants() {
        RepairCatalogService service = new RepairCatalogService();
        service.replaceSnapshot(service.buildSnapshot(
                List.of(
                        furnitureCategory("FURNITURE", "Мебель", true, 1, null),
                        node("METAL_BUNK_BED", "Кровать двухъярусная", RepairEstimateCatalogNodeType.MATERIAL, "FURNITURE", false, null, null),
                        node("WINDOW", "Окна", RepairEstimateCatalogNodeType.CATEGORY, null, true, 2, null),
                        node("PVC_WINDOW", "Окно ПВХ", RepairEstimateCatalogNodeType.MATERIAL, "WINDOW", false, null, null)
                ),
                List.of()));

        assertThat(service.isFurnitureCode("FURNITURE")).isTrue();
        assertThat(service.isFurnitureCode("metal_bunk_bed")).isTrue();
        assertThat(service.isFurnitureCode("PVC_WINDOW")).isFalse();
    }

    private RepairEstimateCatalogNode node(String code,
                                           String name,
                                           RepairEstimateCatalogNodeType nodeType,
                                           String parentCode,
                                           boolean showInMainMenu,
                                           Integer mainMenuOrder,
                                           String mainMenuTitle) {
        RepairEstimateCatalogNode node = new RepairEstimateCatalogNode();
        node.setId(UUID.randomUUID());
        node.setCode(code);
        node.setName(name);
        node.setNodeType(nodeType);
        node.setActive(true);
        node.setShowInMainMenu(showInMainMenu);
        node.setMainMenuOrder(mainMenuOrder);
        node.setMainMenuTitle(mainMenuTitle);
        node.setDefaultQuantity(1);
        node.setUnit("ед.");
        node.setUnitPrice(BigDecimal.TEN);
        if (parentCode != null) {
            RepairEstimateCatalogNode parent = new RepairEstimateCatalogNode();
            parent.setId(UUID.randomUUID());
            parent.setCode(parentCode);
            node.setParent(parent);
        }
        return node;
    }

    private RepairEstimateCatalogNode furnitureCategory(String code,
                                                        String name,
                                                        boolean showInMainMenu,
                                                        Integer mainMenuOrder,
                                                        String mainMenuTitle) {
        RepairEstimateCatalogNode node = node(code, name, RepairEstimateCatalogNodeType.CATEGORY, null, showInMainMenu, mainMenuOrder, mainMenuTitle);
        node.setFurnitureCategory(true);
        return node;
    }

    private RepairEstimateCatalogLink link(String sourceCode, String targetCode, RepairEstimateCatalogLinkType type) {
        RepairEstimateCatalogLink link = new RepairEstimateCatalogLink();
        link.setId(UUID.randomUUID());
        link.setLinkType(type);
        link.setActive(true);
        link.setSourceNode(node(sourceCode, sourceCode, RepairEstimateCatalogNodeType.WORK, null, false, null, null));
        link.setTargetNode(node(targetCode, targetCode, RepairEstimateCatalogNodeType.MATERIAL, null, false, null, null));
        return link;
    }
}
