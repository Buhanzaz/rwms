package dev.buhanzaz.wmspanel.view.repairestimatecataloglink;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogLink;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "repair-estimate-catalog-links/:id", layout = MainView.class)
@ViewController(id = "RepairEstimateCatalogLink.detail")
@ViewDescriptor(path = "repair-estimate-catalog-link-detail-view.xml")
@EditedEntityContainer("repairEstimateCatalogLinkDc")
public class RepairEstimateCatalogLinkDetailView extends StandardDetailView<RepairEstimateCatalogLink> {

    @ViewComponent
    private EntityComboBox<RepairEstimateCatalogNode> sourceNodeField;
    @ViewComponent
    private EntityComboBox<RepairEstimateCatalogNode> targetNodeField;

    @Subscribe
    public void onInit(final InitEvent event) {
        sourceNodeField.setItemLabelGenerator(RepairEstimateCatalogNode::getDisplayName);
        targetNodeField.setItemLabelGenerator(RepairEstimateCatalogNode::getDisplayName);
    }
}
