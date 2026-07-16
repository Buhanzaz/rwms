package dev.buhanzaz.wmspanel.view.repairestimatecataloglink;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogLink;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "repair-estimate-catalog-links", layout = MainView.class)
@ViewController(id = "RepairEstimateCatalogLink.list")
@ViewDescriptor(path = "repair-estimate-catalog-link-list-view.xml")
public class RepairEstimateCatalogLinkListView extends StandardListView<RepairEstimateCatalogLink> {
}
