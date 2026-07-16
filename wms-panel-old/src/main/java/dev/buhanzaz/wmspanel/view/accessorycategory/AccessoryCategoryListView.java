package dev.buhanzaz.wmspanel.view.accessorycategory;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.AccessoryCategory;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "accessory-categories", layout = MainView.class)
@ViewController(id = "AccessoryCategory.list")
@ViewDescriptor(path = "accessory-category-list-view.xml")
public class AccessoryCategoryListView extends StandardListView<AccessoryCategory> {
}
