package dev.buhanzaz.wmspanel.view.accessorysubcategory;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.AccessorySubcategory;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "accessory-subcategories", layout = MainView.class)
@ViewController(id = "AccessorySubcategory.list")
@ViewDescriptor(path = "accessory-subcategory-list-view.xml")
public class AccessorySubcategoryListView extends StandardListView<AccessorySubcategory> {
}
