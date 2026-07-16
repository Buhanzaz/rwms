package dev.buhanzaz.wmspanel.view.rentalsubcategory;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalSubcategory;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "rental-subcategories/:id", layout = MainView.class)
@ViewController(id = "RentalSubcategory.detail")
@ViewDescriptor(path = "rental-subcategory-detail-view.xml")
@EditedEntityContainer("rentalSubcategoryDc")
public class RentalSubcategoryDetailView extends StandardDetailView<RentalSubcategory> {
}
