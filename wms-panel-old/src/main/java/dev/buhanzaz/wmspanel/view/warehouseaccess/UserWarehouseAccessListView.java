package dev.buhanzaz.wmspanel.view.warehouseaccess;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.UserWarehouseAccess;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "warehouse-accesses", layout = MainView.class)
@ViewController(id = "UserWarehouseAccess.list")
@ViewDescriptor(path = "user-warehouse-access-list-view.xml")
@LookupComponent("userWarehouseAccessesDataGrid")
@DialogMode(width = "64em")
public class UserWarehouseAccessListView extends StandardListView<UserWarehouseAccess> {
}
