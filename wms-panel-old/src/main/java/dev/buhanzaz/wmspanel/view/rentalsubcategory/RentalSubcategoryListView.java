package dev.buhanzaz.wmspanel.view.rentalsubcategory;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalSubcategory;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "rental-subcategories", layout = MainView.class)
@ViewController(id = "RentalSubcategory.list")
@ViewDescriptor(path = "rental-subcategory-list-view.xml")
@LookupComponent("rentalSubcategoriesDataGrid")
@DialogMode(width = "64em")
public class RentalSubcategoryListView extends StandardListView<RentalSubcategory> {
}
