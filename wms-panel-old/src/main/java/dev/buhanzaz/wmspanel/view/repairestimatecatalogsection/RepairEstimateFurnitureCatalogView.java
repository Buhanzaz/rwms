package dev.buhanzaz.wmspanel.view.repairestimatecatalogsection;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNodeType;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Route(value = "repair-estimate-catalog-furniture", layout = MainView.class)
@ViewController(id = "RepairEstimateCatalogFurniture.view")
@ViewDescriptor(path = "repair-estimate-catalog-furniture-view.xml")
public class RepairEstimateFurnitureCatalogView extends AbstractRepairEstimateCatalogSectionView {

    @Override
    protected RepairEstimateCatalogNodeType sectionType() {
        return RepairEstimateCatalogNodeType.MATERIAL;
    }

    @Override
    protected String sectionTitleKey() {
        return "dev.buhanzaz.wmspanel.view.repairestimatecatalogsection/RepairEstimateFurnitureCatalogView.title";
    }

    @Override
    protected String sectionTitleFallback() {
        return "Мебель";
    }

    @Override
    protected boolean includeCategory(RepairEstimateCatalogNode category,
                                      List<RepairEstimateCatalogNode> allNodes,
                                      Map<UUID, RepairEstimateCatalogNode> nodesById) {
        return category != null
                && category.getNodeType() == RepairEstimateCatalogNodeType.CATEGORY
                && category.getParent() == null
                && Boolean.TRUE.equals(category.getFurnitureCategory());
    }

    @Override
    protected boolean includeCategoryBySection(RepairEstimateCatalogNode category) {
        return Boolean.TRUE.equals(category.getFurnitureCategory());
    }

    @Override
    protected boolean includeItem(RepairEstimateCatalogNode node,
                                  UUID categoryId,
                                  Map<UUID, RepairEstimateCatalogNode> nodesById) {
        return super.includeItem(node, categoryId, nodesById) && isFurnitureTreeNode(node, nodesById);
    }

    private boolean isFurnitureTreeNode(RepairEstimateCatalogNode node,
                                        Map<UUID, RepairEstimateCatalogNode> nodesById) {
        RepairEstimateCatalogNode current = node;
        while (current != null) {
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
