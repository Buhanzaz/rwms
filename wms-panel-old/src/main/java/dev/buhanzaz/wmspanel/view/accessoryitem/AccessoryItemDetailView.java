package dev.buhanzaz.wmspanel.view.accessoryitem;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.AccessoryCategory;
import dev.buhanzaz.wmspanel.entity.AccessoryItem;
import dev.buhanzaz.wmspanel.entity.AccessorySubcategory;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNodeType;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.DataManager;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Route(value = "accessory-items/:id", layout = MainView.class)
@ViewController(id = "AccessoryItem.detail")
@ViewDescriptor(path = "accessory-item-detail-view.xml")
@EditedEntityContainer("accessoryItemDc")
public class AccessoryItemDetailView extends StandardDetailView<AccessoryItem> {

    @ViewComponent
    private EntityComboBox<AccessoryCategory> categoryField;

    @ViewComponent
    private EntityComboBox<AccessorySubcategory> subcategoryField;

    @ViewComponent
    private CollectionLoader<AccessorySubcategory> accessorySubcategoriesDl;

    @ViewComponent
    private EntityComboBox<RepairEstimateCatalogNode> furnitureMaterialField;

    @Autowired
    private DataManager dataManager;

    @Subscribe
    public void onInit(final InitEvent event) {
        categoryField.setItemLabelGenerator(AccessoryCategory::getName);
        subcategoryField.setItemLabelGenerator(AccessorySubcategory::getName);
        furnitureMaterialField.setItemLabelGenerator(node -> node == null ? "" : node.getName());

        categoryField.addValueChangeListener(changeEvent -> {
            accessorySubcategoriesDl.setParameter("category", changeEvent.getValue());
            accessorySubcategoriesDl.load();

            AccessorySubcategory selectedSubcategory = subcategoryField.getValue();
            if (selectedSubcategory != null
                    && !Objects.equals(selectedSubcategory.getCategory(), changeEvent.getValue())) {
                subcategoryField.clear();
            }
        });
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        accessorySubcategoriesDl.setParameter("category", categoryField.getValue());
        accessorySubcategoriesDl.load();
        furnitureMaterialField.setItems(loadAvailableFurnitureMaterials());
    }

    private List<RepairEstimateCatalogNode> loadAvailableFurnitureMaterials() {
        AccessoryItem currentItem = getEditedEntity();
        UUID currentItemId = currentItem == null ? null : currentItem.getId();
        UUID currentMaterialId = currentItem == null || currentItem.getFurnitureMaterial() == null
                ? null
                : currentItem.getFurnitureMaterial().getId();

        Set<UUID> usedMaterialIds = dataManager.load(AccessoryItem.class)
                .query("select e from AccessoryItem e where e.furnitureMaterial is not null")
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("furnitureMaterial", "_base"))
                .list()
                .stream()
                .filter(item -> item.getId() != null && !Objects.equals(item.getId(), currentItemId))
                .map(AccessoryItem::getFurnitureMaterial)
                .filter(Objects::nonNull)
                .map(RepairEstimateCatalogNode::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        List<RepairEstimateCatalogNode> allNodes = dataManager.load(RepairEstimateCatalogNode.class)
                .query("""
                        select e from RepairEstimateCatalogNode e
                        left join fetch e.parent
                        order by coalesce(e.sortOrder, 2147483647), e.name
                        """)
                .list();
        Map<UUID, RepairEstimateCatalogNode> nodesById = allNodes.stream()
                .filter(node -> node.getId() != null)
                .collect(Collectors.toMap(RepairEstimateCatalogNode::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        return allNodes.stream()
                .filter(node -> node.getNodeType() == RepairEstimateCatalogNodeType.MATERIAL)
                .filter(node -> node.getActive() == null || Boolean.TRUE.equals(node.getActive()))
                .filter(node -> isFurnitureCatalogNode(node, nodesById))
                .filter(node -> Objects.equals(node.getId(), currentMaterialId) || !usedMaterialIds.contains(node.getId()))
                .sorted(Comparator
                        .comparing((RepairEstimateCatalogNode node) -> node.getSortOrder() == null ? Integer.MAX_VALUE : node.getSortOrder())
                        .thenComparing(node -> node.getName() == null ? "" : node.getName(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private boolean isFurnitureCatalogNode(RepairEstimateCatalogNode node,
                                           Map<UUID, RepairEstimateCatalogNode> nodesById) {
        Set<UUID> visited = new HashSet<>();
        RepairEstimateCatalogNode current = node;
        while (current != null && current.getId() != null && visited.add(current.getId())) {
            if (Boolean.TRUE.equals(current.getFurnitureCategory())) {
                return true;
            }
            if (current.getParent() == null || current.getParent().getId() == null) {
                return false;
            }
            current = nodesById.get(current.getParent().getId());
        }
        return false;
    }
}
