package dev.buhanzaz.wmspanel.view.rentalattributeoption;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalAttributeOption;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "rental-attribute-options/:id", layout = MainView.class)
@ViewController(id = "RentalAttributeOption.detail")
@ViewDescriptor(path = "rental-attribute-option-detail-view.xml")
@EditedEntityContainer("rentalAttributeOptionDc")
public class RentalAttributeOptionDetailView extends StandardDetailView<RentalAttributeOption> {
}
