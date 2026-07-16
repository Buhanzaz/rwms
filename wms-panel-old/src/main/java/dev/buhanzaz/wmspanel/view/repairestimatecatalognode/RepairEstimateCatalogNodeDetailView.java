package dev.buhanzaz.wmspanel.view.repairestimatecatalognode;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "repair-estimate-catalog-nodes/:id", layout = MainView.class)
@ViewController(id = "RepairEstimateCatalogNode.detail")
@ViewDescriptor(path = "repair-estimate-catalog-node-detail-view.xml")
@EditedEntityContainer("repairEstimateCatalogNodeDc")
public class RepairEstimateCatalogNodeDetailView extends StandardDetailView<RepairEstimateCatalogNode> {

    @ViewComponent
    private EntityComboBox<RepairEstimateCatalogNode> parentField;
    @ViewComponent
    private EntityComboBox<WorkQueue> workQueueField;

    @Subscribe
    public void onInit(final InitEvent event) {
        parentField.setItemLabelGenerator(RepairEstimateCatalogNode::getDisplayName);
        workQueueField.setItemLabelGenerator(queue -> queue == null || queue.getName() == null ? "" : queue.getName());
    }
}
