package dev.buhanzaz.wmspanel.view.accessorycategory;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.AccessoryCategory;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "accessory-categories/:id", layout = MainView.class)
@ViewController(id = "AccessoryCategory.detail")
@ViewDescriptor(path = "accessory-category-detail-view.xml")
@EditedEntityContainer("accessoryCategoryDc")
public class AccessoryCategoryDetailView extends StandardDetailView<AccessoryCategory> {
}
