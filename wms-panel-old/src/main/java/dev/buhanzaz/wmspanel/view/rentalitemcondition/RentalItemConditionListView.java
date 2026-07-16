package dev.buhanzaz.wmspanel.view.rentalitemcondition;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalItemCondition;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "rental-item-conditions", layout = MainView.class)
@ViewController(id = "RentalItemCondition.list")
@ViewDescriptor(path = "rental-item-condition-list-view.xml")
@LookupComponent("rentalItemConditionsDataGrid")
@DialogMode(width = "64em")
public class RentalItemConditionListView extends StandardListView<RentalItemCondition> {
}
