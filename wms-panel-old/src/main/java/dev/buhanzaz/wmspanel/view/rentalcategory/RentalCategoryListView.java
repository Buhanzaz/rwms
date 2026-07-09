package dev.buhanzaz.wmspanel.view.rentalcategory;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "rental-categories", layout = MainView.class)
@ViewController(id = "RentalCategory.list")
@ViewDescriptor(path = "rental-category-list-view.xml")
@LookupComponent("rentalCategoriesDataGrid")
@DialogMode(width = "64em")
public class RentalCategoryListView extends StandardListView<RentalCategory> {
}
