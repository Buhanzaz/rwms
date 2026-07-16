package dev.buhanzaz.wmspanel.view.accessoryitem;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.AccessoryItem;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "accessory-items", layout = MainView.class)
@ViewController(id = "AccessoryItem.list")
@ViewDescriptor(path = "accessory-item-list-view.xml")
public class AccessoryItemListView extends StandardListView<AccessoryItem> {
}
