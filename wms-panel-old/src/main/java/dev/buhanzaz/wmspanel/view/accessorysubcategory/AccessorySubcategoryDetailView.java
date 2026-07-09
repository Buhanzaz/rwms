package dev.buhanzaz.wmspanel.view.accessorysubcategory;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.AccessoryCategory;
import dev.buhanzaz.wmspanel.entity.AccessorySubcategory;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "accessory-subcategories/:id", layout = MainView.class)
@ViewController(id = "AccessorySubcategory.detail")
@ViewDescriptor(path = "accessory-subcategory-detail-view.xml")
@EditedEntityContainer("accessorySubcategoryDc")
public class AccessorySubcategoryDetailView extends StandardDetailView<AccessorySubcategory> {

    @ViewComponent
    private EntityComboBox<AccessoryCategory> categoryField;

    @Subscribe
    public void onInit(final InitEvent event) {
        categoryField.setItemLabelGenerator(AccessoryCategory::getName);
    }
}
