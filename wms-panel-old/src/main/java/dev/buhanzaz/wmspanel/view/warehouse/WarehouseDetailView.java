package dev.buhanzaz.wmspanel.view.warehouse;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "warehouses/:id", layout = MainView.class)
@ViewController(id = "Warehouse.detail")
@ViewDescriptor(path = "warehouse-detail-view.xml")
@EditedEntityContainer("warehouseDc")
public class WarehouseDetailView extends StandardDetailView<Warehouse> {
}
