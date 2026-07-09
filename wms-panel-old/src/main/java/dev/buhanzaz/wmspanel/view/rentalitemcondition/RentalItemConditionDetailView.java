package dev.buhanzaz.wmspanel.view.rentalitemcondition;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalItemCondition;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "rental-item-conditions/:id", layout = MainView.class)
@ViewController(id = "RentalItemCondition.detail")
@ViewDescriptor(path = "rental-item-condition-detail-view.xml")
@EditedEntityContainer("rentalItemConditionDc")
public class RentalItemConditionDetailView extends StandardDetailView<RentalItemCondition> {
}
