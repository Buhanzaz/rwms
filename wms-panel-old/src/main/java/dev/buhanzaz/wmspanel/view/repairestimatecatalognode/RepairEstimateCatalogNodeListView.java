package dev.buhanzaz.wmspanel.view.repairestimatecatalognode;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "repair-estimate-catalog-nodes", layout = MainView.class)
@ViewController(id = "RepairEstimateCatalogNode.list")
@ViewDescriptor(path = "repair-estimate-catalog-node-list-view.xml")
public class RepairEstimateCatalogNodeListView extends StandardListView<RepairEstimateCatalogNode> {
}
