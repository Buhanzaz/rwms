package dev.buhanzaz.wmspanel.view.rentaltype;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalType;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "rental-types/:id", layout = MainView.class)
@ViewController(id = "RentalType.detail")
@ViewDescriptor(path = "rental-type-detail-view.xml")
@EditedEntityContainer("rentalTypeDc")
public class RentalTypeDetailView extends StandardDetailView<RentalType> {
}
