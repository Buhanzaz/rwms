package dev.buhanzaz.wmspanel.view.warehouseaccess;

import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.User;
import dev.buhanzaz.wmspanel.entity.UserWarehouseAccess;
import dev.buhanzaz.wmspanel.entity.WarehouseAccessLevel;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.View.InitEvent;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "warehouse-accesses/:id", layout = MainView.class)
@ViewController(id = "UserWarehouseAccess.detail")
@ViewDescriptor(path = "user-warehouse-access-detail-view.xml")
@EditedEntityContainer("userWarehouseAccessDc")
public class UserWarehouseAccessDetailView extends StandardDetailView<UserWarehouseAccess> {

    @ViewComponent
    private EntityComboBox<User> userField;
    @ViewComponent
    private ComboBox<WarehouseAccessLevel> accessLevelField;

    @Subscribe
    public void onInit(final InitEvent event) {
        accessLevelField.setItems(WarehouseAccessLevel.values());
    }
}
