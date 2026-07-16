package dev.buhanzaz.wmspanel.view.rentalcategory;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "rental-categories/:id", layout = MainView.class)
@ViewController(id = "RentalCategory.detail")
@ViewDescriptor(path = "rental-category-detail-view.xml")
@EditedEntityContainer("rentalCategoryDc")
public class RentalCategoryDetailView extends StandardDetailView<RentalCategory> {
}
